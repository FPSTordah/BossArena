package com.bossarena.shop;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

final class ShopCurrencySupport {
    static final String PROVIDER_AUTO = "auto";
    static final String PROVIDER_ITEM = "item";
    static final String PROVIDER_HYMARKET = "hymarket";
    static final String PROVIDER_ECONOMY_SYSTEM = "economysystem";

    private ShopCurrencySupport() {
    }

    static String sanitizeProvider(String rawProvider) {
        if (rawProvider == null || rawProvider.isBlank()) {
            return PROVIDER_AUTO;
        }
        String provider = rawProvider.trim().toLowerCase(Locale.ROOT);
        if ("hymarketplus".equals(provider) || "hy_market".equals(provider) || "marketplace".equals(provider)) {
            return PROVIDER_HYMARKET;
        }
        if ("economy".equals(provider) || "ecosystem".equals(provider)) {
            return PROVIDER_ECONOMY_SYSTEM;
        }
        if (PROVIDER_ITEM.equals(provider)
                || PROVIDER_HYMARKET.equals(provider)
                || PROVIDER_ECONOMY_SYSTEM.equals(provider)
                || PROVIDER_AUTO.equals(provider)) {
            return provider;
        }
        return PROVIDER_AUTO;
    }

    static String resolveAutoProvider() {
        if (HyMarketBridge.isActive()) {
            return PROVIDER_HYMARKET;
        }
        if (EconomySystemBridge.isActive()) {
            return PROVIDER_ECONOMY_SYSTEM;
        }
        return PROVIDER_ITEM;
    }

    static boolean isHyMarketActive() {
        return HyMarketBridge.isActive();
    }

    static boolean isEconomySystemActive() {
        return EconomySystemBridge.isActive();
    }

    static boolean removeHyMarketCopper(UUID playerUuid, long amountCopper) {
        return HyMarketBridge.removeCopper(playerUuid, amountCopper);
    }

    static boolean removeEconomySystemBalance(UUID playerUuid, double amount) {
        return EconomySystemBridge.removeBalance(playerUuid, amount);
    }

    static String formatHyMarketCost(long copper) {
        if (copper <= 0) {
            return "0c";
        }
        long gold = copper / 10_000L;
        long rem = copper % 10_000L;
        long silver = rem / 100L;
        long c = rem % 100L;

        StringBuilder out = new StringBuilder();
        if (gold > 0) {
            out.append(gold).append("g");
        }
        if (silver > 0) {
            if (out.length() > 0) {
                out.append(" ");
            }
            out.append(silver).append("s");
        }
        if (c > 0 || out.length() == 0) {
            if (out.length() > 0) {
                out.append(" ");
            }
            out.append(c).append("c");
        }
        return out.toString();
    }

    static String formatEconomySystemCost(double amount) {
        return EconomySystemBridge.formatAmount(amount);
    }

    private static final class HyMarketBridge {
        private static boolean initialized = false;
        private static boolean available = false;
        private static Method marketplaceGetInstance;
        private static Method removeCopperMethod;

        private static synchronized boolean isActive() {
            if (!initialized) {
                initialized = true;
                try {
                    Class<?> economyManagerClass = Class.forName("com.luni.marketplace.economy.EconomyManager");
                    marketplaceGetInstance = economyManagerClass.getMethod("getInstance");
                    removeCopperMethod = economyManagerClass.getMethod("removeCopper", UUID.class, long.class);
                    available = true;
                } catch (Throwable ignored) {
                    available = false;
                }
            }
            if (!available) {
                return false;
            }
            try {
                return marketplaceGetInstance.invoke(null) != null;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean removeCopper(UUID playerUuid, long amountCopper) {
            if (!isActive()) {
                return false;
            }
            try {
                Object economyManager = marketplaceGetInstance.invoke(null);
                if (economyManager == null) {
                    return false;
                }
                if (amountCopper <= 0) {
                    return true;
                }
                return Boolean.TRUE.equals(removeCopperMethod.invoke(economyManager, playerUuid, amountCopper));
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static final class EconomySystemBridge {
        private static boolean initialized = false;
        private static boolean available = false;
        private static Method mainGetInstance;
        private static Method economyApiGetInstance;
        private static Method removeBalanceMethod;
        private static Method formatCurrency;

        private static synchronized boolean isActive() {
            if (!initialized) {
                initialized = true;
                try {
                    Class<?> mainClass = Class.forName("com.economy.Main");
                    Class<?> economyApiClass = Class.forName("com.economy.api.EconomyAPI");
                    Class<?> formatterClass = Class.forName("com.economy.util.CurrencyFormatter");

                    mainGetInstance = mainClass.getMethod("getInstance");
                    economyApiGetInstance = economyApiClass.getMethod("getInstance");
                    removeBalanceMethod = economyApiClass.getMethod("removeBalance", UUID.class, double.class);
                    formatCurrency = formatterClass.getMethod("format", double.class);
                    available = true;
                } catch (Throwable ignored) {
                    available = false;
                }
            }
            if (!available) {
                return false;
            }
            try {
                Object main = mainGetInstance.invoke(null);
                Object api = economyApiGetInstance.invoke(null);
                return main != null && api != null;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean removeBalance(UUID playerUuid, double amount) {
            if (!isActive()) {
                return false;
            }
            try {
                if (amount <= 0.0d) {
                    return true;
                }
                Object api = economyApiGetInstance.invoke(null);
                if (api == null) {
                    return false;
                }
                return Boolean.TRUE.equals(removeBalanceMethod.invoke(api, playerUuid, amount));
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static String formatAmount(double amount) {
            if (isActive()) {
                try {
                    Object value = formatCurrency.invoke(null, amount);
                    if (value instanceof String text && !text.isBlank()) {
                        return text;
                    }
                } catch (Throwable ignored) {
                    // Fallback below.
                }
            }
            return String.format(Locale.ROOT, "%.2f", amount);
        }
    }
}
