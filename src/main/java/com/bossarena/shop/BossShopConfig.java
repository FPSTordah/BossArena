package com.bossarena.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class BossShopConfig {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_CURRENCY_PROVIDER = "auto";
    private static final String DEFAULT_FALLBACK_ITEM_CURRENCY_ID = "Ingredient_Bar_Iron";
    private static final String DEFAULT_SHOP_NPC_ID = "bossarena_shop_guard";

    public String currencyProvider = DEFAULT_CURRENCY_PROVIDER;
    public String currencyItemId = "";
    public String shopNpcId = DEFAULT_SHOP_NPC_ID;
    public boolean strictContractPricing = false;
    public List<ShopEntry> entries = new ArrayList<>();
    public List<ShopLocation> shops = new ArrayList<>();

    private static JsonObject parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(raw);
            if (root != null && root.isJsonObject()) {
                return root.getAsJsonObject();
            }
        } catch (Exception ignored) {
            // malformed JSON handled by caller fallback
        }
        return null;
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

    private static List<ShopLocation> sanitizeShops(List<ShopLocation> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }

        List<ShopLocation> out = new ArrayList<>();
        for (ShopLocation entry : source) {
            if (entry == null) {
                continue;
            }

            String world = optional(entry.worldName);
            if (world.isEmpty()) {
                continue;
            }

            ShopLocation normalized = new ShopLocation();
            normalized.uuid = sanitizeUuid(entry.uuid);
            normalized.name = sanitizeShopName(entry.name);
            normalized.worldName = world;
            normalized.x = entry.x;
            normalized.y = entry.y;
            normalized.z = entry.z;
            normalized.arenaId = sanitizeArenaId(entry.arenaId);
            normalized.enabledBossIds = sanitizeBossIds(entry.enabledBossIds);
            normalized.contractPrices = sanitizeContractPrices(entry.contractPrices);
            if (normalized.name.isEmpty()) {
                normalized.name = defaultShopName(normalized.uuid, normalized.x, normalized.y, normalized.z);
            }

            ShopLocation existingByUuid = findByUuid(out, normalized.uuid);
            if (existingByUuid != null) {
                mergeShopLocation(existingByUuid, normalized);
                continue;
            }

            ShopLocation existingByLocation = findByLocation(out, world, normalized.x, normalized.y, normalized.z);
            if (existingByLocation != null) {
                mergeShopLocation(existingByLocation, normalized);
                continue;
            }

            out.add(normalized);
        }
        return out;
    }

    private static boolean applyLegacyNpcUuids(JsonObject rawRoot, List<ShopLocation> locations) {
        if (rawRoot == null || locations == null || locations.isEmpty() || !rawRoot.has("shopNpcUuids")) {
            return false;
        }
        JsonElement element = rawRoot.get("shopNpcUuids");
        if (element == null || !element.isJsonArray()) {
            return false;
        }

        List<String> legacyUuids = sanitizeShopNpcUuidsFromJson(element.getAsJsonArray());
        if (legacyUuids.isEmpty()) {
            return false;
        }

        boolean changed = false;
        int max = Math.min(locations.size(), legacyUuids.size());
        for (int i = 0; i < max; i++) {
            ShopLocation location = locations.get(i);
            if (location == null) {
                continue;
            }

            String uuid = legacyUuids.get(i);
            if (uuid.isEmpty()) {
                continue;
            }

            if (optional(location.uuid).isEmpty()) {
                location.uuid = uuid;
                changed = true;
            }
            if (optional(location.name).isEmpty()) {
                location.name = defaultShopName(location.uuid, location.x, location.y, location.z);
                changed = true;
            }
        }
        return changed;
    }

    private static List<String> sanitizeShopNpcUuidsFromJson(JsonArray source) {
        List<String> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        for (JsonElement element : source) {
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                continue;
            }
            String uuid = sanitizeUuid(element.getAsString());
            if (uuid.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (String existing : out) {
                if (existing.equalsIgnoreCase(uuid)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                out.add(uuid);
            }
        }
        return out;
    }

    private static void mergeShopLocation(ShopLocation target, ShopLocation source) {
        if (target == null || source == null) {
            return;
        }
        if (optional(target.uuid).isEmpty() && !optional(source.uuid).isEmpty()) {
            target.uuid = source.uuid;
        }
        if (optional(target.name).isEmpty() && !optional(source.name).isEmpty()) {
            target.name = source.name;
        }
        if (optional(target.worldName).isEmpty() && !optional(source.worldName).isEmpty()) {
            target.worldName = source.worldName;
            target.x = source.x;
            target.y = source.y;
            target.z = source.z;
        }
        if (optional(target.arenaId).isEmpty() && !optional(source.arenaId).isEmpty()) {
            target.arenaId = source.arenaId;
        }
        if ((target.enabledBossIds == null || target.enabledBossIds.isEmpty())
                && source.enabledBossIds != null && !source.enabledBossIds.isEmpty()) {
            target.enabledBossIds = sanitizeBossIds(source.enabledBossIds);
        }
        if ((target.contractPrices == null || target.contractPrices.isEmpty())
                && source.contractPrices != null && !source.contractPrices.isEmpty()) {
            target.contractPrices = sanitizeContractPrices(source.contractPrices);
        }

        if (optional(target.name).isEmpty()) {
            target.name = defaultShopName(target.uuid, target.x, target.y, target.z);
        }
        target.arenaId = sanitizeArenaId(target.arenaId);
        if (target.enabledBossIds == null) {
            target.enabledBossIds = new ArrayList<>();
        } else {
            target.enabledBossIds = sanitizeBossIds(target.enabledBossIds);
        }
        if (target.contractPrices == null) {
            target.contractPrices = new ArrayList<>();
        } else {
            target.contractPrices = sanitizeContractPrices(target.contractPrices);
        }
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

    private static List<ContractPrice> sanitizeContractPrices(List<ContractPrice> source) {
        List<ContractPrice> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        for (ContractPrice raw : source) {
            if (raw == null) {
                continue;
            }
            String bossId = optional(raw.bossId);
            if (bossId.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (ContractPrice existing : out) {
                if (existing != null && bossId.equalsIgnoreCase(optional(existing.bossId))) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            ContractPrice clean = new ContractPrice();
            clean.bossId = bossId;
            clean.cost = Math.max(0, raw.cost);
            out.add(clean);
        }
        return out;
    }

    private static String sanitizeCurrencyProvider(String value) {
        return ShopCurrencySupport.sanitizeProvider(value);
    }

    private static String sanitizeCurrencyItemId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeShopNpcId(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_SHOP_NPC_ID;
        }
        return value.trim();
    }

    private static String sanitizeUuid(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeShopName(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultShopName(String uuid, int x, int y, int z) {
        String cleanedUuid = optional(uuid);
        if (!cleanedUuid.isEmpty()) {
            String shortId = cleanedUuid.length() > 8 ? cleanedUuid.substring(0, 8) : cleanedUuid;
            return "Shop " + shortId;
        }
        return "Shop " + x + "," + y + "," + z;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeFallbackCurrencyItemId(String value) {
        String sanitized = sanitizeCurrencyItemId(value);
        return sanitized.isBlank() ? DEFAULT_FALLBACK_ITEM_CURRENCY_ID : sanitized;
    }

    private static List<ShopEntry> createDefaultEntries() {
        List<ShopEntry> defaults = new ArrayList<>();

        for (String tier : BossShopItems.TIER_ORDER) {
            ShopEntry entry = new ShopEntry();
            entry.tier = tier;
            entry.slot = 1;
            entry.enabled = true;
            entry.cost = tierBaseCost(tier) + 25;
            entry.displayName = BossShopItems.displayTier(tier) + " Boss Contract";
            entry.description = "Configure shop arena in /ba config and bossId in mods/BossArena/shop.json. Cost uses copper (100c=1s, 10,000c=1g).";
            entry.arenaId = "";
            entry.bossId = "";
            entry.icon = "Boss_Shop_" + BossShopItems.displayTier(tier) + "_1";
            defaults.add(entry);
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
            case "common" -> 100;
            case "uncommon" -> 250;
            case "rare" -> 500;
            case "epic" -> 900;
            case "legendary" -> 1500;
            default -> 100;
        };
    }

    private static ShopLocation findByUuid(List<ShopLocation> list, String uuid) {
        String normalizedUuid = sanitizeUuid(uuid);
        if (list == null || list.isEmpty() || normalizedUuid.isEmpty()) {
            return null;
        }
        for (ShopLocation existing : list) {
            if (existing == null) {
                continue;
            }
            if (normalizedUuid.equalsIgnoreCase(optional(existing.uuid))) {
                return existing;
            }
        }
        return null;
    }

    private static ShopLocation findByLocation(List<ShopLocation> list, String worldName, int x, int y, int z) {
        if (list == null || list.isEmpty() || worldName == null || worldName.isBlank()) {
            return null;
        }
        for (ShopLocation existing : list) {
            if (existing == null || existing.worldName == null) {
                continue;
            }
            if (existing.worldName.equalsIgnoreCase(worldName)
                    && existing.x == x
                    && existing.y == y
                    && existing.z == z) {
                return existing;
            }
        }
        return null;
    }

    private static boolean contractPriceListsEqual(List<ContractPrice> left, List<ContractPrice> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            ContractPrice l = left.get(i);
            ContractPrice r = right.get(i);
            String lb = optional(l != null ? l.bossId : null);
            String rb = optional(r != null ? r.bossId : null);
            int lc = l != null ? Math.max(0, l.cost) : 0;
            int rc = r != null ? Math.max(0, r.cost) : 0;
            if (!lb.equalsIgnoreCase(rb) || lc != rc) {
                return false;
            }
        }
        return true;
    }

    public void load(Path path) {
        if (path == null) {
            currencyProvider = DEFAULT_CURRENCY_PROVIDER;
            currencyItemId = "";
            shopNpcId = DEFAULT_SHOP_NPC_ID;
            strictContractPricing = false;
            entries = createDefaultEntries();
            shops = new ArrayList<>();
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
                shopNpcId = DEFAULT_SHOP_NPC_ID;
                strictContractPricing = false;
                entries = createDefaultEntries();
                shops = new ArrayList<>();
                save(path);
                LOGGER.info("Created default shop config at " + path.toAbsolutePath());
                return;
            }

            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject rawRoot = parseJsonObject(raw);
            BossShopConfig parsed = GSON.fromJson(raw, BossShopConfig.class);

            if (parsed == null) {
                currencyProvider = DEFAULT_CURRENCY_PROVIDER;
                currencyItemId = "";
                shopNpcId = DEFAULT_SHOP_NPC_ID;
                strictContractPricing = false;
                entries = createDefaultEntries();
                shops = new ArrayList<>();
                save(path);
                LOGGER.warning("shop.json was empty; regenerated defaults.");
                return;
            }

            currencyProvider = sanitizeCurrencyProvider(parsed.currencyProvider);
            currencyItemId = sanitizeCurrencyItemId(parsed.currencyItemId);
            shopNpcId = sanitizeShopNpcId(parsed.shopNpcId);
            strictContractPricing = parsed.strictContractPricing;
            entries = sanitizeEntries(parsed.entries);
            shops = sanitizeShops(parsed.shops);
            if (entries.isEmpty()) {
                entries = createDefaultEntries();
            } else {
                ensurePreviewEntriesEnabled(entries);
            }

            boolean migratedLegacyUuids = applyLegacyNpcUuids(rawRoot, shops);
            if (migratedLegacyUuids) {
                save(path);
                LOGGER.info("Migrated legacy shop fields into shops list.");
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to load shop config: " + e.getMessage());
            currencyProvider = DEFAULT_CURRENCY_PROVIDER;
            currencyItemId = "";
            shopNpcId = DEFAULT_SHOP_NPC_ID;
            strictContractPricing = false;
            entries = createDefaultEntries();
            shops = new ArrayList<>();
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

    public boolean recordShopNpcUuid(String uuid) {
        String normalizedUuid = sanitizeUuid(uuid);
        if (normalizedUuid.isEmpty() || shops == null || shops.isEmpty()) {
            return false;
        }

        for (ShopLocation location : shops) {
            if (location != null && normalizedUuid.equalsIgnoreCase(optional(location.uuid))) {
                return false;
            }
        }

        for (ShopLocation location : shops) {
            if (location == null) {
                continue;
            }
            if (optional(location.uuid).isEmpty()) {
                location.uuid = normalizedUuid;
                if (optional(location.name).isEmpty()) {
                    location.name = defaultShopName(location.uuid, location.x, location.y, location.z);
                }
                return true;
            }
        }
        return false;
    }

    public boolean isShopNpcUuid(String uuid) {
        String normalizedUuid = sanitizeUuid(uuid);
        if (normalizedUuid.isEmpty() || shops == null || shops.isEmpty()) {
            return false;
        }
        for (ShopLocation location : shops) {
            if (location != null && normalizedUuid.equalsIgnoreCase(optional(location.uuid))) {
                return true;
            }
        }
        return false;
    }

    public boolean removeShopNpcUuid(String uuid) {
        String normalizedUuid = sanitizeUuid(uuid);
        if (normalizedUuid.isEmpty() || shops == null || shops.isEmpty()) {
            return false;
        }
        int before = shops.size();
        shops.removeIf(location ->
                location != null
                        && normalizedUuid.equalsIgnoreCase(optional(location.uuid))
        );
        return before != shops.size();
    }

    public List<String> getShopNpcUuids() {
        List<String> out = new ArrayList<>();
        if (shops == null || shops.isEmpty()) {
            return out;
        }
        for (ShopLocation location : shops) {
            if (location == null) {
                continue;
            }
            String uuid = sanitizeUuid(location.uuid);
            if (uuid.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (String existing : out) {
                if (existing.equalsIgnoreCase(uuid)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                out.add(uuid);
            }
        }
        return out;
    }

    public boolean recordShopLocation(String uuid, String worldName, int x, int y, int z) {
        return recordShopLocation(uuid, null, worldName, x, y, z);
    }

    public boolean recordShopLocation(String uuid, String name, String worldName, int x, int y, int z) {
        String world = optional(worldName);
        if (world.isEmpty()) {
            return false;
        }
        if (shops == null) {
            shops = new ArrayList<>();
        }

        String normalizedUuid = sanitizeUuid(uuid);
        String normalizedName = sanitizeShopName(name);
        boolean changed = false;

        ShopLocation byUuid = findByUuid(shops, normalizedUuid);
        ShopLocation byLocation = findByLocation(shops, world, x, y, z);

        ShopLocation target;
        if (byUuid != null) {
            target = byUuid;
            if (byLocation != null && byLocation != byUuid) {
                mergeShopLocation(target, byLocation);
                shops.remove(byLocation);
                changed = true;
            }
        } else if (byLocation != null) {
            target = byLocation;
        } else {
            target = new ShopLocation();
            target.worldName = world;
            target.x = x;
            target.y = y;
            target.z = z;
            target.arenaId = "";
            target.enabledBossIds = new ArrayList<>();
            target.contractPrices = new ArrayList<>();
            shops.add(target);
            changed = true;
        }

        if (!normalizedUuid.isEmpty() && !normalizedUuid.equalsIgnoreCase(optional(target.uuid))) {
            target.uuid = normalizedUuid;
            changed = true;
        }
        if (!normalizedName.isEmpty() && !normalizedName.equals(target.name)) {
            target.name = normalizedName;
            changed = true;
        }
        if (!world.equalsIgnoreCase(optional(target.worldName)) || target.x != x || target.y != y || target.z != z) {
            target.worldName = world;
            target.x = x;
            target.y = y;
            target.z = z;
            changed = true;
        }

        if (optional(target.name).isEmpty()) {
            String fallback = defaultShopName(target.uuid, target.x, target.y, target.z);
            if (!fallback.equals(target.name)) {
                target.name = fallback;
                changed = true;
            }
        }
        target.arenaId = sanitizeArenaId(target.arenaId);
        if (target.enabledBossIds == null) {
            target.enabledBossIds = new ArrayList<>();
            changed = true;
        } else {
            List<String> cleanedBossIds = sanitizeBossIds(target.enabledBossIds);
            if (!cleanedBossIds.equals(target.enabledBossIds)) {
                target.enabledBossIds = cleanedBossIds;
                changed = true;
            }
        }
        if (target.contractPrices == null) {
            target.contractPrices = new ArrayList<>();
            changed = true;
        } else {
            List<ContractPrice> cleanedContractPrices = sanitizeContractPrices(target.contractPrices);
            if (!contractPriceListsEqual(cleanedContractPrices, target.contractPrices)) {
                target.contractPrices = cleanedContractPrices;
                changed = true;
            }
        }
        return changed;
    }

    public ShopLocation getShopLocation(String worldName, int x, int y, int z) {
        if (shops == null || shops.isEmpty()) {
            return null;
        }
        ShopLocation location = findByLocation(shops, worldName, x, y, z);
        if (location == null) {
            return null;
        }

        location.uuid = sanitizeUuid(location.uuid);
        if (optional(location.name).isEmpty()) {
            location.name = defaultShopName(location.uuid, location.x, location.y, location.z);
        } else {
            location.name = sanitizeShopName(location.name);
        }
        location.arenaId = sanitizeArenaId(location.arenaId);
        if (location.enabledBossIds == null) {
            location.enabledBossIds = new ArrayList<>();
        } else {
            location.enabledBossIds = sanitizeBossIds(location.enabledBossIds);
        }
        if (location.contractPrices == null) {
            location.contractPrices = new ArrayList<>();
        } else {
            location.contractPrices = sanitizeContractPrices(location.contractPrices);
        }
        return location;
    }

    public int removeShopLocationsByPosition(int x, int y, int z) {
        if (shops == null || shops.isEmpty()) {
            return 0;
        }

        int before = shops.size();
        shops.removeIf(location ->
                location != null
                        && location.x == x
                        && location.y == y
                        && location.z == z
        );
        return before - shops.size();
    }

    public boolean removeShopLocation(String worldName, int x, int y, int z) {
        if (worldName == null || worldName.isBlank() || shops == null || shops.isEmpty()) {
            return false;
        }
        int before = shops.size();
        shops.removeIf(location ->
                location != null
                        && location.worldName != null
                        && location.worldName.equalsIgnoreCase(worldName)
                        && location.x == x
                        && location.y == y
                        && location.z == z
        );
        return before != shops.size();
    }

    public boolean removeNearestShopLocation(String worldName, int x, int y, int z, int maxDistanceBlocks) {
        if (worldName == null || worldName.isBlank() || shops == null || shops.isEmpty()) {
            return false;
        }
        if (maxDistanceBlocks < 0) {
            return false;
        }

        int nearestIndex = -1;
        int nearestDistanceSq = Integer.MAX_VALUE;
        int maxDistanceSq = maxDistanceBlocks * maxDistanceBlocks;

        for (int i = 0; i < shops.size(); i++) {
            ShopLocation location = shops.get(i);
            if (location == null || location.worldName == null || !location.worldName.equalsIgnoreCase(worldName)) {
                continue;
            }

            int dx = location.x - x;
            int dy = location.y - y;
            int dz = location.z - z;
            int distanceSq = (dx * dx) + (dy * dy) + (dz * dz);
            if (distanceSq > maxDistanceSq) {
                continue;
            }
            if (distanceSq < nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
                nearestIndex = i;
            }
        }

        if (nearestIndex < 0) {
            return false;
        }
        shops.remove(nearestIndex);
        return true;
    }

    public boolean hasShopLocationAt(int x, int y, int z) {
        if (shops == null || shops.isEmpty()) {
            return false;
        }
        for (ShopLocation location : shops) {
            if (location == null) {
                continue;
            }
            if (location.x == x && location.y == y && location.z == z) {
                return true;
            }
        }
        return false;
    }

    public static final class ShopLocation {
        public String uuid = "";
        public String name = "";
        public String worldName = "";
        public int x;
        public int y;
        public int z;
        public String arenaId = "";
        public List<String> enabledBossIds = new ArrayList<>();
        public List<ContractPrice> contractPrices = new ArrayList<>();

        @Override
        public String toString() {
            String id = optional(uuid);
            if (!id.isEmpty()) {
                return id + "@" + worldName + ":" + x + "," + y + "," + z;
            }
            return worldName + ":" + x + "," + y + "," + z;
        }
    }

    public static final class ContractPrice {
        public String bossId = "";
        public int cost = 0;
    }
}
