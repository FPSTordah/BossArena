package com.bossarena;

import com.bossarena.data.Arena;
import com.bossarena.data.ArenaRegistry;
import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.bossarena.command.BossArenaCommand;
import com.bossarena.command.BossArenaShortCommand;
import com.bossarena.spawn.BossSpawnService;
import com.bossarena.system.BossTrackingSystem;
import com.bossarena.system.BossDeathSystem;
import com.bossarena.system.BossEventNotificationSystem;
import com.bossarena.system.LootSpawnSystem;
import com.bossarena.system.RPGLevelingBossScaleCompatSystem;
import com.bossarena.loot.LootRegistry;
import com.bossarena.loot.BossLootHandler;
import com.bossarena.loot.BossLootChestState;
import com.bossarena.loot.OpenBossChestInteraction;
import com.bossarena.shop.BossShopConfig;
import com.bossarena.shop.BossArenaShopPage;
import com.bossarena.shop.OpenBossShopNpcInteraction;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityUseBlockEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.BlockStateModule;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BossArenaPlugin extends JavaPlugin {
    private static BossArenaPlugin INSTANCE;
    public static final String ASSET_PACK_ID = "com.bossarena:BossArena";
    public static final String NO_DEATH_DROPS_INTERACTION_ID = "BossArena_NoDeathDrops";
    public static final String SHOP_OPEN_INTERACTION_ID = "BossArena_OpenShopNpc";
    public static final String SHOP_NPC_TYPE_ID = "bossarena_shop_guard";
    public static final PluginIdentifier RPG_LEVELING_PLUGIN_ID = new PluginIdentifier("Zuxaw", "RPGLeveling");
    private static final Path MOD_ROOT = Path.of("mods", "BossArena");
    private static final ScheduledExecutorService SHOP_REBIND_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BossArena-ShopRebind");
                t.setDaemon(true);
                return t;
            });

    private BossTrackingSystem trackingSystem;
    private BossArenaConfig config = new BossArenaConfig();
    private BossShopConfig shopConfig = new BossShopConfig();
    private BossSpawnService bossSpawnService;
    private Path bossesJsonPath;
    private Path arenasJsonPath;
    private Path lootTablesPath;
    private Path lootChestStatePath;
    private Path shopJsonPath;

    public BossArenaPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
        INSTANCE = this;
        getLogger().atInfo().log("BossArena setup() called");

        registerCustomCodecs();
        registerCustomInteractions();
        registerAssetPack();
        registerCustomBlocks();

        Path modRoot = getModRootDirectory();
        this.bossesJsonPath = modRoot.resolve("bosses.json");
        this.arenasJsonPath = modRoot.resolve("arenas.json");
        this.lootTablesPath = modRoot.resolve("loot_tables.json");
        this.lootChestStatePath = modRoot.resolve("loot_chests_state.json");
        this.shopJsonPath = modRoot.resolve("shop.json");

        // Create tracking system
        this.trackingSystem = new BossTrackingSystem();

        // Register ECS systems
        this.getEntityStoreRegistry().registerSystem(new LootSpawnSystem());
        this.getEntityStoreRegistry().registerSystem(new BossDeathSystem(trackingSystem));
        this.getEntityStoreRegistry().registerSystem(new BossEventNotificationSystem(trackingSystem));
        this.getEntityStoreRegistry().registerSystem(new RPGLevelingBossScaleCompatSystem(trackingSystem));
        getLogger().atInfo().log("Registered BossArena HP scale compatibility system "
                + "(activates only when RPGLeveling is loaded)");
        getLogger().atInfo().log("Successfully registered boss systems");

        // Register chest interaction event
        this.getEventRegistry().registerGlobal(
                LivingEntityUseBlockEvent.class,
                this::onBlockInteract
        );
        getLogger().atInfo().log("Registered chest interaction listener");
        this.getEventRegistry().registerGlobal(
                PlayerInteractEvent.class,
                this::onPlayerInteract
        );
        getLogger().atInfo().log("Registered player interaction listener");
        this.getEventRegistry().registerGlobal(
                AddPlayerToWorldEvent.class,
                this::onAddPlayerToWorld
        );
        getLogger().atInfo().log("Registered world-entry listener");

        config.load();
        shopConfig.load(shopJsonPath);
        if (shopConfig.applyRuntimeCurrencyDetection(resolveFallbackCurrencyItemId())) {
            saveShopConfig();
            getLogger().atInfo().log("Detected currency provider at startup: " + shopConfig.currencyProvider
                    + " (item fallback: " + shopConfig.currencyItemId + ")");
        }

        // Register commands
        try {
            var cm = CommandManager.get();
            if (cm != null) {
                cm.register(new BossArenaCommand(this));
                cm.register(new BossArenaShortCommand(this));
                getLogger().atInfo().log("BossArena commands registered successfully");
            } else {
                getLogger().atWarning().log("CommandManager is null, commands not registered!");
            }
        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("Failed to register BossArena commands");
        }

        this.bossSpawnService = new BossSpawnService(trackingSystem, config);

        // Async startup
        CompletableFuture.runAsync(() -> {
            while (com.hypixel.hytale.server.core.universe.Universe.get() == null) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
            }
            startBossArenaSystems();
        });
    }

    private void registerCustomBlocks() {
        try {
            getLogger().atInfo().log("Registering Boss Arena custom blocks...");

            // Note: Block registration might need to happen during asset loading phase
            // We may need to register the BlockState codec first

        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("Failed to register custom blocks");
        }
    }

    private void registerCustomInteractions() {
        try {
            getLogger().atInfo().log("Registering Boss Arena custom interactions...");

            RootInteraction rootInteraction = new RootInteraction("BossArena_OpenChest", "BossArena_OpenChest");
            RootInteraction.getAssetStore().loadAssets(ASSET_PACK_ID, List.of(rootInteraction));
            getLogger().atInfo().log("Registered RootInteraction: BossArena_OpenChest");

            RootInteraction noDeathDropsInteraction = new RootInteraction(
                    NO_DEATH_DROPS_INTERACTION_ID,
                    NO_DEATH_DROPS_INTERACTION_ID
            );
            RootInteraction.getAssetStore().loadAssets(ASSET_PACK_ID, List.of(noDeathDropsInteraction));
            getLogger().atInfo().log("Registered RootInteraction: " + NO_DEATH_DROPS_INTERACTION_ID);

            RootInteraction shopOpenInteraction = new RootInteraction(
                    SHOP_OPEN_INTERACTION_ID,
                    SHOP_OPEN_INTERACTION_ID
            );
            RootInteraction.getAssetStore().loadAssets(ASSET_PACK_ID, List.of(shopOpenInteraction));
            getLogger().atInfo().log("Registered RootInteraction: " + SHOP_OPEN_INTERACTION_ID);

            Interaction.getAssetStore().loadAssets(ASSET_PACK_ID, List.of(
                    new OpenBossChestInteraction(),
                    new OpenBossShopNpcInteraction(),
                    new SimpleInteraction(NO_DEATH_DROPS_INTERACTION_ID)
            ));
            getLogger().atInfo().log("Registered interaction assets for BossArena_OpenChest, "
                    + SHOP_OPEN_INTERACTION_ID + ", and " + NO_DEATH_DROPS_INTERACTION_ID);

        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("Failed to register custom interactions");
        }
    }

    private void registerCustomCodecs() {
        try {
            getLogger().atInfo().log("Registering Boss Arena custom block state and interaction...");

            // Register BossLootChestState codec with BlockState system
            // The string "BossLootChestState" must match the "type" in the JSON
            BlockStateModule.get().registerBlockState(
                    BossLootChestState.class,
                    "BossLootChestState",
                    BossLootChestState.CODEC
            );

            // Register OpenBossChestInteraction codec with Interaction system
            // The string must match the interaction ID in the JSON
            Interaction.CODEC.register(
                    "BossArena_OpenChest",
                    OpenBossChestInteraction.class,
                    OpenBossChestInteraction.CODEC
            );
            Interaction.CODEC.register(
                    SHOP_OPEN_INTERACTION_ID,
                    OpenBossShopNpcInteraction.class,
                    OpenBossShopNpcInteraction.CODEC
            );

            getLogger().atInfo().log("✅ Successfully registered custom codecs");

        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("❌ Failed to register custom codecs");
        }
    }

    private void registerAssetPack() {
        try {
            Path modRoot = getModRootDirectory();
            // Prevent mods/BossArena from being auto-detected as a separate external pack on restart.
            // The canonical asset pack is the mod JAR (IncludesAssetPack=true).
            Files.deleteIfExists(modRoot.resolve("manifest.json"));
            migrateLegacyDataDirectory(modRoot);
            Path assetsRoot = modRoot.resolve("assets");
            extractAssets(assetsRoot);

            AssetModule assetModule = AssetModule.get();
            if (assetModule != null
                    && assetModule.getAssetPack(ASSET_PACK_ID) == null
                    && assetModule.getAssetPack("BossArena") == null) {
                assetModule.registerPack(ASSET_PACK_ID, assetsRoot, getManifest(), true);
                assetModule.initPendingStores();
                getLogger().atInfo().log("Registered BossArena asset pack at " + assetsRoot);
            }

            BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
            if (findBlockType(blockTypeMap, "Boss_Arena_Chest_Legendary") != null) {
                getLogger().atInfo().log("BossArena custom chest block found in asset map");
            } else {
                getLogger().atWarning().log("BossArena custom chest block still missing from asset map");
            }

        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("Failed to register BossArena asset pack");
        }
    }

    private void extractAssets(Path assetsRoot) throws IOException {
        Path serverRoot = assetsRoot.resolve("Server");
        String modelPath = safeAssetPath(
                ModelAsset.getAssetStore() != null ? ModelAsset.getAssetStore().getPath() : ""
        );
        String itemPath = defaultAssetPath(
                Item.getAssetStore() != null ? Item.getAssetStore().getPath() : "",
                "Item/Items"
        );
        Path modelDir = modelPath.isEmpty() ? serverRoot : serverRoot.resolve(trimServerPrefix(modelPath));
        Path itemDir = itemPath.isEmpty() ? serverRoot : serverRoot.resolve(trimServerPrefix(itemPath));

        copyPackResource(assetsRoot, "manifest.json");

        copyPackResource(assetsRoot, "Common/UI/Custom/Pages/BossArenaShopPage.ui");
        copyPackResource(assetsRoot, "Common/UI/Custom/Pages/BossArenaShopElementButton.ui");
        copyPackResource(assetsRoot, "Common/UI/Custom/Pages/BossArenaConfigPage.ui");

        copyPackResource(assetsRoot, "Common/Blocks/Boss_Arena_Chest_Legendary.blockymodel");
        copyPackResource(assetsRoot, "Common/Blocks/Boss_Arena_Chest_Legendary_Texture.png");
        copyPackResource(assetsRoot, "Blocks/Boss_Arena_Chest_Legendary.blockymodel");
        copyPackResource(assetsRoot, "Blocks/Boss_Arena_Chest_Legendary_Texture.png");
        copyPackResource(assetsRoot, "Server/Item/Items/Boss_Arena_Chest_Legendary.json");
        copyPackResource(assetsRoot, "Server/Item/Items/Boss_Arena_Chest_Legendary.blockymodel");
        copyPackResource(assetsRoot, "Server/NPC/Roles/bossarena_shop_guard.json");

        copyPackResource(assetsRoot, "Server/Textures/Boss_Arena_Chest_Legendary_Texture.png");

        copyPackResource(assetsRoot, "Server/Icons/ItemsGenerated/boss_arena_shop_icon.png");

        // Shop item icons are intentionally disabled for now.

        // Mirror key assets into runtime asset-store directories for compatibility.
        copyResource("Server/Item/Items/Boss_Arena_Chest_Legendary.blockymodel", modelDir.resolve("Boss_Arena_Chest_Legendary.blockymodel"));
        // Legacy compatibility copies for pre-Update 3 item path assumptions.
        copyResource("Server/Item/Items/Boss_Arena_Chest_Legendary.json", assetsRoot.resolve("Server/Items/Boss_Arena_Chest_Legendary.json"));

        // Texture compatibility copies for model lookup differences.
        copyResource("Blocks/Boss_Arena_Chest_Legendary.blockymodel", assetsRoot.resolve("Blocks/Boss_Arena_Chest_Legendary.blockymodel"));
        copyResource("Blocks/Boss_Arena_Chest_Legendary_Texture.png", assetsRoot.resolve("Blocks/Boss_Arena_Chest_Legendary_Texture.png"));
        copyResource("Server/Textures/Boss_Arena_Chest_Legendary_Texture.png", modelDir.resolve("Boss_Arena_Chest_Legendary_Texture.png"));

        // Clean up legacy pedestal files if they exist from prior versions.
        Files.deleteIfExists(assetsRoot.resolve("Server/Item/Items/boss_arena_shop_pedestal.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Item/Items/Boss_Arena_Shop.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Item/Items/Boss_Pedestal.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Item/RootInteractions/Block/BossArena_OpenShop.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Item/Interactions/Block/BossArena_OpenShop_Simple.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Items/Boss_Arena_Shop.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Items/Boss_Pedestal.json"));
        Files.deleteIfExists(assetsRoot.resolve("Blocks/Boss_Shop.blockymodel"));
        Files.deleteIfExists(assetsRoot.resolve("Blocks/boss_arena_shop_texture.png"));
        Files.deleteIfExists(assetsRoot.resolve("Common/Blocks/Boss_Shop.blockymodel"));
        Files.deleteIfExists(assetsRoot.resolve("Common/Blocks/boss_arena_shop_texture.png"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Textures/boss_arena_shop_texture.png"));
        Files.deleteIfExists(itemDir.resolve("Boss_Arena_Shop.json"));
        Files.deleteIfExists(itemDir.resolve("Boss_Pedestal.json"));
        Files.deleteIfExists(modelDir.resolve("Boss_Shop.blockymodel"));
        Files.deleteIfExists(modelDir.resolve("boss_arena_shop_texture.png"));
        purgeLegacyShopArtifacts(assetsRoot);
    }

    private void purgeLegacyShopArtifacts(Path assetsRoot) throws IOException {
        Files.walkFileTree(assetsRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (isLegacyShopArtifact(fileName)) {
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isLegacyShopArtifact(String fileName) {
        return fileName.contains("pedestal")
                || fileName.equals("boss_arena_shop.json")
                || fileName.equals("bossarena_openshop.json")
                || fileName.equals("bossarena_openshop_simple.json")
                || fileName.equals("boss_shop.blockymodel")
                || fileName.equals("boss_arena_shop_texture.png");
    }

    private void copyPackResource(Path assetsRoot, String resourcePath) throws IOException {
        copyResource(resourcePath, assetsRoot.resolve(resourcePath));
    }

    private void copyResource(String resourcePath, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                getLogger().atWarning().log("Missing bundled resource: " + resourcePath);
                return;
            }
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeAssetPath(String path) {
        if (path == null || path.isBlank() || ".".equals(path)) {
            return "";
        }
        return path;
    }

    private static String defaultAssetPath(String path, String fallback) {
        String normalized = safeAssetPath(path);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimServerPrefix(String path) {
        if (path == null) {
            return "";
        }
        if (path.startsWith("Server/")) {
            return path.substring("Server/".length());
        }
        return path;
    }

    private static BlockType findBlockType(BlockTypeAssetMap<String, BlockType> map, String... baseIds) {
        for (String baseId : baseIds) {
            if (baseId == null || baseId.isBlank()) {
                continue;
            }
            BlockType direct = map.getAsset(baseId);
            if (direct != null) {
                return direct;
            }
            BlockType namespaced = map.getAsset(ASSET_PACK_ID + ":" + baseId);
            if (namespaced != null) {
                return namespaced;
            }
        }
        return null;
    }

    private static Item findItem(String... baseIds) {
        for (String baseId : baseIds) {
            if (baseId == null || baseId.isBlank()) {
                continue;
            }
            Item direct = Item.getAssetMap().getAsset(baseId);
            if (direct != null) {
                return direct;
            }
            Item namespaced = Item.getAssetMap().getAsset(ASSET_PACK_ID + ":" + baseId);
            if (namespaced != null) {
                return namespaced;
            }
        }
        return null;
    }

    private Path getModRootDirectory() {
        return MOD_ROOT;
    }

    private void migrateLegacyDataDirectory(Path canonicalRoot) {
        try {
            Path legacyRoot = getDataDirectory();
            if (legacyRoot == null) {
                return;
            }

            Path canonical = canonicalRoot.toAbsolutePath().normalize();
            Path legacy = legacyRoot.toAbsolutePath().normalize();
            if (legacy.equals(canonical) || !Files.isDirectory(legacy)) {
                return;
            }

            Files.createDirectories(canonical);
            copyMissingTree(legacy, canonical);
            copyResource("manifest.json", canonical.resolve("manifest.json"));
            getLogger().atInfo().log("Migrated BossArena data from legacy path " + legacy + " to " + canonical);
            if (isLegacyBossArenaDirectory(legacy)) {
                deleteTree(legacy);
                getLogger().atInfo().log("Removed legacy BossArena directory at " + legacy);
            }
        } catch (Exception e) {
            getLogger().atWarning().withCause(e).log("Failed to migrate legacy BossArena data directory");
        }
    }

    private static void copyMissingTree(Path sourceRoot, Path targetRoot) throws IOException {
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceRoot.relativize(dir);
                Files.createDirectories(targetRoot.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceRoot.relativize(file);
                Path destination = targetRoot.resolve(relative);
                if (!Files.exists(destination)
                        || Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(destination)) > 0) {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isLegacyBossArenaDirectory(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase();
        return name.equals("com.bossarena_bossarena");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void onBlockInteract(LivingEntityUseBlockEvent event) {
        String blockType = event.getBlockType();

        // Check if it's a chest
        if (!blockType.contains("Chest_Legendary") && !blockType.contains("Furniture_Dungeon_Chest")) {
            return;
        }

        getLogger().atInfo().log("Player interacted with chest! Block type: " + blockType);

        // Get player
        Ref<EntityStore> playerRef = event.getRef();
        Store<EntityStore> store = playerRef.getStore();

        // Get player's world
        EntityStore entityStoreData = store.getExternalData();
        World world = entityStoreData.getWorld();

        // Get player position to find nearby chest
        Object transformObj = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (!(transformObj instanceof TransformComponent)) {
            return;
        }

        Vector3d playerPos = ((TransformComponent) transformObj).getPosition();

        // Find chest location near player (within 5 blocks)
        Vector3d chestLoc = BossLootHandler.getChestLocationNear(world, playerPos);
        if (chestLoc == null) {
            getLogger().atInfo().log("No boss loot chest nearby");
            return;
        }

        int x = (int) Math.floor(chestLoc.x);
        int y = (int) Math.floor(chestLoc.y);
        int z = (int) Math.floor(chestLoc.z);

        // Get the block state
        BlockState state = world.getState(x, y, z, true);

        if (!(state instanceof BossLootChestState)) {
            getLogger().atWarning().log("Chest found but state is not BossLootChestState: " +
                    (state != null ? state.getClass().getSimpleName() : "null"));
            return;
        }

        getLogger().atInfo().log("✅ Found BossLootChestState! Opening custom chest...");

        // Let OpenBossChestInteraction handle it OR manually open here
        manuallyOpenChest(playerRef, (BossLootChestState) state, world, x, y, z);
    }

    private void onPlayerInteract(PlayerInteractEvent event) {
        if (event == null) {
            return;
        }

        if (!isShopOpenInteraction(event.getActionType())) {
            return;
        }

        Ref<EntityStore> playerRef = event.getPlayerRef();
        if (playerRef == null) {
            return;
        }
        Store<EntityStore> store = playerRef.getStore();
        Entity targetEntity = event.getTargetEntity();
        Ref<EntityStore> targetRef = event.getTargetRef();

        NPCEntity npc = resolveTargetNpc(targetEntity, targetRef, store);
        UUID targetUuid = resolveTargetUuid(targetEntity, targetRef, store, npc);
        if (shopConfig == null) {
            return;
        }

        boolean knownShopNpc = targetUuid != null && shopConfig.isShopNpcUuid(targetUuid.toString());
        boolean matchesConfiguredType = npc != null
                && isShopNpcTypeId(npc.getNPCTypeId(), resolveShopNpcId());
        if (!knownShopNpc && !matchesConfiguredType) {
            if (targetEntity != null || targetRef != null) {
                getLogger().atInfo().log(
                        "Shop interact ignored: action=" + event.getActionType()
                                + ", targetEntity=" + (targetEntity != null ? targetEntity.getClass().getSimpleName() : "null")
                                + ", npcType=" + (npc != null ? npc.getNPCTypeId() : "null")
                                + ", targetUuid=" + (targetUuid != null ? targetUuid : "null")
                                + ", knownShopNpc=" + knownShopNpc
                                + ", matchesConfiguredType=" + matchesConfiguredType
                );
            }
            return;
        }
        Object playerObj = store.getComponent(playerRef, Player.getComponentType());
        if (!(playerObj instanceof Player player)) {
            return;
        }

        TransformComponent targetTransform = resolveTargetTransform(targetEntity, targetRef, store, npc);
        if (targetUuid != null && targetTransform != null) {
            String worldName = player.getWorld() != null ? player.getWorld().getName() : null;
            if (worldName != null && !worldName.isBlank()) {
                Vector3d targetPosition = targetTransform.getPosition();
                recordShopLocation(
                        worldName,
                        new Vector3i(
                                (int) Math.floor(targetPosition.x),
                                (int) Math.floor(targetPosition.y),
                                (int) Math.floor(targetPosition.z)
                        ),
                        targetUuid
                );
            } else if (!knownShopNpc && matchesConfiguredType) {
                recordShopNpcUuid(targetUuid);
            }
        } else if (!knownShopNpc && matchesConfiguredType && targetUuid != null) {
            recordShopNpcUuid(targetUuid);
        }

        getLogger().atInfo().log(
                "Opening shop from interact: action=" + event.getActionType()
                        + ", npcType=" + (npc != null ? npc.getNPCTypeId() : "null")
                        + ", targetUuid=" + (targetUuid != null ? targetUuid : "null")
                        + ", hasTransform=" + (targetTransform != null)
        );
        openShopPage(playerRef, store, player, targetTransform);
        event.setCancelled(true);
    }

    private void onAddPlayerToWorld(AddPlayerToWorldEvent event) {
        if (event == null || shopConfig == null) {
            return;
        }
        World world = event.getWorld();
        if (world == null) {
            return;
        }
        scheduleShopRebind(world, 0);
    }

    private void scheduleShopRebind(World world, int attempt) {
        if (world == null || !world.isAlive() || shopConfig == null || shopConfig.shops == null || shopConfig.shops.isEmpty()) {
            return;
        }

        long delayMs = switch (attempt) {
            case 0 -> 0L;
            case 1 -> 750L;
            case 2 -> 2000L;
            default -> 5000L;
        };

        SHOP_REBIND_EXECUTOR.schedule(() -> {
            if (!world.isAlive()) {
                return;
            }
            world.execute(() -> {
                int rebound = rebindShopInteractions(world);
                if (rebound == 0 && attempt < 3) {
                    scheduleShopRebind(world, attempt + 1);
                }
            });
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private int rebindShopInteractions(World world) {
        if (world == null || shopConfig == null || shopConfig.shops == null || shopConfig.shops.isEmpty()) {
            return 0;
        }

        Store<EntityStore> store = world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
        if (store == null) {
            return 0;
        }

        String worldName = world.getName();
        int rebound = 0;
        boolean changed = false;
        for (BossShopConfig.ShopLocation location : new ArrayList<>(shopConfig.shops)) {
            if (location == null || location.worldName == null || !location.worldName.equalsIgnoreCase(worldName)) {
                continue;
            }

            String uuidText = location.uuid != null ? location.uuid.trim() : "";
            Ref<EntityStore> ref = null;
            if (!uuidText.isEmpty()) {
                try {
                    UUID uuid = UUID.fromString(uuidText);
                    ref = world.getEntityRef(uuid);
                } catch (IllegalArgumentException ignored) {
                    // malformed uuid; fall back to location lookup
                }
            }

            if (ref == null) {
                ref = findShopNpcRefNearLocation(store, location, resolveShopNpcId());
            }
            if (ref == null) {
                continue;
            }

            if (bindShopNpcInteractionInternal(store, ref)) {
                rebound++;
            }

            Object uuidObj = store.getComponent(ref, UUIDComponent.getComponentType());
            if (uuidObj instanceof UUIDComponent uuidComponent) {
                String resolvedUuid = uuidComponent.getUuid() != null ? uuidComponent.getUuid().toString() : "";
                if (!resolvedUuid.isEmpty() && !resolvedUuid.equalsIgnoreCase(uuidText)) {
                    location.uuid = resolvedUuid;
                    changed = true;
                }
            }
        }

        if (changed) {
            saveShopConfig();
        }

        if (rebound > 0) {
            getLogger().atInfo().log("Rebound shop interaction for " + rebound + " guard(s) in world " + worldName);
        }
        return rebound;
    }

    private Ref<EntityStore> findShopNpcRefNearLocation(Store<EntityStore> store,
                                                        BossShopConfig.ShopLocation location,
                                                        String configuredShopNpcId) {
        if (store == null || location == null) {
            return null;
        }

        final Ref<EntityStore>[] nearestRef = new Ref[]{null};
        final double[] nearestDistanceSq = new double[]{Double.MAX_VALUE};

        store.forEachChunk(
                com.hypixel.hytale.component.query.Query.and(
                        TransformComponent.getComponentType(),
                        NPCEntity.getComponentType()
                ),
                (chunk, ignored) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                        if (npc == null || !isShopNpcTypeId(npc.getNPCTypeId(), configuredShopNpcId)) {
                            continue;
                        }

                        TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                        if (transform == null) {
                            continue;
                        }
                        Vector3d pos = transform.getPosition();
                        double dx = pos.x - location.x;
                        double dy = pos.y - location.y;
                        double dz = pos.z - location.z;
                        double distanceSq = (dx * dx) + (dy * dy) + (dz * dz);
                        if (distanceSq > 16.0d || distanceSq >= nearestDistanceSq[0]) {
                            continue;
                        }
                        nearestDistanceSq[0] = distanceSq;
                        nearestRef[0] = chunk.getReferenceTo(i);
                    }
                }
        );
        return nearestRef[0];
    }

    public void bindShopNpcInteraction(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        bindShopNpcInteractionInternal(store, entityRef);
    }

    private boolean bindShopNpcInteractionInternal(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        if (store == null || entityRef == null) {
            return false;
        }

        Object npcObj = store.getComponent(entityRef, NPCEntity.getComponentType());
        if (npcObj instanceof NPCEntity npc && !isShopNpcTypeId(npc.getNPCTypeId(), resolveShopNpcId())) {
            return false;
        }

        store.ensureComponent(entityRef, Interactable.getComponentType());
        Interactions interactions = store.ensureAndGetComponent(entityRef, Interactions.getComponentType());
        interactions.setInteractionId(InteractionType.Use, SHOP_OPEN_INTERACTION_ID);
        interactions.setInteractionHint("open Boss Arena Shop");
        return true;
    }

    private static boolean isShopOpenInteraction(InteractionType type) {
        return type == InteractionType.Use;
    }

    private static boolean isShopNpcTypeId(String npcTypeId, String configuredId) {
        if (npcTypeId == null || npcTypeId.isBlank() || configuredId == null || configuredId.isBlank()) {
            return false;
        }
        String normalizedNpcTypeId = npcTypeId.toLowerCase(Locale.ROOT);
        String normalizedConfiguredId = configuredId.toLowerCase(Locale.ROOT);
        return normalizedNpcTypeId.equals(normalizedConfiguredId)
                || normalizedNpcTypeId.endsWith(":" + normalizedConfiguredId)
                || normalizedNpcTypeId.endsWith("/" + normalizedConfiguredId);
    }

    private String resolveShopNpcId() {
        if (shopConfig != null && shopConfig.shopNpcId != null && !shopConfig.shopNpcId.isBlank()) {
            return shopConfig.shopNpcId.trim();
        }
        return SHOP_NPC_TYPE_ID;
    }

    private static NPCEntity resolveTargetNpc(Entity targetEntity, Ref<EntityStore> targetRef, Store<EntityStore> store) {
        if (targetRef != null) {
            Object npcObj = store.getComponent(targetRef, NPCEntity.getComponentType());
            if (npcObj instanceof NPCEntity npc) {
                return npc;
            }
        }
        if (targetEntity instanceof NPCEntity npc) {
            return npc;
        }
        return null;
    }

    private static UUID resolveTargetUuid(Entity targetEntity, Ref<EntityStore> targetRef, Store<EntityStore> store, NPCEntity npc) {
        if (targetRef != null) {
            Object uuidObj = store.getComponent(targetRef, UUIDComponent.getComponentType());
            if (uuidObj instanceof UUIDComponent uuidComponent) {
                return uuidComponent.getUuid();
            }
        }
        if (targetEntity != null) {
            return targetEntity.getUuid();
        }
        if (npc == null) {
            return null;
        }
        return npc.getUuid();
    }

    private static TransformComponent resolveTargetTransform(Entity targetEntity, Ref<EntityStore> targetRef, Store<EntityStore> store, NPCEntity npc) {
        if (npc != null && npc.getTransformComponent() != null) {
            return npc.getTransformComponent();
        }
        if (targetRef != null) {
            Object transformObj = store.getComponent(targetRef, TransformComponent.getComponentType());
            if (transformObj instanceof TransformComponent transform) {
                return transform;
            }
        }
        if (targetEntity != null) {
            return targetEntity.getTransformComponent();
        }
        return null;
    }

    private void openShopPage(Ref<EntityStore> playerRef,
                              Store<EntityStore> store,
                              Player player,
                              TransformComponent targetTransform) {
        if (targetTransform == null) {
            BossArenaShopPage.open(playerRef, store, player, this);
            return;
        }

        Vector3d npcPosition = targetTransform.getPosition();
        Vector3i shopAnchor = new Vector3i(
                (int) Math.floor(npcPosition.x),
                (int) Math.floor(npcPosition.y),
                (int) Math.floor(npcPosition.z)
        );

        String worldName = player.getWorld() != null ? player.getWorld().getName() : null;
        if (worldName != null && !worldName.isBlank()) {
            recordShopLocation(worldName, shopAnchor);
            BossArenaShopPage.openAtTable(
                    playerRef,
                    store,
                    player,
                    this,
                    worldName,
                    shopAnchor.x,
                    shopAnchor.y,
                    shopAnchor.z
            );
            return;
        }
        BossArenaShopPage.open(playerRef, store, player, this);
    }

    private void manuallyOpenChest(Ref<EntityStore> playerRef, BossLootChestState state,
                                   World world, int x, int y, int z) {
        // No-op: chest interaction is handled by OpenBossChestInteraction.
    }

    private void startBossArenaSystems() {
        try {
            getLogger().atInfo().log("Starting BossArena systems...");

            BossLootHandler.initializePersistence(lootChestStatePath);
            getLogger().atInfo().log("Loot chest persistence initialized at " + lootChestStatePath);

            if (Files.notExists(bossesJsonPath)) {
                writeDefaultBosses();
            }

            try {
                if (Files.notExists(lootTablesPath)) {
                    LootRegistry.createDefaults();
                    LootRegistry.saveToFile(lootTablesPath);
                    getLogger().atInfo().log("Created default loot_tables.json");
                } else {
                    LootRegistry.loadFromFile(lootTablesPath);
                }
            } catch (IOException e) {
                getLogger().atWarning().withCause(e).log("Failed to load loot tables");
                LootRegistry.createDefaults();
            }
            getLogger().atInfo().log("Loot system initialized with " + LootRegistry.size() + " loot tables");

            reloadBossDefinitions().thenRun(() -> {
                reloadArenas().thenRun(() -> {
                    getLogger().atInfo().log("BossArena fully initialized: " +
                            BossRegistry.size() + " bosses, " +
                            ArenaRegistry.size() + " arenas");
                });
            }).exceptionally(err -> {
                getLogger().atSevere().withCause(err).log("Failed to load boss definitions");
                return null;
            });

        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("Initialization failed for BossArena");
        }
    }

    @Override
    protected void shutdown() {
        try {
            SHOP_REBIND_EXECUTOR.shutdownNow();
            BossLootHandler.flushPersistence();
            getLogger().atInfo().log("Persisted loot chest state during shutdown");
        } catch (Exception e) {
            getLogger().atWarning().withCause(e).log("Failed to persist loot chest state during shutdown");
        }
    }

    public BossSpawnService getBossSpawnService() {
        return bossSpawnService;
    }

    public BossArenaConfig cfg() {
        return config;
    }

    public BossShopConfig getShopConfig() {
        return shopConfig;
    }

    public void reloadShopConfig() {
        shopConfig.load(shopJsonPath);
        if (shopConfig.applyRuntimeCurrencyDetection(resolveFallbackCurrencyItemId())) {
            saveShopConfig();
        }
    }

    private String resolveFallbackCurrencyItemId() {
        if (config == null) {
            return "Ingredient_Bar_Iron";
        }
        if (config.fallbackCurrencyItemId != null && !config.fallbackCurrencyItemId.isBlank()) {
            return config.fallbackCurrencyItemId.trim();
        }
        return "Ingredient_Bar_Iron";
    }

    public CompletableFuture<Void> saveShopConfig() {
        return CompletableFuture.runAsync(() -> {
            try {
                shopConfig.save(shopJsonPath);
                getLogger().atInfo().log("Saved shop config");
            } catch (IOException e) {
                getLogger().atSevere().withCause(e).log("Failed to save shop config");
                throw new RuntimeException(e);
            }
        });
    }

    public void recordShopLocation(String worldName, Vector3i position) {
        recordShopLocation(worldName, position, null);
    }

    public void recordShopLocation(String worldName, Vector3i position, UUID uuid) {
        if (position == null || worldName == null || worldName.isBlank()) {
            return;
        }
        if (shopConfig == null) {
            return;
        }
        boolean changed = shopConfig.recordShopLocation(
                uuid != null ? uuid.toString() : "",
                worldName,
                position.x,
                position.y,
                position.z
        );
        if (!changed) {
            return;
        }
        saveShopConfig();
    }

    public void recordShopNpcUuid(UUID uuid) {
        if (uuid == null || shopConfig == null) {
            return;
        }
        boolean changed = shopConfig.recordShopNpcUuid(uuid.toString());
        if (changed) {
            saveShopConfig();
        }
    }

    public static BossArenaPlugin getInstance() {
        return INSTANCE;
    }

    public BossArenaConfig getConfigHandle() {
        return config;
    }

    public Path getLootTablesPath() {
        return lootTablesPath;
    }

    public Path getBossesJsonPath() {
        return bossesJsonPath;
    }

    public CompletableFuture<Integer> reloadBossDefinitions() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                getLogger().atInfo().log("Loading boss definitions from: " + bossesJsonPath);

                Gson gson = new Gson();
                String json = Files.readString(bossesJsonPath, StandardCharsets.UTF_8);
                BossDefinition[] bosses = gson.fromJson(json, BossDefinition[].class);

                BossRegistry.clear();

                if (bosses != null) {
                    for (BossDefinition def : bosses) {
                        BossRegistry.register(def);
                        getLogger().atInfo().log("Registered boss: " + def.bossName);
                    }
                }

                getLogger().atInfo().log("Loaded " + BossRegistry.size() + " boss definitions");
                return BossRegistry.size();
            } catch (IOException e) {
                getLogger().atSevere().withCause(e).log("Failed to load boss definitions");
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Integer> reloadArenas() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.notExists(arenasJsonPath)) {
                    Files.createDirectories(arenasJsonPath.getParent());
                    Files.writeString(arenasJsonPath, "[]", StandardCharsets.UTF_8);
                    getLogger().atInfo().log("Created empty arenas.json");
                    return 0;
                }

                getLogger().atInfo().log("Loading arenas from: " + arenasJsonPath);

                Gson gson = new Gson();
                String json = Files.readString(arenasJsonPath, StandardCharsets.UTF_8);
                Arena[] arenas = gson.fromJson(json, Arena[].class);

                ArenaRegistry.clear();

                if (arenas != null) {
                    for (Arena arena : arenas) {
                        ArenaRegistry.register(arena);
                        getLogger().atInfo().log("Registered arena: " + arena.arenaId);
                    }
                }

                getLogger().atInfo().log("Loaded " + ArenaRegistry.size() + " arenas");
                return ArenaRegistry.size();
            } catch (IOException e) {
                getLogger().atSevere().withCause(e).log("Failed to load arenas");
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Void> saveArenas() {
        return CompletableFuture.runAsync(() -> {
            try {
                Collection<Arena> arenas = ArenaRegistry.getAll();
                String prettyJson = new GsonBuilder().setPrettyPrinting().create().toJson(arenas);
                Files.createDirectories(arenasJsonPath.getParent());
                Files.writeString(arenasJsonPath, prettyJson, StandardCharsets.UTF_8);
                getLogger().atInfo().log("Saved " + arenas.size() + " arenas");
            } catch (IOException e) {
                getLogger().atSevere().withCause(e).log("Failed to save arenas");
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Integer> reloadLootTables() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.notExists(lootTablesPath)) {
                    LootRegistry.createDefaults();
                    LootRegistry.saveToFile(lootTablesPath);
                    return LootRegistry.size();
                }
                return LootRegistry.loadFromFile(lootTablesPath);
            } catch (IOException e) {
                getLogger().atSevere().withCause(e).log("Failed to load loot tables");
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Void> saveBossDefinitions() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<BossDefinition> bosses = new ArrayList<>(BossRegistry.getAll().values());
                String prettyJson = new GsonBuilder().setPrettyPrinting().create().toJson(bosses);
                Files.createDirectories(bossesJsonPath.getParent());
                Files.writeString(bossesJsonPath, prettyJson, StandardCharsets.UTF_8);
                getLogger().atInfo().log("Saved " + bosses.size() + " boss definitions");
            } catch (IOException e) {
                getLogger().atSevere().withCause(e).log("Failed to save boss definitions");
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Void> saveLootTables() {
        return CompletableFuture.runAsync(() -> {
            try {
                LootRegistry.saveToFile(lootTablesPath);
            } catch (IOException e) {
                getLogger().atSevere().withCause(e).log("Failed to save loot tables");
                throw new RuntimeException(e);
            }
        });
    }

    private void writeDefaultBosses() throws IOException {
        BossDefinition exampleBoss = new BossDefinition();
        exampleBoss.bossName = "Example Boss";
        exampleBoss.npcId = "Bat";
        exampleBoss.tier = "uncommon";
        exampleBoss.amount = 1;

        exampleBoss.modifiers = new BossDefinition.Modifiers();
        exampleBoss.modifiers.hp = 2.0f;
        exampleBoss.modifiers.damage = 1.5f;
        exampleBoss.modifiers.size = 1.0f;

        exampleBoss.perPlayerIncrease = new BossDefinition.PerPlayerIncrease();
        exampleBoss.perPlayerIncrease.hp = 0.5f;
        exampleBoss.perPlayerIncrease.damage = 0.2f;
        exampleBoss.perPlayerIncrease.size = 0.0f;

        exampleBoss.extraMobs = new BossDefinition.ExtraMobs();
        exampleBoss.extraMobs.npcId = "Bat";
        exampleBoss.extraMobs.timeLimitMs = 30000;
        exampleBoss.extraMobs.waves = 2;
        exampleBoss.extraMobs.mobsPerWave = 5;
        BossDefinition.ExtraMobs.WaveAdd exampleAdd = new BossDefinition.ExtraMobs.WaveAdd();
        exampleAdd.npcId = "Bat";
        exampleAdd.mobsPerWave = 5;
        exampleAdd.everyWave = 1;
        exampleBoss.extraMobs.adds.add(exampleAdd);
        exampleBoss.extraMobs.sanitize();

        BossDefinition[] bosses = new BossDefinition[] { exampleBoss };

        String prettyJson = new GsonBuilder().setPrettyPrinting().create().toJson(bosses);
        Files.createDirectories(bossesJsonPath.getParent());
        Files.writeString(bossesJsonPath, prettyJson, StandardCharsets.UTF_8);
        getLogger().atInfo().log("Created default bosses.json");
    }
}
