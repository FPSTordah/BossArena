package com.bossarena.system;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.util.EventTitleUtil;

import java.util.Locale;
import java.util.logging.Logger;

public final class BossWaveNotificationService {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final double NOTIFY_RADIUS = 100.0d;
    private static final float TRANSIENT_DURATION_SECONDS = 3.0f;
    private static final float PERSISTENT_DURATION_SECONDS = 999.0f;
    private static final float FINAL_CLEAR_DURATION_SECONDS = 8.0f;

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

    private static void showToNearbyPlayers(World world,
                                            Vector3d center,
                                            Message title,
                                            Message subtitle,
                                            float durationSeconds) {
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            if (playerRef == null) {
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
