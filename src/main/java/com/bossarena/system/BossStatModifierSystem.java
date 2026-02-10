package com.bossarena.system;

import com.bossarena.boss.BossFightComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.logging.Logger;

@SuppressWarnings("unused")
public class BossStatModifierSystem {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private final BossTrackingSystem trackingSystem;

    public BossStatModifierSystem(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
    }

    public void onEntityAdded(@Nonnull Ref<EntityStore> entityRef, @Nonnull EntityStore store) {
        // No longer needed - modifiers are applied directly in BossSpawnService
        // This method is kept for compatibility but does nothing
    }
}