package com.bossarena;

import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/**
 * Handles extraction of bundled assets and registration of the BossArena asset pack.
 * Called from {@link BossArenaPlugin} during setup.
 */
public final class BossArenaAssetSetup {
    private static final int TIMED_MARKER_SIZE = 64;

    private BossArenaAssetSetup() {}

    /**
     * Extracts assets to a temp directory and registers the asset pack with the engine.
     * Call this after deleting manifest and migrating legacy data (plugin responsibility).
     */
    public static void register(BossArenaPlugin plugin) {
        try {
            Path assetsRoot = Files.createTempDirectory("BossArenaAssets");
            assetsRoot.toFile().deleteOnExit();
            extractAssets(plugin, assetsRoot);

            AssetModule assetModule = AssetModule.get();
            if (assetModule != null
                    && assetModule.getAssetPack(BossArenaPlugin.ASSET_PACK_ID) == null
                    && assetModule.getAssetPack("BossArena") == null) {
                assetModule.registerPack(BossArenaPlugin.ASSET_PACK_ID, assetsRoot, plugin.getManifest(), true);
                assetModule.initPendingStores();
                plugin.getLogger().atInfo().log("Registered BossArena asset pack from temp dir: " + assetsRoot);
            }

            BlockTypeAssetMap<String, BlockType> blockTypeMap = BlockType.getAssetMap();
            if (findBlockType(blockTypeMap, "Boss_Arena_Chest_Legendary") != null) {
                plugin.getLogger().atInfo().log("BossArena custom chest block found in asset map");
            } else {
                plugin.getLogger().atWarning().log("BossArena custom chest block still missing from asset map");
            }
        } catch (Exception e) {
            plugin.getLogger().atSevere().withCause(e).log("Failed to register BossArena asset pack");
        }
    }

    private static void extractAssets(BossArenaPlugin plugin, Path assetsRoot) throws IOException {
        Path serverRoot = assetsRoot.resolve("Server");
        String modelPath = safeAssetPath(
                ModelAsset.getAssetStore() != null ? ModelAsset.getAssetStore().getPath() : ""
        );
        String itemPath = defaultAssetPath(
                Item.getAssetStore() != null ? Item.getAssetStore().getPath() : "",
                "Item/Items"
        );
        Path modelDir = modelPath.isEmpty() ? serverRoot : serverRoot.resolve(trimServerPrefix(modelPath));
        Path itemDir = itemPath.isEmpty() ? serverRoot : serverRoot.resolve(trimServerPrefix(itemPath));

        copyPackResource(plugin, assetsRoot, "manifest.json");
        copyPackResource(plugin, assetsRoot, "Common/UI/Custom/Pages/BossArenaShopPage.ui");
        copyPackResource(plugin, assetsRoot, "Common/UI/Custom/Pages/BossArenaShopElementButton.ui");
        copyPackResource(plugin, assetsRoot, "Common/UI/Custom/Pages/BossArenaConfigPage.ui");
        copyPackResource(plugin, assetsRoot, "Common/UI/Custom/Pages/BossArenaDamageChart.ui");
        copyPackResource(plugin, assetsRoot, "Common/Blocks/Boss_Arena_Chest_Legendary.blockymodel");
        copyPackResource(plugin, assetsRoot, "Common/Blocks/Boss_Arena_Chest_Legendary_Texture.png");
        copyPackResource(plugin, assetsRoot, "Blocks/Boss_Arena_Chest_Legendary.blockymodel");
        copyPackResource(plugin, assetsRoot, "Blocks/Boss_Arena_Chest_Legendary_Texture.png");
        copyPackResource(plugin, assetsRoot, "Server/Item/Items/Boss_Arena_Chest_Legendary.json");
        copyPackResource(plugin, assetsRoot, "Server/Item/Items/Boss_Arena_Chest_Legendary.blockymodel");
        copyPackResource(plugin, assetsRoot, "Server/NPC/Roles/bossarena_shop_guard.json");
        copyPackResource(plugin, assetsRoot, "Server/Textures/Boss_Arena_Chest_Legendary_Texture.png");
        copyPackResource(plugin, assetsRoot, "Server/Icons/ItemsGenerated/boss_arena_shop_icon.png");
        copyPackResource(plugin, assetsRoot, "Common/UI/WorldMap/MapMarkers/map_marker.png");
        copyPackResource(plugin, assetsRoot, "Common/UI/WorldMap/MapMarkers/map_marker_large.png");
        copyPackResource(plugin, assetsRoot, "Common/UI/MapMarkers/map_marker.png");
        copyPackResource(plugin, assetsRoot, "Common/UI/MapMarkers/map_marker_large.png");
        copyOptionalExternalMapMarker(plugin, assetsRoot);

        copyResource(plugin, "Server/Item/Items/Boss_Arena_Chest_Legendary.blockymodel", modelDir.resolve("Boss_Arena_Chest_Legendary.blockymodel"));
        copyResource(plugin, "Server/Item/Items/Boss_Arena_Chest_Legendary.json", assetsRoot.resolve("Server/Items/Boss_Arena_Chest_Legendary.json"));
        copyResource(plugin, "Blocks/Boss_Arena_Chest_Legendary.blockymodel", assetsRoot.resolve("Blocks/Boss_Arena_Chest_Legendary.blockymodel"));
        copyResource(plugin, "Blocks/Boss_Arena_Chest_Legendary_Texture.png", assetsRoot.resolve("Blocks/Boss_Arena_Chest_Legendary_Texture.png"));
        copyResource(plugin, "Server/Textures/Boss_Arena_Chest_Legendary_Texture.png", modelDir.resolve("Boss_Arena_Chest_Legendary_Texture.png"));

        Files.deleteIfExists(assetsRoot.resolve("Server/Item/Items/boss_arena_shop_pedestal.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Item/Items/Boss_Arena_Shop.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Item/Items/Boss_Pedestal.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Item/RootInteractions/Block/BossArena_OpenShop.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Item/Interactions/Block/BossArena_OpenShop_Simple.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Items/Boss_Arena_Shop.json"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Items/Boss_Pedestal.json"));
        Files.deleteIfExists(assetsRoot.resolve("Blocks/Boss_Shop.blockymodel"));
        Files.deleteIfExists(assetsRoot.resolve("Blocks/boss_arena_shop_texture.png"));
        Files.deleteIfExists(assetsRoot.resolve("Common/Blocks/Boss_Shop.blockymodel"));
        Files.deleteIfExists(assetsRoot.resolve("Common/Blocks/boss_arena_shop_texture.png"));
        Files.deleteIfExists(assetsRoot.resolve("Server/Textures/boss_arena_shop_texture.png"));
        Files.deleteIfExists(itemDir.resolve("Boss_Arena_Shop.json"));
        Files.deleteIfExists(itemDir.resolve("Boss_Pedestal.json"));
        Files.deleteIfExists(modelDir.resolve("Boss_Shop.blockymodel"));
        Files.deleteIfExists(modelDir.resolve("boss_arena_shop_texture.png"));
        purgeLegacyShopArtifacts(assetsRoot);
    }

    private static boolean isLegacyShopArtifact(String fileName) {
        return fileName.contains("pedestal")
                || fileName.equals("boss_arena_shop.json")
                || fileName.equals("bossarena_openshop.json")
                || fileName.equals("bossarena_openshop_simple.json")
                || fileName.equals("boss_shop.blockymodel")
                || fileName.equals("boss_arena_shop_texture.png");
    }

    private static void purgeLegacyShopArtifacts(Path assetsRoot) throws IOException {
        Files.walkFileTree(assetsRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (isLegacyShopArtifact(fileName)) {
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyPackResource(BossArenaPlugin plugin, Path assetsRoot, String resourcePath) throws IOException {
        copyResource(plugin, resourcePath, assetsRoot.resolve(resourcePath));
    }

    private static void copyOptionalExternalMapMarker(BossArenaPlugin plugin, Path assetsRoot) throws IOException {
        Path externalMarker = Path.of("libs", "map_marker.png");
        if (!Files.exists(externalMarker)) {
            return;
        }
        Path destination = assetsRoot.resolve("Common/UI/WorldMap/MapMarkers/map_marker.png");
        Path destination2 = assetsRoot.resolve("Common/UI/MapMarkers/map_marker.png");
        Files.createDirectories(destination.getParent());
        Files.createDirectories(destination2.getParent());

        BufferedImage source = ImageIO.read(externalMarker.toFile());
        if (source == null) {
            Files.copy(externalMarker, destination, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(externalMarker, destination2, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().atWarning().log("Could not decode " + externalMarker + "; copied raw marker image as-is.");
        } else {
            BufferedImage normalized = new BufferedImage(TIMED_MARKER_SIZE, TIMED_MARKER_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = normalized.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setBackground(new java.awt.Color(0, 0, 0, 0));
                g.clearRect(0, 0, TIMED_MARKER_SIZE, TIMED_MARKER_SIZE);
                double scale = Math.min(
                        (double) TIMED_MARKER_SIZE / Math.max(1, source.getWidth()),
                        (double) TIMED_MARKER_SIZE / Math.max(1, source.getHeight())
                );
                int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
                int offsetX = (TIMED_MARKER_SIZE - drawWidth) / 2;
                int offsetY = (TIMED_MARKER_SIZE - drawHeight) / 2;
                g.drawImage(source, offsetX, offsetY, drawWidth, drawHeight, null);
            } finally {
                g.dispose();
            }
            boolean wrote = ImageIO.write(normalized, "png", destination.toFile());
            boolean wrote2 = ImageIO.write(normalized, "png", destination2.toFile());
            if (!wrote || !wrote2) {
                throw new IOException("No ImageIO writer available for PNG");
            }
            plugin.getLogger().atInfo().log(
                    "Loaded custom world map marker icon from " + externalMarker
                            + " and normalized to " + TIMED_MARKER_SIZE + "x" + TIMED_MARKER_SIZE
            );
        }
    }

    private static void copyResource(BossArenaPlugin plugin, String resourcePath, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (InputStream in = plugin.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                plugin.getLogger().atWarning().log("Missing bundled resource: " + resourcePath);
                return;
            }
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeAssetPath(String path) {
        if (path == null || path.isBlank() || ".".equals(path)) {
            return "";
        }
        return path;
    }

    private static String defaultAssetPath(String path, String fallback) {
        String normalized = safeAssetPath(path);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trimServerPrefix(String path) {
        if (path == null) {
            return "";
        }
        if (path.startsWith("Server/")) {
            return path.substring("Server/".length());
        }
        return path;
    }

    private static BlockType findBlockType(BlockTypeAssetMap<String, BlockType> map, String... baseIds) {
        for (String baseId : baseIds) {
            if (baseId == null || baseId.isBlank()) {
                continue;
            }
            BlockType direct = map.getAsset(baseId);
            if (direct != null) {
                return direct;
            }
            BlockType namespaced = map.getAsset(BossArenaPlugin.ASSET_PACK_ID + ":" + baseId);
            if (namespaced != null) {
                return namespaced;
            }
        }
        return null;
    }
}
