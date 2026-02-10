package com.bossarena.loot;

import com.bossarena.boss.BossFightComponent;
import com.bossarena.system.BossTrackingSystem;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.logging.Logger;

public class BossDeathListener {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private final BossTrackingSystem trackingSystem;

    public BossDeathListener(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
    }

    public void onEntityRemoved(@Nonnull Ref<EntityStore> entityRef, @Nonnull RemoveReason reason, @Nonnull Store<EntityStore> store, World world) {
        // Check if this entity has BossFightComponent (is a boss)
        Object bossFightObj = store.getComponent(entityRef, BossFightComponent.getComponentType());
        if (!(bossFightObj instanceof BossFightComponent)) {
            return; // Not a boss
        }

        // Get UUID
        Object uuidObj = store.getComponent(entityRef, UUIDComponent.getComponentType());
        if (!(uuidObj instanceof UUIDComponent uuidComp)) {
            return;
        }

        UUID bossUuid = uuidComp.getUuid();

        // Check if boss died (health <= 0)
        boolean diedFromDamage = false;
        try {
            Object statMapObj = store.getComponent(entityRef, EntityStatMap.getComponentType());
            if (statMapObj instanceof EntityStatMap statMap) {
                var assetMap = EntityStatType.getAssetMap();
                int healthIndex = assetMap.getIndex("Health");
                if (healthIndex >= 0) {
                    var healthValue = statMap.get(healthIndex);
                    if (healthValue != null && healthValue.get() <= 0) {
                        diedFromDamage = true;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Error checking boss health: " + e.getMessage());
        }

        if (!diedFromDamage) {
            LOGGER.info("Boss removed but didn't die from damage (despawned/removed)");
            trackingSystem.untrack(bossUuid);
            return;
        }

        // Get boss position
        Vector3d deathLocation = getBossPosition(entityRef, store);

        // Find which boss definition this was
        String bossName = trackingSystem.getBossName(bossUuid);

        if (bossName != null && deathLocation != null) {
            LOGGER.info("Boss died: " + bossName + " at " + deathLocation);

            // Handle loot drop
            BossLootHandler.handleBossDeath(world, deathLocation, bossName);
        }

        // Remove from tracking
        trackingSystem.untrack(bossUuid);
    }

    private Vector3d getBossPosition(Ref<EntityStore> entityRef, Store<EntityStore> store) {
        // TODO: Get entity position from Hytale API
        // Need to find the position component
        LOGGER.warning("Position retrieval not implemented - returning default");
        return new Vector3d(0, 100, 0); // Placeholder
    }
}
