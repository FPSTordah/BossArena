package com.bossarena.loot;

/**
 * Placeholder while migrating loot/chest APIs.
 */
@SuppressWarnings("unused")
public final class ArenaLoot {
  private ArenaLoot() {}

  // Dummy handler so ArenaLoot::onChestOpen compiles (until event API is ported)
  public static void onChestOpen(@SuppressWarnings("unused") Object event) {
    // TODO: implement with correct event type
  }
}