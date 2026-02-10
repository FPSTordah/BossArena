package com.bossarena.command;

import com.bossarena.BossArenaPlugin;
import com.bossarena.data.BossDefinition;
import com.bossarena.data.BossRegistry;
import com.bossarena.config.ConfigEditor;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeVector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.math.vector.Transform;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.lang.reflect.Method;

public final class BossArenaCommand extends AbstractCommand {

  public BossArenaCommand(BossArenaPlugin plugin) {
    super("bossarena", "BossArena admin commands");

    addSubCommand(new ArenaRoot(plugin));
    addSubCommand(new SpawnBoss(plugin));
    addSubCommand(new Reload(plugin));
  }

  // =====================
  // Reflection helpers (API drift)
  // =====================
  @SuppressWarnings("JavaReflectionMemberAccess")
  private static CommandSender getSender(CommandContext ctx) {
    if (ctx == null) return null;
    try {
      Method m = ctx.getClass().getMethod("getSender");
      Object o = m.invoke(ctx);
      if (o instanceof CommandSender cs) return cs;
    } catch (Throwable ignored) {
    }

    // fallback: some builds make the sender itself the context or expose senderAs
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
      // Get PlayerRef from Player (deprecated but works)
      PlayerRef playerRef = player.getPlayerRef();

      if (playerRef != null) {
        // Get Transform from PlayerRef
        Transform transform = playerRef.getTransform();

        if (transform != null) {
          // Get position from Transform
          Vector3d position = transform.getPosition();
          System.out.println("DEBUG: Got position successfully: " + position);
          return position;
        }
      }
    } catch (Throwable t) {
      System.out.println("DEBUG: Failed to get position: " + t.getMessage());
      t.printStackTrace();
    }

    System.out.println("DEBUG: All position methods failed, returning (0,0,0)");
    return new Vector3d(0, 0, 0);
  }

  private static Vector3i getPlayerBlockPosition(Player player) {
    Vector3d p = getPlayerPosition(player);
    return new Vector3i((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
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
  private static final class ArenaRoot extends AbstractCommand {

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
    private final RequiredArg<String> worldArg;
    private final RequiredArg<RelativeVector3i> posArg;
    private final RequiredArg<Integer> radiusArg;
    private final RequiredArg<Integer> lifetimeArg;

    ArenaCreate(BossArenaPlugin plugin) {
      super("create", "Create arena: /bossarena arena create <id> <world> <x y z> <radius> <lifetimeSeconds>");
      this.plugin = plugin;

      this.idArg = withRequiredArg("id", "Arena id", ArgTypes.STRING);
      this.worldArg = withRequiredArg("world", "World name", ArgTypes.STRING);
      this.posArg = withRequiredArg("pos", "Center position (relative ok)", ArgTypes.RELATIVE_VECTOR3I);
      this.radiusArg = withRequiredArg("radius", "Arena radius", ArgTypes.INTEGER);
      this.lifetimeArg = withRequiredArg("lifetime", "Lifetime seconds", ArgTypes.INTEGER);
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      if (!ctx.isPlayer()) {
        ctx.sendMessage(Message.raw("Player-only command."));
        return CompletableFuture.completedFuture(null);
      }

      Player player = ctx.senderAs(Player.class);

      String id = ctx.get(idArg);
      String worldName = ctx.get(worldArg);
      RelativeVector3i rel = ctx.get(posArg);
      int r = ctx.get(radiusArg);
      int life = ctx.get(lifetimeArg);

      if (id == null || id.isBlank()) {
        ctx.sendMessage(Message.raw("Arena id cannot be empty."));
        return CompletableFuture.completedFuture(null);
      }

      World world = player.getWorld();
      if (world == null) {
        ctx.sendMessage(Message.raw("Could not resolve player world."));
        return CompletableFuture.completedFuture(null);
      }
      if (worldName != null && !worldName.isBlank() && !worldName.equalsIgnoreCase(world.getName())) {
        ctx.sendMessage(Message.raw("This build doesn't support cross-world targeting. You're in: " + world.getName()));
        return CompletableFuture.completedFuture(null);
      }

      Vector3i resolved;
      try {
        resolved = rel.resolve(getPlayerBlockPosition(player));
      } catch (Exception e) {
        ctx.sendMessage(Message.raw("Failed to resolve position: " + e.getMessage()));
        return CompletableFuture.completedFuture(null);
      }

      try {
        ConfigEditor.createArena(
                plugin,
                id,
                world.getName(),
                resolved.x,
                resolved.y,
                resolved.z,
                r,
                life
        );

        ctx.sendMessage(Message.raw(
                "Arena created: " + id +
                        " @ " + resolved.x + " " + resolved.y + " " + resolved.z
        ));
      } catch (Exception e) {
        ctx.sendMessage(Message.raw("Failed: " + e.getMessage()));
      }

      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class ArenaDelete extends AbstractCommand {
    private final BossArenaPlugin plugin;
    private final RequiredArg<String> idArg;

    ArenaDelete(BossArenaPlugin plugin) {
      super("delete", "Delete arena: /bossarena arena delete <id>");
      this.plugin = plugin;
      this.idArg = withRequiredArg("id", "Arena id", ArgTypes.STRING);
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      String id = ctx.get(idArg);
      if (id == null || id.isBlank()) {
        ctx.sendMessage(Message.raw("Usage: /bossarena arena delete <id>"));
        return CompletableFuture.completedFuture(null);
      }

      try {
        boolean ok = ConfigEditor.deleteArena(plugin, id);
        ctx.sendMessage(Message.raw(ok ? "Arena deleted: " + id : "Arena not found: " + id));
      } catch (Exception e) {
        ctx.sendMessage(Message.raw("Failed: " + e.getMessage()));
      }

      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class ArenaList extends AbstractCommand {
    private final BossArenaPlugin plugin;

    ArenaList(BossArenaPlugin plugin) {
      super("list", "List arenas: /bossarena arena list");
      this.plugin = plugin;
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      try {
        var list = ConfigEditor.listArenas(plugin);
        if (list.isEmpty()) {
          ctx.sendMessage(Message.raw("No arenas configured."));
        } else {
          ctx.sendMessage(Message.raw("Arenas: " + String.join(", ", list)));
        }
      } catch (Exception e) {
        ctx.sendMessage(Message.raw("Failed: " + e.getMessage()));
      }
      return CompletableFuture.completedFuture(null);
    }
  }

  // ============================================================
  // /bossarena spawn <bossId>
  // ============================================================
  private static final class SpawnBoss extends AbstractCommand {
    private final BossArenaPlugin plugin;
    private final RequiredArg<String> commonNameArg;

    SpawnBoss(BossArenaPlugin plugin) {
      super("spawn", "Spawn a boss from JSON registry: /bossarena spawn <bossId>");
      this.plugin = plugin;
      this.commonNameArg = withRequiredArg(
              "bossId",
              "Boss id from boss JSON (e.g. sand_warlord)",
              ArgTypes.STRING
      );
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      if (!ctx.isPlayer()) {
        ctx.sendMessage(Message.raw("Player-only command."));
        return CompletableFuture.completedFuture(null);
      }

      Player player = ctx.senderAs(Player.class);
      String bossId = ctx.get(commonNameArg);

      if (bossId == null || bossId.isBlank()) {
        ctx.sendMessage(Message.raw("Usage: /bossarena spawn <bossId>"));
        return CompletableFuture.completedFuture(null);
      }

      BossDefinition def = BossRegistry.get(bossId);
      if (def == null) {
        ctx.sendMessage(Message.raw("Unknown boss id: " + bossId));
        return CompletableFuture.completedFuture(null);
      }

      if (def.npcId == null || def.npcId.isBlank()) {
        ctx.sendMessage(Message.raw("Boss '" + bossId + "' is missing npcId in its JSON definition."));
        return CompletableFuture.completedFuture(null);
      }

      World world = player.getWorld();
      if (world == null) {
        ctx.sendMessage(Message.raw("Could not resolve player world."));
        return CompletableFuture.completedFuture(null);
      }

      Vector3d spawnPos = BossArenaCommand.getPlayerPosition(player).add(0, 2, 0);
      ctx.sendMessage(Message.raw("Spawning at: " + spawnPos));

      CommandSender sender = BossArenaCommand.getSender(ctx);
      if (sender == null) {
        ctx.sendMessage(Message.raw("Could not resolve command sender."));
        return CompletableFuture.completedFuture(null);
      }

      // IMPORTANT: run on world thread
      world.execute(() -> {
        UUID uuid = plugin.getBossSpawnService().spawnBossFromJson(
                sender,
                bossId,
                world,
                spawnPos
        );

        if (uuid == null) {
          ctx.sendMessage(Message.raw("Spawn failed for '" + bossId + "'."));
        } else {
          ctx.sendMessage(Message.raw("Spawned boss: " + bossId + " (UUID: " + uuid + ")"));
        }
      });

      ctx.sendMessage(Message.raw("Spawning boss: " + bossId + "..."));
      return CompletableFuture.completedFuture(null);
    }
  }

  // ============================================================
  // /bossarena reload
  // ============================================================
  private static final class Reload extends AbstractCommand {
    private final BossArenaPlugin plugin;

    Reload(BossArenaPlugin plugin) {
      super("reload", "Reload BossArenaConfig and boss definitions");
      this.plugin = plugin;
    }

    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
      try {
        // 1. Reload BossArenaConfig.json
        var config = plugin.getConfigHandle();
        config.save(); // First, save the current state
        config.load(); // Then, reload from disk
        ctx.sendMessage(Message.raw("BossArenaConfig reloaded."));
      } catch (Exception err) {
        plugin.getLogger()
                .atWarning()
                .withCause(err)
                .log("BossArenaConfig reload failed.");
        ctx.sendMessage(Message.raw("BossArenaConfig reload failed (see server logs)."));
      }

      // 2. Reload boss definitions (JSON-driven)
      plugin.reloadBossDefinitions().handle((count, err) -> {
        if (err != null) {
          plugin.getLogger()
                  .atWarning()
                  .withCause(err)
                  .log("Boss definitions reload failed.");
          ctx.sendMessage(Message.raw("Boss definitions reload failed (see server logs)."));
        } else {
          ctx.sendMessage(Message.raw("Boss definitions reloaded (" + count + " bosses)."));
        }
        return null;
      });

      return CompletableFuture.completedFuture(null);
    }
  }
}