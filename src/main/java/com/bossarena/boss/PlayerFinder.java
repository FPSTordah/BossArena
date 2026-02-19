package com.bossarena.boss;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class PlayerFinder {

    private PlayerFinder() {}

    public static List<Player> playersInRadius(World world, Vector3d center, int radius) {
        if (world == null || center == null) {
            return Collections.emptyList();
        }

        double radiusSq = (double) radius * radius;

        return world.getPlayerRefs().stream()
                .filter(ref -> {
                    Vector3d pPos = ref.getTransform().getPosition();
                    return pPos != null && getDistanceSq(pPos, center) <= radiusSq;
                })
                .map(PlayerFinder::resolvePlayerFromRef)
                .filter(player -> player != null)
                .collect(Collectors.toList());
    }

    public static int countPlayersInRadius(World world, Vector3d center, int radius) {
        if (world == null || center == null) {
            return 0;
        }

        double radiusSq = (double) radius * radius;
        int count = 0;

        for (PlayerRef ref : world.getPlayerRefs()) {
            Vector3d pos = ref.getTransform().getPosition();
            if (pos != null && getDistanceSq(pos, center) <= radiusSq) {
                count++;
            }
        }

        return count;
    }

    private static double getDistanceSq(Vector3d a, Vector3d b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private static Player resolvePlayerFromRef(PlayerRef ref) {
        if (ref == null) {
            return null;
        }
        try {
            Method getComponent = ref.getClass().getMethod("getComponent", com.hypixel.hytale.component.ComponentType.class);
            Object value = getComponent.invoke(ref, Player.getComponentType());
            return value instanceof Player ? (Player) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

}
