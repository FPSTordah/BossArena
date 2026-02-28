package com.bossarena.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BossDefinition {
    public String bossName;
    public String npcId;
    public String tier = "uncommon";
    public int amount;
    // 0 = default RPGLeveling behavior, >=1 forces a specific spawn level.
    public int levelOverride = 0;

    public Modifiers modifiers = new Modifiers();
    public PerPlayerIncrease perPlayerIncrease = new PerPlayerIncrease();
    public ExtraMobs extraMobs = new ExtraMobs();

    public static class Modifiers {
        public float hp = 1.0f;
        public float damage = 1.0f;
        public float movementSpeed = 1.0f;
        public float size = 1.0f;
        public float attackRate = 1.0f;
        public float abilityCooldown = 1.0f;
        public float knockbackGiven = 1.0f;
        public float knockbackTaken = 1.0f;
        public float turnRate = 1.0f;
        public float regen = 1.0f;
    }

    public static class PerPlayerIncrease {
        public float hp = 0.0f;
        public float damage = 0.0f;
        public float movementSpeed = 0.0f;
        public float size = 0.0f;
        public float attackRate = 0.0f;
        public float abilityCooldown = 0.0f;
        public float knockbackGiven = 0.0f;
        public float knockbackTaken = 0.0f;
        public float turnRate = 0.0f;
        public float regen = 0.0f;
    }

    public static class ExtraMobs {
        public static final String TRIGGER_BEFORE_BOSS = "before_boss";
        public static final String TRIGGER_ON_SPAWN = "on_spawn";
        public static final String TRIGGER_AFTER_SPAWN_SECONDS = "after_spawn_seconds";
        public static final String TRIGGER_SINCE_LAST_WAVE = "since_last_wave";
        public static final String TRIGGER_BOSS_HP_PERCENT = "boss_hp_percent";

        public String npcId;
        public long timeLimitMs;
        public int waves;
        public int mobsPerWave = 3;
        public boolean useRandomSpawnLocations = true;
        public double randomSpawnRadius = 15.0d;
        // New format: multiple add definitions with per-wave cadence.
        public List<WaveAdd> adds = new ArrayList<>();
        // Trigger-based wave schedule. If empty, legacy fields are auto-migrated.
        public List<ScheduledWave> scheduledWaves = new ArrayList<>();

        public static class WaveAdd {
            public String npcId;
            public int mobsPerWave = 3;
            // 1 = every wave, 2 = every 2nd wave, etc.
            public int everyWave = 1;
            public float hp = 1.0f;
            public float damage = 1.0f;
            public float size = 1.0f;
        }

        public static class ScheduledWave {
            public String trigger = TRIGGER_AFTER_SPAWN_SECONDS;
            // Seconds for time-based triggers, HP percent for hp trigger.
            public double triggerValue = 0.0d;
            // Number of executions. -1 = infinite.
            public int repeatCount = 1;
            // Repeat period in seconds for repeated schedules.
            public double repeatEverySeconds = 0.0d;
            public List<WaveAdd> adds = new ArrayList<>();
        }

        public void sanitize() {
            if (timeLimitMs < 0L) {
                timeLimitMs = 0L;
            }
            if (waves < -1) {
                waves = -1;
            }
            if (mobsPerWave < 1) {
                mobsPerWave = 3;
            }
            randomSpawnRadius = sanitizeWaveRandomSpawnRadius(randomSpawnRadius);

            if (adds == null) {
                adds = new ArrayList<>();
            }
            if (scheduledWaves == null) {
                scheduledWaves = new ArrayList<>();
            }

            List<WaveAdd> cleaned = new ArrayList<>();
            for (WaveAdd add : adds) {
                WaveAdd sanitized = sanitizeWaveAdd(add, true);
                if (sanitized != null) {
                    cleaned.add(sanitized);
                }
            }
            adds = cleaned;

            if (!adds.isEmpty()) {
                // Keep legacy fields in sync with first configured add for UI/backward compatibility.
                WaveAdd first = adds.get(0);
                npcId = first.npcId;
                mobsPerWave = first.mobsPerWave;
            } else {
                String legacyNpcId = npcId == null ? "" : npcId.trim();
                if (!legacyNpcId.isEmpty()) {
                    WaveAdd legacy = new WaveAdd();
                    legacy.npcId = legacyNpcId;
                    legacy.mobsPerWave = Math.max(1, mobsPerWave);
                    legacy.everyWave = 1;
                    adds.add(legacy);
                    npcId = legacy.npcId;
                    mobsPerWave = legacy.mobsPerWave;
                }
            }

            List<ScheduledWave> cleanedWaves = new ArrayList<>();
            for (ScheduledWave wave : scheduledWaves) {
                ScheduledWave sanitized = sanitizeScheduledWave(wave);
                if (sanitized != null) {
                    cleanedWaves.add(sanitized);
                }
            }
            scheduledWaves = cleanedWaves;

            if (scheduledWaves.isEmpty()) {
                scheduledWaves = migrateLegacyWaves(adds, waves, timeLimitMs);
            }
        }

        public boolean hasConfiguredAdds() {
            sanitize();
            if (!adds.isEmpty()) {
                return true;
            }
            for (ScheduledWave wave : scheduledWaves) {
                if (wave != null && wave.adds != null && !wave.adds.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        public List<WaveAdd> getConfiguredAdds() {
            sanitize();
            return new ArrayList<>(adds);
        }

        public List<ScheduledWave> getResolvedScheduledWaves() {
            sanitize();
            List<ScheduledWave> out = new ArrayList<>();
            for (ScheduledWave wave : scheduledWaves) {
                if (wave == null || wave.adds == null || wave.adds.isEmpty()) {
                    continue;
                }
                out.add(copyScheduledWave(wave));
            }
            return out;
        }

        public void setPrimaryAdd(String inputNpcId, int inputMobsPerWave) {
            String normalizedNpcId = inputNpcId == null ? "" : inputNpcId.trim();
            npcId = normalizedNpcId;
            mobsPerWave = Math.max(1, inputMobsPerWave);

            if (adds == null) {
                adds = new ArrayList<>();
            }
            if (normalizedNpcId.isEmpty()) {
                if (!adds.isEmpty()) {
                    adds.remove(0);
                }
                sanitize();
                return;
            }

            WaveAdd primary;
            if (adds.isEmpty()) {
                primary = new WaveAdd();
                adds.add(primary);
            } else {
                primary = adds.get(0);
            }

            primary.npcId = normalizedNpcId;
            primary.mobsPerWave = Math.max(1, inputMobsPerWave);
            if (primary.everyWave < 1) {
                primary.everyWave = 1;
            }

            sanitize();
        }

        public double getWaveRandomSpawnRadius() {
            sanitize();
            return randomSpawnRadius;
        }

        private static double sanitizeWaveRandomSpawnRadius(double radius) {
            if (!Double.isFinite(radius) || radius < 0.0d) {
                return 15.0d;
            }
            return radius;
        }

        private static WaveAdd sanitizeWaveAdd(WaveAdd add, boolean requireNpcId) {
            if (add == null) {
                return null;
            }
            String id = add.npcId == null ? "" : add.npcId.trim();
            if (requireNpcId && id.isEmpty()) {
                return null;
            }
            WaveAdd out = new WaveAdd();
            out.npcId = id;
            out.mobsPerWave = Math.max(1, add.mobsPerWave);
            out.everyWave = Math.max(1, add.everyWave);
            out.hp = (!Float.isFinite(add.hp) || add.hp <= 0f) ? 1.0f : add.hp;
            out.damage = (!Float.isFinite(add.damage) || add.damage <= 0f) ? 1.0f : add.damage;
            out.size = (!Float.isFinite(add.size) || add.size <= 0f) ? 1.0f : add.size;
            return out;
        }

        private static ScheduledWave sanitizeScheduledWave(ScheduledWave wave) {
            if (wave == null) {
                return null;
            }
            String trigger = normalizeTrigger(wave.trigger);
            if (trigger == null) {
                return null;
            }

            ScheduledWave out = new ScheduledWave();
            out.trigger = trigger;
            out.triggerValue = Double.isFinite(wave.triggerValue) ? wave.triggerValue : 0.0d;
            out.repeatCount = wave.repeatCount == 0 ? 1 : wave.repeatCount;
            if (out.repeatCount < -1) {
                out.repeatCount = -1;
            }
            out.repeatEverySeconds = Double.isFinite(wave.repeatEverySeconds) ? wave.repeatEverySeconds : 0.0d;
            if (out.repeatEverySeconds < 0d) {
                out.repeatEverySeconds = 0.0d;
            }

            if (TRIGGER_BOSS_HP_PERCENT.equals(out.trigger)) {
                if (out.triggerValue < 0d) {
                    out.triggerValue = 0d;
                } else if (out.triggerValue > 100d) {
                    out.triggerValue = 100d;
                }
            } else {
                if (out.triggerValue < 0d) {
                    out.triggerValue = 0d;
                }
            }

            out.adds = new ArrayList<>();
            List<WaveAdd> srcAdds = wave.adds != null ? wave.adds : List.of();
            for (WaveAdd add : srcAdds) {
                WaveAdd sanitizedAdd = sanitizeWaveAdd(add, true);
                if (sanitizedAdd != null) {
                    sanitizedAdd.everyWave = 1;
                    out.adds.add(sanitizedAdd);
                }
            }
            if (out.adds.isEmpty()) {
                return null;
            }

            if (out.repeatCount == 1) {
                out.repeatEverySeconds = 0.0d;
            } else if (out.repeatEverySeconds <= 0.0d) {
                if (TRIGGER_BOSS_HP_PERCENT.equals(out.trigger)) {
                    out.repeatEverySeconds = 1.0d;
                } else {
                    out.repeatEverySeconds = out.triggerValue > 0.0d ? out.triggerValue : 1.0d;
                }
            }

            return out;
        }

        private static List<ScheduledWave> migrateLegacyWaves(List<WaveAdd> legacyAdds, int legacyWaves, long legacyIntervalMs) {
            if (legacyAdds == null || legacyAdds.isEmpty() || legacyWaves == 0) {
                return new ArrayList<>();
            }

            double intervalSeconds = Math.max(0.001d, legacyIntervalMs / 1000.0d);
            List<ScheduledWave> out = new ArrayList<>();

            for (WaveAdd add : legacyAdds) {
                WaveAdd sanitizedAdd = sanitizeWaveAdd(add, true);
                if (sanitizedAdd == null) {
                    continue;
                }
                int every = Math.max(1, sanitizedAdd.everyWave);
                double firstAtSeconds = intervalSeconds * every;
                int repeatCount;
                if (legacyWaves < 0) {
                    repeatCount = -1;
                } else {
                    repeatCount = legacyWaves / every;
                }
                if (repeatCount == 0) {
                    continue;
                }

                ScheduledWave wave = new ScheduledWave();
                wave.trigger = TRIGGER_AFTER_SPAWN_SECONDS;
                wave.triggerValue = firstAtSeconds;
                wave.repeatCount = repeatCount;
                wave.repeatEverySeconds = intervalSeconds * every;
                sanitizedAdd.everyWave = 1;
                wave.adds = new ArrayList<>(List.of(sanitizedAdd));
                out.add(wave);
            }

            return out;
        }

        private static ScheduledWave copyScheduledWave(ScheduledWave wave) {
            ScheduledWave out = new ScheduledWave();
            out.trigger = wave.trigger;
            out.triggerValue = wave.triggerValue;
            out.repeatCount = wave.repeatCount;
            out.repeatEverySeconds = wave.repeatEverySeconds;
            out.adds = new ArrayList<>();
            if (wave.adds != null) {
                for (WaveAdd add : wave.adds) {
                    WaveAdd copy = sanitizeWaveAdd(add, true);
                    if (copy != null) {
                        copy.everyWave = 1;
                        out.adds.add(copy);
                    }
                }
            }
            return out;
        }

        private static String normalizeTrigger(String trigger) {
            if (trigger == null || trigger.isBlank()) {
                return TRIGGER_AFTER_SPAWN_SECONDS;
            }
            String normalized = trigger.trim()
                    .toLowerCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            return switch (normalized) {
                case TRIGGER_BEFORE_BOSS, TRIGGER_ON_SPAWN, TRIGGER_AFTER_SPAWN_SECONDS, TRIGGER_SINCE_LAST_WAVE, TRIGGER_BOSS_HP_PERCENT -> normalized;
                default -> null;
            };
        }
    }
}
