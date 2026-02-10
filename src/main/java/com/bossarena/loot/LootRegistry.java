package com.bossarena.loot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Static registry for boss loot tables.
 * Loot tables are registered by boss name (case-insensitive).
 */
public final class LootRegistry {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final Map<String, LootTable> TABLES = new ConcurrentHashMap<>();

    private LootRegistry() {}

    public static void register(LootTable table) {
        TABLES.put(table.bossName.toLowerCase(), table);
        LOGGER.info("Registered loot table for boss: " + table.bossName + " (" + table.items.size() + " items)");
    }

    public static LootTable get(String bossName) {
        return TABLES.get(bossName.toLowerCase());
    }

    public static void clear() {
        TABLES.clear();
    }

    public static int size() {
        return TABLES.size();
    }
}
