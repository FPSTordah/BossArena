package com.bossarena.command;

import com.bossarena.BossArenaPlugin;
import com.bossarena.data.Arena;
import com.bossarena.data.ArenaRegistry;
import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.bossarena.shop.ShopEntry;
import com.bossarena.shop.ShopRegistry;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.lang.reflect.Method;

public final class BossArenaCommand extends AbstractCommand {

  private final BossArenaPlugin plugin;

  public BossArenaCommand(BossArenaPlugin plugin) {
    super("bossarena", "BossArena admin commands");
    this.plugin = plugin;

    addSubCommand(new ArenaRoot(plugin));
    addSubCommand(new SpawnBoss(plugin));
    addSubCommand(new Reload(plugin));
    addSubCommand(new ShopRoot(plugin));
  }

  @SuppressWarnings("JavaReflectionMemberAccess")
  private static CommandSender getSender(CommandContext ctx) {
    if (ctx == null) return null;
    try {
      Method m = ctx.getClass().getMethod("getSender");
      Object o = m.invoke(ctx);
      if (o instanceof CommandSender cs) return cs;
    } catch (Throwable ignored) {
    }

    try {
      if (ctx.isPlayer()) {
        return ctx.senderAs(Player.class);
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  @SuppressWarnings({"SpellCheckingInspection", "deprecation"})
  private static Vector3d getPlayerPosition(Player player) {
    if (player == null) return new Vector3d(0, 0, 0);

    try {
      PlayerRef playerRef = player.getPlayerRef();
      if (playerRef != null) {
        Transform transform = playerRef.getTransform();
        if (transform != null) {
          Vector3d position = transform.getPosition();
          Vector3d copy = new Vector3d(position.x, position.y, position.z);
          System.out.println("DEBUG: Got position successfully: " + copy);
          return copy;
        }
      }
    } catch (Throwable t) {
      System.out.println("DEBUG: Failed to get position: " + t.getMessage());
      t.printStackTrace();
    }

    System.out.println("DEBUG: All position methods failed, returning (0,0,0)");
    return new Vector3d(0, 0, 0);
  }

  @Override
  protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
    ctx.sendMessage(Message.raw(
            "Use: /bossarena arena <create|delete|list> OR /bossarena spawn <id> OR /bossarena reload"
    ));
    return CompletableFuture.completedFuture(null);
  }

  // ============================================================
  // /bossarena arena ...
  // ============================================================
  static final class ArenaRoot extends AbstractCommand {

    ArenaRoot(BossArenaPlugin plugin) {
      super("arena", "Arena management");
      addSubCommand(new ArenaCreate(plugin));
      addSubCommand(new ArenaDelete(plugin));
      addSubCommand(new ArenaList(plugin));
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      ctx.sendMessage(Message.raw("Use: /bossarena arena <create|delete|list>"));
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class ArenaCreate extends AbstractCommand {
    private final BossArenaPlugin plugin;
    private final RequiredArg<String> idArg;

    ArenaCreate(BossArenaPlugin plugin) {
      super("create", "Create arena at your location: /bossarena arena create <arenaId>");
      this.plugin = plugin;
      this.idArg = withRequiredArg("arenaId", "Unique arena identifier", ArgTypes.STRING);
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      if (!ctx.isPlayer()) {
        ctx.sendMessage(Message.raw("Only players can create arenas"));
        return CompletableFuture.completedFuture(null);
      }

      Player player = ctx.senderAs(Player.class);
      String arenaId = ctx.get(idArg);

      if (arenaId == null || arenaId.isBlank()) {
        ctx.sendMessage(Message.raw("Arena ID cannot be empty"));
        return CompletableFuture.completedFuture(null);
      }

      if (ArenaRegistry.exists(arenaId)) {
        ctx.sendMessage(Message.raw("Arena '" + arenaId + "' already exists!"));
        return CompletableFuture.completedFuture(null);
      }

      World world = player.getWorld();
      if (world == null) {
        ctx.sendMessage(Message.raw("Could not resolve player world"));
        return CompletableFuture.completedFuture(null);
      }

      Vector3d position = BossArenaCommand.getPlayerPosition(player);
      String worldName = world.getName(); // Instead of world.getWorldConfig().getName()

      Arena arena = new Arena(arenaId, worldName, position);
      ArenaRegistry.register(arena);

      plugin.saveArenas().thenRun(() -> {
        ctx.sendMessage(Message.raw("✓ Created arena '" + arenaId + "' at your location"));
        ctx.sendMessage(Message.raw(String.format("  Position: %.1f, %.1f, %.1f in %s",
                position.x, position.y, position.z, worldName)));
      });

      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class ArenaDelete extends AbstractCommand {
    private final BossArenaPlugin plugin;
    private final RequiredArg<String> idArg;

    ArenaDelete(BossArenaPlugin plugin) {
      super("delete", "Delete an arena: /bossarena arena delete <arenaId>");
      this.plugin = plugin;
      this.idArg = withRequiredArg("arenaId", "Arena to delete", ArgTypes.STRING);
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      String arenaId = ctx.get(idArg);

      if (arenaId == null || arenaId.isBlank()) {
        ctx.sendMessage(Message.raw("Usage: /bossarena arena delete <arenaId>"));
        return CompletableFuture.completedFuture(null);
      }

      if (!ArenaRegistry.exists(arenaId)) {
        ctx.sendMessage(Message.raw("Arena '" + arenaId + "' does not exist!"));
        return CompletableFuture.completedFuture(null);
      }

      Arena removed = ArenaRegistry.remove(arenaId);

      plugin.saveArenas().thenRun(() -> {
        ctx.sendMessage(Message.raw("✓ Deleted arena '" + arenaId + "'"));
        if (removed != null) {
          ctx.sendMessage(Message.raw("  Was at: " + removed.toString()));
        }
      });

      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class ArenaList extends AbstractCommand {
    ArenaList(BossArenaPlugin plugin) {
      super("list", "List all arenas: /bossarena arena list");
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      Collection<Arena> arenas = ArenaRegistry.getAll();

      if (arenas.isEmpty()) {
        ctx.sendMessage(Message.raw("No arenas registered"));
        return CompletableFuture.completedFuture(null);
      }

      ctx.sendMessage(Message.raw("=== Registered Arenas (" + arenas.size() + ") ==="));
      for (Arena arena : arenas) {
        ctx.sendMessage(Message.raw("  • " + arena.toString()));
      }

      return CompletableFuture.completedFuture(null);
    }
  }

  // ============================================================
  // /bossarena spawn <bossId>
  // ============================================================
  static final class SpawnBoss extends AbstractCommand {
    private final BossArenaPlugin plugin;
    private final RequiredArg<String> bossIdArg;
    private final RequiredArg<String> locationArg;  // Changed to required

    SpawnBoss(BossArenaPlugin plugin) {
      super("spawn", "Spawn a boss: /spawn <bossId> <arena|here>");
      this.plugin = plugin;

      this.bossIdArg = withRequiredArg("bossId", "Boss ID to spawn", ArgTypes.STRING);
      this.locationArg = withRequiredArg("location", "Arena ID or 'here' for current location", ArgTypes.STRING);
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      if (!ctx.isPlayer()) {
        ctx.sendMessage(Message.raw("This command can only be used by players."));
        return CompletableFuture.completedFuture(null);
      }

      Player player = ctx.senderAs(Player.class);
      String bossId = ctx.get(bossIdArg);
      String location = ctx.get(locationArg);

      World world = player.getWorld();
      Vector3d spawnPos = BossArenaCommand.getPlayerPosition(player);
      BossDefinition def = BossRegistry.get(bossId);

      if (def == null) {
        ctx.sendMessage(Message.raw("§cBoss '" + bossId + "' not found in registry."));
        return CompletableFuture.completedFuture(null);
      }

      if (def.npcId == null || def.npcId.isBlank()) {
        ctx.sendMessage(Message.raw("§cBoss '" + bossId + "' has no npcId set."));
        return CompletableFuture.completedFuture(null);
      }

      // Check if using arena or current location
      String finalArenaId = null;
      if (!location.equalsIgnoreCase("here")) {
        // It's an arena ID
        var arena = ArenaRegistry.get(location);
        if (arena == null) {
          ctx.sendMessage(Message.raw("§cArena '" + location + "' not found. Use 'here' to spawn at your location."));
          return CompletableFuture.completedFuture(null);
        }
        spawnPos = arena.getPosition();
        finalArenaId = location;
        ctx.sendMessage(Message.raw("Spawning at arena: " + location));
      } else {
        ctx.sendMessage(Message.raw("Spawning at your location"));
      }

      final Vector3d finalSpawnPos = spawnPos;
      final String arenaId = finalArenaId;

      world.execute(() -> {
        UUID uuid = plugin.getBossSpawnService().spawnBossFromJson(
                player,
                bossId,
                world,
                finalSpawnPos,
                arenaId
        );

        if (uuid == null) {
          ctx.sendMessage(Message.raw("§cSpawn failed for '" + bossId + "'."));
        } else {
          ctx.sendMessage(Message.raw("§aSpawned boss: " + bossId + " (UUID: " + uuid + ")"));
        }
      });

      ctx.sendMessage(Message.raw("Spawning boss: " + bossId + "..."));
      return CompletableFuture.completedFuture(null);
    }
  }

  // ============================================================
  // /bossarena reload
  // ============================================================
  static final class Reload extends AbstractCommand {
    private final BossArenaPlugin plugin;

    Reload(BossArenaPlugin plugin) {
      super("reload", "Reload config and boss definitions");
      this.plugin = plugin;
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      try {
        var config = plugin.getConfigHandle();
        config.save();
        config.load();
        ctx.sendMessage(Message.raw("✓ Config reloaded"));
      } catch (Exception err) {
        ctx.sendMessage(Message.raw("✗ Config reload failed"));
      }

      plugin.reloadBossDefinitions().handle((count, err) -> {
        if (err != null) {
          ctx.sendMessage(Message.raw("✗ Boss definitions reload failed"));
        } else {
          ctx.sendMessage(Message.raw("✓ Reloaded " + count + " boss definitions"));
        }
        return null;
      });

      plugin.reloadArenas().handle((count, err) -> {
        if (err != null) {
          ctx.sendMessage(Message.raw("✗ Arena reload failed"));
        } else {
          ctx.sendMessage(Message.raw("✓ Reloaded " + count + " arenas"));
        }
        return null;
      });

      // ADD THIS BLOCK:
      plugin.reloadLootTables().handle((count, err) -> {
        if (err != null) {
          ctx.sendMessage(Message.raw("✗ Loot tables reload failed"));
        } else {
          ctx.sendMessage(Message.raw("✓ Reloaded " + count + " loot tables"));
        }
        return null;
      });

      return CompletableFuture.completedFuture(null);
    }
  }

  // ============================================================
  // /bossarena shop ...
  // ============================================================
  static final class ShopRoot extends AbstractCommand {

    ShopRoot(BossArenaPlugin plugin) {
      super("shop", "Shop management");
      addSubCommand(new ShopAdd(plugin));
      addSubCommand(new ShopRemove(plugin));
      addSubCommand(new ShopList(plugin));
      addSubCommand(new ShopOpen(plugin));
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      ctx.sendMessage(Message.raw("Use: /bossarena shop <add|remove|list|open>"));
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class ShopAdd extends AbstractCommand {
    private final BossArenaPlugin plugin;
    private final RequiredArg<String> arenaIdArg;
    private final RequiredArg<String> bossIdArg;
    private final RequiredArg<Double> costArg;

    ShopAdd(BossArenaPlugin plugin) {
      super("add", "Add shop entry: /bossarena shop add <arenaId> <bossId> <cost>");
      this.plugin = plugin;
      this.arenaIdArg = withRequiredArg("arenaId", "Arena ID", ArgTypes.STRING);
      this.bossIdArg = withRequiredArg("bossId", "Boss ID", ArgTypes.STRING);
      this.costArg = withRequiredArg("cost", "Cost in currency", ArgTypes.DOUBLE);
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      String arenaId = ctx.get(arenaIdArg);
      String bossId = ctx.get(bossIdArg);
      Double cost = ctx.get(costArg);

      if (!ArenaRegistry.exists(arenaId)) {
        ctx.sendMessage(Message.raw("Arena '" + arenaId + "' does not exist!"));
        return CompletableFuture.completedFuture(null);
      }

      if (!BossRegistry.exists(bossId)) {
        ctx.sendMessage(Message.raw("Boss '" + bossId + "' does not exist!"));
        return CompletableFuture.completedFuture(null);
      }

      ShopEntry entry = new ShopEntry(arenaId, bossId, cost);
      ShopRegistry.register(entry);

      //plugin.saveShops().thenRun(() -> {
      //  ctx.sendMessage(Message.raw("✓ Added shop entry: " + entry.toString()));
      //});

      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class ShopRemove extends AbstractCommand {
    private final BossArenaPlugin plugin;
    private final RequiredArg<String> arenaIdArg;
    private final RequiredArg<String> bossIdArg;

    ShopRemove(BossArenaPlugin plugin) {
      super("remove", "Remove shop entry: /bossarena shop remove <arenaId> <bossId>");
      this.plugin = plugin;
      this.arenaIdArg = withRequiredArg("arenaId", "Arena ID", ArgTypes.STRING);
      this.bossIdArg = withRequiredArg("bossId", "Boss ID", ArgTypes.STRING);
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      String arenaId = ctx.get(arenaIdArg);
      String bossId = ctx.get(bossIdArg);

      ShopEntry existing = ShopRegistry.find(arenaId, bossId);
      if (existing == null) {
        ctx.sendMessage(Message.raw("No shop entry found for " + bossId + " at " + arenaId));
        return CompletableFuture.completedFuture(null);
      }

      ShopRegistry.remove(arenaId, bossId);

      //plugin.saveShops().thenRun(() -> {
      //  ctx.sendMessage(Message.raw("✓ Removed shop entry: " + existing.toString()));
      //});

      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class ShopList extends AbstractCommand {
    ShopList(BossArenaPlugin plugin) {
      super("list", "List all shop entries: /bossarena shop list");
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      List<ShopEntry> entries = ShopRegistry.getAll();

      if (entries.isEmpty()) {
        ctx.sendMessage(Message.raw("No shop entries"));
        return CompletableFuture.completedFuture(null);
      }

      ctx.sendMessage(Message.raw("=== Shop Entries (" + entries.size() + ") ==="));
      for (ShopEntry entry : entries) {
        ctx.sendMessage(Message.raw("  • " + entry.toString()));
      }

      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class ShopOpen extends AbstractCommand {
    private final BossArenaPlugin plugin;

    ShopOpen(BossArenaPlugin plugin) {
      super("open", "Open the shop GUI: /bossarena shop open");
      this.plugin = plugin;
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      if (!ctx.isPlayer()) {
        ctx.sendMessage(Message.raw("Player-only command"));
        return CompletableFuture.completedFuture(null);
      }

      Player player = ctx.senderAs(Player.class);

      //gui.open(player);

      return CompletableFuture.completedFuture(null);
    }
  }
}
