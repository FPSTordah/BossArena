package com.bossarena.util;

import com.bossarena.BossArenaPlugin;
import com.bossarena.loot.BossLootHandler;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized logic for removing BossArena entities from a world (tracked bosses/adds and
 * entities with BossArena interaction IDs). Call from the world thread.
 */
public final class BossArenaCleanup {

    private BossArenaCleanup() {}

    /**
     * Removes BossArena entities in the given world: those whose UUID is in {@code trackedUuids},
     * or that have {@link BossArenaPlugin#NO_DEATH_DROPS_INTERACTION_ID} or
     * {@link BossArenaPlugin#SHOP_OPEN_INTERACTION_ID}. Optionally runs {@link BossLootHandler#cleanupAllChests}.
     * Must be called from the world's thread.
     *
     * @param world         the world to sweep
     * @param trackedUuids  UUIDs of tracked bosses/adds (from BossTrackingSystem)
     * @param cleanupChests whether to call BossLootHandler.cleanupAllChests(world)
     * @return number of entities removed
     */
    public static int removeBossArenaEntitiesInWorld(World world, Set<UUID> trackedUuids, boolean cleanupChests) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Query<EntityStore> uuidQuery = UUIDComponent.getComponentType();
        AtomicInteger removedCount = new AtomicInteger(0);

        store.forEachChunk(uuidQuery, (chunk, buffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                UUIDComponent uuidComp = (UUIDComponent) chunk.getComponent(i, UUIDComponent.getComponentType());
                UUID uuid = uuidComp != null ? uuidComp.getUuid() : null;

                boolean shouldRemove = false;
                if (uuid != null && trackedUuids.contains(uuid)) {
                    shouldRemove = true;
                } else {
                    Interactions interactions = (Interactions) chunk.getComponent(i, Interactions.getComponentType());
                    if (interactions != null) {
                        String deathId = interactions.getInteractionId(InteractionType.Death);
                        String interactId = interactions.getInteractionId(InteractionType.Use);
                        if (BossArenaPlugin.NO_DEATH_DROPS_INTERACTION_ID.equals(deathId)
                                || BossArenaPlugin.SHOP_OPEN_INTERACTION_ID.equals(interactId)) {
                            shouldRemove = true;
                        }
                    }
                }

                if (shouldRemove) {
                    buffer.removeEntity(ref, com.hypixel.hytale.component.RemoveReason.REMOVE);
                    removedCount.incrementAndGet();
                }
            }
        });

        if (cleanupChests) {
            BossLootHandler.cleanupAllChests(world);
        }
        return removedCount.get();
    }
}
