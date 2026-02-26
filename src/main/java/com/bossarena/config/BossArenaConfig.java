package com.bossarena;

import com.bossarena.arena.ArenaDef;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class BossArenaConfig {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final String DEFAULT_CURRENCY_ITEM_ID = "Coin";
    private static final String DEFAULT_FALLBACK_CURRENCY_ITEM_ID = "Ingredient_Bar_Iron";
    private static final int MIN_COUNTDOWN_MINUTES = 1;

    public ArenaDef[] arenas = new ArenaDef[0];
    public String currencyItemId = DEFAULT_CURRENCY_ITEM_ID;
    public String fallbackCurrencyItemId = DEFAULT_FALLBACK_CURRENCY_ITEM_ID;
    public Map<String, Integer> bossTierCountdownMinutes = createDefaultBossTierCountdownMinutes();

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
                    this.arenas = loaded.arenas != null ? loaded.arenas : new ArenaDef[0];
                    this.currencyItemId = sanitizeItemId(loaded.currencyItemId, DEFAULT_CURRENCY_ITEM_ID);
                    this.fallbackCurrencyItemId = sanitizeItemId(
                            loaded.fallbackCurrencyItemId,
                            DEFAULT_FALLBACK_CURRENCY_ITEM_ID
                    );
                    this.bossTierCountdownMinutes = sanitizeBossTierCountdownMinutes(loaded.bossTierCountdownMinutes);
                    LOGGER.info("Successfully loaded BossArena config");
                    if (!hasAllCountdownTiers(loaded.bossTierCountdownMinutes)) {
                        save();
                    }
                }
            } else {
                LOGGER.info("No config file found, using defaults");
            }
        } catch (IOException e) {
            LOGGER.severe("Failed to load BossArena config: " + e.getMessage());
        }
    }

    private static String sanitizeItemId(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    public int getBossTierCountdownMinutes(String tier) {
        Map<String, Integer> timers = sanitizeBossTierCountdownMinutes(bossTierCountdownMinutes);
        this.bossTierCountdownMinutes = timers;
        String key = normalizeTierKey(tier);
        Integer configured = timers.get(key);
        if (configured == null) {
            configured = timers.get("uncommon");
        }
        if (configured == null) {
            configured = 30;
        }
        return Math.max(MIN_COUNTDOWN_MINUTES, configured);
    }

    private static Map<String, Integer> sanitizeBossTierCountdownMinutes(Map<String, Integer> source) {
        Map<String, Integer> out = createDefaultBossTierCountdownMinutes();
        if (source == null || source.isEmpty()) {
            return out;
        }

        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String tier = normalizeTierKey(entry.getKey());
            if (!out.containsKey(tier)) {
                continue;
            }
            out.put(tier, Math.max(MIN_COUNTDOWN_MINUTES, entry.getValue()));
        }
        return out;
    }

    private static Map<String, Integer> createDefaultBossTierCountdownMinutes() {
        Map<String, Integer> out = new LinkedHashMap<>();
        // Start at 30 minutes for uncommon, then +15 per tier.
        out.put("common", 15);
        out.put("uncommon", 30);
        out.put("rare", 45);
        out.put("epic", 60);
        out.put("legendary", 75);
        return out;
    }

    private static String normalizeTierKey(String tier) {
        if (tier == null || tier.isBlank()) {
            return "uncommon";
        }
        return tier.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasAllCountdownTiers(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            normalized.put(normalizeTierKey(entry.getKey()), entry.getValue());
        }
        Map<String, Integer> defaults = createDefaultBossTierCountdownMinutes();
        for (String tier : defaults.keySet()) {
            Integer value = normalized.get(tier);
            if (value == null || value < MIN_COUNTDOWN_MINUTES) {
                return false;
            }
        }
        return true;
    }
}
