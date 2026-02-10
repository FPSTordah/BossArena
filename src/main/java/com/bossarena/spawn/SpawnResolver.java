package com.bossarena.spawn;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.UUID;

@FunctionalInterface
public interface SpawnResolver {
  @SuppressWarnings("unused")
  UUID resolveNearestNpc(World world, Vector3d spawnPos, long startedAtTick);
}