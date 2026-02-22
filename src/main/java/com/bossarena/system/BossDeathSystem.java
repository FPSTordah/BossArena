package com.bossarena.system;

import com.bossarena.loot.BossLootHandler;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.logging.Logger;

public class BossDeathSystem extends DeathSystems.OnDeathSystem {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private final BossTrackingSystem trackingSystem;

    public BossDeathSystem(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return TransformComponent.getComponentType();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull DeathComponent component,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        // Get entity UUID from UUIDComponent
        Object uuidCompObj = store.getComponent(ref, UUIDComponent.getComponentType());
        if (!(uuidCompObj instanceof UUIDComponent)) {
            return;
        }

        UUIDComponent uuidComp = (UUIDComponent) uuidCompObj;
        UUID entityUuid = uuidComp.getUuid();

        if (trackingSystem.isTracked(entityUuid)) {
            handleTrackedBossDeath(entityUuid);
            return;
        }

        if (trackingSystem.isTrackedAdd(entityUuid)) {
            handleTrackedAddDeath(entityUuid);
        }
    }

    private void handleTrackedBossDeath(UUID bossUuid) {
        LOGGER.info("🎯 BOSS DIED! UUID: " + bossUuid);
        BossTrackingSystem.BossEventContext eventContext = trackingSystem.getEventContext(bossUuid);
        int activeAdds = trackingSystem.getActiveAddCount(bossUuid);
        BossTrackingSystem.PendingLootData pendingLoot = trackingSystem.markBossDead(bossUuid);

        if (pendingLoot != null) {
            LOGGER.info("Boss '" + pendingLoot.bossName + "' died with no active adds, spawning loot now.");
            BossWaveNotificationService.notifyBossAliveStatus(
                    pendingLoot.world,
                    pendingLoot.spawnLocation,
                    pendingLoot.bossName,
                    0,
                    0,
                    null
            );
            BossLootHandler.queueLootSpawn(pendingLoot.world, pendingLoot.spawnLocation, pendingLoot.bossName);
            return;
        }

        LOGGER.info("Boss died but waiting on " + activeAdds + " active add(s) before spawning loot chest.");
        if (eventContext != null) {
            BossWaveNotificationService.notifyBossAliveStatus(
                    eventContext.world,
                    eventContext.spawnLocation,
                    eventContext.bossName,
                    0,
                    activeAdds,
                    null
            );
        }
    }

    private void handleTrackedAddDeath(UUID addUuid) {
        UUID bossUuid = trackingSystem.getBossUuidForAdd(addUuid);
        BossTrackingSystem.BossEventContext eventContext = trackingSystem.getEventContext(bossUuid);
        BossTrackingSystem.PendingLootData pendingLoot = trackingSystem.handleTrackedAddDeath(addUuid);
        int remainingAdds = bossUuid != null ? trackingSystem.getActiveAddCount(bossUuid) : 0;

        if (eventContext != null) {
            if (bossUuid != null && trackingSystem.isTracked(bossUuid)) {
                BossWaveNotificationService.notifyBossAliveStatus(
                        eventContext.world,
                        eventContext.spawnLocation,
                        eventContext.bossName,
                        1,
                        remainingAdds,
                        null
                );
            } else {
                BossWaveNotificationService.notifyBossAliveStatus(
                        eventContext.world,
                        eventContext.spawnLocation,
                        eventContext.bossName,
                        0,
                        remainingAdds,
                        null
                );
            }
        }

        if (pendingLoot == null) {
            return;
        }

        LOGGER.info("All tracked adds dead for boss '" + pendingLoot.bossName + "', spawning loot chest.");
        BossLootHandler.queueLootSpawn(pendingLoot.world, pendingLoot.spawnLocation, pendingLoot.bossName);
    }
}
