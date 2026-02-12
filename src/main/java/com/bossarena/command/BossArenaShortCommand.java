package com.bossarena.command;

import com.bossarena.BossArenaPlugin;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public final class BossArenaShortCommand extends AbstractCommand {

  public BossArenaShortCommand(BossArenaPlugin plugin) {
    super("ba", "Shortcut for /bossarena");  // CHANGED from "bs" to "ba"

    // Register all the same subcommands as BossArenaCommand
    addSubCommand(new BossArenaCommand.ArenaRoot(plugin));
    addSubCommand(new BossArenaCommand.SpawnBoss(plugin));
    addSubCommand(new BossArenaCommand.Reload(plugin));
    addSubCommand(new BossArenaCommand.ShopRoot(plugin));
  }

  @Override
  protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
    ctx.sendMessage(com.hypixel.hytale.server.core.Message.raw(
            "Use: /ba arena <create|delete|list> OR /ba spawn <bossId> [arenaId] OR /ba reload"
    ));
    return CompletableFuture.completedFuture(null);
  }
}