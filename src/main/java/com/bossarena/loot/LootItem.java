package com.bossarena.loot;

/**
 * A single item entry within a LootTable.
 */
public class LootItem {
    public String itemId;
    public double dropChance;  // 0.0 to 1.0
    public int minAmount;
    public int maxAmount;

    public LootItem() {}

    public LootItem(String itemId, double dropChance, int minAmount, int maxAmount) {
        this.itemId = itemId;
        this.dropChance = dropChance;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
    }
}
