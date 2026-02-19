package com.bossarena.shop;

import com.bossarena.BossArenaPlugin;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class OpenBossShopInteraction extends SimpleBlockInteraction {
    public static final BuilderCodec<OpenBossShopInteraction> CODEC = BuilderCodec.builder(
            OpenBossShopInteraction.class,
            OpenBossShopInteraction::new,
            SimpleBlockInteraction.CODEC
    ).build();

    public OpenBossShopInteraction() {
        super("BossArena_OpenShop");
    }

    @Override
    protected void interactWithBlock(@Nonnull World world,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull InteractionType type,
                                     @Nonnull InteractionContext context,
                                     @Nullable ItemStack itemInHand,
                                     @Nonnull Vector3i pos,
                                     @Nonnull CooldownHandler cooldownHandler) {
        if (!isShopOpenInteraction(type)) {
            return;
        }

        Ref<EntityStore> ref = context.getEntity();
        Store<EntityStore> store = ref.getStore();
        Player player = (Player) commandBuffer.getComponent(ref, Player.getComponentType());
        BossArenaPlugin plugin = BossArenaPlugin.getInstance();

        if (player == null || plugin == null) {
            return;
        }

        BossArenaShopPage.open(ref, store, player, plugin);
    }

    private static boolean isShopOpenInteraction(InteractionType type) {
        return type == InteractionType.Use;
    }

    @Override
    protected void simulateInteractWithBlock(@Nonnull InteractionType type,
                                             @Nonnull InteractionContext context,
                                             @Nullable ItemStack itemInHand,
                                             @Nonnull World world,
                                             @Nonnull Vector3i targetBlock) {
        // Client prediction not needed for this interaction.
    }
}
