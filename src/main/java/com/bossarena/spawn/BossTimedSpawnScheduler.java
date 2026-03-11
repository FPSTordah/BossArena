package com.bossarena.spawn;

import com.bossarena.BossArenaConfig;
import com.bossarena.data.Arena;
import com.bossarena.data.ArenaRegistry;
import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.bossarena.system.BossTrackingSystem;
import com.bossarena.system.BossWaveNotificationService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.math.vector.Vector3d;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long PENDING_SPAWN_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10L);
    /** If no matching boss is alive and pending is older than this, treat as stale and clear (boss died/crate gone). */
    private static final long STALE_PENDING_WHEN_NO_BOSS_MS = TimeUnit.MINUTES.toMillis(2L);
    // When no players are online in the target world, defer timed spawns and retry soon.
    private static final long NO_PLAYER_RETRY_SECONDS = 30L;
    /** When waiting for a player within proximity radius, re-check this often so walking into range triggers quickly. */
    private static final long PROXIMITY_RETRY_SECONDS = 10L;
    /** Cooldown between proximity-triggered spawns for the same boss/arena combination. */
    private static final long PROXIMITY_RESPAWN_COOLDOWN_SECONDS = 60L;
    private final BossSpawnService bossSpawnService;
    private final BossTrackingSystem trackingSystem;
    private final Map<String, PendingSpawnState> pendingSpawnByKey = new ConcurrentHashMap<>();
    /** Last time (epoch ms) a proximity-based spawn fired for a given boss/arena key. */
    private final Map<String, Long> lastProximitySpawnByKey = new ConcurrentHashMap<>();
    private final Set<UUID> spawnedTimedBossUuids = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BossArena-TimedSpawns");
                t.setDaemon(true);
                return t;
            });
    private final Object persistenceLock = new Object();
    private TimedBossMapMarkerService mapMarkerService;
    private volatile Path persistencePath;
    private volatile Map<String, Long> persistedNextSpawnByLabel = Map.of();
    private volatile List<TimedSpawnState> states = List.of();
    private volatile boolean started;

    public BossTimedSpawnScheduler(BossSpawnService bossSpawnService, BossTrackingSystem trackingSystem) {
        this.bossSpawnService = bossSpawnService;
        this.trackingSystem = trackingSystem;
    }

    private static void removeEntity(World world, UUID entityUuid) {
        if (world == null || entityUuid == null) {
            return;
        }
        if (!world.isInThread()) {
            world.execute(() -> removeEntity(world, entityUuid));
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

    private static String resolveSpawnKey(BossArenaConfig.TimedBossSpawn rule) {
        if (rule == null) {
            return "";
        }
        String boss = optional(rule.bossId).toLowerCase(Locale.ROOT);
        String arena = optional(rule.arenaId).toLowerCase(Locale.ROOT);
        if (boss.isEmpty() || arena.isEmpty()) {
            return "";
        }
        return boss + "@" + arena;
    }

    private static World resolveWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        World w = universe.getWorld(worldName);
        if (w != null) {
            return w;
        }
        for (World candidate : universe.getWorlds().values()) {
            if (candidate != null && worldName.equalsIgnoreCase(candidate.getName())) {
                return candidate;
            }
        }
        return null;
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

    private static long sanitizeNextSpawnEpoch(Long storedEpochMs, long now, long intervalMs) {
        if (storedEpochMs == null || storedEpochMs <= 0L) {
            return now + Math.max(1L, intervalMs);
        }
        return storedEpochMs;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    public void setMapMarkerService(TimedBossMapMarkerService mapMarkerService) {
        this.mapMarkerService = mapMarkerService;
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
        pendingSpawnByKey.clear();
        spawnedTimedBossUuids.clear();
        states = List.of();
    }

    public void reloadFromConfig(BossArenaConfig config) {
        long now = System.currentTimeMillis();
        Map<String, Long> persistedMap = persistedNextSpawnByLabel;
        List<TimedSpawnState> rebuilt = new ArrayList<>();
        Set<String> validPendingKeys = new HashSet<>();

        if (config != null) {
            List<BossArenaConfig.TimedBossSpawn> configured = config.getTimedBossSpawns();
            int index = 0;
            for (BossArenaConfig.TimedBossSpawn rule : configured) {
                index++;
                if (rule == null || !rule.enabled) {
                    continue;
                }
                BossArenaConfig.TimedBossSpawn snapshot = copyRule(rule);

                // If the referenced boss is configured for proximity spawning, ignore this timed rule.
                String bossId = optional(snapshot.bossId);
                if (!bossId.isEmpty()) {
                    BossDefinition def = BossRegistry.get(bossId);
                    if (def != null && def.extraMobs != null) {
                        def.extraMobs.sanitize();
                        if (def.extraMobs.timedProximityEnabled && def.extraMobs.getTimedProximityRadius() > 0.0d) {
                            String skippedLabel = resolveRuleLabel(snapshot, index);
                            LOGGER.info("Timed spawn rule '" + skippedLabel + "' skipped because boss '" + bossId
                                    + "' has proximity spawning enabled.");
                            continue;
                        }
                    }
                }

                long intervalMs = minutesToMillis(resolveSpawnIntervalMinutes(snapshot));
                String label = resolveRuleLabel(snapshot, index);
                long firstAt = sanitizeNextSpawnEpoch(persistedMap.get(label), now, intervalMs);
                rebuilt.add(new TimedSpawnState(snapshot, firstAt, label));
                String spawnKey = resolveSpawnKey(snapshot);
                if (!spawnKey.isEmpty()) {
                    validPendingKeys.add(spawnKey);
                }
            }
        }

        states = rebuilt;
        pendingSpawnByKey.keySet().retainAll(validPendingKeys);
        rebuildTrackedTimedBossCache(rebuilt);
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
        boolean changed = false;
        long now = System.currentTimeMillis();
        pruneExpiredPendingSpawns(now);
        if (!current.isEmpty()) {
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
        }

        // Also evaluate pure proximity-based spawns (no timed rule required).
        if (evaluateProximitySpawns(now)) {
            changed = true;
        }

        if (changed) {
            persistState();
        }
    }

    private boolean evaluateSpawn(TimedSpawnState state, long now) {
        BossArenaConfig.TimedBossSpawn rule = state.rule;
        long intervalMs = minutesToMillis(resolveSpawnIntervalMinutes(rule));

        if (rule.preventDuplicateWhileAlive) {
            if (hasAliveBossForRule(rule)) {
                clearPendingSpawnForRule(rule);
                LOGGER.info("Timed spawn skipped for '" + state.label + "' because a matching boss is already alive.");
                state.nextSpawnEpochMs = now + intervalMs;
                return true;
            }
            // No matching boss alive: clear stale pending so we don't block forever after boss/crate gone
            clearStalePendingForRuleIfNoAliveBoss(rule, now);
            if (isSpawnPendingForRule(rule, now)) {
                LOGGER.info("Timed spawn skipped for '" + state.label + "' because a matching spawn is already pending.");
                state.nextSpawnEpochMs = now + intervalMs;
                return true;
            }
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
            state.nextSpawnEpochMs = now + intervalMs;
            return true;
        }

        World world = resolveWorld(arena.worldName);
        if (world == null) {
            state.nextSpawnEpochMs = now + minutesToMillis(WORLD_LOOKUP_RETRY_MINUTES);
            LOGGER.warning("Timed spawn rule '" + state.label + "' could not resolve world '" + arena.worldName + "'. Retrying soon.");
            return true;
        }

        // If there are no players online in the target world, defer this spawn until players are present.
        if (!hasAnyOnlinePlayer(world)) {
            state.nextSpawnEpochMs = now + TimeUnit.SECONDS.toMillis(NO_PLAYER_RETRY_SECONDS);
            LOGGER.info("Timed spawn for '" + state.label + "' deferred because no players are online in world '"
                    + world.getName() + "'.");
            return true;
        }

        // Per-boss proximity: if enabled on the boss, wait until at least one player is within the
        // configured radius of the chosen proximity arena center before spawning. Re-fetch from registry so we see latest save.
        BossDefinition latestDef = BossRegistry.get(configuredBossId);
        if (latestDef != null) {
            def = latestDef;
            if (def.extraMobs != null) def.extraMobs.sanitize();
        }
        boolean proximityEnabled = def.extraMobs != null && def.extraMobs.timedProximityEnabled;
        double proximityRadius = def.extraMobs != null ? def.extraMobs.getTimedProximityRadius() : 0.0d;
        String configuredProximityArenaId = (def.extraMobs != null && def.extraMobs.timedProximityArenaId != null)
                ? def.extraMobs.timedProximityArenaId.trim()
                : "";
        if (proximityEnabled && proximityRadius > 0.0d) {
            String proximityArenaId = !configuredProximityArenaId.isEmpty() ? configuredProximityArenaId : configuredArenaId;
            Arena proximityArena = ArenaRegistry.get(proximityArenaId);
            if (proximityArena != null) {
                World proximityWorld = resolveWorld(proximityArena.worldName);
                if (proximityWorld == null) {
                    state.nextSpawnEpochMs = now + TimeUnit.SECONDS.toMillis(PROXIMITY_RETRY_SECONDS);
                    LOGGER.warning("Timed spawn '" + state.label + "' proximity arena '" + proximityArenaId + "' world '" + proximityArena.worldName + "' not loaded. Retrying in " + PROXIMITY_RETRY_SECONDS + "s.");
                    return true;
                }
                Vector3d center = proximityArena.getPosition();
                if (!hasPlayerWithinRadius(proximityWorld, center, proximityRadius)) {
                    state.nextSpawnEpochMs = now + TimeUnit.SECONDS.toMillis(PROXIMITY_RETRY_SECONDS);
                    double closest = closestPlayerDistance(proximityWorld, center);
                    LOGGER.info("Timed spawn '" + state.label + "' waiting for player within "
                            + proximityRadius + " blocks of arena '" + proximityArenaId + "' (center "
                            + String.format("%.0f, %.0f, %.0f", center.x, center.y, center.z) + " in " + proximityWorld.getName()
                            + "). Closest player: " + (closest < Double.MAX_VALUE ? String.format("%.0f blocks", closest) : "none") + ". Re-checking in " + PROXIMITY_RETRY_SECONDS + "s.");
                    return true;
                }
                LOGGER.info("Timed spawn '" + state.label + "': player in proximity of arena '" + proximityArenaId + "', spawning boss.");
            } else {
                LOGGER.warning("Timed spawn rule '" + state.label + "' has proximity enabled for boss '"
                        + configuredBossId + "' but arena '" + proximityArenaId + "' does not exist; spawning without proximity.");
            }
        }

        if (rule.preventDuplicateWhileAlive) {
            markSpawnPending(rule, state.label, now);
        }

        world.execute(() -> {
            long timedDespawnMinutes = resolveDespawnMinutes(rule);
            UUID result = bossSpawnService.spawnBossFromJson(
                    null,
                    configuredBossId,
                    world,
                    arena.getPosition(),
                    arena.arenaId,
                    timedDespawnMinutes,
                    uuid -> {
                        spawnedTimedBossUuids.add(uuid);
                        if (mapMarkerService != null) {
                            mapMarkerService.onTimedBossSpawn(world, uuid);
                        }
                        // Clear pending when boss actually spawns (critical for deferred spawns after pre-boss waves)
                        clearPendingSpawnForRule(rule);
                    }
            );
            if (result == null) {
                clearPendingSpawnForRule(rule);
                LOGGER.warning("Timed spawn failed for rule '" + state.label + "'.");
                return;
            }
            // Immediate spawn: clear now; deferred spawn: cleared in callback when boss spawns
            if (!BossSpawnService.DEFERRED_SPAWN_UUID.equals(result)) {
                clearPendingSpawnForRule(rule);
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
        state.nextSpawnEpochMs = now + intervalMs;
        return true;
    }

    private static boolean hasAnyOnlinePlayer(World world) {
        if (world == null) {
            return false;
        }
        try {
            for (var playerRef : world.getPlayerRefs()) {
                if (playerRef != null && playerRef.isValid()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // If this fails for any reason, fall back to treating as no players.
        }
        return false;
    }

    /** Returns distance of closest player to center, or Double.MAX_VALUE if no players. */
    private static double closestPlayerDistance(World world, Vector3d center) {
        if (world == null || center == null) {
            return Double.MAX_VALUE;
        }
        double minDistSq = Double.MAX_VALUE;
        try {
            for (var playerRef : world.getPlayerRefs()) {
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }
                var transform = playerRef.getTransform();
                if (transform == null) {
                    continue;
                }
                Vector3d pos = transform.getPosition();
                if (pos == null) {
                    continue;
                }
                double dx = pos.x - center.x;
                double dy = pos.y - center.y;
                double dz = pos.z - center.z;
                double distSq = (dx * dx) + (dy * dy) + (dz * dz);
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                }
            }
        } catch (Exception ignored) {
        }
        return minDistSq < Double.MAX_VALUE ? Math.sqrt(minDistSq) : Double.MAX_VALUE;
    }

    private static boolean hasPlayerWithinRadius(World world, Vector3d center, double radius) {
        if (world == null || center == null || radius <= 0.0d) {
            return false;
        }
        double radiusSq = radius * radius;
        try {
            for (var playerRef : world.getPlayerRefs()) {
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }
                var transform = playerRef.getTransform();
                if (transform == null) {
                    continue;
                }
                Vector3d pos = transform.getPosition();
                if (pos == null) {
                    continue;
                }
                double dx = pos.x - center.x;
                double dy = pos.y - center.y;
                double dz = pos.z - center.z;
                double distSq = (dx * dx) + (dy * dy) + (dz * dz);
                if (distSq <= radiusSq) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean evaluateProximitySpawns(long now) {
        boolean spawnedAny = false;

        for (BossDefinition def : BossRegistry.getAll().values()) {
            if (def == null || def.extraMobs == null) {
                continue;
            }
            def.extraMobs.sanitize();
            if (!def.extraMobs.timedProximityEnabled) {
                continue;
            }
            double radius = def.extraMobs.getTimedProximityRadius();
            if (radius <= 0.0d) {
                continue;
            }
            String arenaId = optional(def.extraMobs.timedProximityArenaId);
            if (arenaId.isEmpty()) {
                continue;
            }

            Arena arena = ArenaRegistry.get(arenaId);
            if (arena == null) {
                LOGGER.warning("Proximity spawn skipped for boss '" + def.bossName + "' because arena '" + arenaId + "' does not exist.");
                continue;
            }

            World world = resolveWorld(arena.worldName);
            if (world == null) {
                LOGGER.warning("Proximity spawn skipped for boss '" + def.bossName + "' because world '" + arena.worldName + "' is not loaded.");
                continue;
            }

            Vector3d center = arena.getPosition();
            if (!hasPlayerWithinRadius(world, center, radius)) {
                continue;
            }

            // Avoid spawning if there's already a boss alive in this arena.
            if (hasAliveBossInArena(arena.arenaId)) {
                continue;
            }

            String key = resolveProximitySpawnKey(def.bossName, arena.arenaId);
            long lastAt = lastProximitySpawnByKey.getOrDefault(key, 0L);
            long cooldownSeconds = def.extraMobs.getTimedProximityCooldownSeconds(PROXIMITY_RESPAWN_COOLDOWN_SECONDS);
            long cooldownMs = TimeUnit.SECONDS.toMillis(cooldownSeconds);
            if (lastAt > 0L && now - lastAt < cooldownMs) {
                continue;
            }

            String label = def.bossName + "@" + arena.arenaId;
            LOGGER.info("Proximity spawn '" + label + "': player in proximity of arena '" + arena.arenaId
                    + "' (center " + String.format("%.1f, %.1f, %.1f", center.x, center.y, center.z)
                    + " in " + world.getName() + "), radius " + radius + ".");

            world.execute(() -> {
                UUID result = bossSpawnService.spawnBossFromJson(
                        null,
                        def.bossName,
                        world,
                        center,
                        arena.arenaId,
                        0L,
                        uuid -> {
                            spawnedTimedBossUuids.add(uuid);
                            if (mapMarkerService != null) {
                                mapMarkerService.onTimedBossSpawn(world, uuid);
                            }
                        }
                );
                if (result == null) {
                    LOGGER.warning("Proximity spawn failed for boss '" + def.bossName + "' at arena '" + arena.arenaId + "'.");
                } else {
                    LOGGER.info("Proximity spawn created boss '" + def.bossName + "' (uuid=" + result + ") at arena '" + arena.arenaId + "'.");
                }
            });

            lastProximitySpawnByKey.put(key, now);
            spawnedAny = true;
        }

        return spawnedAny;
    }

    private boolean hasAliveBossInArena(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) {
            return false;
        }
        try {
            for (BossTrackingSystem.BossData data : trackingSystem.snapshotTrackedBosses().values()) {
                if (data == null || data.arenaId == null) {
                    continue;
                }
                if (arenaId.equalsIgnoreCase(data.arenaId)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String resolveProximitySpawnKey(String bossName, String arenaId) {
        String boss = optional(bossName);
        String arena = optional(arenaId);
        if (boss.isEmpty() || arena.isEmpty()) {
            return "";
        }
        return boss + "@" + arena;
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
        if (!trackingSystem.isTracked(bossUuid)) {
            return;
        }

        BossTrackingSystem.EventMembersSnapshot eventSnapshot = trackingSystem.snapshotEventMembersForBoss(bossUuid);
        Set<UUID> bossUuids = new HashSet<>();
        Set<UUID> addUuids = new HashSet<>();
        if (eventSnapshot != null) {
            bossUuids.addAll(eventSnapshot.bossUuids);
            addUuids.addAll(eventSnapshot.activeAddUuids);
        } else {
            bossUuids.add(bossUuid);
            addUuids.addAll(trackingSystem.snapshotAddsForBoss(bossUuid));
        }
        bossUuids.remove(null);
        addUuids.remove(null);
        if (bossUuids.isEmpty()) {
            bossUuids.add(bossUuid);
        }
        spawnedTimedBossUuids.removeAll(bossUuids);

        World world = eventSnapshot != null && eventSnapshot.world != null
                ? eventSnapshot.world
                : data.world;
        Vector3d notifyLocation = eventSnapshot != null && eventSnapshot.eventCenter != null
                ? eventSnapshot.eventCenter
                : data.spawnLocation;
        String notifyBossName = eventSnapshot != null && eventSnapshot.bossName != null && !eventSnapshot.bossName.isBlank()
                ? eventSnapshot.bossName
                : data.bossName;

        for (UUID addUuid : addUuids) {
            trackingSystem.handleTrackedAddDeath(addUuid);
        }
        for (UUID eventBossUuid : bossUuids) {
            trackingSystem.markBossDead(eventBossUuid);
        }

        if (world != null) {
            world.execute(() -> {
                for (UUID eventBossUuid : bossUuids) {
                    if (mapMarkerService != null) {
                        mapMarkerService.onTimedBossDespawn(world, eventBossUuid);
                    }
                    removeEntity(world, eventBossUuid);
                }
                for (UUID addUuid : addUuids) {
                    removeEntity(world, addUuid);
                }
            });
            BossWaveNotificationService.notifyBossAliveStatus(
                    world,
                    notifyLocation,
                    notifyBossName,
                    0,
                    0,
                    "Timed despawn",
                    0L,
                    false,
                    false
            );
        }

        LOGGER.info("Timed despawn removed event '" + notifyBossName + "' via rule '" + ruleLabel
                + "' (bosses=" + bossUuids.size() + ", adds=" + addUuids.size() + ").");
    }

    private boolean hasAliveBossForRule(BossArenaConfig.TimedBossSpawn rule) {
        for (BossTrackingSystem.BossData data : trackingSystem.snapshotTrackedBosses().values()) {
            if (matchesRule(data, rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSpawnPendingForRule(BossArenaConfig.TimedBossSpawn rule, long now) {
        String key = resolveSpawnKey(rule);
        if (key.isEmpty()) {
            return false;
        }
        PendingSpawnState pending = pendingSpawnByKey.get(key);
        if (pending == null) {
            return false;
        }
        if ((now - pending.startedAtEpochMs) > PENDING_SPAWN_TIMEOUT_MS) {
            if (pendingSpawnByKey.remove(key, pending)) {
                LOGGER.warning("Cleared stale pending timed spawn '" + pending.ruleLabel
                        + "' after timeout (" + PENDING_SPAWN_TIMEOUT_MS + "ms).");
            }
            return false;
        }
        return true;
    }

    private void markSpawnPending(BossArenaConfig.TimedBossSpawn rule, String label, long now) {
        String key = resolveSpawnKey(rule);
        if (key.isEmpty()) {
            return;
        }
        PendingSpawnState pending = new PendingSpawnState();
        pending.ruleLabel = optional(label);
        pending.startedAtEpochMs = now;
        pendingSpawnByKey.put(key, pending);
    }

    private void clearPendingSpawnForRule(BossArenaConfig.TimedBossSpawn rule) {
        String key = resolveSpawnKey(rule);
        if (key.isEmpty()) {
            return;
        }
        pendingSpawnByKey.remove(key);
    }

    /**
     * When no matching boss is alive but pending exists and is older than {@link #STALE_PENDING_WHEN_NO_BOSS_MS},
     * clear it so the next spawn can run (avoids "already pending" forever after boss/crate are gone).
     */
    private void clearStalePendingForRuleIfNoAliveBoss(BossArenaConfig.TimedBossSpawn rule, long now) {
        if (hasAliveBossForRule(rule)) {
            return;
        }
        String key = resolveSpawnKey(rule);
        if (key.isEmpty()) {
            return;
        }
        PendingSpawnState pending = pendingSpawnByKey.get(key);
        if (pending == null) {
            return;
        }
        if ((now - pending.startedAtEpochMs) < STALE_PENDING_WHEN_NO_BOSS_MS) {
            return;
        }
        if (pendingSpawnByKey.remove(key, pending)) {
            LOGGER.info("Cleared stale pending timed spawn '" + pending.ruleLabel
                    + "' (no matching boss alive for " + (STALE_PENDING_WHEN_NO_BOSS_MS / 60_000L) + " min).");
        }
    }

    private void pruneExpiredPendingSpawns(long now) {
        for (Map.Entry<String, PendingSpawnState> entry : pendingSpawnByKey.entrySet()) {
            PendingSpawnState pending = entry.getValue();
            if (pending == null) {
                pendingSpawnByKey.remove(entry.getKey());
                continue;
            }
            if ((now - pending.startedAtEpochMs) <= PENDING_SPAWN_TIMEOUT_MS) {
                continue;
            }
            if (pendingSpawnByKey.remove(entry.getKey(), pending)) {
                LOGGER.warning("Cleared stale pending timed spawn '" + pending.ruleLabel
                        + "' after timeout (" + PENDING_SPAWN_TIMEOUT_MS + "ms).");
            }
        }
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

    private void rebuildTrackedTimedBossCache(List<TimedSpawnState> activeStates) {
        if (activeStates == null || activeStates.isEmpty()) {
            spawnedTimedBossUuids.clear();
            return;
        }

        Set<UUID> refreshed = new HashSet<>();
        Map<UUID, BossTrackingSystem.BossData> tracked = trackingSystem.snapshotTrackedBosses();
        for (Map.Entry<UUID, BossTrackingSystem.BossData> entry : tracked.entrySet()) {
            UUID bossUuid = entry.getKey();
            BossTrackingSystem.BossData data = entry.getValue();
            if (bossUuid == null || data == null) {
                continue;
            }
            for (TimedSpawnState state : activeStates) {
                if (state == null || state.rule == null) {
                    continue;
                }
                if (!matchesRule(data, state.rule)) {
                    continue;
                }
                refreshed.add(bossUuid);
                break;
            }
        }
        spawnedTimedBossUuids.clear();
        spawnedTimedBossUuids.addAll(refreshed);
    }

    public Set<UUID> snapshotSpawnedTimedBossUuids() {
        return Set.copyOf(spawnedTimedBossUuids);
    }

    public void forgetSpawnedTimedBoss(UUID bossUuid) {
        if (bossUuid == null) {
            return;
        }
        spawnedTimedBossUuids.remove(bossUuid);
    }

    private static final class PersistedState {
        int version = PERSISTENCE_VERSION;
        List<PersistedRuleState> rules = new ArrayList<>();
    }

    private static final class PersistedRuleState {
        String label;
        long nextSpawnEpochMs;
    }

    private static final class PendingSpawnState {
        String ruleLabel;
        long startedAtEpochMs;
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
