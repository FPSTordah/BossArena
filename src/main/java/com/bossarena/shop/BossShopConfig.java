package com.bossarena.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class BossShopConfig {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_VISIBLE_SLOTS_PER_TIER = 4;
    private static final String DEFAULT_CURRENCY_PROVIDER = "auto";
    private static final String DEFAULT_FALLBACK_ITEM_CURRENCY_ID = "Ingredient_Bar_Iron";

    public String currencyProvider = DEFAULT_CURRENCY_PROVIDER;
    public String currencyItemId = "";
    public List<ShopEntry> entries = new ArrayList<>();
    public List<ShopTableLocation> tableLocations = new ArrayList<>();
    public Map<String, Integer> visibleSlotsByTier = createDefaultVisibleSlotsByTier();

    public void load(Path path) {
        if (path == null) {
            currencyProvider = DEFAULT_CURRENCY_PROVIDER;
            currencyItemId = "";
            entries = createDefaultEntries();
            visibleSlotsByTier = createDefaultVisibleSlotsByTier();
            return;
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (Files.notExists(path)) {
                currencyProvider = DEFAULT_CURRENCY_PROVIDER;
                currencyItemId = "";
                entries = createDefaultEntries();
                visibleSlotsByTier = createDefaultVisibleSlotsByTier();
                save(path);
                LOGGER.info("Created default shop config at " + path.toAbsolutePath());
                return;
            }

            String raw = Files.readString(path, StandardCharsets.UTF_8);
            BossShopConfig parsed = GSON.fromJson(raw, BossShopConfig.class);

            if (parsed == null) {
                currencyProvider = DEFAULT_CURRENCY_PROVIDER;
                currencyItemId = "";
                entries = createDefaultEntries();
                visibleSlotsByTier = createDefaultVisibleSlotsByTier();
                save(path);
                LOGGER.warning("shop.json was empty; regenerated defaults.");
                return;
            }

            currencyProvider = sanitizeCurrencyProvider(parsed.currencyProvider);
            currencyItemId = sanitizeCurrencyItemId(parsed.currencyItemId);
            entries = sanitizeEntries(parsed.entries);
            tableLocations = sanitizeTableLocations(parsed.tableLocations);
            if (entries.isEmpty()) {
                entries = createDefaultEntries();
            } else {
                ensurePreviewEntriesEnabled(entries);
            }
            visibleSlotsByTier = sanitizeVisibleSlotsByTier(parsed.visibleSlotsByTier);
        } catch (Exception e) {
            LOGGER.warning("Failed to load shop config: " + e.getMessage());
            currencyProvider = DEFAULT_CURRENCY_PROVIDER;
            currencyItemId = "";
            entries = createDefaultEntries();
            tableLocations = new ArrayList<>();
            visibleSlotsByTier = createDefaultVisibleSlotsByTier();
            try {
                save(path);
            } catch (Exception ignored) {
                // best effort only
            }
        }
    }

    public void save(Path path) throws IOException {
        if (path == null) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    private static List<ShopEntry> sanitizeEntries(List<ShopEntry> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        List<ShopEntry> out = new ArrayList<>();
        for (ShopEntry entry : source) {
            if (entry == null) {
                continue;
            }
            if (!BossShopItems.isValidTier(entry.tier) || entry.slot <= 0) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    private static List<ShopTableLocation> sanitizeTableLocations(List<ShopTableLocation> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        List<ShopTableLocation> out = new ArrayList<>();
        for (ShopTableLocation entry : source) {
            if (entry == null) {
                continue;
            }
            String world = entry.worldName != null ? entry.worldName.trim() : "";
            if (world.isEmpty()) {
                continue;
            }
            if (containsLocation(out, world, entry.x, entry.y, entry.z)) {
                continue;
            }
            ShopTableLocation normalized = new ShopTableLocation();
            normalized.worldName = world;
            normalized.x = entry.x;
            normalized.y = entry.y;
            normalized.z = entry.z;
            normalized.arenaId = sanitizeArenaId(entry.arenaId);
            normalized.enabledBossIds = sanitizeBossIds(entry.enabledBossIds);
            out.add(normalized);
        }
        return out;
    }

    private static String sanitizeArenaId(String source) {
        return source == null ? "" : source.trim();
    }

    private static List<String> sanitizeBossIds(List<String> source) {
        List<String> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        for (String bossId : source) {
            if (bossId == null) {
                continue;
            }
            String trimmed = bossId.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (String existing : out) {
                if (existing.equalsIgnoreCase(trimmed)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static Map<String, Integer> sanitizeVisibleSlotsByTier(Map<String, Integer> source) {
        Map<String, Integer> out = createDefaultVisibleSlotsByTier();
        if (source == null || source.isEmpty()) {
            return out;
        }

        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            if (entry == null || !BossShopItems.isValidTier(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            out.put(
                    entry.getKey().toLowerCase(),
                    clampVisibleSlots(entry.getValue())
            );
        }
        return out;
    }

    private static Map<String, Integer> createDefaultVisibleSlotsByTier() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String tier : BossShopItems.TIER_ORDER) {
            out.put(tier, DEFAULT_VISIBLE_SLOTS_PER_TIER);
        }
        return out;
    }

    private static int clampVisibleSlots(int value) {
        return Math.max(1, Math.min(value, BossShopItems.SLOTS_PER_TIER));
    }

    private static String sanitizeCurrencyProvider(String value) {
        return ShopCurrencySupport.sanitizeProvider(value);
    }

    private static String sanitizeCurrencyItemId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeFallbackCurrencyItemId(String value) {
        String sanitized = sanitizeCurrencyItemId(value);
        return sanitized.isBlank() ? DEFAULT_FALLBACK_ITEM_CURRENCY_ID : sanitized;
    }

    public boolean applyRuntimeCurrencyDetection(String fallbackCurrencyItemId) {
        boolean changed = false;
        String currentProvider = sanitizeCurrencyProvider(currencyProvider);
        if (!currentProvider.equals(currencyProvider)) {
            currencyProvider = currentProvider;
            changed = true;
        }

        String normalizedCurrencyItemId = sanitizeCurrencyItemId(currencyItemId);
        if (!normalizedCurrencyItemId.equals(currencyItemId)) {
            currencyItemId = normalizedCurrencyItemId;
            changed = true;
        }

        if (!ShopCurrencySupport.PROVIDER_AUTO.equals(currentProvider)) {
            return changed;
        }

        String detectedProvider = ShopCurrencySupport.resolveAutoProvider();
        if (ShopCurrencySupport.PROVIDER_ITEM.equals(detectedProvider) && currencyItemId.isBlank()) {
            String fallback = sanitizeFallbackCurrencyItemId(fallbackCurrencyItemId);
            if (!fallback.equals(currencyItemId)) {
                currencyItemId = fallback;
                changed = true;
            }
        }

        return changed;
    }

    private static List<ShopEntry> createDefaultEntries() {
        List<ShopEntry> defaults = new ArrayList<>();

        for (String tier : BossShopItems.TIER_ORDER) {
            for (int slot = 1; slot <= BossShopItems.SLOTS_PER_TIER; slot++) {
                ShopEntry entry = new ShopEntry();
                entry.tier = tier;
                entry.slot = slot;
                entry.enabled = slot <= 2;
                entry.cost = tierBaseCost(tier) + (slot * 25);
                entry.displayName = BossShopItems.displayTier(tier) + " Boss Contract " + slot;
                entry.description = "Configure table arena in /ba config and bossId in mods/BossArena/shop.json. Cost uses copper (100c=1s, 10,000c=1g).";
                entry.arenaId = "";
                entry.bossId = "";
                entry.icon = "Boss_Shop_" + BossShopItems.displayTier(tier) + "_" + Math.min(slot, 4);
                defaults.add(entry);
            }
        }

        return defaults;
    }

    private static void ensurePreviewEntriesEnabled(List<ShopEntry> entries) {
        boolean anyEnabled = false;
        for (ShopEntry entry : entries) {
            if (entry != null && entry.enabled) {
                anyEnabled = true;
                break;
            }
        }
        if (anyEnabled) {
            return;
        }

        int enabledCount = 0;
        for (String tier : BossShopItems.TIER_ORDER) {
            for (ShopEntry entry : entries) {
                if (entry == null || entry.tier == null) {
                    continue;
                }
                if (tier.equalsIgnoreCase(entry.tier) && entry.slot == 1) {
                    entry.enabled = true;
                    enabledCount++;
                    break;
                }
            }
            if (enabledCount >= 3) {
                return;
            }
        }

        for (ShopEntry entry : entries) {
            if (entry == null || entry.enabled) {
                continue;
            }
            entry.enabled = true;
            enabledCount++;
            if (enabledCount >= 3) {
                return;
            }
        }
    }

    private static int tierBaseCost(String tier) {
        return switch (tier) {
            case "uncommon" -> 100;
            case "common" -> 250;
            case "rare" -> 500;
            case "epic" -> 900;
            case "legendary" -> 1500;
            default -> 100;
        };
    }

    public boolean recordTableLocation(String worldName, int x, int y, int z) {
        String world = worldName != null ? worldName.trim() : "";
        if (world.isEmpty()) {
            return false;
        }
        if (tableLocations == null) {
            tableLocations = new ArrayList<>();
        }
        if (containsLocation(tableLocations, world, x, y, z)) {
            return false;
        }

        ShopTableLocation location = new ShopTableLocation();
        location.worldName = world;
        location.x = x;
        location.y = y;
        location.z = z;
        location.arenaId = "";
        location.enabledBossIds = new ArrayList<>();
        tableLocations.add(location);
        return true;
    }

    public ShopTableLocation getTableLocation(String worldName, int x, int y, int z) {
        if (tableLocations == null || tableLocations.isEmpty()) {
            return null;
        }
        for (ShopTableLocation location : tableLocations) {
            if (location == null || location.worldName == null) {
                continue;
            }
            if (location.worldName.equalsIgnoreCase(worldName)
                    && location.x == x
                    && location.y == y
                    && location.z == z) {
                location.arenaId = sanitizeArenaId(location.arenaId);
                if (location.enabledBossIds == null) {
                    location.enabledBossIds = new ArrayList<>();
                } else {
                    location.enabledBossIds = sanitizeBossIds(location.enabledBossIds);
                }
                return location;
            }
        }
        return null;
    }

    public int removeTableLocationsByPosition(int x, int y, int z) {
        if (tableLocations == null || tableLocations.isEmpty()) {
            return 0;
        }

        int before = tableLocations.size();
        tableLocations.removeIf(location ->
                location != null
                        && location.x == x
                        && location.y == y
                        && location.z == z
        );
        return before - tableLocations.size();
    }

    private static boolean containsLocation(List<ShopTableLocation> list, String worldName, int x, int y, int z) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (ShopTableLocation existing : list) {
            if (existing == null || existing.worldName == null) {
                continue;
            }
            if (existing.worldName.equalsIgnoreCase(worldName)
                    && existing.x == x
                    && existing.y == y
                    && existing.z == z) {
                return true;
            }
        }
        return false;
    }

    public static final class ShopTableLocation {
        public String worldName = "";
        public int x;
        public int y;
        public int z;
        public String arenaId = "";
        public List<String> enabledBossIds = new ArrayList<>();

        @Override
        public String toString() {
            return worldName + ":" + x + "," + y + "," + z;
        }
    }
}
