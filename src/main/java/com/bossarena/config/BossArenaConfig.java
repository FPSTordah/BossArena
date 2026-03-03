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
    public static final String DEFAULT_TIMED_ANNOUNCEMENT_TEXT = "[$World] $Boss event started at $Arena";
    public static final String DEFAULT_TIMED_MAP_MARKER_IMAGE = "map_marker.png";
    public static final String DEFAULT_TIMED_MAP_MARKER_NAME_TEMPLATE = "Timed Boss: $Boss @ $Arena";
    public static final String DEFAULT_EVENT_ACTIVE_TITLE_TEMPLATE = "The Shadows Stir—$BossUpper Approaches!";
    public static final String DEFAULT_EVENT_ACTIVE_SUBTITLE_TEMPLATE =
            "$ContextLine$CountdownLineBoss alive: $BossAlive | Wave mobs alive: $AddsAlive";
    public static final String DEFAULT_EVENT_VICTORY_TITLE_TEMPLATE = "VICTORY! Claim your spoils!";
    public static final String DEFAULT_EVENT_VICTORY_SUBTITLE_TEMPLATE = "Boss alive: 0 | Wave mobs alive: 0";
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final String DEFAULT_CURRENCY_ITEM_ID = "Coin";
    private static final String DEFAULT_FALLBACK_CURRENCY_ITEM_ID = "Ingredient_Bar_Iron";
    private static final int MIN_COUNTDOWN_MINUTES = 1;
    private static final double DEFAULT_NOTIFICATION_RADIUS = 100.0d;
    private static final double MIN_NOTIFICATION_RADIUS = 10.0d;
    private static final double MAX_NOTIFICATION_RADIUS = 500.0d;
    private static final Path CONFIG_PATH = Path.of("mods", "BossArena", "config.json");

    public ArenaDef[] arenas = new ArenaDef[0];
    /** Distance (blocks) within which players see boss event title/subtitle. */
    public double notificationRadius = DEFAULT_NOTIFICATION_RADIUS;
    public String currencyItemId = DEFAULT_CURRENCY_ITEM_ID;
    public String fallbackCurrencyItemId = DEFAULT_FALLBACK_CURRENCY_ITEM_ID;
    public Map<String, Integer> bossTierCountdownMinutes = createDefaultBossTierCountdownMinutes();
    public EventBannerTemplates eventBanner = createDefaultEventBannerTemplates();
    public TimedMapMarkerSettings timedMapMarker = createDefaultTimedMapMarkerSettings();
    public List<TimedBossSpawn> timedBossSpawns = new ArrayList<>();
    // JSON does not support real comments. This is a docs-only block.
    public PlaceholderDocs _comment_placeholders = createDefaultPlaceholderDocs();

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
        // Start at 15 minutes for common, then +15 per tier.
        out.put("common", 15);
        out.put("uncommon", 30);
        out.put("rare", 45);
        out.put("epic", 60);
        out.put("legendary", 75);
        return out;
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

    private static String normalizeTierKey(String tier) {
        if (tier == null || tier.isBlank()) {
            return "common";
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

    private static EventBannerTemplates createDefaultEventBannerTemplates() {
        return new EventBannerTemplates();
    }

    private static EventBannerTemplates sanitizeEventBannerTemplates(EventBannerTemplates source) {
        EventBannerTemplates out = createDefaultEventBannerTemplates();
        if (source == null) {
            return out;
        }

        out.activeTitle = optional(source.activeTitle);
        if (out.activeTitle.isEmpty()) {
            out.activeTitle = DEFAULT_EVENT_ACTIVE_TITLE_TEMPLATE;
        }

        out.activeSubtitle = optional(source.activeSubtitle);
        if (out.activeSubtitle.isEmpty()) {
            out.activeSubtitle = DEFAULT_EVENT_ACTIVE_SUBTITLE_TEMPLATE;
        }

        out.victoryTitle = optional(source.victoryTitle);
        if (out.victoryTitle.isEmpty()) {
            out.victoryTitle = DEFAULT_EVENT_VICTORY_TITLE_TEMPLATE;
        }

        out.victorySubtitle = optional(source.victorySubtitle);
        if (out.victorySubtitle.isEmpty()) {
            out.victorySubtitle = DEFAULT_EVENT_VICTORY_SUBTITLE_TEMPLATE;
        }

        return out;
    }

    private static TimedMapMarkerSettings createDefaultTimedMapMarkerSettings() {
        return new TimedMapMarkerSettings();
    }

    private static TimedMapMarkerSettings sanitizeTimedMapMarkerSettings(TimedMapMarkerSettings source) {
        TimedMapMarkerSettings out = createDefaultTimedMapMarkerSettings();
        if (source == null) {
            return out;
        }

        out.enabled = source.enabled;
        out.markerImage = optional(source.markerImage);
        if (out.markerImage.isEmpty()) {
            out.markerImage = DEFAULT_TIMED_MAP_MARKER_IMAGE;
        }
        out.nameTemplate = optional(source.nameTemplate);
        if (out.nameTemplate.isEmpty()) {
            out.nameTemplate = DEFAULT_TIMED_MAP_MARKER_NAME_TEMPLATE;
        }
        return out;
    }

    private static PlaceholderDocs createDefaultPlaceholderDocs() {
        return new PlaceholderDocs();
    }

    private static PlaceholderDocs sanitizePlaceholderDocs(PlaceholderDocs source) {
        PlaceholderDocs out = createDefaultPlaceholderDocs();
        if (source == null) {
            return out;
        }
        out.note = optional(source.note);
        if (out.note.isEmpty()) {
            out.note = "Documentation-only placeholders reference. Safe to edit/remove; BossArena does not read this block.";
        }
        out.eventBanner = sanitizeDocsMap(source.eventBanner, createDefaultEventBannerPlaceholderDocs());
        out.timedAnnouncement = sanitizeDocsMap(source.timedAnnouncement, createDefaultTimedAnnouncementPlaceholderDocs());
        out.timedMapMarker = sanitizeDocsMap(source.timedMapMarker, createDefaultTimedMapMarkerPlaceholderDocs());
        return out;
    }

    private static Map<String, String> sanitizeDocsMap(Map<String, String> source, Map<String, String> fallback) {
        Map<String, String> out = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, String> entry : source.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                String key = entry.getKey().trim();
                if (key.isEmpty()) {
                    continue;
                }
                out.put(key, optional(entry.getValue()));
            }
        }
        if (out.isEmpty()) {
            out.putAll(fallback);
        }
        return out;
    }

    private static Map<String, String> createDefaultEventBannerPlaceholderDocs() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("$Boss / {Boss}", "Boss display name.");
        out.put("$BossUpper / {BossUpper}", "Boss display name in uppercase.");
        out.put("$BossAlive / {BossAlive}", "Alive tracked boss count.");
        out.put("$AddsAlive / {AddsAlive}", "Alive tracked wave/add mob count.");
        out.put("$Context / {Context}", "Optional context text (for example wave status).");
        out.put("$ContextLine / {ContextLine}", "Context text with trailing ' | ' when context exists.");
        out.put("$Countdown / {Countdown}", "Remaining timer as MM:SS (blank when no timer).");
        out.put("$CountdownLabel / {CountdownLabel}", "Time left label (for example 'Time left: 14:22').");
        out.put("$CountdownLine / {CountdownLine}", "Countdown label with trailing ' | ' when timer exists.");
        out.put("$State / {State}", "Event state: active or victory.");
        out.put("Legacy aliases", "$ContextPrefix and $CountdownPrefix are still supported.");
        return out;
    }

    private static Map<String, String> createDefaultTimedAnnouncementPlaceholderDocs() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("$Boss / {Boss}", "Boss display name.");
        out.put("$Arena / {Arena}", "Arena id/name.");
        out.put("$World / {World}", "World name.");
        return out;
    }

    private static Map<String, String> createDefaultTimedMapMarkerPlaceholderDocs() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("$Boss / {Boss}", "Boss display name.");
        out.put("$Arena / {Arena}", "Arena id/name.");
        out.put("$World / {World}", "World name when available.");
        return out;
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

    public int getBossTierCountdownMinutes(String tier) {
        Map<String, Integer> timers = sanitizeBossTierCountdownMinutes(bossTierCountdownMinutes);
        this.bossTierCountdownMinutes = timers;
        String key = normalizeTierKey(tier);
        Integer configured = timers.get(key);
        if (configured == null) {
            configured = timers.get("common");
        }
        if (configured == null) {
            configured = 15;
        }
        return Math.max(MIN_COUNTDOWN_MINUTES, configured);
    }

    private void applyLoadedConfig(BossArenaConfig loaded) {
        this.arenas = loaded.arenas != null ? loaded.arenas : new ArenaDef[0];
        this.notificationRadius = sanitizeNotificationRadius(loaded.notificationRadius);
        this.currencyItemId = sanitizeItemId(loaded.currencyItemId, DEFAULT_CURRENCY_ITEM_ID);
        this.fallbackCurrencyItemId = sanitizeItemId(
                loaded.fallbackCurrencyItemId,
                DEFAULT_FALLBACK_CURRENCY_ITEM_ID
        );
        this.bossTierCountdownMinutes = sanitizeBossTierCountdownMinutes(loaded.bossTierCountdownMinutes);
        this.eventBanner = sanitizeEventBannerTemplates(loaded.eventBanner);
        this.timedMapMarker = sanitizeTimedMapMarkerSettings(loaded.timedMapMarker);
        this.timedBossSpawns = sanitizeTimedBossSpawns(loaded.timedBossSpawns);
        this._comment_placeholders = sanitizePlaceholderDocs(loaded._comment_placeholders);
    }

    private static double sanitizeNotificationRadius(double value) {
        if (!Double.isFinite(value) || value < MIN_NOTIFICATION_RADIUS) {
            return DEFAULT_NOTIFICATION_RADIUS;
        }
        return Math.min(value, MAX_NOTIFICATION_RADIUS);
    }

    /** Returns the configured notification radius (blocks), clamped to valid range. */
    public double getNotificationRadius() {
        return sanitizeNotificationRadius(notificationRadius);
    }

    private void applyDefaultConfig() {
        this.arenas = new ArenaDef[0];
        this.notificationRadius = DEFAULT_NOTIFICATION_RADIUS;
        this.currencyItemId = DEFAULT_CURRENCY_ITEM_ID;
        this.fallbackCurrencyItemId = DEFAULT_FALLBACK_CURRENCY_ITEM_ID;
        this.bossTierCountdownMinutes = createDefaultBossTierCountdownMinutes();
        this.eventBanner = createDefaultEventBannerTemplates();
        this.timedMapMarker = createDefaultTimedMapMarkerSettings();
        this.timedBossSpawns = new ArrayList<>();
        this._comment_placeholders = createDefaultPlaceholderDocs();
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

    public static final class EventBannerTemplates {
        public String activeTitle = DEFAULT_EVENT_ACTIVE_TITLE_TEMPLATE;
        public String activeSubtitle = DEFAULT_EVENT_ACTIVE_SUBTITLE_TEMPLATE;
        public String victoryTitle = DEFAULT_EVENT_VICTORY_TITLE_TEMPLATE;
        public String victorySubtitle = DEFAULT_EVENT_VICTORY_SUBTITLE_TEMPLATE;
    }

    public static final class TimedMapMarkerSettings {
        public boolean enabled = true;
        public String markerImage = DEFAULT_TIMED_MAP_MARKER_IMAGE;
        public String nameTemplate = DEFAULT_TIMED_MAP_MARKER_NAME_TEMPLATE;
    }

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

    public static final class PlaceholderDocs {
        public String note = "Documentation-only placeholders reference. Safe to edit/remove; BossArena does not read this block.";
        public Map<String, String> eventBanner = createDefaultEventBannerPlaceholderDocs();
        public Map<String, String> timedAnnouncement = createDefaultTimedAnnouncementPlaceholderDocs();
        public Map<String, String> timedMapMarker = createDefaultTimedMapMarkerPlaceholderDocs();
    }
}
