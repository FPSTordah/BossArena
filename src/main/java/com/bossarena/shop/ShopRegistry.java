package com.bossarena.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ShopRegistry {
    private static final List<ShopEntry> ENTRIES = new ArrayList<>();

    public static void register(ShopEntry entry) {
        if (entry != null && entry.arenaId != null && entry.bossId != null) {
            ENTRIES.add(entry);
        }
    }

    public static void remove(String arenaId, String bossId) {
        ENTRIES.removeIf(e ->
                e.arenaId.equalsIgnoreCase(arenaId) &&
                        e.bossId.equalsIgnoreCase(bossId)
        );
    }

    public static List<ShopEntry> getAll() {
        return new ArrayList<>(ENTRIES);
    }

    public static void clear() {
        ENTRIES.clear();
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static ShopEntry find(String arenaId, String bossId) {
        return ENTRIES.stream()
                .filter(e -> e.arenaId.equalsIgnoreCase(arenaId) && e.bossId.equalsIgnoreCase(bossId))
                .findFirst()
                .orElse(null);
    }
}