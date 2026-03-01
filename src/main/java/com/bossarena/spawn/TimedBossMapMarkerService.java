package com.bossarena.spawn;

import com.bossarena.BossArenaConfig;
import com.bossarena.BossArenaPlugin;
import com.bossarena.system.BossTrackingSystem;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.Transform;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.hypixel.hytale.server.core.entity.entities.Player;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class TimedBossMapMarkerService {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final String PROVIDER_ID = "bossarena_timed_boss_markers";

    private final BossArenaPlugin plugin;
    private final BossTrackingSystem trackingSystem;
    private final BossTimedSpawnScheduler timedSpawnScheduler;

    public TimedBossMapMarkerService(BossArenaPlugin plugin,
                                     BossTrackingSystem trackingSystem,
                                     BossTimedSpawnScheduler timedSpawnScheduler) {
        this.plugin = plugin;
        this.trackingSystem = trackingSystem;
        this.timedSpawnScheduler = timedSpawnScheduler;
    }

    private static String resolveMarkerName(String template, BossTrackingSystem.BossData data, World world) {
        if (data == null) {
            return "";
        }

        String boss = optional(data.bossName);
        String arena = optional(data.arenaId);
        String tier = optional(data.bossTier);
        String worldName = world != null ? optional(world.getName()) : "";

        String text = template;
        text = replacePlaceholder(text, "Boss", boss);
        text = replacePlaceholder(text, "Arena", arena);
        text = replacePlaceholder(text, "Tier", tier);
        text = replacePlaceholder(text, "TierUpper", tier.toUpperCase(Locale.ROOT));
        text = replacePlaceholder(text, "World", worldName);
        return text;
    }

    private static boolean isSameWorld(World expected, World actual) {
        if (expected == null || actual == null) {
            return false;
        }
        if (expected == actual) {
            return true;
        }
        String left = optional(expected.getName());
        String right = optional(actual.getName());
        return !left.isEmpty() && left.equalsIgnoreCase(right);
    }

    private static String replacePlaceholder(String template, String key, String value) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String safeValue = value == null ? "" : value;
        String out = template.replace("$" + key, safeValue);
        out = out.replace("{" + key + "}", safeValue);
        return out;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    public void registerForAllWorlds() {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        for (World world : universe.getWorlds().values()) {
            registerForWorld(world);
        }
    }

    public void registerForWorld(World world) {
        if (world == null || timedSpawnScheduler == null) {
            return;
        }

        world.execute(() -> {
            try {
                if (world.getWorldMapManager() != null) {
                    world.getWorldMapManager().addMarkerProvider(PROVIDER_ID, this::collectMarkers);
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to register timed boss marker provider in world '"
                        + world.getName() + "': " + e.getMessage());
            }
        });
    }

    private void collectMarkers(World world, Player ignoredPlayer, MarkersCollector collector) {
        if (world == null || collector == null || timedSpawnScheduler == null || trackingSystem == null) {
            return;
        }

        BossArenaConfig config = plugin != null ? plugin.getConfigHandle() : null;
        if (config == null || config.timedMapMarker == null || !config.timedMapMarker.enabled) {
            return;
        }

        Set<UUID> timedBosses = timedSpawnScheduler.snapshotSpawnedTimedBossUuids();
        if (timedBosses.isEmpty()) {
            return;
        }

        for (UUID bossUuid : timedBosses) {
            BossTrackingSystem.BossData data = trackingSystem.getBossData(bossUuid);
            if (data == null || data.spawnLocation == null) {
                timedSpawnScheduler.forgetSpawnedTimedBoss(bossUuid);
                continue;
            }
            if (!isSameWorld(world, data.world)) {
                continue;
            }

            MapMarker marker = createMarker(bossUuid, data, world, config);
            if (marker != null) {
                collector.add(marker);
            }
        }
    }

    public void onTimedBossSpawn(World world, UUID bossUuid) {
        if (world == null || bossUuid == null) return;

        BossArenaConfig config = plugin != null ? plugin.getConfigHandle() : null;
        if (config == null || config.timedMapMarker == null || !config.timedMapMarker.enabled) {
            return;
        }

        BossTrackingSystem.BossData data = trackingSystem.getBossData(bossUuid);
        if (data == null || data.spawnLocation == null) return;

        MapMarker marker = createMarker(bossUuid, data, world, config);
        if (marker == null) return;

        UpdateWorldMap updatePacket = new UpdateWorldMap(null, new MapMarker[]{marker}, null);
        for (Player player : world.getPlayers()) {
            player.getPlayerConnection().writeNoCache(updatePacket);
        }
    }

    public void onTimedBossDespawn(World world, UUID bossUuid) {
        if (world == null || bossUuid == null) return;

        String markerId = getMarkerId(bossUuid);
        UpdateWorldMap updatePacket = new UpdateWorldMap(null, null, new String[]{markerId});

        for (Player player : world.getPlayers()) {
            player.getPlayerConnection().writeNoCache(updatePacket);
        }
    }

    private MapMarker createMarker(UUID bossUuid, BossTrackingSystem.BossData data, World world, BossArenaConfig config) {
        String markerImage = optional(config.timedMapMarker.markerImage);
        if (markerImage.isEmpty()) {
            markerImage = BossArenaConfig.DEFAULT_TIMED_MAP_MARKER_IMAGE;
        }
        String nameTemplate = optional(config.timedMapMarker.nameTemplate);
        if (nameTemplate.isEmpty()) {
            nameTemplate = BossArenaConfig.DEFAULT_TIMED_MAP_MARKER_NAME_TEMPLATE;
        }

        String markerId = getMarkerId(bossUuid);
        String markerName = resolveMarkerName(nameTemplate, data, world);

        Position position = new Position(data.spawnLocation.x, data.spawnLocation.y, data.spawnLocation.z);
        Direction direction = new Direction();
        Transform transform = new Transform(position, direction);

        FormattedMessage nameMsg = new FormattedMessage();
        nameMsg.rawText = markerName;

        return new MapMarker(
                markerId,
                nameMsg,
                null,       // customName
                markerImage,
                transform,
                null,       // no context menu items
                null        // no components
        );
    }

    private String getMarkerId(UUID bossUuid) {
        return "BossArenaTimedBoss_" + bossUuid.toString().replace("-", "");
    }

    public void clearAllMarkers() {
        if (timedSpawnScheduler == null) return;

        Set<UUID> timedBosses = timedSpawnScheduler.snapshotSpawnedTimedBossUuids();
        if (timedBosses.isEmpty()) return;

        String[] markerIds = new String[timedBosses.size()];
        int i = 0;
        for (UUID uuid : timedBosses) {
            markerIds[i++] = getMarkerId(uuid);
        }

        UpdateWorldMap updatePacket = new UpdateWorldMap(null, null, markerIds);

        Universe universe = Universe.get();
        if (universe == null) return;

        for (PlayerRef playerRef : universe.getPlayers()) {
            try {
                Player player = (Player) playerRef.getComponent(Player.getComponentType());
                if (player != null && player.getPlayerConnection() != null) {
                    player.getPlayerConnection().writeNoCache(updatePacket);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
