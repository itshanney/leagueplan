# Tech Spec: Schedule Lifecycle, Viewing, Export, and Backward Compatibility

* **Date:** 2026-05-24
* **Status:** Ready for Implementation
* **Scope:** "Schedule Lifecycle", "Schedule Viewing", "Schedule Export", and "Backward Compatibility" acceptance criteria from `features/2026-05-17-league-planner-core-scheduling-v2.md`
* **Coordinates with:**
  * `specs/2026-05-18-phase1-team-schedule.md` — defines the TEAM_SCHEDULE state and `TeamSchedule`/`TeamGame` types this spec operates on
  * `specs/2026-05-23-phase2-field-assignment.md` — defines the DRAFT state and `Schedule`/`ScheduledGame` types this spec operates on

---

## Overview

This spec covers the four remaining acceptance criterion groups from the v2 PRD. Together they close out the full schedule lifecycle:

1. **Schedule Lifecycle** — the state machine governing valid transitions between NONE, TEAM_SCHEDULE, DRAFT, and FINALIZED, plus the finalize action and the individual game override.
2. **Schedule Viewing** — `planr schedule view` renders the correct read model for every state, with optional filters for DRAFT/FINALIZED views and a `--team-schedule` flag for the matchup-only view.
3. **Schedule Export** — `planr schedule export` streams a JSON array to stdout, producing either a matchup-only or full-schedule payload depending on state and flags.
4. **Backward Compatibility** — `LeagueStore.load()` upgrades persisted files from v1/v2/v3 to v4 in a single pass, emitting a one-time stderr warning when availability windows are discarded.

No new top-level services are introduced. All logic lives in the existing command layer (`ScheduleCommand`, `ScheduleGameCommand`), model layer (`Schedule`, `ScheduledGame`, `ScheduleState`, `ScheduleStatus`), and store layer (`LeagueStore`). The `league.json` schema version advances to `4`.

---

## Component Diagram

```
PlanrApp
  └── ScheduleCommand
        ├── GenerateMatchupsCmd     (Phase 1 — defined in phase1 spec; state guard here)
        ├── AssignCmd               (Phase 2 — defined in phase2 spec; state guard here)
        ├── StatusCmd               (read-only summary of current state)
        ├── FinalizeCmd             (DRAFT → FINALIZED, one-way, requires confirmation)
        ├── ViewCmd                 (read-only render of team schedule or full schedule)
        ├── ExportCmd               (JSON export to stdout)
        └── ScheduleGameCommand
              ├── EditHomeAwayCmd   (swap home/away on a TeamGame; available in TEAM_SCHEDULE, DRAFT)
              └── OverrideCmd       (edit individual ScheduledGame fields; FINALIZED only)

LeagueStore
  └── load()                        (deserializes league.json, runs migration chain v1→v4)

Model
  ├── ScheduleState (enum)          (NONE | TEAM_SCHEDULE | DRAFT | FINALIZED — derived, not persisted)
  ├── ScheduleStatus (enum)         (DRAFT | FINALIZED — persisted inside Schedule.status)
  ├── Schedule (record)             (persisted; status, seasonStart, seasonEnd, games)
  └── ScheduledGame (record)        (persisted inside Schedule.games; full assignment + overridden flag)
```

### Component responsibilities

| Component | Responsibility |
|---|---|
| `ScheduleState` | Derives the current state from `(league.teamSchedule(), league.schedule())` nullability and `schedule.status()`; used everywhere a state guard is needed |
| `ScheduleStatus` | Enum persisted inside `Schedule`; drives whether FINALIZED commands are allowed |
| `FinalizeCmd` | Validates DRAFT state, prints irreversibility warning, requires `yes`, atomically writes FINALIZED schedule |
| `ViewCmd` | Renders team schedule (matchups + stats) or full schedule (date/time/field table with filters); routes on state + flags |
| `ExportCmd` | Serializes team schedule or full schedule to a JSON array on stdout; metadata line to stderr |
| `OverrideCmd` | Edits any combination of date/start/field/home/away on a single FINALIZED `ScheduledGame`; non-blocking field-conflict warning |
| `EditHomeAwayCmd` | Swaps home/away on a single `TeamGame`; available in TEAM_SCHEDULE and DRAFT states |
| `LeagueStore.load()` | Applies v1→v2→v3→v4 migration in sequence on every load; idempotent for v4 files |

---

## Data Model

### `ScheduleState` — derived enum (not persisted)

```
NONE          league.teamSchedule == null  &&  league.schedule == null
TEAM_SCHEDULE league.teamSchedule != null  &&  league.schedule == null
DRAFT         league.schedule != null      &&  schedule.status == DRAFT
FINALIZED     league.schedule != null      &&  schedule.status == FINALIZED
```

Computed by the static factory `ScheduleState.of(League)`. Every command that is state-gated calls this and returns exit 1 with an appropriate error message if the precondition fails.

### `Schedule` — persisted record

```
Schedule
  ├── status: ScheduleStatus         (DRAFT | FINALIZED)
  ├── seasonStart: LocalDate
  ├── seasonEnd: LocalDate
  └── games: List<ScheduledGame>
```

Mutations return new records (`withStatus`, `withGameReplaced`). Only `LeagueStore` writes to disk.

### `ScheduledGame` — persisted record

```
ScheduledGame
  ├── id: UUID
  ├── date: LocalDate
  ├── startTime: LocalTime
  ├── fieldId: UUID
  ├── fieldName: String              (denormalized for export/view without joins)
  ├── homeTeamId: UUID
  ├── homeTeamName: String           (denormalized)
  ├── awayTeamId: UUID
  ├── awayTeamName: String           (denormalized)
  ├── divisionId: UUID
  ├── divisionName: String           (denormalized)
  ├── gameDurationMinutes: int
  └── overridden: boolean            (set true on any OverrideCmd application; shown as * in view)
```

`withOverride(...)` returns a new `ScheduledGame` with `overridden = true`. Null arguments are treated as "no change" (previous value retained).

### `TeamGame` — persisted record (defined in phase1 spec, modified here)

`withSwappedHomeAway()` returns a new `TeamGame` with home/away fields swapped. This is the only mutation added in this slice.

### `league.json` — version 4 schema additions

```json
{
  "version": 4,
  "config": { "sunrise": "HH:mm", "sunset": "HH:mm", "seasonStart": "YYYY-MM-DD", "seasonEnd": "YYYY-MM-DD" },
  "divisions": [...],
  "fields": [...],                   // Field.blocks and Field.dateOverrides are lists (may be empty)
  "teamSchedule": {                  // null until Phase 1 completes
    "games": [...]
  },
  "schedule": {                      // null until Phase 2 completes
    "status": "DRAFT" | "FINALIZED",
    "seasonStart": "YYYY-MM-DD",
    "seasonEnd": "YYYY-MM-DD",
    "games": [...]
  }
}
```

Both `teamSchedule` and `schedule` are nullable top-level fields. Their co-existence is intentional: in DRAFT state both are populated; in TEAM_SCHEDULE state only `teamSchedule` is populated; in FINALIZED state both remain (the team schedule is preserved so `--team-schedule` view and export remain available).

---

## State Machine

```
        planr schedule generate
NONE ─────────────────────────────────→ TEAM_SCHEDULE
  ↑                                           │
  │                                  planr schedule assign
  │                                           │
  │      planr schedule generate              ↓
  └─────────────────────────────────────── DRAFT
                                             │  ↑
                                             │  │ planr schedule generate (discards Draft)
                                             │  │ planr schedule assign   (replaces Draft)
                                             │  └───────────────────────────────────────
                                   planr schedule finalize
                                             │
                                             ↓
                                         FINALIZED
                                         (terminal)
```

**Permitted mutations by state:**

| Action | NONE | TEAM_SCHEDULE | DRAFT | FINALIZED |
|---|---|---|---|---|
| `schedule generate` | ✓ | ✓ (confirm, discards) | ✓ (confirm, discards Draft) | ✗ |
| `schedule assign` | ✗ | ✓ | ✓ (replaces Draft) | ✗ |
| `schedule finalize` | ✗ | ✗ | ✓ | ✗ |
| `schedule game edit` | ✗ | ✓ | ✓ | ✗ |
| `schedule game override` | ✗ | ✗ | ✗ | ✓ |
| `schedule view` | ✗ | ✓ | ✓ | ✓ |
| `schedule export` | ✗ | ✓ (team schedule only) | ✓ (both) | ✓ (full schedule only) |

---

## CLI Command Contracts

### `planr schedule finalize`

Preconditions:
- Schedule must be in DRAFT state → exit 1 `"Error: No draft schedule to finalize."`

Flow:
1. Prints confirmation warning: `"Warning: Finalizing the schedule is irreversible. The schedule will be locked and cannot be regenerated. Type 'yes' to confirm: "`
2. Reads one line from stdin. If not `"yes"`, prints `"Finalization cancelled."` → exit 0.
3. Builds new `Schedule` with `status = FINALIZED` via `schedule.withStatus(FINALIZED)`.
4. Saves via `store.save(league.withSchedule(finalizedSchedule))`.
5. Prints: `"Schedule finalized. N games locked. Use 'planr schedule game override' for individual adjustments."` → exit 0.

No re-validation is performed. The persisted `teamSchedule` field is preserved unchanged.

---

### `planr schedule game override <game-number> [--date] [--start] [--field] [--home] [--away]`

Preconditions:
- Schedule must be FINALIZED → exit 1 `"Error: 'game override' requires a finalized schedule. Run 'planr schedule finalize' first."`
- At least one option must be present → exit 1 `"Error: At least one override option must be provided."`

Argument validation (each option independently):
- `--date <YYYY-MM-DD>`: parsed with `LocalDate.parse()`; invalid → exit 1 with format error
- `--start <HH:mm>`: parsed with `DateTimeFormatter.ofPattern("HH:mm")`; invalid → exit 1 with format error
- `--field <name>`: resolved case-insensitively via `league.findField()`; not found → exit 1
- `--home <team>`: resolved by `resolveTeam()` (prefers same division; falls back league-wide); not found → exit 1
- `--away <team>`: same resolution as `--home`; not found → exit 1

Mutation:
1. Loads the game at `gameNumber - 1` (0-based) from `schedule.games()`; out-of-range → exit 1.
2. Calls `game.withOverride(newDate, newStart, newFieldId, newFieldName, newHomeId, newHomeName, newAwayId, newAwayName)` — null arguments retain previous value; result always has `overridden = true`.
3. Replaces the game in the schedule via `schedule.withGameReplaced(zeroIndex, updated)`.

Conflict check (non-blocking, post-mutation):
- Iterates all other games on `updated.date()` at `updated.fieldId()`.
- Checks for overlap: game A and game B conflict if their time intervals — each extended by 15 minutes — overlap: `aStart < bEnd && bStart < aEnd` (using minutes-since-midnight arithmetic).
- If any conflict found: prints to stderr: `"Warning: Game #N now conflicts with game #M at <field> on <date> (overlapping times including the 15-minute buffer). Game #N saved anyway."`
- Saves regardless of conflict.

Success output: `"Game #N updated."` → exit 0.

---

### `planr schedule game edit <game-number> --home <team>`

Preconditions:
- State must not be NONE → exit 1 `"Error: No team schedule found. Run 'planr schedule generate' first."`
- State must not be FINALIZED → exit 1 `"Error: Schedule is finalized. Use 'planr schedule game override' to modify individual games."`
- `league.teamSchedule()` must be non-null.

Validation:
- `gameNumber` must match a game in the team schedule (`teamSchedule.findGame(gameNumber)`); not found → exit 1.
- `teamName` must match either `homeTeamName` or `awayTeamName` (case-insensitive) of the target game; neither → exit 1 with specific error naming both participants.
- If `teamName` already matches `homeTeamName`: prints `"Game #N: <team> is already the home team. No change made."` → exit 0.

Mutation:
- Calls `game.withSwappedHomeAway()`, replaces in `TeamSchedule` via `teamSchedule.withGameReplaced(gameNumber, swapped)`.
- Saves via `store.save(league.withTeamSchedule(updated))`.

Success output: `"Game #N updated: <home> (home) vs <away> (away)."` → exit 0.

---

### `planr schedule view [--division <name>] [--team <name>] [--field <name>] [--team-schedule]`

Preconditions:
- State must not be NONE → exit 1 `"Error: No schedule generated yet. Run 'planr schedule generate' to create one."`

Routing logic:
```
if (--team-schedule flag OR state == TEAM_SCHEDULE):
    → team schedule view path
else:
    → full schedule view path (requires league.schedule() != null)
```

**Team schedule view path** (matchups + stats):
- Validates `league.teamSchedule() != null`.
- If `--team-schedule` was explicitly passed alongside `--division`, `--team`, or `--field`: prints a note that filters are not applicable (no dates or fields assigned), then proceeds.
- Prints: `"Schedule status: TEAM_SCHEDULE"`
- Renders the matchup table: columns `#`, `HOME`, `AWAY`, `DIVISION`; column widths auto-fit to content.
- Renders per-division stats blocks (HOME/AWAY BALANCE table + HEAD-TO-HEAD matrix) — defined in `specs/2026-05-23-team-schedule-stats-view.md`.

**Full schedule view path** (date/time/field table):
- Validates `league.schedule() != null`.
- Validates named filters: `--division` → `league.findDivision()`; `--field` → `league.findField()`; `--team` → linear search across all divisions; unknown name for any filter → exit 1 with `"Error: <type> \"<name>\" not found."`.
- Prints header: `"Schedule status: <DRAFT|FINALIZED> | Season: <start> to <end>"`
- Applies filters (all three are independent AND conditions): builds `List<ScheduledGame> filtered`.
- If `filtered` is empty: prints `"No games match the specified filter."` → exit 0.
- Computes 1-based label for each game; appends `*` suffix if `game.overridden() == true`.
- Renders table: columns `#`, `DATE`, `START`, `FIELD`, `HOME`, `AWAY`, `DIVISION`; column widths auto-fit.

---

### `planr schedule export [--team-schedule]`

Preconditions:
- State must not be NONE → exit 1 `"Error: No schedule generated yet."`

Routing logic:
```
if (--team-schedule flag OR state == TEAM_SCHEDULE):
    → team schedule export path
else:
    → full schedule export path
```

**Team schedule export path:**
- Validates `league.teamSchedule() != null`.
- If `--team-schedule` was explicitly passed in FINALIZED state: exit 1 `"Error: Team schedule export is not available for a finalized schedule."`
- Serializes `teamSchedule.games()` as a JSON array; each element:
  ```json
  {
    "game_number": 1,
    "home_team": "Blue Jays",
    "away_team": "Cardinals",
    "division_name": "Majors"
  }
  ```
- Writes JSON array to stdout.
- Writes `"Exported N games (team schedule)."` to stderr.

**Full schedule export path:**
- Validates `league.schedule() != null`; else exit 1 `"Error: No draft or finalized schedule exists. Run 'planr schedule assign' first, or use --team-schedule to export matchups."`
- Serializes `schedule.games()` as a JSON array; each element:
  ```json
  {
    "date": "2026-06-14",
    "start_time": "09:00",
    "field_name": "Riverside Park",
    "home_team": "Blue Jays",
    "away_team": "Cardinals",
    "division_name": "Majors",
    "status": "draft"
  }
  ```
  `status` is `schedule.status().name().toLowerCase()` → `"draft"` or `"finalized"`.
  `start_time` is formatted as `"HH:mm"` (zero-padded).
- Writes JSON array to stdout.
- Writes `"Exported N games."` to stderr.

**ObjectMapper configuration for export** (local to `ExportCmd`, separate from `LeagueStore`'s mapper):
- `INDENT_OUTPUT` enabled
- `WRITE_DATES_AS_TIMESTAMPS` disabled
- `JavaTimeModule` registered

Note: Export writes to stdout, not a file. The organizer redirects: `planr schedule export > schedule.json`. This is idiomatic for CLI tools and avoids the need for a `--output` flag.

---

### `planr schedule view` — State availability matrix

| State | Default (`view`) | `view --team-schedule` | `export` | `export --team-schedule` |
|---|---|---|---|---|
| NONE | Error | Error | Error | Error |
| TEAM_SCHEDULE | Team schedule view | Team schedule view | Team schedule JSON | Team schedule JSON |
| DRAFT | Full schedule table | Team schedule view | Full schedule JSON | Team schedule JSON |
| FINALIZED | Full schedule table | Team schedule view | Full schedule JSON | Error (not available) |

---

## Backward Compatibility — Migration Chain

`LeagueStore.load()` runs a sequential migration chain on every load. Migrations are applied in ascending version order; each step saves before the next check, so a crash mid-chain leaves a valid intermediate file.

### v1 → v2 (adds empty `fields` list)

```java
league = new League(2, null, league.divisions(), List.of(), null, null);
save(league);
```

v1 files have no `fields` key. Jackson deserializes `fields` as null; `League`'s compact constructor normalizes it to `List.of()`. The explicit `List.of()` here is idempotent. `config` is set to null — the v3→v4 migration will provide `LeagueConfig.empty()`.

### v2 → v3 (no-op marker; preserves fields)

```java
league = new League(3, null, league.divisions(), league.fields(), null, null);
save(league);
```

v2 fields had per-field availability windows (now removed from the `Field` model). Jackson's `FAIL_ON_UNKNOWN_PROPERTIES = false` drops those keys on deserialization. This step is a version-bump-only migration to ensure the file reaches v3 without any data loss on the remaining fields.

### v3 (and any pre-v4) → v4 (clears field blocks/overrides; adds `LeagueConfig`)

```java
if (league.version() < 4) {
    List<Field> migratedFields = league.fields().stream()
        .map(f -> new Field(f.id(), f.name(), f.address(), List.of(), List.of()))
        .toList();
    LeagueConfig config = LeagueConfig.empty();
    league = new League(4, config, league.divisions(), migratedFields, null, league.schedule());
    save(league);
    System.err.println("Warning: Field availability windows from a previous version "
        + "have been removed. Please configure field blocks for the new season.");
}
```

Key behaviors:
- `Field.blocks` and `Field.dateOverrides` are reset to empty lists — any pre-v4 availability window data is discarded.
- `LeagueConfig.empty()` creates a config with null sunrise/sunset/seasonStart/seasonEnd — the organizer must set these via `planr config set`.
- `league.schedule()` is preserved if present (carries forward any previously persisted schedule).
- `league.teamSchedule()` is set to null — it was not present in v3 and the field is not preserved.
- Warning is printed to **stderr** (not stdout) so it does not pollute piped output.
- The warning prints exactly once: on the first load after migration. Subsequent loads find `version == 4` and skip this branch.

### v4 files

No migration applied. Returned as-is.

### Migration invariants

- `version` field in the persisted file always reflects the actual schema version after migration.
- The `FAIL_ON_UNKNOWN_PROPERTIES = false` setting on `ObjectMapper` ensures fields from future versions are silently ignored when loading a file on older software.
- The compact constructor on `League` normalizes null `divisions` and `fields` to `List.of()`, making the migration chain safe against partially-populated files from any version.

---

## Critical Path Walkthroughs

### 1. Draft → Finalized → Override

```
User: planr schedule finalize
  1. load() → League; ScheduleState.of() == DRAFT ✓
  2. Print irreversibility warning; read stdin
  3. Input "yes" → build Schedule(FINALIZED, ...) via withStatus()
  4. store.save(league.withSchedule(finalizedSchedule))
  5. Print "Schedule finalized. 12 games locked."
  Exit 0.

User: planr schedule game override 3 --date 2026-07-04 --field "North Field"
  1. load() → ScheduleState == FINALIZED ✓; schedule.status == FINALIZED ✓
  2. Parse --date: LocalDate.parse("2026-07-04") ✓
  3. Resolve --field "North Field" via league.findField() → Field{id=..., name="North Field"} ✓
  4. game = schedule.games().get(2)  (0-based index 2)
  5. updated = game.withOverride(2026-07-04, null, northFieldId, "North Field", null, null, null, null)
     → overridden = true
  6. updatedSchedule = schedule.withGameReplaced(2, updated)
  7. Conflict check: iterate other games on 2026-07-04 at northFieldId
     → no overlap found → no warning
  8. store.save(league.withSchedule(updatedSchedule))
  9. Print "Game #3 updated."
  Exit 0.

User: planr schedule view
  → Full schedule table; game #3 row shows label "3*"
```

### 2. Full schedule view with filters

```
User: planr schedule view --division Majors --team "Blue Jays"
  1. ScheduleState == DRAFT or FINALIZED ✓
  2. Validate --division "Majors": league.findDivision("Majors") → found ✓
  3. Validate --team "Blue Jays": search all division.teams() case-insensitively → found ✓
  4. filtered = schedule.games().stream()
       .filter(g -> g.divisionName().equalsIgnoreCase("Majors"))
       .filter(g -> g.homeTeamName().equalsIgnoreCase("Blue Jays")
                 || g.awayTeamName().equalsIgnoreCase("Blue Jays"))
       .toList()
  5. Compute labels: 1-based position in original schedule.games() list; append * if overridden
  6. Render table with computed column widths
  Exit 0.
```

### 3. Team schedule export (DRAFT state)

```
User: planr schedule export --team-schedule
  1. ScheduleState == DRAFT; --team-schedule flag set
  2. Routing: exportTeamSchedule=true → team schedule export path
  3. State != FINALIZED → no block
  4. league.teamSchedule() != null ✓
  5. Build List<ExportTeamGame>: map each TeamGame → {game_number, home_team, away_team, division_name}
  6. mapper.writeValueAsString(exports) → stdout
  7. "Exported 12 games (team schedule)." → stderr
  Exit 0.
```

### 4. v3 → v4 migration on first load

```
User: planr schedule status   (first command after upgrade from v3 binary)
  1. LeagueStore.load():
     a. Files.exists(LEAGUE_FILE) → true
     b. mapper.readValue(...) → League{version=3, config=null, divisions=[...], fields=[...]}
     c. version < 4 → migration block:
        - migratedFields: clear blocks/dateOverrides on all fields
        - config = LeagueConfig.empty()
        - league = League{version=4, config=LeagueConfig.empty(), ..., teamSchedule=null, schedule=oldSchedule}
        - save(league) → league.json updated
        - stderr: "Warning: Field availability windows from a previous version have been removed. ..."
     d. return league (version 4)
  2. StatusCmd executes normally with the migrated league
```

### 5. Conflict warning on override

```
User: planr schedule game override 2 --start 09:00 --date 2026-06-07 --field "Riverside Park"
  (Game #1 is already at Riverside Park on 2026-06-07 from 09:00, duration 60 min)

  Conflict check (game #2 vs game #1):
    game1: date=2026-06-07, field=Riverside Park, startTime=09:00, duration=60
    game2 (updated): date=2026-06-07, field=Riverside Park, startTime=09:00, duration=60
    aStart=540, aEnd=540+60+15=615
    bStart=540, bEnd=615
    540 < 615 && 540 < 615 → overlap detected

  stderr: "Warning: Game #2 now conflicts with game #1 at Riverside Park on 2026-06-07
           (overlapping times including the 15-minute buffer). Game #2 saved anyway."
  store.save(...)
  stdout: "Game #2 updated."
  Exit 0.
```

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| Export to stdout vs file | (A) stdout; (B) `--output <path>` flag; (C) auto-write to `./schedule.json` | **(A) stdout** | Standard CLI idiom; composable with shell redirection. No file path management logic needed. | The organizer must remember to redirect. The stderr count line provides feedback even in a piped invocation. |
| `status` field in full export | (A) per-game literal `"draft"` or `"finalized"`; (B) top-level metadata object; (C) omit status | **(A) per-game literal** | PRD specifies this explicitly. Adding status at the game level makes each object self-contained — a consumer doesn't need to inspect a wrapper. | Redundant across every game in the array. Acceptable at the small scale of a single-league file. |
| Conflict check on override — non-blocking | (A) block save on conflict; (B) non-blocking warning; (C) no conflict check | **(B) non-blocking warning** | PRD explicitly requires non-blocking. Organizer may intentionally create back-to-back games (e.g., both fields are double-headers) and knows the layout better than the tool. | Organizer can persist conflicting games silently if they dismiss the warning. No automatic resolution mechanism. |
| Team schedule preserved through finalization | (A) preserve; (B) clear on finalize; (C) clear only after re-generating Phase 1 | **(A) preserve** | Keeps the `--team-schedule` view and team schedule export available on finalized leagues. The matchup history is useful for reference (e.g., confirming head-to-head counts after the season). | Slightly larger `league.json`. Not significant at this scale. |
| Team schedule export blocked in FINALIZED state | (A) block; (B) allow in all states with a team schedule; (C) allow with a deprecation warning | **(A) block** | PRD explicitly limits team schedule export to TEAM_SCHEDULE and DRAFT states. In FINALIZED state, the full schedule export is the authoritative artifact; the matchup-only export would be misleading (it lacks the finalized overrides). | Organizer cannot export matchups from a finalized league. If needed, they can `schedule view --team-schedule` to inspect matchups at the terminal. |
| Migration chain: v1 fields cleared vs preserved | Clear fields on v1→v2 | **Clear to `List.of()`** | v1 had no `fields` key; Jackson deserializes null. Migration normalizes to empty list. No data is lost because there was none. | None. |
| Migration: discard availability windows, no conversion | (A) discard; (B) attempt conversion to new block format | **(A) discard** | The new block model is fundamentally different from v1–v3 windows (blocks subtract from a default-open window; v3 windows were explicit open windows). Lossless conversion is not possible. | Organizer loses previously configured availability. The one-time stderr warning is the only notification. |
| `ScheduleState` as derived enum | (A) derived from League fields; (B) persisted as a separate field | **(A) derived** | Single source of truth. Deriving from `(teamSchedule, schedule, schedule.status)` nullability is unambiguous and eliminates the possibility of an inconsistent persisted state. | Any command that needs state must call `ScheduleState.of(league)` after `store.load()`. This is cheap and consistent. |
| Conflict check: buffer model | (A) aEnd = aStart + gameDuration + 15; (B) store buffer as a separate field; (C) configurable buffer | **(A) hard-coded 15-min extension** | PRD specifies fixed 15-minute buffer, explicitly out-of-scope for configuration. The buffer is only needed at conflict-check time; it is not persisted. | Cannot be changed without a code change. Acceptable for MVP. |

---

## Operational Concerns

### Testing Strategy

**`ScheduleCommandTest` — finalize lifecycle:**

| Test | Assertion |
|---|---|
| `finalize` exits 1 when no draft exists | `stderr().contains("No draft schedule")` |
| `finalize` cancels when input is not "yes" | `exit == 0`; `stdout().contains("cancelled")` |
| `finalize` transitions state on "yes" | After finalize, `schedule status` shows `FINALIZED` |
| Phase 1 blocked after finalization | `schedule generate` exits 1; `stderr().contains("finalized schedule")` |
| Phase 2 blocked after finalization | `schedule assign` exits 1 |

**`ScheduleCommandTest` — game override:**

| Test | Assertion |
|---|---|
| Override exits 1 in DRAFT state | `stderr().contains("finalized schedule")` |
| Override exits 1 with no options | `stderr().contains("At least one override option")` |
| Override exits 1 for game number out-of-range | `stderr().contains("not found")` |
| Override exits 1 for unknown field | `stderr().contains("not found")` |
| Override exits 1 for unknown team | `stderr().contains("not found")` |
| Override succeeds with valid date | `exit == 0`; `stdout().contains("updated")` |
| Override marks game with `*` in view | After override + view, `stdout().contains("*")` |
| Conflict warning emitted on overlap | `stderr().contains("Warning")` |
| Override saves without full constraint re-validation | Override does not fail even when out-of-season date is supplied |

**`ScheduleCommandTest` — game edit:**

| Test | Assertion |
|---|---|
| Edit exits 1 in NONE state | `stderr().contains("No team schedule found")` |
| Edit exits 1 in FINALIZED state | `stderr().contains("finalized schedule")` |
| Edit exits 1 when team not in game | `stderr().contains("not playing in game")` |
| Edit is no-op when team is already home | `exit == 0`; `stdout().contains("already the home team")` |
| Edit swaps home/away correctly | After edit + view `--team-schedule`, table shows new assignment |

**`ScheduleCommandTest` — view:**

| Test | Assertion |
|---|---|
| View exits 1 in NONE state | `stderr().contains("No schedule generated yet")` |
| View in TEAM_SCHEDULE shows matchup table | Column headers `HOME`, `AWAY`, `DIVISION` present |
| View in DRAFT shows full schedule table | Column headers `DATE`, `START`, `FIELD` present |
| `--division` filter excludes other divisions | Other division teams absent from output |
| `--team` filter shows only matching team's games | Non-matching teams absent |
| `--field` filter shows only matching field's games | Non-matching fields absent |
| Unknown `--division` exits 1 | `stderr().contains("not found")` |
| Unknown `--field` exits 1 | `stderr().contains("not found")` |
| Unknown `--team` exits 1 | `stderr().contains("not found")` |
| Overridden game shows `*` suffix | `stdout().contains("*")` |
| `--team-schedule` in DRAFT shows stats | `stdout().contains("HOME/AWAY BALANCE")` |
| Full view in DRAFT hides stats | `assertFalse(stdout().contains("HOME/AWAY BALANCE"))` |

**`ScheduleCommandTest` — export:**

| Test | Assertion |
|---|---|
| Export exits 1 in NONE state | `stderr().contains("No schedule generated yet")` |
| Export in DRAFT produces valid JSON array | `stdout().startsWith("[")` && `stdout().endsWith("]")` |
| Full schedule export contains all required fields | `date`, `start_time`, `field_name`, `home_team`, `away_team`, `division_name`, `status` present |
| `status` field is `"draft"` in DRAFT state | `stdout().contains("\"draft\"")` |
| `status` field is `"finalized"` after finalize | `stdout().contains("\"finalized\"")` |
| `--team-schedule` in FINALIZED exits 1 | `stderr().contains("not available")` |
| Game count in stderr | `stderr().contains("Exported")` |

**`LeagueStoreTest` — migration:**

| Test | Assertion |
|---|---|
| v1 file loads as v4 | Loaded league has `version == 4`; `fields` is `List.of()` |
| v2 file loads as v4 | Loaded league has `version == 4`; existing fields preserved (no blocks) |
| v3 file with field blocks loads as v4; blocks cleared | `field.blocks().isEmpty()` for all fields; `field.dateOverrides().isEmpty()` |
| Migration prints warning to stderr | `stderr().contains("Field availability windows")` |
| Warning not printed on second load | Load again; `stderr()` is empty |
| v4 file loads without migration | No stderr output; version unchanged |

### Failure Mode Summary

| Failure | Behavior |
|---|---|
| `store.load()` throws `IOException` | All commands catch and return exit 2 with `"Failed to access league data: <message>"` to stderr |
| `store.save()` fails after `Files.move()` | `IOException` propagates to command; exit 2. The `.tmp` file is left on disk but the original file is untouched (ATOMIC_MOVE). No data loss. |
| `league.json` missing on first run | `store.load()` creates `League.empty()`, saves it, returns it. All commands work against a freshly initialized league. |
| Conflict check in `OverrideCmd` — game list iteration throws | `IndexOutOfBoundsException` not possible because the list is already loaded and the index is validated. |
| `mapper.writeValueAsString()` in `ExportCmd` fails | Wrapped `IOException` is caught; exit 2 with `"Failed to access league data: ..."`. Unlikely for in-memory serialization. |

### Deployment / Rollback

- All changes are confined to: `ScheduleCommand.java`, `ScheduleGameCommand.java`, `Schedule.java`, `ScheduledGame.java`, `ScheduleState.java`, `ScheduleStatus.java`, `LeagueStore.java`, and the new model records.
- No new packages or external dependencies are introduced.
- Migration is one-way. Rolling back the binary after a v3→v4 migration will leave a v4 `league.json` that the old binary cannot read (the old binary expects no `teamSchedule` key and no `LeagueConfig` block). If rollback is needed, restore the `league.json` backup (the old binary writes `league.json.bak` on load — confirm this is true or adjust the rollback plan).
- FINALIZED schedules written by v4 can be inspected with `planr schedule view` or `planr schedule export` using any future binary that understands v4 schema.

---

## Out of Scope / Future Work

- **Reverting a Finalized schedule** — Finalization is one-way per PRD. Any revert mechanism requires a new command and a new state (REVERTED or ARCHIVED).
- **Bulk editing a Finalized schedule** — Only individual game overrides are permitted. A bulk re-assign would require a new lifecycle state.
- **Rainout/reschedule workflow** — Out of scope per PRD. The override command provides a manual workaround.
- **Exporting to formats other than JSON** — CSV, PDF, iCal are explicitly out of scope per PRD.
- **`--output <path>` flag for export** — The shell redirect idiom (`>`) is sufficient for the CLI prototype. Add when building the web API layer.
- **Per-game status in team schedule export** — The team schedule export intentionally omits `status`; it is a matchup-only artifact.
- **Conflict detection in Phase 2 re-run after home/away edit** — The re-run of Phase 2 re-assigns all games from scratch, so the solver enforces conflict constraints natively. No incremental conflict check is needed.

---

## Implementation Plan

Tasks are ordered by dependency. Items within the same milestone can be worked in parallel.

### Milestone 1 — Model Layer

**Task 1.1 — `ScheduleState` enum**
- File: `src/main/java/org/leagueplan/planr/model/ScheduleState.java`
- Values: `NONE`, `TEAM_SCHEDULE`, `DRAFT`, `FINALIZED`
- Static factory: `ScheduleState.of(League league)`:
  - If `league.schedule() != null`: return `FINALIZED` if `schedule.status() == FINALIZED`, else `DRAFT`
  - If `league.teamSchedule() != null`: return `TEAM_SCHEDULE`
  - Return `NONE`

**Task 1.2 — `ScheduleStatus` enum**
- File: `src/main/java/org/leagueplan/planr/model/ScheduleStatus.java`
- Values: `DRAFT`, `FINALIZED`

**Task 1.3 — `Schedule` record**
- File: `src/main/java/org/leagueplan/planr/model/Schedule.java`
- Fields: `ScheduleStatus status`, `LocalDate seasonStart`, `LocalDate seasonEnd`, `List<ScheduledGame> games`
- Methods: `withStatus(ScheduleStatus)`, `withGameReplaced(int zeroBasedIndex, ScheduledGame)`

**Task 1.4 — `ScheduledGame` record**
- File: `src/main/java/org/leagueplan/planr/model/ScheduledGame.java`
- Fields: as specified in Data Model section
- Method: `withOverride(LocalDate, LocalTime, UUID, String, UUID, String, UUID, String)` — null = retain previous value; always sets `overridden = true`

**Task 1.5 — `TeamGame.withSwappedHomeAway()`**
- File: `src/main/java/org/leagueplan/planr/model/TeamGame.java`
- Adds `withSwappedHomeAway()` method that swaps all four home/away fields

**Task 1.6 — `League` schedule mutations**
- File: `src/main/java/org/leagueplan/planr/model/League.java`
- Add `withSchedule(Schedule)`, `withTeamSchedule(TeamSchedule)`, `withTeamScheduleCleared()` (sets both `teamSchedule` and `schedule` to null)

---

### Milestone 2 — Store Layer (migration chain)

**Task 2.1 — `LeagueStore.load()` migration chain**
- File: `src/main/java/org/leagueplan/planr/store/LeagueStore.java`
- v1→v2: `new League(2, null, league.divisions(), List.of(), null, null)` + save
- v2→v3: `new League(3, null, league.divisions(), league.fields(), null, null)` + save
- v3 (or any pre-v4)→v4: clear field blocks/overrides, add `LeagueConfig.empty()`, preserve `schedule`, null `teamSchedule`, advance to version 4, save, print stderr warning

---

### Milestone 3 — Command Layer

**Task 3.1 — `ScheduleCommand.FinalizeCmd`**
- Precondition: DRAFT state; else exit 1
- Print warning; read stdin; if not "yes", exit 0 with "Finalization cancelled."
- Build `schedule.withStatus(FINALIZED)`; save; print confirmation; exit 0

**Task 3.2 — `ScheduleGameCommand.EditHomeAwayCmd`**
- Preconditions: not NONE, not FINALIZED
- Validate game number against `teamSchedule.findGame()`
- Validate team membership; detect no-op case
- Call `game.withSwappedHomeAway()`; update `TeamSchedule`; save; print result

**Task 3.3 — `ScheduleGameCommand.OverrideCmd`**
- Precondition: FINALIZED state
- At least one option required
- Parse and validate each optional field independently
- Apply `game.withOverride(...)`; replace in schedule
- Run non-blocking conflict check; emit warning to stderr if needed
- Save; print `"Game #N updated."`

**Task 3.4 — `ScheduleCommand.ViewCmd`**
- State guard: NONE → exit 1
- Route on `--team-schedule` flag or TEAM_SCHEDULE state
- Team schedule path: print status header, matchup table, stats blocks
- Full schedule path: validate filters, apply filters, compute labels (with `*`), render table

**Task 3.5 — `ScheduleCommand.ExportCmd`**
- State guard: NONE → exit 1
- Route on `--team-schedule` flag or TEAM_SCHEDULE state
- Team schedule path: block if FINALIZED; map to `ExportTeamGame`; write JSON to stdout; count to stderr
- Full schedule path: map to `ExportGame`; write JSON to stdout; count to stderr
- Use a local `ObjectMapper` (separate from `LeagueStore`'s)

---

### Milestone 4 — Tests and Verification

**Task 4.1 — `LeagueStoreTest` migration tests**
- Write v1/v2/v3 fixture JSON files in test resources
- Assert post-migration `version == 4`, field properties cleared, warning emitted
- Assert no warning on second load

**Task 4.2 — `ScheduleCommandTest` (lifecycle, view, export)**
- Cover all cases in the Testing Strategy section above

**Task 4.3 — Full test suite**
```bash
gradle test
```

**Task 4.4 — End-to-end smoke test**
```bash
gradle installDist

# Setup
./build/install/planr/bin/planr config set --sunrise 09:00 --sunset 18:00 \
  --start 2026-06-01 --end 2026-08-31
./build/install/planr/bin/planr division add Majors --duration 90 --target 6
./build/install/planr/bin/planr team add Majors "Blue Jays"
./build/install/planr/bin/planr team add Majors "Cardinals"
./build/install/planr/bin/planr team add Majors "Red Sox"
./build/install/planr/bin/planr field add "Riverside Park"

# Phase 1
./build/install/planr/bin/planr schedule generate
./build/install/planr/bin/planr schedule view               # matchup table + stats
./build/install/planr/bin/planr schedule export --team-schedule > /tmp/team.json

# Edit home/away
./build/install/planr/bin/planr schedule game edit 1 --home "Cardinals"

# Phase 2
echo yes | ./build/install/planr/bin/planr schedule assign
./build/install/planr/bin/planr schedule status
./build/install/planr/bin/planr schedule view
./build/install/planr/bin/planr schedule view --division Majors
./build/install/planr/bin/planr schedule export > /tmp/draft.json

# Finalize
echo yes | ./build/install/planr/bin/planr schedule finalize
./build/install/planr/bin/planr schedule status             # FINALIZED
./build/install/planr/bin/planr schedule view               # full table

# Override
./build/install/planr/bin/planr schedule game override 1 --date 2026-07-04
./build/install/planr/bin/planr schedule view               # game 1 shows 1*
./build/install/planr/bin/planr schedule export > /tmp/final.json

# Verify export files
cat /tmp/team.json    # array of {game_number, home_team, away_team, division_name}
cat /tmp/draft.json   # array with status "draft"
cat /tmp/final.json   # array with status "finalized"
```
