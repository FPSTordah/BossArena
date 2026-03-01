package com.bossarena.system;

import com.bossarena.BossArenaPlugin;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles immediate cleanup when tracked NPCs are forcibly removed (e.g., via admin wipe).
 * This bypasses the missing-entity grace period and stops the event + clears UI instantly.
 */
public final class BossEntityRemovedSystem extends RefSystem<EntityStore> {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private final BossTrackingSystem trackingSystem;

    public BossEntityRemovedSystem(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
    }

    @Override
    public Query<EntityStore> getQuery() {
        // Listen for removal of any entity with a UUID (covers NPCs)
        return UUIDComponent.getComponentType();
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // no-op
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (trackingSystem == null) {
            return;
        }

        Object uuidObj = store.getComponent(ref, UUIDComponent.getComponentType());
        if (!(uuidObj instanceof UUIDComponent uuidComponent)) {
            return;
        }
        UUID entityUuid = uuidComponent.getUuid();
        if (entityUuid == null) {
            return;
        }

        boolean isBoss = trackingSystem.isTracked(entityUuid);
        boolean isAdd = trackingSystem.isTrackedAdd(entityUuid);
        if (!isBoss && !isAdd) {
            return;
        }

        // Only act immediately on explicit REMOVE (admin wipe / force remove / kill).
        // Allow UNLOAD to be handled by reconcile (grace period).
        if (reason != RemoveReason.REMOVE) {
            return;
        }

        var plugin = BossArenaPlugin.getInstance();

        if (isBoss) {
            // Snapshot event members before canceling to clean up everything.
            BossTrackingSystem.EventMembersSnapshot snapshot = trackingSystem.snapshotEventMembersForBoss(entityUuid);

            // Use untrackAndCancel for immediate system cleanup without loot.
            BossTrackingSystem.BossEventContext context = trackingSystem.untrackAndCancel(entityUuid);

            if (snapshot != null) {
                // Clear map markers for all bosses in the event.
                if (plugin != null && plugin.getTimedBossMapMarkerService() != null) {
                    for (UUID bossUuid : snapshot.bossUuids) {
                        plugin.getTimedBossMapMarkerService().onTimedBossDespawn(snapshot.world, bossUuid);
                    }
                }

                // Send final UI clear (NO VICTORY)
                BossWaveNotificationService.notifyBossAliveStatus(
                        snapshot.world,
                        snapshot.eventCenter,
                        snapshot.bossName,
                        0,
                        0,
                        null,
                        0L,
                        false,
                        false
                );
                LOGGER.info("Immediate removal (" + reason + ") handled for event: " + snapshot.bossName);
            } else if (context != null) {
                // Lone boss cleanup
                if (plugin != null && plugin.getTimedBossMapMarkerService() != null) {
                    plugin.getTimedBossMapMarkerService().onTimedBossDespawn(context.world, entityUuid);
                }
                BossWaveNotificationService.notifyBossAliveStatus(
                        context.world,
                        context.spawnLocation,
                        context.bossName,
                        0,
                        0,
                        null,
                        0L,
                        false,
                        false
                );
                LOGGER.info("Immediate removal (" + reason + ") handled for lone boss: " + context.bossName);
            }
            return;
        }

        if (isAdd) {
            // Forcibly removed add: just drop it from tracking without loot. Let the event continue if bosses remain.
            // We can't call internal clear directly; simulate by marking add death but suppress any side-effects after.
            // Since a boss REMOVE will cancel the event above, this path usually won't spawn loot.
            trackingSystem.handleTrackedAddDeath(entityUuid);
            LOGGER.info("Admin removal detected: cleared tracked add " + entityUuid);
        }
    }
}
