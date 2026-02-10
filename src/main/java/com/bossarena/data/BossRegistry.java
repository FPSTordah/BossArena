package com.bossarena.data;


import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public final class BossRegistry {
  private static final Logger LOGGER = Logger.getLogger("BossArena");
  private static final Map<String, BossDefinition> BOSSES = new HashMap<>();

  private BossRegistry() {}

  public static void register(BossDefinition def) {
    if (def != null && def.bossName != null) {
      BOSSES.put(def.bossName.toLowerCase(), def);
    }
  }

  public static void clear() {
    BOSSES.clear();
  }

  public static int size() {
    return BOSSES.size();
  }

  public static BossDefinition get(String bossName) {
    if (bossName == null) return null;
    return BOSSES.get(bossName.toLowerCase());
  }

  public static Map<String, BossDefinition> getAll() {
    return new HashMap<>(BOSSES);
  }
}