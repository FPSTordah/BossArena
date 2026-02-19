package com.bossarena.shop;

import java.util.Locale;

public final class BossShopItems {
    public static final String[] TIER_ORDER = new String[]{
            "uncommon",
            "common",
            "rare",
            "epic",
            "legendary"
    };

    public static final int SLOTS_PER_TIER = 5;

    private BossShopItems() {
    }

    public static boolean isValidTier(String tier) {
        return tierIndex(tier) >= 0;
    }

    public static int tierIndex(String tier) {
        if (tier == null) {
            return -1;
        }

        String key = tier.toLowerCase(Locale.ROOT);
        for (int i = 0; i < TIER_ORDER.length; i++) {
            if (TIER_ORDER[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    public static String displayTier(String tier) {
        if (tier == null || tier.isBlank()) {
            return "";
        }
        String lower = tier.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static String key(String tier, int slot) {
        return tier.toLowerCase(Locale.ROOT) + ":" + slot;
    }
}
