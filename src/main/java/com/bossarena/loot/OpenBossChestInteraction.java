package com.bossarena.loot;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class OpenBossChestInteraction extends SimpleBlockInteraction {
    private static final Logger LOGGER = Logger.getLogger("BossArena");

    public static final BuilderCodec<OpenBossChestInteraction> CODEC = BuilderCodec.builder(
            OpenBossChestInteraction.class,
            OpenBossChestInteraction::new,
            SimpleBlockInteraction.CODEC
    ).build();

    public OpenBossChestInteraction() {
        super("BossArena_OpenChest");
    }

    @Override
    protected void interactWithBlock(@Nonnull World world,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull InteractionType type,
                                     @Nonnull InteractionContext context,
                                     @Nullable ItemStack itemInHand,
                                     @Nonnull Vector3i pos,
                                     @Nonnull CooldownHandler cooldownHandler) {

        Ref<EntityStore> ref = context.getEntity();
        Store<EntityStore> store = ref.getStore();
        Player playerComponent = (Player) commandBuffer.getComponent(ref, Player.getComponentType());

        if (playerComponent == null) {
            return;
        }

        LOGGER.info("Player opening boss chest at " + pos);

        // Get the block state
        BlockState state = world.getState(pos.x, pos.y, pos.z, true);

        if (!(state instanceof BossLootChestState)) {
            LOGGER.warning("Block state is not BossLootChestState!");
            return;
        }

        BossLootChestState chestState = (BossLootChestState) state;
        BlockType blockType = world.getBlockType(pos.x, pos.y, pos.z);

        if (!chestState.isAllowViewing() || !chestState.canOpen(ref, commandBuffer)) {
            return;
        }

        // Get player UUID
        UUIDComponent uuidComponent = (UUIDComponent) commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return;
        }

        UUID playerUuid = uuidComponent.getUuid();
        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(pos.x, pos.z));

        // Create the window for this player
        ContainerBlockWindow window = new ContainerBlockWindow(
                pos.x, pos.y, pos.z,
                chunk.getRotationIndex(pos.x, pos.y, pos.z),
                blockType,
                chestState.getItemContainer(playerComponent, playerUuid)
        );

        Map<UUID, ContainerBlockWindow> windows = chestState.getWindows();

        if (windows.putIfAbsent(playerUuid, window) == null) {
            if (playerComponent.getPageManager().setPageWithWindows(ref, store, Page.Bench, true, new Window[]{window})) {

                // Register close event
                window.registerCloseEvent((event) -> {
                    windows.remove(playerUuid, window);
                    BlockType currentBlockType = world.getBlockType(pos);

                    BossLootHandler.cleanupChestIfEmpty(world, new Vector3d(pos.x, pos.y, pos.z));
                    if (windows.isEmpty()) {
                        world.setBlockInteractionState(pos, currentBlockType, "CloseWindow");
                    }

                    playSound(world, pos, currentBlockType, "CloseWindow", chunk, blockType, ref, commandBuffer);
                });

                // Open the chest
                if (windows.size() == 1) {
                    world.setBlockInteractionState(pos, blockType, "OpenWindow");
                }

                playSound(world, pos, blockType, "OpenWindow", chunk, blockType, ref, commandBuffer);
            } else {
                windows.remove(playerUuid, window);
            }
        }

        chestState.onOpen(ref, world, store);
    }

    @Override
    protected void simulateInteractWithBlock(@Nonnull InteractionType type,
                                             @Nonnull InteractionContext context,
                                             @Nullable ItemStack itemInHand,
                                             @Nonnull World world,
                                             @Nonnull Vector3i targetBlock) {
        // Nothing to simulate
    }

    private void playSound(World world, Vector3i pos, BlockType blockType, String stateName,
                           WorldChunk chunk, BlockType originalBlockType,
                           Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        BlockType interactionState = blockType.getBlockForState(stateName);
        if (interactionState != null) {
            int soundEventIndex = interactionState.getInteractionSoundEventIndex();
            if (soundEventIndex != 0) {
                int rotationIndex = chunk.getRotationIndex(pos.x, pos.y, pos.z);
                Vector3d soundPos = new Vector3d();
                originalBlockType.getBlockCenter(rotationIndex, soundPos);
                soundPos.add(pos);
                SoundUtil.playSoundEvent3d(ref, soundEventIndex, soundPos, commandBuffer);
            }
        }
    }
}
