# Scheduling Constraints — Technical Spec

**Date:** 2026-05-25  
**Status:** Ready for implementation  
**PRD:** `features/2026-05-25-scheduling-constraints.md`

---

## Overview

Three scheduling constraints are added: a per-team weekly game cap, a minimum rest-day gap between a team's games, and date-range field-division locks. The first two are stored as nullable `Integer` fields on the existing `LeagueConfig` record and enforced as hard CP-SAT constraints inside `SchedulerService.buildAndSolve()`. The third introduces a new `FieldDivisionLock` record stored on each `Field` and is enforced by filtering slots during `enumerateAllSlots()` and `estimateAvailableSlots()`. All three are purely Phase 2 concerns — `TeamScheduleService` is untouched. Schema version bumps from 5 → 6 with a trivial no-op migration marker.

---

## Component Diagram

```
┌────────────────────────────────────────────────────────────────────┐
│  CLI Layer (commands)                                              │
│                                                                    │
│  ConfigCommand.SetCmd      ← new --max-games-per-week, --rest-days │
│  ConfigCommand.ShowCmd     ← display two new fields                │
│                                                                    │
│  FieldCommand              ← register FieldLockCommand             │
│  FieldLockCommand (NEW)    ← add / delete / list division locks    │
│                                                                    │
│  ScheduleCommand.AssignCmd ← print active constraint config        │
└────────────────────────────────────────────────────────────────────┘
           │ store.load() / store.save()
┌──────────▼───────────────────────────────────────────────────────┐
│  LeagueStore                                                      │
│  • v5→v6 migration (no-op stamp)                                  │
│  • ObjectMapper unchanged (JavaTimeModule + SimpleModule)         │
└──────────┬────────────────────────────────────────────────────────┘
           │
┌──────────▼────────────────────────────────────────────────────────┐
│  Model (records)                                                   │
│                                                                    │
│  LeagueConfig  ← + maxGamesPerWeek: Integer, minRestDays: Integer │
│  Field         ← + divisionLocks: List<FieldDivisionLock>         │
│  FieldDivisionLock (NEW) — divisionId, startDate, endDate         │
└──────────┬────────────────────────────────────────────────────────┘
           │
┌──────────▼────────────────────────────────────────────────────────┐
│  SchedulerService                                                  │
│  • enumerateAllSlots()  ← filter locked fields per division        │
│  • estimateAvailableSlots() ← same lock filter                    │
│  • buildAndSolve()      ← two new hard CP-SAT constraint groups   │
└───────────────────────────────────────────────────────────────────┘
```

---

## Data Model

### `LeagueConfig` — modified record

Add two nullable fields after `blockedDays`. Null means "use default."

```
LeagueConfig(
    LocalTime  sunriseTime,
    LocalTime  sunsetTime,
    LocalDate  seasonStart,
    LocalDate  seasonEnd,
    List<DayOfWeekWindow> dowWindows,
    List<DayOfWeek>       blockedDays,
    Integer    maxGamesPerWeek,   // NEW — null → default 2
    Integer    minRestDays        // NEW — null → default 1
)
```

Compact constructor normalizes both to `null` (do not replace with a default — keep null so `config show` can distinguish "explicitly set" from "using default").

Add two `with*` helpers following existing pattern:
- `withMaxGamesPerWeek(Integer n)` → returns new record with all other fields preserved
- `withMinRestDays(Integer n)` → same

Update `LeagueConfig.empty()` to pass `null, null` for the two new parameters.

### `FieldDivisionLock` — new record

```
FieldDivisionLock(
    UUID      divisionId,
    LocalDate startDate,
    LocalDate endDate
)
```

No `id` field — identified by field name + 1-based index within `field.divisionLocks()` (same pattern as `FieldBlock`).

Serialized with Jackson via the existing `JavaTimeModule` (dates as `YYYY-MM-DD`, UUIDs as strings). No custom serializer needed.

### `Field` — modified record

Add `List<FieldDivisionLock> divisionLocks` as the last parameter:

```
Field(UUID id, String name, String address,
      List<FieldBlock> blocks,
      List<FieldDateOverride> dateOverrides,
      List<FieldDivisionLock> divisionLocks)   // NEW
```

Compact constructor: `divisionLocks = (divisionLocks == null) ? List.of() : divisionLocks;`

Add mutation helpers following the existing pattern:
- `withLockAdded(FieldDivisionLock lock)` → `Stream.concat`
- `withLockRemoved(int zeroBasedIndex)` → same as `withBlockRemoved`

**Callsites to update** for the new constructor parameter:
- `FieldCommand.AddCmd.call()` — `new Field(…, List.of(), List.of(), List.of())`
- `FieldCommand.EditCmd.applyEdits()` — pass `field.divisionLocks()` through
- `FieldCommand.DeleteCmd.call()` — output line may optionally include lock count
- `LeagueStore.load()` v<4 migration — creates `Field` instances with empty lists; add the new empty list

### `League` — version bump

Change `CURRENT_VERSION` from `5` to `6`.

---

## CLI Contracts

### `planr config set` — extended

Add two new options to `ConfigCommand.SetCmd`:

| Option | Type | Validation |
|--------|------|------------|
| `--max-games-per-week <N>` | `String` (parsed) | Integer ≥ 1; missing → no-op |
| `--rest-days <N>` | `String` (parsed) | Integer ≥ 0; missing → no-op |

**At-least-one guard**: the existing check (`if (sunriseStr == null && sunsetStr == null && startStr == null && endStr == null)`) expands to also check `maxGamesPerWeekStr == null && restDaysStr == null`.

**Mutation**: load the existing `LeagueConfig`, apply whichever fields are non-null, construct the new record explicitly (same pattern as today). Pass `existing.maxGamesPerWeek()` / `existing.minRestDays()` through when the option is not provided.

**Success output**: `"League config updated."` (no change to message).

**Error output**:
- `"Error: --max-games-per-week must be a positive integer (got \"<value>\")."` → exit 1
- `"Error: --rest-days must be a non-negative integer (got \"<value>\")."` → exit 1

### `planr config show` — extended

After the existing `Season end:` line, add two new lines in the same block:

```
Max games/week:   2 (default)
Min rest days:    1 (default)
```

When explicitly set:
```
Max games/week:   3
Min rest days:    2
```

Logic: if `config.maxGamesPerWeek() == null` print `"<DEFAULT_MAX_GAMES_PER_WEEK> (default)"`, else print the value as-is. Same for `minRestDays`.

Constants `DEFAULT_MAX_GAMES_PER_WEEK = 2` and `DEFAULT_MIN_REST_DAYS = 1` live in `SchedulerService` as `package-private static final int` fields so they can be referenced by the command layer without duplication.

### `planr field lock` — new command

New top-level class `FieldLockCommand` following the exact same structure as `FieldBlockCommand`: outer class with `@ParentCommand FieldCommand fieldCmd`, three static inner `Callable` classes.

Register in `FieldCommand`'s `@Command(subcommands = {…, FieldLockCommand.class})`.

#### `planr field lock add`

```
--field <name>     required  Field name (case-insensitive lookup)
--division <name>  required  Division name (case-insensitive lookup)
--start <date>     required  YYYY-MM-DD lock start (inclusive)
--end <date>       required  YYYY-MM-DD lock end (inclusive)
```

Validation order:
1. Parse `--start` and `--end`; error if malformed.
2. `--end` must be ≥ `--start`; error if not.
3. `store.load()` → find field (case-insensitive); error if not found.
4. Find division (case-insensitive in `league.divisions()`); error if not found.
5. Overlap check: for each existing lock in `field.divisionLocks()`, check if the new range overlaps any existing range. Two ranges overlap if `newStart <= existingEnd && newEnd >= existingStart`. Collect all conflicting 1-based indices; if any, print: `"Error: Lock date range overlaps with existing lock(s): #1, #3. Use 'planr field lock list' to review."` → exit 1.
6. Append `FieldDivisionLock(division.id(), startDate, endDate)` via `withLockAdded`.
7. Success: `"Division lock #<N> added: \"<fieldName>\" locked to \"<divisionName>\" from <start> to <end>."` (1-based index = new list size).

#### `planr field lock delete`

```
--field <name>  required  Field name (case-insensitive lookup)
--index <N>     required  1-based lock index
```

Validation:
1. `store.load()` → find field; error if not found.
2. Parse `--index` as integer; if `< 1` or `> field.divisionLocks().size()` print: `"Error: Lock #<N> not found for \"<fieldName>\" (1–<size> are valid)."` → exit 1. If list is empty: `"Error: No locks exist for \"<fieldName>\"."` → exit 1.
3. Resolve division name for the lock being deleted by looking up `lock.divisionId()` in `league.divisions()`.
4. Success: `"Division lock #<N> deleted (\"<fieldName>\" / \"<divisionName>\", <start> to <end>)."`.

#### `planr field lock list`

```
--field <name>  optional  Filter to a single field (case-insensitive)
```

When `--field` is provided and not found → exit 1 with error.

Table columns: `FIELD`, `#`, `DIVISION`, `START`, `END`  
Sort: by field name (alphabetical), then by `startDate` within each field.

Resolve division name from `lock.divisionId()` via `league.divisions()`. If the division has been deleted since the lock was added, show `"[unknown]"`.

Empty-state messages:
- No field filter: `"No division locks configured. Use 'planr field lock add' to create one."`
- Field filter: `"No locks for \"<fieldName>\". Use 'planr field lock add' to create one."`

---

## Critical Path Walkthrough

### Path 1: `planr config set --max-games-per-week 3 --rest-days 2`

1. `ConfigCommand.SetCmd.call()` — parses both options.
2. Validates: 3 ≥ 1 ✓, 2 ≥ 0 ✓.
3. `store.load()` → deserialize `league.json` (now version 6).
4. Load `existing = league.config()` (or `LeagueConfig.empty()` if null).
5. Construct `LeagueConfig(existing.sunriseTime(), …, existing.dowWindows(), existing.blockedDays(), 3, 2)`.
6. `store.save(league.withConfig(updated))` → `.tmp` then atomic rename.
7. Print `"League config updated."`, exit 0.

### Path 2: `planr field lock add --field "T-Ball Field" --division "T-Ball" --start 2026-03-01 --end 2026-06-30`

1. `FieldLockCommand.AddCmd.call()`.
2. Parses dates; validates end ≥ start.
3. `store.load()`.
4. `league.findField("T-Ball Field")` (case-insensitive) → found.
5. `league.divisions().stream().filter(d -> d.name().equalsIgnoreCase("T-Ball"))` → found.
6. Overlap check: `field.divisionLocks()` is empty → no conflicts.
7. `field.withLockAdded(new FieldDivisionLock(division.id(), 2026-03-01, 2026-06-30))`.
8. `store.save(league.withFieldReplaced(field.id(), updated))`.
9. Print `"Division lock #1 added: \"T-Ball Field\" locked to \"T-Ball\" from 2026-03-01 to 2026-06-30."`, exit 0.

### Path 3: `planr schedule assign` — solver with new constraints

**Pre-solve:**
1. `AssignCmd.call()` prints the active constraint config before the confirmation prompt:
   ```
   Scheduling constraints: max 2 games/week per team, min 1 rest day between games.
   ```
   Derive effective values: `config.maxGamesPerWeek() != null ? config.maxGamesPerWeek() : DEFAULT_MAX_GAMES_PER_WEEK`.

2. `schedulerService.estimateAvailableSlots()` → now skips locked fields (see below).

**`enumerateAllSlots()` — division lock filtering:**  
Inside the `for (UUID divId : slotsByDiv.keySet())` inner loop, before enumerating slots for `(field, currentDate, divId)`, add:

```
boolean lockedToOther = field.divisionLocks().stream().anyMatch(lock ->
    !lock.divisionId().equals(divId)
    && !currentDate.isBefore(lock.startDate())
    && !currentDate.isAfter(lock.endDate()));
if (lockedToOther) continue;
```

This silently excludes the field from that division's slot pool for that date. The locked division is unaffected because `lock.divisionId().equals(divId)` is true for it.

**`estimateAvailableSlots()` — same lock filter:**  
Before the inner `field` loop body, add the same check using the method's `divisionId` parameter.

**`buildAndSolve()` — new constraint group C4 (max games per week):**

```
int cap = (config.maxGamesPerWeek() != null) ? config.maxGamesPerWeek() : DEFAULT_MAX_GAMES_PER_WEEK;
for (List<GameVar> weekVars : byTeamWeek.values()) {
    if (!weekVars.isEmpty()) {
        Literal[] lits = weekVars.stream().map(gv -> (Literal) gv.var()).toArray(Literal[]::new);
        model.addLessOrEqual(LinearExpr.sum(lits), cap);   // hard cap — new
        model.addLessOrEqual(LinearExpr.sum(lits), maxWeekLoad);  // soft tracking — unchanged
    }
}
```

The existing `maxWeekLoad` soft objective is kept; it now operates within the hard cap. No change to the objective function.

**`buildAndSolve()` — new constraint group C5 (minimum rest days):**

```
int restDays = (config.minRestDays() != null) ? config.minRestDays() : DEFAULT_MIN_REST_DAYS;
if (restDays > 0) {
    for (Map.Entry<String, List<GameVar>> entry : byTeamDate.entrySet()) {
        if (entry.getValue().isEmpty()) continue;
        String[] parts = entry.getKey().split("\\|", 2);
        String teamIdStr = parts[0];
        LocalDate date = LocalDate.parse(parts[1]);

        for (int r = 1; r <= restDays; r++) {
            List<GameVar> nextDay = byTeamDate.getOrDefault(
                teamIdStr + "|" + date.plusDays(r), List.of());
            if (nextDay.isEmpty()) continue;

            // D's games and (D+r)'s games cannot both fire for this team.
            List<Literal> combined = new ArrayList<>();
            entry.getValue().forEach(gv -> combined.add(gv.var()));
            nextDay.forEach(gv -> combined.add(gv.var()));
            if (combined.size() > 1) {
                model.addAtMostOne(combined.toArray(Literal[]::new));
            }
        }
    }
}
```

Note: because C3 already enforces at most one game per team per day, each `entry.getValue()` and each `nextDay` list has at most one element. Each combined group therefore has at most 2 elements, and `addAtMostOne` on 2 elements is simply a `sum ≤ 1` constraint. This keeps constraint count at O(numTeams × numDates × restDays), which is well-bounded.

**Post-solve — constraint summary additions:**  
After `printConstraintSummary(success.divisionSummaries())`, `AssignCmd` prints:

```
Active constraints: max 3 games/week per team, min 2 rest days between games.
```

Then, if any field has division locks whose date ranges overlap the season window, print:

```
Field division locks applied:
  "T-Ball Field" → T-Ball (2026-03-01 to 2026-06-30)
```

This loop is in `AssignCmd` (not `SchedulerService`) because it reads from `league.fields()` and `league.divisions()` which the service doesn't need to expose.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|----------|--------------------|--------|-----------|---------------|
| Store max-games and rest-days as nullable `Integer` vs. always-present `int` with defaults | Nullable keeps null = "use system default" distinct from "explicitly set to default" — needed for AC-4's `(default)` display | Nullable `Integer` | Lets `config show` distinguish unset from explicit; avoids a schema migration that rewrites every old file | Code must always null-check and substitute default before use |
| Rest-day constraint as pairwise `atMostOne` vs. sliding-window sum | Pairwise is simpler and each group has ≤ 2 literals (C3 guarantees at most 1 game/team/day). Sliding-window sum needs a separate IntVar per window | Pairwise `atMostOne` on pairs `(D, D+r)` | O(numTeams × numDates × restDays) constraints, each tiny (2 literals). No new IntVars. Readable and directly mirrors the business rule | With restDays > 3 and a long season, constraint count grows; still bounded and acceptable for a little-league season |
| Week-load: convert soft objective to hard cap only vs. keep both | Removing the soft minimization simplifies the model; keeping it incentivizes the solver to spread games evenly within the cap | Keep soft minimization alongside hard cap | The hard cap is the new correctness guarantee; the soft objective still improves schedule quality | Slightly more complex objective, but unchanged from prior version |
| Division lock identified by index vs. by a stable UUID | Index is consistent with `FieldBlock` and avoids adding an `id` field to the record | 1-based index | Pattern already established; consistent UX | Delete-by-index is positional: indices shift after a deletion, which can confuse users who cache them. Mitigated by `list` command before delete |
| Lock stored on `Field` vs. on `Division` vs. new top-level list | Field-level is natural ("this field is restricted to…"); division-level would require scanning all divisions to find locked fields; top-level adds complexity | Stored on `Field` as `List<FieldDivisionLock>` | Locality: slot enumeration already iterates per-field, so the filter is a single stream check on `field.divisionLocks()` | Adding a lock requires knowing both the field name and division name; no bidirectional index maintained (acceptable for CLI volume) |
| Schema version bump with trivial migration vs. relying on `FAIL_ON_UNKNOWN_PROPERTIES` | The `FAIL_ON_UNKNOWN_PROPERTIES = false` and compact-constructor null-normalization already handle old files safely without a migration | Bump version + no-op migration block | Consistent with prior versions (v2 was also a no-op marker). Prevents the migration block from re-running on subsequent loads | None: purely defensive bookkeeping |

---

## Operational Concerns

### Testing

Follow the existing `CommandTestBase` pattern. New test classes:

- **`FieldLockCommandTest`** — `add` (success, field not found, division not found, end before start, overlap), `delete` (success, out of range, empty list), `list` (all fields, filtered, empty).
- **`ConfigCommandTest`** additions (or new inner class) — `--max-games-per-week` valid, invalid (0, negative, non-integer), `--rest-days` valid (0, 1), invalid (negative, non-integer); combined with existing options; `show` displays `(default)` when unset and explicit value when set.
- **`SchedulerServiceTest`** (unit, no CLI) — test that the week cap is respected with a synthetic league where more than 2 games in one week would otherwise be scheduled; test that rest days are respected with back-to-back dates; test that locked fields are excluded for non-owning divisions.

### Migration

`LeagueStore.load()` adds the following block after the existing v4→v5 block:

```java
if (league.version() < 6) {
    league = new League(6, league.config(), league.divisions(), league.fields(),
        league.teamSchedule(), league.schedule());
    save(league);
}
```

No data transformation needed:
- `LeagueConfig.maxGamesPerWeek` and `minRestDays` are absent from old JSON → deserialized as `null` → treated as "use default."
- `Field.divisionLocks` is absent from old JSON → compact constructor normalizes to `List.of()`.

### Rollback

If a v6 file is read by a hypothetical binary that only knows v5, `FAIL_ON_UNKNOWN_PROPERTIES = false` silently drops `maxGamesPerWeek`, `minRestDays`, and `divisionLocks`. The next `store.save()` by the old binary will re-serialize without those fields, effectively losing the new config. This is the known forward-compatibility tradeoff already accepted by the project.

---

## Out of Scope / Future Work

- **Edit for division locks**: the PRD defines add/delete/list. No edit subcommand. Workaround: delete + re-add.
- **`planr field delete` warning for active locks**: currently deletes all blocks and overrides silently. Division locks are similarly silently removed. A future enhancement could warn. Not in scope.
- **Per-division constraint overrides**: league-wide only, as stated in the PRD.
- **Phase 1 rest-day awareness**: `TeamScheduleService` is untouched; it does not assign dates. Rest-day enforcement lives entirely in Phase 2.
- **Constraint summary in `planr schedule status`**: not required by the PRD. Status shows game counts only.

---

## Errata

### E1 — Field Division Lock: Bidirectional Pinning

**Status:** Design error in original spec; implementation must be corrected. Supersedes the lock-filtering description in *Critical Path Walkthrough — Path 3* and the `SchedulerService` notes in the *Component Diagram*.

---

#### What the original spec said (incorrect)

A `FieldDivisionLock` was described as a one-way exclusion: field F locked to division D blocks all *other* divisions from using F during the lock period. Division D itself remains free to use any available field.

#### What the correct behavior is

A `FieldDivisionLock` is **bidirectional**. During the lock period:

1. Other divisions cannot use the locked field. *(unchanged)*
2. The owning division is **pinned** to its locked field — it may only receive slots from fields that are explicitly locked to it for that date. Unlocked fields are excluded from the owning division's slot pool while the pin is active.

**Why this matters:** The canonical example is T-ball. The league locks a smaller infield to the T-ball division for the entire season because the full-size fields are physically unsafe for young players. Under the original one-way exclusion, the solver could schedule T-ball games on any full-size field that happened to have open slots — defeating the safety rationale for the lock entirely. Pinning forces the solver to keep all T-ball games on the dedicated infield.

---

#### Implementation correction — `SchedulerService` only

No changes are needed to: model records, `LeagueStore`, any command class, schema version, or the CP-SAT constraint model. The correction is confined to slot enumeration.

**Step 1 — Add a new private helper:**

```java
private boolean isDivisionPinnedElsewhere(League league, Field currentField,
                                           LocalDate date, UUID divisionId) {
    // True when divisionId has an active lock on a field OTHER than currentField
    // for this date. When true, currentField must be excluded from this division's
    // slot pool — the division is pinned to its locked field(s) instead.
    return league.fields().stream()
        .filter(f -> !f.id().equals(currentField.id()))
        .anyMatch(f -> f.divisionLocks().stream()
            .anyMatch(lock -> lock.divisionId().equals(divisionId)
                && !date.isBefore(lock.startDate())
                && !date.isAfter(lock.endDate())));
}
```

**Step 2 — `enumerateAllSlots()`:** inside the `for (UUID divId : slotsByDiv.keySet())` inner loop, add after the existing `isFieldLockedToOtherDivision` guard:

```java
if (isFieldLockedToOtherDivision(field, currentDate, divId)) continue;
if (isDivisionPinnedElsewhere(league, field, currentDate, divId)) continue;  // E1
```

**Step 3 — `estimateAvailableSlots()`:** inside the `for (Field field : league.fields())` loop, add the same guard:

```java
if (isFieldLockedToOtherDivision(field, date, divisionId)) continue;
if (isDivisionPinnedElsewhere(league, field, date, divisionId)) continue;  // E1
```

---

#### Edge cases

| Scenario | Behavior |
|----------|----------|
| Division has no lock on any field on date X | Uses all available unlocked fields — unchanged. |
| Lock covers a partial date range (e.g., June only) | Pinned to locked field in June; uses all fields outside June. |
| Division has locks on two different fields during overlapping periods | Gets slots from both locked fields during the overlap; excluded from unlocked fields. The existing overlap check in `FieldLockCommand.AddCmd` prevents two locks on the *same* field from overlapping; locks on different fields for the same period are legal and both contribute. |
| Locked field has insufficient capacity to host all division games | Solver produces a partial assignment — same behavior as any slot-shortage scenario. No new logic needed. |

---

#### Test correction — `SchedulerServiceTest`

The existing tests `lockedFieldContributesZeroSlotsToOtherDivision`, `lockedFieldContributesSlotsToDivisionThatOwnsLock`, and `partialPeriodLockLeavesRestOfSeasonOpen` remain correct after this change.

The existing test `lockedFieldOnlyHostsOwningDivisionGames` verifies the exclusion side (AAA games do not appear on the Majors-locked field) but does not assert the pinning side. Add a companion test:

```
@DisplayName("field lock: all owning division games are assigned to the locked field")
void owningDivisionGamesOnlyOnLockedField()
```

Setup: two fields (`lockedField` locked to Majors for the full season, `unlockedField` with no locks), one division (Majors). After `assign()`, assert that every `ScheduledGame` has `fieldId` equal to `lockedField.id()`. No Majors games should appear on `unlockedField`.
