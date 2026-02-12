package com.bossarena.shop;

public class ShopEntry {
    public String arenaId;
    public String bossId;
    public double cost;
    public String displayName;
    public String description;

    // For GSON
    public ShopEntry() {}

    public ShopEntry(String arenaId, String bossId, double cost) {
        this.arenaId = arenaId;
        this.bossId = bossId;
        this.cost = cost;
        this.displayName = bossId; // Default to bossId
        this.description = "Spawn " + bossId + " at " + arenaId;
    }

    @Override
    public String toString() {
        return String.format("%s at %s - $%.2f", bossId, arenaId, cost);
    }
}