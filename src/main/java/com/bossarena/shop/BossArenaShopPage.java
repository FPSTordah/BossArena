package com.bossarena.shop;

import com.bossarena.BossArenaConfig;
import com.bossarena.BossArenaPlugin;
import com.bossarena.data.Arena;
import com.bossarena.data.ArenaRegistry;
import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class BossArenaShopPage extends InteractiveCustomUIPage<BossArenaShopPage.ShopEventData> {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final String LAYOUT = "Pages/BossArenaShopPage.ui";
    private static final int DEFAULT_VISIBLE_SLOTS_PER_TIER = 4;
    private static final String DEFAULT_TIER = BossShopItems.TIER_ORDER.length > 0
            ? BossShopItems.TIER_ORDER[0]
            : "common";


    private final BossArenaPlugin plugin;
    private final String tableWorldName;
    private final Integer tableX;
    private final Integer tableY;
    private final Integer tableZ;
    private String selectedTier;

    private BossArenaShopPage(PlayerRef playerRef,
                              BossArenaPlugin plugin,
                              String tier,
                              @Nullable String tableWorldName,
                              @Nullable Integer tableX,
                              @Nullable Integer tableY,
                              @Nullable Integer tableZ) {
        super(playerRef, CustomPageLifetime.CanDismiss, ShopEventData.CODEC);
        this.plugin = plugin;
        this.tableWorldName = tableWorldName;
        this.tableX = tableX;
        this.tableY = tableY;
        this.tableZ = tableZ;
        this.selectedTier = BossShopItems.isValidTier(tier) ? tier : DEFAULT_TIER;
    }

    public static void open(Ref<EntityStore> ref,
                            Store<EntityStore> store,
                            Player player,
                            BossArenaPlugin plugin) {
        openTier(ref, store, player, plugin, DEFAULT_TIER);
    }

    public static void openTier(Ref<EntityStore> ref,
                                Store<EntityStore> store,
                                Player player,
                                BossArenaPlugin plugin,
                                String tier) {
        openTierAtTable(ref, store, player, plugin, tier, null, null, null, null);
    }

    public static void openAtTable(Ref<EntityStore> ref,
                                   Store<EntityStore> store,
                                   Player player,
                                   BossArenaPlugin plugin,
                                   String tableWorldName,
                                   int tableX,
                                   int tableY,
                                   int tableZ) {
        openTierAtTable(ref, store, player, plugin, DEFAULT_TIER, tableWorldName, tableX, tableY, tableZ);
    }

    private static void openTierAtTable(Ref<EntityStore> ref,
                                        Store<EntityStore> store,
                                        Player player,
                                        BossArenaPlugin plugin,
                                        String tier,
                                        @Nullable String tableWorldName,
                                        @Nullable Integer tableX,
                                        @Nullable Integer tableY,
                                        @Nullable Integer tableZ) {
        if (player == null || plugin == null) {
            return;
        }

        @SuppressWarnings("removal")
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null) {
            LOGGER.warning("BossArena shop: PlayerRef is null, cannot open.");
            return;
        }

        String normalizedTier = tier != null ? tier.toLowerCase(Locale.ROOT) : DEFAULT_TIER;
        if (!BossShopItems.isValidTier(normalizedTier)) {
            normalizedTier = DEFAULT_TIER;
        }

        BossArenaShopPage page = new BossArenaShopPage(
                playerRef,
                plugin,
                normalizedTier,
                tableWorldName,
                tableX,
                tableY,
                tableZ
        );
        player.getPageManager().openCustomPage(ref, store, page);
    }

    private static String resolveShopArenaId(BossShopConfig.ShopLocation location,
                                             String worldName,
                                             int x,
                                             int y,
                                             int z) {
        String configuredArenaId = optional(location != null ? location.arenaId : null);
        if (!configuredArenaId.isEmpty()) {
            return configuredArenaId;
        }
        String nearestArenaId = findNearestArenaIdForTable(worldName, x, y, z);
        return nearestArenaId == null ? "" : nearestArenaId;
    }

    private static List<ShopEntry> resolveLegacyEntriesForTier(BossShopConfig config, String tier) {
        Map<String, ShopEntry> entries = buildEntriesMap(config);
        int visibleSlots = resolveVisibleSlotsForTier(config, entries, tier);
        List<ShopEntry> out = new ArrayList<>();
        for (int slot = 1; slot <= visibleSlots; slot++) {
            out.add(normalizeEntry(entries.get(BossShopItems.key(tier, slot)), tier, slot));
        }
        return out;
    }

    private static ShopEntry findConfiguredEntry(List<ShopEntry> entries, String bossId, String tier) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        for (ShopEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (!tier.equalsIgnoreCase(optional(entry.tier))) {
                continue;
            }
            if (bossId.equalsIgnoreCase(optional(entry.bossId))) {
                return entry;
            }
        }
        return null;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeTier(String input) {
        if (input == null) {
            return DEFAULT_TIER;
        }
        String tier = input.trim().toLowerCase(Locale.ROOT);
        return BossShopItems.isValidTier(tier) ? tier : DEFAULT_TIER;
    }

    private static Integer findLocationContractCost(BossShopConfig.ShopLocation location, String bossId) {
        if (location == null || location.contractPrices == null || location.contractPrices.isEmpty() || isBlank(bossId)) {
            return null;
        }
        for (BossShopConfig.ContractPrice entry : location.contractPrices) {
            if (entry == null || isBlank(entry.bossId)) {
                continue;
            }
            if (bossId.equalsIgnoreCase(entry.bossId)) {
                return Math.max(0, entry.cost);
            }
        }
        return null;
    }

    private static boolean isStrictContractPricing(BossShopConfig config) {
        return config != null && config.strictContractPricing;
    }

    private static int defaultCostForTier(String tier) {
        return switch (normalizeTier(tier)) {
            case "common" -> 250;
            case "rare" -> 500;
            case "epic" -> 900;
            case "legendary" -> 1500;
            default -> 100;
        };
    }

    private static String findNearestArenaIdForTable(String worldName, int x, int y, int z) {
        Arena best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Arena arena : ArenaRegistry.getAll()) {
            if (arena == null || arena.worldName == null) {
                continue;
            }
            if (!arena.worldName.equalsIgnoreCase(worldName)) {
                continue;
            }
            double dx = arena.x - x;
            double dy = arena.y - y;
            double dz = arena.z - z;
            double distanceSq = (dx * dx) + (dy * dy) + (dz * dz);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = arena;
            }
        }
        return best != null ? best.arenaId : null;
    }

    private static Map<String, ShopEntry> buildEntriesMap(BossShopConfig config) {
        Map<String, ShopEntry> map = new HashMap<>();
        List<ShopEntry> entries = config != null && config.entries != null ? config.entries : List.of();

        for (ShopEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (!BossShopItems.isValidTier(entry.tier) || entry.slot < 1) {
                continue;
            }
            map.putIfAbsent(BossShopItems.key(entry.tier, entry.slot), entry);
        }
        return map;
    }

    private static int resolveVisibleSlotsForTier(BossShopConfig config, Map<String, ShopEntry> entries, String tier) {
        int inferred = inferVisibleSlotsFromEntries(entries, tier);
        if (inferred > 0) {
            return clampVisibleSlots(inferred);
        }
        return DEFAULT_VISIBLE_SLOTS_PER_TIER;
    }

    private static int inferVisibleSlotsFromEntries(Map<String, ShopEntry> entries, String tier) {
        int max = 0;
        for (int slot = 1; slot <= BossShopItems.SLOTS_PER_TIER; slot++) {
            ShopEntry entry = entries.get(BossShopItems.key(tier, slot));
            if (entry == null) {
                continue;
            }
            if (entry.enabled || !isBlank(entry.bossId) || !isBlank(entry.arenaId)) {
                max = slot;
            }
        }
        return max;
    }

    private static int clampVisibleSlots(int value) {
        return Math.max(1, Math.min(value, BossShopItems.SLOTS_PER_TIER));
    }

    private static String buildCostText(int cost, String currencyItemId, String provider) {
        if (cost <= 0) {
            return "Price: Free";
        }
        if (ShopCurrencySupport.PROVIDER_HYMARKET.equals(provider)) {
            return "Price: " + ShopCurrencySupport.formatHyMarketCost(cost);
        }
        if (ShopCurrencySupport.PROVIDER_ECONOMY_SYSTEM.equals(provider)) {
            return "Price: " + ShopCurrencySupport.formatEconomySystemCost(cost);
        }
        if (currencyItemId == null || currencyItemId.isBlank()) {
            return "Price: " + cost;
        }
        return "Price: " + cost + " " + ItemNameResolver.resolveCommonName(currencyItemId);
    }

    private static ShopEntry normalizeEntry(ShopEntry source, String tier, int slot) {
        ShopEntry out = new ShopEntry();
        out.tier = tier;
        out.slot = slot;

        if (source != null) {
            out.enabled = source.enabled;
            out.bossId = source.bossId;
            out.arenaId = source.arenaId;
            out.cost = Math.max(source.cost, 0);
            out.displayName = source.displayName;
            out.description = source.description;
            out.icon = source.icon;
        } else {
            out.enabled = false;
            out.cost = 0;
            out.bossId = "";
            out.arenaId = "";
            out.icon = "";
        }

        if (isBlank(out.displayName)) {
            out.displayName = BossShopItems.displayTier(tier) + " Contract " + slot;
        }
        if (isBlank(out.description)) {
            out.description = "Summon boss " + slot + " from the " + BossShopItems.displayTier(tier) + " tier.";
        }
        if (out.bossId == null) {
            out.bossId = "";
        }
        if (out.arenaId == null) {
            out.arenaId = "";
        }

        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);

        String effectiveCurrencyProvider = resolveDisplayCurrencyProvider();
        String currencyItemId = ShopCurrencySupport.PROVIDER_ITEM.equals(effectiveCurrencyProvider)
                ? resolveItemCurrencyItemId()
                : null;
        cmd.set("#TitleLabel.Text", "Boss Arena // Elite Contract Board");
        cmd.set("#SubtitleLabel.Text", "Choose a tier and summon a boss");

        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));

        for (String tier : BossShopItems.TIER_ORDER) {
            String selector = "#TierBtn" + tier;
            String borderSelector = "#TierBorder" + tier;
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    selector,
                    EventData.of("Action", "tier_" + tier)
            );

            String title = BossShopItems.displayTier(tier);
            cmd.set(selector + ".Text", title);
            cmd.set(borderSelector + ".Visible", tier.equals(selectedTier));
        }

        List<ShopEntry> displayed = resolveDisplayedEntriesForTier(selectedTier);
        int visibleSlots = Math.min(BossShopItems.SLOTS_PER_TIER, Math.max(1, displayed.size()));
        boolean noDisplayedEntries = displayed.isEmpty();

        for (int slot = 1; slot <= BossShopItems.SLOTS_PER_TIER; slot++) {
            String suffix = Integer.toString(slot);
            boolean slotVisible = slot <= visibleSlots;
            cmd.set("#ItemCard" + suffix + ".Visible", slotVisible);
            if (!slotVisible) {
                cmd.set("#BuyBtn" + suffix + ".Visible", false);
                cmd.set("#BuyBtn" + suffix + ".Disabled", true);
                continue;
            }

            ShopEntry entry;
            if (noDisplayedEntries) {
                entry = new ShopEntry();
                entry.enabled = false;
                entry.displayName = "No Contracts";
                entry.description = "No bosses are enabled for this shop and tier.";
                entry.cost = 0;
                entry.bossId = "";
                entry.arenaId = "";
            } else {
                entry = displayed.get(slot - 1);
            }

            cmd.set("#ItemName" + suffix + ".Text", entry.displayName);
            cmd.set("#ItemPrice" + suffix + ".Text", buildCostText(entry.cost, currencyItemId, effectiveCurrencyProvider));
            cmd.set("#ItemDesc" + suffix + ".Text", entry.description);
            cmd.set("#ItemIcon" + suffix + ".Visible", false);

            boolean ready = !noDisplayedEntries && entry.enabled && !isBlank(entry.arenaId) && !isBlank(entry.bossId);
            boolean needsConfig = entry.enabled && !ready;
            cmd.set("#BuyBtn" + suffix + ".Visible", true);
            cmd.set("#BuyBtn" + suffix + ".Disabled", noDisplayedEntries || !entry.enabled || needsConfig);
            cmd.set("#BuyBtn" + suffix + ".Text", !entry.enabled ? "Locked" : (needsConfig ? "Please Configure" : "Summon"));

            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#BuyBtn" + suffix,
                    EventData.of("Action", "buy_" + suffix)
            );
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull ShopEventData data) {
        String action = data.action;
        if (action == null || action.isBlank()) {
            return;
        }

        if ("close".equals(action)) {
            close();
            return;
        }

        if (action.startsWith("tier_")) {
            String tier = action.substring("tier_".length()).toLowerCase(Locale.ROOT);
            if (BossShopItems.isValidTier(tier)) {
                selectedTier = tier;
                rebuild();
            }
            return;
        }

        if (action.startsWith("buy_")) {
            try {
                int slot = Integer.parseInt(action.substring("buy_".length()));
                handlePurchase(ref, store, slot);
            } catch (NumberFormatException ignored) {
                LOGGER.warning("BossArena shop: invalid buy action payload: " + action);
            }
        }
    }

    private void handlePurchase(Ref<EntityStore> ref, Store<EntityStore> store, int slot) {
        List<ShopEntry> displayed = resolveDisplayedEntriesForTier(selectedTier);
        if (displayed.isEmpty()) {
            playerRef.sendMessage(Message.raw("No bosses are enabled for this shop and tier."));
            return;
        }

        if (slot < 1 || slot > displayed.size()) {
            playerRef.sendMessage(Message.raw("Invalid shop slot."));
            return;
        }

        ShopEntry entry = displayed.get(slot - 1);

        if (!entry.enabled) {
            playerRef.sendMessage(Message.raw("That contract is locked."));
            return;
        }

        if (isBlank(entry.bossId) || isBlank(entry.arenaId)) {
            playerRef.sendMessage(Message.raw("This contract is not configured. Edit mods/BossArena/shop.json."));
            return;
        }

        new BossArenaShopPurchaseInteraction(entry.bossId, entry.arenaId, entry.cost).run(store, ref, playerRef);
        close();
    }

    private List<ShopEntry> resolveDisplayedEntriesForTier(String tier) {
        BossShopConfig config = plugin.getShopConfig();
        if (config == null) {
            return List.of();
        }

        if (tableWorldName != null && tableX != null && tableY != null && tableZ != null) {
            BossShopConfig.ShopLocation location = config.getShopLocation(tableWorldName, tableX, tableY, tableZ);
            if (location != null && location.enabledBossIds != null) {
                String tableArenaId = resolveShopArenaId(location, tableWorldName, tableX, tableY, tableZ);
                List<ShopEntry> tableEntries = new ArrayList<>();
                for (String bossId : location.enabledBossIds) {
                    BossDefinition boss = BossRegistry.get(bossId);
                    if (boss == null) {
                        continue;
                    }
                    String bossTier = normalizeTier(boss.tier);
                    if (!bossTier.equalsIgnoreCase(tier)) {
                        continue;
                    }

                    ShopEntry configured = findConfiguredEntry(config.entries, boss.bossName, tier);
                    Integer locationCost = findLocationContractCost(location, boss.bossName);
                    if (locationCost == null) {
                        locationCost = findLocationContractCost(location, bossId);
                    }
                    Integer configuredCost = configured != null ? Math.max(0, configured.cost) : null;
                    boolean missingConfiguredCost = locationCost == null && configuredCost == null;
                    ShopEntry out = new ShopEntry();
                    out.enabled = true;
                    out.tier = tier;
                    out.slot = tableEntries.size() + 1;
                    out.bossId = boss.bossName;
                    out.arenaId = tableArenaId;
                    if (locationCost != null) {
                        out.cost = locationCost;
                    } else if (configuredCost != null) {
                        out.cost = configuredCost;
                    } else if (isStrictContractPricing(config)) {
                        out.cost = 0;
                        out.enabled = false;
                    } else {
                        out.cost = defaultCostForTier(tier);
                    }
                    out.displayName = configured != null && !isBlank(configured.displayName)
                            ? configured.displayName
                            : boss.bossName;
                    out.description = configured != null && !isBlank(configured.description)
                            ? configured.description
                            : (isBlank(out.arenaId)
                            ? "Summon " + boss.bossName + ". Configure the shop arena in /ba config."
                            : "Summon " + boss.bossName + " at " + out.arenaId);
                    if (missingConfiguredCost && isStrictContractPricing(config)) {
                        out.description = "Set price in mods/BossArena/shop.json shops[].contractPrices[] for " + boss.bossName + ".";
                    }
                    out.icon = configured != null ? configured.icon : "";

                    tableEntries.add(out);
                    if (tableEntries.size() >= BossShopItems.SLOTS_PER_TIER) {
                        break;
                    }
                }
                return tableEntries;
            }
        }

        return resolveLegacyEntriesForTier(config, tier);
    }

    private String resolveItemCurrencyItemId() {
        BossShopConfig config = plugin.getShopConfig();
        if (config != null && config.currencyItemId != null && !config.currencyItemId.isBlank()) {
            return config.currencyItemId;
        }
        BossArenaConfig global = plugin.getConfigHandle();
        if (global != null && global.currencyItemId != null && !global.currencyItemId.isBlank()) {
            return global.currencyItemId;
        }
        if (global != null && global.fallbackCurrencyItemId != null && !global.fallbackCurrencyItemId.isBlank()) {
            return global.fallbackCurrencyItemId;
        }
        return "Ingredient_Bar_Iron";
    }

    private String resolveDisplayCurrencyProvider() {
        BossShopConfig config = plugin.getShopConfig();
        String provider = config != null ? config.currencyProvider : ShopCurrencySupport.PROVIDER_AUTO;
        provider = ShopCurrencySupport.sanitizeProvider(provider);
        if (ShopCurrencySupport.PROVIDER_AUTO.equals(provider)) {
            return ShopCurrencySupport.resolveAutoProvider();
        }
        return provider;
    }

    public static final class ShopEventData {
        public static final BuilderCodec<ShopEventData> CODEC = BuilderCodec.builder(
                        ShopEventData.class,
                        ShopEventData::new
                )
                .append(
                        new KeyedCodec<>("Action", Codec.STRING),
                        (data, v) -> data.action = v,
                        data -> data.action
                ).add()
                .build();
        public String action;
    }
}
