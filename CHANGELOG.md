# Changelog

## 1.3.0 - 2026-02-28

### Added
- Timed boss scheduler rules with per-row `bossId`, `arenaId`, spawn interval, and duplicate prevention while alive.
- Timed boss GUI controls for schedule rows and announcement settings.
- Timed announcement placeholders: `$Boss`, `$Arena`, `$World`.
- Boss wave random spawn controls: enable/disable random spawn locations and configurable random radius from boss origin.
- Boss level override support for RPGLeveling compatibility (`0`/blank = default behavior, `>=1` = forced level).
- Expanded boss scaling support in advanced boss scaler UI, including movement speed scaling.
- Persistent state files for boss fights, timed spawns, and loot chest runtime state.

### Changed
- Timed spawn despawn settings now override boss base timer when used.
- Timed despawn `0h 0m` now means infinite lifetime (no forced despawn).
- Timed boss notifications default to 10-second display duration.
- Config startup now creates missing files and merges missing config keys on boot.

### Fixed
- Boss event continuity over server restarts with retry-based link restoration for bosses/adds that load later.
- Crash path after `/npc clean --confirm` by guarding null world/location during queued loot handling.
- Timed event announcement reliability for server/world broadcast paths.

### Removed
- Timed custom sound trigger settings were removed due to runtime sound event instability.
