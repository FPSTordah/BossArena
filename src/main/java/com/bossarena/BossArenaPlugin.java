package com.bossarena;

import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.bossarena.command.BossArenaCommand;
import com.bossarena.command.BossArenaShortCommand;
import com.bossarena.spawn.BossSpawnService;
import com.bossarena.system.BossTrackingSystem;
import com.bossarena.system.BossDamageSystem;
import com.bossarena.boss.BossFightComponent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.ComponentType;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

public final class BossArenaPlugin extends JavaPlugin {

    private final BossTrackingSystem trackingSystem = new BossTrackingSystem();
    private BossArenaConfig config = new BossArenaConfig();
    private BossSpawnService bossSpawnService;
    private Path bossesJsonPath;

    public BossArenaPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
        getLogger().atInfo().log("BossArena setup() called");

        this.bossesJsonPath = Path.of("mods", "BossArena", "bosses.json");
        config.load();

        // Register commands immediately
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

        // Register damage system during setup (not async)
        try {
            BossDamageSystem damageSystem = new BossDamageSystem(trackingSystem);
            getEntityStoreRegistry().registerSystem(damageSystem);
            getLogger().atInfo().log("Successfully registered BossDamageSystem");
        } catch (Exception e) {
            getLogger().atWarning().withCause(e).log("Failed to register BossDamageSystem");
        }

        this.bossSpawnService = new BossSpawnService(trackingSystem, 300000L);

        // Start async initialization for boss definitions
        CompletableFuture.runAsync(() -> {
            while (Universe.get() == null) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
            }
            startBossArenaSystems();
        });
    }

    private void startBossArenaSystems() {
        try {
            getLogger().atInfo().log("Starting BossArena systems...");

            if (Files.notExists(bossesJsonPath)) {
                writeDefaultBosses();
            }

            reloadBossDefinitions().thenRun(() -> {
                getLogger().atInfo().log("BossArena fully initialized: " + BossRegistry.size() + " bosses loaded");
            }).exceptionally(err -> {
                getLogger().atSevere().withCause(err).log("Failed to load boss definitions");
                return null;
            });

        } catch (Exception e) {
            getLogger().atSevere().withCause(e).log("Initialization failed for BossArena");
        }
    }

    public BossSpawnService getBossSpawnService() { return bossSpawnService; }
    public BossArenaConfig cfg() { return config; }
    public BossArenaConfig getConfigHandle() { return config; }

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

    private void writeDefaultBosses() throws IOException {
        getLogger().atInfo().log("Creating default bosses.json...");

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

        String prettyJson = new GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(bosses);

        Files.createDirectories(bossesJsonPath.getParent());
        Files.writeString(bossesJsonPath, prettyJson, StandardCharsets.UTF_8);

        getLogger().atInfo().log("Created default bosses.json");
    }
}