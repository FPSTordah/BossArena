package com.bossarena.spawn;

import com.bossarena.BossArenaConfig;
import com.bossarena.data.Arena;
import com.bossarena.data.ArenaRegistry;
import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.bossarena.loot.BossLootHandler;
import com.bossarena.system.BossTrackingSystem;
import com.bossarena.system.BossWaveNotificationService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class BossTimedSpawnScheduler {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int PERSISTENCE_VERSION = 1;
    private static final long SCHEDULER_TICK_SECONDS = 5L;
    private static final long WORLD_LOOKUP_RETRY_MINUTES = 1L;

    private static final class PersistedState {
        int version = PERSISTENCE_VERSION;
        List<PersistedRuleState> rules = new ArrayList<>();
    }

    private static final class PersistedRuleState {
        String label;
        long nextSpawnEpochMs;
    }

    private final BossSpawnService bossSpawnService;
    private final BossTrackingSystem trackingSystem;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BossArena-TimedSpawns");
                t.setDaemon(true);
                return t;
            });

    private final Object persistenceLock = new Object();
    private volatile Path persistencePath;
    private volatile Map<String, Long> persistedNextSpawnByLabel = Map.of();

    private volatile List<TimedSpawnState> states = List.of();
    private volatile boolean started;

    public BossTimedSpawnScheduler(BossSpawnService bossSpawnService, BossTrackingSystem trackingSystem) {
        this.bossSpawnService = bossSpawnService;
        this.trackingSystem = trackingSystem;
    }

    public synchronized void initializePersistence(Path stateFilePath) {
        this.persistencePath = stateFilePath;
        if (stateFilePath == null) {
            this.persistedNextSpawnByLabel = Map.of();
            return;
        }

        try {
            Path parent = stateFilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to create timed scheduler persistence directory: " + e.getMessage());
        }

        this.persistedNextSpawnByLabel = loadPersistedNextSpawnMap();
    }

    public void flushPersistence() {
        persistState();
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        executor.scheduleAtFixedRate(this::tickSafely, SCHEDULER_TICK_SECONDS, SCHEDULER_TICK_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized void shutdown() {
        flushPersistence();
        started = false;
        executor.shutdownNow();
        states = List.of();
    }

    public void reloadFromConfig(BossArenaConfig config) {
        long now = System.currentTimeMillis();
        Map<String, Long> persistedMap = persistedNextSpawnByLabel;
        List<TimedSpawnState> rebuilt = new ArrayList<>();

        if (config != null) {
            List<BossArenaConfig.TimedBossSpawn> configured = config.getTimedBossSpawns();
            int index = 0;
            for (BossArenaConfig.TimedBossSpawn rule : configured) {
                index++;
                if (rule == null || !rule.enabled) {
                    continue;
                }
                BossArenaConfig.TimedBossSpawn snapshot = copyRule(rule);
                long intervalMs = minutesToMillis(resolveSpawnIntervalMinutes(snapshot));
                String label = resolveRuleLabel(snapshot, index);
                long firstAt = sanitizeNextSpawnEpoch(persistedMap.get(label), now, intervalMs);
                rebuilt.add(new TimedSpawnState(snapshot, firstAt, label));
            }
        }

        states = rebuilt;
        persistState();
        LOGGER.info("Timed boss scheduler reloaded: " + rebuilt.size() + " active rule(s).");
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Exception e) {
            LOGGER.warning("Timed boss scheduler tick failed: " + e.getMessage());
        }
    }

    private void tick() {
        trackingSystem.retryPendingRestore();

        List<TimedSpawnState> current = states;
        if (current.isEmpty()) {
            return;
        }

        boolean changed = false;
        long now = System.currentTimeMillis();
        for (TimedSpawnState state : current) {
            if (state == null || state.rule == null) {
                continue;
            }
            enforceTimedDespawn(state, now);
            if (now < state.nextSpawnEpochMs) {
                continue;
            }
            if (evaluateSpawn(state, now)) {
                changed = true;
            }
        }

        if (changed) {
            persistState();
        }
    }

    private boolean evaluateSpawn(TimedSpawnState state, long now) {
        BossArenaConfig.TimedBossSpawn rule = state.rule;
        long intervalMs = minutesToMillis(resolveSpawnIntervalMinutes(rule));
        state.nextSpawnEpochMs = now + intervalMs;

        if (rule.preventDuplicateWhileAlive && hasAliveBossForRule(rule)) {
            LOGGER.info("Timed spawn skipped for '" + state.label + "' because a matching boss is already alive.");
            return true;
        }

        String configuredBossId = optional(rule.bossId);
        String configuredArenaId = optional(rule.arenaId);
        if (configuredBossId.isEmpty() || configuredArenaId.isEmpty()) {
            LOGGER.warning("Timed spawn rule '" + state.label + "' is missing bossId or arenaId.");
            return true;
        }

        BossDefinition def = BossRegistry.get(configuredBossId);
        if (def == null) {
            LOGGER.warning("Timed spawn rule '" + state.label + "' references unknown bossId '" + configuredBossId + "'.");
            return true;
        }

        Arena arena = ArenaRegistry.get(configuredArenaId);
        if (arena == null) {
            LOGGER.warning("Timed spawn rule '" + state.label + "' references missing arena '" + configuredArenaId + "'.");
            return true;
        }

        World world = resolveWorld(arena.worldName);
        if (world == null) {
            state.nextSpawnEpochMs = now + minutesToMillis(WORLD_LOOKUP_RETRY_MINUTES);
            LOGGER.warning("Timed spawn rule '" + state.label + "' could not resolve world '" + arena.worldName + "'. Retrying soon.");
            return true;
        }

        world.execute(() -> {
            long timedDespawnMinutes = resolveDespawnMinutes(rule);
            UUID result = bossSpawnService.spawnBossFromJson(
                    null,
                    configuredBossId,
                    world,
                    arena.getPosition(),
                    arena.arenaId,
                    timedDespawnMinutes
            );
            if (result == null) {
                LOGGER.warning("Timed spawn failed for rule '" + state.label + "'.");
                return;
            }
            BossWaveNotificationService.notifyTimedSpawn(
                    resolveExpectedBossName(configuredBossId),
                    configuredArenaId,
                    world,
                    rule.worldAnnouncementText,
                    rule.announceWorldWide,
                    rule.announceCurrentWorld
            );
            if (BossSpawnService.DEFERRED_SPAWN_UUID.equals(result)) {
                LOGGER.info("Timed spawn sequence started for rule '" + state.label + "'. Boss will spawn after pre-boss waves.");
            } else {
                LOGGER.info("Timed spawn created boss '" + configuredBossId + "' for rule '" + state.label + "' (uuid=" + result + ").");
            }
        });
        return true;
    }

    private void enforceTimedDespawn(TimedSpawnState state, long now) {
        BossArenaConfig.TimedBossSpawn rule = state.rule;
        long despawnMinutes = resolveDespawnMinutes(rule);
        if (despawnMinutes <= 0L) {
            return;
        }
        long maxAgeMs = minutesToMillis(despawnMinutes);

        for (Map.Entry<UUID, BossTrackingSystem.BossData> entry : trackingSystem.snapshotTrackedBosses().entrySet()) {
            UUID bossUuid = entry.getKey();
            BossTrackingSystem.BossData data = entry.getValue();
            if (!matchesRule(data, rule)) {
                continue;
            }
            long spawnedAt = Math.max(0L, data.spawnedAtEpochMs);
            if (spawnedAt <= 0L || (now - spawnedAt) < maxAgeMs) {
                continue;
            }
            timedDespawnBoss(state.label, bossUuid, data);
        }
    }

    private void timedDespawnBoss(String ruleLabel, UUID bossUuid, BossTrackingSystem.BossData data) {
        if (bossUuid == null || data == null) {
            return;
        }
        World world = data.world;
        Set<UUID> addUuids = trackingSystem.snapshotAddsForBoss(bossUuid);
        BossTrackingSystem.PendingLootData pendingLoot = trackingSystem.markBossDead(bossUuid);
        for (UUID addUuid : addUuids) {
            BossTrackingSystem.PendingLootData addPending = trackingSystem.handleTrackedAddDeath(addUuid);
            if (pendingLoot == null) {
                pendingLoot = addPending;
            }
        }

        if (world != null) {
            world.execute(() -> {
                removeEntity(world, bossUuid);
                for (UUID addUuid : addUuids) {
                    removeEntity(world, addUuid);
                }
            });
            BossWaveNotificationService.notifyBossAliveStatus(
                    world,
                    data.spawnLocation,
                    data.bossName,
                    0,
                    0,
                    "Timed despawn",
                    0L
            );
            if (pendingLoot != null) {
                BossLootHandler.queueLootSpawn(pendingLoot.world, pendingLoot.spawnLocation, pendingLoot.bossName);
            }
        }

        LOGGER.info("Timed despawn removed boss '" + data.bossName + "' via rule '" + ruleLabel + "'.");
    }

    private static void removeEntity(World world, UUID entityUuid) {
        if (world == null || entityUuid == null) {
            return;
        }
        try {
            var ref = world.getEntityRef(entityUuid);
            if (ref == null || !ref.isValid()) {
                return;
            }
            world.getEntityStore().getStore().removeEntity(ref, RemoveReason.REMOVE);
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
    }

    private boolean hasAliveBossForRule(BossArenaConfig.TimedBossSpawn rule) {
        for (BossTrackingSystem.BossData data : trackingSystem.snapshotTrackedBosses().values()) {
            if (matchesRule(data, rule)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRule(BossTrackingSystem.BossData data, BossArenaConfig.TimedBossSpawn rule) {
        if (data == null || rule == null) {
            return false;
        }

        String expectedArenaId = optional(rule.arenaId);
        if (!expectedArenaId.isEmpty()) {
            String trackedArenaId = optional(data.arenaId);
            if (!trackedArenaId.equalsIgnoreCase(expectedArenaId)) {
                return false;
            }
        }

        String expectedBossName = resolveExpectedBossName(rule.bossId);
        if (expectedBossName.isEmpty()) {
            return false;
        }
        return optional(data.bossName).equalsIgnoreCase(expectedBossName);
    }

    private static String resolveExpectedBossName(String configuredBossId) {
        String normalized = optional(configuredBossId);
        if (normalized.isEmpty()) {
            return "";
        }
        BossDefinition def = BossRegistry.get(normalized);
        if (def != null && def.bossName != null && !def.bossName.isBlank()) {
            return def.bossName.trim();
        }
        return normalized;
    }

    private static World resolveWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        return universe.getWorld(worldName);
    }

    private static long resolveSpawnIntervalMinutes(BossArenaConfig.TimedBossSpawn rule) {
        long total = BossArenaConfig.resolveMinutes(rule.spawnIntervalHours, rule.spawnIntervalMinutes);
        return Math.max(1L, total);
    }

    private static long resolveDespawnMinutes(BossArenaConfig.TimedBossSpawn rule) {
        // Explicit contract: 0h 0m means infinite lifetime (no forced despawn).
        if (rule == null || (rule.despawnAfterHours <= 0L && rule.despawnAfterMinutes <= 0L)) {
            return 0L;
        }
        return Math.max(0L, BossArenaConfig.resolveMinutes(rule.despawnAfterHours, rule.despawnAfterMinutes));
    }

    private static long minutesToMillis(long minutes) {
        return TimeUnit.MINUTES.toMillis(Math.max(0L, minutes));
    }

    private static String resolveRuleLabel(BossArenaConfig.TimedBossSpawn rule, int index) {
        String id = optional(rule.id);
        if (!id.isEmpty()) {
            return id;
        }
        String boss = optional(rule.bossId);
        String arena = optional(rule.arenaId);
        if (!boss.isEmpty() || !arena.isEmpty()) {
            return boss + "@" + arena;
        }
        return "rule_" + index;
    }

    private static BossArenaConfig.TimedBossSpawn copyRule(BossArenaConfig.TimedBossSpawn source) {
        BossArenaConfig.TimedBossSpawn out = new BossArenaConfig.TimedBossSpawn();
        out.id = source.id;
        out.enabled = source.enabled;
        out.bossId = source.bossId;
        out.arenaId = source.arenaId;
        out.spawnIntervalHours = source.spawnIntervalHours;
        out.spawnIntervalMinutes = source.spawnIntervalMinutes;
        out.preventDuplicateWhileAlive = source.preventDuplicateWhileAlive;
        out.despawnAfterHours = source.despawnAfterHours;
        out.despawnAfterMinutes = source.despawnAfterMinutes;
        out.announceWorldWide = source.announceWorldWide;
        out.announceCurrentWorld = source.announceCurrentWorld;
        out.worldAnnouncementText = source.worldAnnouncementText;
        return out;
    }

    private void persistState() {
        Path path = persistencePath;
        if (path == null) {
            return;
        }

        synchronized (persistenceLock) {
            PersistedState state = new PersistedState();
            Map<String, Long> nextByLabel = new HashMap<>();
            for (TimedSpawnState timedState : states) {
                if (timedState == null || timedState.label == null || timedState.label.isBlank()) {
                    continue;
                }
                PersistedRuleState row = new PersistedRuleState();
                row.label = timedState.label;
                row.nextSpawnEpochMs = Math.max(0L, timedState.nextSpawnEpochMs);
                state.rules.add(row);
                nextByLabel.put(row.label, row.nextSpawnEpochMs);
            }

            String json = GSON.toJson(state);
            Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            try {
                Files.writeString(temp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                persistedNextSpawnByLabel = nextByLabel;
            } catch (IOException e) {
                LOGGER.warning("Failed to persist timed spawn state: " + e.getMessage());
            }
        }
    }

    private Map<String, Long> loadPersistedNextSpawnMap() {
        Path path = persistencePath;
        if (path == null || Files.notExists(path)) {
            return Map.of();
        }

        synchronized (persistenceLock) {
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                PersistedState state = GSON.fromJson(raw, PersistedState.class);
                if (state == null || state.version != PERSISTENCE_VERSION || state.rules == null) {
                    return Map.of();
                }

                Map<String, Long> out = new HashMap<>();
                for (PersistedRuleState row : state.rules) {
                    if (row == null) {
                        continue;
                    }
                    String label = optional(row.label).toLowerCase(Locale.ROOT);
                    if (label.isEmpty()) {
                        continue;
                    }
                    out.put(label, Math.max(0L, row.nextSpawnEpochMs));
                }
                return out;
            } catch (Exception e) {
                LOGGER.warning("Failed to load timed spawn state: " + e.getMessage());
                return Map.of();
            }
        }
    }

    private static long sanitizeNextSpawnEpoch(Long storedEpochMs, long now, long intervalMs) {
        if (storedEpochMs == null || storedEpochMs <= 0L) {
            return now + Math.max(1L, intervalMs);
        }
        return storedEpochMs;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class TimedSpawnState {
        private final BossArenaConfig.TimedBossSpawn rule;
        private final String label;
        private volatile long nextSpawnEpochMs;

        private TimedSpawnState(BossArenaConfig.TimedBossSpawn rule, long nextSpawnEpochMs, String label) {
            this.rule = rule;
            this.label = label == null || label.isBlank() ? "timed_spawn" : label.toLowerCase(Locale.ROOT);
            this.nextSpawnEpochMs = Math.max(0L, nextSpawnEpochMs);
        }
    }
}
