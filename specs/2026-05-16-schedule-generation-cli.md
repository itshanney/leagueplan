# Tech Spec: Schedule Generation — `planr` CLI

**Date:** 2026-05-16
**Status:** Ready for Implementation
**Scope:** Schedule Generation, Lifecycle, Viewing, and Export acceptance criteria from `features/2026-05-15-league-planner-core-scheduling.md`
**Phase:** CLI prototype (follows Field Management slice)

---

## Overview

This slice adds schedule generation to the `planr` CLI. It introduces a `planr schedule` command group covering: generate, finalize, view, export, and individual game override. The scheduling core delegates all constraint satisfaction and optimization to **Google OR-Tools CP-SAT** — a production-grade, open-source constraint programming solver that handles the NP-hard bin-packing problem of placing fixtures into field time slots without manual heuristics.

The schedule lifecycle is a simple two-state machine (Draft → Finalized). Draft schedules can be regenerated freely; finalized schedules are locked except for individual game overrides. The `League` record gains a nullable `Schedule` field, and the JSON schema advances to version 3. No existing commands change. The solver is given a **60-second wall-clock budget**: it finds a feasible solution as early as possible, then continues optimizing toward an ideal schedule (minimizing game clustering across the season) until the budget is exhausted or optimality is proven.

---

## Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  planr (CLI entry point)                                            │
│  PlanrApp — root @Command; wires subcommands, injects store         │
└──────────────────────┬──────────────────────────────────────────────┘
                       │ dispatches to
    ┌──────────────────┼───────────────────────┐
    ▼                  ▼                        ▼
┌──────────┐    ┌──────────┐    ┌───────────────────────────────────┐
│ Division │    │ Field    │    │ ScheduleCommand                   │
│ /Team    │    │ /Window  │    │ generate / finalize / view /      │
│(unchanged│    │(unchanged│    │ export / game override             │
└──────────┘    └──────────┘    └────────────────────┬──────────────┘
                                                     │
                          ┌──────────────────────────┤
                          ▼                          ▼
              ┌────────────────────┐   ┌────────────────────────────┐
              │ SchedulerService   │   │ LeagueStore                │
              │ Orchestrates the   │   │ (extended: schedule field,  │
              │ full solve cycle:  │   │  v2→v3 migration)          │
              │ fixture gen →      │   └────────────┬───────────────┘
              │ slot enum →        │                │ read / atomic write
              │ OR-Tools solve →   │                ▼
              │ result assembly    │   ┌────────────────────────────┐
              └─────────┬──────────┘   │  ~/.planr/league.json      │
                        │              └────────────────────────────┘
                        ▼
              ┌────────────────────┐
              │ OR-Tools CP-SAT    │
              │ Solver             │
              │ (bundled native    │
              │  via Maven JAR)    │
              └────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| `ScheduleCommand` | Picocli command group; validates preconditions; delegates generation to `SchedulerService`; delegates persistence to `LeagueStore`; handles view/export/override commands |
| `SchedulerService` | Encapsulates the full schedule computation: fixture generation (circle method), slot enumeration, CP-SAT model construction, solve, and result assembly |
| `OR-Tools CP-SAT Solver` | Google's constraint programming solver; receives boolean decision variables and constraint clauses; returns an assignment of fixtures to slots |
| `LeagueStore` | Extended with schedule read/write; v2→v3 migration (add null `schedule` field) |
| `Schedule` (record) | Immutable value: status, season dates, list of `ScheduledGame` records |
| `ScheduledGame` (record) | Immutable value: one scheduled game (or override) with all display fields denormalized |

---

## Data Model

### New In-Memory Records

```
Schedule
  ├── status: ScheduleStatus       (DRAFT | FINALIZED)
  ├── seasonStart: LocalDate
  ├── seasonEnd: LocalDate
  └── List<ScheduledGame>
        ├── id: UUID               (stable game identity; survives overrides)
        ├── date: LocalDate
        ├── startTime: LocalTime   (HH:mm)
        ├── fieldId: UUID
        ├── fieldName: String      (denormalized at generation time)
        ├── homeTeamId: UUID
        ├── homeTeamName: String   (denormalized)
        ├── awayTeamId: UUID
        ├── awayTeamName: String   (denormalized)
        ├── divisionId: UUID
        ├── divisionName: String   (denormalized)
        └── overridden: boolean    (true if modified post-finalization)
```

**Denormalization rationale:** Names on `ScheduledGame` are copied at generation time. This means the JSON export and view commands always display the names that were current when the game was scheduled (or overridden), which is the correct behavior — renaming a team after finalization should not silently alter the historical schedule record.

```
League (version 3)
  ├── List<Division>   (unchanged)
  ├── List<Field>      (unchanged)
  └── Schedule         (nullable; null = no schedule generated yet)
```

### JSON Shape — New Additions

```json
{
  "version": 3,
  "divisions": [ ... ],
  "fields":    [ ... ],
  "schedule": {
    "status": "DRAFT",
    "seasonStart": "2026-06-01",
    "seasonEnd":   "2026-08-31",
    "games": [
      {
        "id":           "a1b2c3d4-...",
        "date":         "2026-06-07",
        "startTime":    "09:00",
        "fieldId":      "f1f2f3f4-...",
        "fieldName":    "Riverside Park",
        "homeTeamId":   "t1t2t3t4-...",
        "homeTeamName": "Blue Jays",
        "awayTeamId":   "t5t6t7t8-...",
        "awayTeamName": "Cardinals",
        "divisionId":   "d1d2d3d4-...",
        "divisionName": "Majors",
        "overridden":   false
      }
    ]
  }
}
```

`schedule` is `null` (JSON `null`) when no schedule has been generated. `ScheduleStatus` serializes as the enum name string.

### Schema Migration (v2 → v3)

`LeagueStore.load()` adds a migration guard after the existing v1→v2 guard:

```
if (league.version() == 2) {
    league = new League(3, league.divisions(), league.fields(), null);
    save(league);
}
```

Silent, forward-only, idempotent — identical pattern to the existing v1→v2 migration.

---

## OR-Tools Integration

### Dependency

Add to `build.gradle`:

```groovy
implementation 'com.google.ortools:ortools-java:9.10.4067'
```

The `ortools-java` artifact on Maven Central bundles native shared libraries for Linux (x86-64, ARM64), macOS (x86-64, ARM64), and Windows (x86-64) inside the JAR. No separate native installation is required. The JAR is large (~50 MB) but this is a developer/prototype tool and acceptable.

**Assumption (flag for confirmation):** The build environment has internet access to Maven Central for the initial dependency download. The artifact is then cached in Gradle's local cache like any other dependency.

### CP-SAT Problem Formulation

`SchedulerService` translates the league configuration into a CP-SAT constraint satisfaction problem. The solver finds any feasible assignment (there is no optimization objective — feasibility alone satisfies the spec).

#### Step 1 — Fixture Generation (outside the solver)

Use the **circle method** (standard round-robin algorithm) to generate all fixtures with home/away assignments pre-determined. For a division with `n` teams:

- If `n` is odd, add a "bye" placeholder team; remove all games involving the bye after generation.
- Fix `team[0]` at the top of the circle. In each of the `n-1` rounds, rotate the remaining `n-1` teams clockwise.
- In each round, pair `team[0]` with `team[n-1]` (rightmost) and pair adjacent teams in the remaining columns.
- Home/away assignment per round: `team[0]` is **home** in even-numbered rounds, **away** in odd-numbered rounds. For the column pairs in each round, the team in the upper column position is home in odd rounds, away in even rounds. This ensures no team is home for all of its first-half games.
- Each fixture appears exactly twice in the output list: once with Team A home, once with Team B home — capturing the "exactly twice, once home, once away" requirement from the spec.

Output: `List<Fixture>` where `Fixture` holds `(homeTeamId, awayTeamId, divisionId, gameDurationMinutes)`.

#### Step 2 — Slot Enumeration (outside the solver)

Enumerate every valid `Slot` for each division: a `Slot` is a `(date, field, startTime)` triple satisfying:
- `date` is within `[seasonStart, seasonEnd]`
- `date.getDayOfWeek()` matches a window's `dayOfWeek` on the field
- The window allows the division (either `divisionId == null` or `window.divisionId == this division`)
- `startTime >= window.startTime`
- `startTime + gameDurationMinutes <= window.endTime` (the game fits entirely within the window)
- Time grid: slot start times are quantized to 15-minute increments beginning at `window.startTime`

Output: `Map<UUID /* divisionId */, List<Slot>>` — slots per division.

#### Step 3 — CP-SAT Model

```
Variables:
  For each (fixture f, slot s) where s is valid for f's division:
    BoolVar assigned[f][s]   ← 1 if fixture f is placed in slot s

Constraints:
  C1 — Each fixture assigned exactly once:
    For each fixture f:
      sum(assigned[f][s] for all s) == 1

  C2 — No two games on the same field overlap (including 15-min buffer):
    For each field F, for each date D:
      Collect all (fixture, slot) pairs where slot.field == F and slot.date == D.
      Sort by startTime.
      For each adjacent pair (a, b) where b.startTime < a.startTime + a.gameDuration + 15:
        assigned[f_a][s_a] + assigned[f_b][s_b] <= 1
      (This is the "no two games in the same time window on a field" constraint.
       Implemented as pairwise mutual exclusion on all pairs whose slots overlap.)

  C3 — No team plays more than once on the same calendar day:
    For each team T, for each date D:
      sum(assigned[f][s] for all (f,s) where T ∈ {f.home, f.away} and s.date == D) <= 1
```

**Optimization objective — minimize game clustering:** Rather than stopping at the first feasible solution, CP-SAT optimizes a soft objective that spreads games as evenly as possible across the season. The objective minimizes the maximum number of games scheduled in any single calendar week across all teams. Formally:

```
For each team T, for each ISO week W in [seasonStart, seasonEnd]:
  weekLoad[T][W] = sum(assigned[f][s] for all (f,s) where T ∈ {f.home, f.away} and s.date in week W)

Minimize: max(weekLoad[T][W] for all T, W)
```

This objective is expressed as a CP-SAT `Minimize(maxWeekLoad)` call where `maxWeekLoad` is an `IntVar` bounded by `[0, totalFixtures]` with constraints linking it to the per-team-per-week sums. The solver finds a feasible solution quickly (within seconds for typical leagues) and then tightens the objective bound for the remainder of the 60-second budget, returning the best solution found when time expires.

#### Step 4 — Solve

```java
CpSolver solver = new CpSolver();
solver.getParameters().setMaxTimeInSeconds(60);   // full 60s budget: find feasible, then optimize
CpSolverStatus status = solver.solve(model);
```

If `status == FEASIBLE || status == OPTIMAL` → extract the assignment and proceed. `OPTIMAL` means the solver proved no better distribution is possible within the model; `FEASIBLE` means the budget expired but the best solution found is returned.
If `status == INFEASIBLE` → report error (see Error Reporting below).
If `status == UNKNOWN` (budget exhausted before finding any feasible solution) → report error.

#### Step 5 — Result Assembly

Walk the solved variables. For each `assigned[f][s] == 1`:
- Build a `ScheduledGame` record with denormalized names, `overridden = false`.

Return the full `List<ScheduledGame>`, wrap in a `Schedule(DRAFT, seasonStart, seasonEnd, games)`.

#### Error Reporting

When the solver cannot produce a valid schedule, `ScheduleCommand` prints a specific diagnostic to stderr. `SchedulerService` returns a typed failure result rather than throwing — the command decides how to format it.

Two failure modes:

| Solver Status | Diagnostic to stderr |
|---|---|
| `INFEASIBLE` | `Error: Cannot generate a valid schedule. <details>` |
| `UNKNOWN` (timeout) | `Error: Schedule generation timed out after 60 seconds without finding a valid schedule. Reduce the number of teams, extend the season, or add more field availability.` |

For `INFEASIBLE`, the diagnostic includes a per-division summary of how many games need to be scheduled vs. how many available slots exist, e.g.:

```
Error: Cannot generate a valid schedule. Insufficient field availability:
  AAA: 12 games required, 8 slots available across the season window.
  Majors: 20 games required, 24 slots available (OK).
Add more field availability windows or extend the season date range.
```

This diagnostic is computed **before** invoking the solver by comparing `fixtures.size()` against `slots.size()` per division. If any division has fewer slots than fixtures, the solver call is skipped entirely and the specific division(s) are named.

---

## CLI Command Contracts

All commands: `stdout` on success, `stderr` on error. Exit codes: `0` = success, `1` = validation/constraint error, `2` = I/O error.

### `planr schedule generate --start <YYYY-MM-DD> --end <YYYY-MM-DD>`

| | |
|---|---|
| Preconditions | At least one division with ≥ 2 teams exists; at least one field with ≥ 1 availability window exists; if a FINALIZED schedule exists, block with error |
| Behavior | If a DRAFT schedule exists, it is silently discarded and replaced. Runs `SchedulerService`. On success, saves the new DRAFT schedule and prints a summary. On failure, prints the diagnostic and exits 1. |
| On success (OPTIMAL) | `Draft schedule generated: 48 games across 3 divisions (optimal distribution). Run 'planr schedule view' to review.` |
| On success (FEASIBLE) | `Draft schedule generated: 48 games across 3 divisions (good distribution — optimizer ran for 60s). Run 'planr schedule view' to review.` |
| On error (finalized exists) | `Error: A finalized schedule exists and cannot be regenerated. Use 'planr schedule game override' to adjust individual games.` Exit 1 |
| On error (precondition not met) | `Error: Schedule generation requires at least one division with 2 or more teams and at least one field with an availability window.` Exit 1 |
| On error (orphaned windows) | `Error: Field "Riverside Park" has windows referencing deleted divisions. Fix or remove them before generating. Run 'planr field window list "Riverside Park"' to review.` Exit 1 |

**`--start` / `--end` parsing:** ISO 8601 date format (`YYYY-MM-DD`). Reject if `end` is before `start`. Reject if `end` equals `start` (zero-length season).

---

### `planr schedule status`

| | |
|---|---|
| Behavior | Prints current schedule state: status, season dates, total game count, games per division |
| On success (schedule exists) | Multi-line summary (see format below) |
| On success (no schedule) | `No schedule generated yet. Run 'planr schedule generate' to create one.` |

```
Status:        DRAFT
Season:        2026-06-01 to 2026-08-31
Total games:   48
  Majors:      20 games
  AAA:         12 games
  Coast:       10 games
  T-Ball:       6 games
```

---

### `planr schedule finalize`

| | |
|---|---|
| Preconditions | A DRAFT schedule must exist |
| Behavior | Prints a confirmation warning and requires the user to type `yes` to proceed. On confirmation, sets status to FINALIZED and saves. |
| Confirmation prompt | `Warning: Finalizing the schedule is irreversible. The schedule will be locked and cannot be regenerated. Type 'yes' to confirm: ` |
| On confirmation | `Schedule finalized. 48 games locked. Use 'planr schedule game override' for individual adjustments.` |
| On abort (anything other than `yes`) | `Finalization cancelled.` Exit 0 |
| On error (no draft) | `Error: No draft schedule to finalize.` Exit 1 |

**Assumption (flag for confirmation):** Reading `yes` from stdin is acceptable in a CLI prototype. The web layer will use a modal confirmation instead.

---

### `planr schedule view [--division <name>] [--team <name>] [--field <name>]`

| | |
|---|---|
| Preconditions | A schedule (any status) must exist |
| Behavior | Lists all scheduled games, optionally filtered. Filters are additive (AND). Prints status header followed by tabular output. |
| On success | Header line + table (see format below) |
| On success (no matching games) | Header + `No games match the specified filter.` |
| On error (no schedule) | `Error: No schedule generated yet. Run 'planr schedule generate' to create one.` Exit 1 |
| On error (filter not found) | `Error: Division "XYZ" not found.` Exit 1 (same for team, field) |

```
Schedule status: DRAFT | Season: 2026-06-01 to 2026-08-31

#    DATE          START    FIELD             HOME               AWAY               DIVISION
--   ----------    -----    ---------------   ----------------   ----------------   --------
1    2026-06-07    09:00    Riverside Park    Blue Jays          Cardinals          Majors
2    2026-06-07    11:15    Eastside Field    Red Sox            Yankees            AAA
...
```

`#` is the 1-based display index (used by `game override`). Games are sorted by date, then start time, then field name. The `[override]` tag is appended in the `#` column for overridden games: `1*`.

---

### `planr schedule export`

| | |
|---|---|
| Preconditions | A schedule (any status) must exist |
| Behavior | Writes JSON to stdout. The caller may redirect to a file. |
| On success | Prints a JSON array to stdout, one object per game. Prints `Exported N games.` to stderr (so stdout remains clean for piping). |
| On error (no schedule) | `Error: No schedule generated yet.` to stderr. Exit 1 |

JSON array element shape per the PRD acceptance criteria:

```json
{
  "date":          "2026-06-07",
  "start_time":    "09:00",
  "field_name":    "Riverside Park",
  "home_team":     "Blue Jays",
  "away_team":     "Cardinals",
  "division_name": "Majors",
  "status":        "draft"
}
```

`status` is lowercase (`"draft"` or `"finalized"`). `date` and `start_time` use ISO 8601 format.

---

### `planr schedule game override <game-number> [options]`

| | |
|---|---|
| Preconditions | A FINALIZED schedule must exist |
| Options | `--date <YYYY-MM-DD>`, `--start <HH:mm>`, `--field <name>`, `--home <team-name>`, `--away <team-name>` |
| Behavior | Replaces the identified game's fields with the provided values. Marks `overridden = true`. Does NOT re-validate against constraints. If the new slot conflicts with another game on the same field + date, prints a non-blocking warning (does not prevent the save). |
| On success | `Game #3 updated.` (plus optional warning if field conflict detected) |
| On error (no finalized schedule) | `Error: 'game override' requires a finalized schedule. Run 'planr schedule finalize' first.` Exit 1 |
| On error (invalid game number) | `Error: Game #99 not found (1–48 are valid).` Exit 1 |
| On error (no options provided) | `Error: At least one override option must be provided.` Exit 1 |
| On error (field not found) | `Error: Field "XYZ" not found.` Exit 1 |
| On error (team not found) | `Error: Team "XYZ" not found. Specify the team name as it appears in 'planr schedule view'.` Exit 1 |

**Conflict warning format (non-blocking):**
```
Warning: Game #3 now conflicts with game #7 at Riverside Park on 2026-07-12
         (overlapping times including the 15-minute buffer). Game #3 saved anyway.
```

Conflict detection: scan all other games on the same `date` and `field`. A conflict exists if `|s1 - s2| < max(duration1, duration2) + 15` minutes (accounting for buffer on both sides).

**Team resolution for override:** Team names are matched against `homeTeamName` and `awayTeamName` denormalized fields across all games in the schedule, then against `team.name` in `league.divisions`. Case-insensitive. If the same name appears in multiple divisions, `ScheduleCommand` uses the team in the same division as the game being overridden.

---

## Project Structure Changes

```
src/main/java/org/leagueplan/planr/
├── PlanrApp.java                     # add ScheduleCommand to subcommands list
├── command/
│   ├── DivisionCommand.java          (unchanged)
│   ├── TeamCommand.java              (unchanged)
│   ├── FieldCommand.java             (unchanged)
│   ├── FieldWindowCommand.java       (unchanged)
│   └── ScheduleCommand.java          # NEW: generate / finalize / status /
│                                     #      view / export / game override
├── model/
│   ├── League.java                   # extend: add Schedule field + helpers
│   ├── Division.java                 (unchanged)
│   ├── Team.java                     (unchanged)
│   ├── Field.java                    (unchanged)
│   ├── AvailabilityWindow.java       (unchanged)
│   ├── Schedule.java                 # NEW: record status/seasonStart/seasonEnd/games
│   ├── ScheduledGame.java            # NEW: record with all display fields
│   └── ScheduleStatus.java          # NEW: enum DRAFT | FINALIZED
├── scheduler/
│   ├── SchedulerService.java         # NEW: orchestrates the full solve cycle
│   ├── Fixture.java                  # NEW: record homeTeamId/awayTeamId/divisionId/duration
│   ├── Slot.java                     # NEW: record date/field/startTime
│   └── ScheduleResult.java          # NEW: sealed interface — Success(games) | Failure(msg)
└── store/
    └── LeagueStore.java              # extend: schedule R/W; v2→v3 migration
```

**`scheduler/` package** is an internal domain package. `SchedulerService` is instantiated by `ScheduleCommand` and has no picocli annotations. `Fixture`, `Slot`, and `ScheduleResult` are plain records used only within this package and by `ScheduleCommand`. They are not persisted.

---

## Build Configuration Changes

One new dependency in `build.gradle`:

```groovy
implementation 'com.google.ortools:ortools-java:9.10.4067'
```

The `ortools-java` JAR contains native libraries for all supported platforms. The existing `jar` task configuration (`from { configurations.runtimeClasspath.collect { ... zipTree(it) } }`) will bundle native libraries into the fat JAR. The `EXCLUDE` duplicates strategy already handles duplicates across the runtimeClasspath.

**GraalVM native image:** OR-Tools native libraries are pre-compiled C++ shared objects; they are not compatible with GraalVM native image compilation. The `picocli-codegen` annotation processor remains for future use, but a native image build cannot be targeted while OR-Tools is on the classpath. This is acceptable for the CLI prototype phase.

---

## Critical Path Walkthroughs

### 1. Generate a Draft Schedule

```
User: planr schedule generate --start 2026-06-01 --end 2026-08-31

1. Picocli parses: seasonStart=2026-06-01, seasonEnd=2026-08-31
2. ScheduleCommand.GenerateCmd.call():
   a. Validate: seasonEnd is after seasonStart ✓
3. LeagueStore.load() → League (v3; migration runs if needed)
4. Precondition checks:
   a. At least one division with ≥ 2 teams ✓
   b. At least one field with ≥ 1 window ✓
   c. No orphaned division references in any window ✓
   d. schedule.status != FINALIZED ✓  (null schedule is OK)
5. SchedulerService.generate(league, seasonStart, seasonEnd):
   a. Fixture generation (circle method):
      - For each division with N teams, produce N*(N-1) fixtures
      - Home/away interleaved per circle rotation
      - Result: List<Fixture> (e.g., 90 fixtures for a 10-team division)
   b. Slot enumeration per division:
      - Walk each calendar date in [seasonStart, seasonEnd]
      - For each date, find all field windows matching that day-of-week
      - Filter windows by division restriction
      - Quantize start times into 15-min grid aligned to window.startTime
      - Keep only slots where startTime + gameDuration <= window.endTime
      - Result: Map<divisionId, List<Slot>>
   c. Pre-solve feasibility check:
      - For each division: if fixtures.size() > slots.size(), return Failure with diagnostic
   d. OR-Tools model construction:
      - Create CpModel
      - Create BoolVar[fixture][slot] for each valid (fixture, slot) pair
      - Add C1: each fixture assigned exactly once
      - Add C2: pairwise field conflict constraints (same field, overlapping window)
      - Add C3: team double-booking constraints (same team, same date)
   e. Solve (28-second wall-clock limit):
      - CpSolverStatus status = solver.solve(model)
      - On FEASIBLE: time budget expired; return best solution found (acceptable)
      - On OPTIMAL: solver proved optimal distribution; return solution
      - On INFEASIBLE: return Failure("Infeasible")
      - On UNKNOWN: return Failure("Timeout — no feasible solution found within 60s")
   f. Result assembly:
      - For each assigned[f][s] == 1: build ScheduledGame with denormalized names
      - Sort by date, startTime, fieldName
      - Return ScheduleResult.Success(games)
6. ScheduleCommand receives Success:
   a. Build Schedule(DRAFT, seasonStart, seasonEnd, games)
   b. league.withSchedule(schedule) → new League
   c. LeagueStore.save(league) — atomic write
7. Print: Draft schedule generated: 90 games across 1 division.
          Run 'planr schedule view' to review.
8. Exit 0
```

**Error path (insufficient slots):**
```
Step 5c fails: AAA division has 12 fixtures but only 8 available slots.
Return Failure with per-division diagnostic.
ScheduleCommand prints diagnostic to stderr. Exit 1. No file write.
```

---

### 2. Finalize the Schedule

```
User: planr schedule finalize

1. LeagueStore.load() → League
2. Validate: league.schedule() != null and status == DRAFT ✓
3. Print warning:
   "Warning: Finalizing the schedule is irreversible. The schedule will be
    locked and cannot be regenerated. Type 'yes' to confirm: "
4. Read line from System.in
5. If input != "yes": print "Finalization cancelled." Exit 0
6. Build Schedule(FINALIZED, seasonStart, seasonEnd, games)
7. league.withSchedule(finalizedSchedule) → new League
8. LeagueStore.save(league) — atomic write
9. Print: Schedule finalized. 90 games locked. Use 'planr schedule game override'
          for individual adjustments.
10. Exit 0
```

---

### 3. Override a Game on a Finalized Schedule

```
User: planr schedule game override 3 --date 2026-07-19 --field "Eastside Field"

1. LeagueStore.load() → League
2. Validate: schedule exists and status == FINALIZED ✓
3. Validate: game number 3 is valid (1-based index into schedule.games()) ✓
4. Resolve "--field Eastside Field": league.findField("Eastside Field") → found ✓
5. Build updated ScheduledGame:
   - Copy all existing fields from game #3
   - Replace: date=2026-07-19, fieldId=<eastside UUID>, fieldName="Eastside Field"
   - Set: overridden=true
6. Conflict detection:
   - Filter games where game.date == 2026-07-19 and game.fieldId == eastside UUID
   - Exclude game #3 itself
   - For each candidate: check if startTime ranges overlap (including 15-min buffer)
   - If conflict found: record conflicting game number for warning
7. Build new schedule with game #3 replaced
8. LeagueStore.save(league) — atomic write
9. If conflict detected:
   Print: Warning: Game #3 now conflicts with game #7 at Eastside Field on 2026-07-19
          (overlapping times including the 15-minute buffer). Game #3 saved anyway.
10. Print: Game #3 updated.
11. Exit 0
```

---

### 4. Export the Schedule

```
User: planr schedule export > my-schedule.json

1. LeagueStore.load() → League
2. Validate: schedule exists ✓
3. Map each ScheduledGame to the export shape (snake_case fields, lowercase status)
4. Serialize to JSON array (pretty-printed with 2-space indent)
5. Print JSON to stdout
6. Print "Exported 90 games." to stderr
7. Exit 0
```

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| Scheduling algorithm | Custom greedy, backtracking with heuristics, ILP (Gurobi/CPLEX), CP-SAT | **Google OR-Tools CP-SAT** | Battle-tested open-source constraint solver; handles the combinatorial problem correctly without hand-rolled heuristics; Maven Central artifact requires no separate install | Large JAR dependency (~50 MB); incompatible with GraalVM native image — accepted for CLI prototype phase |
| Solver objective | Feasibility only, minimize max games/week, minimize variance in home games per week | **Minimize max games per team per week** | A 60-second budget is long enough for CP-SAT to find feasibility quickly and then optimize. "Minimize max weekly game load per team" is the simplest objective that meaningfully spreads games across the season without requiring complex fairness modeling. It naturally prevents bunching fixtures at the start of the season (the typical first-feasible artifact). | The objective adds an `IntVar` for `maxWeekLoad` and per-team-per-week sum constraints. For leagues at the acceptance criterion scale this is negligible additional model size. If the optimizer cannot prove optimality in 60 seconds it returns the best solution found — still a valid, conflict-free schedule. |
| Fixture generation | CP-SAT decision variable for home/away, pre-computed circle method | **Circle method (pre-computed)** | Round-robin home/away is a solved, deterministic problem. Folding it into the CP-SAT model doubles the variable count and adds no benefit. | None — the circle method is provably correct for balanced home/away. |
| Time grid quantization | Continuous time (any minute), 15-min grid, 30-min grid | **15-minute grid** | Matches the buffer size; avoids fractional slot overlap edge cases; reduces the variable count vs. continuous time | Slots start only at 15-min boundaries within a window. A window starting at 09:00 yields slots at 09:00, 09:15, 09:30, etc. This is expected and desirable behavior. |
| Schedule storage location | Separate file `~/.planr/schedule.json`, embedded in `league.json` | **Embedded in `league.json`** | Maintains the single-file invariant already established; eliminates partial-state scenarios where entity config and schedule can be out of sync | `league.json` grows significantly for large leagues (~100 teams, 900 games). Still well under 1 MB; acceptable for a local CLI. |
| Names on ScheduledGame | Resolve UUIDs at view time (normalized), denormalize at generation | **Denormalize at generation** | Renaming a team post-finalization should not silently alter the historical schedule. The export spec also requires names, not IDs. | If a team is renamed, the schedule shows the old name until the organizer uses `game override` on affected games. This is the correct semantics — a finalized schedule is a historical record. |
| Game identification for override | UUID (expose to user), 1-based display index | **1-based display index** | Consistent with the window number convention already established. The view output is the canonical reference. | Index is positional; if games were ever reordered (they aren't after finalization), indices would shift. Post-finalization the list is frozen, so this is safe. |
| Confirmation for finalize | `--force` flag, `yes` prompt, no confirmation | **`yes` stdin prompt** | The spec explicitly requires a confirmation warning for an irreversible action. A `--force` flag is less visible. Reading stdin is appropriate for a single-user CLI. | Slightly inconvenient in scripting contexts. A `--yes` flag can be added later if needed. |
| OR-Tools version | Latest (9.11+), 9.10 LTS | **9.10.4067** | 9.10 is the most recent stable series on Maven Central with confirmed cross-platform native bundles. Upgrade is a one-line version bump. | None significant. |

---

## Operational Concerns

**Performance budget:**
- Fixture generation and slot enumeration are O(teams² + fields × weeks × windows) — sub-millisecond for any realistic league.
- CP-SAT solve is budgeted at **60 seconds**. The solver finds a feasible solution within the first few seconds for typical leagues, then spends the remaining budget tightening the `maxWeekLoad` objective. For the acceptance criterion scenario (10 divisions, 100 teams, 10 fields), variable count is bounded by `fixtures × slots_per_division`. With a 3-month season and typical Saturday/Sunday windows (8 slots/week × 13 weeks = ~100 slots per field per division), the variable space is approximately 900 fixtures × 1,000 slots = 900,000 boolean variables plus the `weekLoad` auxiliary variables. CP-SAT handles millions of variables; this is well within its range.
- Solver returns `FEASIBLE` (best solution found within budget), `OPTIMAL` (proven optimal), or `UNKNOWN` (no feasible solution within 60 seconds). Only `UNKNOWN` is treated as a failure — `FEASIBLE` is a valid, conflict-free schedule that may not be perfectly balanced but is never rejected.
- If solve returns `UNKNOWN`, the user gets a specific error message with actionable guidance.

**Error handling:**
- All validation errors → stderr + exit 1.
- OR-Tools `INFEASIBLE` or `UNKNOWN` → stderr diagnostic + exit 1. No partial schedule is written.
- I/O errors → stderr + exit 2.
- Stack traces are never shown; they are suppressed via picocli's exception handler.

**Data integrity:**
- Atomic write (temp + rename) guards against partial writes, as in all prior slices.
- A failed `generate` invocation (solver failure) leaves the previous schedule (if any) intact — no file write occurs on failure.

**Testing strategy:**
- `FixtureGeneratorTest` — verify circle method produces exactly N*(N-1) fixtures; verify each team-pair appears exactly once in each direction; verify no team is home in all first-half games.
- `SlotEnumeratorTest` — verify quantized slot generation; verify division restriction filtering; verify endTime boundary (game must fit entirely within window).
- `SchedulerServiceTest` — integration tests using small leagues (2 divisions, 4 teams each, 2 fields with 2 windows each); assert: all fixtures scheduled, no field conflicts, no team double-booking, all games within season dates, no team plays more than `ceil(totalGames / seasonWeeks) + 1` games in any single week (verifies the optimizer objective is applied).
- `SchedulerServiceTest` — infeasibility detection: configure a league where slots < fixtures for one division; assert specific division named in failure message.
- `ScheduleCommandTest` — precondition checks (no division, no field, finalized guard).
- `ScheduleCommandTest` — finalize: confirm prompt, `yes` → FINALIZED, non-yes → cancelled.
- `ScheduleCommandTest` — game override: field lookup, conflict detection, `overridden=true` flag.
- `LeagueStoreTest` — v2→v3 migration: write v2 file, call `load()`, assert v3 file with `schedule: null`.
- Jackson round-trip for `Schedule`, `ScheduledGame`, `ScheduleStatus`.

Tests use the existing `systemProperty 'user.home'` redirect and `maxParallelForks = 1`.

**Capacity:** For a full 10-division, 100-team, 10-field league, `league.json` grows to approximately 300–500 KB (900 games × ~400 bytes JSON per game). Well within the limits of a local file and JSON parsing.

---

## Out of Scope / Future Work

- **Web application** — this CLI validates the scheduling algorithm and data model; the web layer follows.
- **Configurable buffer time** — fixed at 15 minutes per the spec. A `--buffer` flag can be added when the spec requires it.
- **Post-finalization bulk edits / revert to draft** — explicitly out of scope per the PRD.
- **Rainout / reschedule workflows** — out of scope for Phase 1.
- **CSV, PDF, iCal export** — JSON only for Phase 1.
- **Team home-field preferences** — out of scope per the PRD.
- **Playoff bracket scheduling** — out of scope.
- **GraalVM native image** — blocked by OR-Tools native library incompatibility. Revisit if OR-Tools adds GraalVM support or if the scheduler is decoupled into a separate service.
- **Multi-league support** — deferred.
- **Fairness objective in CP-SAT** — the current model finds the first feasible schedule. A future improvement could add a soft objective to minimize variance in game counts per day or balance Saturday vs. Sunday games. This would require benchmarking to confirm it stays within the 30-second SLA.
- **`$PLANR_DATA_DIR` env var override** — deferred from prior slices; still deferred.
