package com.bossarena.loot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        if (table == null) {
            LOGGER.warning("Cannot register null loot table");
            return;
        }
        if (table.bossName == null || table.bossName.isBlank()) {
            LOGGER.warning("Cannot register loot table with null/empty boss name");
            return;
        }
        if (table.items == null) {
            table.items = new ArrayList<>();
        } else {
            table.items.removeIf(item -> item == null);
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

    public static LootTable remove(String bossName) {
        if (bossName == null) return null;
        return LOOT_TABLES.remove(bossName.toLowerCase());
    }

    public static Map<String, LootTable> getAll() {
        return new HashMap<>(LOOT_TABLES);
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
        exampleBossLoot.addItem(new LootItem("Ingredient_Fibre", 1.0, 4, 10));
        exampleBossLoot.addItem(new LootItem("Ingredient_Stick", 0.85, 2, 6));
        exampleBossLoot.addItem(new LootItem("Ore_Copper", 0.70, 1, 4));
        exampleBossLoot.addItem(new LootItem("Plant_Fruit_Berries_Red", 0.60, 2, 5));
        register(exampleBossLoot);

        // My_Boss loot table
        LootTable myBossLoot = new LootTable("My_Boss", 40.0);
        myBossLoot.addItem(new LootItem("Ingredient_Fibre", 1.0, 5, 12));
        myBossLoot.addItem(new LootItem("Ingredient_Hide_Light", 0.80, 1, 4));
        myBossLoot.addItem(new LootItem("Ore_Copper", 0.75, 1, 5));
        myBossLoot.addItem(new LootItem("Plant_Fruit_Apple", 0.55, 2, 4));
        register(myBossLoot);

        LOGGER.info("Created " + size() + " default loot tables");
    }
}
