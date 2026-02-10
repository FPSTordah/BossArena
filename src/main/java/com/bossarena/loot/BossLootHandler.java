package com.bossarena.loot;

import com.bossarena.boss.PlayerFinder;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class BossLootHandler {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final Random RANDOM = new Random();

    // Track pending loot: ChestLocation -> Map of PlayerUUID -> their loot
    private static final Map<Vector3d, Map<UUID, List<GeneratedLoot>>> PENDING_LOOT = new ConcurrentHashMap<>();

    // Track who has already claimed loot: ChestLocation -> Set of PlayerUUIDs
    private static final Map<Vector3d, Set<UUID>> CLAIMED_LOOT = new ConcurrentHashMap<>();

    public static void handleBossDeath(World world, Vector3d deathLocation, String bossName) {
        LootTable lootTable = LootRegistry.get(bossName);

        if (lootTable == null) {
            LOGGER.info("No loot table found for boss: " + bossName);
            return;
        }

        // Find eligible players (within radius when boss died)
        List<Player> nearbyPlayers = PlayerFinder.playersInRadius(world, deathLocation, lootTable.lootRadius);

        if (nearbyPlayers.isEmpty()) {
            LOGGER.info("No players nearby for loot - boss died alone");
            return;
        }

        LOGGER.info("Boss '" + bossName + "' died - " + nearbyPlayers.size() + " eligible players for loot");

        // Generate loot for EACH eligible player
        Map<UUID, List<GeneratedLoot>> playerLoot = new HashMap<>();

        for (Player player : nearbyPlayers) {
            List<GeneratedLoot> loot = generateLoot(lootTable);
            playerLoot.put(player.getUuid(), loot);
            LOGGER.info("  Generated loot for: " + player.getName() + " (" + loot.size() + " items)");
        }

        // Store pending loot
        Vector3d chestLocation = deathLocation.add(0, 1, 0); // Spawn 1 block above
        PENDING_LOOT.put(chestLocation, playerLoot);
        CLAIMED_LOOT.put(chestLocation, new HashSet<>());

        // Spawn chest at boss location
        spawnLootChest(world, chestLocation);
    }

    private static List<GeneratedLoot> generateLoot(LootTable table) {
        List<GeneratedLoot> items = new ArrayList<>();

        for (LootItem item : table.items) {
            // Roll for drop chance
            if (RANDOM.nextDouble() <= item.dropChance) {
                // Calculate random amount between min and max
                int amount = item.minAmount;
                if (item.maxAmount > item.minAmount) {
                    amount = item.minAmount + RANDOM.nextInt(item.maxAmount - item.minAmount + 1);
                }

                items.add(new GeneratedLoot(item.itemId, amount));
            }
        }

        return items;
    }

    private static void spawnLootChest(World world, Vector3d location) {
        LOGGER.info("Attempting to spawn loot chest at: " + location);

        // TODO: Place chest block using Hytale API
        // We need to find the block placement API
        // Something like: world.setBlock(location, "minecraft:chest")

        LOGGER.warning("Chest spawning not yet implemented - needs Hytale block API");
    }

    /**
     * Called when a player interacts with a chest.
     * Returns the loot they should receive, or null if not eligible.
     */
    public static List<GeneratedLoot> onChestOpen(Vector3d chestLocation, UUID playerUuid) {
        // Check if there's pending loot at this location
        Map<UUID, List<GeneratedLoot>> lootAtChest = PENDING_LOOT.get(chestLocation);
        if (lootAtChest == null) {
            return null; // Not a boss loot chest
        }

        // Check if player already claimed
        Set<UUID> claimed = CLAIMED_LOOT.get(chestLocation);
        if (claimed != null && claimed.contains(playerUuid)) {
            LOGGER.info("Player already claimed loot from this chest");
            return new ArrayList<>(); // Empty - already looted
        }

        // Get player's loot
        List<GeneratedLoot> playerLoot = lootAtChest.get(playerUuid);
        if (playerLoot == null) {
            LOGGER.info("Player not eligible for this chest");
            return new ArrayList<>(); // Empty - not eligible
        }

        // Mark as claimed
        if (claimed != null) {
            claimed.add(playerUuid);
        }

        LOGGER.info("Player claimed their loot: " + playerLoot.size() + " items");

        // Check if all players have claimed - if so, clean up
        if (claimed != null && claimed.size() >= lootAtChest.size()) {
            PENDING_LOOT.remove(chestLocation);
            CLAIMED_LOOT.remove(chestLocation);
            LOGGER.info("All players claimed loot - cleaned up chest data");
        }

        return playerLoot;
    }

    /**
     * Check if a player is eligible to loot a chest at this location.
     */
    public static boolean isEligible(Vector3d chestLocation, UUID playerUuid) {
        Map<UUID, List<GeneratedLoot>> lootAtChest = PENDING_LOOT.get(chestLocation);
        if (lootAtChest == null) return false;

        Set<UUID> claimed = CLAIMED_LOOT.get(chestLocation);
        if (claimed != null && claimed.contains(playerUuid)) return false;

        return lootAtChest.containsKey(playerUuid);
    }

    /**
     * Manually clean up a chest location (e.g., if chest is destroyed).
     */
    public static void cleanupChest(Vector3d chestLocation) {
        PENDING_LOOT.remove(chestLocation);
        CLAIMED_LOOT.remove(chestLocation);
        LOGGER.info("Cleaned up chest data at: " + chestLocation);
    }

    // Helper class for generated loot
    public static class GeneratedLoot {
        public final String itemId;
        public final int amount;

        public GeneratedLoot(String itemId, int amount) {
            this.itemId = itemId;
            this.amount = amount;
        }

        @Override
        public String toString() {
            return itemId + " x" + amount;
        }
    }
}
