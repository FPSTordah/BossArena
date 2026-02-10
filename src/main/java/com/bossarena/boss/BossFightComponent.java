package com.bossarena.boss;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class BossFightComponent implements Component<EntityStore>, Cloneable {
    public float hpMultiplier = 1.0f;

    public static final BuilderCodec<BossFightComponent> CODEC = BuilderCodec.builder(BossFightComponent.class, BossFightComponent::new)
            .append(new KeyedCodec<>("HpMultiplier", Codec.FLOAT), (c, v) -> c.hpMultiplier = v, c -> c.hpMultiplier)
            .add()
            .build();

    // Cache the component type so it's only registered once
    private static ComponentType<EntityStore, BossFightComponent> COMPONENT_TYPE = null;

    public static ComponentType<EntityStore, BossFightComponent> getComponentType() {
        if (COMPONENT_TYPE == null) {
            COMPONENT_TYPE = EntityStore.REGISTRY.registerComponent(
                    BossFightComponent.class,
                    "bossarena:boss_fight",
                    CODEC
            );
        }
        return COMPONENT_TYPE;
    }

    @Override
    public BossFightComponent clone() {
        try {
            return (BossFightComponent) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}