package com.bossarena.system;

import com.bossarena.BossArenaConfig;
import com.bossarena.BossArenaPlugin;
import com.bossarena.data.Arena;
import com.bossarena.data.ArenaRegistry;
import com.bossarena.loot.BossLootHandler;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;

public final class BossEventNotificationSystem extends TickingSystem<EntityStore> {
    private static final float UPDATE_INTERVAL_SECONDS = 1.0f;
    private static final long MISSING_RECONCILE_GRACE_MS = 15_000L;
    private static final double MISSING_RECONCILE_PLAYER_RADIUS = 192.0d;

    private final BossTrackingSystem trackingSystem;
    private final Map<UUID, Long> missingBossSince = new ConcurrentHashMap<>();
    private final Map<UUID, Long> missingAddSince = new ConcurrentHashMap<>();
    private float elapsedSeconds;

    public BossEventNotificationSystem(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
    }

    private static boolean isEntityMissing(World world, UUID entityUuid) {
        if (world == null || entityUuid == null) {
            return true;
        }
        try {
            var entityRef = world.getEntityRef(entityUuid);
            return entityRef == null || !entityRef.isValid();
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean isMissingReconcileEligible(World world, Vector3d anchor) {
        if (world == null || anchor == null) {
            return false;
        }
        try {
            for (var player : world.getPlayers()) {
                if (player == null || player.getTransformComponent() == null) {
                    continue;
                }
                Vector3d playerPos = player.getTransformComponent().getPosition();
                if (playerPos == null) {
                    continue;
                }
                if (playerPos.distanceSquaredTo(anchor) <= (MISSING_RECONCILE_PLAYER_RADIUS * MISSING_RECONCILE_PLAYER_RADIUS)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Best effort only; if this fails we skip reconcile.
        }
        return false;
    }

    @Override
    public void tick(float dt, int index, @Nonnull Store<EntityStore> store) {
        if (trackingSystem == null) {
            return;
        }

        elapsedSeconds += Math.max(0f, dt);
        if (elapsedSeconds < UPDATE_INTERVAL_SECONDS) {
            return;
        }
        elapsedSeconds = 0f;

        reconcileMissingTrackedEntities();

        for (BossTrackingSystem.ActiveEventStatus event : trackingSystem.snapshotActiveEvents()) {
            if (event == null) {
                continue;
            }
            double notificationRadius = -1.0d;
            if (event.arenaId != null && !event.arenaId.isBlank()) {
                Arena arena = ArenaRegistry.get(event.arenaId);
                if (arena != null) {
                    notificationRadius = arena.getNotificationRadius();
                }
            }
            if (!Double.isFinite(notificationRadius) || notificationRadius <= 0) {
                BossArenaConfig config = BossArenaPlugin.getInstance() != null ? BossArenaPlugin.getInstance().getConfigHandle() : null;
                notificationRadius = (config != null) ? config.getNotificationRadius() : 100.0d;
            }
            BossWaveNotificationService.notifyBossAliveStatus(
                    event.world,
                    event.eventCenter,
                    event.bossName,
                    event.aliveBossCount,
                    event.activeAddCount,
                    event.awaitingPrimaryBossSpawn ? "Preparing encounter" : null,
                    event.remainingCountdownMillis,
                    true,
                    true,
                    notificationRadius
            );
        }
    }

    private void reconcileMissingTrackedEntities() {
        long now = System.currentTimeMillis();
        Map<UUID, BossTrackingSystem.BossData> trackedBosses = trackingSystem.snapshotTrackedBosses();
        Map<UUID, UUID> trackedAdds = trackingSystem.snapshotTrackedAdds();
        missingBossSince.keySet().retainAll(trackedBosses.keySet());
        missingAddSince.keySet().retainAll(trackedAdds.keySet());

        for (Map.Entry<UUID, BossTrackingSystem.BossData> entry : trackedBosses.entrySet()) {
            UUID bossUuid = entry.getKey();
            BossTrackingSystem.BossData bossData = entry.getValue();
            if (bossUuid == null || bossData == null) {
                continue;
            }
            if (!isMissingReconcileEligible(bossData.world, bossData.spawnLocation)) {
                missingBossSince.remove(bossUuid);
                continue;
            }
            if (!isEntityMissing(bossData.world, bossUuid)) {
                missingBossSince.remove(bossUuid);
                continue;
            }
            long missingSince = missingBossSince.computeIfAbsent(bossUuid, ignored -> now);
            if ((now - missingSince) < MISSING_RECONCILE_GRACE_MS) {
                continue;
            }
            missingBossSince.remove(bossUuid);

            // Capture context BEFORE marking dead
            BossTrackingSystem.BossEventContext eventContext = trackingSystem.getEventContext(bossUuid);
            BossTrackingSystem.PendingLootData pendingLoot = trackingSystem.markBossDead(bossUuid);

            // Cleanup map marker
            var plugin = BossArenaPlugin.getInstance();
            if (plugin != null && plugin.getTimedBossMapMarkerService() != null) {
                plugin.getTimedBossMapMarkerService().onTimedBossDespawn(bossData.world, bossUuid);
            }

            if (pendingLoot != null) {
                // Clear any remaining boss markers if event completed
                if (plugin != null && plugin.getTimedBossMapMarkerService() != null) {
                    for (java.util.UUID uuid : pendingLoot.bossUuids) {
                        plugin.getTimedBossMapMarkerService().onTimedBossDespawn(pendingLoot.world, uuid);
                    }
                }

                BossWaveNotificationService.notifyBossAliveStatus(
                        pendingLoot.world,
                        pendingLoot.eventCenter != null ? pendingLoot.eventCenter : pendingLoot.spawnLocation,
                        pendingLoot.bossName,
                        0,
                        0,
                        null,
                        0L
                );
                BossLootHandler.queueLootSpawn(pendingLoot.world, pendingLoot.spawnLocation, pendingLoot.bossName);
                continue;
            }

            if (eventContext != null) {
                BossWaveNotificationService.notifyBossAliveStatus(
                        eventContext.world,
                        eventContext.spawnLocation,
                        eventContext.bossName,
                        trackingSystem.getAliveBossCount(bossUuid),
                        trackingSystem.getActiveAddCountForEvent(bossUuid),
                        null,
                        eventContext.remainingCountdownMillis
                );
            }
        }

        for (Map.Entry<UUID, UUID> entry : trackedAdds.entrySet()) {
            UUID addUuid = entry.getKey();
            UUID bossUuid = entry.getValue();
            if (addUuid == null || bossUuid == null) {
                continue;
            }

            BossTrackingSystem.BossData bossData = trackingSystem.getBossData(bossUuid);
            BossTrackingSystem.BossEventContext eventContext = trackingSystem.getEventContext(bossUuid);
            World world = bossData != null ? bossData.world : (eventContext != null ? eventContext.world : null);
            Vector3d anchor = bossData != null ? bossData.spawnLocation : (eventContext != null ? eventContext.spawnLocation : null);
            if (!isMissingReconcileEligible(world, anchor)) {
                missingAddSince.remove(addUuid);
                continue;
            }
            if (!isEntityMissing(world, addUuid)) {
                missingAddSince.remove(addUuid);
                continue;
            }
            long missingSince = missingAddSince.computeIfAbsent(addUuid, ignored -> now);
            if ((now - missingSince) < MISSING_RECONCILE_GRACE_MS) {
                continue;
            }
            missingAddSince.remove(addUuid);

            // Capture context BEFORE marking dead
            eventContext = trackingSystem.getEventContext(bossUuid);
            BossTrackingSystem.PendingLootData pendingLoot = trackingSystem.handleTrackedAddDeath(addUuid);

            if (pendingLoot != null) {
                // Cleanup all boss markers for the completed event
                var plugin = BossArenaPlugin.getInstance();
                if (plugin != null && plugin.getTimedBossMapMarkerService() != null) {
                    for (java.util.UUID buuid : pendingLoot.bossUuids) {
                        plugin.getTimedBossMapMarkerService().onTimedBossDespawn(pendingLoot.world, buuid);
                    }
                }

                BossWaveNotificationService.notifyBossAliveStatus(
                        pendingLoot.world,
                        pendingLoot.eventCenter != null ? pendingLoot.eventCenter : pendingLoot.spawnLocation,
                        pendingLoot.bossName,
                        0,
                        0,
                        null,
                        0L
                );
                BossLootHandler.queueLootSpawn(pendingLoot.world, pendingLoot.spawnLocation, pendingLoot.bossName);
                continue;
            }

            if (eventContext != null) {
                BossWaveNotificationService.notifyBossAliveStatus(
                        eventContext.world,
                        eventContext.spawnLocation,
                        eventContext.bossName,
                        trackingSystem.getAliveBossCount(bossUuid),
                        trackingSystem.getActiveAddCountForEvent(bossUuid),
                        null,
                        eventContext.remainingCountdownMillis
                );
            }
        }
    }
}
