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

    private final Map<UUID, BossData> trackedBosses = new ConcurrentHashMap<>();

    public void track(UUID uuid, String bossName, BossModifiers mods, String arenaId, World world, Vector3d spawnPos) {
        Vector3d spawnCopy = new Vector3d(spawnPos.x, spawnPos.y, spawnPos.z);
        trackedBosses.put(uuid, new BossData(bossName, mods, arenaId, world, spawnCopy));
    }

    public boolean isTracked(UUID uuid) {
        return trackedBosses.containsKey(uuid);
    }

    public String getBossName(UUID uuid) {
        BossData data = trackedBosses.get(uuid);
        return data != null ? data.bossName : null;
    }

    public void untrack(UUID uuid) {
        trackedBosses.remove(uuid);
    }

    public BossData getBossData(UUID uuid) {
        return trackedBosses.get(uuid);
    }
}
