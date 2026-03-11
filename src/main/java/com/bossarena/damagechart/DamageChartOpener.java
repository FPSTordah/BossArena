package com.bossarena.damagechart;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;

/**
 * Opens the boss event damage chart for eligible players (vanilla custom page).
 */
public interface DamageChartOpener {

    /**
     * Show the damage chart to each eligible player. Same data for all.
     *
     * @param world            world of the event
     * @param eligiblePlayers   players in loot radius who should see the chart
     * @param rows              sorted rows (display name, damage)
     * @param bossName           name of the boss for the title
     * @param store              entity store (for opening custom page fallback)
     */
    void openChart(World world, List<PlayerRef> eligiblePlayers, List<DisplayRow> rows, String bossName, Store<EntityStore> store);

    record DisplayRow(String displayName, long damage) {}
}
