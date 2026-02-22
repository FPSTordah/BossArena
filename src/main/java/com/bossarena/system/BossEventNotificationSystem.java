package com.bossarena.system;

import com.bossarena.loot.BossLootHandler;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;

public final class BossEventNotificationSystem extends TickingSystem<EntityStore> {
    private static final float UPDATE_INTERVAL_SECONDS = 1.0f;

    private final BossTrackingSystem trackingSystem;
    private float elapsedSeconds;

    public BossEventNotificationSystem(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
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
            BossWaveNotificationService.notifyBossAliveStatus(
                    event.world,
                    event.eventCenter,
                    event.bossName,
                    event.aliveBossCount,
                    event.activeAddCount,
                    null,
                    event.remainingCountdownMillis
            );
        }
    }

    private void reconcileMissingTrackedEntities() {
        for (Map.Entry<UUID, BossTrackingSystem.BossData> entry : trackingSystem.snapshotTrackedBosses().entrySet()) {
            UUID bossUuid = entry.getKey();
            BossTrackingSystem.BossData bossData = entry.getValue();
            if (bossUuid == null || bossData == null) {
                continue;
            }
            if (!isEntityMissing(bossData.world, bossUuid)) {
                continue;
            }

            BossTrackingSystem.PendingLootData pendingLoot = trackingSystem.markBossDead(bossUuid);
            BossTrackingSystem.BossEventContext eventContext = trackingSystem.getEventContext(bossUuid);

            if (pendingLoot != null) {
                BossWaveNotificationService.notifyBossAliveStatus(
                        pendingLoot.world,
                        pendingLoot.spawnLocation,
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

        for (Map.Entry<UUID, UUID> entry : trackingSystem.snapshotTrackedAdds().entrySet()) {
            UUID addUuid = entry.getKey();
            UUID bossUuid = entry.getValue();
            if (addUuid == null || bossUuid == null) {
                continue;
            }

            BossTrackingSystem.BossData bossData = trackingSystem.getBossData(bossUuid);
            BossTrackingSystem.BossEventContext eventContext = trackingSystem.getEventContext(bossUuid);
            World world = bossData != null ? bossData.world : (eventContext != null ? eventContext.world : null);
            if (!isEntityMissing(world, addUuid)) {
                continue;
            }

            BossTrackingSystem.PendingLootData pendingLoot = trackingSystem.handleTrackedAddDeath(addUuid);
            eventContext = trackingSystem.getEventContext(bossUuid);

            if (pendingLoot != null) {
                BossWaveNotificationService.notifyBossAliveStatus(
                        pendingLoot.world,
                        pendingLoot.spawnLocation,
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
}
