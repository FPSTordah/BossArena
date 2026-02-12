BossArena (BossArena Mod)
=========================

Made for: Hytale server ShadowTale Hardcore @ 144.217.240.173:5521

Summary
-------
BossArena adds configurable boss encounters to Hytale with scalable stats,
instanced loot per player, and timed loot chests. Boss fights can be spawned
manually for testing or configured to spawn automatically in arenas.

Key Features
------------
- Boss encounters with configurable stats and loot tables.
- Per-player scaling for boss stats.
- Instanced loot: each eligible player gets their own loot roll.
- Timed boss loot chest with inactivity timeout and cleanup.
- Custom chest block + interaction for boss loot.

Commands
--------
Primary command: /ba

Subcommands (common):
- /ba help
  Shows available commands.

- /ba spawn <bossId>
  Spawns a boss by ID at your location.

- /ba spawn <bossId> <count>
  Spawns multiple bosses by ID at your location.

- /ba list
  Lists available bosses.

- /ba reload
  Reloads configuration and loot tables.

Notes:
- Boss IDs are defined in the boss config files.
- Loot tables are matched by boss name/ID in the loot config.
- Some environments may also register /bossarena as an alias.

Configuration
-------------
- Boss definitions: configure boss stats, abilities, and scaling.
- Loot tables: define loot items, drop chances, and amounts.
- Arenas: define locations and spawning behavior for bosses.

Per-Player Scaling
------------------
Boss stats scale based on nearby player count. Scaling uses a base modifier
plus per-player increase. Example logic:
  final = base + (perPlayerIncrease * max(0, playerCount - 1))

Loot Chest Behavior
-------------------
- Loot is generated per eligible player and stored server-side.
- Opening the chest grants only the opening player's loot.
- When all loot is claimed, the chest is removed after it is closed
  or after inactivity timeout (30 seconds by default).

Assets
------
Custom chest assets are in:
  src/main/resources/assets/
  - Boss_Arena_Chest_Legendary.blockymodel
  - Boss_Arena_Chest_Legendary_Texture.png
  - boss_brena_bhest_begendary.json

Build
-----
  mvn -q -DskipTests package

Development Notes
-----------------
- This plugin expects Hytale server classes and assets at runtime.
- Custom chest interaction uses BossArena_OpenChest interaction mapping.
