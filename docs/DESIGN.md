# Pre-Civilization: Day of Assassins

Design notes and event specification. Updated as discussion continues.

---

## Overview

**Day of Assassins** is a server-wide timed PvP event with two phases:

1. **Hunt** — free-world tracking and killing with a special compass  
2. **FFA finale** — one-time ring teleport into the PvP arena cuboid for the last stretch before end  

Core loop: **track → kill → climb leaderboard → finale TP → highest kills wins.**

Package base: `com.fusion.dev.cystol.*`  
Plugin: Paper 1.21.x, Java 21, Maven  

**Hard depends:** TAB, PvPManager (kill credit API)  
**Storage:** SQLite (event state, kills, metrics)  
**GUI:** Triumph GUI (shaded)  
**Display:** TAB API (scoreboard lines + bossbar)

---

## Core loop (end-to-end)

### What is the game?

Players hunt with a tracking compass, rack up kills, get a **one-time** teleport onto a circle/oval in the final arena for the deathmatch stretch, then the timer ends. **Most kills wins** (ties share place).

```
ADMIN SETS TIMES + ARENA CUBOID + CENTER
        ↓
COUNTDOWN → filling bossbar until start
        ↓
HUNT → compasses, kills count (anywhere), no TP lock
        ↓
FFA ANNOUNCE → titles before finale (configurable)
        ↓
FFA → one ring TP for eligible players; open arena (can leave)
        ↓
END → freeze scores → titles + sounds for everyone → top 3 gold/silver/bronze + heroic
```

There is **no teleport lock**, **no spawn safe zone**, **no arena reset**, **no custom death handling**, **no spectators**, **no prize hooks** (yet).

---

### What does an admin have to do to start?

1. **Build arena** in the normal world (no plugin reset later)
2. **Select cuboid** with wand (pos1/pos2) + **`/preciv admin set centerspawn`**
3. **Schedule times (UTC+0)**
   ```
   /preciv admin set starttime <yyyy/mm/dd hh:mm:ss>
   /preciv admin set endtime   <yyyy/mm/dd hh:mm:ss>
   ```
   - **FFA teleport moment** = **`endtime − ffa-before-end`** (config, example **30 minutes** before end)
   - Optional: `/preciv admin set ffatime` if we keep an absolute override (must still be before end)
4. Tune announce lead/interval in config (e.g. 60m before FFA, every 10m)
5. **Wait** — clock + persistence do the rest (including across restarts)

---

### What does a player have to do?

| Phase | Player |
|-------|--------|
| Before start | `/event`; filling countdown bossbar |
| Hunt | Get compass; track; kill; climb killtop |
| FFA | Eligible players teleported once to ring; may leave arena freely; kills still count |
| Outside cuboid after FFA | Optional **1 minute actionbar** nudge (configurable text, e.g. finale /warp) |
| End | Title with place + kills; sound; top 3 get medal title + heroic sound |

Leaving the server is fine — **still ranked** (offline kills persist; offline can place/win).

---

### Systems while event runs

| System | Behavior |
|--------|----------|
| **Clock / state** | COUNTDOWN → HUNT → FFA → ENDED; **fully persisted** (SQLite) |
| **TAB bossbar** | Pre-start: countdown + **filling** bar (stacks with existing TAB bars). Live: #1 killer + **time until next phase**; fill tracks **current phase segment** |
| **TAB scoreboard** | **Inject** top-N + phase lines at configured indices/offset on the **existing** TAB board (never replace the whole board) |
| **Compass** | Track GUI; one only; bound to inv; death: not dropped, re-given on respawn |
| **Kills** | Via **PvPManager API**; window start→end; **persist SQLite**; no kill-message edits |
| **FFA announce** | Titles before FFA moment (“final deathmatch begins in …”) |
| **FFA TP** | Once: unvanished survival, no bypass → ring sized by **player count** + diameter cap; **dry** standable Y only |
| **Outside-cuboid nudge** | After FFA: ~1 min actionbar for players outside cuboid (configurable message) |
| **End ceremony** | Shared-place ranking; titles+sounds for all; top 3 medal + heroic |
| **Metrics** | SQLite |

---

### How does it end?

1. `endtime` → stop scoring  
2. Rank everyone with kill rows (online **and offline**)  
3. **Ties:** shared place — e.g. two with max kills both **#1**, next player is **#2** (not #3)  
4. **Everyone** in the ranking: title + subtitle (place + kills) + a sound  
5. **Places 1–3** (after dense ranking): **gold / silver / bronze** title variants + **heroic** sound  
6. Hide bossbar / clear event UI lines  
7. No arena reset, no prize hooks yet  

---

## Time system

All wall times **UTC+0**.

| Moment | How set | Meaning |
|--------|---------|---------|
| **Start** | `/preciv admin set starttime …` | Hunt begins; compasses; kills count; live bossbar |
| **FFA** | **`endtime − ffa-before-end`** (config default **30 minutes**) | One-time ring teleport + announce schedule anchors here |
| **End** | `/preciv admin set endtime …` | Score freeze + ceremony |

```
starttime ----------> ffaMoment (= end - 30m) ----------> endtime
   |                         |                              |
 Hunt                     ring TP once                   ceremony
 kills anywhere           open arena                     freeze ranks
```

### State machine (persisted)

| State | Condition |
|-------|-----------|
| **COUNTDOWN** | start set, `now < start` |
| **HUNT** | `start ≤ now < ffaMoment` |
| **FFA** | `ffaMoment ≤ now < end` |
| **ENDED** | `now ≥ end` |

On plugin enable: load SQLite → restore state → re-apply bossbar/scoreboard/schedules.  
Works for **pre-event, hunt, FFA, and post-end recovery**.

### FFA announce schedule

Relative to **`ffaMoment`** (not end):

| Config | Example |
|--------|---------|
| `announce-lead` | 60 minutes before FFA |
| `announce-interval` | every 10 minutes |
| Title | “Final deathmatch begins in %countdown%” |

At FFA: perform TP + optional start title.

---

## Display: TAB API (**hard depend**)

`paper-plugin.yml`: hard depend on **TAB** (required). Scoreboard + bossbar features must be enabled in TAB.

### Scoreboard — inject, do not replace

- Write only configured rows via TAB `Line#setText` on the player’s existing / registered board  
- **Never** `showScoreboard` a private board that wipes server layout  
- On clear: restore original text for those indices  
- Config: `tab.scoreboard.offset` + ordered `lines` (relative), or absolute `line:` keys  
- Default injects: **top 1–3** kill leaders + phase / countdown row  
- Placeholders: `%top1_name%`…`%topN_*` (N = `top-slots`), `%top_killer%` alias for #1, `%phase%`, `%remaining%`, `%until_end%`  

### Timers on HUD

| Placeholder | Meaning |
|-------------|---------|
| `%remaining%` | Seconds until **next phase boundary** (COUNTDOWN→start, HUNT→FFA, FFA→end) |
| `%until_end%` | Seconds until event end (optional; not the default continuous clock) |

Templates should ideally name the **destination** (“Finale in …”), not only print a bare duration next to the current phase.

### Bossbar

| Mode | When | Title | Progress |
|------|------|-------|----------|
| **Countdown** | Before start | “Starts in …” (`%countdown%`) | **FILLING** over announce-lead → start (**not draining**) |
| **Live** | Hunt / FFA | **#1 killer** + `%remaining%` (next phase) | **Phase segment fill** (Hunt: start→FFA; FFA: FFA→end) via `phaseFillProgress` |

API bar is **added** with `addPlayer` so it stacks alongside config-defined TAB bars.

---

## Special compass

PDC-marked plugin compass only.

### Give rules

- `/preciv compass` — only if they don’t already have one  
- Auto at event start + join while active  
- Full inv: **no ground drop**; actionbar ~1 min → use command; stop until next rejoin  

### Uniqueness / slots / drop

- Max one; pickup dupe destroyed/denied  
- Only hand / offhand / hotbar / main inv — cancel crafting & other GUIs  
- Drop/throw → **destroy entity** (not pickupable)  

### Death (**no custom death UX**)

- Compass **does not drop** on death (strip from death drops / never added)  
- **Re-give on respawn** if event still active and they don’t have one  
- We do **not** change death messages, respawn location, keep-inventory, etc.

### Tracking + GUI

- Triumph GUI player list (non-vanished)  
- One target; other dimension → actionbar world name  
- State-in-lore; all text from `lang.yml`  
- FX on open + menu actions (`config.yml` effects)

---

## Kill rules

| Rule | Detail |
|------|--------|
| Credit source | **PvPManager API** — who killed whom / died while in combat with whom |
| Window | Only `starttime ≤ death < endtime` |
| After end | Ignored |
| Location | **Kills count anywhere** entire event (including after FFA). Cuboid is **not** a kill boundary |
| Messages | **We do not modify kill messages** (never was in the initial command spec) |
| Persist | **SQLite** — survives restarts |
| Win | Highest kills; **dense ranking with shared places** |

### Tie-break / ranking

Example: kills `10, 10, 8, 8, 5`

| Players | Place |
|---------|-------|
| two with 10 | both **#1** |
| two with 8 | both **#2** |
| one with 5 | **#3** |

(Shared first → next rank is 2nd, not “skip to 3rd”.)

### Ceremony FX

| Who | Title | Sound |
|-----|-------|-------|
| **Everyone** ranked | Place + kills (lang) | Normal end sound |
| **#1 / #2 / #3** place labels | Gold / silver / bronze title variants | **Heroic** sound (in addition or instead of normal — config) |

Offline players: still in ranking as winners/placers; titles/sounds only deliver to **online** at ceremony (offline keep stored place).

---

## Final arena (cuboid = spawn geometry only)

### Admin

| Action | |
|--------|--|
| Wand / `set pos1` `set pos2` | Axis-aligned cuboid |
| `set centerspawn` | Ring center (required) |
| No reset | Normal world forever |

Cuboid is used to:

1. Compute ring/oval/multi-ring/random **spawn points** at FFA  
2. Detect who is **outside** for the post-FFA **actionbar nudge**  

**Not used for:** kill credit, enter/leave bans, force-stay, spectators.

### FFA teleport — once

**Who:** online, **unvanished** (Essentials if present, else metadata `vanished`), **survival** game mode, **without** `preciv.ffa.tp.bypass`.

**What:** one mass teleport onto safe points; no re-TP loop; no cuboid prison.

**Pattern algorithm:**

1. Max **circle/oval diameter ≤ `max-diameter-fraction`** of longest cuboid horizontal side (upper bound)  
2. Ellipse fits inside cuboid with margin; prefer near **centerspawn**  
3. **Player-count scaling:** target chord ≈ `min-player-spacing` between adjacent ring mates so 2 players stand close enough to see each other; radius grows with N until the diameter cap  
4. Even angles for N players; **≥2 air** above feet; Y near center Y with bounded scan, then full cuboid column  
5. Floor must be solid and **non-liquid**; body column non-liquid / non-waterlogged — **never invent feet Y on water**  
6. If a planned column is wet/unsafe: nudge nearby → center → random dry columns; only then skip/pad  
7. If horizontal distance between assigned points would be **&lt; 1 block**: multi-ring and/or random viable samples inside the ellipse  
8. Soft-lock avoided: pad last good location if some columns fail; log when the arena has no dry ground

### After FFA — outside cuboid actionbar

For players **outside** the cuboid when/after FFA (and still in FFA phase):

- Show **actionbar for ~1 minute** (configurable duration + text)  
- Example: `Event finale is here — /warp pvparena`  
- Fully configurable in lang/config  
- Does **not** force teleport; does **not** block leaving  

---

## Explicitly out of scope (for now)

| Topic | Status |
|-------|--------|
| Teleport lock / spawn safe zone | **Removed** — not needed |
| Arena block reset | **Never** |
| Custom death handling | **Never** (except compass drop suppress + respawn re-give) |
| Kill message edits | **Never** |
| Cuboid enter/leave kill rules | **Never** |
| Spectators | **No** |
| Prize / command hooks | **Message only** — top-N get short congrats; staff get private list; no economy/crates yet |

---

## Sounds & particles

Config-driven `EffectService` — open compass, menu click/page/deny, kill credited, FFA announce, FFA TP, end normal, end top-3 heroic.

---

## UX & configurability

- All messages, menu titles, item names, **per-state lore** in `lang.yml`  
- Materials, slots, timings, effects, TAB lines in `config.yml`  
- State visible in lore where useful  
- No hard-coded player-facing strings  

---

## Persistence & performance (SQLite)

| Data | Stored |
|------|--------|
| Event times / derived FFA moment / phase | Yes |
| Kill counts (UUID → kills) | Yes |
| Metrics (event status, aggregates) | Yes |
| Arena pos1/pos2/center | Config and/or DB |

Rules:

- **Events persist through restarts** in every phase (countdown, hunt, FFA)  
- On enable: restore phase, reschedule announce/FFA/end tasks, restore UI  
- **`ffa_teleported` / `ceremony_done`:** one-shot flags so FFA TP and ceremony do not re-fire every tick or after mid-phase restart  
- Vanish: Essentials preferred soft-dep; metadata fallback only when Essentials absent
- Config **read/write protected** (single writer / sync) and **performant** (cache in memory; flush async or batched)  
- Kill increments: memory + durable write (async queue OK if consistent on crash window is acceptable — prefer safe flush on kill and on disable)

---

## Commands

| Command | Description |
|---------|-------------|
| `/event` | Event description (`lang.yml`) |
| `/preciv compass` | Give compass if not already held |
| `/preciv killtop` | Kill leaderboard |
| `/preciv admin set starttime <yyyy/mm/dd hh:mm:ss>` | Start (UTC+0) |
| `/preciv admin set endtime <yyyy/mm/dd hh:mm:ss>` | End (UTC+0); FFA = end − config offset |
| `/preciv admin set ffatime <…>` | Optional absolute FFA override (if kept) |
| `/preciv admin set centerspawn` | Ring center |
| `/preciv admin wand` | Selection wand |
| `/preciv admin set pos1` / `pos2` | Cuboid corners |
| `/preciv admin status` | Phase, times, flags, vanish backend, eligible count |
| `/preciv admin startnow` / `ffanow` / `endnow` | Time-jumps (phase stays clock-derived) |
| `/preciv admin phase <…>` | Time-jump helper to a named phase |
| `/preciv admin forcetp` / `forceceremony` / `resetflags` | Re-arm / re-run one-shots |
| `/preciv admin eligible` | List FFA TP candidates |
| `/preciv admin clearkills confirm` | Wipe kill table |
| `/preciv admin reload` | Config + lang + effects (not live event times) |

### Permissions (draft)

| Permission | Default | Purpose |
|------------|---------|---------|
| `preciv.event` | true | `/event` |
| `preciv.compass` | true | `/preciv compass` |
| `preciv.killtop` | true | killtop |
| `preciv.admin` | op | Admin setup |
| `preciv.ffa.tp.bypass` | false | Skip FFA mass teleport |

---

## Dependencies

```yaml
# plugin.yml
depend: [TAB, PvPManager]   # exact PvPManager plugin name as installed
```

- Triumph GUI: shaded + relocated  
- SQLite: JDBC driver shaded or Paper-friendly embedded  

---

## Config sketch

```yaml
times:
  start: ""
  end: ""
  # ffa absolute optional override; else end - ffa-before-end
  ffa: ""

ffa:
  before-end-seconds: 1800          # 30 minutes before end → teleport
  announce-lead-seconds: 3600       # 60 minutes before FFA
  announce-interval-seconds: 600    # every 10 minutes
  outside-actionbar-seconds: 60
  max-diameter-fraction: 0.75       # diameter cap (many players)
  min-player-spacing: 8             # chord between neighbors; shrinks ring for small N
  min-air-above: 2
  y-search-range: 12
  ring-margin-blocks: 2

arena:
  world: world
  pos1: { x: 0, y: 64, z: 0 }
  pos2: { x: 100, y: 80, z: 100 }
  centerspawn: { x: 50.5, y: 65.0, z: 50.5, yaw: 0.0, pitch: 0.0 }

storage:
  type: sqlite
  file: data.db

tab:
  scoreboard:
    offset: 3
    top-slots: 3
    empty-name: "—"
    empty-kills: "0"
    lines:
      - "&c&l#1 &f%top1_name% &8(%top1_kills%)"
      - "&6#2 &f%top2_name% &8(%top2_kills%)"
      - "&e#3 &f%top3_name% &8(%top3_kills%)"
      - "&7%phase% &8| &f%remaining%"   # remaining = until next phase
  bossbar:
    enabled: true

effects:
  enabled: true
  # compass / menu / kill / ffa / end.normal / end.top3 …
```

```yaml
# lang fragments
ffa:
  announce-title: "&c&lFinal deathmatch"
  announce-subtitle: "&7Begins in &f%countdown%"
  outside-actionbar: "&eEvent finale is here — &f/warp pvparena"
end:
  title:
    place-1: { title: "&6&lGOLD — #1", subtitle: "&e%kills% kills" }
    place-2: { title: "&f&lSILVER — #2", subtitle: "&7%kills% kills" }
    place-3: { title: "&c&lBRONZE — #3", subtitle: "&7%kills% kills" }
    place-other: { title: "&7Place &f#%place%", subtitle: "&7%kills% kills" }
```

---

## Suggested package layout

```
com.fusion.dev.cystol
├── DayOfAssassinsPlugin
├── command/
├── event/
│   ├── EventManager              # state machine + schedules
│   └── EventState
├── kill/
│   ├── PvpManagerKillListener    # PvPManager API
│   └── KillService               # memory + SQLite
├── compass/
├── arena/
│   ├── CuboidBounds / RingSpawnMath  # N-scaled spacing + diameter cap
│   ├── ArenaWandListener
│   └── FfaSpawnService               # dry Y, batched TP
├── display/
│   ├── EventDisplayRenderer          # pure bossbar + inject templates
│   └── TabDisplayService             # TAB inject + stacked bossbar
├── ceremony/
│   └── EndCeremonyService        # dense rank, titles, sounds
├── fx/
│   └── EffectService
├── storage/
│   ├── SqliteDatabase
│   ├── EventRepository
│   ├── KillRepository
│   └── MetricsRepository
├── config/
│   ├── PluginConfig              # thread-safe cached reads
│   └── Lang
└── util/
```

---

## Mental model

```
Admin: start + end (+ arena wand/center). FFA = end − 30m (config).
        ↓
Countdown filling bossbar (stacks) + optional inject rows
        ↓
Hunt: compass + PvPManager kills → SQLite; HUD top-N inject + phase clock
        ↓
Announce titles → one N-scaled dry ring TP (survival, unvanished, no bypass)
        ↓
Open world FFA stretch; outside cuboid → 1m actionbar hint
        ↓
End: shared places; all titles+sounds; top3 medal+heroic; restore inject rows
```

---

## Changelog (discussion log)

| Date | Notes |
|------|--------|
| 2026-07-17 | Initial scaffold + full design discussion |
| 2026-07-17 | TAB, Triumph GUI, UX/lang, effects |
| 2026-07-17 | Arena wand, ceremony, FFA announce |
| 2026-07-17 | **Decisions locked:** FFA = before end (e.g. 30m); shared places; no TP lock; cuboid spawn-only; PvPManager kills; SQLite full event persist; TAB hard depend; filling pre-start bossbar; multi-ring spawn algo; outside actionbar; compass respawn re-give; no spectators/prizes yet |
| 2026-07-17 | **Display:** inject-only scoreboard (top-N configurable); bossbar stacks; `%remaining%` = next phase; phase-segment bar fill |
| 2026-07-17 | **FFA spawn:** `min-player-spacing` scaling; refuse water/lava feet; dry-column rescue |
