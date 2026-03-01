package com.bossarena.system;

import com.bossarena.BossArenaPlugin;
import com.bossarena.loot.BossLootHandler;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;
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

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.BEFORE, NPCDamageSystems.DropDeathItems.class));
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
        boolean trackedBoss = trackingSystem.isTracked(entityUuid);
        boolean trackedAdd = trackingSystem.isTrackedAdd(entityUuid);

        if (trackedBoss || trackedAdd) {
            // Prevent NPCDamageSystems.DropDeathItems from generating world loot.
            component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);
        }

        if (trackedBoss) {
            handleTrackedBossDeath(entityUuid);
            return;
        }

        if (trackedAdd) {
            handleTrackedAddDeath(entityUuid);
        }
    }

    private void handleTrackedBossDeath(UUID bossUuid) {
        LOGGER.info("🎯 BOSS DIED! UUID: " + bossUuid);

        // Cleanup map marker
        var plugin = BossArenaPlugin.getInstance();
        if (plugin != null && plugin.getTimedBossMapMarkerService() != null) {
            BossTrackingSystem.BossData data = trackingSystem.getBossData(bossUuid);
            if (data != null) {
                plugin.getTimedBossMapMarkerService().onTimedBossDespawn(data.world, bossUuid);
            }
        }

        // Capture context BEFORE marking dead (as that removes the data)
        BossTrackingSystem.BossEventContext eventContext = trackingSystem.getEventContext(bossUuid);
        BossTrackingSystem.PendingLootData pendingLoot = trackingSystem.markBossDead(bossUuid);

        // Capture counts AFTER marking dead for accurate remaining status
        int aliveBosses = trackingSystem.getAliveBossCount(bossUuid);
        int activeAdds = trackingSystem.getActiveAddCountForEvent(bossUuid);

        if (pendingLoot != null) {
            LOGGER.info("Boss event for '" + pendingLoot.bossName + "' is complete, spawning one loot chest.");

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
            return;
        }

        LOGGER.info("Boss died but event still has " + aliveBosses + " boss(es) and " + activeAdds + " add(s) alive.");
        if (eventContext != null) {
            BossWaveNotificationService.notifyBossAliveStatus(
                    eventContext.world,
                    eventContext.spawnLocation,
                    eventContext.bossName,
                    aliveBosses,
                    activeAdds,
                    null,
                    eventContext.remainingCountdownMillis
            );
        }
    }

    private void handleTrackedAddDeath(UUID addUuid) {
        UUID bossUuid = trackingSystem.getBossUuidForAdd(addUuid);
        BossTrackingSystem.BossEventContext eventContext = trackingSystem.getEventContext(bossUuid);
        BossTrackingSystem.PendingLootData pendingLoot = trackingSystem.handleTrackedAddDeath(addUuid);
        int remainingAdds = bossUuid != null ? trackingSystem.getActiveAddCountForEvent(bossUuid) : 0;
        int remainingBosses = bossUuid != null ? trackingSystem.getAliveBossCount(bossUuid) : 0;

        if (pendingLoot != null) {
            LOGGER.info("All bosses and tracked adds are dead for '" + pendingLoot.bossName + "', spawning one loot chest.");

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
            return;
        }

        if (eventContext != null) {
            BossWaveNotificationService.notifyBossAliveStatus(
                    eventContext.world,
                    eventContext.spawnLocation,
                    eventContext.bossName,
                    remainingBosses,
                    remainingAdds,
                    null,
                    eventContext.remainingCountdownMillis
            );
        }
    }
}
