package com.bossarena.system;

import com.bossarena.boss.BossModifiers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class BossTrackingSystem {
    private static final Logger LOGGER = Logger.getLogger("BossArena");

    private static class TrackedBoss {
        final BossModifiers modifiers;
        final long expireAt;

        TrackedBoss(BossModifiers modifiers, long expireAt) {
            this.modifiers = modifiers;
            this.expireAt = expireAt;
        }
    }

    private final Map<UUID, TrackedBoss> tracked = new ConcurrentHashMap<>();

    public void track(UUID uuid, BossModifiers mods, long ttlMs) {
        long expireAt = System.currentTimeMillis() + ttlMs;
        tracked.put(uuid, new TrackedBoss(mods, expireAt));
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

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        tracked.values().removeIf(boss -> boss.expireAt < now);
    }
}