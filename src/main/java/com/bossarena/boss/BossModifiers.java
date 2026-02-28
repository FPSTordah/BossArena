package com.bossarena.boss;

public record BossModifiers(
    float hpMultiplier,
    float damageMultiplier,
    float speedMultiplier,
    float scaleMultiplier,
    float attackRateMultiplier,
    float abilityCooldownMultiplier,
    float knockbackGivenMultiplier,
    float knockbackTakenMultiplier,
    float turnRateMultiplier,
    float regenMultiplier
) {}
