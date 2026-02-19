package com.bossarena.shop;

import com.bossarena.BossArenaConfig;
import com.bossarena.BossArenaPlugin;
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
import java.util.HashMap;
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
            : "uncommon";


    private final BossArenaPlugin plugin;
    private String selectedTier;

    private BossArenaShopPage(PlayerRef playerRef, BossArenaPlugin plugin, String tier) {
        super(playerRef, CustomPageLifetime.CanDismiss, ShopEventData.CODEC);
        this.plugin = plugin;
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
        if (player == null || plugin == null) {
            return;
        }

        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            LOGGER.warning("BossArena shop: PlayerRef is null, cannot open.");
            return;
        }

        String normalizedTier = tier != null ? tier.toLowerCase(Locale.ROOT) : DEFAULT_TIER;
        if (!BossShopItems.isValidTier(normalizedTier)) {
            normalizedTier = DEFAULT_TIER;
        }

        BossArenaShopPage page = new BossArenaShopPage(playerRef, plugin, normalizedTier);
        player.getPageManager().openCustomPage(ref, store, page);
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

        Map<String, ShopEntry> entries = buildEntriesMap();
        int visibleSlots = resolveVisibleSlotsForTier(entries, selectedTier);
        for (int slot = 1; slot <= BossShopItems.SLOTS_PER_TIER; slot++) {
            String suffix = Integer.toString(slot);
            boolean slotVisible = slot <= visibleSlots;
            cmd.set("#ItemCard" + suffix + ".Visible", slotVisible);
            if (!slotVisible) {
                cmd.set("#BuyBtn" + suffix + ".Visible", false);
                cmd.set("#BuyBtn" + suffix + ".Disabled", true);
                continue;
            }

            ShopEntry entry = normalizeEntry(entries.get(BossShopItems.key(selectedTier, slot)), selectedTier, slot);
            cmd.set("#ItemName" + suffix + ".Text", entry.displayName);
            cmd.set("#ItemPrice" + suffix + ".Text", buildCostText(entry.cost, currencyItemId, effectiveCurrencyProvider));
            cmd.set("#ItemDesc" + suffix + ".Text", entry.description);
            cmd.set("#ItemIcon" + suffix + ".Visible", false);

            boolean ready = entry.enabled && !isBlank(entry.arenaId) && !isBlank(entry.bossId);
            boolean needsConfig = entry.enabled && !ready;
            cmd.set("#BuyBtn" + suffix + ".Visible", true);
            cmd.set("#BuyBtn" + suffix + ".Disabled", !entry.enabled || needsConfig);
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
        Map<String, ShopEntry> entries = buildEntriesMap();
        int visibleSlots = resolveVisibleSlotsForTier(entries, selectedTier);
        if (slot < 1 || slot > visibleSlots) {
            playerRef.sendMessage(Message.raw("Invalid shop slot."));
            return;
        }

        ShopEntry entry = normalizeEntry(
                entries.get(BossShopItems.key(selectedTier, slot)),
                selectedTier,
                slot
        );

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

    private Map<String, ShopEntry> buildEntriesMap() {
        Map<String, ShopEntry> map = new HashMap<>();
        BossShopConfig config = plugin.getShopConfig();
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

    private int resolveVisibleSlotsForTier(Map<String, ShopEntry> entries, String tier) {
        BossShopConfig config = plugin.getShopConfig();
        if (config != null && config.visibleSlotsByTier != null) {
            Integer configured = config.visibleSlotsByTier.get(tier);
            if (configured != null) {
                return clampVisibleSlots(configured);
            }
        }

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

    private String resolveItemCurrencyItemId() {
        BossShopConfig config = plugin.getShopConfig();
        if (config != null && config.currencyItemId != null && !config.currencyItemId.isBlank()) {
            return config.currencyItemId;
        }
        BossArenaConfig global = plugin.getConfigHandle();
        if (global != null && global.currencyItemId != null && !global.currencyItemId.isBlank()) {
            return global.currencyItemId;
        }
        return null;
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
        return "Price: " + cost + " " + currencyItemId;
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

    public static final class ShopEventData {
        public String action;

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
    }
}
