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
import com.bossarena.system.LootSpawnSystem;
import com.bossarena.loot.LootRegistry;
import com.bossarena.loot.BossLootHandler;
import com.bossarena.loot.BossLootChestState;
import com.bossarena.loot.OpenBossChestInteraction;
import com.bossarena.shop.BossShopConfig;
import com.bossarena.shop.BossArenaShopPage;
import com.bossarena.shop.OpenBossShopInteraction;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityUseBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
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
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.BlockStateModule;

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
import java.util.concurrent.CompletableFuture;

public final class BossArenaPlugin extends JavaPlugin {
    private static BossArenaPlugin INSTANCE;
    public static final String ASSET_PACK_ID = "com.bossarena:BossArena";
    private static final Path MOD_ROOT = Path.of("mods", "BossArena");
    private static final String[] SHOP_ICON_NAMES = new String[]{
            "common1.png", "common2.png", "common3.png", "common4.png",
            "uncommon1.png", "uncommon2.png", "uncommon3.png", "uncommon4.png",
            "rare1.png", "rare2.png", "rare3.png", "rare4.png",
            "epic1.png", "epic2.png", "epic3.png", "epic4.png",
            "legendary1.png", "legendary2.png", "legendary3.png", "legendary4.png"
    };
    private static final List<String> SHOP_TIERS = List.of("Common", "Uncommon", "Rare", "Epic", "Legendary");

    private BossTrackingSystem trackingSystem;
    private BossArenaConfig config = new BossArenaConfig();
    private BossShopConfig shopConfig = new BossShopConfig();
    private BossSpawnService bossSpawnService;
    private Path bossesJsonPath;
    private Path arenasJsonPath;
    private Path lootTablesPath;
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
        this.shopJsonPath = modRoot.resolve("shop.json");

        // Create tracking system
        this.trackingSystem = new BossTrackingSystem();

        // Register ECS systems
        this.getEntityStoreRegistry().registerSystem(new LootSpawnSystem());
        this.getEntityStoreRegistry().registerSystem(new BossDeathSystem(trackingSystem));
        getLogger().atInfo().log("Successfully registered boss systems");

        // Register chest interaction event
        this.getEventRegistry().registerGlobal(
                LivingEntityUseBlockEvent.class,
                this::onBlockInteract
        );
        getLogger().atInfo().log("Registered chest interaction listener");
        this.getEventRegistry().registerGlobal(
                UseBlockEvent.Post.class,
                this::onUseBlock
        );
        getLogger().atInfo().log("Registered block use interaction listener");
        this.getEventRegistry().registerGlobal(
                BreakBlockEvent.class,
                this::onBreakBlock
        );
        getLogger().atInfo().log("Registered block break listener");

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

        this.bossSpawnService = new BossSpawnService(trackingSystem);

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

            RootInteraction shopInteraction = new RootInteraction("BossArena_OpenShop", "BossArena_OpenShop");
            RootInteraction.getAssetStore().loadAssets(ASSET_PACK_ID, List.of(shopInteraction));
            getLogger().atInfo().log("Registered RootInteraction: BossArena_OpenShop");

            Interaction.getAssetStore().loadAssets(ASSET_PACK_ID, List.of(
                    new OpenBossChestInteraction(),
                    new OpenBossShopInteraction()
            ));
            getLogger().atInfo().log("Registered interaction assets for BossArena_OpenChest and BossArena_OpenShop");

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
                    "BossArena_OpenShop",
                    OpenBossShopInteraction.class,
                    OpenBossShopInteraction.CODEC
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

            BlockType shopBlockType = findBlockType(blockTypeMap, "Boss_Arena_Shop");
            if (shopBlockType != null) {
                getLogger().atInfo().log("BossArena shop pedestal block found in asset map");
            } else {
                getLogger().atWarning().log("BossArena shop pedestal block still missing from asset map");
            }

            Item shopItem = findItem("Boss_Arena_Shop");
            if (shopItem != null) {
                getLogger().atInfo().log("BossArena shop pedestal item found in asset map");
            } else {
                getLogger().atWarning().log("BossArena shop pedestal item still missing from asset map");
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

        copyPackResource(assetsRoot, "Common/Blocks/Boss_Shop.blockymodel");
        copyPackResource(assetsRoot, "Common/Blocks/boss_arena_shop_texture.png");
        copyPackResource(assetsRoot, "Common/Blocks/Boss_Arena_Chest_Legendary.blockymodel");
        copyPackResource(assetsRoot, "Common/Blocks/Boss_Arena_Chest_Legendary_Texture.png");
        copyPackResource(assetsRoot, "Blocks/Boss_Shop.blockymodel");
        copyPackResource(assetsRoot, "Blocks/boss_arena_shop_texture.png");
        copyPackResource(assetsRoot, "Blocks/Boss_Arena_Chest_Legendary.blockymodel");
        copyPackResource(assetsRoot, "Blocks/Boss_Arena_Chest_Legendary_Texture.png");
        copyPackResource(assetsRoot, "Server/Item/Items/Boss_Arena_Chest_Legendary.json");
        copyPackResource(assetsRoot, "Server/Item/Items/Boss_Arena_Chest_Legendary.blockymodel");
        copyPackResource(assetsRoot, "Server/Item/Items/Boss_Arena_Shop.json");
        copyPackResource(assetsRoot, "Server/Item/RootInteractions/Block/BossArena_OpenShop.json");
        copyPackResource(assetsRoot, "Server/Item/Interactions/Block/BossArena_OpenShop_Simple.json");

        copyPackResource(assetsRoot, "Server/Textures/Boss_Arena_Chest_Legendary_Texture.png");
        copyPackResource(assetsRoot, "Server/Textures/boss_arena_shop_texture.png");

        copyPackResource(assetsRoot, "Common/Icons/ItemsGenerated/boss_arena_shop_icon.png");
        copyPackResource(assetsRoot, "Server/Icons/ItemsGenerated/boss_arena_shop_icon.png");

        // Shop item icons are intentionally disabled for now.

        // Mirror key assets into runtime asset-store directories for compatibility.
        copyResource("Blocks/Boss_Shop.blockymodel", modelDir.resolve("Boss_Shop.blockymodel"));
        copyResource("Server/Item/Items/Boss_Arena_Chest_Legendary.blockymodel", modelDir.resolve("Boss_Arena_Chest_Legendary.blockymodel"));
        copyResource("Server/Item/Items/Boss_Arena_Shop.json", itemDir.resolve("Boss_Arena_Shop.json"));
        // Legacy compatibility copies for pre-Update 3 item path assumptions.
        copyResource("Server/Item/Items/Boss_Arena_Shop.json", assetsRoot.resolve("Server/Items/Boss_Arena_Shop.json"));
        copyResource("Server/Item/Items/Boss_Arena_Chest_Legendary.json", assetsRoot.resolve("Server/Items/Boss_Arena_Chest_Legendary.json"));

        // Texture compatibility copies for model lookup differences.
        copyResource("Blocks/boss_arena_shop_texture.png", modelDir.resolve("boss_arena_shop_texture.png"));
        copyResource("Server/Textures/boss_arena_shop_texture.png", assetsRoot.resolve("boss_arena_shop_texture.png"));
        copyResource("Server/Textures/boss_arena_shop_texture.png", assetsRoot.resolve("shop_texture.png"));
        copyResource("Server/Textures/boss_arena_shop_texture.png", assetsRoot.resolve("Textures/boss_arena_shop_texture.png"));
        copyResource("Server/Textures/boss_arena_shop_texture.png", assetsRoot.resolve("Blocks/boss_arena_shop_texture.png"));
        copyResource("Blocks/boss_arena_shop_texture.png", assetsRoot.resolve("Blocks/boss_arena_shop_texture.png"));
        copyResource("Blocks/Boss_Shop.blockymodel", assetsRoot.resolve("Blocks/Boss_Shop.blockymodel"));
        copyResource("Blocks/Boss_Arena_Chest_Legendary.blockymodel", assetsRoot.resolve("Blocks/Boss_Arena_Chest_Legendary.blockymodel"));
        copyResource("Blocks/Boss_Arena_Chest_Legendary_Texture.png", assetsRoot.resolve("Blocks/Boss_Arena_Chest_Legendary_Texture.png"));
        copyResource("Server/Textures/boss_arena_shop_texture.png", modelDir.resolve("boss_arena_shop_texture.png"));
        copyResource("Server/Textures/Boss_Arena_Chest_Legendary_Texture.png", modelDir.resolve("Boss_Arena_Chest_Legendary_Texture.png"));

        // Clean up legacy pedestal files if they exist from prior versions.
        Files.deleteIfExists(assetsRoot.resolve("Server/Item/Items/boss_arena_shop_pedestal.json"));
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

    private void copyResourceIfMissing(String resourcePath, Path destination) throws IOException {
        if (Files.exists(destination)) {
            return;
        }
        copyResource(resourcePath, destination);
    }

    private void syncExternalShopIcons(Path assetsRoot) throws IOException {
        Path externalDir = getModRootDirectory().resolve("shopicons");
        Files.createDirectories(externalDir);

        // Seed easy-to-edit icon folder on first run.
        for (String name : SHOP_ICON_NAMES) {
            copyResourceIfMissing(
                    "Common/Icons/Items/ShopItems/" + name,
                    externalDir.resolve(name)
            );
        }

        int copied = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(externalDir)) {
            for (Path iconFile : stream) {
                if (!Files.isRegularFile(iconFile)) {
                    continue;
                }
                String fileName = iconFile.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    continue;
                }

                Path commonDest = assetsRoot.resolve("Common/Icons/Items/ShopItems").resolve(fileName);
                Path serverDest = assetsRoot.resolve("Server/Icons/Items/ShopItems").resolve(fileName);
                Files.createDirectories(commonDest.getParent());
                Files.createDirectories(serverDest.getParent());
                Files.copy(iconFile, commonDest, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(iconFile, serverDest, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }

        getLogger().atInfo().log("BossArena shop icon sync: loaded " + copied + " file(s) from " + externalDir);
    }

    private void generateShopIconItems(Path assetsRoot, Path itemDir) throws IOException {
        JsonObject template = readJsonObjectResource("Server/Item/Items/Boss_Arena_Shop.json");
        if (template == null) {
            getLogger().atWarning().log("Skipping generated Boss_Shop items: missing Boss_Arena_Shop.json template");
            return;
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path serverItemDir = assetsRoot.resolve("Server/Item/Items");
        Files.createDirectories(serverItemDir);
        Files.createDirectories(itemDir);

        for (String tier : SHOP_TIERS) {
            String lowerTier = tier.toLowerCase(Locale.ROOT);
            for (int slot = 1; slot <= 4; slot++) {
                String id = "Boss_Shop_" + tier + "_" + slot;
                JsonObject itemJson = template.deepCopy();

                JsonObject translation = itemJson.has("TranslationProperties")
                        && itemJson.get("TranslationProperties").isJsonObject()
                        ? itemJson.getAsJsonObject("TranslationProperties")
                        : new JsonObject();
                itemJson.add("TranslationProperties", translation);
                translation.addProperty("Name", tier + " Contract " + slot);
                translation.addProperty("Description", "BossArena tier contract icon.");

                itemJson.addProperty("Icon", "Icons/Items/ShopItems/" + lowerTier + slot + ".png");
                itemJson.addProperty("Quality", tier);
                itemJson.addProperty("MaxStack", 1);

                Path generatedPath = serverItemDir.resolve(id + ".json");
                writeJson(gson, generatedPath, itemJson);

                Path runtimePath = itemDir.resolve(id + ".json");
                if (!generatedPath.normalize().equals(runtimePath.normalize())) {
                    writeJson(gson, runtimePath, itemJson);
                }
            }
        }
    }

    private JsonObject readJsonObjectResource(String resourcePath) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(raw).getAsJsonObject();
        }
    }

    private void writeJson(Gson gson, Path destination, JsonObject object) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.writeString(
                destination,
                gson.toJson(object),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
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
        Vector3d chestLoc = BossLootHandler.getChestLocationNear(playerPos);
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

    private void onUseBlock(UseBlockEvent event) {
        if (event == null) {
            return;
        }

        InteractionType type = event.getInteractionType();
        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType = event.getBlockType();
        if (blockType == null) {
            return;
        }

        String blockId = blockType.getId();
        if (blockId == null) {
            return;
        }

        if (!isShopBlockId(blockId)) {
            return;
        }

        if (!isShopOpenInteraction(type)) {
            return;
        }
        getLogger().atInfo().log("Shop use event (blockId=" + blockId + ", interaction=" + type + ")");
        var context = event.getContext();
        if (context == null) {
            return;
        }
        Vector3i targetBlock = event.getTargetBlock();
        if (targetBlock != null) {
            recordShopTableLocation(playerRefWorldName(context), targetBlock);
        }
        Ref<EntityStore> playerRef = context.getEntity();
        Store<EntityStore> store = playerRef.getStore();
        Object playerObj = store.getComponent(playerRef, Player.getComponentType());
        if (playerObj instanceof Player player) {
            if (targetBlock != null) {
                BossArenaShopPage.openAtTable(
                        playerRef,
                        store,
                        player,
                        this,
                        playerRefWorldName(context),
                        targetBlock.x,
                        targetBlock.y,
                        targetBlock.z
                );
            } else {
                BossArenaShopPage.open(playerRef, store, player, this);
            }
        }
    }

    private void onBreakBlock(BreakBlockEvent event) {
        if (event == null || shopConfig == null) {
            return;
        }

        var blockType = event.getBlockType();
        if (blockType == null || blockType.getId() == null) {
            return;
        }
        if (!isShopBlockId(blockType.getId())) {
            return;
        }

        Vector3i target = event.getTargetBlock();
        if (target == null) {
            return;
        }

        int removed = shopConfig.removeTableLocationsByPosition(target.x, target.y, target.z);
        if (removed <= 0) {
            return;
        }

        getLogger().atInfo().log("Removed " + removed + " saved shop table location(s) at "
                + target.x + ", " + target.y + ", " + target.z + " after block break");
        saveShopConfig();
    }

    private static boolean isShopOpenInteraction(InteractionType type) {
        return type == InteractionType.Use;
    }

    private static boolean isShopBlockId(String blockId) {
        String normalized = blockId.toLowerCase(Locale.ROOT);
        return normalized.contains("boss_arena_shop");
    }

    private void manuallyOpenChest(Ref<EntityStore> playerRef, BossLootChestState state,
                                   World world, int x, int y, int z) {
        // No-op: chest interaction is handled by OpenBossChestInteraction.
    }

    private void startBossArenaSystems() {
        try {
            getLogger().atInfo().log("Starting BossArena systems...");

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

    public void recordShopTableLocation(String worldName, Vector3i position) {
        if (position == null || worldName == null || worldName.isBlank()) {
            return;
        }
        if (shopConfig == null) {
            return;
        }
        boolean changed = shopConfig.recordTableLocation(worldName, position.x, position.y, position.z);
        if (!changed) {
            return;
        }
        saveShopConfig();
    }

    private String playerRefWorldName(com.hypixel.hytale.server.core.entity.InteractionContext context) {
        if (context == null) {
            return null;
        }
        Ref<EntityStore> playerRef = context.getEntity();
        if (playerRef == null) {
            return null;
        }
        Store<EntityStore> store = playerRef.getStore();
        Object playerObj = store.getComponent(playerRef, Player.getComponentType());
        if (playerObj instanceof Player player) {
            World world = player.getWorld();
            if (world != null) {
                return world.getName();
            }
        }
        return null;
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
                bosses.sort(Comparator.comparing(
                        boss -> boss != null && boss.bossName != null ? boss.bossName.toLowerCase(Locale.ROOT) : ""
                ));
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
