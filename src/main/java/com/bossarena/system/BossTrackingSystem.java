package com.bossarena.system;

import com.bossarena.boss.BossModifiers;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BossTrackingSystem {

    public static class BossData {
        public String bossName;
        public BossModifiers modifiers;
        public String arenaId;
        public World world;
        public Vector3d spawnLocation;
        public String bossTier;
        public UUID eventId;

        public BossData(String bossName,
                        BossModifiers modifiers,
                        String arenaId,
                        World world,
                        Vector3d spawnLocation,
                        String bossTier,
                        UUID eventId) {
            this.bossName = bossName;
            this.modifiers = modifiers;
            this.arenaId = arenaId;
            this.world = world;
            this.spawnLocation = spawnLocation;
            this.bossTier = bossTier;
            this.eventId = eventId;
        }
    }

    public static class PendingLootData {
        public final World world;
        public final Vector3d spawnLocation;
        public final String bossName;

        public PendingLootData(World world, Vector3d spawnLocation, String bossName) {
            this.world = world;
            this.spawnLocation = new Vector3d(spawnLocation.x, spawnLocation.y, spawnLocation.z);
            this.bossName = bossName;
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

        public ActiveEventStatus(World world,
                                 Vector3d eventCenter,
                                 String bossName,
                                 String bossTier,
                                 int aliveBossCount,
                                 int activeAddCount,
                                 long remainingCountdownMillis) {
            this.world = world;
            this.eventCenter = eventCenter == null ? null : new Vector3d(eventCenter.x, eventCenter.y, eventCenter.z);
            this.bossName = bossName;
            this.bossTier = bossTier;
            this.aliveBossCount = aliveBossCount;
            this.activeAddCount = activeAddCount;
            this.remainingCountdownMillis = remainingCountdownMillis;
        }
    }

    private static final class EventData {
        private final UUID eventId;
        private final World world;
        private final Vector3d eventCenter;
        private final String bossName;
        private final String bossTier;
        private final long countdownDurationMs;
        private final long countdownStartEpochMs;
        private final Set<UUID> bossUuids = ConcurrentHashMap.newKeySet();
        private final Set<UUID> aliveBosses = ConcurrentHashMap.newKeySet();
        private final Set<UUID> activeAdds = ConcurrentHashMap.newKeySet();

        private EventData(UUID eventId,
                          World world,
                          Vector3d eventCenter,
                          String bossName,
                          String bossTier,
                          long countdownDurationMs) {
            this.eventId = eventId;
            this.world = world;
            this.eventCenter = new Vector3d(eventCenter.x, eventCenter.y, eventCenter.z);
            this.bossName = bossName;
            this.bossTier = bossTier;
            this.countdownDurationMs = Math.max(0L, countdownDurationMs);
            this.countdownStartEpochMs = System.currentTimeMillis();
        }
    }

    private final Map<UUID, BossData> trackedBosses = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> trackedAddsByBoss = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> addToBoss = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> bossToEvent = new ConcurrentHashMap<>();
    private final Map<UUID, EventData> eventsById = new ConcurrentHashMap<>();

    public UUID createEvent(World world, Vector3d eventCenter, String bossName) {
        return createEvent(world, eventCenter, bossName, null, 0L);
    }

    public UUID createEvent(World world,
                            Vector3d eventCenter,
                            String bossName,
                            String bossTier,
                            long countdownDurationMs) {
        if (eventCenter == null) {
            throw new IllegalArgumentException("eventCenter cannot be null");
        }

        UUID eventId = UUID.randomUUID();
        eventsById.put(eventId, new EventData(eventId, world, eventCenter, bossName, bossTier, countdownDurationMs));
        return eventId;
    }

    public void track(UUID uuid, String bossName, BossModifiers mods, String arenaId, World world, Vector3d spawnPos) {
        UUID eventId = createEvent(world, spawnPos, bossName);
        track(uuid, bossName, mods, arenaId, world, spawnPos, null, eventId, spawnPos);
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
        if (uuid == null || spawnPos == null || eventId == null) {
            return;
        }

        Vector3d spawnCopy = new Vector3d(spawnPos.x, spawnPos.y, spawnPos.z);
        trackedBosses.put(uuid, new BossData(bossName, mods, arenaId, world, spawnCopy, bossTier, eventId));
        bossToEvent.put(uuid, eventId);

        Vector3d center = eventCenter != null ? eventCenter : spawnPos;
        EventData event = eventsById.computeIfAbsent(eventId, ignored -> new EventData(eventId, world, center, bossName, bossTier, 0L));
        event.bossUuids.add(uuid);
        event.aliveBosses.add(uuid);
    }

    public boolean isTracked(UUID uuid) {
        return trackedBosses.containsKey(uuid);
    }

    public void trackAdd(UUID bossUuid, UUID addUuid) {
        if (bossUuid == null || addUuid == null) {
            return;
        }

        trackedAddsByBoss
                .computeIfAbsent(bossUuid, ignored -> ConcurrentHashMap.newKeySet())
                .add(addUuid);
        addToBoss.put(addUuid, bossUuid);

        EventData event = getEventForBoss(bossUuid);
        if (event != null) {
            event.activeAdds.add(addUuid);
        }
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
            if (alive <= 0 && adds <= 0) {
                continue;
            }
            out.add(new ActiveEventStatus(
                    event.world,
                    event.eventCenter,
                    event.bossName,
                    event.bossTier,
                    alive,
                    adds,
                    getRemainingCountdownMillis(event)
            ));
        }
        return out;
    }

    public Map<UUID, BossData> snapshotTrackedBosses() {
        return new HashMap<>(trackedBosses);
    }

    public Map<UUID, UUID> snapshotTrackedAdds() {
        return new HashMap<>(addToBoss);
    }

    public String getBossName(UUID uuid) {
        BossData data = trackedBosses.get(uuid);
        return data != null ? data.bossName : null;
    }

    public void untrack(UUID uuid) {
        if (uuid == null) {
            return;
        }

        trackedBosses.remove(uuid);

        UUID eventId = bossToEvent.remove(uuid);
        EventData event = eventId != null ? eventsById.get(eventId) : null;
        clearBossAddMappings(uuid, event);

        if (event == null) {
            return;
        }

        event.aliveBosses.remove(uuid);
        event.bossUuids.remove(uuid);
        if (event.bossUuids.isEmpty() && event.activeAdds.isEmpty()) {
            eventsById.remove(eventId);
        }
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
            return new PendingLootData(data.world, data.spawnLocation, data.bossName);
        }

        event.aliveBosses.remove(bossUuid);
        return tryCompleteEvent(eventId);
    }

    public PendingLootData handleTrackedAddDeath(UUID addUuid) {
        if (addUuid == null) {
            return null;
        }

        UUID bossUuid = addToBoss.remove(addUuid);
        if (bossUuid == null) {
            return null;
        }

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
            return tryCompleteEvent(eventId);
        }

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

        if (!event.aliveBosses.isEmpty() || !event.activeAdds.isEmpty()) {
            return null;
        }

        eventsById.remove(eventId);

        for (UUID bossUuid : event.bossUuids) {
            bossToEvent.remove(bossUuid);
            trackedBosses.remove(bossUuid);
            clearBossAddMappings(bossUuid, event);
        }

        return new PendingLootData(event.world, event.eventCenter, event.bossName);
    }

    private void clearBossAddMappings(UUID bossUuid, EventData event) {
        Set<UUID> adds = trackedAddsByBoss.remove(bossUuid);
        if (adds == null || adds.isEmpty()) {
            return;
        }

        for (UUID addUuid : adds) {
            addToBoss.remove(addUuid);
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

    private long getRemainingCountdownMillis(EventData event) {
        if (event == null || event.countdownDurationMs <= 0L) {
            return -1L;
        }
        long elapsed = System.currentTimeMillis() - event.countdownStartEpochMs;
        return Math.max(0L, event.countdownDurationMs - Math.max(0L, elapsed));
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
                    event.world,
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
}
