package com.bossarena.spawn;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.Collection;
import java.lang.reflect.Method;

@SuppressWarnings("unused")
public final class NearestNpcSpawnResolver implements SpawnResolver {

    private final Set<UUID> claimedInCurrentCycle = new HashSet<>();

    @Override
    public UUID resolveNearestNpc(World world, Vector3d spawnPos, long startedAtTick) {
        Collection<Entity> entities = findEntities(world);
        if (entities == null) return null;

        for (Entity e : entities) {
            if (!(e instanceof NPCEntity)) continue;

            UUID uuid = getEntityUuid(e);
            if (uuid == null || claimedInCurrentCycle.contains(uuid)) continue;

            Vector3d pos = getEntityPosition(e);
            if (pos != null) {
                double dx = pos.getX() - spawnPos.getX();
                double dy = pos.getY() - spawnPos.getY();
                double dz = pos.getZ() - spawnPos.getZ();
                double distSq = (dx * dx) + (dy * dy) + (dz * dz);

                if (distSq < 9.0) {
                    claimedInCurrentCycle.add(uuid);
                    return uuid;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("SpellCheckingInspection")
    private Vector3d getEntityPosition(Object entity) {
        // Try common method names for getting position
        for (String name : new String[]{"getPosition", "getWorldPosition", "getPos"}) {
            try {
                Method m = entity.getClass().getMethod(name);
                Object v = m.invoke(entity);
                if (v instanceof Vector3d) return (Vector3d) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private UUID getEntityUuid(Entity entity) {
        try {
            Method m = entity.getClass().getMethod("getComponent", com.hypixel.hytale.component.ComponentType.class);
            Object comp = m.invoke(entity, UUIDComponent.getComponentType());
            if (comp instanceof UUIDComponent uuidComp) {
                return uuidComp.getUuid();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private Collection<Entity> findEntities(World world) {
        try {
            Method m = world.getClass().getMethod("getEntities");
            return (Collection<Entity>) m.invoke(world);
        } catch (Throwable t) {
            return null;
        }
    }

    public void clearCycle() {
        claimedInCurrentCycle.clear();
    }
}