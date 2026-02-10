package com.bossarena.util;

import com.bossarena.data.BossDefinition;
import com.bossarena.boss.BossModifiers;  // Add this import

public final class BossScaler {

    private BossScaler() {}

    /**
     * Calculate the final multiplier based on base modifier + (per-player increase * player count)
     */
    public static float calculateMultiplier(float baseModifier, float perPlayerIncrease, int playerCount) {
        return baseModifier + (perPlayerIncrease * playerCount);
    }

    public static BossModifiers calculateModifiers(BossDefinition def, int playerCount) {
        if (def == null || def.modifiers == null || def.perPlayerIncrease == null) {
            return new BossModifiers(1.0f, 1.0f, 1.0f, 1.0f);
        }

        float hp = calculateMultiplier(def.modifiers.hp, def.perPlayerIncrease.hp, playerCount);
        float damage = calculateMultiplier(def.modifiers.damage, def.perPlayerIncrease.damage, playerCount);
        float size = calculateMultiplier(def.modifiers.size, def.perPlayerIncrease.size, playerCount);

        return new BossModifiers(hp, damage, 1.0f, size);
    }
}