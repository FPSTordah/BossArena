package com.bossarena.data;

import com.hypixel.hytale.math.vector.Vector3d;

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

    /**
     * Returns the nearest registered arena in the given world to the supplied position, or null
     * when no arenas exist for that world.
     */
    public static Arena findNearest(String worldName, double x, double y, double z) {
        if (worldName == null || worldName.isBlank() || ARENAS.isEmpty()) {
            return null;
        }

        Arena best = null;
        double bestDistSq = Double.MAX_VALUE;
        Vector3d point = new Vector3d(x, y, z);

        for (Arena arena : ARENAS.values()) {
            if (arena == null || arena.worldName == null) {
                continue;
            }
            if (!worldName.equalsIgnoreCase(arena.worldName)) {
                continue;
            }
            Vector3d center = arena.getPosition();
            double dx = center.x - point.x;
            double dy = center.y - point.y;
            double dz = center.z - point.z;
            double distSq = (dx * dx) + (dy * dy) + (dz * dz);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = arena;
            }
        }

        return best;
    }
}