package com.bossarena.damagechart;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.bossarena.system.BossTrackingSystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Records damage from players to tracked bosses/adds into BossDamageChartTracker.
 * Runs in the same damage pipeline as BossDamageScalingSystem (records final scaled amount).
 */
public final class BossDamageChartRecordingSystem extends DamageEventSystem {

    private final BossTrackingSystem trackingSystem;
    private final BossDamageChartTracker tracker;

    public BossDamageChartRecordingSystem(BossTrackingSystem trackingSystem, BossDamageChartTracker tracker) {
        this.trackingSystem = trackingSystem;
        this.tracker = tracker;
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
        if (trackingSystem == null || tracker == null || damage == null) {
            return;
        }

        UUID targetUuid = extractTargetUuid(index, archetypeChunk);
        if (targetUuid == null) {
            return;
        }

        UUID eventId = trackingSystem.getEventIdForTrackedEntity(targetUuid);
        if (eventId == null) {
            return;
        }

        UUID playerUuid = extractPlayerUuidFromSource(damage, store);
        if (playerUuid == null) {
            return;
        }

        float amount = damage.getAmount();
        if (!Float.isFinite(amount) || amount <= 0f) {
            return;
        }
        tracker.addDamage(eventId, playerUuid, Math.round(amount));
    }

    private static UUID extractTargetUuid(int index, ArchetypeChunk<EntityStore> archetypeChunk) {
        Object targetUuidObj = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        if (targetUuidObj instanceof UUIDComponent targetUuidComp) {
            return targetUuidComp.getUuid();
        }
        return null;
    }

    @Nullable
    private static UUID extractPlayerUuidFromSource(Damage damage, Store<EntityStore> store) {
        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource entitySource)) {
            return null;
        }
        var sourceRef = entitySource.getRef();
        if (sourceRef == null || !sourceRef.isValid()) {
            return null;
        }
        Object playerObj = store.getComponent(sourceRef, Player.getComponentType());
        if (playerObj == null) {
            return null;
        }
        Object uuidObj = store.getComponent(sourceRef, UUIDComponent.getComponentType());
        if (uuidObj instanceof UUIDComponent uuidComp) {
            return uuidComp.getUuid();
        }
        return null;
    }
}
