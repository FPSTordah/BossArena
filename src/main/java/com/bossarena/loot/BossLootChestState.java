package com.bossarena.loot;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BossLootChestState extends ItemContainerState {
    public static final BuilderCodec<BossLootChestState> CODEC = BuilderCodec.builder(
            BossLootChestState.class,
            BossLootChestState::new,
            BlockState.BASE_CODEC
    ).build();

    private static final ThreadLocal<UUID> LAST_OPEN_UUID = new ThreadLocal<>();
    protected Map<UUID, ItemContainer> playerContainers = new ConcurrentHashMap<>();
    private Vector3d chestLocation;

    public BossLootChestState() {
        super();
    }

    public BossLootChestState(Vector3d location) {
        this();
        this.chestLocation = location;
        System.out.println("[BossArena] Created BossLootChestState at " + location);
    }

    public ItemContainer getItemContainer(Player playerComponent, UUID playerUuid) {
        System.out.println("[BossArena] getItemContainer called for player " + playerUuid);
        return getOrCreateContainer(playerUuid);
    }

    @Override
    public ItemContainer getItemContainer() {
        UUID playerUuid = LAST_OPEN_UUID.get();
        if (playerUuid == null) {
            return super.getItemContainer();
        }
        LAST_OPEN_UUID.remove();
        return getOrCreateContainer(playerUuid);
    }

    @Override
    public boolean canOpen(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        UUIDComponent uuidComponent = (UUIDComponent) accessor.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent != null) {
            LAST_OPEN_UUID.set(uuidComponent.getUuid());
        } else {
            LAST_OPEN_UUID.remove();
        }
        if (getChunk() != null) {
            BossLootHandler.scheduleChestExpiry(
                    getChunk().getWorld(),
                    new Vector3d(getBlockX(), getBlockY(), getBlockZ())
            );
        }
        return super.canOpen(ref, accessor);
    }

    private ItemContainer getOrCreateContainer(UUID playerUuid) {
        // Check if we already created a container for this player
        ItemContainer cached = playerContainers.get(playerUuid);
        if (cached != null) {
            System.out.println("[BossArena] Returning existing container");
            return cached;
        }

        // Create new container for this player
        ItemContainer container = new SimpleItemContainer((short) 27); // Standard chest size

        // Get the loot for this player
        Vector3d lookupLocation = chestLocation;
        if (lookupLocation == null) {
            lookupLocation = new Vector3d(getBlockX(), getBlockY(), getBlockZ());
            chestLocation = lookupLocation;
        }
        List<GeneratedLoot> loot = BossLootHandler.claimLoot(
                getChunk() != null ? getChunk().getWorld() : null,
                lookupLocation,
                playerUuid
        );

        System.out.println("[BossArena] Retrieved loot: " + (loot != null ? loot.size() + " items" : "null"));

        if (loot != null && !loot.isEmpty()) {
            // Fill the container with items
            int slot = 0;
            for (GeneratedLoot item : loot) {
                if (slot >= 27) break; // Don't overflow the chest

                System.out.println("[BossArena] Adding " + item.amount + "x " + item.itemId + " to slot " + slot);

                try {
                    ItemStack stack = new ItemStack(item.itemId, item.amount);
                    container.setItemStackForSlot((short) slot, stack);
                    slot++;
                } catch (Exception e) {
                    System.err.println("[BossArena] Error creating ItemStack for " + item.itemId + ": " + e.getMessage());
                }
            }
            System.out.println("[BossArena] Added " + slot + " items to container");
        } else {
            System.out.println("[BossArena] No loot found for player at location " + chestLocation);
        }

        // Cache the container
        playerContainers.put(playerUuid, container);

        return container;
    }
}
