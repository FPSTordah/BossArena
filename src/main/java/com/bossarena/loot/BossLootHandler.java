package com.bossarena.loot;

import com.bossarena.BossArenaPlugin;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.function.consumer.TriIntConsumer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BossLootHandler {
    public static final Queue<PendingLootSpawn> PENDING_SPAWNS = new ConcurrentLinkedQueue<>();
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int PERSISTENCE_VERSION = 1;
    private static final Map<Vector3d, Map<UUID, List<GeneratedLoot>>> CHEST_LOOT = new ConcurrentHashMap<>();
    private static final Map<Vector3d, String> CHEST_WORLD = new ConcurrentHashMap<>();
    private static final Map<Vector3d, ScheduledFuture<?>> CHEST_EXPIRY_TASKS = new ConcurrentHashMap<>();
    private static final Map<Vector3d, Long> CHEST_EXPIRY_DEADLINES = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService CHEST_EXPIRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BossArena-ChestExpiry");
                t.setDaemon(true);
                return t;
            });
    private static final long CHEST_CLOSE_EXPIRY_MS = 30_000L;
    private static final long CHEST_UNTOUCHED_EXPIRY_MS = 60_000L;
    private static final Random RANDOM = new Random();
    private static volatile Path persistencePath;

    public static synchronized void initializePersistence(Path stateFilePath) {
        persistencePath = stateFilePath;
        clearRuntimeState();
        loadPersistentState();
        restorePendingExpiryTasks();
    }

    public static synchronized void flushPersistence() {
        persistState();
    }

    private static void clearRuntimeState() {
        for (ScheduledFuture<?> future : CHEST_EXPIRY_TASKS.values()) {
            if (future != null) {
                future.cancel(false);
            }
        }
        CHEST_EXPIRY_TASKS.clear();
        CHEST_EXPIRY_DEADLINES.clear();
        CHEST_WORLD.clear();
        CHEST_LOOT.clear();
    }

    // Queue a loot spawn
    public static void queueLootSpawn(World world, Vector3d location, String bossName) {
        if (world == null) {
            LOGGER.warning("Skipping loot spawn queue for '" + bossName + "' because world is null.");
            return;
        }
        if (location == null) {
            LOGGER.warning("Skipping loot spawn queue for '" + bossName + "' because location is null.");
            return;
        }
        PENDING_SPAWNS.add(new PendingLootSpawn(world, location, bossName));
        LOGGER.info("Queued loot spawn for: " + bossName + " at " + location);
    }

    // Main handler called from LootSpawnSystem
    public static void handleBossDeath(World world, Vector3d chestLocation, String bossName) {
        LOGGER.info("=== BOSS LOOT DEBUG ===");
        LOGGER.info("Boss: " + bossName + " died at: " + chestLocation);
        if (world == null) {
            LOGGER.warning("Cannot handle boss loot for '" + bossName + "': world is null.");
            return;
        }
        if (chestLocation == null) {
            LOGGER.warning("Cannot handle boss loot for '" + bossName + "': chest location is null.");
            return;
        }

        LootTable table = LootRegistry.get(bossName);
        if (table == null) {
            LOGGER.warning("No loot table found for boss: " + bossName);
            return;
        }

        LOGGER.info("Loot table found. Radius: " + table.lootRadius);
        boolean hasItems = table.items != null && !table.items.isEmpty();
        boolean hasCommands = table.commands != null && !table.commands.isEmpty();

        if (!hasItems && !hasCommands) {
            LOGGER.warning("Loot table has no items or commands for boss: " + bossName);
            return;
        }

        List<PlayerRef> eligiblePlayers = new ArrayList<>();
        var playerRefs = world.getPlayerRefs();
        LOGGER.info("Total players in world: " + playerRefs.size());

        for (PlayerRef ref : playerRefs) {
            UUID playerUuid = ref.getUuid();
            Transform playerTransform = ref.getTransform();
            if (playerTransform == null) {
                continue;
            }
            Vector3d playerPos = playerTransform.getPosition();
            if (playerPos == null) {
                continue;
            }
            double distance = calculateDistance(playerPos, chestLocation);

            LOGGER.info("Player " + playerUuid + " at " + playerPos + ", distance: " + distance);

            if (distance <= table.lootRadius) {
                eligiblePlayers.add(ref);
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

        // Execute commands if any
        if (hasCommands) {
            for (PlayerRef player : eligiblePlayers) {
                for (String cmd : table.commands) {
                    executeConsoleCommand(player, cmd, bossName);
                }
            }
        }

        if (!hasItems) {
            LOGGER.info("No items in loot table, skipping chest spawn.");
            return;
        }

        // Generate loot for each player
        Map<UUID, List<GeneratedLoot>> allPlayerLoot = new HashMap<>();
        for (PlayerRef player : eligiblePlayers) {
            List<GeneratedLoot> playerLoot = new ArrayList<>();
            for (LootItem item : table.items) {
                if (item == null || item.itemId == null) continue;
                if (RANDOM.nextDouble() <= item.dropChance) {
                    if (item.itemId.startsWith("cmd:")) {
                        // Special case: itemId is a console command
                        String cmd = item.itemId.substring(4).trim();
                        executeConsoleCommand(player, cmd, bossName);
                    } else {
                        // Regular item loot
                        int amount = item.minAmount + (item.maxAmount > item.minAmount
                                ? RANDOM.nextInt(item.maxAmount - item.minAmount + 1)
                                : 0);
                        playerLoot.add(new GeneratedLoot(item.itemId, amount));
                        LOGGER.info("  Added item loot for " + player.getUuid() + ": " + amount + "x " + item.itemId);
                    }
                }
            }
            allPlayerLoot.put(player.getUuid(), playerLoot);
            LOGGER.info("Generated " + playerLoot.size() + " items for " + player.getUuid());
        }

        Vector3d chestCopy = normalizeChestKey(chestLocation);
        LOGGER.info("Using boss event center block for chest spawn: " + chestCopy + " (requested: " + chestLocation + ")");

        // Store the loot at this location
        storeLootAtChest(world, chestCopy, allPlayerLoot);

        // Spawn the chest
        spawnLootChest(world, chestCopy);
        // Hard timeout while untouched: chest is removed if nobody opens it within 60s.
        scheduleUntouchedChestExpiry(world, chestCopy);
    }

    private static void executeConsoleCommand(PlayerRef player, String cmd, String bossName) {
        if (cmd == null || cmd.trim().isEmpty()) return;
        CommandManager cm = CommandManager.get();
        if (cm == null) {
            LOGGER.warning("CommandManager is null, cannot execute command: " + cmd);
            return;
        }

        String finalCmd = cmd
                .replace("{player}", player.getUsername())
                .replace("$Player", player.getUsername())
                .replace("{player_uuid}", player.getUuid().toString())
                .replace("{boss_name}", bossName);
        // Ensure command doesn't start with /
        if (finalCmd.startsWith("/")) {
            finalCmd = finalCmd.substring(1);
        }
        try {
            cm.handleCommand(ConsoleSender.INSTANCE, finalCmd);
            LOGGER.info("Executed console command for player " + player.getUsername() + ": " + finalCmd);
        } catch (Exception e) {
            LOGGER.warning("Failed to execute console command: " + finalCmd + " Error: " + e.getMessage());
        }
    }

    // Store loot at chest location
    private static void storeLootAtChest(World world, Vector3d location, Map<UUID, List<GeneratedLoot>> playerLoot) {
        Vector3d key = normalizeChestKey(location);
        CHEST_LOOT.put(key, new ConcurrentHashMap<>(playerLoot));
        if (world != null) {
            CHEST_WORLD.put(key, world.getName());
        }
        CHEST_EXPIRY_DEADLINES.remove(key);
        LOGGER.info("Stored loot at chest location: " + key + " for " + playerLoot.size() + " players");
        persistStateSafe();
    }

    // Check if there's loot at a location (within 2 blocks)
    public static boolean hasLootAtLocation(Vector3d location) {
        return hasLootAtLocation(null, location);
    }

    public static boolean hasLootAtLocation(World world, Vector3d location) {
        Vector3d key = normalizeChestKey(location);
        if (CHEST_LOOT.containsKey(key) && isWorldMatch(world, key)) {
            return true;
        }
        for (Vector3d chestLoc : CHEST_LOOT.keySet()) {
            double dist = chestLoc.distanceTo(location);
            if (dist < 2.0 && isWorldMatch(world, chestLoc)) {
                return true;
            }
        }
        return false;
    }

    // Get loot chest location near a position
    public static Vector3d getChestLocationNear(Vector3d location) {
        return getChestLocationNear(null, location);
    }

    public static Vector3d getChestLocationNear(World world, Vector3d location) {
        Vector3d key = normalizeChestKey(location);
        if (CHEST_LOOT.containsKey(key) && isWorldMatch(world, key)) {
            return key;
        }
        for (Vector3d chestLoc : CHEST_LOOT.keySet()) {
            double dist = chestLoc.distanceTo(location);
            if (dist < 2.0 && isWorldMatch(world, chestLoc)) {
                return chestLoc;
            }
        }
        return null;
    }

    // Claim loot for a player
    public static List<GeneratedLoot> claimLoot(World world, Vector3d location, UUID playerUuid) {
        // Find the chest (within 2 blocks)
        Vector3d chestLoc = getChestLocationNear(world, location);

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

        persistStateSafe();
        return loot;
    }

    public static void cleanupChestIfEmpty(World world, Vector3d location) {
        Vector3d chestLoc = getChestLocationNear(world, location);
        if (chestLoc == null) {
            return;
        }

        Map<UUID, List<GeneratedLoot>> playerLoot = CHEST_LOOT.get(chestLoc);
        if (playerLoot == null || playerLoot.isEmpty()) {
            // Keep the block alive until expiry after the last window closes.
            LOGGER.info("All loot claimed at " + chestLoc + ", waiting for close-expiry cleanup.");
            persistStateSafe();
        }
    }

    public static void pauseChestExpiry(World world, Vector3d location) {
        if (location == null) {
            return;
        }
        Vector3d key = normalizeChestKey(location);
        if (world != null && !isWorldMatch(world, key)) {
            return;
        }
        cancelChestExpiry(key, true);
    }

    public static void scheduleChestExpiry(World world, Vector3d location) {
        if (world == null || location == null) {
            return;
        }

        Vector3d key = normalizeChestKey(location);
        if (!isWorldMatch(world, key)) {
            return;
        }
        long expiresAtEpochMs = System.currentTimeMillis() + CHEST_CLOSE_EXPIRY_MS;
        scheduleChestExpiryInternal(world, key, CHEST_CLOSE_EXPIRY_MS, expiresAtEpochMs, true);
    }

    public static void scheduleUntouchedChestExpiry(World world, Vector3d location) {
        if (world == null || location == null) {
            return;
        }

        Vector3d key = normalizeChestKey(location);
        if (!isWorldMatch(world, key)) {
            return;
        }
        long expiresAtEpochMs = System.currentTimeMillis() + CHEST_UNTOUCHED_EXPIRY_MS;
        scheduleChestExpiryInternal(world, key, CHEST_UNTOUCHED_EXPIRY_MS, expiresAtEpochMs, true);
    }

    // Check if player has unclaimed loot at a location
    public static boolean hasUnclaimedLoot(Vector3d location, UUID playerUuid) {
        return hasUnclaimedLoot(null, location, playerUuid);
    }

    public static boolean hasUnclaimedLoot(World world, Vector3d location, UUID playerUuid) {
        Vector3d chestLoc = getChestLocationNear(world, location);
        if (chestLoc == null) {
            return false;
        }

        Map<UUID, List<GeneratedLoot>> playerLoot = CHEST_LOOT.get(chestLoc);
        if (playerLoot == null) {
            return false;
        }

        return playerLoot.containsKey(playerUuid);
    }


    // Get stored loot for a player at a location (for BossLootChestState)
    public static List<GeneratedLoot> getStoredLootForPlayer(Vector3d location, UUID playerUuid) {
        return getStoredLootForPlayer(null, location, playerUuid);
    }

    public static List<GeneratedLoot> getStoredLootForPlayer(World world, Vector3d location, UUID playerUuid) {
        Vector3d key = normalizeChestKey(location);
        Map<UUID, List<GeneratedLoot>> chestLoot = CHEST_LOOT.get(key);

        if (chestLoot == null || !isWorldMatch(world, key)) {
            chestLoot = null;
            // Try to find nearby chest (in case of floating point precision issues)
            for (Vector3d chestLoc : CHEST_LOOT.keySet()) {
                if (chestLoc.distanceTo(location) < 2.0 && isWorldMatch(world, chestLoc)) {
                    chestLoot = CHEST_LOOT.get(chestLoc);
                    break;
                }
            }
        }

        if (chestLoot == null) {
            return null;
        }

        return chestLoot.get(playerUuid);
    }

    // Spawn the chest block
    private static void spawnLootChest(World world, Vector3d location) {
        world.execute(() -> {
            try {
                BlockTypeAssetMap<String, BlockType> blockTypeAssetMap = BlockType.getAssetMap();
                BlockType chestBlockType = findBlockType(blockTypeAssetMap, "Boss_Arena_Chest_Legendary");
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
                LOGGER.log(Level.SEVERE, "Error spawning boss chest", e);
            }
        });
    }

    private static Vector3d resolveChestSpawnLocation(World world, Vector3d desiredLocation) {
        if (world == null || desiredLocation == null) {
            return desiredLocation;
        }

        int x = (int) Math.floor(desiredLocation.x);
        int z = (int) Math.floor(desiredLocation.z);
        int baseY = (int) Math.floor(desiredLocation.y);
        int maxOffset = 24;

        // Choose the nearest valid surface to the requested Y, preferring at/below first.
        for (int offset = 0; offset <= maxOffset; offset++) {
            int supportYBelow = baseY - offset;
            if (supportYBelow >= 1 && isValidChestSupport(world, x, supportYBelow, z)) {
                return new Vector3d(x, supportYBelow + 1, z);
            }

            if (offset == 0) {
                continue;
            }
            int supportYAbove = baseY + offset;
            if (isValidChestSupport(world, x, supportYAbove, z)) {
                return new Vector3d(x, supportYAbove + 1, z);
            }
        }

        int fallbackY = Math.max(1, baseY);
        for (int attempt = 0; attempt < 8; attempt++) {
            if (isReplaceableBlock(world.getBlockType(x, fallbackY, z))) {
                break;
            }
            fallbackY++;
        }
        return new Vector3d(x, fallbackY, z);
    }

    private static boolean isValidChestSupport(World world, int x, int supportY, int z) {
        if (!isSolidSupportBlock(world.getBlockType(x, supportY, z))) {
            return false;
        }
        if (!isReplaceableBlock(world.getBlockType(x, supportY + 1, z))) {
            return false;
        }
        // Most chest prefabs need headroom; avoid clipping into overhead blocks.
        return isReplaceableBlock(world.getBlockType(x, supportY + 2, z));
    }

    private static boolean isSolidSupportBlock(BlockType type) {
        if (type == null) {
            return false;
        }
        return !isReplaceableBlock(type);
    }

    private static boolean isReplaceableBlock(BlockType type) {
        if (type == null) {
            return true;
        }

        String id = type.getId();
        if (id == null || id.isBlank()) {
            return true;
        }

        String normalized = id.toLowerCase(Locale.ROOT);
        return normalized.contains("air")
                || normalized.contains("snow_layer")
                || normalized.contains("tallgrass")
                || normalized.contains("tall_grass")
                || normalized.contains("short_grass")
                || normalized.contains("grass_plant")
                || normalized.contains("flower")
                || normalized.contains("fern")
                || normalized.contains("leaf")
                || normalized.contains("vine")
                || normalized.contains("water")
                || normalized.contains("lava");
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
            LOGGER.log(Level.SEVERE, "Error replacing chest state", e);
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

    private static boolean isWorldMatch(World world, Vector3d chestLoc) {
        if (world == null) {
            return true;
        }
        String expectedWorld = CHEST_WORLD.get(chestLoc);
        return expectedWorld == null || expectedWorld.equalsIgnoreCase(world.getName());
    }

    private static World resolveWorldForChest(Vector3d chestLoc) {
        String worldName = CHEST_WORLD.get(chestLoc);
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        return universe.getWorld(worldName);
    }

    public static void restorePendingExpiryTasks() {
        long now = System.currentTimeMillis();
        List<Map.Entry<Vector3d, Long>> pending = new ArrayList<>(CHEST_EXPIRY_DEADLINES.entrySet());
        for (Map.Entry<Vector3d, Long> entry : pending) {
            Vector3d key = entry.getKey();
            Long expiresAt = entry.getValue();
            if (key == null || expiresAt == null || CHEST_EXPIRY_TASKS.containsKey(key)) {
                continue;
            }

            World world = resolveWorldForChest(key);
            if (world == null) {
                continue;
            }

            long delay = Math.max(0L, expiresAt - now);
            scheduleChestExpiryInternal(world, key, delay, expiresAt, false);
        }
    }

    private static void scheduleChestExpiryInternal(World world,
                                                    Vector3d key,
                                                    long delayMs,
                                                    long expiresAtEpochMs,
                                                    boolean persist) {
        if (world == null || key == null) {
            return;
        }

        cancelChestExpiry(key, false);
        CHEST_WORLD.putIfAbsent(key, world.getName());
        CHEST_EXPIRY_DEADLINES.put(key, expiresAtEpochMs);

        ScheduledFuture<?> future = CHEST_EXPIRY_EXECUTOR.schedule(
                () -> expireChestOnWorldThread(world, key),
                Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS
        );
        CHEST_EXPIRY_TASKS.put(key, future);

        if (persist) {
            persistStateSafe();
        }
    }

    private static void expireChestOnWorldThread(World world, Vector3d key) {
        world.execute(() -> {
            CHEST_LOOT.remove(key);
            CHEST_WORLD.remove(key);
            CHEST_EXPIRY_DEADLINES.remove(key);
            CHEST_EXPIRY_TASKS.remove(key);
            removeChestBlock(world, key);
            LOGGER.info("Chest expired after inactivity: " + key);
            persistStateSafe();
        });
    }

    public static void cleanupAllChests(World world) {
        if (world == null) return;
        String worldName = world.getName();
        List<Vector3d> toRemove = new ArrayList<>();
        for (Map.Entry<Vector3d, String> entry : CHEST_WORLD.entrySet()) {
            if (worldName.equals(entry.getValue())) {
                toRemove.add(entry.getKey());
            }
        }
        for (Vector3d loc : toRemove) {
            CHEST_LOOT.remove(loc);
            CHEST_WORLD.remove(loc);
            cancelChestExpiry(loc, false);
            removeChestBlockDirectly(world, loc);
        }
        persistStateSafe();
    }

    private static void removeChestBlock(World world, Vector3d location) {
        if (world == null) {
            LOGGER.warning("Cannot remove chest block: world is null");
            return;
        }
        world.execute(() -> removeChestBlockDirectly(world, location));
    }

    private static void removeChestBlockDirectly(World world, Vector3d location) {
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
            LOGGER.log(Level.SEVERE, "Error removing chest block", e);
        }
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
        if (matchesAssetId(id, "Boss_Arena_Chest_Legendary")
                || "Furniture_Dungeon_Chest_Legendary_Large".equals(id)) {
            return true;
        }

        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        BlockType customBase = findBlockType(map, "Boss_Arena_Chest_Legendary");
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
        cancelChestExpiry(location, true);
    }

    private static void cancelChestExpiry(Vector3d location, boolean persist) {
        Vector3d key = normalizeChestKey(location);
        ScheduledFuture<?> future = CHEST_EXPIRY_TASKS.remove(key);
        if (future != null) {
            future.cancel(false);
        }
        CHEST_EXPIRY_DEADLINES.remove(key);
        if (persist) {
            persistStateSafe();
        }
    }

    private static synchronized void loadPersistentState() {
        if (persistencePath == null || !Files.exists(persistencePath)) {
            return;
        }

        try {
            String json = Files.readString(persistencePath, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }

            PersistedLootState state = GSON.fromJson(json, PersistedLootState.class);
            if (state == null || state.chests == null || state.chests.isEmpty()) {
                return;
            }

            long now = System.currentTimeMillis();
            int restored = 0;

            for (PersistedChest persistedChest : state.chests) {
                if (persistedChest == null) {
                    continue;
                }

                Vector3d key = normalizeChestKey(new Vector3d(persistedChest.x, persistedChest.y, persistedChest.z));
                Map<UUID, List<GeneratedLoot>> playerLoot = new ConcurrentHashMap<>();

                if (persistedChest.players != null) {
                    for (PersistedPlayerLoot player : persistedChest.players) {
                        if (player == null || player.playerUuid == null || player.playerUuid.isBlank()) {
                            continue;
                        }

                        UUID playerUuid;
                        try {
                            playerUuid = UUID.fromString(player.playerUuid.trim());
                        } catch (IllegalArgumentException ignored) {
                            continue;
                        }

                        List<GeneratedLoot> loot = new ArrayList<>();
                        if (player.loot != null) {
                            for (PersistedLootItem item : player.loot) {
                                if (item == null || item.itemId == null || item.itemId.isBlank() || item.amount <= 0) {
                                    continue;
                                }
                                loot.add(new GeneratedLoot(item.itemId, item.amount));
                            }
                        }

                        if (!loot.isEmpty()) {
                            playerLoot.put(playerUuid, loot);
                        }
                    }
                }

                if (playerLoot.isEmpty()) {
                    continue;
                }

                CHEST_LOOT.put(key, playerLoot);
                restored++;

                if (persistedChest.world != null && !persistedChest.world.isBlank()) {
                    CHEST_WORLD.put(key, persistedChest.world.trim());
                }

                if (persistedChest.expiresAtEpochMs != null) {
                    long expiresAt = persistedChest.expiresAtEpochMs;
                    if (expiresAt <= now) {
                        World world = resolveWorldForChest(key);
                        if (world != null) {
                            scheduleChestExpiryInternal(world, key, 0L, now, false);
                        } else {
                            CHEST_LOOT.remove(key);
                            CHEST_WORLD.remove(key);
                            CHEST_EXPIRY_DEADLINES.remove(key);
                        }
                    } else {
                        CHEST_EXPIRY_DEADLINES.put(key, expiresAt);
                    }
                }
            }

            if (restored > 0) {
                LOGGER.info("Restored " + restored + " pending loot chest(s) from disk");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load persisted loot chest state", e);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Invalid persisted loot chest state, skipping restore", e);
        }
    }

    private static synchronized void persistState() {
        if (persistencePath == null) {
            return;
        }

        PersistedLootState state = new PersistedLootState();

        for (Map.Entry<Vector3d, Map<UUID, List<GeneratedLoot>>> entry : CHEST_LOOT.entrySet()) {
            Vector3d key = entry.getKey();
            Map<UUID, List<GeneratedLoot>> byPlayer = entry.getValue();

            if (key == null || byPlayer == null || byPlayer.isEmpty()) {
                continue;
            }

            PersistedChest chest = new PersistedChest();
            chest.world = CHEST_WORLD.get(key);
            chest.x = Math.floor(key.x);
            chest.y = Math.floor(key.y);
            chest.z = Math.floor(key.z);
            chest.expiresAtEpochMs = CHEST_EXPIRY_DEADLINES.get(key);

            for (Map.Entry<UUID, List<GeneratedLoot>> playerEntry : byPlayer.entrySet()) {
                UUID playerUuid = playerEntry.getKey();
                List<GeneratedLoot> lootList = playerEntry.getValue();
                if (playerUuid == null || lootList == null || lootList.isEmpty()) {
                    continue;
                }

                PersistedPlayerLoot persistedPlayerLoot = new PersistedPlayerLoot();
                persistedPlayerLoot.playerUuid = playerUuid.toString();

                for (GeneratedLoot loot : lootList) {
                    if (loot == null || loot.itemId == null || loot.itemId.isBlank() || loot.amount <= 0) {
                        continue;
                    }
                    PersistedLootItem persistedLootItem = new PersistedLootItem();
                    persistedLootItem.itemId = loot.itemId;
                    persistedLootItem.amount = loot.amount;
                    persistedPlayerLoot.loot.add(persistedLootItem);
                }

                if (!persistedPlayerLoot.loot.isEmpty()) {
                    chest.players.add(persistedPlayerLoot);
                }
            }

            if (!chest.players.isEmpty()) {
                state.chests.add(chest);
            }
        }

        try {
            if (state.chests.isEmpty()) {
                Files.deleteIfExists(persistencePath);
                return;
            }

            Path parent = persistencePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = GSON.toJson(state);
            Files.writeString(persistencePath, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to persist loot chest state", e);
        }
    }

    private static void persistStateSafe() {
        try {
            persistState();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unexpected error while persisting loot chest state", e);
        }
    }

    private static BlockType findBlockType(BlockTypeAssetMap<String, BlockType> map, String baseId) {
        BlockType direct = map.getAsset(baseId);
        if (direct != null) {
            return direct;
        }
        return map.getAsset(BossArenaPlugin.ASSET_PACK_ID + ":" + baseId);
    }

    private static boolean matchesAssetId(String currentId, String baseId) {
        if (currentId == null || baseId == null) {
            return false;
        }
        if (currentId.equalsIgnoreCase(baseId)) {
            return true;
        }
        return currentId.equalsIgnoreCase(BossArenaPlugin.ASSET_PACK_ID + ":" + baseId);
    }

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

    private static final class PersistedLootState {
        int version = PERSISTENCE_VERSION;
        List<PersistedChest> chests = new ArrayList<>();
    }

    private static final class PersistedChest {
        String world;
        double x;
        double y;
        double z;
        Long expiresAtEpochMs;
        List<PersistedPlayerLoot> players = new ArrayList<>();
    }

    private static final class PersistedPlayerLoot {
        String playerUuid;
        List<PersistedLootItem> loot = new ArrayList<>();
    }

    private static final class PersistedLootItem {
        String itemId;
        int amount;
    }
}
