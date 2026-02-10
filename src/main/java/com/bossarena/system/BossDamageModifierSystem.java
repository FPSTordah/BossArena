package com.bossarena.system;

import com.bossarena.boss.BossFightComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

@SuppressWarnings("unused")
public class BossDamageModifierSystem {
    private final BossTrackingSystem trackingSystem;

    public BossDamageModifierSystem(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
    }

    public void onDamageDealt(@Nonnull Ref<EntityStore> entityRef, @Nonnull EntityStore store, float damage) {
        // Damage modification not implemented
        // The "Damage" stat doesn't exist in Hytale's EntityStatMap
        // This system is disabled for now
    }
}