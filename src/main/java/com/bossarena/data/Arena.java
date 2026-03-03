package com.bossarena.data;

import com.hypixel.hytale.math.vector.Vector3d;

public class Arena {
    private static final double DEFAULT_NOTIFICATION_RADIUS = 100.0d;
    private static final double MIN_NOTIFICATION_RADIUS = 10.0d;
    private static final double MAX_NOTIFICATION_RADIUS = 500.0d;

    public String arenaId;
    public String worldName;
    public double x;
    public double y;
    public double z;
    /** Distance (blocks) within which players see boss event title/subtitle for this arena. */
    public double notificationRadius = DEFAULT_NOTIFICATION_RADIUS;

    // For GSON
    public Arena() {}

    public Arena(String arenaId, String worldName, Vector3d position) {
        this.arenaId = arenaId;
        this.worldName = worldName;
        this.x = position.x;
        this.y = position.y;
        this.z = position.z;
    }

    public Vector3d getPosition() {
        return new Vector3d(x, y, z);
    }

    /** Returns notification radius in blocks, clamped to [10, 500]. Missing or invalid values default to 100. */
    public double getNotificationRadius() {
        if (!Double.isFinite(notificationRadius) || notificationRadius < MIN_NOTIFICATION_RADIUS) {
            return DEFAULT_NOTIFICATION_RADIUS;
        }
        return Math.min(notificationRadius, MAX_NOTIFICATION_RADIUS);
    }

    @Override
    public String toString() {
        return String.format("Arena[%s @ %.1f, %.1f, %.1f in %s]", arenaId, x, y, z, worldName);
    }
}