# BossArena

BossArena is a Hytale server mod for configurable boss encounters, NPC-based shop access, and per-player boss loot rewards.

## Version

- Current project version: `2.0.3`

## Changelog

- See `CHANGELOG.md` for release notes.

## Support

- `https://discord.gg/r5MBWdzWWW`

## Repository

- `https://github.com/FPSTordah/BossArena`

## Compatibility

- Server target: `2026.02.19-1a311a592`
- Required dependencies: none (runs standalone)
- Optional gameplay integrations:
- `RPGLeveling` (plugin id `Zuxaw:RPGLeveling`) for level override support and HP-scale compatibility on tracked bosses.
- Optional currency integrations:
- `HyMarketPlus` (`currencyProvider: hymarket`, auto-detected in `auto` mode)
- `EconomySystem` (`currencyProvider: economysystem`, auto-detected in `auto` mode)
- If neither optional economy mod is present, BossArena falls back to item currency.

### RPGLeveling Compatibility

- BossArena auto-detects RPGLeveling at runtime. No extra setup is required.
- Boss HP scaling compatibility is enforced on tracked bosses when RPGLeveling is loaded.
- Boss level override is supported through `bosses.json` and the Boss editor UI:
- `levelOverride: 0` (or blank in UI) = default RPGLeveling behavior.
- `levelOverride: 1+` = force that spawn level for the boss.
- Level overrides are applied while the boss is tracked and cleaned up when the boss/event ends.

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
- Loot chests are designed for solid ground placement; **do not place arenas or loot chests on top of snow blocks**, as snow is not treated as stable ground for chest spawning.

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
- Bosses tab: edit boss identity, tier, stats, level override, and loot rows.
- Boss Waves overlay: edit `timeLimitMs`, wave count, and multi-add rows (`npcId`, `mobsPerWave`, `everyWave`).
- Shop tab: shows saved shop locations for current world, nearest first.
- Shop editor: set arena id override, toggle enabled bosses, and edit per-boss contract prices per location.
- Shop editor boss list supports scrolling for large boss lists.
- Arenas tab: inline id/position editing, add at player location, and delete.

## Runtime Data Files

All runtime customization is stored under `mods/BossArena/`.

### `bosses.json`

Fields:

- `bossName`
- `npcId`
- `tier` (`uncommon`, `common`, `rare`, `epic`, `legendary`)
- `levelOverride` (`0` = default RPGLeveling behavior, `1+` = force spawn level when RPGLeveling is installed)
- `amount`
- `modifiers.hp`
- `modifiers.damage`
- `modifiers.size`
- `perPlayerIncrease.hp`
- `perPlayerIncrease.damage`
- `perPlayerIncrease.size`
- `extraMobs.timeLimitMs`
- `extraMobs.waves` (`0` none, `1..N` finite, `-1` infinite-until-boss-death)
- `extraMobs.useRandomSpawnLocations` (`true|false`, default `true`)
- `extraMobs.randomSpawnRadius` (blocks from boss origin, default `15`)
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
- `notificationRadius` (blocks; distance within which players see boss event title/subtitle for this arena; 10–500, default `100`)

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
- `strictContractPricing` (`true|false`, disables tier auto-price fallback when `true`)
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
- `contractPrices[]`

`contractPrices[]` fields:

- `bossId` (boss name/id for that location contract)
- `cost` (integer, clamped to `0+`)

Notes:

- First-run defaults generate one enabled preview entry per tier.
- Contract visibility for players is filtered by location `enabledBossIds[]` and entry tier.
- Per-contract pricing priority in location shops:
  1. `shops[].contractPrices[]` (boss-specific, per shop location)
  2. `entries[]` boss-specific cost match
  3. tier auto-price fallback (only when `strictContractPricing` is `false`)

### `config.json`

Global config fields:

- `currencyItemId`
- `fallbackCurrencyItemId`
- `notificationRadius` (blocks; fallback when an arena has no per-arena value; 10–500, default `100`)
- `arenas` (legacy)
- `eventBanner` (custom event-title banner templates)
- `timedMapMarker` (world map marker settings for active timed bosses)
- `timedBossSpawns[]` (optional timed auto-spawn rules)

`eventBanner` fields:

- `activeTitle` (title while encounter is active)
- `activeSubtitle` (subtitle while encounter is active)
- `victoryTitle` (title when encounter is complete)
- `victorySubtitle` (subtitle when encounter is complete)

`eventBanner` placeholders:

- `$Boss` / `{Boss}` = boss display name
- `$BossUpper` / `{BossUpper}` = boss display name uppercased
- `$BossAlive` / `{BossAlive}` = alive tracked boss count
- `$AddsAlive` / `{AddsAlive}` = alive tracked add count
- `$Context` / `{Context}` = extra context text (wave spawn/despawn info)
- `$ContextLine` / `{ContextLine}` = context plus separator (`" | "`) when present
- `$Countdown` / `{Countdown}` = remaining timer in `MM:SS` (blank when no timer)
- `$CountdownLabel` / `{CountdownLabel}` = `Time left: MM:SS` (blank when no timer)
- `$CountdownLine` / `{CountdownLine}` = countdown label plus separator (`" | "`) when timer is present
- `$State` / `{State}` = `active` or `victory`
- Legacy aliases still supported: `$ContextPrefix`, `$CountdownPrefix`

`timedBossSpawns[]` fields:

- `id` (optional label for logs)
- `enabled` (`true|false`)
- `bossId` (must match `bosses.json` boss id/name)
- `arenaId` (must match `arenas.json` arena id)
- `spawnIntervalHours`
- `spawnIntervalMinutes`
- `preventDuplicateWhileAlive` (`true` recommended)
- `despawnAfterHours`
- `despawnAfterMinutes`
- `announceWorldWide` (`true|false`, server-wide across all worlds)
- `announceCurrentWorld` (`true|false`, players in the boss world only)
- `worldAnnouncementText` (supports `$Boss`, `$Arena`, `$World`)

`timedMapMarker` fields:

- `enabled` (`true|false`)
- `markerImage` (world-map marker icon id, for example `map_marker.png`)
- `nameTemplate` (supports placeholders below)
- If `libs/map_marker.png` exists, BossArena copies it to world map marker assets and normalizes it to `32x32`.
- Optional: if `libs/map_marker.json` exists, it is copied alongside the marker image.

`timedMapMarker.nameTemplate` placeholders:

- `$Boss` / `{Boss}`
- `$Arena` / `{Arena}`
- `$Tier` / `{Tier}`
- `$TierUpper` / `{TierUpper}`
- `$World` / `{World}`

Timed announcement defaults:

- `worldAnnouncementText`: `[$World] $Boss event started at $Arena`

Behavior:

- Boss spawns at the arena location on the configured interval.
- If `preventDuplicateWhileAlive` is enabled and that boss is still alive in that arena, the next timed spawn is skipped.
- If both `despawnAfterHours` and `despawnAfterMinutes` are `0`, the boss is infinite (no timed despawn).
- If either despawn value is > `0`, the timed boss (and tracked adds) is removed when the timer is reached.
- Announcement targeting:
  - `announceWorldWide: true` sends to all players on the server.
  - `announceCurrentWorld: true` sends only to players in the spawned world.
  - If both are `false`, no timed announcement is sent.
  - If both are `true`, `announceWorldWide` overrides and `announceCurrentWorld` is ignored.

Example:

```json
{
  "timedMapMarker": {
    "enabled": true,
    "markerImage": "map_marker.png",
    "nameTemplate": "Timed Boss: $Boss @ $Arena"
  },
  "eventBanner": {
    "activeTitle": "The Shadows Stir: $Boss",
    "activeSubtitle": "$ContextLine$CountdownLineBoss: $BossAlive | Adds: $AddsAlive",
    "victoryTitle": "Victory Over $Boss",
    "victorySubtitle": "All clear in this arena."
  },
  "timedBossSpawns": [
    {
      "id": "volcano_hourly",
      "enabled": true,
      "bossId": "Example Boss",
      "arenaId": "volcano_arena",
      "spawnIntervalHours": 1,
      "spawnIntervalMinutes": 30,
      "preventDuplicateWhileAlive": true,
      "despawnAfterHours": 0,
      "despawnAfterMinutes": 45,
      "announceWorldWide": true,
      "announceCurrentWorld": false,
      "worldAnnouncementText": "[$World] $Boss event started at $Arena"
    }
  ]
}
```

## Event and Loot Flow

- Event title/banner text is configurable in `config.json.eventBanner`.
- Notification stays until both boss and tracked adds reach zero.
- Loot chest is queued only after tracked encounter completion.
- Loot is per-player claim data, not globally shared item stacks.
- Unclaimed chest state is persisted and restored on restart.

## Build

```bash
mvn -q -DskipTests package
```

Output:

- `target/BossArena-2.0.3.jar`
