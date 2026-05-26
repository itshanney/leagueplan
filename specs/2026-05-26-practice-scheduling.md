# Tech Spec — Practice Scheduling (v0.10.0)

**Date:** 2026-05-26  
**PRD:** `features/2026-05-26-practice-scheduling.md`  
**Status:** Ready for implementation

---

## Overview

Practice scheduling follows the same two-phase pattern established by `planr schedule` and `planr playoff`: Phase 1 generates slot stubs (no field assigned yet); Phase 2 runs the CP-SAT solver to assign fields and times. The primary structural difference from games is that each practice involves exactly one team — there is no opponent. A new `PracticeFixture` record replaces the two-team `Fixture` used elsewhere. The existing `SchedulerService` gains a new `assignPractices` method that mirrors `assignPlayoffs`, with per-division date windows replacing the single shared playoff window. The data model acquires four nullable practice-config fields on `Division`, two new model records (`PracticeSchedule`, `PracticeSlot`), a new `PracticeState` enum, and a `List<PracticeSchedule>` on `League`. `LeagueStore` advances to version 8.

---

## Component Diagram

```
PlanrApp
├── PracticeCommand          (new) — routes planr practice subcommands
│   ├── GenerateCmd          (new) — Phase 1: create PracticeSlot stubs per team
│   ├── AssignCmd            (new) — Phase 2: run CP-SAT via SchedulerService
│   ├── StatusCmd            (new) — display per-division or per-slot state
│   └── ClearCmd             (new) — remove a division's PracticeSchedule
├── DivisionCommand.Edit     (modified) — four new optional flags
├── DivisionCommand.List     (modified) — four new display columns
└── SchedulerService         (modified) — new assignPractices() method
    └── PracticeScheduleResult (new)  — sealed Success/Failure result type

Model layer (new/modified):
  Division           (modified) — practiceCount, practiceDurationMinutes,
                                  practiceStart, practiceEnd (all nullable)
  PracticeSchedule   (new)     — divisionId, state, List<PracticeSlot>
  PracticeSlot       (new)     — slotId, teamId, slotNumber, assignedDate,
                                  assignedStartTime, assignedFieldId
  PracticeState      (new)     — enum GENERATED | ASSIGNED
  League             (modified) — List<PracticeSchedule> practiceSchedules
  LeagueStore        (modified) — version 8 migration no-op marker

Scheduler layer (new):
  PracticeFixture    (new)     — slotId, teamId, divisionId, durationMinutes
  PracticeScheduleResult (new) — sealed Success / Failure
```

---

## Data Model

### Modified: `Division`

Add four nullable fields after the existing `targetGamesPerTeam`. All default to `null` when absent in existing JSON (no migration logic required — `FAIL_ON_UNKNOWN_PROPERTIES` is disabled and compact constructor handles nulls).

```
Division(
    UUID id,
    String name,
    int gameDurationMinutes,
    int targetGamesPerTeam,
    List<Team> teams,
    Integer practiceCount,           // new — null = not configured
    Integer practiceDurationMinutes, // new — null = not configured
    LocalDate practiceStart,         // new — null = not configured
    LocalDate practiceEnd            // new — null = not configured
)
```

Add `withPracticeConfig(Integer count, Integer durationMinutes, LocalDate start, LocalDate end)` mutation method. All four may be independently null-updated, so the method accepts individual nullable parameters or a builder-style chain — a single `withPracticeConfig` that replaces all four simultaneously is simplest and avoids eight individual mutators. The command validates before calling, so null means "leave unchanged."

### New: `PracticeState`

```java
public enum PracticeState { GENERATED, ASSIGNED }
```

### New: `PracticeSlot`

```
PracticeSlot(
    UUID slotId,
    UUID teamId,
    int slotNumber,              // 1-based: 1st practice for this team, 2nd, ...
    LocalDate assignedDate,      // null until assigned
    LocalTime assignedStartTime, // null until assigned
    UUID assignedFieldId         // null until assigned
)
```

Mutation helper: `withAssignment(LocalDate, LocalTime, UUID)` → new `PracticeSlot` with all three set.  
Mutation helper: `withAssignmentCleared()` → new `PracticeSlot` with all three set to `null`.

### New: `PracticeSchedule`

```
PracticeSchedule(
    UUID divisionId,
    PracticeState state,
    List<PracticeSlot> slots
)
```

Mutation helper: `withSlots(List<PracticeSlot>)`.  
Mutation helper: `withState(PracticeState)`.

### Modified: `League`

Add `List<PracticeSchedule> practiceSchedules` after `playoffs`. Compact constructor normalizes `null` to `List.of()`.

New query: `Optional<PracticeSchedule> findPracticeSchedule(UUID divisionId)`  
New mutations: `withPracticeScheduleAdded`, `withPracticeScheduleReplaced(UUID divisionId, PracticeSchedule)`, `withPracticeScheduleRemoved(UUID divisionId)`

### New: `PracticeFixture` (scheduler layer)

```
PracticeFixture(
    UUID slotId,       // maps back to PracticeSlot.slotId for result assembly
    UUID teamId,
    UUID divisionId,
    int durationMinutes
)
```

### New: `PracticeScheduleResult` (scheduler layer)

```java
public sealed interface PracticeScheduleResult
        permits PracticeScheduleResult.Success, PracticeScheduleResult.Failure {
    record Success(
        Map<UUID, Slot> assignmentsBySlotId, // PracticeSlot.slotId → Slot
        boolean optimal,
        List<DivisionSummary> divisionSummaries
    ) implements PracticeScheduleResult {}
    record Failure(String message) implements PracticeScheduleResult {}
}
```

---

## API Contracts (CLI Commands)

### `planr division edit` — new flags

| Flag | Type | Validation |
|------|------|-----------|
| `--practice-count <n>` | Integer | ≥ 1; exit 1 if ≤ 0 |
| `--practice-duration-minutes <n>` | Integer | ≥ 1; exit 1 if ≤ 0 |
| `--practice-start <YYYY-MM-DD>` | LocalDate | must be < seasonStart (when set) |
| `--practice-end <YYYY-MM-DD>` | LocalDate | must be ≥ practiceStart; must be < seasonStart |

Validation order: parse dates first, then check `end ≥ start`, then check both `< seasonStart`. When either date flag is supplied without the other, read the stored value for the missing side before validation.

Mutation: after validation, call `division.withPracticeConfig(...)` passing the new value or the existing stored value for each unchanged field.

### `planr division list` — output change

Add four columns to the per-division row:

```
DIVISION  TEAMS  GAME_MIN  TARGET  PRAC_COUNT  PRAC_MIN  PRAC_START   PRAC_END
--------  -----  --------  ------  ----------  --------  ----------   --------
Minors    6      75        12      3           60        2025-02-01   2025-03-14
Majors    8      90        14      --          --        --           --
```

Show `--` for any field that is `null`.

### `planr practice generate`

No required flags. Operates on all divisions.

**Preconditions checked per division:**
1. All four practice fields set → qualify; otherwise warn and skip.
2. No existing `PracticeSchedule` for division → qualify; otherwise warn and skip.

**On qualification:** create T × P `PracticeSlot` records (T = team count, P = `practiceCount`). Assign sequential `slotNumber` 1…P per team. `PracticeSchedule` state = `GENERATED`. Persist.

**Output:**
```
Generated 18 practice slots for Minors (6 teams × 3 practices).
Generated 24 practice slots for Majors (8 teams × 3 practices).
Practice generation complete: 2 divisions processed, 42 total slots created.
```
Exit 0 on success, 1 if no divisions qualify, 2 on I/O error.

### `planr practice assign`

No flags.

**Preconditions:**
1. At least one `PracticeSchedule` exists; exit 1 otherwise.
2. `config.sunriseTime` and `config.sunsetTime` configured; exit 1 otherwise.
3. At least one field configured; exit 1 otherwise.

**Confirmation prompt** (same pattern as `planr playoff assign`):
```
Practice field assignment: 42 slots across 2 division(s).
Scheduling constraints: max 2 practice(s)/week per team, min 1 rest day(s) between practices.
Begin field assignment? This may take up to 5 minutes. Type 'yes' to continue:
```

**Solve:** clear all existing assignments on all `PracticeSchedule` entities, invoke `SchedulerService.assignPractices`, write results back, transition all to `ASSIGNED`.

**Final output line:**
```
Practice field assignment complete: 40/42 slots assigned across 2 divisions.
```
Exit 0 always on solver completion (including partial), 1 if preconditions fail, 2 on I/O.

### `planr practice status`

**No flag (summary):**
```
DIVISION  STATE          ASSIGNED  TOTAL
--------  -----          --------  -----
Majors    ASSIGNED       24        24
Minors    ASSIGNED       17        18
Rookies   NOT_CONFIGURED --        --
```
States: `NOT_CONFIGURED` (missing any config field), `NOT_STARTED` (config complete but no `PracticeSchedule`), `GENERATED`, `ASSIGNED`.

**`--division <name>` (detail):**
```
Division: Minors | State: ASSIGNED | Period: 2025-02-01 to 2025-03-14

TEAM         PRACTICE  DATE        TIME   FIELD
-----------  --------  ----------  -----  -------
Cardinals    1 of 3    2025-02-08  10:00  Field A
Cardinals    2 of 3    2025-02-15  10:00  Field B
Cardinals    3 of 3    2025-02-22  10:00  Field A
Giants       1 of 3    UNASSIGNED
...
```
Exit 1 if division not found or no `PracticeSchedule` exists for it.

### `planr practice clear`

Requires `--division <name>`. Confirmation prompt before removal. Exits 1 if no `PracticeSchedule` exists.

---

## Critical Path Walkthrough

### Path 1 — Happy path: configure, generate, assign, view

1. `planr division edit --name Minors --practice-count 3 --practice-duration-minutes 60 --practice-start 2025-02-01 --practice-end 2025-03-14`
   - Load league, find division "Minors", validate flags, call `division.withPracticeConfig(3, 60, 2025-02-01, 2025-03-14)`, save.
2. `planr practice generate`
   - Load league. Minors has all 4 fields set → qualifies. No existing `PracticeSchedule` for Minors → proceed. Create 6 × 3 = 18 `PracticeSlot` records (slotIds are new random UUIDs, `assignedDate` etc. null). Build `PracticeSchedule(minorsId, GENERATED, slots)`. Call `league.withPracticeScheduleAdded(...)`. Save.
3. `planr practice assign`
   - Load league. One `PracticeSchedule` found. Config has sunrise/sunset. Fields exist. Display prompt. User types `yes`.
   - Clear all assignments on existing `PracticeSchedule` entities (no-op since all null).
   - Build `PracticeFixture` list: one per `PracticeSlot`. Duration = `division.practiceDurationMinutes()` = 60.
   - Call `SchedulerService.assignPractices(league, practiceSchedules)`.
   - Inside solver: enumerate slots for Minors over 2025-02-01 to 2025-03-14 using 60-minute duration. Build CP-SAT model. Run solve.
   - On `Success`: iterate `PracticeSlot` list, look up each `slotId` in `assignmentsBySlotId`, call `slot.withAssignment(...)`. Build updated `PracticeSchedule(divisionId, ASSIGNED, updatedSlots)`. Save.
   - Print summary table and final line.
4. `planr practice status --division Minors`
   - Load league. Find Minors division. Find its `PracticeSchedule`. Print detail table.

### Path 2 — Partial assignment

1. Practice window is too narrow for all slots (e.g., 18 slots but only 14 available field times given constraints).
2. Solver returns `Success` with 14 assignments.
3. Command writes the 14 assignments back; 4 slots remain with `assignedDate = null`.
4. All `PracticeSchedule` entities still transition to `ASSIGNED` (partial is still "done solving").
5. Final line: `Practice field assignment complete: 14/18 slots assigned across 1 division.`
6. `planr practice status --division Minors` shows 4 rows with `UNASSIGNED`.

### Path 3 — Re-solve after configuration change

1. Organizer realizes practice window is wrong. Runs `planr practice clear --division Minors`. Confirms with `yes`. `PracticeSchedule` for Minors is removed from `League`.
2. `planr division edit --name Minors --practice-end 2025-03-28` (extends window).
3. `planr practice generate` → recreates 18 slots (new `slotId` UUIDs).
4. `planr practice assign` → full re-solve with the extended window.

---

## SchedulerService Changes

### New method: `assignPractices`

```
public PracticeScheduleResult assignPractices(League league, List<PracticeSchedule> schedules)
```

**Implementation outline:**

```
1. Loader.loadNativeLibraries()
2. Build Map<UUID, List<PracticeFixture>> fixturesByDiv from schedules:
   - For each PracticeSchedule → each PracticeSlot:
     - Look up Division.practiceDurationMinutes() for this divisionId
     - Create PracticeFixture(slot.slotId(), slot.teamId(), divisionId, durationMinutes)
3. Build Map<UUID, LocalDate[]> divisionWindows from Division.practiceStart/End
4. Call enumeratePracticeSlots(league, divisionWindows, fixturesByDiv.keySet())
   → Map<UUID, List<Slot>> slotsByDiv
5. Emit feasibility check line
6. Call buildAndSolvePractices(league, fixturesByDiv, slotsByDiv, startMs)
   → PracticeScheduleResult
```

### New private method: `enumeratePracticeSlots`

Signature:
```
private Map<UUID, List<Slot>> enumeratePracticeSlots(
    League league,
    Map<UUID, LocalDate[]> divisionWindows,
    Set<UUID> divisionIds)
```

Logic is identical to `enumeratePlayoffSlots` except:
- Iterates each division's own window (`divisionWindows.get(divId)`) rather than a shared `[start, end]`.
- Uses `practiceDurationMinutes` from the division (add `divisionPracticeDuration` helper analogous to `divisionDuration`).
- Iterates dates globally only across the union of all windows (optimization: from `min(practiceStart)` to `max(practiceEnd)` across all divisions, then skip dates outside a given division's window in the inner loop).

### New private method: `buildAndSolvePractices`

Nearly identical to `buildAndSolvePlayoffs` with one key difference: each `PracticeFixture` has only one team (`teamId`), so `byTeamDate` and `byTeamWeek` receive **one registration per practice var**, not two:

```java
// In the fixture loop:
String teamKey = fixture.teamId() + "|" + slot.date();
byTeamDate.computeIfAbsent(teamKey, k -> new ArrayList<>()).add(gv);

var weekFields = WeekFields.ISO;
String weekKey = slot.date().get(weekFields.weekOfWeekBasedYear())
    + "|" + slot.date().get(weekFields.weekBasedYear());
byTeamWeek.computeIfAbsent(fixture.teamId() + "|" + weekKey, k -> new ArrayList<>()).add(gv);
```

Constraints C1–C5 apply unchanged in meaning:
- **C1** — each practice slot assigned at most once
- **C2** — field non-overlap via 15-min tick buckets (same as existing)
- **C3** — no team practices twice on the same calendar day
- **C4** — no team exceeds `maxGamesPerWeek` practices in an ISO week
- **C5** — `minRestDays` enforced between consecutive practice dates per team

Return type is `PracticeScheduleResult.Success(assignmentsBySlotId, optimal, divisionSummaries)` where `assignmentsBySlotId` maps `PracticeSlot.slotId` → `Slot`.

### New private helper: `divisionPracticeDuration`

```java
private int divisionPracticeDuration(League league, UUID divId) {
    return league.divisions().stream()
        .filter(d -> d.id().equals(divId))
        .findFirst()
        .map(Division::practiceDurationMinutes)
        .orElseThrow();
}
```

---

## LeagueStore Migration

Current version after playoff feature: `7`. This feature advances to `8`.

```java
// v7→v8: adds practiceSchedules list to League.
// Absent from old JSON; compact constructor normalizes null to List.of(),
// so no data transformation needed — this block only stamps the version.
if (league.version() < 8) {
    league = new League(8, league.config(), league.divisions(), league.fields(),
        league.teamSchedule(), league.schedule(), league.playoffs(), league.practiceSchedules());
    save(league);
}
```

Also update `League.empty()` and `CURRENT_VERSION = 8`.

---

## PlanrApp Registration

Add `PracticeCommand.class` to the `subcommands` array in `@Command` on `PlanrApp`.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| Single-team fixture representation | Reuse `Fixture` with `awayTeamId = homeTeamId`; new `PracticeFixture` record | New `PracticeFixture` | Avoids confusing `homeTeamId == awayTeamId` semantic; constraint registration is explicit and readable | Minor duplication of a small record |
| Solver method location | New `PracticeScheduleService`; new method on `SchedulerService` | New method on `SchedulerService` | Consistent with `assignPlayoffs` pattern; no new class needed for what is essentially the same solver algorithm | `SchedulerService` grows longer; mitigated by clear method naming |
| Date window enumeration | Single global window (would require a dummy union range); per-division windows | Per-division windows passed as `Map<UUID, LocalDate[]>` | Correct model: each division has its own practice window; union-range approach would enumerate unnecessary slots for divisions outside their window | Slightly more complex enumeration loop |
| `practiceDurationMinutes` location | `LeagueConfig` (league-wide); `Division` (per-division) | `Division` | Different divisions (Rookies vs Majors) have structurally different practice lengths; league-wide would be an incorrect constraint | `division list` output needs more columns |
| `maxGamesPerWeek` scoping for practices | Shared cap across games + practices; practice-only weeks use same cap | Same cap, practice-only context | Practice windows end before `seasonStart`; no game activity in practice weeks; cap applies to practices alone with no special logic | If `practiceEnd` validation is bypassed or seasonStart changes post-assign, a team could theoretically have a mixed week — mitigated by AC-5 date enforcement |

---

## Operational Concerns

This is a CLI prototype targeting a single developer's local machine. All state lives in `~/.planr/league.json`. No deployment, monitoring, or alerting applies.

**Data safety:** `LeagueStore.save()` writes to `.tmp` then atomically renames — existing crash-safety guarantee applies to all new writes.

**Rollback:** `league.json.bak` (existing convention) can be restored manually if a migration goes wrong. The v7→v8 migration is a no-op stamp, so there is no irreversible transformation.

**Solver timeout:** Practice assignment shares the 300-second CP-SAT timeout from the existing solver. Pre-season practice windows are typically narrower than full seasons, so solve time is expected to be shorter in practice. No timeout change is needed.

**Version compatibility:** A v8 file read by a v7 binary will silently ignore `practiceSchedules` (due to `FAIL_ON_UNKNOWN_PROPERTIES = false`) but will overwrite it as empty on next save. Downgrade is possible only before any practices are generated.

---

## Out of Scope / Future Work

- Refactoring the three near-identical `buildAndSolve*` methods into a shared core. Deferred per the "no premature abstraction" rule for a prototype. If a fourth solve path is added, consider extraction at that point.
- `planr practice generate --division <name>` (single-division generation). Current design always processes all divisions. Targeted generation can be added without model changes.
- A `practiceDurationMinutes` fallback to `gameDurationMinutes` when `practiceDurationMinutes` is not set. Not specified in the PRD; division must configure it explicitly.
