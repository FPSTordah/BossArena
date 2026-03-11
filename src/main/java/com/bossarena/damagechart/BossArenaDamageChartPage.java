package com.bossarena.damagechart;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Vanilla custom page for the boss event damage chart.
 * Shows rank, player name, and damage; Close button and ESC dismiss.
 */
public final class BossArenaDamageChartPage extends InteractiveCustomUIPage<BossArenaDamageChartPage.ChartEventData> {

    private static final String LAYOUT = "Pages/BossArenaDamageChart.ui";
    private static final int MAX_ROWS = 10;

    private final List<DamageChartOpener.DisplayRow> rows;
    private final String bossName;

    public BossArenaDamageChartPage(PlayerRef playerRef, List<DamageChartOpener.DisplayRow> rows, String bossName) {
        super(playerRef, CustomPageLifetime.CanDismiss, ChartEventData.CODEC);
        this.rows = rows != null ? List.copyOf(rows) : List.of();
        this.bossName = bossName != null ? bossName : "";
    }

    public static void open(Ref<EntityStore> ref,
                           Store<EntityStore> store,
                           Player player,
                           List<DamageChartOpener.DisplayRow> rows,
                           String bossName) {
        if (player == null || rows == null) {
            return;
        }
        @SuppressWarnings("removal")
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null) {
            return;
        }
        BossArenaDamageChartPage page = new BossArenaDamageChartPage(playerRef, rows, bossName);
        player.getPageManager().openCustomPage(ref, store, page);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);

        String title = bossName.isBlank() ? "Damage Dealt" : ("Damage Dealt — " + bossName);
        cmd.set("#TitleLabel.Text", title);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));

        int size = Math.min(rows.size(), MAX_ROWS);
        for (int i = 1; i <= MAX_ROWS; i++) {
            String rankSel = "#Row" + i + "Rank";
            String nameSel = "#Row" + i + "Name";
            String damageSel = "#Row" + i + "Damage";
            boolean visible = i <= size;
            cmd.set(rankSel + ".Visible", visible);
            cmd.set(nameSel + ".Visible", visible);
            cmd.set(damageSel + ".Visible", visible);
            if (visible) {
                DamageChartOpener.DisplayRow row = rows.get(i - 1);
                cmd.set(rankSel + ".Text", String.valueOf(i));
                cmd.set(nameSel + ".Text", row.displayName() != null ? row.displayName() : "");
                cmd.set(damageSel + ".Text", formatDamage(row.damage()));
            }
        }
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

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull ChartEventData data) {
        if (data.action != null && "close".equals(data.action)) {
            close();
        }
    }

    public static final class ChartEventData {
        public static final BuilderCodec<ChartEventData> CODEC = BuilderCodec.builder(
                        ChartEventData.class,
                        ChartEventData::new
                )
                .append(
                        new KeyedCodec<>("Action", Codec.STRING),
                        (data, v) -> data.action = v,
                        data -> data.action
                ).add()
                .build();
        public String action;
    }
}
