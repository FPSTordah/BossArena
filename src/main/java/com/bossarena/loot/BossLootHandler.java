package com.bossarena.loot;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.hypixel.hytale.function.consumer.TriIntConsumer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class BossLootHandler {
    private static final Logger LOGGER = Logger.getLogger("BossArena");

    public static class PendingLootSpawn {
        public final World world;
        public final Vector3d location;
        public final String bossName;

        public PendingLootSpawn(World world, Vector3d location, String bossName) {
            this.world = world;
            this.location = new Vector3d(location.x, location.y, location.z);
            this.bossName = bossName;
        }
    }

    public static final Queue<PendingLootSpawn> PENDING_SPAWNS = new ConcurrentLinkedQueue<>();
    private static final Map<Vector3d, Map<UUID, List<GeneratedLoot>>> CHEST_LOOT = new ConcurrentHashMap<>();
    private static final Map<Vector3d, ScheduledFuture<?>> CHEST_EXPIRY_TASKS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService CHEST_EXPIRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BossArena-ChestExpiry");
                t.setDaemon(true);
                return t;
            });
    private static final long CHEST_EXPIRY_MS = 30_000L;

    // Queue a loot spawn
    public static void queueLootSpawn(World world, Vector3d location, String bossName) {
        PENDING_SPAWNS.add(new PendingLootSpawn(world, location, bossName));
        LOGGER.info("Queued loot spawn for: " + bossName + " at " + location);
    }

    // Main handler called from LootSpawnSystem
    public static void handleBossDeath(World world, Vector3d chestLocation, String bossName) {
        LOGGER.info("=== BOSS LOOT DEBUG ===");
        LOGGER.info("Boss: " + bossName + " died at: " + chestLocation);

        LootTable table = LootRegistry.get(bossName);
        if (table == null) {
            LOGGER.warning("No loot table found for boss: " + bossName);
            return;
        }

        LOGGER.info("Loot table found. Radius: " + table.lootRadius);

        List<UUID> eligiblePlayers = new ArrayList<>();
        var playerRefs = world.getPlayerRefs();
        LOGGER.info("Total players in world: " + playerRefs.size());

        for (PlayerRef ref : playerRefs) {
            UUID playerUuid = ref.getUuid();
            Vector3d playerPos = ref.getTransform().getPosition();
            double distance = calculateDistance(playerPos, chestLocation);

            LOGGER.info("Player " + playerUuid + " at " + playerPos + ", distance: " + distance);

            if (distance <= table.lootRadius) {
                eligiblePlayers.add(playerUuid);
                LOGGER.info("  -> Player IS eligible!");
            } else {
                LOGGER.info("  -> Player too far (radius: " + table.lootRadius + ")");
            }
        }

        LOGGER.info("Total eligible players: " + eligiblePlayers.size());

        if (eligiblePlayers.isEmpty()) {
            LOGGER.info("No players nearby for loot - boss died alone");
            return;
        }

        // Generate loot for each player
        Map<UUID, List<GeneratedLoot>> allPlayerLoot = new HashMap<>();
        for (UUID playerUuid : eligiblePlayers) {
            List<GeneratedLoot> loot = generateLootForPlayer(table);
            allPlayerLoot.put(playerUuid, loot);
            LOGGER.info("Generated loot for " + playerUuid + ": " + loot.size() + " items");
        }

        Vector3d chestCopy = new Vector3d(chestLocation.x, chestLocation.y, chestLocation.z);

        // Store the loot at this location
        storeLootAtChest(chestCopy, allPlayerLoot);

        // Spawn the chest
        spawnLootChest(world, chestCopy);
    }

    // Store loot at chest location
    private static void storeLootAtChest(Vector3d location, Map<UUID, List<GeneratedLoot>> playerLoot) {
        Vector3d key = normalizeChestKey(location);
        CHEST_LOOT.put(key, playerLoot);
        LOGGER.info("Stored loot at chest location: " + key + " for " + playerLoot.size() + " players");
    }

    // Check if there's loot at a location (within 2 blocks)
    public static boolean hasLootAtLocation(Vector3d location) {
        Vector3d key = normalizeChestKey(location);
        if (CHEST_LOOT.containsKey(key)) {
            return true;
        }
        for (Vector3d chestLoc : CHEST_LOOT.keySet()) {
            double dist = chestLoc.distanceTo(location);
            if (dist < 2.0) {
                return true;
            }
        }
        return false;
    }

    // Get loot chest location near a position
    public static Vector3d getChestLocationNear(Vector3d location) {
        Vector3d key = normalizeChestKey(location);
        if (CHEST_LOOT.containsKey(key)) {
            return key;
        }
        for (Vector3d chestLoc : CHEST_LOOT.keySet()) {
            double dist = chestLoc.distanceTo(location);
            if (dist < 2.0) {
                return chestLoc;
            }
        }
        return null;
    }

    // Claim loot for a player
    public static List<GeneratedLoot> claimLoot(World world, Vector3d location, UUID playerUuid) {
        // Find the chest (within 2 blocks)
        Vector3d chestLoc = getChestLocationNear(location);

        if (chestLoc == null) {
            LOGGER.info("No chest found near " + location);
            return null;
        }

        Map<UUID, List<GeneratedLoot>> playerLoot = CHEST_LOOT.get(chestLoc);
        if (playerLoot == null) {
            LOGGER.info("No loot data at chest location " + chestLoc);
            return null;
        }

        List<GeneratedLoot> loot = playerLoot.remove(playerUuid);

        if (loot == null) {
            LOGGER.info("Player " + playerUuid + " has no loot at this chest (already claimed or not eligible)");
            return null;
        }

        LOGGER.info("Player " + playerUuid + " claimed " + loot.size() + " items from chest");

        // If no more players have loot, mark it empty (cleanup handled on chest close)
        if (playerLoot.isEmpty()) {
            LOGGER.info("All loot claimed at " + chestLoc + ", waiting for chest close to remove");
        }

        return loot;
    }

    public static void cleanupChestIfEmpty(World world, Vector3d location) {
        Vector3d chestLoc = getChestLocationNear(location);
        if (chestLoc == null) {
            return;
        }

        Map<UUID, List<GeneratedLoot>> playerLoot = CHEST_LOOT.get(chestLoc);
        if (playerLoot == null || playerLoot.isEmpty()) {
            CHEST_LOOT.remove(chestLoc);
            cancelChestExpiry(chestLoc);
            removeChestBlock(world, chestLoc);
            LOGGER.info("All loot claimed, removed chest at " + chestLoc);
        }
    }

    public static void scheduleChestExpiry(World world, Vector3d location) {
        if (world == null || location == null) {
            return;
        }

        Vector3d key = normalizeChestKey(location);
        ScheduledFuture<?> existing = CHEST_EXPIRY_TASKS.remove(key);
        if (existing != null) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = CHEST_EXPIRY_EXECUTOR.schedule(() -> {
            world.execute(() -> {
                CHEST_LOOT.remove(key);
                CHEST_EXPIRY_TASKS.remove(key);
                removeChestBlock(world, key);
                LOGGER.info("Chest expired after inactivity: " + key);
            });
        }, CHEST_EXPIRY_MS, TimeUnit.MILLISECONDS);

        CHEST_EXPIRY_TASKS.put(key, future);
    }

    // Check if player has unclaimed loot at a location
    public static boolean hasUnclaimedLoot(Vector3d location, UUID playerUuid) {
        Vector3d chestLoc = getChestLocationNear(location);
        if (chestLoc == null) {
            return false;
        }

        Map<UUID, List<GeneratedLoot>> playerLoot = CHEST_LOOT.get(chestLoc);
        if (playerLoot == null) {
            return false;
        }

        return playerLoot.containsKey(playerUuid);
    }

    // Generate loot for a single player
    private static List<GeneratedLoot> generateLootForPlayer(LootTable table) {
        List<GeneratedLoot> loot = new ArrayList<>();
        Random random = new Random();

        for (LootItem item : table.items) {  // Changed from table.loot to table.items
            double roll = random.nextDouble();  // Returns 0.0 to 1.0

            if (roll <= item.dropChance) {  // dropChance is 0.0-1.0
                int amount = item.minAmount;
                if (item.maxAmount > item.minAmount) {
                    amount = random.nextInt(item.maxAmount - item.minAmount + 1) + item.minAmount;
                }

                loot.add(new GeneratedLoot(item.itemId, amount));
                LOGGER.info("  Rolled " + roll + " <= " + item.dropChance + " -> DROP " + amount + "x " + item.itemId);
            } else {
                LOGGER.info("  Rolled " + roll + " > " + item.dropChance + " -> SKIP " + item.itemId);
            }
        }

        return loot;
    }

    // Get stored loot for a player at a location (for BossLootChestState)
    public static List<GeneratedLoot> getStoredLootForPlayer(Vector3d location, UUID playerUuid) {
        System.out.println("[BossArena] Looking for loot at location: " + location);
        System.out.println("[BossArena] Available chest locations: " + CHEST_LOOT.keySet());

        Vector3d key = normalizeChestKey(location);
        Map<UUID, List<GeneratedLoot>> chestLoot = CHEST_LOOT.get(key);

        if (chestLoot == null) {
            System.out.println("[BossArena] No chest found at exact location, searching nearby...");
            // Try to find nearby chest (in case of floating point precision issues)
            for (Vector3d chestLoc : CHEST_LOOT.keySet()) {
                if (chestLoc.distanceTo(location) < 2.0) {
                    System.out.println("[BossArena] Found nearby chest at " + chestLoc);
                    chestLoot = CHEST_LOOT.get(chestLoc);
                    break;
                }
            }
        }

        if (chestLoot == null) {
            System.out.println("[BossArena] Still no chest found!");
            return null;
        }

        List<GeneratedLoot> playerLoot = chestLoot.get(playerUuid);
        System.out.println("[BossArena] Player " + playerUuid + " has " + (playerLoot != null ? playerLoot.size() : 0) + " loot items");

        return playerLoot;
    }

    // Spawn the chest block
    private static void spawnLootChest(World world, Vector3d location) {
        world.execute(() -> {
            try {
                BlockTypeAssetMap<String, BlockType> blockTypeAssetMap = BlockType.getAssetMap();
                BlockType chestBlockType = blockTypeAssetMap.getAsset("Boss_Arena_Chest_Legendary");
                if (chestBlockType == null) {
                    LOGGER.warning("BossArena custom chest not found, falling back to Furniture_Dungeon_Chest_Legendary_Large");
                    chestBlockType = blockTypeAssetMap.getAsset("Furniture_Dungeon_Chest_Legendary_Large");
                }

                if (chestBlockType == null) {
                    LOGGER.warning("Chest block type not found!");
                    return;
                }

                int blockId = blockTypeAssetMap.getIndex(chestBlockType.getId());

                int x = (int) Math.floor(location.x);
                int y = (int) Math.floor(location.y);
                int z = (int) Math.floor(location.z);

                int chunkX = x >> 5;
                int chunkZ = z >> 5;
                WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunk(chunkX, chunkZ));

                if (chunk == null) {
                    LOGGER.warning("Chunk not loaded at chest location!");
                    return;
                }

                int localX = x & 31;
                int localZ = z & 31;

                // Spawn regular chest block
                chunk.setBlock(localX, y, localZ, blockId, chestBlockType, 0, 0, 157);

                LOGGER.info("Spawned chest block at: " + x + ", " + y + ", " + z);

                // NOW: Replace its state with our custom state!
                replaceChestState(world, x, y, z, location);

            } catch (Exception e) {
                LOGGER.severe("Error spawning boss chest: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    // New method to replace the chest's state
    private static void replaceChestState(World world, int x, int y, int z, Vector3d originalLocation) {
        try {
            // Get the current block state
            BlockState existingState = world.getState(x, y, z, true);

            LOGGER.info("Existing state type: " + (existingState != null ? existingState.getClass().getSimpleName() : "null"));

            if (existingState == null) {
                LOGGER.warning("No existing state found!");
                return;
            }

            // Create our custom state
            BossLootChestState customState = new BossLootChestState(new Vector3d(
                    originalLocation.x,
                    originalLocation.y,
                    originalLocation.z
            ));

            // Initialize it with the chest block type
            BlockType chestType = world.getBlockType(x, y, z);
            if (chestType != null) {
                customState.initialize(chestType);
            }

            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
            if (chunk == null) {
                LOGGER.warning("Chunk not loaded while replacing chest state!");
                return;
            }

            // Set the position with its chunk reference
            customState.setPosition(chunk, new Vector3i(x, y, z));

            int localX = x & 31;
            int localZ = z & 31;

            // Replace the state!
            chunk.setState(localX, y, localZ, customState, true);

            LOGGER.info("✅ Replaced chest state with BossLootChestState!");

        } catch (Exception e) {
            LOGGER.severe("Error replacing chest state: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Calculate distance between two points
    private static double calculateDistance(Vector3d pos1, Vector3d pos2) {
        double dx = pos1.x - pos2.x;
        double dy = pos1.y - pos2.y;
        double dz = pos1.z - pos2.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Vector3d normalizeChestKey(Vector3d location) {
        return new Vector3d(
                Math.floor(location.x),
                Math.floor(location.y),
                Math.floor(location.z)
        );
    }

    private static void removeChestBlock(World world, Vector3d location) {
        if (world == null) {
            LOGGER.warning("Cannot remove chest block: world is null");
            return;
        }
        world.execute(() -> {
            try {
                int x = (int) Math.floor(location.x);
                int y = (int) Math.floor(location.y);
                int z = (int) Math.floor(location.z);

                removeChestBlockAt(world, x, y, z);

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int bx = x + dx;
                        int bz = z + dz;
                        BlockType type = world.getBlockType(bx, y, bz);
                        if (isChestBlockType(type)) {
                            removeChestBlockAt(world, bx, y, bz);
                        }
                    }
                }

                LOGGER.info("Removed chest block at: " + x + ", " + y + ", " + z);
            } catch (Exception e) {
                LOGGER.severe("Error removing chest block: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private static void removeChestBlockAt(World world, int x, int y, int z) {
        WorldChunk chunk = getChunk(world, x, z);
        if (chunk == null) {
            LOGGER.warning("Chunk not loaded while removing chest block!");
            return;
        }

        int localX = x & 31;
        int localZ = z & 31;
        int filler = chunk.getFiller(localX, y, localZ);

        int originX = x - FillerBlockUtil.unpackX(filler);
        int originY = y - FillerBlockUtil.unpackY(filler);
        int originZ = z - FillerBlockUtil.unpackZ(filler);

        WorldChunk originChunk = getChunk(world, originX, originZ);
        if (originChunk == null) {
            LOGGER.warning("Origin chunk not loaded while removing chest block!");
            return;
        }

        BlockType originType = world.getBlockType(originX, originY, originZ);
        int originRotation = originChunk.getRotationIndex(originX & 31, originY, originZ & 31);

        removeSingleBlockAt(world, originX, originY, originZ);

        if (originType != null) {
            BlockBoundingBoxes boxes = BlockBoundingBoxes.getAssetMap().getAsset(originType.getHitboxTypeIndex());
            if (boxes != null) {
                var rotated = boxes.get(originRotation);
                TriIntConsumer consumer = (dx, dy, dz) -> {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        return;
                    }
                    removeSingleBlockAt(world, originX + dx, originY + dy, originZ + dz);
                };
                FillerBlockUtil.forEachFillerBlock(rotated, consumer);
            }
        }
    }

    private static boolean isChestBlockType(BlockType type) {
        if (type == null) {
            return false;
        }

        String id = type.getId();
        if ("Boss_Arena_Chest_Legendary".equals(id)
                || "Furniture_Dungeon_Chest_Legendary_Large".equals(id)) {
            return true;
        }

        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        BlockType customBase = map.getAsset("Boss_Arena_Chest_Legendary");
        if (customBase != null && customBase.getStateForBlock(type) != null) {
            return true;
        }

        BlockType vanillaBase = map.getAsset("Furniture_Dungeon_Chest_Legendary_Large");
        return vanillaBase != null && vanillaBase.getStateForBlock(type) != null;
    }

    private static WorldChunk getChunk(World world, int x, int z) {
        return world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
    }

    private static void removeSingleBlockAt(World world, int x, int y, int z) {
        WorldChunk chunk = getChunk(world, x, z);
        if (chunk == null) {
            return;
        }
        int localX = x & 31;
        int localZ = z & 31;
        int filler = chunk.getFiller(localX, y, localZ);
        chunk.breakBlock(localX, y, localZ, filler, 157);
        chunk.setState(localX, y, localZ, null, true);
    }

    private static void cancelChestExpiry(Vector3d location) {
        Vector3d key = normalizeChestKey(location);
        ScheduledFuture<?> future = CHEST_EXPIRY_TASKS.remove(key);
        if (future != null) {
            future.cancel(false);
        }
    }
}
