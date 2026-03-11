package com.bossarena.data;

import com.bossarena.util.NotificationRadiusConstants;
import com.hypixel.hytale.math.vector.Vector3d;

public class Arena {
    public String arenaId;
    public String worldName;
    public double x;
    public double y;
    public double z;
    /** Distance (blocks) within which players see boss event title/subtitle for this arena. */
    public double notificationRadius = NotificationRadiusConstants.DEFAULT;

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

    /** Returns notification radius in blocks, clamped to valid range. Missing or invalid values default to 100. */
    public double getNotificationRadius() {
        return NotificationRadiusConstants.clamp(notificationRadius);
    }

    @Override
    public String toString() {
        return String.format("Arena[%s @ %.1f, %.1f, %.1f in %s]", arenaId, x, y, z, worldName);
    }
}