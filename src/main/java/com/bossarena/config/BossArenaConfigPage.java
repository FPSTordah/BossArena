package com.bossarena.config;

import com.bossarena.BossArenaPlugin;
import com.bossarena.data.Arena;
import com.bossarena.data.ArenaRegistry;
import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.bossarena.loot.LootItem;
import com.bossarena.loot.LootRegistry;
import com.bossarena.loot.LootTable;
import com.bossarena.shop.BossShopConfig;
import com.bossarena.shop.ShopEntry;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class BossArenaConfigPage extends InteractiveCustomUIPage<BossArenaConfigPage.ConfigEventData> {
    private static final String LAYOUT = "Pages/BossArenaConfigPage.ui";
    private static final String TAB_BOSSES = "bosses";
    private static final String TAB_SHOP = "shop";
    private static final String TAB_ARENAS = "arenas";

    private static final int MAX_ARENA_ROWS = 8;
    private static final int MAX_SHOP_ROWS = 8;
    private static final int MAX_SHOP_BOSS_ROWS = 12;
    private static final int MAX_BOSS_ROWS = 8;
    private static final int MAX_LOOT_ROWS = 8;
    private static final int MAX_WAVE_ADD_ROWS = 6;
    private static final int BOSS_SCROLL_THUMB_STEPS = 10;
    private static final Pattern ARENA_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final BossArenaPlugin plugin;
    private String selectedTab;

    private String arenaStatusText = "";
    private String shopStatusText = "";
    private String bossStatusText = "";

    private final List<String> arenaRows = new ArrayList<>();
    private final List<ShopTableRef> shopRows = new ArrayList<>();
    private final List<String> bossRows = new ArrayList<>();
    private int bossListOffset = 0;

    private BossEditorState bossEditorState;
    private boolean bossWavesOverlayOpen;
    private ShopTableEditorState shopTableEditorState;

    private BossArenaConfigPage(PlayerRef playerRef, BossArenaPlugin plugin, String tab) {
        super(playerRef, CustomPageLifetime.CanDismiss, ConfigEventData.CODEC);
        this.plugin = plugin;
        this.selectedTab = sanitizeTab(tab);
    }

    public static void open(Ref<EntityStore> ref,
                            Store<EntityStore> store,
                            Player player,
                            BossArenaPlugin plugin) {
        openTab(ref, store, player, plugin, TAB_BOSSES);
    }

    public static void openTab(Ref<EntityStore> ref,
                               Store<EntityStore> store,
                               Player player,
                               BossArenaPlugin plugin,
                               String tab) {
        if (player == null || plugin == null) {
            return;
        }

        @SuppressWarnings("removal")
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null) {
            return;
        }

        BossArenaConfigPage page = new BossArenaConfigPage(playerRef, plugin, tab);
        player.getPageManager().openCustomPage(ref, store, page);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);

        cmd.set("#TitleLabel.Text", "BossArena Config");
        cmd.set("#SubtitleLabel.Text", "Configure bosses, shop, and arenas");

        boolean bossesTab = TAB_BOSSES.equals(selectedTab);
        boolean shopTab = TAB_SHOP.equals(selectedTab);
        boolean arenasTab = TAB_ARENAS.equals(selectedTab);

        cmd.set("#TabIndicatorBosses.Visible", bossesTab);
        cmd.set("#TabIndicatorShop.Visible", shopTab);
        cmd.set("#TabIndicatorArenas.Visible", arenasTab);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BossesTab", EventData.of("Action", "tab_" + TAB_BOSSES));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ShopTab", EventData.of("Action", "tab_" + TAB_SHOP));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ArenasTab", EventData.of("Action", "tab_" + TAB_ARENAS));

        cmd.set("#BossesPanel.Visible", bossesTab);
        cmd.set("#ShopPanel.Visible", shopTab);
        cmd.set("#ArenasPanel.Visible", arenasTab);

        cmd.set("#StatusLabel.Visible", false);
        cmd.set("#StatusLabel.Text", "");

        if (bossesTab) {
            buildBossesTab(cmd, events);
        }

        if (shopTab) {
            buildShopTab(ref, store, cmd, events);
        }

        if (arenasTab) {
            buildArenasTab(cmd, events);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull ConfigEventData data) {
        String action = data.action;
        if (action == null || action.isBlank()) {
            return;
        }

        if ("close".equals(action)) {
            close();
            return;
        }

        if (action.startsWith("tab_")) {
            selectedTab = sanitizeTab(action.substring("tab_".length()));
            rebuild();
            return;
        }

        if (TAB_BOSSES.equals(selectedTab)) {
            handleBossesAction(action, data);
            return;
        }

        if (TAB_SHOP.equals(selectedTab)) {
            handleShopAction(action, data);
            return;
        }

        if (TAB_ARENAS.equals(selectedTab)) {
            handleArenasAction(action, ref, store, data);
            return;
        }
    }

    private void buildShopTab(Ref<EntityStore> ref,
                              Store<EntityStore> store,
                              UICommandBuilder cmd,
                              UIEventBuilder events) {
        cmd.set("#ShopStatusLabel.Text", shopStatusText == null ? "" : shopStatusText);
        List<ShopTableView> tables = snapshotShopTables(ref, store);
        shopRows.clear();

        cmd.set("#ShopOverflowLabel.Visible", tables.size() > MAX_SHOP_ROWS);
        if (tables.size() > MAX_SHOP_ROWS) {
            cmd.set("#ShopOverflowLabel.Text", "+" + (tables.size() - MAX_SHOP_ROWS) + " more shop tables not shown on this page yet.");
        } else {
            cmd.set("#ShopOverflowLabel.Text", "");
        }

        cmd.set("#ShopEmptyLabel.Visible", tables.isEmpty());
        if (tables.isEmpty()) {
            cmd.set("#ShopEmptyLabel.Text", "No shop tables found for this world.");
        }

        for (int row = 1; row <= MAX_SHOP_ROWS; row++) {
            String suffix = Integer.toString(row);
            boolean visible = row <= tables.size();
            cmd.set("#ShopRow" + suffix + ".Visible", visible);
            if (!visible) {
                continue;
            }

            ShopTableView table = tables.get(row - 1);
            shopRows.add(new ShopTableRef(table.worldName, table.x, table.y, table.z));
            cmd.set("#ShopArena" + suffix + ".Text", table.arenaLabel);
            cmd.set("#ShopDistance" + suffix + ".Text", table.distanceLabel);
            cmd.set("#ShopBosses" + suffix + ".Text", Integer.toString(table.enabledBosses));
            cmd.set("#ShopEntries" + suffix + ".Text", table.enabledBosses + "/" + table.totalBosses);
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#ShopEdit" + suffix,
                    EventData.of("Action", "shop_edit_open_" + row)
            );
        }

        cmd.set("#ShopTableEditorOverlay.Visible", shopTableEditorState != null);
        if (shopTableEditorState != null) {
            buildShopTableEditorOverlay(cmd, events);
        }
    }

    private List<ShopTableView> snapshotShopTables(Ref<EntityStore> ref, Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        String currentWorld = null;
        Vector3d playerPosition = null;

        if (player != null) {
            World world = player.getWorld();
            currentWorld = world != null ? world.getName() : null;

            @SuppressWarnings("removal")
            PlayerRef playerRef = player.getPlayerRef();
            Transform transform = playerRef != null ? playerRef.getTransform() : null;
            playerPosition = transform != null ? transform.getPosition() : null;
        }

        BossShopConfig shopConfig = plugin.getShopConfig();
        if (shopConfig == null || shopConfig.tableLocations == null || shopConfig.tableLocations.isEmpty()) {
            shopStatusText = "";
            return List.of();
        }

        int totalBosses = snapshotBossNames().size();
        List<ShopTableView> out = new ArrayList<>();
        for (BossShopConfig.ShopTableLocation location : shopConfig.tableLocations) {
            if (location == null) {
                continue;
            }
            String locationWorld = optionalText(location.worldName);
            if (locationWorld.isEmpty()) {
                continue;
            }
            if (currentWorld != null && !locationWorld.equalsIgnoreCase(currentWorld)) {
                continue;
            }

            String configuredArenaId = optionalText(location.arenaId);
            Arena nearestArena = configuredArenaId.isEmpty()
                    ? findNearestArenaForTable(locationWorld, location.x, location.y, location.z)
                    : null;
            String resolvedArenaId = configuredArenaId;
            if (resolvedArenaId.isEmpty() && nearestArena != null) {
                resolvedArenaId = optionalText(nearestArena.arenaId);
            }
            int enabledBosses = countEnabledBosses(location.enabledBossIds);

            Double distance = null;
            if (playerPosition != null) {
                double dx = playerPosition.x - location.x;
                double dy = playerPosition.y - location.y;
                double dz = playerPosition.z - location.z;
                distance = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
            }

            String arenaLabel = "(" + location.x + ", " + location.y + ", " + location.z + ")";
            if (!configuredArenaId.isEmpty()) {
                arenaLabel = arenaLabel + " | " + safeText(configuredArenaId);
            } else if (!resolvedArenaId.isEmpty()) {
                arenaLabel = arenaLabel + " | " + safeText(resolvedArenaId) + " (nearest)";
            } else {
                arenaLabel = arenaLabel + " | [unset]";
            }
            String distanceLabel = distance != null ? formatDouble(distance) + "m" : "--";
            out.add(new ShopTableView(
                    arenaLabel,
                    distanceLabel,
                    distance,
                    locationWorld,
                    location.x,
                    location.y,
                    location.z,
                    enabledBosses,
                    totalBosses
            ));
        }

        out.sort(Comparator
                .comparing((ShopTableView row) -> row.distance == null)
                .thenComparing(row -> row.distance == null ? Double.MAX_VALUE : row.distance)
                .thenComparing(row -> row.arenaLabel.toLowerCase(Locale.ROOT)));

        if (currentWorld == null) {
            shopStatusText = "Could not resolve player world; showing all saved shop tables.";
        } else {
            shopStatusText = "Showing saved tables in world '" + currentWorld + "', nearest first.";
        }

        return out;
    }

    private static int countEnabledBosses(List<String> bossIds) {
        if (bossIds == null || bossIds.isEmpty()) {
            return 0;
        }
        int out = 0;
        for (String bossId : bossIds) {
            if (bossId != null && !bossId.isBlank()) {
                out++;
            }
        }
        return out;
    }

    private static Arena findNearestArenaForTable(String worldName, int x, int y, int z) {
        Arena best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Arena arena : ArenaRegistry.getAll()) {
            if (arena == null || arena.worldName == null) {
                continue;
            }
            if (!arena.worldName.equalsIgnoreCase(worldName)) {
                continue;
            }
            double dx = arena.x - x;
            double dy = arena.y - y;
            double dz = arena.z - z;
            double distanceSq = (dx * dx) + (dy * dy) + (dz * dz);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = arena;
            }
        }
        return best;
    }

    private void handleShopAction(String action, ConfigEventData data) {
        if (action == null || action.isBlank()) {
            return;
        }

        if (action.startsWith("shop_edit_open_")) {
            handleShopEditOpen(action.substring("shop_edit_open_".length()));
            return;
        }

        if (action.startsWith("shop_edit_toggle_")) {
            handleShopEditToggle(action.substring("shop_edit_toggle_".length()));
            return;
        }

        if ("shop_edit_save".equals(action)) {
            handleShopEditSave(data);
            return;
        }

        if ("shop_edit_close".equals(action)) {
            shopTableEditorState = null;
            rebuild();
        }
    }

    private void handleShopEditOpen(String rowToken) {
        int row = parseRow(rowToken);
        if (row < 1 || row > shopRows.size()) {
            shopStatusText = "Invalid shop row selection.";
            rebuild();
            return;
        }

        ShopTableRef table = shopRows.get(row - 1);
        BossShopConfig shopConfig = plugin.getShopConfig();
        if (shopConfig == null) {
            shopStatusText = "Shop config is unavailable.";
            rebuild();
            return;
        }

        BossShopConfig.ShopTableLocation location = shopConfig.getTableLocation(table.worldName, table.x, table.y, table.z);
        if (location == null) {
            shopStatusText = "Selected shop table no longer exists in config.";
            rebuild();
            return;
        }

        List<String> orderedBosses = snapshotBossNames();
        Set<String> enabled = new LinkedHashSet<>();
        if (location.enabledBossIds != null) {
            for (String bossId : location.enabledBossIds) {
                if (bossId == null || bossId.isBlank()) {
                    continue;
                }
                String resolved = resolveBossNameCaseInsensitive(orderedBosses, bossId);
                if (resolved != null) {
                    enabled.add(resolved);
                }
            }
        }

        shopTableEditorState = new ShopTableEditorState(table, orderedBosses, enabled, optionalText(location.arenaId));
        shopStatusText = "";
        rebuild();
    }

    private void handleShopEditToggle(String rowToken) {
        if (shopTableEditorState == null) {
            return;
        }

        int row = parseRow(rowToken);
        if (row < 1 || row > shopTableEditorState.orderedBossNames.size()) {
            shopStatusText = "Invalid boss toggle row.";
            rebuild();
            return;
        }

        String bossName = shopTableEditorState.orderedBossNames.get(row - 1);
        if (shopTableEditorState.enabledBossNames.contains(bossName)) {
            shopTableEditorState.enabledBossNames.remove(bossName);
        } else {
            shopTableEditorState.enabledBossNames.add(bossName);
        }
        rebuild();
    }

    private void handleShopEditSave(ConfigEventData data) {
        if (shopTableEditorState == null) {
            return;
        }

        BossShopConfig shopConfig = plugin.getShopConfig();
        if (shopConfig == null) {
            shopStatusText = "Shop config is unavailable.";
            rebuild();
            return;
        }

        ShopTableRef table = shopTableEditorState.table;
        BossShopConfig.ShopTableLocation location = shopConfig.getTableLocation(table.worldName, table.x, table.y, table.z);
        if (location == null) {
            shopStatusText = "Selected shop table no longer exists in config.";
            rebuild();
            return;
        }

        List<String> savedBossIds = new ArrayList<>();
        for (String bossName : shopTableEditorState.orderedBossNames) {
            if (shopTableEditorState.enabledBossNames.contains(bossName)) {
                savedBossIds.add(bossName);
            }
        }
        String configuredArenaId = resolvedOrFallback(
                data != null ? data.shopEditArenaId : null,
                shopTableEditorState.arenaId
        );
        shopTableEditorState.arenaId = configuredArenaId;
        location.arenaId = configuredArenaId;
        location.enabledBossIds = savedBossIds;

        plugin.saveShopConfig();
        String arenaText = configuredArenaId.isEmpty() ? "nearest arena (auto)" : configuredArenaId;
        shopStatusText = "Saved table (" + table.x + ", " + table.y + ", " + table.z + ") with arena '" + arenaText + "'.";
        shopTableEditorState = null;
        rebuild();
    }

    private void buildShopTableEditorOverlay(UICommandBuilder cmd, UIEventBuilder events) {
        ShopTableEditorState state = shopTableEditorState;
        if (state == null) {
            return;
        }

        ShopTableRef table = state.table;
        cmd.set(
                "#ShopTableEditorTitle.Text",
                "Table (" + table.x + ", " + table.y + ", " + table.z + ")  [" + safeText(table.worldName) + "]"
        );
        cmd.set(
                "#ShopTableEditorHint.Text",
                "Set the table arena and toggle bosses. Enabled bosses are the only contracts shown there."
        );
        cmd.set("#ShopTableEditorArena.Value", optionalText(state.arenaId));

        cmd.set(
                "#ShopTableEditorOverflowLabel.Visible",
                state.orderedBossNames.size() > MAX_SHOP_BOSS_ROWS
        );
        if (state.orderedBossNames.size() > MAX_SHOP_BOSS_ROWS) {
            cmd.set(
                    "#ShopTableEditorOverflowLabel.Text",
                    "+" + (state.orderedBossNames.size() - MAX_SHOP_BOSS_ROWS) + " more bosses not shown."
            );
        } else {
            cmd.set("#ShopTableEditorOverflowLabel.Text", "");
        }

        for (int row = 1; row <= MAX_SHOP_BOSS_ROWS; row++) {
            String suffix = Integer.toString(row);
            boolean visible = row <= state.orderedBossNames.size();
            cmd.set("#ShopEditBossRow" + suffix + ".Visible", visible);
            if (!visible) {
                continue;
            }

            String bossName = state.orderedBossNames.get(row - 1);
            BossDefinition boss = BossRegistry.get(bossName);
            String tierText = boss != null ? normalizeTier(boss.tier) : "uncommon";
            boolean enabled = state.enabledBossNames.contains(bossName);

            cmd.set("#ShopEditBossName" + suffix + ".Text", safeText(bossName));
            cmd.set("#ShopEditBossTier" + suffix + ".Text", capitalize(tierText));
            cmd.set("#ShopEditBossToggle" + suffix + ".Text", enabled ? "On" : "Off");

            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#ShopEditBossToggle" + suffix,
                    EventData.of("Action", "shop_edit_toggle_" + row)
            );
        }

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#ShopTableEditorSaveButton",
                new EventData()
                        .append("Action", "shop_edit_save")
                        .append("@ShopEditArenaId", "#ShopTableEditorArena.Value")
        );
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ShopTableEditorCloseButton", EventData.of("Action", "shop_edit_close"));
    }

    private static String resolveBossNameCaseInsensitive(List<String> bossNames, String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        for (String bossName : bossNames) {
            if (bossName.equalsIgnoreCase(input)) {
                return bossName;
            }
        }
        return null;
    }

    private void handleArenasAction(String action,
                                    Ref<EntityStore> ref,
                                    Store<EntityStore> store,
                                    ConfigEventData data) {
        if ("arena_add_here".equals(action)) {
            addArenaAtPlayerPosition(ref, store);
            return;
        }

        if (action.startsWith("arena_delete_")) {
            handleArenaDelete(action.substring("arena_delete_".length()));
            return;
        }

        if (action.startsWith("arena_save_")) {
            handleArenaSave(action.substring("arena_save_".length()), data);
        }
    }

    private void buildArenasTab(UICommandBuilder cmd, UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ArenaAddButton", EventData.of("Action", "arena_add_here"));
        cmd.set("#ArenaStatusLabel.Text", arenaStatusText == null ? "" : arenaStatusText);

        List<Arena> arenas = snapshotArenas();
        arenaRows.clear();

        cmd.set("#ArenaOverflowLabel.Visible", arenas.size() > MAX_ARENA_ROWS);
        if (arenas.size() > MAX_ARENA_ROWS) {
            cmd.set("#ArenaOverflowLabel.Text", "+" + (arenas.size() - MAX_ARENA_ROWS) + " more arenas not shown on this page yet.");
        } else {
            cmd.set("#ArenaOverflowLabel.Text", "");
        }

        cmd.set("#ArenaEmptyLabel.Visible", arenas.isEmpty());
        if (arenas.isEmpty()) {
            cmd.set("#ArenaEmptyLabel.Text", "No arenas yet. Press + to add one at your position.");
        }

        for (int row = 1; row <= MAX_ARENA_ROWS; row++) {
            String suffix = Integer.toString(row);
            boolean visible = row <= arenas.size();
            cmd.set("#ArenaRow" + suffix + ".Visible", visible);
            if (!visible) {
                continue;
            }

            Arena arena = arenas.get(row - 1);
            arenaRows.add(arena.arenaId);

            cmd.set("#ArenaName" + suffix + ".Value", arena.arenaId == null ? "" : arena.arenaId);
            cmd.set("#ArenaX" + suffix + ".Value", formatCoord(arena.x));
            cmd.set("#ArenaY" + suffix + ".Value", formatCoord(arena.y));
            cmd.set("#ArenaZ" + suffix + ".Value", formatCoord(arena.z));

            events.addEventBinding(CustomUIEventBindingType.Activating, "#ArenaDelete" + suffix, EventData.of("Action", "arena_delete_" + row));
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#ArenaSave" + suffix,
                    new EventData()
                            .append("Action", "arena_save_" + row)
                            .append("@ArenaName", "#ArenaName" + suffix + ".Value")
                            .append("@ArenaX", "#ArenaX" + suffix + ".Value")
                            .append("@ArenaY", "#ArenaY" + suffix + ".Value")
                            .append("@ArenaZ", "#ArenaZ" + suffix + ".Value")
            );
        }
    }

    private void addArenaAtPlayerPosition(Ref<EntityStore> ref, Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            arenaStatusText = "Could not resolve player; arena not added.";
            rebuild();
            return;
        }

        @SuppressWarnings("removal")
        PlayerRef playerRef = player.getPlayerRef();
        World world = player.getWorld();
        Transform transform = playerRef != null ? playerRef.getTransform() : null;
        Vector3d position = transform != null ? transform.getPosition() : null;
        if (world == null || position == null) {
            arenaStatusText = "Could not resolve world/position; arena not added.";
            rebuild();
            return;
        }

        String arenaId = nextArenaId();
        Arena arena = new Arena(arenaId, world.getName(), position);
        ArenaRegistry.register(arena);
        plugin.saveArenas();

        arenaStatusText = "Added arena '" + arenaId + "' at " + formatCoord(position.x) + ", " + formatCoord(position.y) + ", " + formatCoord(position.z) + ".";
        rebuild();
    }

    private void handleArenaDelete(String rowToken) {
        int row = parseRow(rowToken);
        if (row < 1 || row > arenaRows.size()) {
            arenaStatusText = "Invalid arena row selection.";
            rebuild();
            return;
        }

        String arenaId = arenaRows.get(row - 1);
        Arena removed = ArenaRegistry.remove(arenaId);
        if (removed == null) {
            arenaStatusText = "Arena '" + arenaId + "' no longer exists.";
            rebuild();
            return;
        }

        plugin.saveArenas();
        arenaStatusText = "Deleted arena '" + arenaId + "'.";
        rebuild();
    }

    private void handleArenaSave(String rowToken, ConfigEventData data) {
        int row = parseRow(rowToken);
        if (row < 1 || row > arenaRows.size()) {
            arenaStatusText = "Invalid arena row selection.";
            rebuild();
            return;
        }

        Arena arena = ArenaRegistry.get(arenaRows.get(row - 1));
        if (arena == null) {
            arenaStatusText = "Selected arena no longer exists.";
            rebuild();
            return;
        }

        String requestedId = normalizeArenaId(data.arenaName);
        if (requestedId.isEmpty()) {
            arenaStatusText = "Arena name cannot be empty.";
            rebuild();
            return;
        }

        if (!ARENA_ID_PATTERN.matcher(requestedId).matches()) {
            arenaStatusText = "Arena name can only use letters, numbers, '_' or '-'.";
            rebuild();
            return;
        }

        Double x = parseCoordinate(data.arenaX);
        Double y = parseCoordinate(data.arenaY);
        Double z = parseCoordinate(data.arenaZ);
        if (x == null || y == null || z == null) {
            arenaStatusText = "Coordinates must be valid numbers.";
            rebuild();
            return;
        }

        String oldArenaId = arena.arenaId;
        boolean nameChanged = oldArenaId == null || !oldArenaId.equalsIgnoreCase(requestedId);
        if (nameChanged && ArenaRegistry.exists(requestedId)) {
            arenaStatusText = "Arena '" + requestedId + "' already exists.";
            rebuild();
            return;
        }

        if (nameChanged && oldArenaId != null && !oldArenaId.isBlank()) {
            ArenaRegistry.remove(oldArenaId);
        }

        arena.arenaId = requestedId;
        arena.x = x;
        arena.y = y;
        arena.z = z;
        ArenaRegistry.register(arena);

        plugin.saveArenas();
        arenaStatusText = "Saved arena '" + arena.arenaId + "'.";
        rebuild();
    }

    private void handleBossesAction(String action, ConfigEventData data) {
        if ("boss_add_open".equals(action)) {
            openNewBossEditor();
            rebuild();
            return;
        }

        if ("boss_editor_close".equals(action)) {
            bossEditorState = null;
            bossWavesOverlayOpen = false;
            bossStatusText = "";
            rebuild();
            return;
        }

        if ("boss_waves_open".equals(action)) {
            if (bossEditorState != null) {
                bossWavesOverlayOpen = true;
                rebuild();
            }
            return;
        }

        if ("boss_waves_close".equals(action)) {
            bossWavesOverlayOpen = false;
            rebuild();
            return;
        }

        if (action.startsWith("boss_wave_add_row_")) {
            handleBossWaveAddRow(action.substring("boss_wave_add_row_".length()), data);
            return;
        }

        if (action.startsWith("boss_wave_delete_")) {
            handleBossWaveDelete(action.substring("boss_wave_delete_".length()), data);
            return;
        }

        if ("boss_waves_save".equals(action)) {
            handleBossWavesSave(data);
            return;
        }

        if (action.startsWith("boss_loot_add_row_")) {
            handleBossLootAddRow(action.substring("boss_loot_add_row_".length()), data);
            return;
        }

        if (action.startsWith("boss_loot_delete_")) {
            handleBossLootDelete(action.substring("boss_loot_delete_".length()), data);
            return;
        }

        if ("boss_scroll_up".equals(action)) {
            scrollBossList(-1);
            return;
        }

        if ("boss_scroll_down".equals(action)) {
            scrollBossList(1);
            return;
        }

        if (action.startsWith("boss_open_")) {
            handleBossOpen(action.substring("boss_open_".length()));
            return;
        }

        if (action.startsWith("boss_delete_")) {
            handleBossDelete(action.substring("boss_delete_".length()));
            return;
        }

        if ("boss_editor_save".equals(action)) {
            handleBossEditorSave(data);
        }
    }

    private void buildBossesTab(UICommandBuilder cmd, UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BossAddButton", EventData.of("Action", "boss_add_open"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BossScrollUp", EventData.of("Action", "boss_scroll_up"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BossScrollDown", EventData.of("Action", "boss_scroll_down"));
        cmd.set("#BossStatusLabel.Text", bossStatusText == null ? "" : bossStatusText);

        List<String> bosses = snapshotBossNames();
        bossRows.clear();

        int totalBosses = bosses.size();
        int maxOffset = Math.max(0, totalBosses - MAX_BOSS_ROWS);
        bossListOffset = Math.max(0, Math.min(bossListOffset, maxOffset));
        boolean scrollable = totalBosses > MAX_BOSS_ROWS;
        boolean canScrollUp = scrollable && bossListOffset > 0;
        boolean canScrollDown = scrollable && bossListOffset < maxOffset;

        cmd.set("#BossScrollUp.Visible", canScrollUp);
        cmd.set("#BossScrollDown.Visible", canScrollDown);
        cmd.set("#BossScrollTrack.Visible", scrollable);
        cmd.set("#BossScrollPageLabel.Visible", scrollable);

        int scrollThumbStep = resolveBossScrollThumbStep(bossListOffset, maxOffset);
        for (int step = 1; step <= BOSS_SCROLL_THUMB_STEPS; step++) {
            cmd.set("#BossScrollThumb" + step + ".Visible", scrollable && step == scrollThumbStep);
        }

        if (scrollable) {
            int start = bossListOffset + 1;
            int end = Math.min(totalBosses, bossListOffset + MAX_BOSS_ROWS);
            cmd.set("#BossScrollPageLabel.Text", start + "-" + end + " / " + totalBosses);
            cmd.set("#BossOverflowLabel.Visible", true);
            cmd.set("#BossOverflowLabel.Text", "Use the scroll controls to view all bosses.");
        } else {
            cmd.set("#BossOverflowLabel.Visible", false);
            cmd.set("#BossOverflowLabel.Text", "");
            cmd.set("#BossScrollPageLabel.Text", "");
        }

        cmd.set("#BossEmptyLabel.Visible", bosses.isEmpty());
        if (bosses.isEmpty()) {
            cmd.set("#BossEmptyLabel.Text", "No bosses yet. Press Add Boss to create one.");
        }

        for (int row = 1; row <= MAX_BOSS_ROWS; row++) {
            String suffix = Integer.toString(row);
            int bossIndex = bossListOffset + row - 1;
            boolean visible = bossIndex < totalBosses;
            cmd.set("#BossRow" + suffix + ".Visible", visible);
            if (!visible) {
                continue;
            }

            String bossName = bosses.get(bossIndex);
            BossDefinition boss = BossRegistry.get(bossName);
            bossRows.add(bossName);

            cmd.set("#BossName" + suffix + ".Text", safeText(boss != null ? boss.bossName : bossName));
            cmd.set("#BossNpc" + suffix + ".Text", safeText(boss != null ? boss.npcId : ""));
            cmd.set("#BossAmount" + suffix + ".Text", Integer.toString(boss != null ? boss.amount : 0));

            events.addEventBinding(CustomUIEventBindingType.Activating, "#BossOpen" + suffix, EventData.of("Action", "boss_open_" + row));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#BossDelete" + suffix, EventData.of("Action", "boss_delete_" + row));
        }

        if (bossEditorState == null) {
            cmd.set("#BossEditorOverlay.Visible", false);
            return;
        }

        cmd.set("#BossEditorOverlay.Visible", true);
        buildBossEditorOverlay(cmd, events);
    }

    private void scrollBossList(int delta) {
        if (delta == 0) {
            return;
        }

        int totalBosses = snapshotBossNames().size();
        int maxOffset = Math.max(0, totalBosses - MAX_BOSS_ROWS);
        int nextOffset = Math.max(0, Math.min(maxOffset, bossListOffset + delta));
        bossListOffset = nextOffset;
        rebuild();
    }

    private static int resolveBossScrollThumbStep(int offset, int maxOffset) {
        if (maxOffset <= 0) {
            return 1;
        }
        double normalized = Math.max(0.0d, Math.min(1.0d, (double) offset / (double) maxOffset));
        int index = (int) Math.round(normalized * (BOSS_SCROLL_THUMB_STEPS - 1));
        return Math.max(1, Math.min(BOSS_SCROLL_THUMB_STEPS, index + 1));
    }

    private void buildBossEditorOverlay(UICommandBuilder cmd, UIEventBuilder events) {
        BossDefinition boss = bossEditorState.boss;
        LootTable loot = bossEditorState.loot;

        cmd.set("#BossEditorTitle.Text", "Editing: " + safeText(boss.bossName));

        cmd.set("#BossEditName.Value", safeText(boss.bossName));
        cmd.set("#BossEditNpcId.Value", safeText(boss.npcId));
        cmd.set("#BossEditTier.Value", normalizeTier(boss.tier));
        cmd.set("#BossEditAmount.Value", Integer.toString(Math.max(1, boss.amount)));

        cmd.set("#BossEditHp.Value", formatFloat(boss.modifiers.hp));
        cmd.set("#BossEditDamage.Value", formatFloat(boss.modifiers.damage));
        cmd.set("#BossEditSize.Value", formatFloat(boss.modifiers.size));

        cmd.set("#BossEditPpHp.Value", formatFloat(boss.perPlayerIncrease.hp));
        cmd.set("#BossEditPpDamage.Value", formatFloat(boss.perPlayerIncrease.damage));
        cmd.set("#BossEditPpSize.Value", formatFloat(boss.perPlayerIncrease.size));
        cmd.set("#BossEditLootRadius.Value", formatDouble(loot.lootRadius));
        if (boss.extraMobs != null) {
            boss.extraMobs.sanitize();
        }
        int waveCount = boss.extraMobs != null ? Math.max(-1, boss.extraMobs.waves) : 0;
        cmd.set("#BossEditWaves.Value", Integer.toString(waveCount));
        BossWavesSummary summary = buildBossWavesSummary(boss.extraMobs);
        cmd.set("#BossWavesAdd1.Text", summary.addLine1);
        cmd.set("#BossWavesAdd2.Text", summary.addLine2);
        cmd.set("#BossWavesMeta.Text", summary.metaLine);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#BossEditorCloseButton", EventData.of("Action", "boss_editor_close"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BossEditWavesButton", EventData.of("Action", "boss_waves_open"));

        List<LootItem> items = loot.items != null ? loot.items : List.of();
        int visibleRows = Math.max(1, Math.min(MAX_LOOT_ROWS, items.size() + 1));

        for (int row = 1; row <= MAX_LOOT_ROWS; row++) {
            String suffix = Integer.toString(row);
            boolean visible = row <= visibleRows;
            cmd.set("#BossLootRow" + suffix + ".Visible", visible);

            if (!visible) {
                continue;
            }

            LootItem item = row <= items.size() ? items.get(row - 1) : null;
            if (item == null) {
                cmd.set("#BossLootName" + suffix + ".Value", "");
                cmd.set("#BossLootMin" + suffix + ".Value", "1");
                cmd.set("#BossLootMax" + suffix + ".Value", "1");
                cmd.set("#BossLootChance" + suffix + ".Value", "0.250");
            } else {
                cmd.set("#BossLootName" + suffix + ".Value", safeText(item.itemId));
                cmd.set("#BossLootMin" + suffix + ".Value", Integer.toString(Math.max(1, item.minAmount)));
                cmd.set("#BossLootMax" + suffix + ".Value", Integer.toString(Math.max(Math.max(1, item.minAmount), item.maxAmount)));
                cmd.set("#BossLootChance" + suffix + ".Value", formatDouble(clamp(item.dropChance, 0.0d, 1.0d)));
            }

            boolean populated = row <= items.size();
            cmd.set("#BossLootDelete" + suffix + ".Text", populated ? "-" : "+");
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    "#BossLootDelete" + suffix,
                    buildBossEditorSnapshotEvent(populated
                            ? "boss_loot_delete_" + row
                            : "boss_loot_add_row_" + row));
        }

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#BossEditorSaveButton",
                buildBossEditorSnapshotEvent("boss_editor_save")
        );

        cmd.set("#BossWavesOverlay.Visible", bossWavesOverlayOpen);
        if (bossWavesOverlayOpen) {
            BossDefinition.ExtraMobs extra = boss.extraMobs != null ? boss.extraMobs : new BossDefinition.ExtraMobs();
            extra.sanitize();
            cmd.set("#BossWavesTimeSec.Value", formatSecondsFromMillis(Math.max(0L, extra.timeLimitMs)));

            List<BossDefinition.ExtraMobs.WaveAdd> waveAdds = extra.getConfiguredAdds();
            int visibleWaveRows = Math.max(1, Math.min(MAX_WAVE_ADD_ROWS, waveAdds.size() + 1));

            for (int row = 1; row <= MAX_WAVE_ADD_ROWS; row++) {
                String suffix = Integer.toString(row);
                boolean visible = row <= visibleWaveRows;
                cmd.set("#BossWavesRow" + suffix + ".Visible", visible);

                if (!visible) {
                    continue;
                }

                BossDefinition.ExtraMobs.WaveAdd add = row <= waveAdds.size() ? waveAdds.get(row - 1) : null;
                if (add == null) {
                    cmd.set("#BossWaveNpc" + suffix + ".Value", "");
                    cmd.set("#BossWaveAmount" + suffix + ".Value", "1");
                    cmd.set("#BossWaveEvery" + suffix + ".Value", "1");
                    cmd.set("#BossWaveHp" + suffix + ".Value", "1.00");
                    cmd.set("#BossWaveDamage" + suffix + ".Value", "1.00");
                    cmd.set("#BossWaveSize" + suffix + ".Value", "1.00");
                } else {
                    cmd.set("#BossWaveNpc" + suffix + ".Value", safeText(add.npcId));
                    cmd.set("#BossWaveAmount" + suffix + ".Value", Integer.toString(Math.max(1, add.mobsPerWave)));
                    cmd.set("#BossWaveEvery" + suffix + ".Value", Integer.toString(Math.max(1, add.everyWave)));
                    cmd.set("#BossWaveHp" + suffix + ".Value", formatFloat(add.hp > 0f ? add.hp : 1.0f));
                    cmd.set("#BossWaveDamage" + suffix + ".Value", formatFloat(add.damage > 0f ? add.damage : 1.0f));
                    cmd.set("#BossWaveSize" + suffix + ".Value", formatFloat(add.size > 0f ? add.size : 1.0f));
                }

                boolean populated = row <= waveAdds.size();
                cmd.set("#BossWaveAction" + suffix + ".Text", populated ? "-" : "+");
                events.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        "#BossWaveAction" + suffix,
                        buildBossWavesSnapshotEvent(populated
                                ? "boss_wave_delete_" + row
                                : "boss_wave_add_row_" + row)
                );
            }

            events.addEventBinding(CustomUIEventBindingType.Activating, "#BossWavesCloseButton", EventData.of("Action", "boss_waves_close"));
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#BossWavesSaveButton",
                    buildBossWavesSnapshotEvent("boss_waves_save")
            );
        }
    }

    private EventData buildBossEditorSnapshotEvent(String action) {
        EventData snapshot = new EventData()
                .append("Action", action)
                .append("@BossEditName", "#BossEditName.Value")
                .append("@BossEditNpcId", "#BossEditNpcId.Value")
                .append("@BossEditTier", "#BossEditTier.Value")
                .append("@BossEditAmount", "#BossEditAmount.Value")
                .append("@BossEditHp", "#BossEditHp.Value")
                .append("@BossEditDamage", "#BossEditDamage.Value")
                .append("@BossEditSize", "#BossEditSize.Value")
                .append("@BossEditPpHp", "#BossEditPpHp.Value")
                .append("@BossEditPpDamage", "#BossEditPpDamage.Value")
                .append("@BossEditPpSize", "#BossEditPpSize.Value")
                .append("@BossEditWaves", "#BossEditWaves.Value")
                .append("@BossEditLootRadius", "#BossEditLootRadius.Value");

        for (int row = 1; row <= MAX_LOOT_ROWS; row++) {
            String suffix = Integer.toString(row);
            snapshot
                    .append("@BossLootName" + suffix, "#BossLootName" + suffix + ".Value")
                    .append("@BossLootMin" + suffix, "#BossLootMin" + suffix + ".Value")
                    .append("@BossLootMax" + suffix, "#BossLootMax" + suffix + ".Value")
                    .append("@BossLootChance" + suffix, "#BossLootChance" + suffix + ".Value");
        }

        return snapshot;
    }

    private EventData buildBossWavesSnapshotEvent(String action) {
        EventData snapshot = new EventData()
                .append("Action", action)
                .append("@BossWaveTimeSec", "#BossWavesTimeSec.Value")
                // Preserve boss-level waves while editing popup rows.
                .append("@BossEditWaves", "#BossEditWaves.Value");

        for (int row = 1; row <= MAX_WAVE_ADD_ROWS; row++) {
            String suffix = Integer.toString(row);
            snapshot
                    .append("@BossWaveNpc" + suffix, "#BossWaveNpc" + suffix + ".Value")
                    .append("@BossWaveAmount" + suffix, "#BossWaveAmount" + suffix + ".Value")
                    .append("@BossWaveEvery" + suffix, "#BossWaveEvery" + suffix + ".Value")
                    .append("@BossWaveHp" + suffix, "#BossWaveHp" + suffix + ".Value")
                    .append("@BossWaveDamage" + suffix, "#BossWaveDamage" + suffix + ".Value")
                    .append("@BossWaveSize" + suffix, "#BossWaveSize" + suffix + ".Value");
        }

        return snapshot;
    }

    private void openNewBossEditor() {
        BossDefinition boss = new BossDefinition();
        boss.bossName = nextBossName();
        boss.npcId = "Bat";
        boss.tier = "uncommon";
        boss.amount = 1;

        boss.modifiers = new BossDefinition.Modifiers();
        boss.perPlayerIncrease = new BossDefinition.PerPlayerIncrease();
        boss.extraMobs = new BossDefinition.ExtraMobs();

        LootTable loot = new LootTable(boss.bossName, 40.0d);

        bossEditorState = new BossEditorState(null, boss, loot);
        bossWavesOverlayOpen = false;
        bossStatusText = "Creating new boss.";
    }

    private void handleBossOpen(String rowToken) {
        int row = parseRow(rowToken);
        if (row < 1 || row > bossRows.size()) {
            bossStatusText = "Invalid boss row selection.";
            rebuild();
            return;
        }

        String bossName = bossRows.get(row - 1);
        BossDefinition sourceBoss = BossRegistry.get(bossName);
        if (sourceBoss == null) {
            bossStatusText = "Selected boss no longer exists.";
            rebuild();
            return;
        }

        LootTable sourceLoot = LootRegistry.get(bossName);
        bossEditorState = new BossEditorState(
                sourceBoss.bossName,
                cloneBoss(sourceBoss),
                cloneLoot(sourceLoot, sourceBoss.bossName)
        );
        bossWavesOverlayOpen = false;

        bossStatusText = "Editing boss '" + sourceBoss.bossName + "'.";
        rebuild();
    }

    private void handleBossDelete(String rowToken) {
        int row = parseRow(rowToken);
        if (row < 1 || row > bossRows.size()) {
            bossStatusText = "Invalid boss row selection.";
            rebuild();
            return;
        }

        String bossName = bossRows.get(row - 1);
        BossDefinition removedBoss = BossRegistry.remove(bossName);
        LootRegistry.remove(bossName);

        if (removedBoss == null) {
            bossStatusText = "Boss '" + bossName + "' no longer exists.";
            rebuild();
            return;
        }

        if (bossEditorState != null
                && bossEditorState.originalBossName != null
                && bossEditorState.originalBossName.equalsIgnoreCase(bossName)) {
            bossEditorState = null;
            bossWavesOverlayOpen = false;
        }

        plugin.saveBossDefinitions();
        plugin.saveLootTables();

        bossStatusText = "Deleted boss '" + removedBoss.bossName + "'.";
        rebuild();
    }

    private void handleBossLootAddRow(String rowToken, ConfigEventData data) {
        if (bossEditorState == null) {
            return;
        }

        int row = parseRow(rowToken);
        if (row < 1 || row > MAX_LOOT_ROWS) {
            bossStatusText = "Invalid loot row.";
            rebuild();
            return;
        }

        try {
            applyBossEditorDraft(data);
            List<LootItem> rows = snapshotLootFromData(data);
            if (rows.size() >= MAX_LOOT_ROWS) {
                bossStatusText = "Maximum loot rows reached.";
                rebuild();
                return;
            }
            bossEditorState.loot.items = rows;
            bossStatusText = "";
            rebuild();
        } catch (IllegalArgumentException ex) {
            bossStatusText = ex.getMessage();
            rebuild();
        }
    }

    private void handleBossLootDelete(String rowToken, ConfigEventData data) {
        if (bossEditorState == null) {
            return;
        }

        int row = parseRow(rowToken);
        if (row < 1 || row > MAX_LOOT_ROWS) {
            bossStatusText = "Invalid loot row.";
            rebuild();
            return;
        }

        try {
            applyBossEditorDraft(data);
            List<LootItem> rows = snapshotLootFromData(data);
            if (row > rows.size()) {
                bossStatusText = "Invalid loot row selection.";
                rebuild();
                return;
            }
            rows.remove(row - 1);
            bossEditorState.loot.items = rows;
            bossStatusText = "";
            rebuild();
        } catch (IllegalArgumentException ex) {
            bossStatusText = ex.getMessage();
            rebuild();
        }
    }

    private void handleBossEditorSave(ConfigEventData data) {
        if (bossEditorState == null) {
            return;
        }

        try {
            BossDefinition outBoss = cloneBoss(bossEditorState.boss);

            String bossNameText = resolvedOrFallback(data.bossEditName, outBoss.bossName);
            String npcIdText = resolvedOrFallback(data.bossEditNpcId, outBoss.npcId);
            outBoss.bossName = requireNonBlank(bossNameText, "Boss name cannot be empty.");
            outBoss.npcId = requireNonBlank(npcIdText, "NPC ID cannot be empty.");
            outBoss.tier = normalizeTier(resolvedOrFallback(data.bossEditTier, outBoss.tier));

            outBoss.amount = parseRequiredInt(
                    resolvedOrFallback(data.bossEditAmount, Integer.toString(Math.max(1, outBoss.amount))),
                    "Amount must be an integer greater than 0.",
                    1,
                    Integer.MAX_VALUE
            );
            outBoss.modifiers.hp = parseRequiredFloat(
                    resolvedOrFallback(data.bossEditHp, formatFloat(outBoss.modifiers.hp)),
                    "HP Mult must be a number greater than 0.",
                    Float.MIN_NORMAL,
                    Float.MAX_VALUE
            );
            outBoss.modifiers.damage = parseRequiredFloat(
                    resolvedOrFallback(data.bossEditDamage, formatFloat(outBoss.modifiers.damage)),
                    "DMG Mult must be a number greater than 0.",
                    Float.MIN_NORMAL,
                    Float.MAX_VALUE
            );
            outBoss.modifiers.size = parseRequiredFloat(
                    resolvedOrFallback(data.bossEditSize, formatFloat(outBoss.modifiers.size)),
                    "Size Mult must be a number greater than 0.",
                    Float.MIN_NORMAL,
                    Float.MAX_VALUE
            );
            outBoss.perPlayerIncrease.hp = parseRequiredFloat(
                    resolvedOrFallback(data.bossEditPpHp, formatFloat(outBoss.perPlayerIncrease.hp)),
                    "PP HP must be a number greater than or equal to 0.",
                    0f,
                    Float.MAX_VALUE
            );
            outBoss.perPlayerIncrease.damage = parseRequiredFloat(
                    resolvedOrFallback(data.bossEditPpDamage, formatFloat(outBoss.perPlayerIncrease.damage)),
                    "PP DMG must be a number greater than or equal to 0.",
                    0f,
                    Float.MAX_VALUE
            );
            outBoss.perPlayerIncrease.size = parseRequiredFloat(
                    resolvedOrFallback(data.bossEditPpSize, formatFloat(outBoss.perPlayerIncrease.size)),
                    "PP Size must be a number greater than or equal to 0.",
                    0f,
                    Float.MAX_VALUE
            );
            if (outBoss.extraMobs == null) {
                outBoss.extraMobs = new BossDefinition.ExtraMobs();
            }
            outBoss.extraMobs.waves = parseRequiredInt(
                    resolvedOrFallback(data.bossEditWaves, Integer.toString(Math.max(-1, outBoss.extraMobs.waves))),
                    "Waves must be -1 (infinite) or an integer >= 0.",
                    -1,
                    Integer.MAX_VALUE
            );
            outBoss.extraMobs.sanitize();

            LootTable outLoot = new LootTable();
            outLoot.bossName = outBoss.bossName;
            outLoot.lootRadius = parseRequiredDouble(
                    resolvedOrFallback(
                            data.bossEditLootRadius,
                            formatDouble(bossEditorState.loot != null ? bossEditorState.loot.lootRadius : 40.0d)
                    ),
                    "Loot Radius must be a number greater than or equal to 0.",
                    0.0d,
                    Double.MAX_VALUE
            );
            outLoot.items = new ArrayList<>();

            boolean unresolvedLootBindingDetected = false;
            boolean lootPayloadMissing = true;
            for (int row = 1; row <= MAX_LOOT_ROWS; row++) {
                String rawItemId = data.getBossLootName(row);
                String rawMinText = data.getBossLootMin(row);
                String rawMaxText = data.getBossLootMax(row);
                String rawChanceText = data.getBossLootChance(row);

                if (rawItemId != null || rawMinText != null || rawMaxText != null || rawChanceText != null) {
                    lootPayloadMissing = false;
                }

                String itemId = optionalText(rawItemId);
                String minText = optionalText(rawMinText);
                String maxText = optionalText(rawMaxText);
                String chanceText = optionalText(rawChanceText);

                if (looksLikeUiBindingExpression(itemId)
                        || looksLikeUiBindingExpression(minText)
                        || looksLikeUiBindingExpression(maxText)
                        || looksLikeUiBindingExpression(chanceText)) {
                    unresolvedLootBindingDetected = true;
                    break;
                }

                // Ignore unfinished rows with no item name (the trailing blank input row).
                if (itemId.isEmpty()) {
                    continue;
                }

                int minAmount = parseRequiredInt(minText, "Loot min amount must be an integer on row " + row + ".", 1, Integer.MAX_VALUE);
                int maxAmount = parseRequiredInt(maxText, "Loot max amount must be an integer on row " + row + ".", minAmount, Integer.MAX_VALUE);
                double chance = parseRequiredDouble(chanceText, "Loot drop chance must be 0.0 to 1.0 on row " + row + ".", 0.0d, 1.0d);

                outLoot.items.add(new LootItem(itemId, chance, minAmount, maxAmount));
            }
            if (unresolvedLootBindingDetected || lootPayloadMissing) {
                LootTable fallbackLoot = bossEditorState.loot;
                outLoot.items = new ArrayList<>();
                if (fallbackLoot != null && fallbackLoot.items != null) {
                    for (LootItem item : fallbackLoot.items) {
                        if (item == null) {
                            continue;
                        }
                        outLoot.items.add(new LootItem(item.itemId, item.dropChance, item.minAmount, item.maxAmount));
                    }
                }
            }

            String oldName = bossEditorState.originalBossName;
            boolean nameChanged = oldName != null && !oldName.equalsIgnoreCase(outBoss.bossName);

            if ((oldName == null || nameChanged) && BossRegistry.exists(outBoss.bossName)) {
                bossStatusText = "Boss '" + outBoss.bossName + "' already exists.";
                rebuild();
                return;
            }

            if (nameChanged) {
                BossRegistry.remove(oldName);
                LootRegistry.remove(oldName);
            }

            BossRegistry.register(outBoss);
            LootRegistry.register(outLoot);

            plugin.saveBossDefinitions();
            plugin.saveLootTables();

            bossEditorState = new BossEditorState(outBoss.bossName, cloneBoss(outBoss), cloneLoot(outLoot, outBoss.bossName));
            bossStatusText = "Saved boss '" + outBoss.bossName + "'.";
            rebuild();
        } catch (IllegalArgumentException ex) {
            bossStatusText = ex.getMessage();
            rebuild();
        }
    }

    private void applyBossEditorDraft(ConfigEventData data) {
        if (data == null || bossEditorState == null) {
            return;
        }

        BossDefinition boss = bossEditorState.boss;
        LootTable loot = bossEditorState.loot;

        String bossName = optionalText(data.bossEditName);
        if (!bossName.isEmpty() && !looksLikeUiBindingExpression(bossName)) {
            boss.bossName = bossName;
            if (loot != null) {
                loot.bossName = bossName;
            }
        } else if (!bossName.isEmpty()) {
        }

        String npcId = optionalText(data.bossEditNpcId);
        if (!npcId.isEmpty() && !looksLikeUiBindingExpression(npcId)) {
            boss.npcId = npcId;
        } else if (!npcId.isEmpty()) {
        }

        String tier = optionalText(data.bossEditTier);
        if (!tier.isEmpty() && !looksLikeUiBindingExpression(tier)) {
            boss.tier = normalizeTier(tier);
        }

        Integer amount = parseOptionalInt(data.bossEditAmount);
        if (amount != null && amount >= 1) {
            boss.amount = amount;
        }

        Float hp = parseOptionalFloat(data.bossEditHp);
        if (hp != null && hp > 0f) {
            boss.modifiers.hp = hp;
        }
        Float damage = parseOptionalFloat(data.bossEditDamage);
        if (damage != null && damage > 0f) {
            boss.modifiers.damage = damage;
        }
        Float size = parseOptionalFloat(data.bossEditSize);
        if (size != null && size > 0f) {
            boss.modifiers.size = size;
        }

        Float ppHp = parseOptionalFloat(data.bossEditPpHp);
        if (ppHp != null && ppHp >= 0f) {
            boss.perPlayerIncrease.hp = ppHp;
        }
        Float ppDamage = parseOptionalFloat(data.bossEditPpDamage);
        if (ppDamage != null && ppDamage >= 0f) {
            boss.perPlayerIncrease.damage = ppDamage;
        }
        Float ppSize = parseOptionalFloat(data.bossEditPpSize);
        if (ppSize != null && ppSize >= 0f) {
            boss.perPlayerIncrease.size = ppSize;
        }
        Integer waves = parseOptionalInt(data.bossEditWaves);
        if (waves != null && waves >= -1) {
            if (boss.extraMobs == null) {
                boss.extraMobs = new BossDefinition.ExtraMobs();
            }
            boss.extraMobs.waves = waves;
            boss.extraMobs.sanitize();
        }

        Double lootRadius = parseOptionalDouble(data.bossEditLootRadius);
        if (loot != null && lootRadius != null && lootRadius >= 0d) {
            loot.lootRadius = lootRadius;
        }
    }

    private void handleBossWavesSave(ConfigEventData data) {
        if (bossEditorState == null) {
            return;
        }

        try {
            applyBossEditorDraft(data);

            if (bossEditorState.boss.extraMobs == null) {
                bossEditorState.boss.extraMobs = new BossDefinition.ExtraMobs();
            }
            BossDefinition.ExtraMobs extra = bossEditorState.boss.extraMobs;

            String timeLimitSec = resolvedOrFallback(
                    data.bossWaveTimeSec,
                    formatSecondsFromMillis(Math.max(0L, extra.timeLimitMs))
            );
            extra.timeLimitMs = parseRequiredSecondsToMillis(
                    timeLimitSec,
                    "Boss Waves time must be a number of seconds."
            );

            extra.adds = snapshotWaveAddsFromData(data);
            extra.sanitize();
            bossWavesOverlayOpen = false;
            bossStatusText = "Boss Waves updated. Click Save to persist changes.";
            rebuild();
        } catch (IllegalArgumentException ex) {
            bossStatusText = ex.getMessage();
            rebuild();
        }
    }

    private void handleBossWaveAddRow(String rowToken, ConfigEventData data) {
        if (bossEditorState == null) {
            return;
        }

        int row = parseRow(rowToken);
        if (row < 1 || row > MAX_WAVE_ADD_ROWS) {
            bossStatusText = "Invalid wave add row.";
            rebuild();
            return;
        }

        try {
            applyBossEditorDraft(data);

            if (bossEditorState.boss.extraMobs == null) {
                bossEditorState.boss.extraMobs = new BossDefinition.ExtraMobs();
            }
            List<BossDefinition.ExtraMobs.WaveAdd> rows = snapshotWaveAddsFromData(data);
            if (rows.size() >= MAX_WAVE_ADD_ROWS) {
                bossStatusText = "Maximum wave add rows reached.";
                rebuild();
                return;
            }
            bossEditorState.boss.extraMobs.adds = rows;
            bossEditorState.boss.extraMobs.sanitize();
            bossStatusText = "";
            rebuild();
        } catch (IllegalArgumentException ex) {
            bossStatusText = ex.getMessage();
            rebuild();
        }
    }

    private void handleBossWaveDelete(String rowToken, ConfigEventData data) {
        if (bossEditorState == null) {
            return;
        }

        int row = parseRow(rowToken);
        if (row < 1 || row > MAX_WAVE_ADD_ROWS) {
            bossStatusText = "Invalid wave add row.";
            rebuild();
            return;
        }

        try {
            applyBossEditorDraft(data);

            if (bossEditorState.boss.extraMobs == null) {
                bossEditorState.boss.extraMobs = new BossDefinition.ExtraMobs();
            }
            List<BossDefinition.ExtraMobs.WaveAdd> rows = snapshotWaveAddsFromData(data);
            if (row > rows.size()) {
                bossStatusText = "Invalid wave add row selection.";
                rebuild();
                return;
            }
            rows.remove(row - 1);
            bossEditorState.boss.extraMobs.adds = rows;
            bossEditorState.boss.extraMobs.sanitize();
            bossStatusText = "";
            rebuild();
        } catch (IllegalArgumentException ex) {
            bossStatusText = ex.getMessage();
            rebuild();
        }
    }

    private List<BossDefinition.ExtraMobs.WaveAdd> snapshotWaveAddsFromData(ConfigEventData data) {
        List<BossDefinition.ExtraMobs.WaveAdd> rows = new ArrayList<>();
        List<BossDefinition.ExtraMobs.WaveAdd> fallbackRows = List.of();
        if (bossEditorState != null
                && bossEditorState.boss != null
                && bossEditorState.boss.extraMobs != null) {
            fallbackRows = bossEditorState.boss.extraMobs.getConfiguredAdds();
        }

        for (int row = 1; row <= MAX_WAVE_ADD_ROWS; row++) {
            BossDefinition.ExtraMobs.WaveAdd fallback = row <= fallbackRows.size() ? fallbackRows.get(row - 1) : null;
            String npcId = optionalText(data.getBossWaveNpc(row));
            String amountText = optionalText(data.getBossWaveAmount(row));
            String everyText = optionalText(data.getBossWaveEvery(row));
            String hpText = optionalText(data.getBossWaveHp(row));
            String damageText = optionalText(data.getBossWaveDamage(row));
            String sizeText = optionalText(data.getBossWaveSize(row));

            if (looksLikeUiBindingExpression(npcId)
                    || looksLikeUiBindingExpression(amountText)
                    || looksLikeUiBindingExpression(everyText)
                    || looksLikeUiBindingExpression(hpText)
                    || looksLikeUiBindingExpression(damageText)
                    || looksLikeUiBindingExpression(sizeText)) {
                if (fallback != null && fallback.npcId != null && !fallback.npcId.isBlank()) {
                    BossDefinition.ExtraMobs.WaveAdd copy = new BossDefinition.ExtraMobs.WaveAdd();
                    copy.npcId = fallback.npcId;
                    copy.mobsPerWave = Math.max(1, fallback.mobsPerWave);
                    copy.everyWave = Math.max(1, fallback.everyWave);
                    copy.hp = fallback.hp > 0f ? fallback.hp : 1.0f;
                    copy.damage = fallback.damage > 0f ? fallback.damage : 1.0f;
                    copy.size = fallback.size > 0f ? fallback.size : 1.0f;
                    rows.add(copy);
                }
                continue;
            }

            if (npcId.isEmpty()) {
                continue;
            }

            int amount = parseRequiredInt(amountText, "Wave add amount must be an integer on row " + row + ".", 1, Integer.MAX_VALUE);
            int every = parseRequiredInt(everyText, "Wave add spawn interval must be >= 1 on row " + row + ".", 1, Integer.MAX_VALUE);
            String resolvedHp = !hpText.isEmpty() ? hpText : (fallback != null ? formatFloat(fallback.hp > 0f ? fallback.hp : 1.0f) : "1.00");
            String resolvedDamage = !damageText.isEmpty() ? damageText : (fallback != null ? formatFloat(fallback.damage > 0f ? fallback.damage : 1.0f) : "1.00");
            String resolvedSize = !sizeText.isEmpty() ? sizeText : (fallback != null ? formatFloat(fallback.size > 0f ? fallback.size : 1.0f) : "1.00");
            float hp = parseRequiredFloat(
                    resolvedHp,
                    "Wave add HP mult must be > 0 on row " + row + ".",
                    0.001f,
                    Float.MAX_VALUE
            );
            float damage = parseRequiredFloat(
                    resolvedDamage,
                    "Wave add damage mult must be > 0 on row " + row + ".",
                    0.001f,
                    Float.MAX_VALUE
            );
            float size = parseRequiredFloat(
                    resolvedSize,
                    "Wave add size mult must be > 0 on row " + row + ".",
                    0.001f,
                    Float.MAX_VALUE
            );

            BossDefinition.ExtraMobs.WaveAdd add = new BossDefinition.ExtraMobs.WaveAdd();
            add.npcId = npcId;
            add.mobsPerWave = amount;
            add.everyWave = every;
            add.hp = hp;
            add.damage = damage;
            add.size = size;
            rows.add(add);
        }

        return rows;
    }

    private List<LootItem> snapshotLootFromData(ConfigEventData data) {
        List<LootItem> rows = new ArrayList<>();
        List<LootItem> fallbackRows = List.of();
        if (bossEditorState != null && bossEditorState.loot != null && bossEditorState.loot.items != null) {
            fallbackRows = bossEditorState.loot.items;
        }

        for (int row = 1; row <= MAX_LOOT_ROWS; row++) {
            String itemId = optionalText(data.getBossLootName(row));
            String minText = optionalText(data.getBossLootMin(row));
            String maxText = optionalText(data.getBossLootMax(row));
            String chanceText = optionalText(data.getBossLootChance(row));

            if (looksLikeUiBindingExpression(itemId)
                    || looksLikeUiBindingExpression(minText)
                    || looksLikeUiBindingExpression(maxText)
                    || looksLikeUiBindingExpression(chanceText)) {
                if (row <= fallbackRows.size()) {
                    LootItem fallback = fallbackRows.get(row - 1);
                    if (fallback != null && fallback.itemId != null && !fallback.itemId.isBlank()) {
                        rows.add(new LootItem(fallback.itemId, fallback.dropChance, fallback.minAmount, fallback.maxAmount));
                    }
                }
                continue;
            }

            // Ignore unfinished rows with no item name (the trailing blank input row).
            if (itemId.isEmpty()) {
                continue;
            }

            int minAmount = parseRequiredInt(minText, "Loot min amount must be an integer on row " + row + ".", 1, Integer.MAX_VALUE);
            int maxAmount = parseRequiredInt(maxText, "Loot max amount must be an integer on row " + row + ".", minAmount, Integer.MAX_VALUE);
            double chance = parseRequiredDouble(chanceText, "Loot drop chance must be 0.0 to 1.0 on row " + row + ".", 0.0d, 1.0d);

            rows.add(new LootItem(itemId, chance, minAmount, maxAmount));
        }

        return rows;
    }

    private static int parseRow(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static Double parseCoordinate(String raw) {
        if (raw == null) {
            return null;
        }

        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeArenaId(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String formatCoord(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatFloat(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String formatSecondsFromMillis(long millis) {
        if (millis <= 0L) {
            return "0";
        }
        if (millis % 1000L == 0L) {
            return Long.toString(millis / 1000L);
        }
        return formatDouble(millis / 1000.0d);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeTier(String input) {
        if (input == null) {
            return "uncommon";
        }
        String tier = input.trim().toLowerCase(Locale.ROOT);
        return switch (tier) {
            case "common", "rare", "epic", "legendary", "uncommon" -> tier;
            default -> "uncommon";
        };
    }

    private static String capitalize(String value) {
        String text = optionalText(value);
        if (text.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requireNonBlank(String value, String errorMessage) {
        String out = optionalText(value);
        if (out.isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return out;
    }

    private static String firstResolvedValue(String primary, String fallback) {
        String primaryValue = optionalText(primary);
        if (!primaryValue.isEmpty() && !looksLikeUiBindingExpression(primaryValue)) {
            return primaryValue;
        }

        String fallbackValue = optionalText(fallback);
        if (!fallbackValue.isEmpty() && !looksLikeUiBindingExpression(fallbackValue)) {
            return fallbackValue;
        }

        return primaryValue;
    }

    private static String resolvedOrFallback(String input, String fallback) {
        String value = optionalText(input);
        if (!value.isEmpty() && !looksLikeUiBindingExpression(value)) {
            return value;
        }
        String fallbackValue = optionalText(fallback);
        if (!fallbackValue.isEmpty() && !looksLikeUiBindingExpression(fallbackValue)) {
            return fallbackValue;
        }
        return "";
    }

    private static boolean looksLikeUiBindingExpression(String value) {
        String out = optionalText(value);
        return out.startsWith("#") && (out.endsWith(".Value") || out.endsWith(".Text"));
    }

    private static int parseRequiredInt(String raw, String errorMessage, int min, int max) {
        try {
            int out = Integer.parseInt(optionalText(raw));
            if (out < min || out > max) {
                throw new IllegalArgumentException(errorMessage);
            }
            return out;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private static long parseRequiredLong(String raw, String errorMessage, long min, long max) {
        try {
            long out = Long.parseLong(optionalText(raw));
            if (out < min || out > max) {
                throw new IllegalArgumentException(errorMessage);
            }
            return out;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private static long parseRequiredSecondsToMillis(String raw, String errorMessage) {
        double seconds = parseRequiredDouble(raw, errorMessage, 0.0d, Double.MAX_VALUE);
        if (seconds > (Long.MAX_VALUE / 1000.0d)) {
            throw new IllegalArgumentException(errorMessage);
        }
        return Math.round(seconds * 1000.0d);
    }

    private static float parseRequiredFloat(String raw, String errorMessage, float min, float max) {
        try {
            float out = Float.parseFloat(optionalText(raw));
            if (!Float.isFinite(out) || out < min || out > max) {
                throw new IllegalArgumentException(errorMessage);
            }
            return out;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private static double parseRequiredDouble(String raw, String errorMessage, double min, double max) {
        try {
            double out = Double.parseDouble(optionalText(raw));
            if (!Double.isFinite(out) || out < min || out > max) {
                throw new IllegalArgumentException(errorMessage);
            }
            return out;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    private static Integer parseOptionalInt(String raw) {
        try {
            String value = optionalText(raw);
            if (value.isEmpty()) {
                return null;
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Float parseOptionalFloat(String raw) {
        try {
            String value = optionalText(raw);
            if (value.isEmpty()) {
                return null;
            }
            float parsed = Float.parseFloat(value);
            return Float.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parseOptionalDouble(String raw) {
        try {
            String value = optionalText(raw);
            if (value.isEmpty()) {
                return null;
            }
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<Arena> snapshotArenas() {
        List<Arena> arenas = new ArrayList<>(ArenaRegistry.getAll());
        arenas.sort(Comparator.comparing(a -> a.arenaId == null ? "" : a.arenaId, String.CASE_INSENSITIVE_ORDER));
        return arenas;
    }

    private static List<String> snapshotBossNames() {
        List<BossDefinition> bosses = new ArrayList<>(BossRegistry.getAll().values());

        List<String> names = new ArrayList<>();
        for (BossDefinition boss : bosses) {
            if (boss != null && boss.bossName != null && !boss.bossName.isBlank()) {
                names.add(boss.bossName);
            }
        }
        return names;
    }

    private String nextBossName() {
        int index = 1;
        while (BossRegistry.exists("boss" + index)) {
            index++;
        }
        return "boss" + index;
    }

    private static String nextArenaId() {
        int index = 1;
        while (ArenaRegistry.exists("arena" + index)) {
            index++;
        }
        return "arena" + index;
    }

    private static BossDefinition cloneBoss(BossDefinition source) {
        BossDefinition out = new BossDefinition();
        out.bossName = source != null ? source.bossName : "";
        out.npcId = source != null ? source.npcId : "";
        out.tier = source != null ? normalizeTier(source.tier) : "uncommon";
        out.amount = source != null ? source.amount : 1;

        out.modifiers = new BossDefinition.Modifiers();
        if (source != null && source.modifiers != null) {
            out.modifiers.hp = source.modifiers.hp;
            out.modifiers.damage = source.modifiers.damage;
            out.modifiers.size = source.modifiers.size;
        }

        out.perPlayerIncrease = new BossDefinition.PerPlayerIncrease();
        if (source != null && source.perPlayerIncrease != null) {
            out.perPlayerIncrease.hp = source.perPlayerIncrease.hp;
            out.perPlayerIncrease.damage = source.perPlayerIncrease.damage;
            out.perPlayerIncrease.size = source.perPlayerIncrease.size;
        }

        out.extraMobs = new BossDefinition.ExtraMobs();
        if (source != null && source.extraMobs != null) {
            out.extraMobs.npcId = source.extraMobs.npcId;
            out.extraMobs.timeLimitMs = source.extraMobs.timeLimitMs;
            out.extraMobs.waves = source.extraMobs.waves;
            out.extraMobs.mobsPerWave = source.extraMobs.mobsPerWave;
            out.extraMobs.adds = new ArrayList<>();
            if (source.extraMobs.adds != null) {
                for (BossDefinition.ExtraMobs.WaveAdd add : source.extraMobs.adds) {
                    if (add == null) {
                        continue;
                    }
                    BossDefinition.ExtraMobs.WaveAdd copy = new BossDefinition.ExtraMobs.WaveAdd();
                    copy.npcId = add.npcId;
                    copy.mobsPerWave = add.mobsPerWave;
                    copy.everyWave = add.everyWave;
                    copy.hp = add.hp;
                    copy.damage = add.damage;
                    copy.size = add.size;
                    out.extraMobs.adds.add(copy);
                }
            }
        }
        out.extraMobs.sanitize();

        return out;
    }

    private static BossWavesSummary buildBossWavesSummary(BossDefinition.ExtraMobs extra) {
        if (extra == null) {
            return new BossWavesSummary("Adds: None", "", "Time: 0s | Waves: 0");
        }
        extra.sanitize();

        List<BossDefinition.ExtraMobs.WaveAdd> adds = extra.getConfiguredAdds();
        String addLine1 = "Adds: None";
        String addLine2 = "";
        if (!adds.isEmpty()) {
            addLine1 = "Adds: " + formatWaveAdd(adds.get(0));
            if (adds.size() >= 2) {
                addLine2 = formatWaveAdd(adds.get(1));
                if (adds.size() > 2) {
                    addLine2 = addLine2 + " | +" + (adds.size() - 2) + " more";
                }
            }
        }

        return new BossWavesSummary(addLine1, addLine2, "");
    }

    private static String formatWaveAdd(BossDefinition.ExtraMobs.WaveAdd add) {
        if (add == null) {
            return "";
        }
        String summary = safeText(add.npcId)
                + " x" + Math.max(1, add.mobsPerWave)
                + " /" + Math.max(1, add.everyWave) + "w";
        float hp = add.hp > 0f ? add.hp : 1.0f;
        float damage = add.damage > 0f ? add.damage : 1.0f;
        float size = add.size > 0f ? add.size : 1.0f;
        if (Math.abs(hp - 1.0f) > 0.0001f || Math.abs(damage - 1.0f) > 0.0001f || Math.abs(size - 1.0f) > 0.0001f) {
            summary += " [" + formatFloat(hp) + "/" + formatFloat(damage) + "/" + formatFloat(size) + "]";
        }
        return summary;
    }

    private static LootTable cloneLoot(LootTable source, String fallbackBossName) {
        LootTable out = new LootTable();
        out.bossName = source != null && source.bossName != null && !source.bossName.isBlank()
                ? source.bossName
                : fallbackBossName;
        out.lootRadius = source != null ? source.lootRadius : 40.0d;
        out.items = new ArrayList<>();

        if (source != null && source.items != null) {
            for (LootItem item : source.items) {
                if (item == null) {
                    continue;
                }
                out.items.add(new LootItem(item.itemId, item.dropChance, item.minAmount, item.maxAmount));
            }
        }

        return out;
    }

    private static String sanitizeTab(String tab) {
        if (TAB_SHOP.equals(tab)) {
            return TAB_SHOP;
        }
        if (TAB_ARENAS.equals(tab)) {
            return TAB_ARENAS;
        }
        return TAB_BOSSES;
    }

    private static final class ShopTableRef {
        private final String worldName;
        private final int x;
        private final int y;
        private final int z;

        private ShopTableRef(String worldName, int x, int y, int z) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class ShopTableEditorState {
        private final ShopTableRef table;
        private final List<String> orderedBossNames;
        private final Set<String> enabledBossNames;
        private String arenaId;

        private ShopTableEditorState(ShopTableRef table,
                                     List<String> orderedBossNames,
                                     Set<String> enabledBossNames,
                                     String arenaId) {
            this.table = table;
            this.orderedBossNames = orderedBossNames;
            this.enabledBossNames = enabledBossNames;
            this.arenaId = arenaId;
        }
    }

    private static final class ShopTableView {
        private final String arenaLabel;
        private final String distanceLabel;
        private final Double distance;
        private final String worldName;
        private final int x;
        private final int y;
        private final int z;
        private final int enabledBosses;
        private final int totalBosses;

        private ShopTableView(String arenaLabel,
                              String distanceLabel,
                              Double distance,
                              String worldName,
                              int x,
                              int y,
                              int z,
                              int enabledBosses,
                              int totalBosses) {
            this.arenaLabel = arenaLabel;
            this.distanceLabel = distanceLabel;
            this.distance = distance;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.enabledBosses = enabledBosses;
            this.totalBosses = totalBosses;
        }
    }

    private static final class BossEditorState {
        private final String originalBossName;
        private final BossDefinition boss;
        private final LootTable loot;

        private BossEditorState(String originalBossName, BossDefinition boss, LootTable loot) {
            this.originalBossName = originalBossName;
            this.boss = boss;
            this.loot = loot;
        }
    }

    private static final class BossWavesSummary {
        private final String addLine1;
        private final String addLine2;
        private final String metaLine;

        private BossWavesSummary(String addLine1, String addLine2, String metaLine) {
            this.addLine1 = addLine1;
            this.addLine2 = addLine2;
            this.metaLine = metaLine;
        }
    }

    public static final class ConfigEventData {
        public String action;

        public String arenaName;
        public String arenaX;
        public String arenaY;
        public String arenaZ;
        public String shopEditArenaId;

        public String bossEditName;
        public String bossEditNpcId;
        public String bossEditTier;
        public String bossEditAmount;
        public String bossEditHp;
        public String bossEditDamage;
        public String bossEditSize;
        public String bossEditPpHp;
        public String bossEditPpDamage;
        public String bossEditPpSize;
        public String bossEditWaves;
        public String bossEditExtraNpcId;
        public String bossEditExtraTimeLimit;
        public String bossEditExtraWaves;
        public String bossEditExtraMobsPerWave;
        public String bossEditLootRadius;

        public String bossWaveTimeSec;
        public String bossWaveNpc1;
        public String bossWaveAmount1;
        public String bossWaveEvery1;
        public String bossWaveHp1;
        public String bossWaveDamage1;
        public String bossWaveSize1;
        public String bossWaveNpc2;
        public String bossWaveAmount2;
        public String bossWaveEvery2;
        public String bossWaveHp2;
        public String bossWaveDamage2;
        public String bossWaveSize2;
        public String bossWaveNpc3;
        public String bossWaveAmount3;
        public String bossWaveEvery3;
        public String bossWaveHp3;
        public String bossWaveDamage3;
        public String bossWaveSize3;
        public String bossWaveNpc4;
        public String bossWaveAmount4;
        public String bossWaveEvery4;
        public String bossWaveHp4;
        public String bossWaveDamage4;
        public String bossWaveSize4;
        public String bossWaveNpc5;
        public String bossWaveAmount5;
        public String bossWaveEvery5;
        public String bossWaveHp5;
        public String bossWaveDamage5;
        public String bossWaveSize5;
        public String bossWaveNpc6;
        public String bossWaveAmount6;
        public String bossWaveEvery6;
        public String bossWaveHp6;
        public String bossWaveDamage6;
        public String bossWaveSize6;

        public String bossLootName1;
        public String bossLootMin1;
        public String bossLootMax1;
        public String bossLootChance1;

        public String bossLootName2;
        public String bossLootMin2;
        public String bossLootMax2;
        public String bossLootChance2;

        public String bossLootName3;
        public String bossLootMin3;
        public String bossLootMax3;
        public String bossLootChance3;

        public String bossLootName4;
        public String bossLootMin4;
        public String bossLootMax4;
        public String bossLootChance4;

        public String bossLootName5;
        public String bossLootMin5;
        public String bossLootMax5;
        public String bossLootChance5;

        public String bossLootName6;
        public String bossLootMin6;
        public String bossLootMax6;
        public String bossLootChance6;

        public String bossLootName7;
        public String bossLootMin7;
        public String bossLootMax7;
        public String bossLootChance7;

        public String bossLootName8;
        public String bossLootMin8;
        public String bossLootMax8;
        public String bossLootChance8;

        public String getBossLootName(int row) {
            return switch (row) {
                case 1 -> bossLootName1;
                case 2 -> bossLootName2;
                case 3 -> bossLootName3;
                case 4 -> bossLootName4;
                case 5 -> bossLootName5;
                case 6 -> bossLootName6;
                case 7 -> bossLootName7;
                case 8 -> bossLootName8;
                default -> "";
            };
        }

        public String getBossLootMin(int row) {
            return switch (row) {
                case 1 -> bossLootMin1;
                case 2 -> bossLootMin2;
                case 3 -> bossLootMin3;
                case 4 -> bossLootMin4;
                case 5 -> bossLootMin5;
                case 6 -> bossLootMin6;
                case 7 -> bossLootMin7;
                case 8 -> bossLootMin8;
                default -> "";
            };
        }

        public String getBossLootMax(int row) {
            return switch (row) {
                case 1 -> bossLootMax1;
                case 2 -> bossLootMax2;
                case 3 -> bossLootMax3;
                case 4 -> bossLootMax4;
                case 5 -> bossLootMax5;
                case 6 -> bossLootMax6;
                case 7 -> bossLootMax7;
                case 8 -> bossLootMax8;
                default -> "";
            };
        }

        public String getBossLootChance(int row) {
            return switch (row) {
                case 1 -> bossLootChance1;
                case 2 -> bossLootChance2;
                case 3 -> bossLootChance3;
                case 4 -> bossLootChance4;
                case 5 -> bossLootChance5;
                case 6 -> bossLootChance6;
                case 7 -> bossLootChance7;
                case 8 -> bossLootChance8;
                default -> "";
            };
        }

        public String getBossWaveNpc(int row) {
            return switch (row) {
                case 1 -> bossWaveNpc1;
                case 2 -> bossWaveNpc2;
                case 3 -> bossWaveNpc3;
                case 4 -> bossWaveNpc4;
                case 5 -> bossWaveNpc5;
                case 6 -> bossWaveNpc6;
                default -> "";
            };
        }

        public String getBossWaveAmount(int row) {
            return switch (row) {
                case 1 -> bossWaveAmount1;
                case 2 -> bossWaveAmount2;
                case 3 -> bossWaveAmount3;
                case 4 -> bossWaveAmount4;
                case 5 -> bossWaveAmount5;
                case 6 -> bossWaveAmount6;
                default -> "";
            };
        }

        public String getBossWaveEvery(int row) {
            return switch (row) {
                case 1 -> bossWaveEvery1;
                case 2 -> bossWaveEvery2;
                case 3 -> bossWaveEvery3;
                case 4 -> bossWaveEvery4;
                case 5 -> bossWaveEvery5;
                case 6 -> bossWaveEvery6;
                default -> "";
            };
        }

        public String getBossWaveHp(int row) {
            return switch (row) {
                case 1 -> bossWaveHp1;
                case 2 -> bossWaveHp2;
                case 3 -> bossWaveHp3;
                case 4 -> bossWaveHp4;
                case 5 -> bossWaveHp5;
                case 6 -> bossWaveHp6;
                default -> "";
            };
        }

        public String getBossWaveDamage(int row) {
            return switch (row) {
                case 1 -> bossWaveDamage1;
                case 2 -> bossWaveDamage2;
                case 3 -> bossWaveDamage3;
                case 4 -> bossWaveDamage4;
                case 5 -> bossWaveDamage5;
                case 6 -> bossWaveDamage6;
                default -> "";
            };
        }

        public String getBossWaveSize(int row) {
            return switch (row) {
                case 1 -> bossWaveSize1;
                case 2 -> bossWaveSize2;
                case 3 -> bossWaveSize3;
                case 4 -> bossWaveSize4;
                case 5 -> bossWaveSize5;
                case 6 -> bossWaveSize6;
                default -> "";
            };
        }

        public static final BuilderCodec<ConfigEventData> CODEC = BuilderCodec.builder(
                        ConfigEventData.class,
                        ConfigEventData::new
                )
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()

                .append(new KeyedCodec<>("@ArenaName", Codec.STRING), (d, v) -> d.arenaName = v, d -> d.arenaName).add()
                .append(new KeyedCodec<>("@ArenaX", Codec.STRING), (d, v) -> d.arenaX = v, d -> d.arenaX).add()
                .append(new KeyedCodec<>("@ArenaY", Codec.STRING), (d, v) -> d.arenaY = v, d -> d.arenaY).add()
                .append(new KeyedCodec<>("@ArenaZ", Codec.STRING), (d, v) -> d.arenaZ = v, d -> d.arenaZ).add()
                .append(new KeyedCodec<>("@ShopEditArenaId", Codec.STRING), (d, v) -> d.shopEditArenaId = v, d -> d.shopEditArenaId).add()

                .append(new KeyedCodec<>("@BossEditName", Codec.STRING), (d, v) -> d.bossEditName = v, d -> d.bossEditName).add()
                .append(new KeyedCodec<>("@BossEditNpcId", Codec.STRING), (d, v) -> d.bossEditNpcId = v, d -> d.bossEditNpcId).add()
                .append(new KeyedCodec<>("@BossEditTier", Codec.STRING), (d, v) -> d.bossEditTier = v, d -> d.bossEditTier).add()
                .append(new KeyedCodec<>("@BossEditAmount", Codec.STRING), (d, v) -> d.bossEditAmount = v, d -> d.bossEditAmount).add()
                .append(new KeyedCodec<>("@BossEditHp", Codec.STRING), (d, v) -> d.bossEditHp = v, d -> d.bossEditHp).add()
                .append(new KeyedCodec<>("@BossEditDamage", Codec.STRING), (d, v) -> d.bossEditDamage = v, d -> d.bossEditDamage).add()
                .append(new KeyedCodec<>("@BossEditSize", Codec.STRING), (d, v) -> d.bossEditSize = v, d -> d.bossEditSize).add()
                .append(new KeyedCodec<>("@BossEditPpHp", Codec.STRING), (d, v) -> d.bossEditPpHp = v, d -> d.bossEditPpHp).add()
                .append(new KeyedCodec<>("@BossEditPpDamage", Codec.STRING), (d, v) -> d.bossEditPpDamage = v, d -> d.bossEditPpDamage).add()
                .append(new KeyedCodec<>("@BossEditPpSize", Codec.STRING), (d, v) -> d.bossEditPpSize = v, d -> d.bossEditPpSize).add()
                .append(new KeyedCodec<>("@BossEditWaves", Codec.STRING), (d, v) -> d.bossEditWaves = v, d -> d.bossEditWaves).add()
                .append(new KeyedCodec<>("@BossEditExtraNpcId", Codec.STRING), (d, v) -> d.bossEditExtraNpcId = v, d -> d.bossEditExtraNpcId).add()
                .append(new KeyedCodec<>("@BossEditExtraTimeLimit", Codec.STRING), (d, v) -> d.bossEditExtraTimeLimit = v, d -> d.bossEditExtraTimeLimit).add()
                .append(new KeyedCodec<>("@BossEditExtraWaves", Codec.STRING), (d, v) -> d.bossEditExtraWaves = v, d -> d.bossEditExtraWaves).add()
                .append(new KeyedCodec<>("@BossEditExtraMobsPerWave", Codec.STRING), (d, v) -> d.bossEditExtraMobsPerWave = v, d -> d.bossEditExtraMobsPerWave).add()
                .append(new KeyedCodec<>("@BossEditLootRadius", Codec.STRING), (d, v) -> d.bossEditLootRadius = v, d -> d.bossEditLootRadius).add()

                .append(new KeyedCodec<>("@BossWaveTimeSec", Codec.STRING), (d, v) -> d.bossWaveTimeSec = v, d -> d.bossWaveTimeSec).add()
                .append(new KeyedCodec<>("@BossWaveNpc1", Codec.STRING), (d, v) -> d.bossWaveNpc1 = v, d -> d.bossWaveNpc1).add()
                .append(new KeyedCodec<>("@BossWaveAmount1", Codec.STRING), (d, v) -> d.bossWaveAmount1 = v, d -> d.bossWaveAmount1).add()
                .append(new KeyedCodec<>("@BossWaveEvery1", Codec.STRING), (d, v) -> d.bossWaveEvery1 = v, d -> d.bossWaveEvery1).add()
                .append(new KeyedCodec<>("@BossWaveHp1", Codec.STRING), (d, v) -> d.bossWaveHp1 = v, d -> d.bossWaveHp1).add()
                .append(new KeyedCodec<>("@BossWaveDamage1", Codec.STRING), (d, v) -> d.bossWaveDamage1 = v, d -> d.bossWaveDamage1).add()
                .append(new KeyedCodec<>("@BossWaveSize1", Codec.STRING), (d, v) -> d.bossWaveSize1 = v, d -> d.bossWaveSize1).add()
                .append(new KeyedCodec<>("@BossWaveNpc2", Codec.STRING), (d, v) -> d.bossWaveNpc2 = v, d -> d.bossWaveNpc2).add()
                .append(new KeyedCodec<>("@BossWaveAmount2", Codec.STRING), (d, v) -> d.bossWaveAmount2 = v, d -> d.bossWaveAmount2).add()
                .append(new KeyedCodec<>("@BossWaveEvery2", Codec.STRING), (d, v) -> d.bossWaveEvery2 = v, d -> d.bossWaveEvery2).add()
                .append(new KeyedCodec<>("@BossWaveHp2", Codec.STRING), (d, v) -> d.bossWaveHp2 = v, d -> d.bossWaveHp2).add()
                .append(new KeyedCodec<>("@BossWaveDamage2", Codec.STRING), (d, v) -> d.bossWaveDamage2 = v, d -> d.bossWaveDamage2).add()
                .append(new KeyedCodec<>("@BossWaveSize2", Codec.STRING), (d, v) -> d.bossWaveSize2 = v, d -> d.bossWaveSize2).add()
                .append(new KeyedCodec<>("@BossWaveNpc3", Codec.STRING), (d, v) -> d.bossWaveNpc3 = v, d -> d.bossWaveNpc3).add()
                .append(new KeyedCodec<>("@BossWaveAmount3", Codec.STRING), (d, v) -> d.bossWaveAmount3 = v, d -> d.bossWaveAmount3).add()
                .append(new KeyedCodec<>("@BossWaveEvery3", Codec.STRING), (d, v) -> d.bossWaveEvery3 = v, d -> d.bossWaveEvery3).add()
                .append(new KeyedCodec<>("@BossWaveHp3", Codec.STRING), (d, v) -> d.bossWaveHp3 = v, d -> d.bossWaveHp3).add()
                .append(new KeyedCodec<>("@BossWaveDamage3", Codec.STRING), (d, v) -> d.bossWaveDamage3 = v, d -> d.bossWaveDamage3).add()
                .append(new KeyedCodec<>("@BossWaveSize3", Codec.STRING), (d, v) -> d.bossWaveSize3 = v, d -> d.bossWaveSize3).add()
                .append(new KeyedCodec<>("@BossWaveNpc4", Codec.STRING), (d, v) -> d.bossWaveNpc4 = v, d -> d.bossWaveNpc4).add()
                .append(new KeyedCodec<>("@BossWaveAmount4", Codec.STRING), (d, v) -> d.bossWaveAmount4 = v, d -> d.bossWaveAmount4).add()
                .append(new KeyedCodec<>("@BossWaveEvery4", Codec.STRING), (d, v) -> d.bossWaveEvery4 = v, d -> d.bossWaveEvery4).add()
                .append(new KeyedCodec<>("@BossWaveHp4", Codec.STRING), (d, v) -> d.bossWaveHp4 = v, d -> d.bossWaveHp4).add()
                .append(new KeyedCodec<>("@BossWaveDamage4", Codec.STRING), (d, v) -> d.bossWaveDamage4 = v, d -> d.bossWaveDamage4).add()
                .append(new KeyedCodec<>("@BossWaveSize4", Codec.STRING), (d, v) -> d.bossWaveSize4 = v, d -> d.bossWaveSize4).add()
                .append(new KeyedCodec<>("@BossWaveNpc5", Codec.STRING), (d, v) -> d.bossWaveNpc5 = v, d -> d.bossWaveNpc5).add()
                .append(new KeyedCodec<>("@BossWaveAmount5", Codec.STRING), (d, v) -> d.bossWaveAmount5 = v, d -> d.bossWaveAmount5).add()
                .append(new KeyedCodec<>("@BossWaveEvery5", Codec.STRING), (d, v) -> d.bossWaveEvery5 = v, d -> d.bossWaveEvery5).add()
                .append(new KeyedCodec<>("@BossWaveHp5", Codec.STRING), (d, v) -> d.bossWaveHp5 = v, d -> d.bossWaveHp5).add()
                .append(new KeyedCodec<>("@BossWaveDamage5", Codec.STRING), (d, v) -> d.bossWaveDamage5 = v, d -> d.bossWaveDamage5).add()
                .append(new KeyedCodec<>("@BossWaveSize5", Codec.STRING), (d, v) -> d.bossWaveSize5 = v, d -> d.bossWaveSize5).add()
                .append(new KeyedCodec<>("@BossWaveNpc6", Codec.STRING), (d, v) -> d.bossWaveNpc6 = v, d -> d.bossWaveNpc6).add()
                .append(new KeyedCodec<>("@BossWaveAmount6", Codec.STRING), (d, v) -> d.bossWaveAmount6 = v, d -> d.bossWaveAmount6).add()
                .append(new KeyedCodec<>("@BossWaveEvery6", Codec.STRING), (d, v) -> d.bossWaveEvery6 = v, d -> d.bossWaveEvery6).add()
                .append(new KeyedCodec<>("@BossWaveHp6", Codec.STRING), (d, v) -> d.bossWaveHp6 = v, d -> d.bossWaveHp6).add()
                .append(new KeyedCodec<>("@BossWaveDamage6", Codec.STRING), (d, v) -> d.bossWaveDamage6 = v, d -> d.bossWaveDamage6).add()
                .append(new KeyedCodec<>("@BossWaveSize6", Codec.STRING), (d, v) -> d.bossWaveSize6 = v, d -> d.bossWaveSize6).add()

                .append(new KeyedCodec<>("@BossLootName1", Codec.STRING), (d, v) -> d.bossLootName1 = v, d -> d.bossLootName1).add()
                .append(new KeyedCodec<>("@BossLootMin1", Codec.STRING), (d, v) -> d.bossLootMin1 = v, d -> d.bossLootMin1).add()
                .append(new KeyedCodec<>("@BossLootMax1", Codec.STRING), (d, v) -> d.bossLootMax1 = v, d -> d.bossLootMax1).add()
                .append(new KeyedCodec<>("@BossLootChance1", Codec.STRING), (d, v) -> d.bossLootChance1 = v, d -> d.bossLootChance1).add()

                .append(new KeyedCodec<>("@BossLootName2", Codec.STRING), (d, v) -> d.bossLootName2 = v, d -> d.bossLootName2).add()
                .append(new KeyedCodec<>("@BossLootMin2", Codec.STRING), (d, v) -> d.bossLootMin2 = v, d -> d.bossLootMin2).add()
                .append(new KeyedCodec<>("@BossLootMax2", Codec.STRING), (d, v) -> d.bossLootMax2 = v, d -> d.bossLootMax2).add()
                .append(new KeyedCodec<>("@BossLootChance2", Codec.STRING), (d, v) -> d.bossLootChance2 = v, d -> d.bossLootChance2).add()

                .append(new KeyedCodec<>("@BossLootName3", Codec.STRING), (d, v) -> d.bossLootName3 = v, d -> d.bossLootName3).add()
                .append(new KeyedCodec<>("@BossLootMin3", Codec.STRING), (d, v) -> d.bossLootMin3 = v, d -> d.bossLootMin3).add()
                .append(new KeyedCodec<>("@BossLootMax3", Codec.STRING), (d, v) -> d.bossLootMax3 = v, d -> d.bossLootMax3).add()
                .append(new KeyedCodec<>("@BossLootChance3", Codec.STRING), (d, v) -> d.bossLootChance3 = v, d -> d.bossLootChance3).add()

                .append(new KeyedCodec<>("@BossLootName4", Codec.STRING), (d, v) -> d.bossLootName4 = v, d -> d.bossLootName4).add()
                .append(new KeyedCodec<>("@BossLootMin4", Codec.STRING), (d, v) -> d.bossLootMin4 = v, d -> d.bossLootMin4).add()
                .append(new KeyedCodec<>("@BossLootMax4", Codec.STRING), (d, v) -> d.bossLootMax4 = v, d -> d.bossLootMax4).add()
                .append(new KeyedCodec<>("@BossLootChance4", Codec.STRING), (d, v) -> d.bossLootChance4 = v, d -> d.bossLootChance4).add()

                .append(new KeyedCodec<>("@BossLootName5", Codec.STRING), (d, v) -> d.bossLootName5 = v, d -> d.bossLootName5).add()
                .append(new KeyedCodec<>("@BossLootMin5", Codec.STRING), (d, v) -> d.bossLootMin5 = v, d -> d.bossLootMin5).add()
                .append(new KeyedCodec<>("@BossLootMax5", Codec.STRING), (d, v) -> d.bossLootMax5 = v, d -> d.bossLootMax5).add()
                .append(new KeyedCodec<>("@BossLootChance5", Codec.STRING), (d, v) -> d.bossLootChance5 = v, d -> d.bossLootChance5).add()

                .append(new KeyedCodec<>("@BossLootName6", Codec.STRING), (d, v) -> d.bossLootName6 = v, d -> d.bossLootName6).add()
                .append(new KeyedCodec<>("@BossLootMin6", Codec.STRING), (d, v) -> d.bossLootMin6 = v, d -> d.bossLootMin6).add()
                .append(new KeyedCodec<>("@BossLootMax6", Codec.STRING), (d, v) -> d.bossLootMax6 = v, d -> d.bossLootMax6).add()
                .append(new KeyedCodec<>("@BossLootChance6", Codec.STRING), (d, v) -> d.bossLootChance6 = v, d -> d.bossLootChance6).add()

                .append(new KeyedCodec<>("@BossLootName7", Codec.STRING), (d, v) -> d.bossLootName7 = v, d -> d.bossLootName7).add()
                .append(new KeyedCodec<>("@BossLootMin7", Codec.STRING), (d, v) -> d.bossLootMin7 = v, d -> d.bossLootMin7).add()
                .append(new KeyedCodec<>("@BossLootMax7", Codec.STRING), (d, v) -> d.bossLootMax7 = v, d -> d.bossLootMax7).add()
                .append(new KeyedCodec<>("@BossLootChance7", Codec.STRING), (d, v) -> d.bossLootChance7 = v, d -> d.bossLootChance7).add()

                .append(new KeyedCodec<>("@BossLootName8", Codec.STRING), (d, v) -> d.bossLootName8 = v, d -> d.bossLootName8).add()
                .append(new KeyedCodec<>("@BossLootMin8", Codec.STRING), (d, v) -> d.bossLootMin8 = v, d -> d.bossLootMin8).add()
                .append(new KeyedCodec<>("@BossLootMax8", Codec.STRING), (d, v) -> d.bossLootMax8 = v, d -> d.bossLootMax8).add()
                .append(new KeyedCodec<>("@BossLootChance8", Codec.STRING), (d, v) -> d.bossLootChance8 = v, d -> d.bossLootChance8).add()
                .build();
    }
}
