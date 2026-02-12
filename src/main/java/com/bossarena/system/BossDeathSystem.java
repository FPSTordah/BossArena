package com.bossarena.system;

import com.bossarena.loot.BossLootHandler;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.World;
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

        // Check if this is a tracked boss
        if (!trackingSystem.isTracked(entityUuid)) {
            return;
        }

        LOGGER.info("🎯 BOSS DIED! UUID: " + entityUuid);

        // Get boss data (includes spawn location and arena info)
        BossTrackingSystem.BossData bossData = trackingSystem.getBossData(entityUuid);
        if (bossData == null) {
            LOGGER.warning("Boss UUID tracked but no data found!");
            return;
        }

        // Use the SPAWN location, not death location
        Vector3d spawnLocation = bossData.spawnLocation;
        World world = bossData.world;

        LOGGER.info("Boss '" + bossData.bossName + "' died, spawning loot at original spawn location: " + spawnLocation);

        // Queue loot spawning at the spawn location
        BossLootHandler.queueLootSpawn(world, spawnLocation, bossData.bossName);

        // Untrack the boss
        trackingSystem.untrack(entityUuid);
    }
}