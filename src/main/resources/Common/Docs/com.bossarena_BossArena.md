--- 
name: BossArena
description: Configurable boss arenas with timed spawns, NPC shop, and per-player loot chests.
author: Project42
---

# BossArena

BossArena is a Hytale server mod that adds configurable boss arenas, an NPC-based contract shop, timed boss events, and per-player loot chests.

**Version:** 2.0.3

**Group:** `com.bossarena`

**Name:** `BossArena`

---

## Quick Start

Follow these steps to get from a fresh install to your first working boss fight.

1. **Create an arena**
   - Stand where you want the boss anchor to be.
   - Run: `/ba arena create <arenaId>`
   - Open `/ba config` > **Arenas** > tab:
     - Adjust the `Arena`, `X`, `Y`, `Z` values if needed.
     - Set a `Notify` radius (in blocks) for the boss event banner.

2. **Create or edit a boss**
   - Open `/ba config` > **Bosses** > tab.
   - Add a new boss or edit an existing one:
     - Set `BossID`, `NPC ID`, `Tier`, and basic stats.
     - Optionally configure waves/adds and per-player scaling.
   - Bosses are saved into `mods/BossArena/bosses.json`.

3. **Define loot**
   - Option A (recommended): use `/ba config` > **Bosses** > loot editor
     - Open a boss in the editor and configure its loot rows directly in the UI.
     - Changes are written to `mods/BossArena/loot_tables.json` for you.
   - Option B: edit `mods/BossArena/loot_tables.json` by hand
     - Add a `lootRadius` and `items[]` for your boss.
     - Each item has `itemId`, `dropChance`, `minAmount`, `maxAmount`.

4. **(Optional) Set up a timed spawn**
   - Edit `mods/BossArena/config.json` > `timedBossSpawns[]`:
     - Set `bossId` to your boss name.
     - Set `arenaId` to your arena.
     - Configure `spawnIntervalHours` / `spawnIntervalMinutes`.
     - Enable `preventDuplicateWhileAlive` and sensible `despawnAfterMinutes`.

5. **Test the encounter**
   - Use `/ba spawn <bossId> <arenaId>` to manually spawn the boss.
   - Verify:
     - Banner shows inside the notification radius you set.
     - Loot chests appear and give the correct items.
     - Timed rule (if configured) behaves as expected.

---

## Core Concepts

### Arenas

- Arenas are simple anchor points where bosses spawn and loot chests drop.  
- Each arena has:
  - `arenaId`
  - `worldName`
  - `x`, `y`, `z` (boss spawn / event center)
  - `notificationRadius` (blocks around the arena where players see the boss event banner)
- Arenas are stored in `mods/BossArena/arenas.json` and editable in-game via `/ba config` > **Arenas** tab.

### Bosses

- Boss definitions live in `mods/BossArena/bosses.json`.
- Each boss has:
  - `bossName` (ID)
  - `npcId` (NPC to spawn)
  - `tier` (common, uncommon, rare, epic, legendary)
  - Stat modifiers, per-player scaling, and optional waves/adds.
- Bosses can be spawned:
  - Manually with `/bossarena spawn <bossId> <arenaId|here>`
  - Automatically via **timed boss spawns**.

### Loot

- When a boss event ends, BossArena creates **per-player loot chests** at the arena position.
- Loot is defined in `mods/BossArena/loot_tables.json`.
- Loot chest state is persisted in `mods/BossArena/loot_chests_state.json` so unclaimed loot survives restarts.

### Shop NPC

- An in-world guard NPC opens the BossArena shop when used.
- Contracts in the shop let players trigger specific bosses in specific arenas for a currency cost.
- Currency sources:
  - `HyMarketPlus`
  - `EconomySystem`
  - Fallback item currency when no economy mod is present.
- Shop configuration lives in `mods/BossArena/shop.json`.

---

## Commands

All commands require you to be an operator or have the `bossarena.admin` permission.

Primary namespaces:

- `/bossarena`
- `/ba` (alias)

### Arena Management

- `/bossarena arena create <arenaId>`  
  Creates an arena at your current position.

- `/bossarena arena delete <arenaId>`  
  Deletes the arena and removes it from `arenas.json`.

- `/bossarena arena list`  
  Lists known arenas and their coordinates.

### Boss Spawning

- `/bossarena spawn <bossId> <arenaId>`  
  Spawns the boss at the specified arena.

- `/bossarena spawn <bossId> here`  
  Spawns the boss at your current position without using a saved arena point.

### Config UI

- `/bossarena config` or `/ba config`  
  Opens the BossArena configuration UI with three tabs:
  - **Bosses** — edit boss stats, waves, and loot.
  - **Shop** — configure shop locations and contracts.
  - **Arenas** — edit arena ids, positions, and notification radius.

### Shop

- `/bossarena shop open`  
  Opens the BossArena shop UI directly.

- `/bossarena shop place`  
  Places a shop NPC at your position and registers it in `shop.json`.

- `/bossarena shop delete`  
  Deletes the nearest registered shop NPC and cleans up its entry.

### Reload

- `/bossarena reload`  
  Reloads the following files:
  - `config.json`
  - `shop.json`
  - `bosses.json`
  - `arenas.json`
  - `loot_tables.json`

---

## Timed Boss Spawns

Timed boss rules live in `mods/BossArena/config.json` under `timedBossSpawns[]`.

Each entry:

- `id` — optional label for logs.
- `enabled` — enable/disable this rule.
- `bossId` — boss name from `bosses.json`.
- `arenaId` — arena id from `arenas.json`.
- `spawnIntervalHours` / `spawnIntervalMinutes` — how often to spawn.
- `preventDuplicateWhileAlive` — skip the rule while a matching boss event is still alive.
- `despawnAfterHours` / `despawnAfterMinutes` — optional forced despawn window for timed bosses.
- `announceWorldWide` / `announceCurrentWorld` — announce behavior.
- `worldAnnouncementText` — customizable world announcement with placeholders.

### Player-Online Spawn Gating (2.0.2+)

- Timed spawns only **fire when at least one player is online in the target world**.
- If the timer elapses while no players are online:
  - The rule is **deferred** and retried in short intervals until a player joins.
  - Once a player is online, the next retry spawns **one** boss for that rule.
- This prevents timed bosses from piling up while the server is empty, while still honoring:
  - `preventDuplicateWhileAlive`
  - `despawnAfterHours` / `despawnAfterMinutes`

---

## Notification Radius

BossArena uses an on-screen event banner to notify players about active boss encounters.

- The banner is shown to players **within the arena’s notification radius**.
- `notificationRadius` is stored per arena in `arenas.json` and editable in `/ba config`:
  - `Notify` column in the **Arenas** tab.
  - Value is in blocks from the arena’s center.
  - Valid range is clamped between 10 and 500 blocks.
- If an arena has no value, BossArena falls back to `config.json.notificationRadius`.

Players who leave the radius have the banner cleared; re-entering the radius restores it while the event is active.

---

## Event Banner Customization

You can customize the **text shown in the boss event banner** through `mods/BossArena/config.json` under the `eventBanner` section.

### Fields

- `activeTitle`  
  Title text while the encounter is active.

- `activeSubtitle`  
  Subtitle text while the encounter is active.

- `victoryTitle`  
  Title shown when the encounter is complete (victory).

- `victorySubtitle`  
  Subtitle shown when the encounter is complete.

If any field is missing or blank, BossArena falls back to sensible defaults.

### Placeholders

All event banner strings support case-insensitive placeholders using either `$Name` or `{Name}`:

- `$Boss` / `{Boss}` — boss display name.  
- `$BossUpper` / `{BossUpper}` — boss name uppercased.  
- `$BossAlive` / `{BossAlive}` — number of alive tracked bosses.  
- `$AddsAlive` / `{AddsAlive}` — number of alive tracked adds.  
- `$Context` / `{Context}` — extra context text (for example, wave messages).  
- `$ContextLine` / `{ContextLine}` — context plus `" | "` when context is present, otherwise empty.  
- `$Countdown` / `{Countdown}` — remaining timer as `MM:SS` (blank when no timer).  
- `$CountdownLabel` / `{CountdownLabel}` — `"Time left: MM:SS"` (blank when no timer).  
- `$CountdownLine` / `{CountdownLine}` — countdown label plus `" | "` when a timer is present, otherwise empty.  
- `$State` / `{State}` — `"active"` or `"victory"` depending on encounter state.

Legacy aliases still work (`$ContextPrefix`, `$CountdownPrefix`) but `ContextLine` / `CountdownLine` are preferred.

### Example

```json
"eventBanner": {
  "activeTitle": "The Shadows Stir: $BossUpper",
  "activeSubtitle": "$ContextLine$CountdownLineBoss: $BossAlive | Adds: $AddsAlive",
  "victoryTitle": "Victory Over $Boss",
  "victorySubtitle": "All clear in this arena."
}
```

This will show a dramatic title while the boss is alive and a clean victory message once the event is finished.

---

## Configuration Files

All runtime configuration lives under `mods/BossArena/`:

- `config.json`
  - Global settings, event banner templates, timed boss spawns, timed map marker settings, default notification radius.

- `bosses.json`
  - Boss definitions, stat modifiers, wave/add schedules, per-player scaling.

- `loot_tables.json`
  - Per-boss loot tables and drop chances.

- `arenas.json`
  - Arena list: id, world, position, and per-arena `notificationRadius`.

- `shop.json`
  - Currency provider, shop NPC id, shop locations, and boss contracts.

- `loot_chests_state.json`
  - Persistent loot chest state (do not edit manually).

---

## Tips for Server Owners

- Use `/ba config` for most day-to-day edits instead of hand-editing JSON.
- Set a **reasonable notification radius** per arena so players near the fight see the event banner without spamming distant players.
- For timed bosses:
  - Keep `preventDuplicateWhileAlive` enabled.
  - Use `despawnAfterMinutes` to ensure old timed bosses are eventually cleaned up.
  - Adjust `spawnIntervalMinutes` so events feel special, not constant.
- Avoid placing arenas or boss loot chests directly on top of snow blocks; snow is not treated as stable ground for chest placement, so prefer solid terrain (stone, dirt, wood, etc.).

---

## Permissions

BossArena is primarily an admin tool; regular players interact with bosses through events, loot chests, and the shop.

- **Permission:** `bossarena.admin`
  - Required for all `/bossarena` / `/ba` admin commands.
  - Recommended for server staff only.
- Players **without** this permission can:
  - Use the **shop NPC** to buy contracts (if you allow it).
  - Fight bosses that have been spawned by admins or timed rules.
  - Loot their own **per-player loot chests**.

---

## Integrations

### RPGLeveling (optional)

If `RPGLeveling` (`Zuxaw:RPGLeveling`) is installed:

- BossArena auto-detects it at runtime.
- Boss HP scaling is made compatible for tracked bosses.
- `levelOverride` in `bosses.json` / Boss editor UI:
  - `0` or blank > default RPGLeveling behavior.
  - `>= 1` > force that level while the boss event is active.
- Level overrides are automatically cleaned up when the boss/event ends.

### Economy Mods (optional)

BossArena can use external economies for shop contracts:

- `HyMarketPlus` > `currencyProvider: "hymarket"`
- `EconomySystem` > `currencyProvider: "economysystem"`
- Fallback item currency when no supported economy is present.

Behavior:

- `shop.json.currencyProvider`:
- `"auto"` (default): try HyMarketPlus > EconomySystem > item currency.
  - `"hymarket"`: use HyMarketPlus only.
  - `"economysystem"`: use EconomySystem only.
  - `"item"`: use item currency only.
- Item currency uses:
  - `shop.json.currencyItemId`
  - Fallbacks in `config.json` if not set.

---

## Troubleshooting

### Multiple Bosses in One Arena

If you see several bosses in the same arena:

- Check `config.json.timedBossSpawns[]` for that boss:
  - `preventDuplicateWhileAlive` should be `true`.
  - `despawnAfterMinutes` should be set to a reasonable value so timed bosses are eventually removed.
- Remember that from **2.0.2** onward:
  - Timed bosses only spawn while at least one player is online in the target world.
  - If a boss was supposed to spawn while offline, it will spawn shortly after players return, but only **once** per interval.

If other plugins or commands directly delete NPCs, BossArena may not see a proper death. Use in-game tools (`/ba` commands) where possible so tracking stays in sync.

### Boss Not Spawning

- Verify the IDs:
  - `bossId` in `timedBossSpawns[]` matches a boss in `bosses.json`.
  - `arenaId` matches an existing arena in `arenas.json`.
- Check the world:
  - `arenas.json.worldName` must be correct.
  - The world must actually be loaded by the server.
- For timed rules:
  - Confirm `enabled: true`.
  - Make sure at least one player is online in the arena world when the spawn interval comes due.

### Loot Chest Issues

- Players only get loot chests if they are part of the tracked event:
  - Make sure they are present and participating when the boss dies.
- Loot is per-player:
  - Each player must open their own chest.
- Do not edit `loot_chests_state.json` by hand:
  - Use normal gameplay and let BossArena clean it up.

---

## Safe Editing & Reload Flow

Recommended workflow for changing BossArena configuration:

- Prefer `/ba config` for:
  - Boss stats, waves, and loot.
  - Arenas and notification radius.
  - Shop locations and contracts.
- When editing JSON files manually:
  - Back up `mods/BossArena/` first.
  - After changes, use `/bossarena reload` to reload:
    - `config.json`
    - `shop.json`
    - `bosses.json`
    - `arenas.json`
    - `loot_tables.json`
- Avoid touching persistence/state files:
  - `boss_fights_state.json`
  - `loot_chests_state.json`
  - `timed_spawn_state.json`
  - These are managed by BossArena and should not be hand-edited.

