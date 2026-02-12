package com.bossarena.loot;

public class GeneratedLoot {
    public final String itemId;
    public final int amount;

    public GeneratedLoot(String itemId, int amount) {
        this.itemId = itemId;
        this.amount = amount;
    }

    @Override
    public String toString() {
        return amount + "x " + itemId;
    }
}