package com.bossarena.boss;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class PlayerFinder {

    private PlayerFinder() {}

    @SuppressWarnings("deprecation")
    public static List<Player> playersInRadius(World world, Vector3d center, int radius) {
        if (world == null || center == null) {
            return Collections.emptyList();
        }

        double radiusSq = (double) radius * radius;

        // TODO: Update when getPlayers() is removed - check Hytale docs for replacement API
        return world.getPlayers().stream()
                .filter(player -> {
                    Vector3d pPos = getPlayerPosition(player);
                    return pPos != null && getDistanceSq(pPos, center) <= radiusSq;
                })
                .collect(Collectors.toList());
    }

    private static double getDistanceSq(Vector3d a, Vector3d b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    @SuppressWarnings("SpellCheckingInspection")
    private static Vector3d getPlayerPosition(Player player) {
        // Try confirmed Hytale accessors via reflection to avoid compiler errors
        for (String mName : new String[]{"getWorldPosition", "getPosition", "getPos"}) {
            try {
                Method m = player.getClass().getMethod(mName);
                Object res = m.invoke(player);
                if (res instanceof Vector3d v) {
                    return v;
                }
            } catch (Exception ignored) {}
        }

        try {
            // Fallback to Transform component
            Method getTransform = player.getClass().getMethod("getTransform");
            Object transform = getTransform.invoke(player);
            if (transform != null) {
                Method getPos = transform.getClass().getMethod("getPosition");
                Object pos = getPos.invoke(transform);
                if (pos instanceof Vector3d v) {
                    return v;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }
}