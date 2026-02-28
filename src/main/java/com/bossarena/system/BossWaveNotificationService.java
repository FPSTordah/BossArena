package com.bossarena.system;

import com.bossarena.BossArenaConfig;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.util.EventTitleUtil;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class BossWaveNotificationService {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final double NOTIFY_RADIUS = 100.0d;
    private static final float TRANSIENT_DURATION_SECONDS = 3.0f;
    private static final float PERSISTENT_DURATION_SECONDS = 999.0f;
    private static final float FINAL_CLEAR_DURATION_SECONDS = 8.0f;
    private static final float WORLD_ALERT_DURATION_SECONDS = 10.0f;
    private static final long WORLD_ALERT_DURATION_MILLIS = (long) (WORLD_ALERT_DURATION_SECONDS * 1000f);
    private static final Map<UUID, Long> TIMED_ALERT_SUPPRESS_UNTIL = new ConcurrentHashMap<>();

    private BossWaveNotificationService() {
    }

    public static void notifyBossAliveStatus(World world,
                                             Vector3d eventCenter,
                                             String bossName,
                                             int aliveBossCount,
                                             int activeAdds,
                                             String context) {
        notifyBossAliveStatus(world, eventCenter, bossName, aliveBossCount, activeAdds, context, -1L);
    }

    public static void notifyBossAliveStatus(World world,
                                             Vector3d eventCenter,
                                             String bossName,
                                             int aliveBossCount,
                                             int activeAdds,
                                             String context,
                                             long remainingCountdownMillis) {
        if (world == null || eventCenter == null) {
            return;
        }
        String contextText = context == null || context.isBlank() ? "" : (context.trim() + " | ");
        String countdownText = formatCountdownPrefix(remainingCountdownMillis);
        int bossesAlive = Math.max(0, aliveBossCount);
        int addsAlive = Math.max(0, activeAdds);
        boolean eventFinished = bossesAlive <= 0 && addsAlive <= 0;
        Message title = eventFinished
                ? Message.raw("VICTORY! Claim your spoils!")
                : Message.raw("The Shadows Stir—" + safeBossName(bossName) + " Approaches!");
        Message subtitle = eventFinished
                ? Message.raw("Boss alive: 0 | Wave mobs alive: 0")
                : Message.raw(contextText + countdownText + "Boss alive: " + bossesAlive + " | Wave mobs alive: " + addsAlive);
        float duration = (bossesAlive > 0 || addsAlive > 0)
                ? PERSISTENT_DURATION_SECONDS
                : FINAL_CLEAR_DURATION_SECONDS;
        showToNearbyPlayers(world, eventCenter, title, subtitle, duration);
    }

    public static void notifyWaveSpawn(World world,
                                       Vector3d eventCenter,
                                       String bossName,
                                       int waveNumber,
                                       int spawnedNow,
                                       int activeAdds,
                                       long remainingCountdownMillis) {
        if (world == null || eventCenter == null || spawnedNow <= 0) {
            return;
        }
        notifyBossAliveStatus(
                world,
                eventCenter,
                bossName,
                1,
                activeAdds,
                "Wave " + waveNumber + " spawned: " + spawnedNow,
                remainingCountdownMillis
        );
    }

    public static void notifyAddsRemaining(World world,
                                           Vector3d eventCenter,
                                           String bossName,
                                           int activeAdds) {
        notifyBossAliveStatus(world, eventCenter, bossName, 0, activeAdds, null);
    }

    public static void notifyTimedSpawn(String bossName,
                                        String arenaId,
                                        World world,
                                        String customMessage,
                                        boolean announceServerWide,
                                        boolean announceWorldWide) {
        if ((!announceServerWide && !announceWorldWide) || world == null) {
            return;
        }

        String bossDisplay = safeBossDisplayName(bossName);
        String arenaDisplay = safeText(arenaId, "unknown arena");
        String worldDisplay = safeText(world.getName(), "unknown world");

        String messageTemplate = customMessage == null || customMessage.isBlank()
                ? BossArenaConfig.DEFAULT_TIMED_ANNOUNCEMENT_TEXT
                : customMessage.trim();
        String chatMessage = applyTimedAnnouncementPlaceholders(messageTemplate, bossDisplay, arenaDisplay, worldDisplay);

        Message title = Message.raw("WORLD BOSS ALERT");
        Message subtitle = Message.raw(bossDisplay + " | Arena: " + arenaDisplay + " | World: " + worldDisplay);

        Iterable<PlayerRef> targets;
        if (announceServerWide) {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            targets = universe.getPlayers();
        } else {
            targets = world.getPlayerRefs();
        }

        for (PlayerRef playerRef : targets) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }
            suppressLocalStatusTitles(playerRef);
            try {
                playerRef.sendMessage(Message.raw(chatMessage));
            } catch (Exception e) {
                LOGGER.fine(() -> "Failed to send timed global alert chat message: " + e.getMessage());
            }
            try {
                EventTitleUtil.hideEventTitleFromPlayer(playerRef, 0f);
                EventTitleUtil.showEventTitleToPlayer(
                        playerRef,
                        title,
                        subtitle,
                        true,
                        null,
                        WORLD_ALERT_DURATION_SECONDS,
                        0f,
                        0f
                );
            } catch (Exception e) {
                LOGGER.fine(() -> "Failed to show timed global alert title: " + e.getMessage());
            }
        }
    }

    private static void showToNearbyPlayers(World world,
                                            Vector3d center,
                                            Message title,
                                            Message subtitle,
                                            float durationSeconds) {
        long now = System.currentTimeMillis();
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            if (playerRef == null) {
                continue;
            }
            if (isLocalStatusSuppressed(playerRef, now)) {
                continue;
            }

            Transform transform = playerRef.getTransform();
            Vector3d playerPosition = transform != null ? transform.getPosition() : null;
            if (playerPosition == null) {
                continue;
            }

            if (playerPosition.distanceTo(center) > NOTIFY_RADIUS) {
                try {
                    // Clear any previously shown BossArena title once the player leaves range.
                    EventTitleUtil.hideEventTitleFromPlayer(playerRef, 0f);
                } catch (Exception e) {
                    LOGGER.fine(() -> "Failed to hide out-of-range wave notification: " + e.getMessage());
                }
                continue;
            }

            try {
                EventTitleUtil.hideEventTitleFromPlayer(playerRef, 0f);
                EventTitleUtil.showEventTitleToPlayer(
                        playerRef,
                        title,
                        subtitle,
                        true,
                        null,
                        durationSeconds,
                        0f,
                        0f
                );
            } catch (Exception e) {
                LOGGER.fine(() -> "Failed to show wave notification: " + e.getMessage());
            }
        }
    }

    private static String safeBossName(String bossName) {
        if (bossName == null || bossName.isBlank()) {
            return "Boss";
        }
        return bossName.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeBossDisplayName(String bossName) {
        if (bossName == null || bossName.isBlank()) {
            return "Boss";
        }
        return bossName.trim();
    }

    private static String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static void suppressLocalStatusTitles(PlayerRef playerRef) {
        if (playerRef == null) {
            return;
        }
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        TIMED_ALERT_SUPPRESS_UNTIL.put(playerUuid, System.currentTimeMillis() + WORLD_ALERT_DURATION_MILLIS);
    }

    private static boolean isLocalStatusSuppressed(PlayerRef playerRef, long nowEpochMs) {
        if (playerRef == null) {
            return false;
        }
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return false;
        }
        Long untilEpochMs = TIMED_ALERT_SUPPRESS_UNTIL.get(playerUuid);
        if (untilEpochMs == null) {
            return false;
        }
        if (untilEpochMs > nowEpochMs) {
            return true;
        }
        TIMED_ALERT_SUPPRESS_UNTIL.remove(playerUuid, untilEpochMs);
        return false;
    }

    private static String applyTimedAnnouncementPlaceholders(String template,
                                                             String bossDisplay,
                                                             String arenaDisplay,
                                                             String worldDisplay) {
        if (template == null || template.isBlank()) {
            return "";
        }
        String resolved = template;
        resolved = resolved.replace("$Boss", bossDisplay);
        resolved = resolved.replace("$boss", bossDisplay);
        resolved = resolved.replace("$Arena", arenaDisplay);
        resolved = resolved.replace("$arena", arenaDisplay);
        resolved = resolved.replace("$World", worldDisplay);
        resolved = resolved.replace("$world", worldDisplay);
        return resolved;
    }

    private static String formatCountdownPrefix(long remainingCountdownMillis) {
        if (remainingCountdownMillis < 0L) {
            return "";
        }

        long totalSeconds = Math.max(0L, remainingCountdownMillis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return "Time left: " + String.format(Locale.ROOT, "%02d:%02d", minutes, seconds) + " | ";
    }
}
