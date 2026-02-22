package com.bossarena.spawn;

import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.bossarena.util.BossScaler;
import com.bossarena.boss.BossModifiers;
import com.bossarena.boss.PlayerFinder;
import com.bossarena.system.BossTrackingSystem;
import com.bossarena.system.BossWaveNotificationService;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BossSpawnService {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final String HEALTH_MODIFIER_KEY = "BossArena.HealthMultiplier";
    private static final ScheduledExecutorService EXTRA_WAVE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BossArena-ExtraWaves");
                t.setDaemon(true);
                return t;
            });

    private final BossTrackingSystem tracking;

    public BossSpawnService(BossTrackingSystem tracking) {
        this.tracking = tracking;
    }

    public UUID spawnBossFromJson(@SuppressWarnings("unused") CommandSender sender,
                                  String bossId,
                                  World world,
                                  Vector3d spawnPos,
                                  String arenaId) {  // ADD THIS PARAMETER
        BossDefinition def = BossRegistry.get(bossId);
        if (def == null) {
            LOGGER.warning("Boss definition not found: " + bossId);
            return null;
        }

        LOGGER.info("Attempting to spawn boss '" + def.bossName + "' at position: " + spawnPos);

        // Calculate player-scaled modifiers
        int nearbyCount = PlayerFinder.countPlayersInRadius(world, spawnPos, 40);
        int worldCount = world.getPlayerCount();
        int playerCount = Math.max(nearbyCount, worldCount);
        if (playerCount != nearbyCount) {
            LOGGER.info("Using world player count for scaling: " + worldCount);
        }
        float baseHp = def.modifiers != null ? def.modifiers.hp : 1.0f;
        float baseDamage = def.modifiers != null ? def.modifiers.damage : 1.0f;
        float baseSize = def.modifiers != null ? def.modifiers.size : 1.0f;
        float perHp = def.perPlayerIncrease != null ? def.perPlayerIncrease.hp : 0.0f;
        float perDamage = def.perPlayerIncrease != null ? def.perPlayerIncrease.damage : 0.0f;
        float perSize = def.perPlayerIncrease != null ? def.perPlayerIncrease.size : 0.0f;
        LOGGER.info("Scaling with players=" + playerCount +
                " (nearby=" + nearbyCount + ", world=" + worldCount + ")" +
                " base(hp=" + baseHp + ", dmg=" + baseDamage + ", size=" + baseSize + ")" +
                " perPlayer(hp=" + perHp + ", dmg=" + perDamage + ", size=" + perSize + ")");
        BossModifiers mods = BossScaler.calculateModifiers(def, playerCount);

        LOGGER.info("Boss modifiers calculated - HP: " + mods.hpMultiplier() + ", Damage: " + mods.damageMultiplier() + ", Size: " + mods.scaleMultiplier());

        UUID primaryBossUuid = null;

        // Spawn the boss(es)
        for (int i = 0; i < def.amount; i++) {
            Vector3d spreadPos = new Vector3d(
                    spawnPos.x + (i * 1.2),
                    spawnPos.y,
                    spawnPos.z + (i * 1.2)
            );

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

                    tracking.track(uuid, def.bossName, mods, arenaId, world, spreadPos);
                    applyModifiers(store, npcRef, mods);

                    LOGGER.info("Successfully spawned boss: " + def.bossName + " (" + uuid + ") at " + spreadPos);
                }
            } else {
                LOGGER.warning("NPCPlugin.spawnNPC returned null for NPC ID: " + def.npcId);
            }
        }

        // Schedule extra mobs if configured
        if (primaryBossUuid != null) {
            BossTrackingSystem.BossData bossData = tracking.getBossData(primaryBossUuid);
            if (bossData != null) {
                BossWaveNotificationService.notifyBossAliveStatus(
                        world,
                        bossData.spawnLocation,
                        bossData.bossName,
                        1,
                        tracking.getActiveAddCount(primaryBossUuid),
                        null
                );
            }
        }

        if (primaryBossUuid != null && def.extraMobs != null && def.extraMobs.hasConfiguredAdds()) {
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
                    LOGGER.log(Level.WARNING, "Failed to apply scale modifier", e);
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to apply modifiers", e);
        }
    }

    private void scheduleExtraMobWaves(World world, BossDefinition def, Vector3d spawnPos, UUID bossUuid) {
        BossDefinition.ExtraMobs extra = def.extraMobs;
        if (extra == null) {
            return;
        }
        extra.sanitize();

        if (extra.waves == 0) {
            LOGGER.info("Extra mob waves disabled (waves=0) for boss '" + def.bossName + "'.");
            return;
        }
        if (!extra.hasConfiguredAdds()) {
            LOGGER.info("No configured add entries for boss '" + def.bossName + "'.");
            return;
        }

        long intervalMs = Math.max(1L, extra.timeLimitMs);
        int maxWaves = extra.waves; // -1 = infinite

        if (maxWaves < 0) {
            LOGGER.info("Scheduling infinite extra mob waves every " + intervalMs + "ms for boss '" + def.bossName + "'.");
        } else {
            LOGGER.info("Scheduling " + maxWaves + " extra mob waves every " + intervalMs + "ms for boss '" + def.bossName + "'.");
        }

        scheduleWave(world, def, spawnPos, bossUuid, 1, maxWaves, intervalMs);
    }

    private void scheduleWave(World world,
                              BossDefinition def,
                              Vector3d spawnPos,
                              UUID bossUuid,
                              int waveNumber,
                              int maxWaves,
                              long intervalMs) {
        if (maxWaves > 0 && waveNumber > maxWaves) {
            return;
        }

        EXTRA_WAVE_SCHEDULER.schedule(() -> world.execute(() -> {
            if (!isBossAlive(world, bossUuid, waveNumber)) {
                return;
            }

            spawnExtraMobWave(world, def, spawnPos, waveNumber, bossUuid);

            if (maxWaves < 0 || waveNumber < maxWaves) {
                scheduleWave(world, def, spawnPos, bossUuid, waveNumber + 1, maxWaves, intervalMs);
            }
        }), intervalMs, TimeUnit.MILLISECONDS);

        LOGGER.info("Scheduled wave " + waveNumber + " to spawn in " + intervalMs + "ms");
    }

    private boolean isBossAlive(World world, UUID bossUuid, int waveNumber) {
        LOGGER.info("Wave " + waveNumber + " timer triggered");

        // Check if boss is still tracked
        boolean bossTracked = tracking.isTracked(bossUuid);

        if (!bossTracked) {
            LOGGER.info("Boss not in tracking system - CANCELLING wave " + waveNumber);
            return false;
        }

        // Also check if boss entity still exists and is alive
        try {
            var entityRef = world.getEntityRef(bossUuid);
            if (entityRef == null || !entityRef.isValid()) {
                LOGGER.info("Boss entity no longer exists - CANCELLING wave " + waveNumber);
                tracking.untrack(bossUuid);
                return false;
            }

            var store = world.getEntityStore().getStore();
            var assetMap = EntityStatType.getAssetMap();
            int healthIndex = assetMap.getIndex("Health");

            Object statMapObj = store.getComponent(entityRef, EntityStatMap.getComponentType());
            if (statMapObj instanceof EntityStatMap statMap) {
                var healthValue = statMap.get(healthIndex);
                if (healthValue != null && healthValue.get() <= 0) {
                    LOGGER.info("Boss is dead (HP <= 0) - CANCELLING wave " + waveNumber);
                    tracking.untrack(bossUuid);
                    return false;
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Error checking boss status: " + e.getMessage());
            tracking.untrack(bossUuid);
            return false;
        }

        return true;
    }

    private void spawnExtraMobWave(World world,
                                   BossDefinition def,
                                   Vector3d spawnPos,
                                   int waveNumber,
                                   UUID bossUuid) {
        if (def.extraMobs == null) {
            return;
        }
        var adds = def.extraMobs.getConfiguredAdds();
        if (adds.isEmpty()) {
            return;
        }

        LOGGER.info("Boss is alive - SPAWNING wave " + waveNumber);

        int spawnedThisWave = 0;
        for (BossDefinition.ExtraMobs.WaveAdd add : adds) {
            int everyWave = Math.max(1, add.everyWave);
            if (waveNumber % everyWave != 0) {
                continue;
            }

            int mobCount = Math.max(1, add.mobsPerWave);
            for (int i = 0; i < mobCount; i++) {
                Vector3d mobPos = spawnPos.add(
                        (Math.random() - 0.5) * 5,
                        0,
                        (Math.random() - 0.5) * 5
                );

                var result = NPCPlugin.get().spawnNPC(
                        world.getEntityStore().getStore(),
                        add.npcId,
                        null,
                        mobPos,
                        new Vector3f(0, 0, 0)
                );

                if (result != null) {
                    Ref<EntityStore> addRef = result.first();
                    Object addUuidObj = world.getEntityStore().getStore().getComponent(addRef, UUIDComponent.getComponentType());
                    if (addUuidObj instanceof UUIDComponent addUuidComp) {
                        tracking.trackAdd(bossUuid, addUuidComp.getUuid());
                        spawnedThisWave++;
                    }
                    LOGGER.info("Spawned add '" + add.npcId + "' " + (i + 1) + "/" + mobCount + " (wave " + waveNumber + ", every " + everyWave + ")");
                }
            }
        }

        if (spawnedThisWave > 0) {
            BossTrackingSystem.BossData bossData = tracking.getBossData(bossUuid);
            if (bossData != null) {
                int activeAdds = tracking.getActiveAddCount(bossUuid);
                BossWaveNotificationService.notifyWaveSpawn(
                        world,
                        bossData.spawnLocation,
                        bossData.bossName,
                        waveNumber,
                        spawnedThisWave,
                        activeAdds
                );
            }
        }
    }

}
