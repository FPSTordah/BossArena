package com.bossarena.spawn;

import com.bossarena.BossArenaConfig;
import com.bossarena.BossArenaPlugin;
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
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.protocol.InteractionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BossSpawnService {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    public static final String HEALTH_MODIFIER_KEY = "BossArena.HealthMultiplier";
    public static final UUID DEFERRED_SPAWN_UUID = new UUID(0L, 2L);
    private static final double GOLDEN_ANGLE_RADIANS = 2.399963229728653d;
    private static final double BOSS_SPAWN_SPACING_BLOCKS = 2.75d;
    private static final long DEFAULT_REPEAT_INTERVAL_MS = 1_000L;
    private static final long HP_TRIGGER_POLL_INTERVAL_MS = 250L;
    private static final double HP_TRIGGER_EPSILON = 0.01d;
    private static final ScheduledExecutorService EXTRA_WAVE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BossArena-ExtraWaves");
                t.setDaemon(true);
                return t;
            });

    private final BossTrackingSystem tracking;
    private final BossArenaConfig config;

    public BossSpawnService(BossTrackingSystem tracking, BossArenaConfig config) {
        this.tracking = tracking;
        this.config = config;
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

        int countdownMinutes = config != null ? config.getBossTierCountdownMinutes(def.tier) : 30;
        long countdownDurationMs = TimeUnit.MINUTES.toMillis(Math.max(1, countdownMinutes));
        List<BossDefinition.ExtraMobs.ScheduledWave> resolvedWavesBuffer = List.of();
        List<UUID> pendingPreBossAdds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger nextWaveNumber = new AtomicInteger(1);

        if (def.extraMobs != null) {
            def.extraMobs.sanitize();
            resolvedWavesBuffer = def.extraMobs.getResolvedScheduledWaves();
        }
        final List<BossDefinition.ExtraMobs.ScheduledWave> resolvedWaves = resolvedWavesBuffer;

        long preBossDelayMs = scheduleBeforeBossWaveTimeline(
                world,
                def,
                spawnPos,
                resolvedWaves,
                nextWaveNumber,
                pendingPreBossAdds
        );
        if (preBossDelayMs > 0L) {
            LOGGER.info("Delaying boss spawn for '" + def.bossName + "' by " + preBossDelayMs + "ms due to before_boss schedule.");
            EXTRA_WAVE_SCHEDULER.schedule(
                    () -> world.execute(() -> spawnBossNow(
                            world,
                            def,
                            spawnPos,
                            arenaId,
                            mods,
                            countdownDurationMs,
                            resolvedWaves,
                            pendingPreBossAdds,
                            nextWaveNumber
                    )),
                    preBossDelayMs,
                    TimeUnit.MILLISECONDS
            );
            return DEFERRED_SPAWN_UUID;
        }

        return spawnBossNow(
                world,
                def,
                spawnPos,
                arenaId,
                mods,
                countdownDurationMs,
                resolvedWaves,
                pendingPreBossAdds,
                nextWaveNumber
        );
    }

    private UUID spawnBossNow(World world,
                              BossDefinition def,
                              Vector3d spawnPos,
                              String arenaId,
                              BossModifiers mods,
                              long countdownDurationMs,
                              List<BossDefinition.ExtraMobs.ScheduledWave> resolvedWaves,
                              List<UUID> pendingPreBossAdds,
                              AtomicInteger nextWaveNumber) {
        UUID primaryBossUuid = null;
        UUID bossEventId = null;

        // Spawn the boss(es)
        for (int i = 0; i < def.amount; i++) {
            Vector3d spreadPos = computeBossSpawnPosition(spawnPos, i);

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

                    if (bossEventId == null) {
                        bossEventId = tracking.createEvent(world, spawnPos, def.bossName, def.tier, countdownDurationMs);
                    }

                    tracking.track(uuid, def.bossName, mods, arenaId, world, spreadPos, def.tier, bossEventId, spawnPos);
                    applyModifiers(store, npcRef, mods);
                    disableDefaultEntityLoot(store, npcRef, def.bossName + "#" + (i + 1));

                    LOGGER.info("Successfully spawned boss: " + def.bossName + " (" + uuid + ") at " + spreadPos);
                }
            } else {
                LOGGER.warning("NPCPlugin.spawnNPC returned null for NPC ID: " + def.npcId);
            }
        }

        if (primaryBossUuid != null && def.extraMobs != null && def.extraMobs.hasConfiguredAdds()) {
            attachPreBossAddsToEvent(world, primaryBossUuid, pendingPreBossAdds);
            scheduleConfiguredWaves(world, def, spawnPos, primaryBossUuid, resolvedWaves, nextWaveNumber);
        }

        if (primaryBossUuid != null) {
            BossTrackingSystem.BossData bossData = tracking.getBossData(primaryBossUuid);
            if (bossData != null) {
                BossWaveNotificationService.notifyBossAliveStatus(
                        world,
                        bossData.spawnLocation,
                        bossData.bossName,
                        tracking.getAliveBossCount(primaryBossUuid),
                        tracking.getActiveAddCountForEvent(primaryBossUuid),
                        null,
                        tracking.getRemainingCountdownMillis(primaryBossUuid)
                );
            }
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

    private long scheduleBeforeBossWaveTimeline(World world,
                                                BossDefinition def,
                                                Vector3d spawnPos,
                                                List<BossDefinition.ExtraMobs.ScheduledWave> schedules,
                                                AtomicInteger nextWaveNumber,
                                                List<UUID> pendingPreBossAdds) {
        List<BossDefinition.ExtraMobs.ScheduledWave> beforeBoss = filterSchedulesByTrigger(
                schedules,
                BossDefinition.ExtraMobs.TRIGGER_BEFORE_BOSS
        );
        if (beforeBoss.isEmpty()) {
            return 0L;
        }

        List<PreBossExecution> executions = new ArrayList<>();
        int scheduleIndex = 0;
        long bossSpawnIntervalMs = DEFAULT_REPEAT_INTERVAL_MS;
        for (BossDefinition.ExtraMobs.ScheduledWave scheduledWave : beforeBoss) {
            scheduleIndex++;
            int repeatCount = Math.max(1, scheduledWave.repeatCount);
            if (scheduledWave.repeatCount < 0) {
                LOGGER.info("before_boss trigger requested infinite repeats; limiting to 1 execution for '" + def.bossName + "'.");
                repeatCount = 1;
            }
            long firstDelayMs = toMillisOrDefault(scheduledWave.triggerValue, 0L);
            long repeatStepMs = toMillisOrDefault(scheduledWave.repeatEverySeconds, DEFAULT_REPEAT_INTERVAL_MS);
            long scheduleBossIntervalMs = repeatStepMs;
            if (scheduledWave.repeatEverySeconds <= 0.0d && scheduledWave.triggerValue > 0.0d) {
                scheduleBossIntervalMs = toMillisOrDefault(scheduledWave.triggerValue, DEFAULT_REPEAT_INTERVAL_MS);
            }
            bossSpawnIntervalMs = Math.max(bossSpawnIntervalMs, scheduleBossIntervalMs);

            for (int execution = 1; execution <= repeatCount; execution++) {
                long triggerDelayMs = firstDelayMs + ((long) (execution - 1) * repeatStepMs);
                executions.add(new PreBossExecution(triggerDelayMs, execution, scheduleIndex, scheduledWave));
            }
        }

        if (executions.isEmpty()) {
            return 0L;
        }

        long bossDelayMs = 0L;
        for (PreBossExecution execution : executions) {
            bossDelayMs = Math.max(bossDelayMs, execution.triggerDelayMs);
        }
        bossDelayMs += Math.max(1L, bossSpawnIntervalMs);

        executions.sort(Comparator
                .comparingLong((PreBossExecution execution) -> execution.triggerDelayMs)
                .thenComparingInt(execution -> execution.scheduleIndex)
                .thenComparingInt(execution -> execution.executionNumber));

        LOGGER.info("Scheduling " + executions.size() + " pre-boss wave execution(s) over " + bossDelayMs
                + "ms for boss '" + def.bossName + "' (boss interval=" + bossSpawnIntervalMs + "ms).");

        for (PreBossExecution execution : executions) {
            long delayMs = Math.max(0L, execution.triggerDelayMs);
            Runnable runExecution = () -> {
                int waveNumber = nextWaveNumber.getAndIncrement();
                List<UUID> spawned = spawnConfiguredWave(
                        world,
                        def,
                        spawnPos,
                        waveNumber,
                        null,
                        execution.wave.adds,
                        "before_boss@+" + formatSeconds(execution.triggerDelayMs / 1000.0d) + "s#" + execution.executionNumber
                );
                if (!spawned.isEmpty()) {
                    synchronized (pendingPreBossAdds) {
                        pendingPreBossAdds.addAll(spawned);
                    }
                }
            };

            if (delayMs <= 0L) {
                runExecution.run();
                continue;
            }

            LOGGER.info("Scheduled before_boss wave execution " + execution.executionNumber
                    + " in " + delayMs + "ms (schedule row " + execution.scheduleIndex + ").");
            EXTRA_WAVE_SCHEDULER.schedule(() -> world.execute(runExecution), delayMs, TimeUnit.MILLISECONDS);
        }

        return bossDelayMs;
    }

    private void scheduleConfiguredWaves(World world,
                                         BossDefinition def,
                                         Vector3d spawnPos,
                                         UUID bossUuid,
                                         List<BossDefinition.ExtraMobs.ScheduledWave> resolvedWaves,
                                         AtomicInteger nextWaveNumber) {
        if (resolvedWaves == null || resolvedWaves.isEmpty()) {
            LOGGER.info("No resolved wave schedule for boss '" + def.bossName + "'.");
            return;
        }

        List<BossDefinition.ExtraMobs.ScheduledWave> onSpawn = filterSchedulesByTrigger(
                resolvedWaves,
                BossDefinition.ExtraMobs.TRIGGER_ON_SPAWN
        );
        List<BossDefinition.ExtraMobs.ScheduledWave> afterSpawn = filterSchedulesByTrigger(
                resolvedWaves,
                BossDefinition.ExtraMobs.TRIGGER_AFTER_SPAWN_SECONDS
        );
        List<BossDefinition.ExtraMobs.ScheduledWave> sinceLastWave = filterSchedulesByTrigger(
                resolvedWaves,
                BossDefinition.ExtraMobs.TRIGGER_SINCE_LAST_WAVE
        );
        List<BossDefinition.ExtraMobs.ScheduledWave> hpThreshold = filterSchedulesByTrigger(
                resolvedWaves,
                BossDefinition.ExtraMobs.TRIGGER_BOSS_HP_PERCENT
        );
        afterSpawn.sort(Comparator.comparingDouble(wave -> wave.triggerValue));
        hpThreshold.sort(Comparator.comparingDouble(BossSpawnService::reverseHealthThresholdOrder));

        LOGGER.info("Resolved wave schedule for '" + def.bossName + "': onSpawn=" + onSpawn.size()
                + ", afterSpawn=" + afterSpawn.size()
                + ", sinceLastWave=" + sinceLastWave.size()
                + ", hpThreshold=" + hpThreshold.size());

        boolean hasSinceLastAnchor = false;
        long lastScheduledWaveDelayMs = 0L;

        for (BossDefinition.ExtraMobs.ScheduledWave scheduledWave : onSpawn) {
            int waveNumber = nextWaveNumber.getAndIncrement();
            spawnConfiguredWave(
                    world,
                    def,
                    spawnPos,
                    waveNumber,
                    bossUuid,
                    scheduledWave.adds,
                    "on_spawn"
            );
            hasSinceLastAnchor = true;
            lastScheduledWaveDelayMs = Math.max(lastScheduledWaveDelayMs, 0L);

            if (scheduledWave.repeatCount < 0 || scheduledWave.repeatCount > 1) {
                long repeatDelayMs = toMillisOrDefault(scheduledWave.repeatEverySeconds, DEFAULT_REPEAT_INTERVAL_MS);
                scheduleTimedWaveExecution(
                        world,
                        def,
                        spawnPos,
                        bossUuid,
                        scheduledWave.adds,
                        nextWaveNumber,
                        "on_spawn_repeat",
                        2,
                        scheduledWave.repeatCount,
                        repeatDelayMs,
                        repeatDelayMs
                );

                if (scheduledWave.repeatCount > 1) {
                    long lastRepeatDelayMs = repeatDelayMs * (scheduledWave.repeatCount - 1L);
                    lastScheduledWaveDelayMs = Math.max(lastScheduledWaveDelayMs, lastRepeatDelayMs);
                }
            }
        }

        for (BossDefinition.ExtraMobs.ScheduledWave scheduledWave : afterSpawn) {
            long firstDelayMs = toMillisOrDefault(scheduledWave.triggerValue, 0L);
            long repeatDelayMs = toMillisOrDefault(scheduledWave.repeatEverySeconds, DEFAULT_REPEAT_INTERVAL_MS);
            hasSinceLastAnchor = true;
            lastScheduledWaveDelayMs = Math.max(lastScheduledWaveDelayMs, firstDelayMs);
            scheduleTimedWaveExecution(
                    world,
                    def,
                    spawnPos,
                    bossUuid,
                    scheduledWave.adds,
                    nextWaveNumber,
                    "after_spawn@" + formatSeconds(scheduledWave.triggerValue) + "s",
                    1,
                    scheduledWave.repeatCount,
                    firstDelayMs,
                    repeatDelayMs
            );

            if (scheduledWave.repeatCount > 1) {
                long lastRepeatDelayMs = firstDelayMs + (repeatDelayMs * (scheduledWave.repeatCount - 1L));
                lastScheduledWaveDelayMs = Math.max(lastScheduledWaveDelayMs, lastRepeatDelayMs);
            }
        }

        if (!sinceLastWave.isEmpty() && !hasSinceLastAnchor) {
            LOGGER.info("Skipping since_last_wave schedules for '" + def.bossName
                    + "' because no prior wave trigger is configured. Boss spawn is not treated as a wave.");
        } else {
            long sinceLastCumulativeDelayMs = lastScheduledWaveDelayMs;
            for (BossDefinition.ExtraMobs.ScheduledWave scheduledWave : sinceLastWave) {
                long stepDelayMs = toMillisOrDefault(scheduledWave.triggerValue, 0L);
                sinceLastCumulativeDelayMs += stepDelayMs;
                long repeatDelayMs = toMillisOrDefault(scheduledWave.repeatEverySeconds, DEFAULT_REPEAT_INTERVAL_MS);
                scheduleTimedWaveExecution(
                        world,
                        def,
                        spawnPos,
                        bossUuid,
                        scheduledWave.adds,
                        nextWaveNumber,
                        "since_last_wave@" + formatSeconds(scheduledWave.triggerValue) + "s",
                        1,
                        scheduledWave.repeatCount,
                        sinceLastCumulativeDelayMs,
                        repeatDelayMs
                );

                if (scheduledWave.repeatCount < 0) {
                    LOGGER.info("since_last_wave schedule for '" + def.bossName
                            + "' has infinite repeats; remaining since_last_wave rows will be ignored.");
                    break;
                }
                if (scheduledWave.repeatCount > 1) {
                    sinceLastCumulativeDelayMs += repeatDelayMs * (scheduledWave.repeatCount - 1L);
                }
            }
        }

        for (BossDefinition.ExtraMobs.ScheduledWave scheduledWave : hpThreshold) {
            scheduleHpThresholdWave(
                    world,
                    def,
                    spawnPos,
                    bossUuid,
                    scheduledWave,
                    nextWaveNumber
            );
        }
    }

    private void attachPreBossAddsToEvent(World world, UUID bossUuid, List<UUID> preBossAdds) {
        if (bossUuid == null || preBossAdds == null || preBossAdds.isEmpty()) {
            return;
        }

        int attached = 0;
        for (UUID addUuid : preBossAdds) {
            if (!isEntityAlive(world, addUuid)) {
                continue;
            }
            tracking.trackAdd(bossUuid, addUuid);
            attached++;
        }
        if (attached > 0) {
            LOGGER.info("Attached " + attached + " pre-boss add(s) to boss event " + bossUuid + ".");
        }
    }

    private void scheduleTimedWaveExecution(World world,
                                            BossDefinition def,
                                            Vector3d spawnPos,
                                            UUID bossUuid,
                                            List<BossDefinition.ExtraMobs.WaveAdd> adds,
                                            AtomicInteger nextWaveNumber,
                                            String triggerLabel,
                                            int executionNumber,
                                            int repeatCount,
                                            long delayMs,
                                            long repeatDelayMs) {
        if (repeatCount > 0 && executionNumber > repeatCount) {
            return;
        }

        long safeDelayMs = Math.max(0L, delayMs);
        long safeRepeatDelayMs = Math.max(1L, repeatDelayMs);
        LOGGER.info("Scheduled wave trigger '" + triggerLabel + "' execution " + executionNumber
                + " in " + safeDelayMs + "ms for boss " + bossUuid + ".");

        EXTRA_WAVE_SCHEDULER.schedule(() -> world.execute(() -> {
            if (!isBossAlive(world, bossUuid, triggerLabel + "#" + executionNumber)) {
                return;
            }

            int waveNumber = nextWaveNumber.getAndIncrement();
            spawnConfiguredWave(world, def, spawnPos, waveNumber, bossUuid, adds, triggerLabel + "#" + executionNumber);

            if (repeatCount < 0 || executionNumber < repeatCount) {
                scheduleTimedWaveExecution(
                        world,
                        def,
                        spawnPos,
                        bossUuid,
                        adds,
                        nextWaveNumber,
                        triggerLabel,
                        executionNumber + 1,
                        repeatCount,
                        safeRepeatDelayMs,
                        safeRepeatDelayMs
                );
            }
        }), safeDelayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleHpThresholdWave(World world,
                                         BossDefinition def,
                                         Vector3d spawnPos,
                                         UUID bossUuid,
                                         BossDefinition.ExtraMobs.ScheduledWave scheduledWave,
                                         AtomicInteger nextWaveNumber) {
        double threshold = Math.max(0d, Math.min(100d, scheduledWave.triggerValue));
        long repeatDelayMs = toMillisOrDefault(scheduledWave.repeatEverySeconds, DEFAULT_REPEAT_INTERVAL_MS);
        int remainingRepeatsAfterThreshold = scheduledWave.repeatCount < 0
                ? -1
                : Math.max(0, scheduledWave.repeatCount - 1);
        AtomicBoolean thresholdTriggered = new AtomicBoolean(false);
        LOGGER.info("Scheduled HP-trigger wave at <= " + formatSeconds(threshold) + "% for boss " + bossUuid + ".");

        scheduleHpThresholdPoll(
                world,
                def,
                spawnPos,
                bossUuid,
                threshold,
                scheduledWave.adds,
                nextWaveNumber,
                remainingRepeatsAfterThreshold,
                repeatDelayMs,
                thresholdTriggered
        );
    }

    private void scheduleHpThresholdPoll(World world,
                                         BossDefinition def,
                                         Vector3d spawnPos,
                                         UUID bossUuid,
                                         double thresholdPercent,
                                         List<BossDefinition.ExtraMobs.WaveAdd> adds,
                                         AtomicInteger nextWaveNumber,
                                         int remainingRepeatsAfterThreshold,
                                         long repeatDelayMs,
                                         AtomicBoolean thresholdTriggered) {
        EXTRA_WAVE_SCHEDULER.schedule(() -> world.execute(() -> {
            if (!isBossAlive(world, bossUuid, "boss_hp_percent<=" + formatSeconds(thresholdPercent))) {
                return;
            }
            if (thresholdTriggered.get()) {
                return;
            }

            double currentHpPercent = getBossHealthPercent(world, bossUuid);
            if (currentHpPercent < 0d) {
                scheduleHpThresholdPoll(
                        world,
                        def,
                        spawnPos,
                        bossUuid,
                        thresholdPercent,
                        adds,
                        nextWaveNumber,
                        remainingRepeatsAfterThreshold,
                        repeatDelayMs,
                        thresholdTriggered
                );
                return;
            }

            if (currentHpPercent > thresholdPercent + HP_TRIGGER_EPSILON) {
                scheduleHpThresholdPoll(
                        world,
                        def,
                        spawnPos,
                        bossUuid,
                        thresholdPercent,
                        adds,
                        nextWaveNumber,
                        remainingRepeatsAfterThreshold,
                        repeatDelayMs,
                        thresholdTriggered
                );
                return;
            }
            if (!thresholdTriggered.compareAndSet(false, true)) {
                return;
            }

            int waveNumber = nextWaveNumber.getAndIncrement();
            LOGGER.info("HP trigger reached for boss " + bossUuid + ": current=" + formatSeconds(currentHpPercent)
                    + "% threshold=" + formatSeconds(thresholdPercent) + "%.");
            spawnConfiguredWave(
                    world,
                    def,
                    spawnPos,
                    waveNumber,
                    bossUuid,
                    adds,
                    "boss_hp_percent<=" + formatSeconds(thresholdPercent)
            );

            if (remainingRepeatsAfterThreshold != 0) {
                LOGGER.info("Scheduling HP follow-up waves for boss " + bossUuid + ": remaining="
                        + (remainingRepeatsAfterThreshold < 0 ? "infinite" : remainingRepeatsAfterThreshold)
                        + ", every=" + repeatDelayMs + "ms.");
                scheduleHpFollowupWave(
                        world,
                        def,
                        spawnPos,
                        bossUuid,
                        thresholdPercent,
                        adds,
                        nextWaveNumber,
                        remainingRepeatsAfterThreshold,
                        repeatDelayMs
                );
            }
        }), HP_TRIGGER_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void scheduleHpFollowupWave(World world,
                                        BossDefinition def,
                                        Vector3d spawnPos,
                                        UUID bossUuid,
                                        double thresholdPercent,
                                        List<BossDefinition.ExtraMobs.WaveAdd> adds,
                                        AtomicInteger nextWaveNumber,
                                        int remainingRepeats,
                                        long repeatDelayMs) {
        long safeDelayMs = Math.max(1L, repeatDelayMs);
        EXTRA_WAVE_SCHEDULER.schedule(() -> world.execute(() -> {
            if (!isBossAlive(world, bossUuid, "boss_hp_percent_repeat<=" + formatSeconds(thresholdPercent))) {
                return;
            }

            int waveNumber = nextWaveNumber.getAndIncrement();
            spawnConfiguredWave(
                    world,
                    def,
                    spawnPos,
                    waveNumber,
                    bossUuid,
                    adds,
                    "boss_hp_percent_repeat<=" + formatSeconds(thresholdPercent)
            );

            if (remainingRepeats < 0) {
                scheduleHpFollowupWave(
                        world,
                        def,
                        spawnPos,
                        bossUuid,
                        thresholdPercent,
                        adds,
                        nextWaveNumber,
                        -1,
                        safeDelayMs
                );
            } else if (remainingRepeats > 1) {
                scheduleHpFollowupWave(
                        world,
                        def,
                        spawnPos,
                        bossUuid,
                        thresholdPercent,
                        adds,
                        nextWaveNumber,
                        remainingRepeats - 1,
                        safeDelayMs
                );
            }
        }), safeDelayMs, TimeUnit.MILLISECONDS);
    }

    private boolean isBossAlive(World world, UUID bossUuid, String triggerLabel) {
        if (bossUuid == null) {
            LOGGER.info("Skipping wave trigger '" + triggerLabel + "' because boss UUID is null.");
            return false;
        }

        if (!tracking.isTracked(bossUuid)) {
            LOGGER.info("Skipping wave trigger '" + triggerLabel + "' because boss is no longer tracked: " + bossUuid);
            return false;
        }

        return isEntityAlive(world, bossUuid);
    }

    private boolean isEntityAlive(World world, UUID entityUuid) {
        if (world == null || entityUuid == null) {
            return false;
        }
        try {
            var entityRef = world.getEntityRef(entityUuid);
            if (entityRef == null || !entityRef.isValid()) {
                return false;
            }

            var store = world.getEntityStore().getStore();
            Object statMapObj = store.getComponent(entityRef, EntityStatMap.getComponentType());
            if (!(statMapObj instanceof EntityStatMap statMap)) {
                return true;
            }

            int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
            if (healthIndex < 0) {
                return true;
            }
            var healthValue = statMap.get(healthIndex);
            return healthValue == null || healthValue.get() > 0f;
        } catch (Exception e) {
            LOGGER.fine("Failed to check entity alive state: " + entityUuid + " -> " + e.getMessage());
            return false;
        }
    }

    private double getBossHealthPercent(World world, UUID bossUuid) {
        if (world == null || bossUuid == null) {
            return -1d;
        }
        try {
            var entityRef = world.getEntityRef(bossUuid);
            if (entityRef == null || !entityRef.isValid()) {
                return -1d;
            }

            var store = world.getEntityStore().getStore();
            Object statMapObj = store.getComponent(entityRef, EntityStatMap.getComponentType());
            if (!(statMapObj instanceof EntityStatMap statMap)) {
                return -1d;
            }

            int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
            if (healthIndex < 0) {
                return -1d;
            }
            var healthValue = statMap.get(healthIndex);
            if (healthValue == null || healthValue.getMax() <= 0f) {
                return -1d;
            }

            return (healthValue.get() / healthValue.getMax()) * 100.0d;
        } catch (Exception e) {
            LOGGER.fine("Failed to evaluate boss HP percent for " + bossUuid + ": " + e.getMessage());
            return -1d;
        }
    }

    private List<UUID> spawnConfiguredWave(World world,
                                           BossDefinition def,
                                           Vector3d spawnPos,
                                           int waveNumber,
                                           UUID bossUuid,
                                           List<BossDefinition.ExtraMobs.WaveAdd> adds,
                                           String triggerLabel) {
        if (adds == null || adds.isEmpty()) {
            return List.of();
        }

        LOGGER.info("Executing wave " + waveNumber + " for '" + def.bossName + "' via trigger '" + triggerLabel + "'.");

        int trackedAddsSpawned = 0;
        List<UUID> spawnedAddUuids = new ArrayList<>();
        for (BossDefinition.ExtraMobs.WaveAdd add : adds) {
            if (add == null || add.npcId == null || add.npcId.isBlank()) {
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
                if (result == null) {
                    LOGGER.warning("Failed to spawn add '" + add.npcId + "' for wave " + waveNumber + ".");
                    continue;
                }

                Ref<EntityStore> addRef = result.first();
                BossModifiers addMods = new BossModifiers(
                        Math.max(0.01f, add.hp),
                        Math.max(0.01f, add.damage),
                        1.0f,
                        Math.max(0.01f, add.size)
                );
                applyModifiers(world.getEntityStore().getStore(), addRef, addMods);
                disableDefaultEntityLoot(world.getEntityStore().getStore(), addRef, add.npcId);

                Object addUuidObj = world.getEntityStore().getStore().getComponent(addRef, UUIDComponent.getComponentType());
                if (addUuidObj instanceof UUIDComponent addUuidComp) {
                    UUID addUuid = addUuidComp.getUuid();
                    spawnedAddUuids.add(addUuid);
                    if (bossUuid != null) {
                        tracking.trackAdd(bossUuid, addUuid);
                        trackedAddsSpawned++;
                    }
                }

                LOGGER.info("Spawned add '" + add.npcId + "' " + (i + 1) + "/" + mobCount
                        + " (wave " + waveNumber
                        + ", hp=" + addMods.hpMultiplier()
                        + ", dmg=" + addMods.damageMultiplier()
                        + ", size=" + addMods.scaleMultiplier() + ")");
            }
        }

        if (bossUuid == null) {
            if (!spawnedAddUuids.isEmpty()) {
                LOGGER.info("Wave " + waveNumber + " spawned " + spawnedAddUuids.size()
                        + " pre-boss add(s). They will be linked after the boss spawns.");
            }
            return spawnedAddUuids;
        }

        if (trackedAddsSpawned > 0) {
            BossTrackingSystem.BossData bossData = tracking.getBossData(bossUuid);
            if (bossData != null) {
                BossWaveNotificationService.notifyWaveSpawn(
                        world,
                        bossData.spawnLocation,
                        bossData.bossName,
                        waveNumber,
                        trackedAddsSpawned,
                        tracking.getActiveAddCountForEvent(bossUuid),
                        tracking.getRemainingCountdownMillis(bossUuid)
                );
            }
        }

        return spawnedAddUuids;
    }

    private static List<BossDefinition.ExtraMobs.ScheduledWave> filterSchedulesByTrigger(
            List<BossDefinition.ExtraMobs.ScheduledWave> schedules,
            String trigger
    ) {
        if (schedules == null || schedules.isEmpty()) {
            return List.of();
        }
        List<BossDefinition.ExtraMobs.ScheduledWave> out = new ArrayList<>();
        for (BossDefinition.ExtraMobs.ScheduledWave wave : schedules) {
            if (wave != null && trigger.equals(wave.trigger)) {
                out.add(wave);
            }
        }
        return out;
    }

    private static double reverseHealthThresholdOrder(BossDefinition.ExtraMobs.ScheduledWave wave) {
        if (wave == null) {
            return Double.MAX_VALUE;
        }
        return -wave.triggerValue;
    }

    private static long toMillisOrDefault(double seconds, long defaultMs) {
        if (!Double.isFinite(seconds)) {
            return Math.max(0L, defaultMs);
        }
        if (seconds <= 0.0d) {
            return Math.max(0L, defaultMs);
        }
        return Math.max(0L, Math.round(seconds * 1000.0d));
    }

    private static String formatSeconds(double value) {
        return String.format("%.2f", value);
    }

    private static final class PreBossExecution {
        private final long triggerDelayMs;
        private final int executionNumber;
        private final int scheduleIndex;
        private final BossDefinition.ExtraMobs.ScheduledWave wave;

        private PreBossExecution(long triggerDelayMs,
                                 int executionNumber,
                                 int scheduleIndex,
                                 BossDefinition.ExtraMobs.ScheduledWave wave) {
            this.triggerDelayMs = Math.max(0L, triggerDelayMs);
            this.executionNumber = executionNumber;
            this.scheduleIndex = Math.max(0, scheduleIndex);
            this.wave = wave;
        }
    }

    private void disableDefaultEntityLoot(Store<EntityStore> store, Ref<EntityStore> entityRef, String label) {
        try {
            Object interactionsObj = store.getComponent(entityRef, Interactions.getComponentType());
            if (interactionsObj instanceof Interactions interactions) {
                // Route death interaction to a valid no-op interaction to suppress prefab death droplists.
                interactions.setInteractionId(InteractionType.Death, BossArenaPlugin.NO_DEATH_DROPS_INTERACTION_ID);
                LOGGER.info("Disabled default entity death drops for '" + label + "'.");
            } else {
                LOGGER.warning("Could not disable drops for '" + label + "' (missing Interactions component).");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to disable default drops for '" + label + "'.", e);
        }
    }

    private Vector3d computeBossSpawnPosition(Vector3d center, int index) {
        if (center == null || index <= 0) {
            return center;
        }

        // Golden-angle spiral keeps dense waves spread around the arena center without clumping.
        double radius = BOSS_SPAWN_SPACING_BLOCKS * Math.sqrt(index);
        double angle = index * GOLDEN_ANGLE_RADIANS;
        double x = center.x + (Math.cos(angle) * radius);
        double z = center.z + (Math.sin(angle) * radius);
        return new Vector3d(x, center.y, z);
    }

}
