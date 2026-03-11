package com.bossarena.damagechart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe per-event damage tally: eventId -> (playerUuid -> totalDamage).
 * Used by BossDamageChartRecordingSystem and read at event completion in BossLootHandler.
 */
public final class BossDamageChartTracker {

    /** eventId -> (playerUuid -> total damage). */
    private final Map<UUID, Map<UUID, LongAdder>> byEvent = new ConcurrentHashMap<>();

    /**
     * Add damage for a player in an event. Safe to call from the damage system (world thread).
     */
    public void addDamage(UUID eventId, UUID playerUuid, long amount) {
        if (eventId == null || playerUuid == null || amount <= 0) {
            return;
        }
        byEvent
                .computeIfAbsent(eventId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(playerUuid, k -> new LongAdder())
                .add(amount);
    }

    /**
     * Take a snapshot of damage per player for the event, then remove the event's data.
     * Call from loot handling when the event completes. Returns entries sorted by damage descending.
     */
    public List<DamageEntry> takeSnapshotAndRemove(UUID eventId) {
        if (eventId == null) {
            return List.of();
        }
        Map<UUID, LongAdder> perPlayer = byEvent.remove(eventId);
        if (perPlayer == null || perPlayer.isEmpty()) {
            return List.of();
        }
        List<DamageEntry> list = new ArrayList<>();
        for (Map.Entry<UUID, LongAdder> e : perPlayer.entrySet()) {
            long total = e.getValue().sum();
            if (total > 0) {
                list.add(new DamageEntry(e.getKey(), total));
            }
        }
        list.sort(Comparator.comparingLong(DamageEntry::damage).reversed());
        return Collections.unmodifiableList(list);
    }

    public static final class DamageEntry {
        private final UUID playerUuid;
        private final long damage;

        public DamageEntry(UUID playerUuid, long damage) {
            this.playerUuid = playerUuid;
            this.damage = damage;
        }

        public UUID playerUuid() {
            return playerUuid;
        }

        public long damage() {
            return damage;
        }
    }
}
