# BossArena

BossArena is a Hytale server mod that adds configurable boss encounters, a world shop pedestal UI to summon raids, and per-player instanced loot chests.

## Current Status

- The shop table/pedestal asset is currently a placeholder and not final art/content.

## What This Mod Does

- Spawns configured bosses in configured arenas.
- Supports manual spawning for admin/testing workflows.
- Provides a shop pedestal UI where players can buy raid summons.
- Uses per-player loot generation so each eligible player gets their own rewards.
- Spawns a loot chest at boss death and cleans it up automatically.

## Key Gameplay Flow

1. Admin configures bosses, arenas, loot, and shop entries in JSON.
2. Players interact with the shop pedestal block in-world.
3. Players choose a tier and buy a configured raid contract.
4. Boss spawns in the configured arena.
5. On boss death, eligible nearby players get personal loot rolls.

## Permissions (LuckPerms)

All admin commands are protected by:

- `bossarena.admin`

No permission is required for players to buy a raid from the world shop pedestal. That interaction is intentionally public.

Example LuckPerms setup:

```text
/lp group admin permission set bossarena.admin true
/lp group default permission set bossarena.admin false
```

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

### Shop Admin Commands

- `/bossarena shop open`
- `/bossarena shop place`
- `/bossarena shop give`
- `/bossarena shop add <arenaId> <bossId> <cost>`
- `/bossarena shop remove <arenaId> <bossId>`
- `/bossarena shop list`

Notes:

- `shop add/remove/list` currently use in-memory `ShopRegistry` (legacy tooling).
- The active player-facing shop UI is driven by `mods/BossArena/shop.json`.

## JSON Customization Files

All runtime customization is in `mods/BossArena/`.

### `bosses.json`

Defines each summonable boss.

Fields:

- `bossName`: internal boss id used by commands/shop.
- `npcId`: NPC asset id to spawn.
- `amount`: count spawned for this boss definition.
- `modifiers.hp`, `modifiers.damage`, `modifiers.size`: base multipliers.
- `perPlayerIncrease.hp`, `perPlayerIncrease.damage`, `perPlayerIncrease.size`: scaling added per nearby player.
- `extraMobs.npcId`: optional extra mob id.
- `extraMobs.timeLimitMs`: optional wave time window.
- `extraMobs.waves`: optional wave count.
- `extraMobs.mobsPerWave`: mobs per wave.

### `arenas.json`

Defines valid raid spawn locations.

Fields per arena:

- `arenaId`
- `worldName`
- `x`
- `y`
- `z`

### `loot_tables.json`

Defines loot logic per boss.

Fields per loot table:

- `bossName`: must match the boss id/name key used for that boss.
- `lootRadius`: players within this radius are eligible.
- `items`: loot entries.

Fields per item:

- `itemId`
- `dropChance` (0.0 to 1.0)
- `minAmount`
- `maxAmount`

### `shop.json`

This is the main raid shop configuration.

Top-level fields:

- `currencyProvider`: `auto`, `item`, `hymarket`, `economysystem`
- `currencyItemId`: used when provider resolves to `item`
- `visibleSlotsByTier`: how many slots to show per tier
- `entries`: actual shop contracts

`currencyProvider` behavior:

- `auto`: prefers `EconomySystem` if active, then `HyMarketPlus` if active, else item currency.
- `economysystem`: uses EconomySystem balance API.
- `hymarket`: uses HyMarket copper API.
- `item`: removes item stacks matching `currencyItemId`.

Tier order in UI:

- `uncommon`, `common`, `rare`, `epic`, `legendary`

Entry fields:

- `tier`: one of the valid tiers above.
- `slot`: slot index in that tier (1..5).
- `enabled`: whether this entry is purchasable.
- `displayName`: name shown in shop UI.
- `description`: description shown in shop UI.
- `cost`: currency cost.
- `arenaId`: target arena id.
- `bossId`: target boss id.
- `icon`: reserved field (icons are currently disabled in the shop UI rendering).

Important behavior:

- If `enabled=true` but `bossId` or `arenaId` is blank, button shows `Please Configure` and is disabled.
- `visibleSlotsByTier` controls displayed layout without deleting entries.

### `config.json`

Legacy/general config currently used for:

- `currencyItemId` fallback (when shop-level `currencyItemId` is blank)
- legacy arena array field

Use `shop.json` for active shop customization and currency provider selection.

## Shop Access for Players

Players buy raids by interacting with the shop pedestal block in-world. No admin permission is required for this action.

## Loot Chest Behavior

- Loot is generated per eligible player at boss death.
- Player loot is claimed when chest data is resolved for that player.
- Chest is removed when all loot is claimed and chest closes, or when expiry triggers.
- Current chest expiry timer is 30 seconds.

## Build

```bash
mvn -q -DskipTests package
```

Output jar:

- `target/BossArena-0.4.0.jar`
