package com.bossarena.util;

/**
 * Shared constants and clamping for boss event notification radius (blocks).
 * Used by global config, per-arena config, and UI validation so bounds stay in sync.
 */
public final class NotificationRadiusConstants {
    public static final double DEFAULT = 100.0d;
    public static final double MIN = 10.0d;
    public static final double MAX = 500.0d;

    private NotificationRadiusConstants() {}

    /** Returns value clamped to [MIN, MAX]; invalid values become DEFAULT. */
    public static double clamp(double value) {
        if (!Double.isFinite(value) || value < MIN) {
            return DEFAULT;
        }
        return Math.min(value, MAX);
    }
}
