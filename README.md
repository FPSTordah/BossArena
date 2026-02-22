# BossArena

BossArena is a Hytale server mod that adds configurable boss encounters, table-based boss purchasing, and per-player loot rewards.

## Version

- Current project version: `0.9.2`

## Support

For support, join:

- `https://discord.gg/r5MBWdzWWW`

## Credits

- JJBYosepi - Extensive testing and feedback across the project.
- Sully     - Key development support and implementation help.

## What This Mod Does

- Spawns configured bosses in configured arenas.
- Supports admin spawning for testing and event control.
- Uses an in-world shop table (`Boss_Arena_Shop`) to buy boss contracts.
- Supports tiered bosses and table-specific boss availability.
- Supports boss waves with multiple add types and per-wave cadence.
- Shows persistent event notifications while boss/adds are alive.
- Drops per-player loot from a shared chest interaction.
- Delays chest spawn until both the boss and all tracked adds are dead.

## Permissions (LuckPerms)

All admin commands are protected by:

- `bossarena.admin`

Example:

```text
/lp group admin permission set bossarena.admin true
/lp group default permission set bossarena.admin false
```

Player shop interaction is public and does not require admin permission.

## Commands

Primary command:

- `/bossarena`
- Alias: `/ba`

All commands below require `bossarena.admin`.

### Arena Commands

- `/bossarena arena create <arenaId>`
- `/bossarena arena delete <arenaId>`
- `/bossarena arena list`

### Spawn Command

- `/bossarena spawn <bossId> <arenaId|here>`

### Reload Command

- `/bossarena reload`

Reloads:

- `config.json`
- `shop.json`
- `bosses.json`
- `arenas.json`
- `loot_tables.json`

### Config GUI

- `/bossarena config`

Opens the BossArena config UI with tabs for:

- Bosses
- Shop
- Arenas

### Legacy Shop Admin Commands

- `/bossarena shop open`
- `/bossarena shop place`
- `/bossarena shop give`
- `/bossarena shop add <arenaId> <bossId> <cost>`
- `/bossarena shop remove <arenaId> <bossId>`
- `/bossarena shop list`

Notes:

- The active player-facing shop behavior is driven by `mods/BossArena/shop.json` and table location config.
- `shop add/remove/list` are legacy registry tooling.

## Config GUI Highlights (`/ba config`)

- Opens on the Bosses tab by default.
- Bosses tab allows editing boss core fields, tier, and loot rows.
- Bosses tab includes a separate Boss Waves overlay for wave timing and add definitions.
- Boss Waves supports multi-add setup (`npcId`, `mobsPerWave`, `everyWave`) per line.
- Shop tab lists saved shop tables in the current world.
- Shop tab sorts tables nearest to farthest from player.
- Shop tab assigns table arena and enables/disables boss contracts per table.
- Arenas tab supports inline arena id and xyz editing.
- Arenas tab can add an arena at player location and delete arena rows.

## JSON Customization Files

All runtime customization is in `mods/BossArena/`.

### `bosses.json`

Defines each summonable boss.

Fields:

- `bossName`
- `npcId`
- `tier`: `uncommon`, `common`, `rare`, `epic`, `legendary`
- `amount`
- `modifiers.hp`, `modifiers.damage`, `modifiers.size`
- `perPlayerIncrease.hp`, `perPlayerIncrease.damage`, `perPlayerIncrease.size`
- `extraMobs.timeLimitMs`: interval between wave spawns (stored in ms)
- `extraMobs.waves`:
- `0` = no waves
- `1..N` = finite waves
- `-1` = infinite waves until boss dies
- `extraMobs.adds[]`:
- `npcId`
- `mobsPerWave`
- `everyWave` (`1` every wave, `2` every 2nd wave, etc.)

### `arenas.json`

Defines valid spawn locations.

Fields per arena:

- `arenaId`
- `worldName`
- `x`
- `y`
- `z`

### `loot_tables.json`

Defines per-boss loot drops.

Fields per loot table:

- `bossName` (must match boss id/name)
- `lootRadius`
- `items[]`

Fields per item:

- `itemId`
- `dropChance` (`0.0` to `1.0`)
- `minAmount`
- `maxAmount`

### `shop.json`

Main shop configuration.

Top-level fields:

- `currencyProvider`: `auto`, `item`, `hymarket`, `economysystem`
- `currencyItemId`
- `visibleSlotsByTier`
- `entries[]`
- `tableLocations[]`

`currencyProvider` behavior:

- `auto`: prefers `HyMarketPlus`, then `EconomySystem`, else item currency
- `hymarket`: uses HyMarket currency
- `economysystem`: uses EconomySystem balance
- `item`: uses item removal with `currencyItemId`

When item currency is needed and shop-level currency item is blank, fallback order is:

1. `config.json.currencyItemId`
2. `config.json.fallbackCurrencyItemId`
3. `Ingredient_Bar_Iron`

`tableLocations[]` fields:

- `worldName`
- `x`
- `y`
- `z`
- `arenaId` (table-level arena assignment)
- `enabledBossIds[]` (bosses allowed on that table)

Important behavior:

- Shop tables are tracked by world position.
- Breaking a `Boss_Arena_Shop` table removes its saved table location entry.
- Boss contracts shown to players are filtered by that table's `enabledBossIds` and boss tier.

### `config.json`

General/global config.

Fields:

- `currencyItemId`
- `fallbackCurrencyItemId`
- `arenas` (legacy field)

## Event and Loot Behavior

- Event titles show live state:
- `Boss alive: <count> | Wave mobs alive: <count>`
- Notification stays persistent while boss or adds are alive.
- After both hit zero, final status remains briefly before clearing.
- Loot chest is queued only after the tracked boss and tracked adds are dead.
- Loot is per-player and claim-based.
- Chest auto-cleans after claim completion/expiry logic.

## Build

```bash
mvn -q -DskipTests package
```

Output jar:

- `target/BossArena-0.9.2.jar`
