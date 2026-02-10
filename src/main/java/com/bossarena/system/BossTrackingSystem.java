package com.bossarena.system;

import com.bossarena.boss.BossModifiers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class BossTrackingSystem {
    private static final Logger LOGGER = Logger.getLogger("BossArena");

    private static class TrackedBoss {
        final String bossName;
        final BossModifiers modifiers;
        final long expireAt;

        TrackedBoss(String bossName, BossModifiers modifiers, long expireAt) {
            this.bossName = bossName;
            this.modifiers = modifiers;
            this.expireAt = expireAt;
        }
    }

    private final Map<UUID, TrackedBoss> tracked = new ConcurrentHashMap<>();

    public void track(UUID uuid, String bossName, BossModifiers mods, long ttlMs) {
        long expireAt = System.currentTimeMillis() + ttlMs;
        tracked.put(uuid, new TrackedBoss(bossName, mods, expireAt));
    }

    public void untrack(UUID uuid) {
        if (tracked.remove(uuid) != null) {
            LOGGER.info("Boss untracked: " + uuid);
        }
    }

    public boolean isTracked(UUID uuid) {
        return tracked.containsKey(uuid);
    }

    public BossModifiers getModifiers(UUID uuid) {
        TrackedBoss boss = tracked.get(uuid);
        return boss != null ? boss.modifiers : null;
    }

    public String getBossName(UUID uuid) {
        TrackedBoss boss = tracked.get(uuid);
        return boss != null ? boss.bossName : null;
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        tracked.values().removeIf(boss -> boss.expireAt < now);
    }
}