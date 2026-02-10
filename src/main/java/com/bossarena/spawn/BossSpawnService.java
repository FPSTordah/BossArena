package com.bossarena.spawn;

import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.bossarena.util.BossScaler;
import com.bossarena.boss.BossModifiers;
import com.bossarena.boss.BossFightComponent;
import com.bossarena.boss.PlayerFinder;
import com.bossarena.system.BossTrackingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;

import java.util.UUID;
import java.util.logging.Logger;

public final class BossSpawnService {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final String HEALTH_MODIFIER_KEY = "BossArena.HealthMultiplier";

    private final BossTrackingSystem tracking;
    private final long trackingTtlMs;

    public BossSpawnService(BossTrackingSystem tracking, long trackingTtlMs) {
        this.tracking = tracking;
        this.trackingTtlMs = trackingTtlMs;
    }

    public UUID spawnBossFromJson(@SuppressWarnings("unused") CommandSender sender, String bossId, World world, Vector3d spawnPos) {
        BossDefinition def = BossRegistry.get(bossId);
        if (def == null) {
            LOGGER.warning("Boss definition not found: " + bossId);
            return null;
        }

        LOGGER.info("Attempting to spawn boss '" + def.bossName + "' at position: " + spawnPos);

        // Calculate player-scaled modifiers
        var nearbyPlayers = PlayerFinder.playersInRadius(world, spawnPos, 40);
        int playerCount = nearbyPlayers.size();
        BossModifiers mods = BossScaler.calculateModifiers(def, playerCount);

        LOGGER.info("Boss modifiers calculated - HP: " + mods.hpMultiplier() + ", Damage: " + mods.damageMultiplier() + ", Size: " + mods.scaleMultiplier());

        UUID primaryBossUuid = null;

        // Spawn the boss(es)
        for (int i = 0; i < def.amount; i++) {
            Vector3d spreadPos = spawnPos.add(i * 1.2, 0, i * 1.2);

            LOGGER.info("Spawning NPC ID: " + def.npcId + " at: " + spreadPos);

            var result = NPCPlugin.get().spawnNPC(
                    world.getEntityStore().getStore(),
                    def.npcId,
                    null,
                    spreadPos,
                    new Vector3f(0, 0, 0)
            );

            if (result != null) {
                Ref<EntityStore> npcRef = result.first();
                var store = world.getEntityStore().getStore();

                Object uuidCompObj = store.getComponent(npcRef, UUIDComponent.getComponentType());
                if (uuidCompObj instanceof UUIDComponent uuidComp) {
                    UUID uuid = uuidComp.getUuid();

                    if (primaryBossUuid == null) {
                        primaryBossUuid = uuid;
                    }

                    tracking.track(uuid, def.bossName, mods, trackingTtlMs);
                    attachBossComponent(world, npcRef, mods);
                    applyModifiers(store, npcRef, mods);

                    LOGGER.info("Successfully spawned boss: " + def.bossName + " (" + uuid + ") at " + spreadPos);
                }
            } else {
                LOGGER.warning("NPCPlugin.spawnNPC returned null for NPC ID: " + def.npcId);
            }
        }

        // Schedule extra mobs if configured
        if (primaryBossUuid != null && def.extraMobs != null && def.extraMobs.npcId != null && !def.extraMobs.npcId.isBlank()) {
            scheduleExtraMobWaves(world, def, spawnPos, primaryBossUuid);
        }

        return primaryBossUuid;
    }

    private void applyModifiers(Store<EntityStore> store, Ref<EntityStore> entityRef, BossModifiers mods) {
        try {
            Object statMapObj = store.getComponent(entityRef, EntityStatMap.getComponentType());

            if (statMapObj instanceof EntityStatMap statMap) {
                LOGGER.info("Found EntityStatMap, applying modifiers...");

                var assetMap = EntityStatType.getAssetMap();

                // Apply Health modifier
                int healthIndex = assetMap.getIndex("Health");
                if (healthIndex >= 0) {
                    StaticModifier healthMod = new StaticModifier(
                            Modifier.ModifierTarget.MAX,
                            StaticModifier.CalculationType.MULTIPLICATIVE,
                            mods.hpMultiplier()
                    );
                    statMap.putModifier(healthIndex, HEALTH_MODIFIER_KEY, healthMod);
                    statMap.maximizeStatValue(healthIndex);

                    var healthValue = statMap.get(healthIndex);
                    float currentHealth = healthValue != null ? healthValue.get() : 0;
                    float maxHealth = healthValue != null ? healthValue.getMax() : 0;

                    LOGGER.info("Applied HP multiplier: " + mods.hpMultiplier());
                    LOGGER.info("Current HP: " + currentHealth + " / Max HP: " + maxHealth);
                } else {
                    LOGGER.warning("Health stat not found!");
                }
            } else {
                LOGGER.warning("EntityStatMap not found on entity");
            }

            // Apply Size modifier using EntityScaleComponent
            if (mods.scaleMultiplier() != 1.0f) {
                try {
                    Object scaleCompObj = store.getComponent(entityRef, EntityScaleComponent.getComponentType());

                    if (scaleCompObj instanceof EntityScaleComponent scaleComp) {
                        float originalScale = scaleComp.getScale();
                        float newScale = originalScale * mods.scaleMultiplier();

                        LOGGER.info("Found existing EntityScaleComponent - original scale: " + originalScale);
                        scaleComp.setScale(newScale);

                        LOGGER.info("Set scale to: " + newScale + " (multiplier: " + mods.scaleMultiplier() + ")");
                    } else {
                        // Component doesn't exist - create and add it
                        LOGGER.info("EntityScaleComponent not found - creating new one");
                        EntityScaleComponent newScaleComp = new EntityScaleComponent(mods.scaleMultiplier());
                        store.addComponent(entityRef, EntityScaleComponent.getComponentType(), newScaleComp);

                        LOGGER.info("Created and added EntityScaleComponent with scale: " + mods.scaleMultiplier());
                    }
                } catch (Exception e) {
                    LOGGER.warning("Failed to apply scale modifier: " + e.getMessage());
                    e.printStackTrace();
                }
            }

        } catch (Exception e) {
            LOGGER.warning("Failed to apply modifiers: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void scheduleExtraMobWaves(World world, BossDefinition def, Vector3d spawnPos, UUID bossUuid) {
        if (def.extraMobs.waves <= 0) return;

        for (int wave = 0; wave < def.extraMobs.waves; wave++) {
            final int currentWave = wave;
            final long delayMs = def.extraMobs.timeLimitMs * (wave + 1);

            // Use Java's ScheduledExecutorService for delay
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                world.execute(() -> spawnExtraMobWave(world, def, spawnPos, bossUuid, currentWave));
            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);

            LOGGER.info("Scheduled wave " + (currentWave + 1) + " to spawn in " + delayMs + "ms");
        }
    }

    private void spawnExtraMobWave(World world, BossDefinition def, Vector3d spawnPos, UUID bossUuid, int waveNumber) {
        LOGGER.info("Wave " + (waveNumber + 1) + " timer triggered");

        // Check if boss is still tracked
        boolean bossTracked = tracking.isTracked(bossUuid);

        if (!bossTracked) {
            LOGGER.info("Boss not in tracking system - CANCELLING wave " + (waveNumber + 1));
            return;
        }

        // Also check if boss entity still exists and is alive
        try {
            var entityRef = world.getEntityRef(bossUuid);
            if (entityRef == null || !entityRef.isValid()) {
                LOGGER.info("Boss entity no longer exists - CANCELLING wave " + (waveNumber + 1));
                tracking.untrack(bossUuid);
                return;
            }

            var store = world.getEntityStore().getStore();
            var assetMap = EntityStatType.getAssetMap();
            int healthIndex = assetMap.getIndex("Health");

            Object statMapObj = store.getComponent(entityRef, EntityStatMap.getComponentType());
            if (statMapObj instanceof EntityStatMap statMap) {
                var healthValue = statMap.get(healthIndex);
                if (healthValue != null && healthValue.get() <= 0) {
                    LOGGER.info("Boss is dead (HP <= 0) - CANCELLING wave " + (waveNumber + 1));
                    tracking.untrack(bossUuid);
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Error checking boss status: " + e.getMessage());
            tracking.untrack(bossUuid);
            return;
        }

        LOGGER.info("Boss is alive - SPAWNING wave " + (waveNumber + 1));

        int mobCount = (def.extraMobs.mobsPerWave > 0) ? def.extraMobs.mobsPerWave : 3;

        for (int i = 0; i < mobCount; i++) {
            Vector3d mobPos = spawnPos.add(
                    (Math.random() - 0.5) * 5,
                    0,
                    (Math.random() - 0.5) * 5
            );

            var result = NPCPlugin.get().spawnNPC(
                    world.getEntityStore().getStore(),
                    def.extraMobs.npcId,
                    null,
                    mobPos,
                    new Vector3f(0, 0, 0)
            );

            if (result != null) {
                LOGGER.info("Spawned extra mob " + (i+1) + "/" + mobCount + " (wave " + (waveNumber + 1) + ")");
            }
        }
    }

    private void attachBossComponent(World world, Ref<EntityStore> entityRef, BossModifiers mods) {
        BossFightComponent fightData = new BossFightComponent();
        fightData.hpMultiplier = mods.hpMultiplier();
        world.getEntityStore().getStore().addComponent(entityRef, BossFightComponent.getComponentType(), fightData);
    }
}