package com.bossarena.system;

import com.bossarena.boss.BossFightComponent;
import com.bossarena.boss.BossModifiers;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.logging.Logger;

public class BossDamageSystem extends DamageEventSystem {
    private static final Logger LOGGER = Logger.getLogger("BossArena");
    private final BossTrackingSystem trackingSystem;

    public BossDamageSystem(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer, Damage damage) {
        // Check if damage source is a boss
        Damage.Source source = damage.getSource();
        if (source instanceof Damage.EntitySource entitySource) {
            Ref<EntityStore> sourceRef = entitySource.getRef();
            if (sourceRef != null && sourceRef.isValid()) {
                // Check if source has BossFightComponent (is a boss)
                Object bossFightObj = store.getComponent(sourceRef, BossFightComponent.getComponentType());

                if (bossFightObj instanceof BossFightComponent) {
                    // This damage is from a boss - get its UUID
                    Object uuidObj = store.getComponent(sourceRef, UUIDComponent.getComponentType());

                    if (uuidObj instanceof UUIDComponent uuidComp) {
                        UUID bossUuid = uuidComp.getUuid();

                        // Get boss modifiers from tracking system
                        BossModifiers mods = trackingSystem.getModifiers(bossUuid);

                        if (mods != null) {
                            float damageMultiplier = mods.damageMultiplier();

                            if (damageMultiplier != 1.0f) {
                                float originalDamage = damage.getAmount();
                                float newDamage = originalDamage * damageMultiplier;
                                damage.setAmount(newDamage);

                                LOGGER.info("Boss damage modified: " + originalDamage + " -> " + newDamage + " (x" + damageMultiplier + ")");
                            }
                        }
                    }
                }
            }
        }
    }
}