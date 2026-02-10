package com.bossarena.command;

import com.bossarena.BossArenaPlugin;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public final class BossArenaShortCommand extends AbstractCommand {

  public BossArenaShortCommand(@SuppressWarnings("unused") BossArenaPlugin plugin) {
    super("bs", "Alias for /bossarena");
  }

  @Override
  protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
    CommandSender sender = resolveSender(ctx);
    if (sender == null) {
      ctx.sendMessage(Message.raw("Could not resolve sender."));
      return CompletableFuture.completedFuture(null);
    }

    String rest = resolveRawArgs(ctx);
    String cmd = rest.isBlank() ? "bossarena" : "bossarena " + rest;

    CommandManager.get().handleCommand(sender, cmd);
    return CompletableFuture.completedFuture(null);
  }

  //@SuppressWarnings("unchecked")
  private static CommandSender resolveSender(CommandContext ctx) {
    try {
      java.lang.reflect.Method m = ctx.getClass().getMethod("getSender");
      Object o = m.invoke(ctx);
      if (o instanceof CommandSender cs) return cs;
    } catch (Throwable ignored) {
    }
    return null;
  }

  //@SuppressWarnings("SpellCheckingInspection")
  private static String resolveRawArgs(CommandContext ctx) {
    for (String name : new String[]{"getRawArgs", "getArgumentsString", "getRemainingArgs"}) {
      try {
        java.lang.reflect.Method m = ctx.getClass().getMethod(name);
        Object v = m.invoke(ctx);
        if (v instanceof String s) return s;
      } catch (Throwable ignored) {
      }
    }
    return "";
  }
}