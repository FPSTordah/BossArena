package com.bossarena.config;

import com.bossarena.BossArenaConfig;
import com.bossarena.BossArenaPlugin;
import com.bossarena.arena.ArenaDef;

public final class ConfigEditor {

  public static void createArena(
          BossArenaPlugin plugin,
          String arenaId,
          String worldId,
          int anchorX,
          int anchorY,
          int anchorZ,
          int radius,
          int lifetimeSeconds
  ) {
    BossArenaConfig cfg = plugin.cfg();
    if (cfg.arenas == null) cfg.arenas = new ArenaDef[0];

    // prevent duplicates
    for (ArenaDef a : cfg.arenas) {
      if (a != null && a.id != null && a.id.equalsIgnoreCase(arenaId)) {
        throw new IllegalArgumentException("Arena already exists: " + arenaId);
      }
    }

    ArenaDef arena = new ArenaDef();
    arena.id = arenaId;
    arena.worldId = worldId;
    arena.bossSpawnX = anchorX;
    arena.bossSpawnY = anchorY;
    arena.bossSpawnZ = anchorZ;
    arena.eligibilityRadiusBlocks = radius;
    arena.chestLifetimeSeconds = lifetimeSeconds;

    // append to array
    ArenaDef[] old = cfg.arenas;
    ArenaDef[] next = new ArenaDef[old.length + 1];
    System.arraycopy(old, 0, next, 0, old.length);
    next[old.length] = arena;
    cfg.arenas = next;

    // Fix: Save is void, so it cannot be chained with .exceptionally()
    try {
      plugin.getConfigHandle().save();
    } catch (Exception e) {
      plugin.getLogger().atWarning().withCause(e).log("Failed to save BossArenaConfig.");
    }
  }

  public static java.util.List<String> listArenas(BossArenaPlugin plugin) {
    BossArenaConfig cfg = plugin.cfg();
    java.util.List<String> out = new java.util.ArrayList<>();
    if (cfg.arenas == null) return out;

    for (ArenaDef a : cfg.arenas) {
      if (a == null || a.id == null) continue;
      out.add(a.id + " (world=" + a.worldId + " @ " + a.bossSpawnX + " " + a.bossSpawnY + " " + a.bossSpawnZ + ")");
    }
    return out;
  }

  public static boolean deleteArena(BossArenaPlugin plugin, String arenaId) {
    BossArenaConfig cfg = plugin.cfg();
    if (cfg.arenas == null || cfg.arenas.length == 0) return false;

    int keep = 0;
    for (ArenaDef a : cfg.arenas) {
      if (a != null && a.id != null && a.id.equalsIgnoreCase(arenaId)) continue;
      keep++;
    }
    if (keep == cfg.arenas.length) return false;

    ArenaDef[] next = new ArenaDef[keep];
    int i = 0;
    for (ArenaDef a : cfg.arenas) {
      if (a != null && a.id != null && a.id.equalsIgnoreCase(arenaId)) continue;
      next[i++] = a;
    }
    cfg.arenas = next;

    plugin.getConfigHandle().save();
    return true;
  }

  private ConfigEditor() {}
}
