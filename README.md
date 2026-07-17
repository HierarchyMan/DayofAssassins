# Pre-Civilization: Day of Assassins

Server-wide timed PvP event for **Paper 1.21+**. Players hunt with a tracking compass, climb a kill leaderboard, then get pulled into a final arena stretch before the clock ends. **Highest kills wins.**

```
SETUP → COUNTDOWN → HUNT → FFA ANNOUNCE → RING TP → END CEREMONY
```

---

## Features

| Feature | Details |
|---------|---------|
| **Timed event phases** | Countdown → Hunt → FFA → Ended (persisted across restarts) |
| **Assassin’s Compass** | GUI of online players, track one target, dimension action bar |
| **Kill leaderboard** | Counts via **PvPManager**; `/preciv killtop`; feeds bossbar top killer |
| **TAB UI** | Filling countdown bossbar, live top-killer bar, scoreboard line injects |
| **FFA finale** | One-shot ring/oval spawn in a wand-selected cuboid around center |
| **Announcements** | Configurable titles before FFA (“final deathmatch begins in…”) |
| **End ceremony** | Dense shared-place ranking; titles for all; gold/silver/bronze + heroic for top 3 |
| **SQLite storage** | Event times/phase, kills, metrics — survives restarts mid-event |
| **Fully configurable text** | All messages, item lore, menu titles in `lang.yml` |
| **Effects** | Sounds/particles for compass, menus, kills, FFA, end (config toggles) |
| **Paper-only plugin** | `paper-plugin.yml` + library loader (lean jar; SQLite downloaded by Paper) |

### Explicitly not included

- Teleport lock / spawn safe zones  
- Arena block reset (arena is normal world)  
- Custom death handling (except compass no-drop + respawn re-give)  
- Kill chat message edits  
- Spectators or prize/command hooks (yet)  

---

## Gameplay loop

### For players

1. **Before start** — Read `/event`. Optional filling bossbar: “starts in …”
2. **Hunt** — Receive the Assassin’s Compass (auto or `/preciv compass`). Right-click → pick a target → hunt → get kills. Track one player at a time. Leaderboard via `/preciv killtop`.
3. **FFA lead-up** — Title announcements as the finale approaches.
4. **Finale** — Eligible players are teleported **once** onto a ring/oval in the PvP arena. The arena is open; you can leave. Kills still count everywhere until the end.
5. **Outside the cuboid after FFA** — Short action-bar nudge (e.g. where the finale is / warp hint). Configurable.
6. **End** — Scoring freezes. Everyone online gets a title with **place + kills**. Top 3 get gold/silver/bronze styling and a heroic sound. Offline players keep rank (kills persist).

### For admins (mental model)

```
Build arena in-world
    → wand pos1/pos2 + centerspawn
    → set starttime + endtime (UTC+0)
    → FFA = end − 30 minutes (default, config)
    → plugin runs the rest (announce, TP, scoring, ceremony)
```

**Win condition:** most event kills when `endtime` hits.  
**Ties:** shared place (two #1s → next is #2, not #3).

---

## Requirements

| Requirement | Notes |
|-------------|--------|
| **Paper 1.21+** | Not Spigot/Bukkit — Paper plugin loader only |
| **Java 21** | Matches Paper 1.21 toolchains |
| **[TAB](https://github.com/NEZNAMY/TAB)** | Hard depend — bossbar + scoreboard API |
| **[PvPManager](https://github.com/ChanceSD/PvPManager)** | Hard depend — kill credit (combat/killer resolution) |
| **Network (first boot)** | Paper downloads `sqlite-jdbc` via the plugin loader (then cached) |

---

## Installation

1. Install **Paper 1.21+**, **TAB**, and **PvPManager**.
2. Build or copy `DayOfAssassins-*.jar` into `plugins/`.
3. Start the server once so configs generate:
   - `plugins/DayOfAssassins/config.yml`
   - `plugins/DayOfAssassins/lang.yml`
   - `plugins/DayOfAssassins/data.db` (SQLite)
4. Stop or leave running; configure (below), then schedule the event.

### Build from source

```bash
mvn clean package
```

Output: `target/DayOfAssassins-1.0.0-SNAPSHOT.jar` (~200 KB; Triumph GUI shaded, SQLite not shaded).

---

## Quick setup (admin)

All wall times are **UTC+0**, format: `yyyy/MM/dd HH:mm:ss`.

### 1. Mark the final arena

Stand in the world where the deathmatch should happen:

```text
/preciv admin wand
```

- **Left-click** a block → **pos1**  
- **Right-click** a block → **pos2**  
- Cuboid is used for **spawn geometry** and the “outside arena” action bar — **not** a kill boundary.

Or without the wand:

```text
/preciv admin set pos1
/preciv admin set pos2
```

### 2. Set the ring center

Stand where the FFA circle should be centered (usually middle of the cuboid):

```text
/preciv admin set centerspawn
```

### 3. Schedule the event

```text
/preciv admin set starttime 2026/07/20 18:00:00
/preciv admin set endtime   2026/07/20 21:00:00
```

With defaults:

| Moment | When |
|--------|------|
| **Start** | `18:00` UTC — hunt begins, compasses out, kills count |
| **FFA** | `20:30` UTC — `end − 30m` — one-time ring teleport |
| **End** | `21:00` UTC — freeze scores + ceremony |

Optional absolute FFA override:

```text
/preciv admin set ffatime 2026/07/20 20:15:00
/preciv admin set ffatime clear
```

### 4. (Optional) Tune config

Edit `config.yml` for announce timing, FFA offset, TAB lines, effects, etc. See [Configuration](#configuration).

### 5. Tell players

```text
/event
```

Description text comes from `lang.yml`.

---

## Commands

| Command | Who | Description |
|---------|-----|-------------|
| `/event` | Everyone | Event description (`lang.yml`) |
| `/preciv compass` | Players | Give Assassin’s Compass if they don’t already have one |
| `/preciv killtop` | Everyone | Current kill leaderboard |
| `/preciv admin wand` | Admin | Arena selection wand |
| `/preciv admin set pos1` / `pos2` | Admin | Cuboid corners at your feet |
| `/preciv admin set centerspawn` | Admin | FFA ring center at your location |
| `/preciv admin set starttime <yyyy/MM/dd HH:mm:ss>` | Admin | Event start (UTC+0) |
| `/preciv admin set endtime <yyyy/MM/dd HH:mm:ss>` | Admin | Event end (UTC+0) |
| `/preciv admin set ffatime <…>` / `clear` | Admin | Optional FFA time override |
| `/doa` | — | Alias of `/preciv` |

---

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `preciv.use` | true | Base `/preciv` access |
| `preciv.event` | true | `/event` |
| `preciv.compass` | true | `/preciv compass` |
| `preciv.killtop` | true | `/preciv killtop` |
| `preciv.admin` | op | All admin setup commands |
| `preciv.ffa.tp.bypass` | false | Skip FFA mass teleport |

---

## Configuration

### `config.yml` (structure & timings)

| Path | Default idea | Purpose |
|------|----------------|---------|
| `times.*` | set by commands | Start / end / optional FFA override (also in SQLite) |
| `ffa.before-end-seconds` | `1800` (30m) | FFA moment = end − this (unless override) |
| `ffa.announce-lead-seconds` | `3600` (60m) | Start FFA titles this long before FFA |
| `ffa.announce-interval-seconds` | `600` (10m) | Title interval |
| `ffa.outside-actionbar-seconds` | `60` | Action bar duration for players outside cuboid after FFA |
| `ffa.max-diameter-fraction` | `0.75` | Max ring diameter vs longest cuboid horizontal side |
| `ffa.min-air-above` | `2` | Air blocks required above spawn feet |
| `ffa.y-search-range` | `12` | Vertical search around center Y |
| `arena.*` | wand/commands | World, pos1, pos2, centerspawn |
| `storage.file` | `data.db` | SQLite file under plugin folder |
| `compass.*` / `wand.*` | materials | Item materials / CMD |
| `tab.scoreboard.lines` | indexes 3–5 | Lines written into TAB scoreboard + placeholders |
| `tab.bossbar.enabled` | true | Event bossbar via TAB |
| `effects.*` | enabled | Sounds/particles per action |

**Scoreboard placeholders:** `%top_killer%`, `%top_kills%`, `%phase%`  
**Bossbar placeholders (lang):** `%countdown%`, `%top_killer%`

### `lang.yml` (all player-facing text)

Configure:

- `/event` description  
- Bossbar titles (countdown + live + no kills)  
- Compass item **name/lore per state** (inactive, tracking, other world, lost target)  
- Compass GUI titles/buttons/lore (including “currently tracking”)  
- Killtop format  
- FFA announce / start / outside action bar  
- End titles for place 1 / 2 / 3 / other  

Prefer MiniMessage-free **legacy `&` color codes** as shipped.

### Effects (examples)

Defaults in `config.yml` include FX for:

- Compass open GUI  
- Menu select / page / deny  
- Kill credited  
- FFA announce & teleport  
- End normal & top-3 heroic  

Toggle globally with `effects.enabled`, or per-effect sound/particle `enabled` flags.

---

## Compass rules (UX)

- **One compass only** — command refuses duplicates; pickup dupes removed  
- **Allowed slots** — player inv + hands; not crafting / containers / armor  
- **Drop / throw** — item entity destroyed (not left on the ground)  
- **Death** — not dropped; **re-given on respawn** while event is active  
- **Full inventory** on join/give — no ground drop; ~1 minute action bar to free a slot and use `/preciv compass`  

Right-click opens a paginated player list (non-vanished). State is reflected in item lore.

---

## FFA teleport eligibility

Teleported **once** when FFA starts if the player is:

- Online  
- **Survival** mode  
- **Not vanished** (metadata `vanished`)  
- **Without** `preciv.ffa.tp.bypass`  

Spawns: circle/oval inside cuboid around centerspawn; multi-ring / random fallback if points would stack under 1 block; diameter capped at 75% of longest cuboid side; standable Y near center Y.

---

## Persistence & restarts

| Data | Stored |
|------|--------|
| Start / end / FFA override | SQLite + mirrored in config when set by command |
| Phase, FFA already teleported, ceremony done | SQLite |
| Kill counts (UUID → name, kills) | SQLite |
| Metrics | SQLite |

If the server restarts **during** countdown, hunt, or FFA, the plugin reloads phase, reschedules behavior, and re-applies UI. Mid-event online players get compasses again when appropriate.

---

## Permissions for TAB / display

TAB must be installed and its bossbar/scoreboard features available. This plugin creates/updates a TAB bossbar and scoreboard lines through the TAB API. Align `tab.scoreboard.lines` indexes with free rows on your existing TAB layout.

---

## Troubleshooting

| Issue | Check |
|-------|--------|
| Plugin doesn’t load on Spigot | **Paper only** — needs `paper-plugin.yml` |
| “Missing dependency TAB / PvPManager” | Install both; names must match |
| SQLite fails first boot | Allow outbound HTTPS for Paper library download (then cached) |
| No bossbar | TAB installed? `tab.bossbar.enabled`? Event in countdown/hunt/FFA? |
| No kills counting | Between start and end? PvPManager resolving killer? |
| Nobody teleported at FFA | Survival + not vanished + no bypass? Arena world loaded? Centerspawn set? |
| Compass missing after death | Event still active? Inv full? Check action bar hint |

---

## Development

```text
com.fusion.dev.cystol
├── DayOfAssassinsPlugin      # Paper JavaPlugin
├── DayOfAssassinsLoader       # MavenLibraryResolver → sqlite-jdbc
├── command/                  # Brigadier + admin/player commands
├── event/                    # Timeline, scheduler, phases
├── compass/                  # Item, GUI (Triumph), listeners
├── kill/                     # KillService, dense ranking, PvPManager hook
├── arena/                    # Wand, cuboid, FFA spawn math
├── display/                  # TAB bossbar + scoreboard render
├── ceremony/                 # End titles / sounds
├── fx/                       # Config-driven sounds & particles
├── storage/                  # SQLite access + repositories
└── config/                   # PluginConfig + Lang
```

```bash
mvn test          # pure logic + resource checks
mvn package       # shaded Triumph GUI, Paper-only jar
```

Design notes (longer form): [`docs/DESIGN.md`](docs/DESIGN.md).

---

## License / credits

- Built for **Pre-Civilization** event play.  
- Depends on community plugins **TAB** (NEZNAMY) and **PvPManager** (ChanceSD).  
- GUI: Triumph GUI · Storage: SQLite (Xerial) via Paper library loader.
