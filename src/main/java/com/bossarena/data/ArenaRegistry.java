package com.bossarena.data;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ArenaRegistry {
    private static final Map<String, Arena> ARENAS = new ConcurrentHashMap<>();

    public static void register(Arena arena) {
        if (arena != null && arena.arenaId != null) {
            ARENAS.put(arena.arenaId.toLowerCase(), arena);
        }
    }

    public static Arena get(String arenaId) {
        return ARENAS.get(arenaId.toLowerCase());
    }

    public static boolean exists(String arenaId) {
        return ARENAS.containsKey(arenaId.toLowerCase());
    }

    public static Arena remove(String arenaId) {
        return ARENAS.remove(arenaId.toLowerCase());
    }

    public static void clear() {
        ARENAS.clear();
    }

    public static Collection<Arena> getAll() {
        return ARENAS.values();
    }

    public static int size() {
        return ARENAS.size();
    }
}