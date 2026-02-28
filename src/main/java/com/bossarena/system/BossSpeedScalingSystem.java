package com.bossarena.system;

import com.bossarena.boss.BossModifiers;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerBase;
import com.hypixel.hytale.server.npc.role.Role;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BossSpeedScalingSystem extends TickingSystem<EntityStore> {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final float UPDATE_INTERVAL_SECONDS = 0.25f;
    private static final float EPSILON = 0.0001f;
    private static final float NPC_SPEED_UNSET = Float.MAX_VALUE;
    private static final Field NPC_CACHED_SPEED_FIELD = resolveCachedSpeedField();
    private static final Field INTERACTION_MANAGER_COOLDOWN_HANDLER_FIELD = resolveField(InteractionManager.class, "cooldownHandler");
    private static final Field COOLDOWN_HANDLER_COOLDOWNS_FIELD = resolveField(CooldownHandler.class, "cooldowns");
    private static final Field MOTION_CONTROLLER_MAX_HEAD_ROTATION_SPEED_FIELD = resolveField(MotionControllerBase.class, "maxHeadRotationSpeed");

    private final BossTrackingSystem trackingSystem;
    private final Map<UUID, Float> lastHealthByEntity = new ConcurrentHashMap<>();
    private final Map<MotionControllerBase, Float> baseTurnRateByController =
            Collections.synchronizedMap(new WeakHashMap<>());
    private float elapsedSeconds;

    public BossSpeedScalingSystem(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
    }

    @Override
    public void tick(float dt, int index, @Nonnull Store<EntityStore> store) {
        if (trackingSystem == null || NPC_CACHED_SPEED_FIELD == null) {
            return;
        }

        elapsedSeconds += Math.max(0f, dt);
        if (elapsedSeconds < UPDATE_INTERVAL_SECONDS) {
            return;
        }
        elapsedSeconds = 0f;

        Set<UUID> activeTrackedEntities = new HashSet<>();

        for (Map.Entry<UUID, BossTrackingSystem.BossData> entry : trackingSystem.snapshotTrackedBosses().entrySet()) {
            UUID entityUuid = entry.getKey();
            BossTrackingSystem.BossData data = entry.getValue();
            if (entityUuid == null || data == null) {
                continue;
            }
            activeTrackedEntities.add(entityUuid);
            applyRuntimeScalers(entityUuid, data.world, data.modifiers);
        }

        for (Map.Entry<UUID, UUID> entry : trackingSystem.snapshotTrackedAdds().entrySet()) {
            UUID addUuid = entry.getKey();
            UUID bossUuid = entry.getValue();
            if (addUuid == null || bossUuid == null) {
                continue;
            }

            BossTrackingSystem.BossData ownerBoss = trackingSystem.getBossData(bossUuid);
            World world = ownerBoss != null ? ownerBoss.world : null;
            if (world == null) {
                BossTrackingSystem.BossEventContext eventContext = trackingSystem.getEventContext(bossUuid);
                world = eventContext != null ? eventContext.world : null;
            }
            if (world == null) {
                continue;
            }

            BossModifiers addModifiers = trackingSystem.getEntityModifiers(addUuid);
            activeTrackedEntities.add(addUuid);
            applyRuntimeScalers(addUuid, world, addModifiers);
        }

        lastHealthByEntity.keySet().retainAll(activeTrackedEntities);
    }

    private void applyRuntimeScalers(UUID entityUuid, World world, BossModifiers modifiers) {
        if (entityUuid == null || world == null || modifiers == null) {
            return;
        }

        applySpeedMultiplier(entityUuid, world, modifiers);
        applyTurnRateMultiplier(entityUuid, world, modifiers);
        applyInteractionCooldownScaling(entityUuid, world, modifiers);
        applyRegenerationScaling(entityUuid, world, modifiers);
    }

    private void applySpeedMultiplier(UUID entityUuid, World world, BossModifiers modifiers) {
        float speedMultiplier = clampMultiplier(modifiers.speedMultiplier());
        if (!Float.isFinite(speedMultiplier)) {
            return;
        }

        try {
            var entityRef = world.getEntityRef(entityUuid);
            if (entityRef == null || !entityRef.isValid()) {
                return;
            }

            Store<EntityStore> worldStore = world.getEntityStore().getStore();
            Object npcObj = worldStore.getComponent(entityRef, NPCEntity.getComponentType());
            if (!(npcObj instanceof NPCEntity npcEntity)) {
                return;
            }

            npcEntity.invalidateCachedHorizontalSpeedMultiplier();
            float naturalSpeed = npcEntity.getCurrentHorizontalSpeedMultiplier(entityRef, worldStore);
            if (!Float.isFinite(naturalSpeed)) {
                naturalSpeed = 1.0f;
            }
            float desiredSpeed = clampMultiplier(naturalSpeed * speedMultiplier);
            float current = NPC_CACHED_SPEED_FIELD.getFloat(npcEntity);
            if (Math.abs(current - desiredSpeed) > EPSILON || current == NPC_SPEED_UNSET) {
                NPC_CACHED_SPEED_FIELD.setFloat(npcEntity, desiredSpeed);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to apply movement speed scaling for entity " + entityUuid, e);
        }
    }

    private void applyTurnRateMultiplier(UUID entityUuid, World world, BossModifiers modifiers) {
        if (MOTION_CONTROLLER_MAX_HEAD_ROTATION_SPEED_FIELD == null) {
            return;
        }

        float turnRateMultiplier = clampMultiplier(modifiers.turnRateMultiplier());
        if (!Float.isFinite(turnRateMultiplier)) {
            return;
        }

        try {
            var entityRef = world.getEntityRef(entityUuid);
            if (entityRef == null || !entityRef.isValid()) {
                return;
            }

            Store<EntityStore> worldStore = world.getEntityStore().getStore();
            Object npcObj = worldStore.getComponent(entityRef, NPCEntity.getComponentType());
            if (!(npcObj instanceof NPCEntity npcEntity)) {
                return;
            }

            Role role = npcEntity.getRole();
            if (role == null) {
                return;
            }
            MotionController controller = role.getActiveMotionController();
            if (!(controller instanceof MotionControllerBase motionController)) {
                return;
            }

            float baseRate = baseTurnRateByController.computeIfAbsent(motionController, ignored -> {
                try {
                    return MOTION_CONTROLLER_MAX_HEAD_ROTATION_SPEED_FIELD.getFloat(motionController);
                } catch (IllegalAccessException e) {
                    return 360.0f;
                }
            });

            float desired = Math.max(EPSILON, baseRate * turnRateMultiplier);
            float current = MOTION_CONTROLLER_MAX_HEAD_ROTATION_SPEED_FIELD.getFloat(motionController);
            if (Math.abs(current - desired) > EPSILON) {
                MOTION_CONTROLLER_MAX_HEAD_ROTATION_SPEED_FIELD.setFloat(motionController, desired);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to apply turn rate scaling for entity " + entityUuid, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyInteractionCooldownScaling(UUID entityUuid, World world, BossModifiers modifiers) {
        if (INTERACTION_MANAGER_COOLDOWN_HANDLER_FIELD == null || COOLDOWN_HANDLER_COOLDOWNS_FIELD == null) {
            return;
        }

        float attackRateMultiplier = clampMultiplier(modifiers.attackRateMultiplier());
        float abilityCooldownMultiplier = clampMultiplier(modifiers.abilityCooldownMultiplier());
        if (!Float.isFinite(attackRateMultiplier) || !Float.isFinite(abilityCooldownMultiplier)) {
            return;
        }

        float cooldownTickFactor = attackRateMultiplier / abilityCooldownMultiplier;
        if (!Float.isFinite(cooldownTickFactor) || Math.abs(cooldownTickFactor - 1.0f) <= EPSILON) {
            return;
        }

        try {
            var entityRef = world.getEntityRef(entityUuid);
            if (entityRef == null || !entityRef.isValid()) {
                return;
            }

            Store<EntityStore> worldStore = world.getEntityStore().getStore();
            Object interactionManagerObj = worldStore.getComponent(
                    entityRef,
                    InteractionModule.get().getInteractionManagerComponent()
            );
            if (!(interactionManagerObj instanceof InteractionManager interactionManager)) {
                return;
            }

            Object cooldownHandlerObj = INTERACTION_MANAGER_COOLDOWN_HANDLER_FIELD.get(interactionManager);
            if (!(cooldownHandlerObj instanceof CooldownHandler cooldownHandler)) {
                return;
            }

            float desiredTick = UPDATE_INTERVAL_SECONDS * cooldownTickFactor;
            float adjustment = desiredTick - UPDATE_INTERVAL_SECONDS;
            if (Math.abs(adjustment) <= EPSILON) {
                return;
            }

            if (adjustment > 0.0f) {
                cooldownHandler.tick(adjustment);
                return;
            }

            Object cooldownsObj = COOLDOWN_HANDLER_COOLDOWNS_FIELD.get(cooldownHandler);
            if (!(cooldownsObj instanceof Map<?, ?> cooldowns)) {
                return;
            }

            float increase = -adjustment;
            for (Object cooldownObj : cooldowns.values()) {
                if (cooldownObj instanceof CooldownHandler.Cooldown cooldown) {
                    cooldown.increaseTime(increase);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to apply cooldown scaling for entity " + entityUuid, e);
        }
    }

    private void applyRegenerationScaling(UUID entityUuid, World world, BossModifiers modifiers) {
        float regenMultiplier = clampMultiplier(modifiers.regenMultiplier());
        if (!Float.isFinite(regenMultiplier)) {
            regenMultiplier = 1.0f;
        }

        try {
            var entityRef = world.getEntityRef(entityUuid);
            if (entityRef == null || !entityRef.isValid()) {
                return;
            }

            Store<EntityStore> worldStore = world.getEntityStore().getStore();
            Object statMapObj = worldStore.getComponent(entityRef, EntityStatMap.getComponentType());
            if (!(statMapObj instanceof EntityStatMap statMap)) {
                return;
            }

            int healthIndex = DefaultEntityStatTypes.getHealth();
            if (healthIndex <= 0) {
                healthIndex = 1;
            }

            EntityStatValue health = statMap.get(healthIndex);
            if (health == null) {
                return;
            }

            float current = health.get();
            if (!Float.isFinite(current)) {
                return;
            }

            Float previous = lastHealthByEntity.put(entityUuid, current);
            if (previous == null || !Float.isFinite(previous) || Math.abs(regenMultiplier - 1.0f) <= EPSILON) {
                return;
            }

            float gained = current - previous;
            if (gained <= EPSILON) {
                return;
            }

            float scaledGain = gained * regenMultiplier;
            float target = previous + scaledGain;
            target = Math.max(health.getMin(), Math.min(health.getMax(), target));
            if (Math.abs(target - current) <= EPSILON || !Float.isFinite(target)) {
                return;
            }

            statMap.setStatValue(healthIndex, target);
            lastHealthByEntity.put(entityUuid, target);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to apply regeneration scaling for entity " + entityUuid, e);
        }
    }

    private static float clampMultiplier(float value) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            return 1.0f;
        }
        return value;
    }

    private static Field resolveField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Exception e) {
            LOGGER.warning("Boss scaling support disabled for " + owner.getSimpleName() + "." + name + ".");
            return null;
        }
    }

    private static Field resolveCachedSpeedField() {
        try {
            Field field = NPCEntity.class.getDeclaredField("cachedEntityHorizontalSpeedMultiplier");
            field.setAccessible(true);
            return field;
        } catch (Exception e) {
            LOGGER.warning("Boss speed scaling disabled: unable to access NPC speed cache field.");
            return null;
        }
    }
}
