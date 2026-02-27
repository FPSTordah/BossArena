# BossArena

BossArena is a Hytale server mod for configurable boss encounters, NPC-based shop access, and per-player boss loot rewards.

## Version

- Current project version: `1.1.0`

## Support

- `https://discord.gg/r5MBWdzWWW`

## Repository

- `https://github.com/FPSTordah/BossArena`

## Compatibility

- Server target: `2026.02.19-1a311a592`
- Required dependencies: none (runs standalone)
- Optional currency integrations:
- `HyMarketPlus` (`currencyProvider: hymarket`, auto-detected in `auto` mode)
- `EconomySystem` (`currencyProvider: economysystem`, auto-detected in `auto` mode)
- If neither optional economy mod is present, BossArena falls back to item currency.

## Credits

- JJBYosepi - Extensive testing and feedback.
- Sully - Development support and implementation help.

## Core Features

- Configurable bosses with tiering, stat modifiers, and wave/add support.
- Arena-based spawning plus admin spawn controls.
- In-world shop guard NPC using standard entity `Use` interaction (`F` by default).
- Shop contracts are filtered by a location-specific enabled boss list.
- Persistent boss event notification while tracked boss/add entities are alive.
- Per-player loot chest generation after boss encounter completion.
- Loot chest persistence across restarts with state recovery.

## Permissions (LuckPerms)

- Admin permission: `bossarena.admin`

Example:

```text
/lp group admin permission set bossarena.admin true
/lp group default permission set bossarena.admin false
```

Player shop interaction does not require admin permission.

## Commands

Primary command namespaces:

- `/bossarena`
- `/ba` (alias)

Admin commands (require `bossarena.admin`):

- `/bossarena arena create <arenaId>`
- `/bossarena arena delete <arenaId>`
- `/bossarena arena list`
- `/bossarena spawn <bossId> <arenaId|here>`
- `/bossarena reload`
- `/bossarena config`
- `/bossarena shop open`
- `/bossarena shop place`
- `/bossarena shop delete`

Reload targets:

- `config.json`
- `shop.json`
- `bosses.json`
- `arenas.json`
- `loot_tables.json`

## Shop Behavior

- Shop NPC type defaults to `bossarena_shop_guard` (configurable via `shop.json.shopNpcId`).
- Default guard role template uses `Temple_Mithril_Guard` appearance, static motion, and proximity greet settings.
- Interacting with the guard through `Use` opens the BossArena shop page.
- Guard interaction is tied to `BossArena_OpenShopNpc`.
- Shop location is tracked in `shop.json.shops[]` with UUID and world position.
- `/ba shop delete` removes the nearest tracked shop NPC and cleans its saved `shops[]` entry.
- If NPC UUID is stale/missing, delete command falls back to removing the nearest saved shop location entry.

## Config GUI (`/ba config`)

- Tabs: `Bosses`, `Shop`, `Arenas`.
- Bosses tab: edit boss identity, tier, stats, and loot rows.
- Boss Waves overlay: edit `timeLimitMs`, wave count, and multi-add rows (`npcId`, `mobsPerWave`, `everyWave`).
- Shop tab: shows saved shop locations for current world, nearest first.
- Shop editor: set arena id override and toggle enabled bosses per location.
- Shop editor boss list supports scrolling for large boss lists.
- Arenas tab: inline id/position editing, add at player location, and delete.

## Runtime Data Files

All runtime customization is stored under `mods/BossArena/`.

### `bosses.json`

Fields:

- `bossName`
- `npcId`
- `tier` (`uncommon`, `common`, `rare`, `epic`, `legendary`)
- `amount`
- `modifiers.hp`
- `modifiers.damage`
- `modifiers.size`
- `perPlayerIncrease.hp`
- `perPlayerIncrease.damage`
- `perPlayerIncrease.size`
- `extraMobs.timeLimitMs`
- `extraMobs.waves` (`0` none, `1..N` finite, `-1` infinite-until-boss-death)
- `extraMobs.adds[].npcId`
- `extraMobs.adds[].mobsPerWave`
- `extraMobs.adds[].everyWave`

### `arenas.json`

Fields per arena:

- `arenaId`
- `worldName`
- `x`
- `y`
- `z`

### `loot_tables.json`

Fields per loot table:

- `bossName`
- `lootRadius`
- `items[]`

Fields per item:

- `itemId`
- `dropChance` (`0.0` to `1.0`)
- `minAmount`
- `maxAmount`

### `loot_chests_state.json`

Managed persistence for active/unclaimed boss loot chests.

Stored chest data includes:

- `world`
- `x`
- `y`
- `z`
- per-player pending loot
- optional expiry deadline

### `shop.json`

Top-level fields:

- `currencyProvider` (`auto`, `item`, `hymarket`, `economysystem`)
- `currencyItemId`
- `shopNpcId`
- `entries[]`
- `shops[]`

`currencyProvider` behavior:

- `auto`: `HyMarketPlus` -> `EconomySystem` -> item currency fallback
- `hymarket`: HyMarket provider only
- `economysystem`: EconomySystem provider only
- `item`: item currency only

Item currency fallback order:

1. `shop.json.currencyItemId`
2. `config.json.currencyItemId`
3. `config.json.fallbackCurrencyItemId`
4. `Ingredient_Bar_Iron`

`entries[]` fields:

- `tier`
- `slot`
- `icon`
- `enabled`
- `arenaId`
- `bossId`
- `cost`
- `displayName`
- `description`

`shops[]` fields:

- `uuid`
- `name`
- `worldName`
- `x`
- `y`
- `z`
- `arenaId`
- `enabledBossIds[]`

Notes:

- First-run defaults generate one enabled preview entry per tier.
- Contract visibility for players is filtered by location `enabledBossIds[]` and entry tier.

### `config.json`

Global config fields:

- `currencyItemId`
- `fallbackCurrencyItemId`
- `arenas` (legacy)

## Event and Loot Flow

- Event title format while encounter is active: `Boss alive: <count> | Wave mobs alive: <count>`
- Notification stays until both boss and tracked adds reach zero.
- Loot chest is queued only after tracked encounter completion.
- Loot is per-player claim data, not globally shared item stacks.
- Unclaimed chest state is persisted and restored on restart.

## Build

```bash
mvn -q -DskipTests package
```

Output:

- `target/BossArena-1.1.0.jar`
