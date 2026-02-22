package com.bossarena.data;

import java.util.ArrayList;
import java.util.List;

public class BossDefinition {
    public String bossName;
    public String npcId;
    public String tier = "uncommon";
    public int amount;

    public Modifiers modifiers = new Modifiers();
    public PerPlayerIncrease perPlayerIncrease = new PerPlayerIncrease();
    public ExtraMobs extraMobs = new ExtraMobs();

    public static class Modifiers {
        public float hp = 1.0f;
        public float damage = 1.0f;
        public float size = 1.0f;
    }

    public static class PerPlayerIncrease {
        public float hp = 0.0f;
        public float damage = 0.0f;
        public float size = 0.0f;
    }

    public static class ExtraMobs {
        public String npcId;
        public long timeLimitMs;
        public int waves;
        public int mobsPerWave = 3;
        // New format: multiple add definitions with per-wave cadence.
        public List<WaveAdd> adds = new ArrayList<>();

        public static class WaveAdd {
            public String npcId;
            public int mobsPerWave = 3;
            // 1 = every wave, 2 = every 2nd wave, etc.
            public int everyWave = 1;
            public float hp = 1.0f;
            public float damage = 1.0f;
            public float size = 1.0f;
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

            if (adds == null) {
                adds = new ArrayList<>();
            }

            List<WaveAdd> cleaned = new ArrayList<>();
            for (WaveAdd add : adds) {
                if (add == null) {
                    continue;
                }
                String id = add.npcId == null ? "" : add.npcId.trim();
                if (id.isEmpty()) {
                    continue;
                }
                add.npcId = id;
                if (add.mobsPerWave < 1) {
                    add.mobsPerWave = 1;
                }
                if (add.everyWave < 1) {
                    add.everyWave = 1;
                }
                if (!Float.isFinite(add.hp) || add.hp <= 0f) {
                    add.hp = 1.0f;
                }
                if (!Float.isFinite(add.damage) || add.damage <= 0f) {
                    add.damage = 1.0f;
                }
                if (!Float.isFinite(add.size) || add.size <= 0f) {
                    add.size = 1.0f;
                }
                cleaned.add(add);
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
        }

        public boolean hasConfiguredAdds() {
            sanitize();
            return !adds.isEmpty();
        }

        public List<WaveAdd> getConfiguredAdds() {
            sanitize();
            return new ArrayList<>(adds);
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
    }
}
