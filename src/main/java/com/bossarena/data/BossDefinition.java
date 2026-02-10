package com.bossarena.data;

public class BossDefinition {
    public String bossName;
    public String npcId;
    public int amount;

    public Modifiers modifiers;
    public PerPlayerIncrease perPlayerIncrease;
    public ExtraMobs extraMobs;

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
        public int mobsPerWave = 3;  // Added: how many mobs to spawn each wave (default 3)
    }
}