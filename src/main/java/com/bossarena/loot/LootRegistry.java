package com.bossarena.loot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class LootRegistry {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final Map<String, LootTable> LOOT_TABLES = new HashMap<>();

    /**
     * Register a loot table for a boss
     */
    public static void register(LootTable table) {
        if (table.bossName == null || table.bossName.isBlank()) {
            LOGGER.warning("Cannot register loot table with null/empty boss name");
            return;
        }

        String key = table.bossName.toLowerCase();
        LOOT_TABLES.put(key, table);
        LOGGER.info("Registered loot table for: " + table.bossName + " with " + table.items.size() + " items");
    }

    /**
     * Get a loot table by boss name (case-insensitive)
     */
    public static LootTable get(String bossName) {
        if (bossName == null) return null;
        return LOOT_TABLES.get(bossName.toLowerCase());
    }

    /**
     * Clear all loot tables
     */
    public static void clear() {
        LOOT_TABLES.clear();
    }

    /**
     * Get number of registered loot tables
     */
    public static int size() {
        return LOOT_TABLES.size();
    }

    /**
     * Load loot tables from JSON file
     */
    public static int loadFromFile(Path lootTablesPath) throws IOException {
        LOGGER.info("Loading loot tables from: " + lootTablesPath);

        if (!Files.exists(lootTablesPath)) {
            LOGGER.warning("Loot tables file not found: " + lootTablesPath);
            return 0;
        }

        Gson gson = new Gson();
        String json = Files.readString(lootTablesPath, StandardCharsets.UTF_8);

        LootTable[] tables = gson.fromJson(json, LootTable[].class);

        clear();

        if (tables != null) {
            for (LootTable table : tables) {
                register(table);
            }
        }

        LOGGER.info("Loaded " + size() + " loot tables from file");
        return size();
    }

    /**
     * Save loot tables to JSON file
     */
    public static void saveToFile(Path lootTablesPath) throws IOException {
        LOGGER.info("Saving loot tables to: " + lootTablesPath);

        LootTable[] tables = LOOT_TABLES.values().toArray(new LootTable[0]);

        String prettyJson = new GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(tables);

        Files.createDirectories(lootTablesPath.getParent());
        Files.writeString(lootTablesPath, prettyJson, StandardCharsets.UTF_8);

        LOGGER.info("Saved " + tables.length + " loot tables");
    }

    /**
     * Create default loot tables (used if file doesn't exist)
     */
    public static void createDefaults() {
        LOGGER.info("Creating default loot tables...");

        clear();

        // Example Boss loot table
        LootTable exampleBossLoot = new LootTable("Example Boss", 40.0);
        exampleBossLoot.addItem(new LootItem("Diamond", 1.0, 3, 7));        // 100% chance, 3-7 diamonds
        exampleBossLoot.addItem(new LootItem("Gold_Ingot", 0.8, 5, 10));    // 80% chance, 5-10 gold
        exampleBossLoot.addItem(new LootItem("Emerald", 0.5, 1, 3));        // 50% chance, 1-3 emeralds
        exampleBossLoot.addItem(new LootItem("Iron_Ingot", 1.0, 10, 20));   // 100% chance, 10-20 iron
        register(exampleBossLoot);

        // My_Boss loot table
        LootTable myBossLoot = new LootTable("My_Boss", 40.0);
        myBossLoot.addItem(new LootItem("Diamond", 1.0, 5, 10));           // 100% chance, 5-10 diamonds
        myBossLoot.addItem(new LootItem("Netherite_Ingot", 0.3, 1, 2));    // 30% chance, 1-2 netherite
        myBossLoot.addItem(new LootItem("Enchanted_Golden_Apple", 0.5, 1, 3)); // 50% chance, 1-3 god apples
        register(myBossLoot);

        LOGGER.info("Created " + size() + " default loot tables");
    }
}