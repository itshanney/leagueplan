# Scheduling Constraints

**Date:** 2026-05-25  
**Status:** Finalized

---

## Problem Statement

The Phase 2 field-assignment scheduler has no hard limits on how many games a team plays in a single week, no enforced rest period between a team's games, and no mechanism to restrict a specialized field to one division. Without these constraints, the solver can legally produce schedules where T-ball teams play back-to-back days or share a full-size field, or where a single team is scheduled five times in one week — all technically valid but operationally unacceptable for a little league.

---

## Proposed Solution

Add three new configurable scheduling constraints:

1. **Max games per week per team** — a league-level cap (default `2`) stored in `LeagueConfig` and enforced as a hard upper bound in the CP-SAT solver. No team may be assigned more than this many games in a single ISO week.

2. **Minimum rest days between games** — a league-level floor (default `1`) stored in `LeagueConfig` and enforced as a hard gap constraint in the CP-SAT solver. If a team plays on day D, they may not play again until day D + restDays + 1 at the earliest (i.e., `restDays` full calendar days must separate any two of a team's games).

3. **Field division locks** — a date-range lock stored on each `Field` record that restricts a field to one division for a bounded period. During a lock's date range, the locked field is excluded from slot enumeration for all other divisions. Locks are managed via a new `planr field lock` subcommand.

Both league-level values are set through extensions to the existing `planr config set` command and displayed in `planr config show`. Neither constraint auto-invalidates an existing `DRAFT` or `FINALIZED` schedule; they take effect the next time `planr schedule assign` is run.

---

## User Stories

1. **As a league organizer**, I want to cap how many games any team can play in a single week, so that the schedule doesn't overburden teams with back-to-back weekday and weekend games in the same week.

2. **As a league organizer**, I want to require a minimum number of rest days between a team's games, so that young players aren't scheduled on consecutive days.

3. **As a league organizer**, I want to lock a small specialized field (e.g., a T-ball infield) to a specific division for the entire season, so that older-division games are never assigned to a field that is too small or configured for a different age group.

4. **As a league organizer**, I want to lock a field to one division only for a portion of the season (e.g., while that field is being renovated for other uses), so that the restriction lifts automatically after the renovation window without manual intervention.

5. **As a league organizer**, I want to see all field division locks, max-games-per-week, and rest-day settings in the existing config and field displays, so that I can audit my constraints in one place.

---

## Acceptance Criteria

### League-Level Config — `planr config set`

- **AC-1.** `planr config set --max-games-per-week <N>` stores the value in `LeagueConfig`. `N` must be a positive integer (≥ 1); if not, the command exits with code `1` and an error message.
- **AC-2.** `planr config set --rest-days <N>` stores the value in `LeagueConfig`. `N` must be a non-negative integer (≥ 0); if not, the command exits with code `1` and an error message.
- **AC-3.** `--max-games-per-week` and `--rest-days` may be combined with each other and with the existing `--sunrise`, `--sunset`, `--start`, and `--end` options in a single invocation.
- **AC-4.** `planr config show` displays `Max games/week:` and `Min rest days:` lines in the main config block. If a value has not been explicitly set, the line shows the default value followed by `(default)`.

### Scheduler — Max Games Per Week

- **AC-5.** When `planr schedule assign` runs, no team is assigned more than `maxGamesPerWeek` games in any single ISO week (Monday–Sunday). If the value is not set in `LeagueConfig`, the solver uses a default of `2`.
- **AC-6.** The max-games-per-week limit is a hard constraint. The solver will leave a game unassigned rather than violate it. Unassigned games are reported in the existing partial-schedule output.
- **AC-7.** `planr schedule assign` prints the configured (or default) max-games-per-week value in its constraint summary output so organizers know the active limit.

### Scheduler — Minimum Rest Days

- **AC-8.** When `planr schedule assign` runs, no team has two games assigned fewer than `restDays + 1` calendar days apart. Specifically: if team T is assigned a game on date D, T may not be assigned any other game on dates D − restDays through D + restDays (inclusive). The constraint is purely calendar-day-based: a game on Sunday and a game on the following Monday are 1 calendar day apart and are therefore blocked when `restDays ≥ 1`. If the value is not set in `LeagueConfig`, the solver uses a default of `1`.
- **AC-9.** `restDays = 0` applies no rest constraint beyond the existing same-day prohibition (C3 constraint).
- **AC-10.** The minimum rest days limit is a hard constraint. The solver will leave a game unassigned rather than violate it.
- **AC-11.** `planr schedule assign` prints the configured (or default) minimum rest days value in its constraint summary output.

### Field Division Locks — `planr field lock`

- **AC-12.** `planr field lock add --field <name> --division <name> --start <YYYY-MM-DD> --end <YYYY-MM-DD>` adds a lock to the named field. All four options are required; missing any exits with code `1` and an error message.
- **AC-13.** Field and division names are matched case-insensitively. If either name does not match an existing field or division, the command exits with code `1` and an error message identifying which name was not found.
- **AC-14.** `--end` must not be before `--start`; if it is, the command exits with code `1` and an error message stating both dates.
- **AC-15.** If the new lock's date range overlaps with any existing lock on the same field (regardless of division), the command exits with code `1` and an error message identifying the conflicting lock(s) by their 1-based index. Two locks on the same field, same division, non-overlapping dates are allowed.
- **AC-16.** `planr field lock delete --field <name> --index <N>` removes the Nth lock (1-based) from the named field. If the field does not exist or the index is out of range, the command exits with code `1` and an error message.
- **AC-17.** `planr field lock list` prints all locks across all fields. `planr field lock list --field <name>` filters to a single field. Both forms print a table with columns: `FIELD`, `DIVISION`, `START`, `END`. Rows sort by field name then start date. If no locks exist (in the filtered scope), the command prints a descriptive empty-state message and exits `0`.

### Scheduler — Field Division Locks

- **AC-18.** When `planr schedule assign` enumerates candidate slots, a field on a given date is excluded from consideration for division D if any lock on that field covers that date and names a division other than D.
- **AC-19.** The locked division is not blocked — it may use the field normally during the lock period. Unlocked fields are unaffected.
- **AC-20.** `planr schedule assign` includes any field with at least one active division lock in its constraint summary, listing the locked field, locked division, and lock date range, so the organizer can verify locks are being applied.
- **AC-21.** The same lock logic applies in `estimateAvailableSlots`, so the feasibility check shown before the solver starts reflects the field restrictions.

### Data Model and Persistence

- **AC-22.** `LeagueConfig` gains two nullable `Integer` fields: `maxGamesPerWeek` and `minRestDays`. Both are `null` in existing files (treated as default). `League` schema version increments to `6`. The `LeagueStore` migration from v5 → v6 is a no-op marker; existing configs deserialize safely because `FAIL_ON_UNKNOWN_PROPERTIES` is disabled and the compact constructor normalizes nulls to `List.of()` where needed.
- **AC-23.** `Field` gains a `List<FieldDivisionLock>` field (new record: `divisionId`, `startDate`, `endDate`). The `Field` compact constructor normalizes a null list to `List.of()`. No separate migration step is needed; existing `Field` records without this key deserialize with an empty list.
- **AC-24.** `FieldDivisionLock` serializes `divisionId` as a UUID string and dates as `YYYY-MM-DD`, consistent with other date fields in the model.

### Error Handling

- **AC-25.** All I/O failures in any of the above commands exit with code `2`.

---

## Out of Scope

- Per-division max-games-per-week or rest-day overrides. These constraints are league-wide only.
- Locking a field to multiple divisions simultaneously for the same date range.
- Automatically regenerating or invalidating a `DRAFT`/`FINALIZED` schedule when constraints change.
- Displaying active division locks in `planr schedule status` or the schedule view output.
- Any changes to the Phase 1 team-schedule algorithm (`TeamScheduleService`). Phase 1 assigns matchups, not dates; these constraints are Phase 2 only.

---

## Open Questions

None — all questions resolved prior to implementation.

---

## Dependencies

- `LeagueConfig` model record must gain `maxGamesPerWeek` and `minRestDays` (`Integer`, nullable); `LeagueStore` adds a v5 → v6 no-op migration marker and increments `CURRENT_VERSION` to `6`.
- `Field` model record gains `List<FieldDivisionLock> divisionLocks`; new `FieldDivisionLock` record is introduced with fields `divisionId` (UUID), `startDate`, `endDate` (LocalDate).
- `ConfigCommand.SetCmd` gains `--max-games-per-week` and `--rest-days` options; `ConfigCommand.ShowCmd` is updated to display both values.
- New `FieldLockCommand` class (parallel to `FieldBlockCommand` and `FieldOverrideCommand`) with `add`, `delete`, `list` inner subcommands, registered under `FieldCommand`.
- `SchedulerService.enumerateAllSlots()` and `estimateAvailableSlots()` must filter out slots on locked fields for non-owning divisions.
- `SchedulerService.buildAndSolve()` must add two new CP-SAT constraint groups: a per-team-week sum `≤ maxGamesPerWeek` hard cap, and a per-team sliding-window gap `≥ restDays + 1` hard constraint.
