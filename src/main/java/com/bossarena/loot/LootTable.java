package com.bossarena.loot;

import java.util.ArrayList;
import java.util.List;

public class LootTable {
    public String bossName;           // Which boss this loot is for
    public double lootRadius;         // How close players need to be to get loot
    public List<LootItem> items;      // List of possible drops
    public List<String> commands;     // List of console commands to execute for each player

    public LootTable() {
        this.items = new ArrayList<>();
        this.commands = new ArrayList<>();
    }

    public LootTable(String bossName, double lootRadius) {
        this.bossName = bossName;
        this.lootRadius = lootRadius;
        this.items = new ArrayList<>();
        this.commands = new ArrayList<>();
    }

    public void addItem(LootItem item) {
        this.items.add(item);
    }

    public void addCommand(String command) {
        this.commands.add(command);
    }
}