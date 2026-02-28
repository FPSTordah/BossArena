package com.bossarena.util;

import com.bossarena.data.BossDefinition;
import com.bossarena.boss.BossModifiers;

public final class BossScaler {

    private BossScaler() {}

    public static BossModifiers calculateModifiers(BossDefinition def, int playerCount) {
        if (def == null) {
            return defaultModifiers();
        }

        float baseHp = positiveOrDefault(def.modifiers != null ? def.modifiers.hp : 1.0f, 1.0f);
        float baseDamage = positiveOrDefault(def.modifiers != null ? def.modifiers.damage : 1.0f, 1.0f);
        float baseSpeed = positiveOrDefault(def.modifiers != null ? def.modifiers.movementSpeed : 1.0f, 1.0f);
        float baseSize = positiveOrDefault(def.modifiers != null ? def.modifiers.size : 1.0f, 1.0f);
        float baseAttackRate = positiveOrDefault(def.modifiers != null ? def.modifiers.attackRate : 1.0f, 1.0f);
        float baseAbilityCooldown = positiveOrDefault(def.modifiers != null ? def.modifiers.abilityCooldown : 1.0f, 1.0f);
        float baseKnockbackGiven = positiveOrDefault(def.modifiers != null ? def.modifiers.knockbackGiven : 1.0f, 1.0f);
        float baseKnockbackTaken = positiveOrDefault(def.modifiers != null ? def.modifiers.knockbackTaken : 1.0f, 1.0f);
        float baseTurnRate = positiveOrDefault(def.modifiers != null ? def.modifiers.turnRate : 1.0f, 1.0f);
        float baseRegen = positiveOrDefault(def.modifiers != null ? def.modifiers.regen : 1.0f, 1.0f);

        float perHp = finiteOrDefault(def.perPlayerIncrease != null ? def.perPlayerIncrease.hp : 0.0f, 0.0f);
        float perDamage = finiteOrDefault(def.perPlayerIncrease != null ? def.perPlayerIncrease.damage : 0.0f, 0.0f);
        float perSpeed = finiteOrDefault(def.perPlayerIncrease != null ? def.perPlayerIncrease.movementSpeed : 0.0f, 0.0f);
        float perSize = finiteOrDefault(def.perPlayerIncrease != null ? def.perPlayerIncrease.size : 0.0f, 0.0f);
        float perAttackRate = finiteOrDefault(def.perPlayerIncrease != null ? def.perPlayerIncrease.attackRate : 0.0f, 0.0f);
        float perAbilityCooldown = finiteOrDefault(def.perPlayerIncrease != null ? def.perPlayerIncrease.abilityCooldown : 0.0f, 0.0f);
        float perKnockbackGiven = finiteOrDefault(def.perPlayerIncrease != null ? def.perPlayerIncrease.knockbackGiven : 0.0f, 0.0f);
        float perKnockbackTaken = finiteOrDefault(def.perPlayerIncrease != null ? def.perPlayerIncrease.knockbackTaken : 0.0f, 0.0f);
        float perTurnRate = finiteOrDefault(def.perPlayerIncrease != null ? def.perPlayerIncrease.turnRate : 0.0f, 0.0f);
        float perRegen = finiteOrDefault(def.perPlayerIncrease != null ? def.perPlayerIncrease.regen : 0.0f, 0.0f);

        int extraPlayers = Math.max(0, playerCount - 1);

        float hp = positiveOrDefault(baseHp + (perHp * extraPlayers), 1.0f);
        float damage = positiveOrDefault(baseDamage + (perDamage * extraPlayers), 1.0f);
        float speed = positiveOrDefault(baseSpeed + (perSpeed * extraPlayers), 1.0f);
        float size = positiveOrDefault(baseSize + (perSize * extraPlayers), 1.0f);
        float attackRate = positiveOrDefault(baseAttackRate + (perAttackRate * extraPlayers), 1.0f);
        float abilityCooldown = positiveOrDefault(baseAbilityCooldown + (perAbilityCooldown * extraPlayers), 1.0f);
        float knockbackGiven = positiveOrDefault(baseKnockbackGiven + (perKnockbackGiven * extraPlayers), 1.0f);
        float knockbackTaken = positiveOrDefault(baseKnockbackTaken + (perKnockbackTaken * extraPlayers), 1.0f);
        float turnRate = positiveOrDefault(baseTurnRate + (perTurnRate * extraPlayers), 1.0f);
        float regen = positiveOrDefault(baseRegen + (perRegen * extraPlayers), 1.0f);

        return new BossModifiers(
                hp,
                damage,
                speed,
                size,
                attackRate,
                abilityCooldown,
                knockbackGiven,
                knockbackTaken,
                turnRate,
                regen
        );
    }

    private static BossModifiers defaultModifiers() {
        return new BossModifiers(
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                1.0f
        );
    }

    private static float finiteOrDefault(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    private static float positiveOrDefault(float value, float fallback) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            return fallback;
        }
        return value;
    }
}
