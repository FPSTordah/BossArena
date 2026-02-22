package com.bossarena.system;

import com.bossarena.boss.BossModifiers;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BossTrackingSystem {

    public static class BossData {
        public String bossName;
        public BossModifiers modifiers;
        public String arenaId;
        public World world;
        public Vector3d spawnLocation;

        public BossData(String bossName, BossModifiers modifiers, String arenaId, World world, Vector3d spawnLocation) {
            this.bossName = bossName;
            this.modifiers = modifiers;
            this.arenaId = arenaId;
            this.world = world;
            this.spawnLocation = spawnLocation;
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

        public BossEventContext(UUID bossUuid, World world, Vector3d spawnLocation, String bossName) {
            this.bossUuid = bossUuid;
            this.world = world;
            this.spawnLocation = spawnLocation == null ? null : new Vector3d(spawnLocation.x, spawnLocation.y, spawnLocation.z);
            this.bossName = bossName;
        }
    }

    private final Map<UUID, BossData> trackedBosses = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> trackedAddsByBoss = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> addToBoss = new ConcurrentHashMap<>();
    private final Map<UUID, PendingLootData> pendingLootByBoss = new ConcurrentHashMap<>();

    public void track(UUID uuid, String bossName, BossModifiers mods, String arenaId, World world, Vector3d spawnPos) {
        Vector3d spawnCopy = new Vector3d(spawnPos.x, spawnPos.y, spawnPos.z);
        trackedBosses.put(uuid, new BossData(bossName, mods, arenaId, world, spawnCopy));
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

    public String getBossName(UUID uuid) {
        BossData data = trackedBosses.get(uuid);
        return data != null ? data.bossName : null;
    }

    public void untrack(UUID uuid) {
        trackedBosses.remove(uuid);
    }

    public PendingLootData markBossDead(UUID bossUuid) {
        if (bossUuid == null) {
            return null;
        }

        BossData data = trackedBosses.remove(bossUuid);
        if (data == null) {
            return null;
        }

        PendingLootData pending = new PendingLootData(data.world, data.spawnLocation, data.bossName);
        pendingLootByBoss.put(bossUuid, pending);

        if (getActiveAddCount(bossUuid) <= 0) {
            pendingLootByBoss.remove(bossUuid);
            clearBossAddMappings(bossUuid);
            return pending;
        }

        return null;
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

        if (getActiveAddCount(bossUuid) > 0) {
            return null;
        }

        PendingLootData pending = pendingLootByBoss.remove(bossUuid);
        if (pending != null) {
            clearBossAddMappings(bossUuid);
        }
        return pending;
    }

    private void clearBossAddMappings(UUID bossUuid) {
        Set<UUID> adds = trackedAddsByBoss.remove(bossUuid);
        if (adds == null || adds.isEmpty()) {
            return;
        }
        for (UUID addUuid : adds) {
            addToBoss.remove(addUuid);
        }
    }

    public BossData getBossData(UUID uuid) {
        return trackedBosses.get(uuid);
    }

    public BossEventContext getEventContext(UUID bossUuid) {
        if (bossUuid == null) {
            return null;
        }

        BossData tracked = trackedBosses.get(bossUuid);
        if (tracked != null) {
            return new BossEventContext(bossUuid, tracked.world, tracked.spawnLocation, tracked.bossName);
        }

        PendingLootData pending = pendingLootByBoss.get(bossUuid);
        if (pending != null) {
            return new BossEventContext(bossUuid, pending.world, pending.spawnLocation, pending.bossName);
        }

        return null;
    }
}
