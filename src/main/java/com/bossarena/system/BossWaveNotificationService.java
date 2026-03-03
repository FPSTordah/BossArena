package com.bossarena.system;

import com.bossarena.BossArenaConfig;
import com.bossarena.BossArenaPlugin;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

public final class BossWaveNotificationService {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final double DEFAULT_NOTIFY_RADIUS = 100.0d;
    private static final float TRANSIENT_DURATION_SECONDS = 3.0f;
    private static final float PERSISTENT_DURATION_SECONDS = 999.0f;
    private static final float FINAL_CLEAR_DURATION_SECONDS = 8.0f;
    private static final float WORLD_ALERT_DURATION_SECONDS = 10.0f;
    private static final long WORLD_ALERT_DURATION_MILLIS = (long) (WORLD_ALERT_DURATION_SECONDS * 1000f);
    private static final Map<UUID, Long> TIMED_ALERT_SUPPRESS_UNTIL = new ConcurrentHashMap<>();
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\$([A-Za-z][A-Za-z0-9_]*)|\\{([A-Za-z][A-Za-z0-9_]*)\\}");

    private BossWaveNotificationService() {
    }

    private static double resolveNotificationRadius() {
        BossArenaPlugin plugin = BossArenaPlugin.getInstance();
        BossArenaConfig config = plugin != null ? plugin.getConfigHandle() : null;
        return config != null ? config.getNotificationRadius() : DEFAULT_NOTIFY_RADIUS;
    }

    public static void notifyBossAliveStatus(World world,
                                             Vector3d eventCenter,
                                             String bossName,
                                             int aliveBossCount,
                                             int activeAdds,
                                             String context) {
        notifyBossAliveStatus(world, eventCenter, bossName, aliveBossCount, activeAdds, context, -1L, false, true);
    }

    public static void notifyBossAliveStatus(World world,
                                             Vector3d eventCenter,
                                             String bossName,
                                             int aliveBossCount,
                                             int activeAdds,
                                             String context,
                                             long remainingCountdownMillis) {
        notifyBossAliveStatus(
                world,
                eventCenter,
                bossName,
                aliveBossCount,
                activeAdds,
                context,
                remainingCountdownMillis,
                false,
                true
        );
    }

    public static void notifyBossAliveStatus(World world,
                                             Vector3d eventCenter,
                                             String bossName,
                                             int aliveBossCount,
                                             int activeAdds,
                                             String context,
                                             long remainingCountdownMillis,
                                             boolean forceActiveState) {
        notifyBossAliveStatus(
                world,
                eventCenter,
                bossName,
                aliveBossCount,
                activeAdds,
                context,
                remainingCountdownMillis,
                forceActiveState,
                true
        );
    }

    public static void notifyBossAliveStatus(World world,
                                             Vector3d eventCenter,
                                             String bossName,
                                             int aliveBossCount,
                                             int activeAdds,
                                             String context,
                                             long remainingCountdownMillis,
                                             boolean forceActiveState,
                                             boolean showVictoryOnFinish) {
        notifyBossAliveStatus(world, eventCenter, bossName, aliveBossCount, activeAdds, context,
                remainingCountdownMillis, forceActiveState, showVictoryOnFinish, -1.0d);
    }

    /** Same as above but with per-arena notification radius (blocks). Use -1 or invalid to fall back to config default. */
    public static void notifyBossAliveStatus(World world,
                                             Vector3d eventCenter,
                                             String bossName,
                                             int aliveBossCount,
                                             int activeAdds,
                                             String context,
                                             long remainingCountdownMillis,
                                             boolean forceActiveState,
                                             boolean showVictoryOnFinish,
                                             double notificationRadiusBlocks) {
        if (world == null || eventCenter == null) {
            return;
        }
        String contextText = context == null || context.isBlank() ? "" : (context.trim() + " | ");
        String countdownValue = formatCountdownValue(remainingCountdownMillis);
        String countdownLabel = countdownValue.isEmpty() ? "" : "Time left: " + countdownValue;
        String countdownText = countdownLabel.isEmpty() ? "" : (countdownLabel + " | ");
        int bossesAlive = Math.max(0, aliveBossCount);
        int addsAlive = Math.max(0, activeAdds);
        boolean eventFinished = !forceActiveState && bossesAlive <= 0 && addsAlive <= 0;
        BossArenaConfig.EventBannerTemplates templates = resolveEventBannerTemplates();
        String bossDisplay = safeBossDisplayName(bossName);

        String titleText = "";
        String subtitleText = "";

        if (eventFinished) {
            if (showVictoryOnFinish) {
                titleText = applyEventBannerPlaceholders(
                        templates.victoryTitle,
                        bossDisplay,
                        bossesAlive,
                        addsAlive,
                        context,
                        contextText,
                        countdownValue,
                        countdownLabel,
                        countdownText,
                        true
                );
                subtitleText = applyEventBannerPlaceholders(
                        templates.victorySubtitle,
                        bossDisplay,
                        bossesAlive,
                        addsAlive,
                        context,
                        contextText,
                        countdownValue,
                        countdownLabel,
                        countdownText,
                        true
                );
            }
        } else {
            titleText = applyEventBannerPlaceholders(
                    templates.activeTitle,
                    bossDisplay,
                    bossesAlive,
                    addsAlive,
                    context,
                    contextText,
                    countdownValue,
                    countdownLabel,
                    countdownText,
                    false
            );
            subtitleText = applyEventBannerPlaceholders(
                    templates.activeSubtitle,
                    bossDisplay,
                    bossesAlive,
                    addsAlive,
                    context,
                    contextText,
                    countdownValue,
                    countdownLabel,
                    countdownText,
                    false
            );
        }

        Message title = titleText == null || titleText.isEmpty() ? null : Message.raw(titleText);
        Message subtitle = subtitleText == null || subtitleText.isEmpty() ? null : Message.raw(subtitleText);
        float duration = (forceActiveState || bossesAlive > 0 || addsAlive > 0)
                ? PERSISTENT_DURATION_SECONDS
                : FINAL_CLEAR_DURATION_SECONDS;
        showToNearbyPlayers(world, eventCenter, title, subtitle, duration, notificationRadiusBlocks);
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
                                            float durationSeconds,
                                            double notificationRadiusBlocks) {
        double radius = (Double.isFinite(notificationRadiusBlocks) && notificationRadiusBlocks > 0)
                ? notificationRadiusBlocks
                : resolveNotificationRadius();
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

            if (playerPosition.distanceTo(center) > radius) {
                try {
                    // Clear any previously shown BossArena title once the player leaves range.
                    EventTitleUtil.hideEventTitleFromPlayer(playerRef, 0f);
                } catch (Exception e) {
                    LOGGER.fine(() -> "Failed to hide out-of-range wave notification: " + e.getMessage());
                }
                continue;
            }

            try {
                // Always hide the previous title first to ensure a clean transition or a clear end state.
                EventTitleUtil.hideEventTitleFromPlayer(playerRef, 0f);

                // If both are null, we don't show any new title, so it stays hidden.
                if (title != null || subtitle != null) {
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
                }
            } catch (Exception e) {
                LOGGER.fine(() -> "Failed to update wave notification visibility: " + e.getMessage());
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
        Map<String, String> values = Map.of(
                "boss", defaultIfBlank(bossDisplay, "Boss"),
                "arena", defaultIfBlank(arenaDisplay, "Arena"),
                "world", defaultIfBlank(worldDisplay, "World")
        );
        return renderTemplate(template, values);
    }

    private static String formatCountdownValue(long remainingCountdownMillis) {
        if (remainingCountdownMillis < 0L) {
            return "";
        }

        long totalSeconds = Math.max(0L, remainingCountdownMillis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static BossArenaConfig.EventBannerTemplates resolveEventBannerTemplates() {
        BossArenaConfig.EventBannerTemplates out = new BossArenaConfig.EventBannerTemplates();
        BossArenaPlugin plugin = BossArenaPlugin.getInstance();
        BossArenaConfig config = plugin != null ? plugin.getConfigHandle() : null;
        if (config == null || config.eventBanner == null) {
            return out;
        }
        BossArenaConfig.EventBannerTemplates loaded = config.eventBanner;
        out.activeTitle = defaultIfBlank(loaded.activeTitle, BossArenaConfig.DEFAULT_EVENT_ACTIVE_TITLE_TEMPLATE);
        out.activeSubtitle = defaultIfBlank(loaded.activeSubtitle, BossArenaConfig.DEFAULT_EVENT_ACTIVE_SUBTITLE_TEMPLATE);
        out.victoryTitle = defaultIfBlank(loaded.victoryTitle, BossArenaConfig.DEFAULT_EVENT_VICTORY_TITLE_TEMPLATE);
        out.victorySubtitle = defaultIfBlank(loaded.victorySubtitle, BossArenaConfig.DEFAULT_EVENT_VICTORY_SUBTITLE_TEMPLATE);
        return out;
    }

    private static String applyEventBannerPlaceholders(String template,
                                                       String bossDisplay,
                                                       int bossesAlive,
                                                       int addsAlive,
                                                       String contextRaw,
                                                       String contextPrefix,
                                                       String countdown,
                                                       String countdownLabel,
                                                       String countdownPrefix,
                                                       boolean eventFinished) {
        String resolved = defaultIfBlank(template, "");
        String context = contextRaw == null || contextRaw.isBlank() ? "" : contextRaw.trim();
        String state = eventFinished ? "victory" : "active";
        Map<String, String> values = Map.ofEntries(
                Map.entry("boss", defaultIfBlank(bossDisplay, "Boss")),
                Map.entry("bossupper", safeBossName(bossDisplay)),
                Map.entry("bossalive", Integer.toString(Math.max(0, bossesAlive))),
                Map.entry("addsalive", Integer.toString(Math.max(0, addsAlive))),
                Map.entry("context", context),
                Map.entry("contextraw", context),
                // Clearer aliases for user-facing templates.
                Map.entry("contextline", defaultIfBlank(contextPrefix, "")),
                // Backwards-compatible legacy alias.
                Map.entry("contextprefix", defaultIfBlank(contextPrefix, "")),
                Map.entry("countdown", defaultIfBlank(countdown, "")),
                Map.entry("countdownlabel", defaultIfBlank(countdownLabel, "")),
                // Clearer aliases for user-facing templates.
                Map.entry("countdownline", defaultIfBlank(countdownPrefix, "")),
                // Backwards-compatible legacy alias.
                Map.entry("countdownprefix", defaultIfBlank(countdownPrefix, "")),
                Map.entry("state", state)
        );
        return renderTemplate(resolved, values);
    }

    private static String renderTemplate(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
            String replacement = values.getOrDefault(normalized, "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
