package com.bossarena.arena;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.simple.IntegerCodec;
import com.hypixel.hytale.codec.codecs.simple.StringCodec;


public final class ArenaDef {
  public String id = "arena_id";
  public String worldId = "";

  // Anchor point: boss spawns here, chest drops here, radius is measured from here
  public int bossSpawnX = 0;
  public int bossSpawnY = 0;
  public int bossSpawnZ = 0;

  // Horizontal eligibility radius (X/Z)
  public int eligibilityRadiusBlocks = 40;

  // How long the chest exists after boss death
  public int chestLifetimeSeconds = 120;

  public static final Codec<String> STRING = new StringCodec();
  public static final Codec<Integer> INT = new IntegerCodec();

  public static final BuilderCodec<ArenaDef> CODEC =
    BuilderCodec.builder(ArenaDef.class, ArenaDef::new)
        .append(new KeyedCodec<>("Id", STRING),
            (a, v) -> a.id = v, a -> a.id).add()
        .append(new KeyedCodec<>("WorldId", STRING),
            (a, v) -> a.worldId = v, a -> a.worldId).add()

        .append(new KeyedCodec<>("BossSpawnX", INT),
            (a, v) -> a.bossSpawnX = v, a -> a.bossSpawnX).add()
        .append(new KeyedCodec<>("BossSpawnY", INT),
            (a, v) -> a.bossSpawnY = v, a -> a.bossSpawnY).add()
        .append(new KeyedCodec<>("BossSpawnZ", INT),
            (a, v) -> a.bossSpawnZ = v, a -> a.bossSpawnZ).add()

        .append(new KeyedCodec<>("EligibilityRadiusBlocks", INT),
            (a, v) -> a.eligibilityRadiusBlocks = v, a -> a.eligibilityRadiusBlocks).add()
        .append(new KeyedCodec<>("ChestLifetimeSeconds", INT),
            (a, v) -> a.chestLifetimeSeconds = v, a -> a.chestLifetimeSeconds).add()
        .build();

}
