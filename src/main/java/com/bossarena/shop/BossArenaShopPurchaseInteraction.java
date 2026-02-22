package com.bossarena.shop;

import com.bossarena.BossArenaConfig;
import com.bossarena.BossArenaPlugin;
import com.bossarena.data.Arena;
import com.bossarena.data.ArenaRegistry;
import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceInteraction;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class BossArenaShopPurchaseInteraction extends ChoiceInteraction {
    private static final Logger LOGGER = Logger.getLogger("BossArena");

    private final String bossId;
    private final String arenaId;
    private final int cost;

    public BossArenaShopPurchaseInteraction(String bossId, String arenaId, int cost) {
        this.bossId = bossId;
        this.arenaId = arenaId;
        this.cost = Math.max(cost, 0);
    }

    @Override
    public void run(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef) {
        BossArenaPlugin plugin = BossArenaPlugin.getInstance();
        if (plugin == null) {
            return;
        }

        Object playerObj = store.getComponent(ref, Player.getComponentType());
        if (!(playerObj instanceof Player player)) {
            return;
        }

        if (bossId == null || bossId.isBlank()) {
            player.sendMessage(Message.raw("Shop entry missing bossId."));
            return;
        }
        if (arenaId == null || arenaId.isBlank()) {
            player.sendMessage(Message.raw("Shop entry missing arenaId."));
            return;
        }

        BossDefinition def = BossRegistry.get(bossId);
        if (def == null) {
            player.sendMessage(Message.raw("Boss not found: " + bossId));
            return;
        }

        Arena arena = ArenaRegistry.get(arenaId);
        if (arena == null) {
            player.sendMessage(Message.raw("Arena not found: " + arenaId));
            return;
        }

        if (cost > 0) {
            ChargeResult result = chargeCost(plugin, player, playerRef, cost);
            if (!result.success()) {
                player.sendMessage(Message.raw(result.message()));
                return;
            }
        }

        World world = player.getWorld();
        if (world == null) {
            player.sendMessage(Message.raw("Could not resolve world."));
            return;
        }

        world.execute(() -> {
            var uuid = plugin.getBossSpawnService().spawnBossFromJson(
                    player,
                    bossId,
                    world,
                    arena.getPosition(),
                    arenaId
            );

            if (uuid == null) {
                player.sendMessage(Message.raw("Failed to spawn boss: " + bossId));
            } else {
                player.sendMessage(Message.raw("Spawned boss: " + bossId));
            }
        });

        LOGGER.info("Shop purchase: " + playerRef + " -> " + bossId + " @ " + arenaId + ", cost=" + cost);
    }

    private static ChargeResult chargeCost(BossArenaPlugin plugin, Player player, PlayerRef playerRef, int amount) {
        CurrencySettings currency = resolveCurrencySettings(plugin);
        String provider = ShopCurrencySupport.sanitizeProvider(currency.provider);

        if (ShopCurrencySupport.PROVIDER_ECONOMY_SYSTEM.equals(provider)) {
            return tryChargeEconomySystem(playerRef.getUuid(), amount);
        }

        if (ShopCurrencySupport.PROVIDER_HYMARKET.equals(provider)) {
            return tryChargeHyMarket(playerRef.getUuid(), amount);
        }

        if (ShopCurrencySupport.PROVIDER_ITEM.equals(provider)) {
            return tryChargeItemCurrency(player, currency.itemId, amount);
        }

        // Auto mode: prefer HyMarket, then EconomySystem, then item currency.
        String autoProvider = ShopCurrencySupport.resolveAutoProvider();
        if (ShopCurrencySupport.PROVIDER_ECONOMY_SYSTEM.equals(autoProvider)) {
            return tryChargeEconomySystem(playerRef.getUuid(), amount);
        }
        if (ShopCurrencySupport.PROVIDER_HYMARKET.equals(autoProvider)) {
            return tryChargeHyMarket(playerRef.getUuid(), amount);
        }
        if (currency.itemId != null && !currency.itemId.isBlank()) {
            return tryChargeItemCurrency(player, currency.itemId, amount);
        }
        return ChargeResult.fail("No currency provider is available. Configure item currency or enable EconomySystem/HyMarketPlus.");
    }

    private static CurrencySettings resolveCurrencySettings(BossArenaPlugin plugin) {
        BossShopConfig shopConfig = plugin.getShopConfig();
        String provider = "auto";

        if (shopConfig != null) {
            if (shopConfig.currencyProvider != null && !shopConfig.currencyProvider.isBlank()) {
                provider = shopConfig.currencyProvider;
            }
            if (shopConfig.currencyItemId != null && !shopConfig.currencyItemId.isBlank()) {
                return new CurrencySettings(provider, shopConfig.currencyItemId);
            }
        }

        BossArenaConfig global = plugin.getConfigHandle();
        if (global != null && global.currencyItemId != null && !global.currencyItemId.isBlank()) {
            return new CurrencySettings(provider, global.currencyItemId);
        }
        if (global != null && global.fallbackCurrencyItemId != null && !global.fallbackCurrencyItemId.isBlank()) {
            return new CurrencySettings(provider, global.fallbackCurrencyItemId);
        }
        return new CurrencySettings(provider, "Ingredient_Bar_Iron");
    }

    private static ChargeResult tryChargeItemCurrency(Player player, String currencyItemId, int amount) {
        if (currencyItemId == null || currencyItemId.isBlank()) {
            return ChargeResult.fail("Shop currency is not configured.");
        }
        if (!consumeCurrency(player, currencyItemId, amount)) {
            return ChargeResult.fail("Not enough currency. Need " + amount + " " + currencyItemId + ".");
        }
        return ChargeResult.ok();
    }

    private static ChargeResult tryChargeHyMarket(UUID playerUuid, int amountCopper) {
        if (!ShopCurrencySupport.isHyMarketActive()) {
            return ChargeResult.fail("HyMarketPlus currency is not available. Configure item currency or enable HyMarketPlus.");
        }
        if (!ShopCurrencySupport.removeHyMarketCopper(playerUuid, amountCopper)) {
            return ChargeResult.fail("Not enough HyMarket currency. Need " + ShopCurrencySupport.formatHyMarketCost(amountCopper) + ".");
        }
        return ChargeResult.ok();
    }

    private static ChargeResult tryChargeEconomySystem(UUID playerUuid, int amount) {
        if (!ShopCurrencySupport.isEconomySystemActive()) {
            return ChargeResult.fail("EconomySystem currency is not available. Configure item currency or enable EconomySystem.");
        }
        if (!ShopCurrencySupport.removeEconomySystemBalance(playerUuid, amount)) {
            return ChargeResult.fail("Not enough EconomySystem currency. Need "
                    + ShopCurrencySupport.formatEconomySystemCost(amount) + ".");
        }
        return ChargeResult.ok();
    }

    private static boolean consumeCurrency(Player player, String currencyItemId, int amount) {
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }

        ItemContainer container = inventory.getCombinedBackpackStorageHotbar();
        if (container == null) {
            return false;
        }

        int total = countItem(container, currencyItemId);
        if (total < amount) {
            return false;
        }

        int remaining = amount;
        short capacity = container.getCapacity();
        for (short slot = 0; slot < capacity && remaining > 0; slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            if (!currencyItemId.equalsIgnoreCase(stack.getItemId())) {
                continue;
            }

            int removeAmount = Math.min(remaining, stack.getQuantity());
            container.removeItemStackFromSlot(slot, removeAmount);
            remaining -= removeAmount;
        }

        player.sendInventory();
        return remaining == 0;
    }

    private static int countItem(ItemContainer container, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return 0;
        }

        AtomicInteger total = new AtomicInteger();
        container.forEach((slot, stack) -> {
            if (!ItemStack.isEmpty(stack) && itemId.equalsIgnoreCase(stack.getItemId())) {
                total.addAndGet(stack.getQuantity());
            }
        });
        return total.get();
    }

    private static final class CurrencySettings {
        private final String provider;
        private final String itemId;

        private CurrencySettings(String provider, String itemId) {
            this.provider = provider;
            this.itemId = itemId;
        }
    }

    private static final class ChargeResult {
        private final boolean success;
        private final String message;

        private ChargeResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        private static ChargeResult ok() {
            return new ChargeResult(true, "");
        }

        private static ChargeResult fail(String message) {
            return new ChargeResult(false, message);
        }

        private boolean success() {
            return success;
        }

        private String message() {
            return message;
        }
    }
}
