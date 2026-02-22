package com.bossarena.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

public final class BossRegistry {
  private static final Logger LOGGER = Logger.getLogger("BossArena");
  private static final Map<String, BossDefinition> BOSSES = new LinkedHashMap<>();
  private static final String DEFAULT_TIER = "uncommon";

  private BossRegistry() {}

  public static void register(BossDefinition def) {
    if (def != null && def.bossName != null) {
      if (def.modifiers == null) {
        def.modifiers = new BossDefinition.Modifiers();
      }
      if (def.perPlayerIncrease == null) {
        def.perPlayerIncrease = new BossDefinition.PerPlayerIncrease();
      }
      if (def.extraMobs == null) {
        def.extraMobs = new BossDefinition.ExtraMobs();
      }
      def.tier = normalizeTier(def.tier);
      def.extraMobs.sanitize();
      BOSSES.put(def.bossName.toLowerCase(), def);
    }
  }

  private static String normalizeTier(String input) {
    if (input == null) {
      return DEFAULT_TIER;
    }
    String tier = input.trim().toLowerCase();
    return switch (tier) {
      case "uncommon", "common", "rare", "epic", "legendary" -> tier;
      default -> DEFAULT_TIER;
    };
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

  public static boolean exists(String bossId) {
    if (bossId == null) return false;
    return BOSSES.containsKey(bossId.toLowerCase());
  }

  public static BossDefinition remove(String bossId) {
    if (bossId == null) return null;
    return BOSSES.remove(bossId.toLowerCase());
  }

  public static Map<String, BossDefinition> getAll() {
    return new LinkedHashMap<>(BOSSES);
  }
}
