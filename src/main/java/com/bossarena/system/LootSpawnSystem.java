package com.bossarena.system;

import com.bossarena.loot.BossLootHandler;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.logging.Logger;

public class LootSpawnSystem extends TickingSystem<EntityStore> {
    private static final Logger LOGGER = Logger.getLogger("BossArena");

    @Override
    public void tick(float dt, int index, @Nonnull Store<EntityStore> store) {
        // Process all pending loot spawns
        while (!BossLootHandler.PENDING_SPAWNS.isEmpty()) {
            var spawn = BossLootHandler.PENDING_SPAWNS.poll();
            if (spawn != null) {
                LOGGER.info("Processing queued loot spawn for: " + spawn.bossName);
                BossLootHandler.handleBossDeath(spawn.world, spawn.location, spawn.bossName);
            }
        }
    }
}