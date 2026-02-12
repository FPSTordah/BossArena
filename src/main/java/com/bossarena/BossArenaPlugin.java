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
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityUseBlockEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.BlockStateModule;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class BossArenaPlugin extends JavaPlugin {

    private BossTrackingSystem trackingSystem;
    private BossArenaConfig config = new BossArenaConfig();
    private BossSpawnService bossSpawnService;
    private Path bossesJsonPath;
    private Path arenasJsonPath;
    private Path lootTablesPath;

    public BossArenaPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
        getLogger().atInfo().log("BossArena setup() called");

        registerCustomCodecs();
        registerAssetPack();
        registerCustomBlocks();
        registerCustomInteractions();

        this.bossesJsonPath = Path.of("mods", "BossArena", "bosses.json");
        this.arenasJsonPath = Path.of("mods", "BossArena", "arenas.json");
        this.lootTablesPath = Path.of("mods", "BossArena", "loot_tables.json");

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

        config.load();

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
            // Register the custom block type
            BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();

            // Load from your assets folder
            // This assumes you have boss_arena_chest_legendary.json in the right place

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
            RootInteraction.getAssetStore().loadAssets("BossArena", List.of(rootInteraction));
            getLogger().atInfo().log("Registered RootInteraction: BossArena_OpenChest");

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

            getLogger().atInfo().log("✅ Successfully registered custom codecs");

        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("❌ Failed to register custom codecs");
        }
    }

    private void registerAssetPack() {
        try {
            Path assetsRoot = getDataDirectory().resolve("assets");
            extractAssets(assetsRoot);

            AssetModule assetModule = AssetModule.get();
            if (assetModule != null && assetModule.getAssetPack("BossArena") == null) {
                assetModule.registerPack("BossArena", assetsRoot, getManifest());
                assetModule.initPendingStores();
                getLogger().atInfo().log("Registered BossArena asset pack at " + assetsRoot);
            }

            BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
            if (blockTypeMap.getAsset("Boss_Arena_Chest_Legendary") != null) {
                getLogger().atInfo().log("BossArena custom chest block found in asset map");
            } else {
                getLogger().atWarning().log("BossArena custom chest block still missing from asset map");
            }
        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("Failed to register BossArena asset pack");
        }
    }

    private void extractAssets(Path assetsRoot) throws IOException {
        String blockTypePath = safeAssetPath(
                BlockType.getAssetStore() != null ? BlockType.getAssetStore().getPath() : ""
        );
        String modelPath = safeAssetPath(
                ModelAsset.getAssetStore() != null ? ModelAsset.getAssetStore().getPath() : ""
        );

        Path blockTypeDir = blockTypePath.isEmpty() ? assetsRoot : assetsRoot.resolve(blockTypePath);
        Path modelDir = modelPath.isEmpty() ? assetsRoot : assetsRoot.resolve(modelPath);

        copyResourceIfMissing(
                "assets/boss_arena_chest_legendary.json",
                blockTypeDir.resolve("boss_arena_chest_legendary.json")
        );
        copyResourceIfMissing(
                "assets/Boss_Arena_Chest_Legendary.blockymodel",
                modelDir.resolve("Boss_Arena_Chest_Legendary.blockymodel")
        );
        copyResourceIfMissing(
                "assets/Boss_Arena_Chest_Legendary_Texture.png",
                assetsRoot.resolve("Boss_Arena_Chest_Legendary_Texture.png")
        );
    }

    private void copyResourceIfMissing(String resourcePath, Path destination) throws IOException {
        if (Files.exists(destination)) {
            return;
        }
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

    private void manuallyOpenChest(Ref<EntityStore> playerRef, BossLootChestState state,
                                   World world, int x, int y, int z) {
        getLogger().atInfo().log("TODO: Open chest UI for player");
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

    public BossArenaConfig getConfigHandle() {
        return config;
    }

    public Path getLootTablesPath() {
        return lootTablesPath;
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

    private void writeDefaultBosses() throws IOException {
        BossDefinition exampleBoss = new BossDefinition();
        exampleBoss.bossName = "Example Boss";
        exampleBoss.npcId = "Bat";
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

        BossDefinition[] bosses = new BossDefinition[] { exampleBoss };

        String prettyJson = new GsonBuilder().setPrettyPrinting().create().toJson(bosses);
        Files.createDirectories(bossesJsonPath.getParent());
        Files.writeString(bossesJsonPath, prettyJson, StandardCharsets.UTF_8);
        getLogger().atInfo().log("Created default bosses.json");
    }
}
