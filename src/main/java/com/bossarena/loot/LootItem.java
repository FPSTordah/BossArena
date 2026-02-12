package com.bossarena.loot;

public class LootItem {
    public String itemId;        // e.g. "Diamond", "Gold_Ingot"
    public double dropChance;    // 0.0 to 1.0 (0% to 100%)
    public int minAmount;        // Minimum items to drop
    public int maxAmount;        // Maximum items to drop

    public LootItem() {}

    public LootItem(String itemId, double dropChance, int minAmount, int maxAmount) {
        this.itemId = itemId;
        this.dropChance = dropChance;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
    }
}