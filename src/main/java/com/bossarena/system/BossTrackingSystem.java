package com.bossarena.system;

import com.bossarena.boss.BossModifiers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

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

public class BossTrackingSystem {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int PERSISTENCE_VERSION = 1;
    private static final long AUTOSAVE_PERIOD_SECONDS = 5L;
    private final Map<UUID, BossData> trackedBosses = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> trackedAddsByBoss = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> addToBoss = new ConcurrentHashMap<>();
    private final Map<UUID, BossModifiers> addModifiers = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> bossToEvent = new ConcurrentHashMap<>();
    private final Map<UUID, EventData> eventsById = new ConcurrentHashMap<>();
    private final Map<UUID, HeldChunk> heldChunkByEvent = new ConcurrentHashMap<>();
    private final Object chunkRetentionLock = new Object();
    private final Object persistenceLock = new Object();
    private volatile Path persistencePath;
    private volatile boolean persistenceDirty;
    private volatile PersistedState pendingRestoreState;
    private ScheduledExecutorService persistenceExecutor;

    private static long resolveChunkIndex(Vector3d location) {
        int blockX = (int) Math.floor(location.x);
        int blockZ = (int) Math.floor(location.z);
        return ChunkUtil.indexChunkFromBlock(blockX, blockZ);
    }

    private static boolean acquireChunkHold(HeldChunk hold) {
        if (hold == null || hold.world == null) {
            return false;
        }
        try {
            // Avoid synchronous chunk loads from tracking updates. Blocking load can
            // re-enter world task processing and invalidate freshly spawned refs.
            WorldChunk chunk = hold.world.getChunkIfInMemory(hold.chunkIndex);
            if (chunk == null) {
                return false;
            }
            chunk.addKeepLoaded();
            return true;
        } catch (Exception e) {
            LOGGER.fine("Failed to acquire keep-loaded chunk: " + e.getMessage());
            return false;
        }
    }

    private static void releaseChunkHold(HeldChunk hold) {
        if (hold == null || hold.world == null) {
            return;
        }
        try {
            WorldChunk chunk = hold.world.getChunkIfInMemory(hold.chunkIndex);
            if (chunk != null) {
                chunk.removeKeepLoaded();
            }
        } catch (Exception e) {
            LOGGER.fine("Failed to release keep-loaded chunk: " + e.getMessage());
        }
    }

    private static void mergePersistedState(PersistedState target, PersistedState pending) {
        if (target == null || !hasPendingEntries(pending)) {
            return;
        }

        Set<String> eventIds = new HashSet<>();
        for (PersistedEvent event : safeList(target.events)) {
            eventIds.add(normalizeKey(event != null ? event.eventId : null));
        }
        for (PersistedEvent event : safeList(pending.events)) {
            String key = normalizeKey(event != null ? event.eventId : null);
            if (key.isEmpty() || eventIds.contains(key)) {
                continue;
            }
            target.events.add(copyPersistedEvent(event));
            eventIds.add(key);
        }

        Set<String> bossIds = new HashSet<>();
        for (PersistedBoss boss : safeList(target.bosses)) {
            bossIds.add(normalizeKey(boss != null ? boss.uuid : null));
        }
        for (PersistedBoss boss : safeList(pending.bosses)) {
            String key = normalizeKey(boss != null ? boss.uuid : null);
            if (key.isEmpty() || bossIds.contains(key)) {
                continue;
            }
            target.bosses.add(copyPersistedBoss(boss));
            bossIds.add(key);
        }

        Set<String> addIds = new HashSet<>();
        for (PersistedAddLink add : safeList(target.addLinks)) {
            addIds.add(normalizeKey(add != null ? add.addUuid : null));
        }
        for (PersistedAddLink add : safeList(pending.addLinks)) {
            String key = normalizeKey(add != null ? add.addUuid : null);
            if (key.isEmpty() || addIds.contains(key)) {
                continue;
            }
            target.addLinks.add(copyPersistedAddLink(add));
            addIds.add(key);
        }
    }

    private static PersistedEvent copyPersistedEvent(PersistedEvent source) {
        PersistedEvent copy = new PersistedEvent();
        if (source == null) {
            return copy;
        }
        copy.eventId = source.eventId;
        copy.world = source.world;
        copy.centerX = source.centerX;
        copy.centerY = source.centerY;
        copy.centerZ = source.centerZ;
        copy.bossName = source.bossName;
        copy.bossTier = source.bossTier;
        copy.countdownDurationMs = source.countdownDurationMs;
        copy.countdownStartEpochMs = source.countdownStartEpochMs;
        copy.awaitingPrimaryBossSpawn = source.awaitingPrimaryBossSpawn;
        copy.bossUuids = source.bossUuids == null ? new ArrayList<>() : new ArrayList<>(source.bossUuids);
        copy.aliveBosses = source.aliveBosses == null ? new ArrayList<>() : new ArrayList<>(source.aliveBosses);
        copy.activeAdds = source.activeAdds == null ? new ArrayList<>() : new ArrayList<>(source.activeAdds);
        return copy;
    }

    private static PersistedBoss copyPersistedBoss(PersistedBoss source) {
        PersistedBoss copy = new PersistedBoss();
        if (source == null) {
            return copy;
        }
        copy.uuid = source.uuid;
        copy.eventId = source.eventId;
        copy.bossName = source.bossName;
        copy.arenaId = source.arenaId;
        copy.world = source.world;
        copy.spawnX = source.spawnX;
        copy.spawnY = source.spawnY;
        copy.spawnZ = source.spawnZ;
        copy.bossTier = source.bossTier;
        copy.levelOverride = source.levelOverride;
        copy.spawnedAtEpochMs = source.spawnedAtEpochMs;
        copy.hpMultiplier = source.hpMultiplier;
        copy.damageMultiplier = source.damageMultiplier;
        copy.speedMultiplier = source.speedMultiplier;
        copy.scaleMultiplier = source.scaleMultiplier;
        copy.attackRateMultiplier = source.attackRateMultiplier;
        copy.abilityCooldownMultiplier = source.abilityCooldownMultiplier;
        copy.knockbackGivenMultiplier = source.knockbackGivenMultiplier;
        copy.knockbackTakenMultiplier = source.knockbackTakenMultiplier;
        copy.turnRateMultiplier = source.turnRateMultiplier;
        copy.regenMultiplier = source.regenMultiplier;
        return copy;
    }

    private static PersistedAddLink copyPersistedAddLink(PersistedAddLink source) {
        PersistedAddLink copy = new PersistedAddLink();
        if (source == null) {
            return copy;
        }
        copy.addUuid = source.addUuid;
        copy.bossUuid = source.bossUuid;
        copy.hpMultiplier = source.hpMultiplier;
        copy.damageMultiplier = source.damageMultiplier;
        copy.speedMultiplier = source.speedMultiplier;
        copy.scaleMultiplier = source.scaleMultiplier;
        copy.attackRateMultiplier = source.attackRateMultiplier;
        copy.abilityCooldownMultiplier = source.abilityCooldownMultiplier;
        copy.knockbackGivenMultiplier = source.knockbackGivenMultiplier;
        copy.knockbackTakenMultiplier = source.knockbackTakenMultiplier;
        copy.turnRateMultiplier = source.turnRateMultiplier;
        copy.regenMultiplier = source.regenMultiplier;
        return copy;
    }

    private static boolean hasPendingEntries(PersistedState state) {
        return pendingEntryCount(state) > 0;
    }

    private static int pendingEntryCount(PersistedState state) {
        if (state == null) {
            return 0;
        }
        int bosses = state.bosses == null ? 0 : state.bosses.size();
        int adds = state.addLinks == null ? 0 : state.addLinks.size();
        return bosses + adds;
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Set<UUID> parseUuidList(List<String> values) {
        Set<UUID> out = ConcurrentHashMap.newKeySet();
        if (values == null || values.isEmpty()) {
            return out;
        }
        for (String value : values) {
            UUID parsed = parseUuid(value);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long sanitizeStartEpoch(long epochMs) {
        long now = System.currentTimeMillis();
        if (epochMs <= 0L) {
            return now;
        }
        if (epochMs > now) {
            return now;
        }
        return epochMs;
    }

    private static float clampModifier(float value) {
        if (!Float.isFinite(value) || value <= 0f) {
            return 1.0f;
        }
        return value;
    }

    private static BossModifiers sanitizeModifiers(BossModifiers modifiers) {
        if (modifiers == null) {
            return new BossModifiers(
                    1.0f,
                    1.0f,
                    1.0f,
                    1.0f,
                    1.0f,
                    1.0f,
                    1.0f,
                    1.0f,
                    1.0f,
                    1.0f
            );
        }
        return new BossModifiers(
                clampModifier(modifiers.hpMultiplier()),
                clampModifier(modifiers.damageMultiplier()),
                clampModifier(modifiers.speedMultiplier()),
                clampModifier(modifiers.scaleMultiplier()),
                clampModifier(modifiers.attackRateMultiplier()),
                clampModifier(modifiers.abilityCooldownMultiplier()),
                clampModifier(modifiers.knockbackGivenMultiplier()),
                clampModifier(modifiers.knockbackTakenMultiplier()),
                clampModifier(modifiers.turnRateMultiplier()),
                clampModifier(modifiers.regenMultiplier())
        );
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

    private static boolean isEntityAlive(World world, UUID entityUuid) {
        if (world == null || entityUuid == null) {
            return false;
        }
        try {
            var ref = world.getEntityRef(entityUuid);
            return ref != null && ref.isValid();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isEventInProgress(EventData event) {
        if (event == null) {
            return false;
        }
        return event.awaitingPrimaryBossSpawn || !event.aliveBosses.isEmpty() || !event.activeAdds.isEmpty();
    }

    public synchronized void initializePersistence(Path stateFilePath) {
        persistencePath = stateFilePath;
        if (persistencePath == null) {
            return;
        }

        try {
            Path parent = persistencePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to create tracking persistence directory: " + e.getMessage());
        }

        loadPersistentState();
        startAutosaveLoop();
        persistenceDirty = true;
        persistDirtySafely();
    }

    public synchronized void shutdownPersistence() {
        flushPersistence();
        releaseAllEventChunkRetention();
        if (persistenceExecutor != null) {
            persistenceExecutor.shutdownNow();
            persistenceExecutor = null;
        }
    }

    public void flushPersistence() {
        persistState();
    }

    private synchronized void startAutosaveLoop() {
        if (persistenceExecutor != null && !persistenceExecutor.isShutdown()) {
            return;
        }
        persistenceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BossArena-TrackingPersistence");
            t.setDaemon(true);
            return t;
        });
        persistenceExecutor.scheduleAtFixedRate(
                this::persistDirtySafely,
                AUTOSAVE_PERIOD_SECONDS,
                AUTOSAVE_PERIOD_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void persistDirtySafely() {
        try {
            retryPendingRestore();
            if (!persistenceDirty && !hasPendingRestore()) {
                return;
            }
            persistState();
        } catch (Exception e) {
            LOGGER.warning("Failed to autosave boss tracking state: " + e.getMessage());
        }
    }

    private void markDirty() {
        if (persistencePath == null) {
            return;
        }
        persistenceDirty = true;
    }

    private void persistState() {
        Path path = persistencePath;
        if (path == null) {
            return;
        }

        synchronized (persistenceLock) {
            PersistedState snapshot = buildPersistedState();
            String json = GSON.toJson(snapshot);
            Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");

            try {
                Files.writeString(temp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
                persistenceDirty = false;
            } catch (IOException e) {
                LOGGER.warning("Failed to persist boss tracking state: " + e.getMessage());
            }
        }
    }

    private PersistedState buildPersistedState() {
        PersistedState state = new PersistedState();

        for (EventData event : eventsById.values()) {
            if (event == null) {
                continue;
            }
            if (event.awaitingPrimaryBossSpawn && event.bossUuids.isEmpty() && event.activeAdds.isEmpty()) {
                // Deferred pre-boss placeholders are runtime-only; they are recreated by spawn flow.
                continue;
            }
            PersistedEvent out = new PersistedEvent();
            out.eventId = event.eventId != null ? event.eventId.toString() : "";
            out.world = resolveEventWorldName(event);
            out.centerX = event.eventCenter.x;
            out.centerY = event.eventCenter.y;
            out.centerZ = event.eventCenter.z;
            out.bossName = optional(event.bossName);
            out.bossTier = optional(event.bossTier);
            out.countdownDurationMs = Math.max(0L, event.countdownDurationMs);
            out.countdownStartEpochMs = Math.max(0L, event.countdownStartEpochMs);
            out.awaitingPrimaryBossSpawn = event.awaitingPrimaryBossSpawn;
            for (UUID bossUuid : event.bossUuids) {
                if (bossUuid != null) {
                    out.bossUuids.add(bossUuid.toString());
                }
            }
            for (UUID bossUuid : event.aliveBosses) {
                if (bossUuid != null) {
                    out.aliveBosses.add(bossUuid.toString());
                }
            }
            for (UUID addUuid : event.activeAdds) {
                if (addUuid != null) {
                    out.activeAdds.add(addUuid.toString());
                }
            }
            state.events.add(out);
        }

        for (Map.Entry<UUID, BossData> entry : trackedBosses.entrySet()) {
            UUID uuid = entry.getKey();
            BossData data = entry.getValue();
            if (uuid == null || data == null || data.spawnLocation == null) {
                continue;
            }

            PersistedBoss out = new PersistedBoss();
            out.uuid = uuid.toString();
            UUID eventId = bossToEvent.get(uuid);
            if (eventId == null) {
                eventId = data.eventId;
            }
            out.eventId = eventId != null ? eventId.toString() : "";
            out.bossName = optional(data.bossName);
            out.arenaId = optional(data.arenaId);
            out.world = data.world != null ? data.world.getName() : "";
            out.spawnX = data.spawnLocation.x;
            out.spawnY = data.spawnLocation.y;
            out.spawnZ = data.spawnLocation.z;
            out.bossTier = optional(data.bossTier);
            out.levelOverride = Math.max(0, data.levelOverride);
            out.spawnedAtEpochMs = Math.max(0L, data.spawnedAtEpochMs);

            BossModifiers mods = data.modifiers;
            out.hpMultiplier = mods != null ? mods.hpMultiplier() : 1.0f;
            out.damageMultiplier = mods != null ? mods.damageMultiplier() : 1.0f;
            out.speedMultiplier = mods != null ? mods.speedMultiplier() : 1.0f;
            out.scaleMultiplier = mods != null ? mods.scaleMultiplier() : 1.0f;
            out.attackRateMultiplier = mods != null ? mods.attackRateMultiplier() : 1.0f;
            out.abilityCooldownMultiplier = mods != null ? mods.abilityCooldownMultiplier() : 1.0f;
            out.knockbackGivenMultiplier = mods != null ? mods.knockbackGivenMultiplier() : 1.0f;
            out.knockbackTakenMultiplier = mods != null ? mods.knockbackTakenMultiplier() : 1.0f;
            out.turnRateMultiplier = mods != null ? mods.turnRateMultiplier() : 1.0f;
            out.regenMultiplier = mods != null ? mods.regenMultiplier() : 1.0f;
            state.bosses.add(out);
        }

        for (Map.Entry<UUID, UUID> entry : addToBoss.entrySet()) {
            UUID addUuid = entry.getKey();
            UUID bossUuid = entry.getValue();
            if (addUuid == null || bossUuid == null) {
                continue;
            }
            PersistedAddLink link = new PersistedAddLink();
            link.addUuid = addUuid.toString();
            link.bossUuid = bossUuid.toString();
            BossModifiers mods = addModifiers.get(addUuid);
            link.hpMultiplier = mods != null ? mods.hpMultiplier() : 1.0f;
            link.damageMultiplier = mods != null ? mods.damageMultiplier() : 1.0f;
            link.speedMultiplier = mods != null ? mods.speedMultiplier() : 1.0f;
            link.scaleMultiplier = mods != null ? mods.scaleMultiplier() : 1.0f;
            link.attackRateMultiplier = mods != null ? mods.attackRateMultiplier() : 1.0f;
            link.abilityCooldownMultiplier = mods != null ? mods.abilityCooldownMultiplier() : 1.0f;
            link.knockbackGivenMultiplier = mods != null ? mods.knockbackGivenMultiplier() : 1.0f;
            link.knockbackTakenMultiplier = mods != null ? mods.knockbackTakenMultiplier() : 1.0f;
            link.turnRateMultiplier = mods != null ? mods.turnRateMultiplier() : 1.0f;
            link.regenMultiplier = mods != null ? mods.regenMultiplier() : 1.0f;
            state.addLinks.add(link);
        }

        mergePersistedState(state, pendingRestoreState);
        return state;
    }

    private void loadPersistentState() {
        Path path = persistencePath;
        if (path == null || Files.notExists(path)) {
            return;
        }

        synchronized (persistenceLock) {
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                PersistedState state = GSON.fromJson(raw, PersistedState.class);
                if (state == null || state.version != PERSISTENCE_VERSION) {
                    LOGGER.info("Boss tracking state missing or incompatible; starting fresh.");
                    clearRuntimeState();
                    pendingRestoreState = null;
                    return;
                }
                restorePersistedState(state, true);
            } catch (Exception e) {
                LOGGER.warning("Failed to load boss tracking state: " + e.getMessage());
                clearRuntimeState();
                pendingRestoreState = null;
            }
        }
    }

    public void clearRuntimeState() {
        releaseAllEventChunkRetention();
        trackedBosses.clear();
        trackedAddsByBoss.clear();
        addToBoss.clear();
        addModifiers.clear();
        bossToEvent.clear();
        eventsById.clear();
    }

    private void refreshEventChunkRetention() {
        synchronized (chunkRetentionLock) {
            Map<UUID, HeldChunk> desired = new HashMap<>();
            for (Map.Entry<UUID, EventData> entry : eventsById.entrySet()) {
                UUID eventId = entry.getKey();
                EventData event = entry.getValue();
                if (eventId == null || event == null || event.eventCenter == null) {
                    continue;
                }
                if (!shouldRetainEventChunk(eventId, event)) {
                    continue;
                }
                World world = resolveEventWorld(event);
                if (world == null) {
                    continue;
                }
                desired.put(eventId, new HeldChunk(world, resolveChunkIndex(event.eventCenter)));
            }

            for (Map.Entry<UUID, HeldChunk> entry : new ArrayList<>(heldChunkByEvent.entrySet())) {
                UUID eventId = entry.getKey();
                HeldChunk existing = entry.getValue();
                HeldChunk target = desired.get(eventId);
                if (existing != null && existing.matches(target)) {
                    continue;
                }
                if (existing != null) {
                    releaseChunkHold(existing);
                }
                heldChunkByEvent.remove(eventId);
            }

            for (Map.Entry<UUID, HeldChunk> entry : desired.entrySet()) {
                UUID eventId = entry.getKey();
                HeldChunk target = entry.getValue();
                HeldChunk existing = heldChunkByEvent.get(eventId);
                if (existing != null && existing.matches(target)) {
                    continue;
                }
                if (acquireChunkHold(target)) {
                    heldChunkByEvent.put(eventId, target);
                }
            }
        }
    }

    private void releaseAllEventChunkRetention() {
        synchronized (chunkRetentionLock) {
            for (HeldChunk hold : heldChunkByEvent.values()) {
                releaseChunkHold(hold);
            }
            heldChunkByEvent.clear();
        }
    }

    private boolean shouldRetainEventChunk(UUID eventId, EventData event) {
        if (event == null) {
            return false;
        }
        if (isEventInProgress(event)) {
            return true;
        }
        return isEventPendingRestore(eventId);
    }

    private boolean isEventPendingRestore(UUID eventId) {
        if (eventId == null) {
            return false;
        }
        PersistedState pending = pendingRestoreState;
        if (pending == null) {
            return false;
        }
        String target = normalizeKey(eventId.toString());
        for (PersistedEvent pendingEvent : safeList(pending.events)) {
            if (target.equals(normalizeKey(pendingEvent != null ? pendingEvent.eventId : null))) {
                return true;
            }
        }
        return false;
    }

    public synchronized int retryPendingRestore() {
        PersistedState pending = pendingRestoreState;
        if (!hasPendingEntries(pending)) {
            return 0;
        }
        int before = pendingEntryCount(pending);
        int restored = restorePersistedState(pending, false);
        int after = pendingEntryCount(pendingRestoreState);
        if (restored > 0 || after < before) {
            markDirty();
            LOGGER.info("Boss tracking retry restored " + restored + " entity link(s); "
                    + after + " unresolved link(s) remain.");
        }
        return restored;
    }

    public synchronized boolean hasPendingRestore() {
        return hasPendingEntries(pendingRestoreState);
    }

    private int restorePersistedState(PersistedState state, boolean clearExisting) {
        if (clearExisting) {
            clearRuntimeState();
        }

        if (state == null) {
            pendingRestoreState = null;
            return 0;
        }

        int restoredLinks = 0;
        Map<UUID, EventData> restoredEvents = new HashMap<>();
        for (Map.Entry<UUID, EventData> entry : eventsById.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                restoredEvents.put(entry.getKey(), entry.getValue());
            }
        }

        for (PersistedEvent persisted : safeList(state.events)) {
            UUID eventId = parseUuid(persisted.eventId);
            if (eventId == null) {
                continue;
            }
            EventData existing = restoredEvents.get(eventId);
            if (existing != null) {
                if (existing.world == null) {
                    existing.world = resolveWorld(persisted.world);
                }
                continue;
            }
            Vector3d center = new Vector3d(persisted.centerX, persisted.centerY, persisted.centerZ);
            World world = resolveWorld(persisted.world);
            EventData event = new EventData(
                    eventId,
                    world,
                    center,
                    optional(persisted.bossName),
                    optional(persisted.bossTier),
                    Math.max(0L, persisted.countdownDurationMs),
                    sanitizeStartEpoch(persisted.countdownStartEpochMs),
                    persisted.awaitingPrimaryBossSpawn
            );
            restoredEvents.put(eventId, event);
            eventsById.put(eventId, event);
        }

        List<PersistedBoss> unresolvedBosses = new ArrayList<>();
        Set<UUID> unresolvedBossUuids = new HashSet<>();
        Set<UUID> unresolvedEventIds = new HashSet<>();
        Map<UUID, UUID> eventByBossUuid = new HashMap<>();

        for (PersistedBoss persistedBoss : safeList(state.bosses)) {
            UUID bossUuid = parseUuid(persistedBoss.uuid);
            UUID eventId = parseUuid(persistedBoss.eventId);
            if (bossUuid == null || eventId == null) {
                continue;
            }
            eventByBossUuid.put(bossUuid, eventId);

            EventData event = restoredEvents.get(eventId);
            if (event == null) {
                Vector3d center = new Vector3d(persistedBoss.spawnX, persistedBoss.spawnY, persistedBoss.spawnZ);
                event = new EventData(
                        eventId,
                        resolveWorld(persistedBoss.world),
                        center,
                        optional(persistedBoss.bossName),
                        optional(persistedBoss.bossTier),
                        0L
                );
                restoredEvents.put(eventId, event);
                eventsById.put(eventId, event);
            }

            if (trackedBosses.containsKey(bossUuid)) {
                bossToEvent.put(bossUuid, eventId);
                BossData existingBoss = trackedBosses.get(bossUuid);
                if (event.world == null && existingBoss != null && existingBoss.world != null) {
                    event.world = existingBoss.world;
                }
                event.bossUuids.add(bossUuid);
                event.aliveBosses.add(bossUuid);
                continue;
            }

            World world = resolveWorld(persistedBoss.world);
            if (world == null) {
                unresolvedBosses.add(copyPersistedBoss(persistedBoss));
                unresolvedBossUuids.add(bossUuid);
                unresolvedEventIds.add(eventId);
                continue;
            }
            if (!isEntityAlive(world, bossUuid)) {
                // Keep unresolved links so we can reattach once the entity/chunk is loaded.
                unresolvedBosses.add(copyPersistedBoss(persistedBoss));
                unresolvedBossUuids.add(bossUuid);
                unresolvedEventIds.add(eventId);
                continue;
            }

            Vector3d spawn = new Vector3d(persistedBoss.spawnX, persistedBoss.spawnY, persistedBoss.spawnZ);
            BossModifiers mods = new BossModifiers(
                    clampModifier(persistedBoss.hpMultiplier),
                    clampModifier(persistedBoss.damageMultiplier),
                    clampModifier(persistedBoss.speedMultiplier),
                    clampModifier(persistedBoss.scaleMultiplier),
                    clampModifier(persistedBoss.attackRateMultiplier),
                    clampModifier(persistedBoss.abilityCooldownMultiplier),
                    clampModifier(persistedBoss.knockbackGivenMultiplier),
                    clampModifier(persistedBoss.knockbackTakenMultiplier),
                    clampModifier(persistedBoss.turnRateMultiplier),
                    clampModifier(persistedBoss.regenMultiplier)
            );

            BossData data = new BossData(
                    optional(persistedBoss.bossName),
                    mods,
                    optional(persistedBoss.arenaId),
                    world,
                    spawn,
                    optional(persistedBoss.bossTier),
                    Math.max(0, persistedBoss.levelOverride),
                    eventId,
                    Math.max(0L, persistedBoss.spawnedAtEpochMs)
            );
            trackedBosses.put(bossUuid, data);
            bossToEvent.put(bossUuid, eventId);
            if (event.world == null) {
                event.world = world;
            }
            event.bossUuids.add(bossUuid);
            event.aliveBosses.add(bossUuid);
            restoredLinks++;
        }

        List<PersistedAddLink> unresolvedAddLinks = new ArrayList<>();
        for (PersistedAddLink persistedLink : safeList(state.addLinks)) {
            UUID addUuid = parseUuid(persistedLink.addUuid);
            UUID bossUuid = parseUuid(persistedLink.bossUuid);
            if (addUuid == null || bossUuid == null) {
                continue;
            }

            if (addToBoss.containsKey(addUuid)) {
                continue;
            }

            BossData bossData = trackedBosses.get(bossUuid);
            if (bossData == null) {
                if (unresolvedBossUuids.contains(bossUuid)) {
                    unresolvedAddLinks.add(copyPersistedAddLink(persistedLink));
                    UUID eventId = eventByBossUuid.get(bossUuid);
                    if (eventId != null) {
                        unresolvedEventIds.add(eventId);
                    }
                }
                continue;
            }
            if (bossData.world == null) {
                unresolvedAddLinks.add(copyPersistedAddLink(persistedLink));
                if (bossData.eventId != null) {
                    unresolvedEventIds.add(bossData.eventId);
                }
                continue;
            }
            if (!isEntityAlive(bossData.world, addUuid)) {
                // Adds can load after their parent boss/chunk; retry on future restore passes.
                unresolvedAddLinks.add(copyPersistedAddLink(persistedLink));
                if (bossData.eventId != null) {
                    unresolvedEventIds.add(bossData.eventId);
                }
                continue;
            }

            trackedAddsByBoss.computeIfAbsent(bossUuid, ignored -> ConcurrentHashMap.newKeySet()).add(addUuid);
            addToBoss.put(addUuid, bossUuid);
            addModifiers.put(addUuid, new BossModifiers(
                    clampModifier(persistedLink.hpMultiplier),
                    clampModifier(persistedLink.damageMultiplier),
                    clampModifier(persistedLink.speedMultiplier),
                    clampModifier(persistedLink.scaleMultiplier),
                    clampModifier(persistedLink.attackRateMultiplier),
                    clampModifier(persistedLink.abilityCooldownMultiplier),
                    clampModifier(persistedLink.knockbackGivenMultiplier),
                    clampModifier(persistedLink.knockbackTakenMultiplier),
                    clampModifier(persistedLink.turnRateMultiplier),
                    clampModifier(persistedLink.regenMultiplier)
            ));

            EventData event = getEventForBoss(bossUuid);
            if (event != null) {
                event.activeAdds.add(addUuid);
            }
            restoredLinks++;
        }

        for (Map.Entry<UUID, EventData> entry : new ArrayList<>(eventsById.entrySet())) {
            UUID eventId = entry.getKey();
            EventData event = entry.getValue();
            if (event == null) {
                eventsById.remove(eventId);
                continue;
            }

            event.bossUuids.removeIf(bossUuid -> {
                UUID mappedEvent = bossToEvent.get(bossUuid);
                return mappedEvent == null || !mappedEvent.equals(eventId);
            });
            event.aliveBosses.retainAll(event.bossUuids);
            event.activeAdds.removeIf(addUuid -> !addToBoss.containsKey(addUuid));

            if (event.bossUuids.isEmpty()
                    && event.activeAdds.isEmpty()
                    && !event.awaitingPrimaryBossSpawn
                    && !unresolvedEventIds.contains(eventId)) {
                eventsById.remove(eventId);
            }
        }
        addModifiers.keySet().retainAll(addToBoss.keySet());

        PersistedState pending = null;
        if (!unresolvedBosses.isEmpty() || !unresolvedAddLinks.isEmpty()) {
            pending = new PersistedState();
            for (PersistedEvent persistedEvent : safeList(state.events)) {
                UUID eventId = parseUuid(persistedEvent.eventId);
                if (eventId != null && unresolvedEventIds.contains(eventId)) {
                    pending.events.add(copyPersistedEvent(persistedEvent));
                }
            }
            pending.bosses.addAll(unresolvedBosses);
            pending.addLinks.addAll(unresolvedAddLinks);
        }
        pendingRestoreState = pending;
        refreshEventChunkRetention();

        LOGGER.info("Restored boss fight tracking state: "
                + trackedBosses.size() + " boss(es), "
                + addToBoss.size() + " add(s), "
                + eventsById.size() + " event(s), "
                + pendingEntryCount(pendingRestoreState) + " pending link(s).");
        return restoredLinks;
    }

    private String resolveEventWorldName(EventData event) {
        if (event == null) {
            return "";
        }
        if (event.world != null && event.world.getName() != null) {
            return event.world.getName();
        }
        for (UUID bossUuid : event.bossUuids) {
            BossData data = trackedBosses.get(bossUuid);
            if (data != null && data.world != null && data.world.getName() != null) {
                return data.world.getName();
            }
        }
        return "";
    }

    public UUID createEvent(World world, Vector3d eventCenter, String bossName) {
        return createEvent(world, eventCenter, bossName, null, 0L, false);
    }

    public UUID createEvent(World world,
                            Vector3d eventCenter,
                            String bossName,
                            String bossTier,
                            long countdownDurationMs) {
        return createEvent(world, eventCenter, bossName, bossTier, countdownDurationMs, false);
    }

    public UUID createEvent(World world,
                            Vector3d eventCenter,
                            String bossName,
                            String bossTier,
                            long countdownDurationMs,
                            boolean awaitingPrimaryBossSpawn) {
        if (eventCenter == null) {
            throw new IllegalArgumentException("eventCenter cannot be null");
        }

        UUID eventId = UUID.randomUUID();
        eventsById.put(eventId, new EventData(
                eventId,
                world,
                eventCenter,
                bossName,
                bossTier,
                countdownDurationMs,
                System.currentTimeMillis(),
                awaitingPrimaryBossSpawn
        ));
        markDirty();
        refreshEventChunkRetention();
        return eventId;
    }

    public void track(UUID uuid, String bossName, BossModifiers mods, String arenaId, World world, Vector3d spawnPos) {
        UUID eventId = createEvent(world, spawnPos, bossName);
        track(uuid, bossName, mods, arenaId, world, spawnPos, null, 0, eventId, spawnPos);
    }

    public void track(UUID uuid,
                      String bossName,
                      BossModifiers mods,
                      String arenaId,
                      World world,
                      Vector3d spawnPos,
                      String bossTier,
                      UUID eventId,
                      Vector3d eventCenter) {
        track(uuid, bossName, mods, arenaId, world, spawnPos, bossTier, 0, eventId, eventCenter);
    }

    public void track(UUID uuid,
                      String bossName,
                      BossModifiers mods,
                      String arenaId,
                      World world,
                      Vector3d spawnPos,
                      String bossTier,
                      int levelOverride,
                      UUID eventId,
                      Vector3d eventCenter) {
        if (uuid == null || spawnPos == null || eventId == null) {
            return;
        }

        Vector3d spawnCopy = new Vector3d(spawnPos.x, spawnPos.y, spawnPos.z);
        trackedBosses.put(
                uuid,
                new BossData(
                        bossName,
                        sanitizeModifiers(mods),
                        arenaId,
                        world,
                        spawnCopy,
                        bossTier,
                        Math.max(0, levelOverride),
                        eventId,
                        System.currentTimeMillis()
                )
        );
        bossToEvent.put(uuid, eventId);

        Vector3d center = eventCenter != null ? eventCenter : spawnPos;
        EventData event = eventsById.computeIfAbsent(eventId, ignored -> new EventData(eventId, world, center, bossName, bossTier, 0L));
        if (event.world == null && world != null) {
            event.world = world;
        }
        event.awaitingPrimaryBossSpawn = false;
        event.bossUuids.add(uuid);
        event.aliveBosses.add(uuid);
        markDirty();
        refreshEventChunkRetention();
    }

    public boolean isTracked(UUID uuid) {
        return trackedBosses.containsKey(uuid);
    }

    public void trackAdd(UUID bossUuid, UUID addUuid) {
        trackAdd(bossUuid, addUuid, null);
    }

    public void trackAdd(UUID bossUuid, UUID addUuid, BossModifiers modifiers) {
        if (bossUuid == null || addUuid == null) {
            return;
        }

        trackedAddsByBoss
                .computeIfAbsent(bossUuid, ignored -> ConcurrentHashMap.newKeySet())
                .add(addUuid);
        addToBoss.put(addUuid, bossUuid);
        addModifiers.put(addUuid, sanitizeModifiers(modifiers));

        EventData event = getEventForBoss(bossUuid);
        if (event != null) {
            event.activeAdds.add(addUuid);
        }
        markDirty();
        refreshEventChunkRetention();
    }

    public boolean isTrackedAdd(UUID addUuid) {
        return addUuid != null && addToBoss.containsKey(addUuid);
    }

    public UUID getBossUuidForAdd(UUID addUuid) {
        if (addUuid == null) {
            return null;
        }
        return addToBoss.get(addUuid);
    }

    public BossModifiers getEntityModifiers(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        BossData bossData = trackedBosses.get(uuid);
        if (bossData != null) {
            return bossData.modifiers;
        }
        return addModifiers.get(uuid);
    }

    public int getActiveAddCount(UUID bossUuid) {
        if (bossUuid == null) {
            return 0;
        }
        Set<UUID> adds = trackedAddsByBoss.get(bossUuid);
        return adds == null ? 0 : adds.size();
    }

    public int getAliveBossCount(UUID bossUuid) {
        EventData event = getEventForBoss(bossUuid);
        if (event == null) {
            return isTracked(bossUuid) ? 1 : 0;
        }
        return event.aliveBosses.size();
    }

    public int getActiveAddCountForEvent(UUID bossUuid) {
        EventData event = getEventForBoss(bossUuid);
        if (event == null) {
            return getActiveAddCount(bossUuid);
        }
        return event.activeAdds.size();
    }

    public long getRemainingCountdownMillis(UUID bossUuid) {
        EventData event = getEventForBoss(bossUuid);
        return getRemainingCountdownMillis(event);
    }

    public List<ActiveEventStatus> snapshotActiveEvents() {
        List<ActiveEventStatus> out = new ArrayList<>();
        for (EventData event : eventsById.values()) {
            if (event == null) {
                continue;
            }
            int alive = event.aliveBosses.size();
            int adds = event.activeAdds.size();
            if (!isEventInProgress(event)) {
                continue;
            }
            World resolvedWorld = resolveEventWorld(event);
            out.add(new ActiveEventStatus(
                    resolvedWorld,
                    event.eventCenter,
                    event.bossName,
                    event.bossTier,
                    alive,
                    adds,
                    getRemainingCountdownMillis(event),
                    event.awaitingPrimaryBossSpawn
            ));
        }
        return out;
    }

    public boolean hasAnyEventInProgress() {
        for (EventData event : eventsById.values()) {
            if (isEventInProgress(event)) {
                return true;
            }
        }
        return false;
    }

    public Map<UUID, BossData> snapshotTrackedBosses() {
        return new HashMap<>(trackedBosses);
    }

    public Map<UUID, UUID> snapshotTrackedAdds() {
        return new HashMap<>(addToBoss);
    }

    public Set<UUID> snapshotAddsForBoss(UUID bossUuid) {
        if (bossUuid == null) {
            return Set.of();
        }
        Set<UUID> adds = trackedAddsByBoss.get(bossUuid);
        if (adds == null || adds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(adds);
    }

    public String getBossName(UUID uuid) {
        BossData data = trackedBosses.get(uuid);
        return data != null ? data.bossName : null;
    }

    public BossEventContext untrackAndCancel(UUID uuid) {
        if (uuid == null) {
            return null;
        }

        UUID eventId = bossToEvent.get(uuid);
        BossEventContext context = getEventContext(uuid);

        if (eventId != null) {
            cancelEvent(eventId);
        }

        untrack(uuid);
        return context;
    }

    public void untrack(UUID uuid) {
        if (uuid == null) {
            return;
        }

        trackedBosses.remove(uuid);

        UUID eventId = bossToEvent.remove(uuid);
        EventData event = eventId != null ? eventsById.get(eventId) : null;
        clearBossAddMappings(uuid, event);

        if (event != null) {
            event.aliveBosses.remove(uuid);
            event.bossUuids.remove(uuid);
            if (event.bossUuids.isEmpty() && event.activeAdds.isEmpty()) {
                eventsById.remove(eventId);
            }
        }

        markDirty();
        refreshEventChunkRetention();
    }

    public PendingLootData markBossDead(UUID bossUuid) {
        if (bossUuid == null) {
            return null;
        }

        BossData data = trackedBosses.remove(bossUuid);
        if (data == null) {
            return null;
        }

        UUID eventId = bossToEvent.get(bossUuid);
        EventData event = eventId != null ? eventsById.get(eventId) : null;
        if (event == null) {
            clearBossAddMappings(bossUuid, null);
            bossToEvent.remove(bossUuid);
            markDirty();
            refreshEventChunkRetention();
            return new PendingLootData(data.world, data.spawnLocation, data.bossName);
        }

        event.aliveBosses.remove(bossUuid);
        PendingLootData pending = tryCompleteEvent(eventId);
        if (pending != null && pending.world == null && data.world != null) {
            pending = new PendingLootData(data.world, pending.spawnLocation, pending.bossName);
        }
        markDirty();
        refreshEventChunkRetention();
        return pending;
    }

    public PendingLootData handleTrackedAddDeath(UUID addUuid) {
        if (addUuid == null) {
            return null;
        }

        UUID bossUuid = addToBoss.remove(addUuid);
        if (bossUuid == null) {
            return null;
        }
        addModifiers.remove(addUuid);

        Set<UUID> adds = trackedAddsByBoss.get(bossUuid);
        if (adds != null) {
            adds.remove(addUuid);
            if (adds.isEmpty()) {
                trackedAddsByBoss.remove(bossUuid);
            }
        }

        UUID eventId = bossToEvent.get(bossUuid);
        EventData event = eventId != null ? eventsById.get(eventId) : null;
        if (event != null) {
            event.activeAdds.remove(addUuid);
            PendingLootData pending = tryCompleteEvent(eventId);
            markDirty();
            refreshEventChunkRetention();
            return pending;
        }

        markDirty();
        refreshEventChunkRetention();
        return null;
    }

    private PendingLootData tryCompleteEvent(UUID eventId) {
        if (eventId == null) {
            return null;
        }

        EventData event = eventsById.get(eventId);
        if (event == null) {
            return null;
        }

        if (event.awaitingPrimaryBossSpawn) {
            return null;
        }

        if (!event.aliveBosses.isEmpty() || !event.activeAdds.isEmpty()) {
            return null;
        }

        World lootWorld = resolveEventWorld(event);
        Vector3d lootLocation = event.eventCenter;
        String lootBossName = event.bossName;
        for (UUID bossUuid : event.bossUuids) {
            BossData tracked = trackedBosses.get(bossUuid);
            if (tracked == null) {
                continue;
            }
            if (lootWorld == null && tracked.world != null) {
                lootWorld = tracked.world;
            }
            if (tracked.spawnLocation != null) {
                lootLocation = tracked.spawnLocation;
            }
            if (tracked.bossName != null && !tracked.bossName.isBlank()) {
                lootBossName = tracked.bossName;
            }
            if (lootWorld != null) {
                break;
            }
        }

        eventsById.remove(eventId);

        for (UUID bossUuid : event.bossUuids) {
            bossToEvent.remove(bossUuid);
            trackedBosses.remove(bossUuid);
            clearBossAddMappings(bossUuid, event);
        }

        return new PendingLootData(lootWorld, lootLocation, event.eventCenter, lootBossName, event.bossUuids);
    }

    private void clearBossAddMappings(UUID bossUuid, EventData event) {
        Set<UUID> adds = trackedAddsByBoss.remove(bossUuid);
        if (adds == null || adds.isEmpty()) {
            return;
        }

        for (UUID addUuid : adds) {
            addToBoss.remove(addUuid);
            addModifiers.remove(addUuid);
            if (event != null) {
                event.activeAdds.remove(addUuid);
            }
        }
    }

    private EventData getEventForBoss(UUID bossUuid) {
        if (bossUuid == null) {
            return null;
        }

        UUID eventId = bossToEvent.get(bossUuid);
        if (eventId == null) {
            return null;
        }

        return eventsById.get(eventId);
    }

    public void markEventPrimaryBossSpawned(UUID eventId) {
        if (eventId == null) {
            return;
        }
        EventData event = eventsById.get(eventId);
        if (event == null) {
            return;
        }
        if (!event.awaitingPrimaryBossSpawn) {
            return;
        }
        event.awaitingPrimaryBossSpawn = false;
        markDirty();
        refreshEventChunkRetention();
    }

    public void cancelEvent(UUID eventId) {
        if (eventId == null) {
            return;
        }
        EventData removed = eventsById.remove(eventId);
        if (removed == null) {
            return;
        }
        for (UUID bossUuid : new ArrayList<>(removed.bossUuids)) {
            bossToEvent.remove(bossUuid);
        }
        markDirty();
        refreshEventChunkRetention();
    }

    private long getRemainingCountdownMillis(EventData event) {
        if (event == null || event.countdownDurationMs <= 0L) {
            return -1L;
        }
        long elapsed = System.currentTimeMillis() - event.countdownStartEpochMs;
        return Math.max(0L, event.countdownDurationMs - Math.max(0L, elapsed));
    }

    private World resolveEventWorld(EventData event) {
        if (event == null) {
            return null;
        }
        if (event.world != null) {
            return event.world;
        }
        for (UUID bossUuid : event.bossUuids) {
            BossData data = trackedBosses.get(bossUuid);
            if (data != null && data.world != null) {
                event.world = data.world;
                return data.world;
            }
        }
        return null;
    }

    public BossData getBossData(UUID uuid) {
        return trackedBosses.get(uuid);
    }

    public BossEventContext getEventContext(UUID bossUuid) {
        if (bossUuid == null) {
            return null;
        }

        EventData event = getEventForBoss(bossUuid);
        if (event != null) {
            return new BossEventContext(
                    bossUuid,
                    resolveEventWorld(event),
                    event.eventCenter,
                    event.bossName,
                    event.bossTier,
                    getRemainingCountdownMillis(event)
            );
        }

        BossData tracked = trackedBosses.get(bossUuid);
        if (tracked != null) {
            return new BossEventContext(
                    bossUuid,
                    tracked.world,
                    tracked.spawnLocation,
                    tracked.bossName,
                    tracked.bossTier,
                    -1L
            );
        }

        return null;
    }

    public EventMembersSnapshot snapshotEventMembersForBoss(UUID bossUuid) {
        if (bossUuid == null) {
            return null;
        }
        UUID eventId = bossToEvent.get(bossUuid);
        if (eventId == null) {
            return null;
        }
        EventData event = eventsById.get(eventId);
        if (event == null) {
            return null;
        }
        Set<UUID> bosses = new HashSet<>(event.bossUuids);
        bosses.remove(null);
        Set<UUID> adds = new HashSet<>(event.activeAdds);
        adds.remove(null);
        return new EventMembersSnapshot(
                eventId,
                resolveEventWorld(event),
                event.eventCenter,
                event.bossName,
                bosses,
                adds
        );
    }

    public static class BossData {
        public String bossName;
        public BossModifiers modifiers;
        public String arenaId;
        public World world;
        public Vector3d spawnLocation;
        public String bossTier;
        public int levelOverride;
        public UUID eventId;
        public long spawnedAtEpochMs;

        public BossData(String bossName,
                        BossModifiers modifiers,
                        String arenaId,
                        World world,
                        Vector3d spawnLocation,
                        String bossTier,
                        int levelOverride,
                        UUID eventId,
                        long spawnedAtEpochMs) {
            this.bossName = bossName;
            this.modifiers = modifiers;
            this.arenaId = arenaId;
            this.world = world;
            this.spawnLocation = spawnLocation;
            this.bossTier = bossTier;
            this.levelOverride = Math.max(0, levelOverride);
            this.eventId = eventId;
            this.spawnedAtEpochMs = spawnedAtEpochMs;
        }
    }

    public static class PendingLootData {
        public final World world;
        public final Vector3d spawnLocation;
        public final Vector3d eventCenter;
        public final String bossName;
        public final java.util.Set<java.util.UUID> bossUuids;

        public PendingLootData(World world, Vector3d spawnLocation, String bossName) {
            this(world, spawnLocation, null, bossName, null);
        }

        public PendingLootData(World world, Vector3d spawnLocation, Vector3d eventCenter, String bossName, java.util.Set<java.util.UUID> bossUuids) {
            this.world = world;
            Vector3d safeLocation = spawnLocation != null ? spawnLocation : new Vector3d(0, 0, 0);
            this.spawnLocation = new Vector3d(safeLocation.x, safeLocation.y, safeLocation.z);
            this.eventCenter = eventCenter;
            this.bossName = bossName;
            this.bossUuids = bossUuids != null ? new java.util.HashSet<>(bossUuids) : new java.util.HashSet<>();
        }
    }

    public static class BossEventContext {
        public final UUID bossUuid;
        public final World world;
        public final Vector3d spawnLocation;
        public final String bossName;
        public final String bossTier;
        public final long remainingCountdownMillis;

        public BossEventContext(UUID bossUuid,
                                World world,
                                Vector3d spawnLocation,
                                String bossName,
                                String bossTier,
                                long remainingCountdownMillis) {
            this.bossUuid = bossUuid;
            this.world = world;
            this.spawnLocation = spawnLocation == null ? null : new Vector3d(spawnLocation.x, spawnLocation.y, spawnLocation.z);
            this.bossName = bossName;
            this.bossTier = bossTier;
            this.remainingCountdownMillis = remainingCountdownMillis;
        }
    }

    public static class ActiveEventStatus {
        public final World world;
        public final Vector3d eventCenter;
        public final String bossName;
        public final String bossTier;
        public final int aliveBossCount;
        public final int activeAddCount;
        public final long remainingCountdownMillis;
        public final boolean awaitingPrimaryBossSpawn;

        public ActiveEventStatus(World world,
                                 Vector3d eventCenter,
                                 String bossName,
                                 String bossTier,
                                 int aliveBossCount,
                                 int activeAddCount,
                                 long remainingCountdownMillis,
                                 boolean awaitingPrimaryBossSpawn) {
            this.world = world;
            this.eventCenter = eventCenter == null ? null : new Vector3d(eventCenter.x, eventCenter.y, eventCenter.z);
            this.bossName = bossName;
            this.bossTier = bossTier;
            this.aliveBossCount = aliveBossCount;
            this.activeAddCount = activeAddCount;
            this.remainingCountdownMillis = remainingCountdownMillis;
            this.awaitingPrimaryBossSpawn = awaitingPrimaryBossSpawn;
        }
    }

    public static class EventMembersSnapshot {
        public final UUID eventId;
        public final World world;
        public final Vector3d eventCenter;
        public final String bossName;
        public final Set<UUID> bossUuids;
        public final Set<UUID> activeAddUuids;

        public EventMembersSnapshot(UUID eventId,
                                    World world,
                                    Vector3d eventCenter,
                                    String bossName,
                                    Set<UUID> bossUuids,
                                    Set<UUID> activeAddUuids) {
            this.eventId = eventId;
            this.world = world;
            this.eventCenter = eventCenter == null ? null : new Vector3d(eventCenter.x, eventCenter.y, eventCenter.z);
            this.bossName = bossName;
            this.bossUuids = bossUuids == null ? Set.of() : Set.copyOf(bossUuids);
            this.activeAddUuids = activeAddUuids == null ? Set.of() : Set.copyOf(activeAddUuids);
        }
    }

    private static final class EventData {
        private final UUID eventId;
        private final Vector3d eventCenter;
        private final String bossName;
        private final String bossTier;
        private final long countdownDurationMs;
        private final long countdownStartEpochMs;
        private final Set<UUID> bossUuids = ConcurrentHashMap.newKeySet();
        private final Set<UUID> aliveBosses = ConcurrentHashMap.newKeySet();
        private final Set<UUID> activeAdds = ConcurrentHashMap.newKeySet();
        private World world;
        private volatile boolean awaitingPrimaryBossSpawn;

        private EventData(UUID eventId,
                          World world,
                          Vector3d eventCenter,
                          String bossName,
                          String bossTier,
                          long countdownDurationMs) {
            this(eventId, world, eventCenter, bossName, bossTier, countdownDurationMs, System.currentTimeMillis(), false);
        }

        private EventData(UUID eventId,
                          World world,
                          Vector3d eventCenter,
                          String bossName,
                          String bossTier,
                          long countdownDurationMs,
                          long countdownStartEpochMs) {
            this(eventId, world, eventCenter, bossName, bossTier, countdownDurationMs, countdownStartEpochMs, false);
        }

        private EventData(UUID eventId,
                          World world,
                          Vector3d eventCenter,
                          String bossName,
                          String bossTier,
                          long countdownDurationMs,
                          long countdownStartEpochMs,
                          boolean awaitingPrimaryBossSpawn) {
            this.eventId = eventId;
            this.world = world;
            this.eventCenter = new Vector3d(eventCenter.x, eventCenter.y, eventCenter.z);
            this.bossName = bossName;
            this.bossTier = bossTier;
            this.countdownDurationMs = Math.max(0L, countdownDurationMs);
            this.countdownStartEpochMs = Math.max(0L, countdownStartEpochMs);
            this.awaitingPrimaryBossSpawn = awaitingPrimaryBossSpawn;
        }
    }

    private static final class HeldChunk {
        private final World world;
        private final long chunkIndex;

        private HeldChunk(World world, long chunkIndex) {
            this.world = world;
            this.chunkIndex = chunkIndex;
        }

        private boolean matches(HeldChunk other) {
            return other != null && this.world == other.world && this.chunkIndex == other.chunkIndex;
        }
    }

    private static final class PersistedState {
        int version = PERSISTENCE_VERSION;
        List<PersistedEvent> events = new ArrayList<>();
        List<PersistedBoss> bosses = new ArrayList<>();
        List<PersistedAddLink> addLinks = new ArrayList<>();
    }

    private static final class PersistedEvent {
        String eventId;
        String world;
        double centerX;
        double centerY;
        double centerZ;
        String bossName;
        String bossTier;
        long countdownDurationMs;
        long countdownStartEpochMs;
        boolean awaitingPrimaryBossSpawn;
        List<String> bossUuids = new ArrayList<>();
        List<String> aliveBosses = new ArrayList<>();
        List<String> activeAdds = new ArrayList<>();
    }

    private static final class PersistedBoss {
        String uuid;
        String eventId;
        String bossName;
        String arenaId;
        String world;
        double spawnX;
        double spawnY;
        double spawnZ;
        String bossTier;
        int levelOverride;
        long spawnedAtEpochMs;
        float hpMultiplier;
        float damageMultiplier;
        float speedMultiplier;
        float scaleMultiplier;
        float attackRateMultiplier;
        float abilityCooldownMultiplier;
        float knockbackGivenMultiplier;
        float knockbackTakenMultiplier;
        float turnRateMultiplier;
        float regenMultiplier;
    }

    private static final class PersistedAddLink {
        String addUuid;
        String bossUuid;
        float hpMultiplier;
        float damageMultiplier;
        float speedMultiplier;
        float scaleMultiplier;
        float attackRateMultiplier;
        float abilityCooldownMultiplier;
        float knockbackGivenMultiplier;
        float knockbackTakenMultiplier;
        float turnRateMultiplier;
        float regenMultiplier;
    }
}
