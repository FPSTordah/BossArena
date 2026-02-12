package com.bossarena.util;

import com.bossarena.data.BossDefinition;
import com.bossarena.boss.BossModifiers;  // Add this import

public final class BossScaler {

    private BossScaler() {}

    public static BossModifiers calculateModifiers(BossDefinition def, int playerCount) {
        if (def == null) {
            return new BossModifiers(1.0f, 1.0f, 1.0f, 1.0f);
        }

        float baseHp = def.modifiers != null ? def.modifiers.hp : 1.0f;
        float baseDamage = def.modifiers != null ? def.modifiers.damage : 1.0f;
        float baseSize = def.modifiers != null ? def.modifiers.size : 1.0f;

        float perHp = def.perPlayerIncrease != null ? def.perPlayerIncrease.hp : 0.0f;
        float perDamage = def.perPlayerIncrease != null ? def.perPlayerIncrease.damage : 0.0f;
        float perSize = def.perPlayerIncrease != null ? def.perPlayerIncrease.size : 0.0f;

        int extraPlayers = Math.max(0, playerCount - 1);

        float hp = baseHp + (perHp * extraPlayers);
        float damage = baseDamage + (perDamage * extraPlayers);
        float size = baseSize + (perSize * extraPlayers);

        return new BossModifiers(hp, damage, 1.0f, size);
    }
}
