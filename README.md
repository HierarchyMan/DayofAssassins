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
| **Kill leaderboard** | Counts via **PvPManager**; `/preciv killtop`; scoreboard top-N + bossbar #1 |
| **TAB UI** | Filling countdown bossbar (stacks with existing TAB bars); **injects** event lines into the existing TAB scoreboard (never replaces it) |
| **FFA finale** | One-shot ring/oval spawn scaled to player count; dry standable Y only |
| **Announcements** | Configurable titles before FFA (“final deathmatch begins in…”) |
| **End ceremony** | Dense shared-place ranking; titles for all; gold/silver/bronze + heroic for top 3 |
| **Reward eligibility** | Configurable top-N places get a short congrats chat; staff see a private summary |
| **Admin ops** | Status, time-jumps (`startnow` / `ffanow` / `endnow` / `phase`), force TP/ceremony, reload |
| **Vanish** | Essentials/EssentialsX when present; else metadata `vanished` fallback |
| **SQLite storage** | Event times/phase, kills, metrics — survives restarts mid-event |
| **Fully configurable text** | All messages, item lore, menu titles in `lang.yml` |
| **Effects** | Sounds/particles for compass, menus, kills, FFA, end (config toggles) |
| **Paper-only plugin** | `paper-plugin.yml` + library loader (lean jar; SQLite downloaded by Paper) |

### Explicitly not included

- Teleport lock / spawn safe zones  
- Arena block reset (arena is normal world)  
- Custom death handling (except compass no-drop + respawn re-give)  
- Kill chat message edits  
- Spectators  
- Automatic prize commands / economy / crates (reward is a **claim-with-staff message** only)  

---

## Gameplay loop

### For players

1. **Before start** — Read `/event`. Optional filling bossbar: “starts in …”
2. **Hunt** — Receive the Assassin’s Compass (auto or `/preciv compass`). Right-click → pick a target → hunt → get kills. Track one player at a time. Leaderboard via `/preciv killtop`.
3. **FFA lead-up** — Title announcements as the finale approaches.
4. **Finale** — Eligible players are teleported **once** onto a ring/oval in the PvP arena. The arena is open; you can leave. Kills still count everywhere until the end.
5. **Outside the cuboid after FFA** — Short action-bar nudge (e.g. where the finale is / warp hint). Configurable.
6. **End** — Scoring freezes. Everyone online gets a title with **place + kills**. Top 3 get gold/silver/bronze styling and a heroic sound. Top-N (config) get a short **congrats** chat. Offline players keep rank (kills persist); staff with admin perm get a private placer list.

### For admins (mental model)

```
Build arena in-world
    → wand pos1/pos2 + centerspawn
    → set starttime + endtime (UTC+0)
    → FFA = end − 30 minutes (default, config)
    → plugin runs the rest (announce, TP, scoring, ceremony)
```

**Dry-run / live ops** without editing the DB:

```text
/preciv admin status
/preciv admin startnow | ffanow | endnow
/preciv admin forcetp | forceceremony | resetflags
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
| **Essentials / EssentialsX** | Soft depend — preferred vanish backend if present |
| **Network (first boot)** | Paper downloads `sqlite-jdbc` via the plugin loader (then cached) |

---

## Installation

1. Install **Paper 1.21+**, **TAB**, and **PvPManager**. EssentialsX is optional (better vanish).
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

Edit `config.yml` for announce timing, FFA offset, rewards top-N, TAB lines, effects, etc. See [Configuration](#configuration).

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
| `/doa` | — | Alias of `/preciv` |

### Admin — setup

| Command | Description |
|---------|-------------|
| `/preciv admin wand` | Arena selection wand |
| `/preciv admin set pos1` / `pos2` | Cuboid corners at your feet |
| `/preciv admin set centerspawn` | FFA ring center at your location |
| `/preciv admin set starttime <yyyy/MM/dd HH:mm:ss>` | Event start (UTC+0) |
| `/preciv admin set endtime <yyyy/MM/dd HH:mm:ss>` | Event end (UTC+0) |
| `/preciv admin set ffatime <…>` / `clear` | Optional FFA time override |

### Admin — ops & controls

Phase is **always derived from the clock**. These commands adjust times and/or one-shot flags so ops can drive the event without hacking SQLite.

| Command | Description |
|---------|-------------|
| `/preciv admin status` | Phase, times, flags, top killer, vanish backend, FFA eligible count |
| `/preciv admin startnow` | Jump into hunt (start ≈ now; ensures end is in the future) |
| `/preciv admin ffanow` | Jump into FFA (override FFA = now; next tick runs ring TP) |
| `/preciv admin endnow` | Jump to ended (end = now; ceremony runs if not done) |
| `/preciv admin phase <idle\|countdown\|hunt\|ffa\|ended>` | Time-jump helper for a target phase |
| `/preciv admin forcetp` | Re-run FFA mass TP (must already be in FFA) |
| `/preciv admin forceceremony` | Re-run end ceremony (must already be ENDED) |
| `/preciv admin resetflags` | Clear `ffa_teleported` + `ceremony_done` only |
| `/preciv admin eligible` | List online FFA-eligible players (debug vanish/GM/bypass) |
| `/preciv admin clearkills confirm` | Wipe all kill scores (requires `confirm`) |
| `/preciv admin reload` | Reload `config.yml` + `lang.yml` + effects (does **not** reload live event times from disk) |

---

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `preciv.use` | true | Base `/preciv` access |
| `preciv.event` | true | `/event` |
| `preciv.compass` | true | `/preciv compass` |
| `preciv.killtop` | true | `/preciv killtop` |
| `preciv.admin` | op | All admin setup + ops commands |
| `preciv.ffa.tp.bypass` | false | Skip FFA mass teleport |

---

## Configuration

### `config.yml` (structure & timings)

| Path | Default idea | Purpose |
|------|----------------|---------|
| `times.*` | set by commands | Start / end / optional FFA override (also in SQLite) |
| `rewards.enabled` | `true` | Send reward-eligibility chat at ceremony |
| `rewards.max-place` | `3` | Dense-rank places ≤ this get the message (ties share place) |
| `ffa.before-end-seconds` | `1800` (30m) | FFA moment = end − this (unless override) |
| `ffa.announce-lead-seconds` | `3600` (60m) | Start FFA titles this long before FFA |
| `ffa.announce-interval-seconds` | `600` (10m) | Title interval |
| `ffa.outside-actionbar-seconds` | `60` | Action bar duration for players outside cuboid after FFA |
| `ffa.max-diameter-fraction` | `0.75` | Cap ring diameter vs longest cuboid horizontal side (many players) |
| `ffa.min-player-spacing` | `8` | Target distance between adjacent ring mates; few players → tight ring; many grow up to diameter cap |
| `ffa.min-air-above` | `2` | Air blocks required above spawn feet |
| `ffa.y-search-range` | `12` | Vertical search around center Y (full cuboid height if local fails) |
| `ffa.ring-margin-blocks` | `2` | Keep ring inside cuboid edges |
| `arena.*` | wand/commands | World, pos1, pos2, centerspawn |
| `storage.file` | `data.db` | SQLite file under plugin folder |
| `compass.*` / `wand.*` | materials | Item materials / CMD |
| `tab.scoreboard.offset` | `3` | First 0-based row on the **existing** TAB board for relative injects |
| `tab.scoreboard.top-slots` | `3` | How many leaders to expose as `%top1_*` … `%topN_*` |
| `tab.scoreboard.empty-name` / `empty-kills` | `—` / `0` | Empty rank slots |
| `tab.scoreboard.lines` | top-3 + phase row | Injected templates (strings from offset, or maps with absolute `line:`) |
| `tab.bossbar.enabled` | true | Event bossbar via TAB (**adds** a bar; does not replace other TAB bars) |
| `effects.*` | enabled | Sounds/particles per action |

**Scoreboard inject (defaults):** lines at offset 3–6 — `#1` / `#2` / `#3` kill leaders + phase countdown.  
Does **not** create or `showScoreboard` a private board; only overwrites the configured rows on whatever TAB layout the player already has, and restores them when the event UI clears.

| Placeholder | Meaning |
|-------------|---------|
| `%top1_name%` `%top1_kills%` `%top1_place%` … through `top-slots` | Ranked leaders (empty → empty-name/kills) |
| `%top_killer%` / `%top_kills%` | Aliases for slot **#1** (bossbar + legacy templates) |
| `%phase%` | Localized phase label (`lang.yml` `phase.*`) |
| `%remaining%` | Time until **next phase change** (countdown→start, hunt→FFA, FFA→end) |
| `%until_end%` | Time until event end (optional in templates) |

**Bossbar placeholders (lang):** `%countdown%` (pre-start, same as remaining-to-start), `%top_killer%`, `%top_kills%`, `%remaining%` (next phase), `%until_end%`  
**Reward placeholders (lang):** `%place%`, `%kills%`, `%player%`, `%max_place%`

Tip: make the last inject line name the destination (e.g. “Finale in %remaining%”) — `%remaining%` alone is the clock, not the noun.

### `lang.yml` (all player-facing text)

Configure:

- `/event` description  
- Bossbar titles (countdown + live + no kills)  
- Compass item **name/lore per state** (inactive, tracking, other world, lost target)  
- Compass GUI titles/buttons/lore (including “currently tracking”)  
- Killtop format  
- FFA announce / start / outside action bar  
- End titles for place 1 / 2 / 3 / other  
- Reward eligibility + staff summary lines  
- Admin status / force / confirm messages  

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

## Rewards

At end ceremony, players with dense-rank **place ≤ `rewards.max-place`** (if enabled) receive a short congrats chat (place + kills only — no claim/ops wording). Copy is in `lang.yml` (`rewards.eligible`).

Online staff with `preciv.admin` get a private placer summary (`rewards.staff-summary-*`). Full list is also logged to console (includes offline winners by name).

This does **not** run prize commands or touch economy — hand out rewards manually (or add command hooks later).

| `max-place` | Who is eligible |
|-------------|-----------------|
| `1` | All place **#1** (including ties) |
| `2` | Places **#1–#2** |
| `3` | Places **#1–#3** (default) |
| `0` | Nobody (even if `enabled: true`) |

---

## Vanish

| Backend | When used |
|---------|-----------|
| **Essentials / EssentialsX** | Plugin present and API binds — **only** this source (no metadata OR) |
| **Metadata `vanished`** | Essentials absent or bind failed — same legacy check as before |

Used for:

- Compass GUI player list  
- FFA teleport eligibility  

`/preciv admin status` reports the active backend (`essentials` or `metadata`).

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
- **Not vanished** (Essentials if present, else metadata `vanished`)  
- **Without** `preciv.ffa.tp.bypass`  

Spawns:

- Ellipse around centerspawn, inside the cuboid with margin  
- **Size scales with player count** — target spacing `ffa.min-player-spacing` between neighbors; grows up to `max-diameter-fraction` of the longest cuboid side for large FFAs  
- Multi-ring / random dry columns if points would stack under 1 block  
- Feet need solid **non-liquid** floor + clear air column (`min-air-above`); no invented Y on water/lava  

List candidates live: `/preciv admin eligible`.

---

## Persistence & restarts

| Data | Stored |
|------|--------|
| Start / end / FFA override | SQLite + mirrored in config when set by command |
| Phase, FFA already teleported, ceremony done | SQLite |
| Kill counts (UUID → name, kills) | SQLite |
| Metrics | SQLite |

If the server restarts **during** countdown, hunt, or FFA, the plugin reloads phase, reschedules behavior, and re-applies UI. Mid-event online players get compasses again when appropriate.

### Why `ffa_teleported` and `ceremony_done`?

Phase is recalculated from the clock every second. Entering **FFA** or **ENDED** would otherwise re-run side effects every tick (and again after a restart mid-phase):

| Flag | Prevents |
|------|----------|
| `ffa_teleported` | Mass ring TP every second / on every mid-FFA restart |
| `ceremony_done` | Ceremony titles/sounds every second while ended / after restart past end |

Changing start/end/FFA times clears both flags so a new schedule can fire again. Ops can also `/preciv admin resetflags`, `forcetp`, or `forceceremony`.

---

## Permissions for TAB / display

TAB must be installed with **bossbar** and **scoreboard** features enabled.

| What we do | What we do **not** do |
|------------|------------------------|
| Create an API bossbar and `addPlayer` so it **stacks** with config bars | Replace other TAB bossbars |
| `Line.setText` on free rows of the **active/registered** TAB scoreboard | `showScoreboard` a private board that wipes the server layout |

Set `tab.scoreboard.offset` (or absolute `line:` keys) to free rows on your existing TAB scoreboard config. Leave unused rows alone so the rest of the layout stays intact.

HUD `%remaining%` is **until the next phase** (not full event length). Bossbar fill tracks the **current phase segment**. Pre-start countdown title still says “starts in …”.

---

## Troubleshooting

| Issue | Check |
|-------|--------|
| Plugin doesn’t load on Spigot | **Paper only** — needs `paper-plugin.yml` |
| “Missing dependency TAB / PvPManager” | Install both; names must match |
| SQLite fails first boot | Allow outbound HTTPS for Paper library download (then cached) |
| No bossbar | TAB installed? Bossbar feature on? `tab.bossbar.enabled`? Event in countdown/hunt/FFA? |
| Event lines wiped the whole scoreboard | Old behavior; current build **injects only**. Update jar; free rows at `offset` must exist on the TAB board |
| Scoreboard shows only blank / wrong rows | `offset` / `line` indices past the board length? Enable TAB scoreboard feature? |
| Countdown looks like “total event left” | `%remaining%` is next-phase time; rename template (e.g. “Finale in …”) if players misread it |
| No kills counting | Between start and end? PvPManager resolving killer? |
| Nobody teleported at FFA | `/preciv admin eligible` — Survival + not vanished + no bypass? Arena world loaded? Centerspawn set? Dry standable ground in cuboid? |
| FFA ring too huge with 2 players | `ffa.min-player-spacing` (default 8); diameter cap only applies when N is large |
| Staff got FFA TP’d while vanished | Essentials installed? `/preciv admin status` vanish backend? |
| Ceremony spam after restart | Should not happen — check `ceremony_done` in status; file a bug if unset |
| Compass missing after death | Event still active? Inv full? Check action bar hint |
| Need to re-test FFA/ceremony | `resetflags` + `forcetp` / `forceceremony`, or `ffanow` / `endnow` |

---

## Development

```text
com.fusion.dev.cystol
├── DayOfAssassinsPlugin      # Paper JavaPlugin
├── DayOfAssassinsLoader       # MavenLibraryResolver → sqlite-jdbc
├── command/                  # Brigadier + admin/player commands (AdminOps)
├── event/                    # Timeline, scheduler, phases
├── compass/                  # Item, GUI (Triumph), listeners
├── kill/                     # KillService, dense ranking, PvPManager hook
├── arena/                    # Wand, cuboid, FFA spawn math
├── display/                  # TAB bossbar + scoreboard render
├── ceremony/                 # End titles / sounds / reward eligibility
├── fx/                       # Config-driven sounds & particles
├── storage/                  # SQLite access + repositories
├── util/                     # TimeUtil, TextUtil, VanishService
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
- Soft vanish integration: **EssentialsX** when present.  
- GUI: Triumph GUI · Storage: SQLite (Xerial) via Paper library loader.
