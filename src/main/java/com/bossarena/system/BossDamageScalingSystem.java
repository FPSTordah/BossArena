package com.bossarena.system;

import com.bossarena.boss.BossModifiers;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public final class BossDamageScalingSystem extends DamageEventSystem {
    private static final float EPSILON = 0.0001f;

    private final BossTrackingSystem trackingSystem;

    public BossDamageScalingSystem(BossTrackingSystem trackingSystem) {
        this.trackingSystem = trackingSystem;
    }

    @Override
    @Nullable
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.and(AllLegacyLivingEntityTypesQuery.INSTANCE, UUIDComponent.getComponentType());
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Damage damage) {
        if (trackingSystem == null || damage == null) {
            return;
        }

        UUID targetUuid = extractTargetUuid(index, archetypeChunk);
        BossModifiers targetMods = trackingSystem.getEntityModifiers(targetUuid);
        BossModifiers sourceMods = extractSourceModifiers(damage, store);

        if (sourceMods != null) {
            float current = Math.max(0.0f, damage.getAmount());
            float scaled = current * clampMultiplier(sourceMods.damageMultiplier());
            if (Float.isFinite(scaled)) {
                damage.setAmount(Math.max(0.0f, scaled));
            }
        }

        KnockbackComponent knockback = damage.getIfPresentMetaObject(Damage.KNOCKBACK_COMPONENT);
        if (knockback == null) {
            return;
        }

        if (sourceMods != null) {
            float knockbackGiven = clampMultiplier(sourceMods.knockbackGivenMultiplier());
            if (Math.abs(knockbackGiven - 1.0f) > EPSILON) {
                knockback.addModifier(knockbackGiven);
            }
        }

        if (targetMods != null) {
            float knockbackTaken = clampMultiplier(targetMods.knockbackTakenMultiplier());
            if (Math.abs(knockbackTaken - 1.0f) > EPSILON) {
                knockback.addModifier(knockbackTaken);
            }
        }
    }

    private static UUID extractTargetUuid(int index, ArchetypeChunk<EntityStore> archetypeChunk) {
        Object targetUuidObj = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        if (targetUuidObj instanceof UUIDComponent targetUuidComp) {
            return targetUuidComp.getUuid();
        }
        return null;
    }

    private BossModifiers extractSourceModifiers(Damage damage, Store<EntityStore> store) {
        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource entitySource)) {
            return null;
        }

        var sourceRef = entitySource.getRef();
        if (sourceRef == null || !sourceRef.isValid()) {
            return null;
        }

        Object sourceUuidObj = store.getComponent(sourceRef, UUIDComponent.getComponentType());
        if (sourceUuidObj instanceof UUIDComponent sourceUuidComp) {
            return trackingSystem.getEntityModifiers(sourceUuidComp.getUuid());
        }
        return null;
    }

    private static float clampMultiplier(float value) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            return 1.0f;
        }
        return value;
    }
}
