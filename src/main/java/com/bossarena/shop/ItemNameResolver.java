package com.bossarena.shop;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTranslationProperties;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ItemNameResolver {
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private ItemNameResolver() {
    }

    static String resolveCommonName(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "Currency";
        }

        String normalized = itemId.trim();
        String cached = CACHE.get(normalized);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        String fromAssets = resolveFromAssets(normalized);
        if (fromAssets != null && !fromAssets.isBlank()) {
            CACHE.put(normalized, fromAssets);
            return fromAssets;
        }
        return humanizeItemId(normalized);
    }

    private static String resolveFromAssets(String itemId) {
        try {
            ItemStack stack = new ItemStack(itemId, 1);
            Item item = stack.getItem();
            if (item == null) {
                return null;
            }

            ItemTranslationProperties translationProperties = item.getTranslationProperties();
            if (translationProperties != null) {
                String rawName = clean(translationProperties.getName());
                if (rawName != null) {
                    if (looksLikeTranslationKey(rawName)) {
                        String translated = translate(rawName);
                        if (translated != null) {
                            return translated;
                        }
                    } else {
                        return rawName;
                    }
                }
            }

            return translate(item.getTranslationKey());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String translate(String key) {
        String cleanedKey = clean(key);
        if (cleanedKey == null) {
            return null;
        }

        I18nModule i18n = I18nModule.get();
        if (i18n == null) {
            return null;
        }

        String translated = clean(i18n.getMessage(cleanedKey, I18nModule.DEFAULT_LANGUAGE));
        if (translated == null || cleanedKey.equals(translated)) {
            return null;
        }
        return translated;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean looksLikeTranslationKey(String value) {
        return value.indexOf(' ') < 0 && value.contains(".");
    }

    private static String humanizeItemId(String itemId) {
        String local = itemId;
        int namespaceIdx = local.indexOf(':');
        if (namespaceIdx >= 0 && namespaceIdx + 1 < local.length()) {
            local = local.substring(namespaceIdx + 1);
        }

        String[] parts = local.split("_+");
        if (parts.length == 0) {
            return local;
        }

        if (parts.length >= 3 && "ingredient".equalsIgnoreCase(parts[0])) {
            return titleJoin(parts, 2, parts.length) + " " + title(parts[1]);
        }

        return titleJoin(parts, 0, parts.length);
    }

    private static String titleJoin(String[] parts, int startInclusive, int endExclusive) {
        StringBuilder out = new StringBuilder();
        for (int i = startInclusive; i < endExclusive; i++) {
            String part = clean(parts[i]);
            if (part == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(title(part));
        }
        return out.isEmpty() ? "Currency" : out.toString();
    }

    private static String title(String token) {
        if (token.isEmpty()) {
            return token;
        }
        String lower = token.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
