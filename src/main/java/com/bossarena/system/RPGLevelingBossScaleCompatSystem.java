package com.bossarena.system;

import com.bossarena.BossArenaPlugin;
import com.bossarena.spawn.BossSpawnService;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.common.semver.SemverRange;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps BossArena's HP multiplier on tracked bosses when RPGLeveling is present.
 * We intentionally avoid maximizing current HP here to prevent accidental mid-fight heals.
 */
public final class RPGLevelingBossScaleCompatSystem extends TickingSystem<EntityStore> {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final String RPG_LEVELING_HP_MODIFIER_KEY = "RPGLeveling.HPModifier";
    private static final float RESYNC_INTERVAL_SECONDS = 0.25f;
    private static final float EPSILON = 0.0001f;

    private final BossTrackingSystem trackingSystem;
    private float elapsedSeconds;
    private boolean lastRpgLevelingLoaded;
    private boolean loadStateKnown;

    public RPGLevelingBossScaleCompatSystem(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
    }

    @Override
    public void tick(float dt, int index, @Nonnull Store<EntityStore> store) {
        if (trackingSystem == null) {
            return;
        }

        elapsedSeconds += Math.max(0f, dt);
        if (elapsedSeconds < RESYNC_INTERVAL_SECONDS) {
            return;
        }
        elapsedSeconds = 0f;

        if (!isRpgLevelingLoaded()) {
            return;
        }

        for (Map.Entry<UUID, BossTrackingSystem.BossData> entry : trackingSystem.snapshotTrackedBosses().entrySet()) {
            enforceBossHpScale(entry.getKey(), entry.getValue());
        }
    }

    private void enforceBossHpScale(UUID bossUuid, BossTrackingSystem.BossData bossData) {
        if (bossUuid == null || bossData == null || bossData.world == null || bossData.modifiers == null) {
            return;
        }

        float desiredMultiplier = Math.max(0.01f, bossData.modifiers.hpMultiplier());

        try {
            var bossRef = bossData.world.getEntityRef(bossUuid);
            if (bossRef == null || !bossRef.isValid()) {
                return;
            }

            var entityStore = bossData.world.getEntityStore().getStore();
            Object statMapObj = entityStore.getComponent(bossRef, EntityStatMap.getComponentType());
            if (!(statMapObj instanceof EntityStatMap statMap)) {
                return;
            }

            int healthIndex = DefaultEntityStatTypes.getHealth();
            Modifier rpgModifier = statMap.getModifier(healthIndex, RPG_LEVELING_HP_MODIFIER_KEY);
            if (rpgModifier != null) {
                statMap.removeModifier(healthIndex, RPG_LEVELING_HP_MODIFIER_KEY);
                LOGGER.log(
                        Level.INFO,
                        "BossArena compat removed RPGLeveling HP modifier for boss {0}: removed={1}",
                        new Object[]{
                                bossUuid,
                                describeModifier(rpgModifier)
                        }
                );
            }

            Modifier existing = statMap.getModifier(healthIndex, BossSpawnService.HEALTH_MODIFIER_KEY);
            if (existing instanceof StaticModifier staticModifier
                    && staticModifier.getTarget() == Modifier.ModifierTarget.MAX
                    && staticModifier.getCalculationType() == StaticModifier.CalculationType.MULTIPLICATIVE
                    && nearlyEqual(staticModifier.getAmount(), desiredMultiplier)) {
                return;
            }

            StaticModifier bossHealthModifier = new StaticModifier(
                    Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.MULTIPLICATIVE,
                    desiredMultiplier
            );
            statMap.putModifier(healthIndex, BossSpawnService.HEALTH_MODIFIER_KEY, bossHealthModifier);
            LOGGER.log(
                    Level.INFO,
                    "BossArena compat reapplied HP modifier for boss {0}: existing={1}, desired={2}",
                    new Object[]{
                            bossUuid,
                            describeModifier(existing),
                            String.format("%.4f", desiredMultiplier)
                    }
            );
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to enforce boss HP scale compatibility for " + bossUuid, e);
        }
    }

    private static boolean nearlyEqual(float a, float b) {
        return Math.abs(a - b) < EPSILON;
    }

    private static String describeModifier(Modifier modifier) {
        if (modifier == null) {
            return "null";
        }
        if (modifier instanceof StaticModifier staticModifier) {
            return staticModifier.getTarget()
                    + "/"
                    + staticModifier.getCalculationType()
                    + "/"
                    + String.format("%.4f", staticModifier.getAmount());
        }
        return modifier.getClass().getSimpleName();
    }

    private boolean isRpgLevelingLoaded() {
        PluginManager pluginManager = PluginManager.get();
        boolean loaded = pluginManager != null
                && pluginManager.hasPlugin(BossArenaPlugin.RPG_LEVELING_PLUGIN_ID, SemverRange.WILDCARD);
        if (!loadStateKnown || loaded != lastRpgLevelingLoaded) {
            LOGGER.info("BossArena compat RPGLeveling loaded state: " + loaded);
            loadStateKnown = true;
            lastRpgLevelingLoaded = loaded;
        }
        return loaded;
    }
}
