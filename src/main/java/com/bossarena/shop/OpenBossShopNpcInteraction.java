package com.bossarena.shop;

import com.bossarena.BossArenaPlugin;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class OpenBossShopNpcInteraction extends SimpleInstantInteraction {
    public static final BuilderCodec<OpenBossShopNpcInteraction> CODEC = BuilderCodec.builder(
            OpenBossShopNpcInteraction.class,
            OpenBossShopNpcInteraction::new,
            SimpleInstantInteraction.CODEC
    ).build();

    public OpenBossShopNpcInteraction() {
        super(BossArenaPlugin.SHOP_OPEN_INTERACTION_ID);
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type,
                            @Nonnull InteractionContext context,
                            @Nonnull CooldownHandler cooldownHandler) {
        Ref<EntityStore> playerRef = context.getEntity();
        if (playerRef == null) {
            return;
        }

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Store<EntityStore> store = commandBuffer.getStore();
        Object playerObj = commandBuffer.getComponent(playerRef, Player.getComponentType());
        if (!(playerObj instanceof Player player)) {
            return;
        }

        BossArenaPlugin plugin = BossArenaPlugin.getInstance();
        if (plugin == null) {
            return;
        }

        Ref<EntityStore> targetRef = context.getTargetEntity();
        Object transformObj = targetRef != null
                ? commandBuffer.getComponent(targetRef, TransformComponent.getComponentType())
                : null;
        if (!(transformObj instanceof TransformComponent transform)) {
            BossArenaShopPage.open(playerRef, store, player, plugin);
            return;
        }

        Vector3d npcPosition = transform.getPosition();
        String worldName = player.getWorld() != null ? player.getWorld().getName() : null;
        if (worldName == null || worldName.isBlank()) {
            BossArenaShopPage.open(playerRef, store, player, plugin);
            return;
        }

        int x = (int) Math.floor(npcPosition.x);
        int y = (int) Math.floor(npcPosition.y);
        int z = (int) Math.floor(npcPosition.z);

        java.util.UUID npcUuid = null;
        if (targetRef != null) {
            Object uuidObj = commandBuffer.getComponent(targetRef, UUIDComponent.getComponentType());
            if (uuidObj instanceof UUIDComponent uuidComponent) {
                npcUuid = uuidComponent.getUuid();
            }
        }

        plugin.recordShopLocation(worldName, new com.hypixel.hytale.math.vector.Vector3i(x, y, z), npcUuid);
        BossArenaShopPage.openAtTable(playerRef, store, player, plugin, worldName, x, y, z);
    }
}
