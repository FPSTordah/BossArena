package com.bossarena.damagechart;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sends a simple chat-only damage summary (top 10 by damage) to each eligible player.
 * No UI / HUD is opened; players can keep moving and fighting freely.
 */
public final class BossArenaDamageChartOpener implements DamageChartOpener {

    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private static final int MAX_CHAT_ROWS = 10;

    @Override
    public void openChart(World world, List<PlayerRef> eligiblePlayers, List<DisplayRow> rows, String bossName, Store<EntityStore> store) {
        if (world == null || eligiblePlayers == null || rows == null || store == null) {
            return;
        }
        String summary = buildChatSummary(rows, bossName);
        for (PlayerRef playerRef : eligiblePlayers) {
            if (playerRef == null) {
                continue;
            }
            try {
                var ref = playerRef.getReference();
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                var storeObj = store.getComponent(ref, com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
                if (!(storeObj instanceof com.hypixel.hytale.server.core.entity.entities.Player player)) {
                    continue;
                }
                player.sendMessage(com.hypixel.hytale.server.core.Message.raw(summary));
            } catch (Exception e) {
                LOGGER.warning("Failed to send damage chart message to player: " + e.getMessage());
            }
        }
    }

    private static String buildChatSummary(List<DisplayRow> rows, String bossName) {
        StringBuilder sb = new StringBuilder();

        String title = (bossName != null && !bossName.isBlank())
                ? ("Damage Dealt — " + bossName)
                : "Damage Dealt";
        sb.append(title);

        int n = Math.min(rows.size(), MAX_CHAT_ROWS);
        for (int i = 0; i < n; i++) {
            DisplayRow row = rows.get(i);
            String name = row.displayName() != null ? row.displayName() : "?";
            sb.append("\n").append(i + 1).append(". ").append(name).append(": ").append(formatDamage(row.damage()));
        }

        if (rows.size() > MAX_CHAT_ROWS) {
            sb.append("\n... and ").append(rows.size() - MAX_CHAT_ROWS).append(" more");
        }

        return sb.toString();
    }

    private static String formatDamage(long damage) {
        if (damage >= 1_000_000) {
            return String.format("%.1fM", damage / 1_000_000.0);
        }
        if (damage >= 1_000) {
            return String.format("%.1fK", damage / 1_000.0);
        }
        return String.valueOf(damage);
    }
}
