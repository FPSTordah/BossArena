package com.bossarena;

import com.bossarena.arena.ArenaDef;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public final class BossArenaConfig {
    private static final Logger LOGGER = Logger.getLogger("BossArena");

    public ArenaDef[] arenas = new ArenaDef[0];
    public String currencyItemId = "Coin";

//    public ArenaDef getArena(String arenaId) {
//        if (arenaId == null || arenas == null) return null;
//        for (ArenaDef a : arenas) {
//            if (a != null && a.id != null && a.id.equalsIgnoreCase(arenaId)) {
//                return a;
//            }
//        }
//        return null;
//    }

    public void save() {
        try {
            Path path = Path.of("mods", "BossArena", "config.json");
            Files.createDirectories(path.getParent());
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(this);
            Files.writeString(path, json);
            LOGGER.info("Successfully saved BossArena config");
        } catch (IOException e) {
            LOGGER.severe("Failed to save BossArena config: " + e.getMessage());
        }
    }

    public void load() {
        try {
            Path path = Path.of("mods", "BossArena", "config.json");
            if (Files.exists(path)) {
                String content = Files.readString(path);
                BossArenaConfig loaded = new GsonBuilder().create().fromJson(content, BossArenaConfig.class);
                if (loaded != null) {
                    this.arenas = loaded.arenas;
                    this.currencyItemId = loaded.currencyItemId;
                    LOGGER.info("Successfully loaded BossArena config");
                }
            } else {
                LOGGER.info("No config file found, using defaults");
            }
        } catch (IOException e) {
            LOGGER.severe("Failed to load BossArena config: " + e.getMessage());
        }
    }
}