package com.bossarena.loot;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines the loot table for a specific boss, including
 * drop items and eligibility radius.
 */
public class LootTable {
    public String bossName;
    public int lootRadius = 40;  // How close players must be to receive loot
    public List<LootItem> items = new ArrayList<>();

    public LootTable() {}

    public LootTable(String bossName, int lootRadius, List<LootItem> items) {
        this.bossName = bossName;
        this.lootRadius = lootRadius;
        this.items = items;
    }
}
