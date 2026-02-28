package com.bossarena;

import com.bossarena.arena.ArenaDef;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class BossArenaConfig {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final String DEFAULT_CURRENCY_ITEM_ID = "Coin";
    private static final String DEFAULT_FALLBACK_CURRENCY_ITEM_ID = "Ingredient_Bar_Iron";
    public static final String DEFAULT_TIMED_ANNOUNCEMENT_TEXT = "[$World] $Boss event started at $Arena";
    private static final int MIN_COUNTDOWN_MINUTES = 1;
    private static final Path CONFIG_PATH = Path.of("mods", "BossArena", "config.json");

    public ArenaDef[] arenas = new ArenaDef[0];
    public String currencyItemId = DEFAULT_CURRENCY_ITEM_ID;
    public String fallbackCurrencyItemId = DEFAULT_FALLBACK_CURRENCY_ITEM_ID;
    public Map<String, Integer> bossTierCountdownMinutes = createDefaultBossTierCountdownMinutes();
    public List<TimedBossSpawn> timedBossSpawns = new ArrayList<>();

    public static final class TimedBossSpawn {
        public String id = "";
        public boolean enabled = true;
        public String bossId = "";
        public String arenaId = "";
        public long spawnIntervalHours = 1L;
        public long spawnIntervalMinutes = 0L;
        public boolean preventDuplicateWhileAlive = true;
        public long despawnAfterHours = 0L;
        public long despawnAfterMinutes = 0L;
        // Legacy key: server-wide announcement across all worlds.
        public boolean announceWorldWide = false;
        // Optional world-only announcement for players in the spawned world.
        public boolean announceCurrentWorld = false;
        // Optional custom message for global announcement.
        public String worldAnnouncementText = DEFAULT_TIMED_ANNOUNCEMENT_TEXT;
    }

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
            Files.createDirectories(CONFIG_PATH.getParent());
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(this);
            Files.writeString(CONFIG_PATH, json);
            LOGGER.info("Successfully saved BossArena config");
        } catch (IOException e) {
            LOGGER.severe("Failed to save BossArena config: " + e.getMessage());
        }
    }

    public void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String content = Files.readString(CONFIG_PATH);
                BossArenaConfig loaded = new GsonBuilder().create().fromJson(content, BossArenaConfig.class);
                if (loaded != null) {
                    applyLoadedConfig(loaded);
                    LOGGER.info("Successfully loaded BossArena config");
                    if (shouldPersistMergedConfig(content)) {
                        save();
                    }
                } else {
                    LOGGER.warning("Config file was empty or invalid JSON, recreating defaults");
                    applyDefaultConfig();
                    save();
                }
            } else {
                LOGGER.info("No config file found, creating defaults");
                applyDefaultConfig();
                save();
            }
        } catch (IOException e) {
            LOGGER.severe("Failed to load BossArena config: " + e.getMessage());
        }
    }

    public List<TimedBossSpawn> getTimedBossSpawns() {
        timedBossSpawns = sanitizeTimedBossSpawns(timedBossSpawns);
        return new ArrayList<>(timedBossSpawns);
    }

    public static long resolveMinutes(long hours, long minutes) {
        long safeHours = Math.max(0L, hours);
        long safeMinutes = Math.max(0L, minutes);
        return (safeHours * 60L) + safeMinutes;
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

    private void applyLoadedConfig(BossArenaConfig loaded) {
        this.arenas = loaded.arenas != null ? loaded.arenas : new ArenaDef[0];
        this.currencyItemId = sanitizeItemId(loaded.currencyItemId, DEFAULT_CURRENCY_ITEM_ID);
        this.fallbackCurrencyItemId = sanitizeItemId(
                loaded.fallbackCurrencyItemId,
                DEFAULT_FALLBACK_CURRENCY_ITEM_ID
        );
        this.bossTierCountdownMinutes = sanitizeBossTierCountdownMinutes(loaded.bossTierCountdownMinutes);
        this.timedBossSpawns = sanitizeTimedBossSpawns(loaded.timedBossSpawns);
    }

    private void applyDefaultConfig() {
        this.arenas = new ArenaDef[0];
        this.currencyItemId = DEFAULT_CURRENCY_ITEM_ID;
        this.fallbackCurrencyItemId = DEFAULT_FALLBACK_CURRENCY_ITEM_ID;
        this.bossTierCountdownMinutes = createDefaultBossTierCountdownMinutes();
        this.timedBossSpawns = new ArrayList<>();
    }

    private boolean shouldPersistMergedConfig(String originalContent) {
        if (originalContent == null || originalContent.isBlank()) {
            return true;
        }
        try {
            Gson gson = new GsonBuilder().create();
            JsonElement onDisk = JsonParser.parseString(originalContent);
            JsonElement merged = gson.toJsonTree(this);
            if (!hasAllCountdownTiers(this.bossTierCountdownMinutes)) {
                return true;
            }
            return !merged.equals(onDisk);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static List<TimedBossSpawn> sanitizeTimedBossSpawns(List<TimedBossSpawn> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        List<TimedBossSpawn> out = new ArrayList<>();
        for (TimedBossSpawn raw : source) {
            if (raw == null) {
                continue;
            }

            TimedBossSpawn clean = new TimedBossSpawn();
            clean.id = optional(raw.id);
            clean.enabled = raw.enabled;
            clean.bossId = optional(raw.bossId);
            clean.arenaId = optional(raw.arenaId);
            clean.spawnIntervalHours = Math.max(0L, raw.spawnIntervalHours);
            clean.spawnIntervalMinutes = Math.max(0L, raw.spawnIntervalMinutes);
            clean.preventDuplicateWhileAlive = raw.preventDuplicateWhileAlive;
            clean.despawnAfterHours = Math.max(0L, raw.despawnAfterHours);
            clean.despawnAfterMinutes = Math.max(0L, raw.despawnAfterMinutes);
            clean.announceWorldWide = raw.announceWorldWide;
            clean.announceCurrentWorld = raw.announceCurrentWorld;
            if (clean.announceWorldWide) {
                clean.announceCurrentWorld = false;
            }
            clean.worldAnnouncementText = optional(raw.worldAnnouncementText);
            if (clean.worldAnnouncementText.isEmpty()) {
                clean.worldAnnouncementText = DEFAULT_TIMED_ANNOUNCEMENT_TEXT;
            }

            long intervalMinutes = resolveMinutes(clean.spawnIntervalHours, clean.spawnIntervalMinutes);
            if (intervalMinutes <= 0L) {
                clean.spawnIntervalHours = 1L;
                clean.spawnIntervalMinutes = 0L;
            }

            out.add(clean);
        }
        return out;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
