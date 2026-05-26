# Practice Scheduling — Pre-Season Field Assignment

**Date:** 2026-05-26  
**Status:** Finalized

---

## Problem Statement

planr currently schedules only regular-season and playoff games. Leagues run team practices before the season starts, and those practices compete for the same field inventory as games. Without first-class practice support, organizers must schedule pre-season field time manually and cannot enforce per-team targets or fairness constraints — exactly the kind of problem planr exists to solve.

---

## Proposed Solution

Add a `planr practice` top-level command group that mirrors the two-phase workflow of `planr schedule`:

1. **Phase 1 — Practice slot generation** (`planr practice generate`): For each division, compute the required practice slots (one per team per requested practice count) and persist them as a new `PracticeSchedule` entity in `league.json`.

2. **Phase 2 — Field assignment** (`planr practice assign`): Assign field/time slots to practice events using the existing CP-SAT solver, operating within each division's practice date window. Because divisions may have different practice windows and share field inventory, the solver runs across all divisions simultaneously — the same cross-division approach used by `planr playoff assign`.

Supporting commands: `planr practice status` to inspect current practice state, and `planr practice clear` to reset a division's practices back to uninitialized.

---

## User Stories

1. **As a league organizer**, I want to configure a practice window and a per-team practice count for each division, so that younger divisions with earlier starts get field time before older divisions whose season begins later.

2. **As a league organizer**, I want the scheduler to assign each team exactly one field slot per practice, so that two teams never share a field during practice time.

3. **As a league organizer**, I want the practice scheduler to respect `maxGamesPerWeek` and `minRestDays` within the practice window, so that teams are not overloaded with back-to-back practices in a single week.

4. **As a league organizer**, I want to view all assigned practice slots per division and per team, so that I can share the pre-season schedule with coaches.

5. **As a league organizer**, I want to reset and regenerate practices for a single division, so that I can correct a configuration mistake without disturbing other divisions.

---

## Acceptance Criteria

### Division Configuration

- **AC-1.** `planr division edit --name <name>` gains four new optional flags: `--practice-count <n>`, `--practice-start <YYYY-MM-DD>`, `--practice-end <YYYY-MM-DD>`, and `--practice-duration-minutes <n>`. All four are optional; omitting them leaves current values unchanged. The flags may be supplied individually or together.
- **AC-2.** `--practice-count` must be a positive integer (≥ 1) if provided. If the supplied value is 0 or negative, the command exits with code `1` and an error message.
- **AC-3.** `--practice-duration-minutes` must be a positive integer (≥ 1) if provided. If the supplied value is 0 or negative, the command exits with code `1` and an error message.
- **AC-4.** `--practice-end` must not be before `--practice-start`. If `--practice-end` is supplied without `--practice-start` (or vice versa), the existing stored value for the omitted field is used for the validation check. If the resulting combination is invalid, the command exits with code `1` and an error message.
- **AC-5.** Both `practiceStart` and `practiceEnd` must be strictly before `config.seasonStart`. If the resulting `practiceStart` or `practiceEnd` is on or after `seasonStart`, the command exits with code `1` and an error message identifying which date(s) violate the constraint. (When `seasonStart` is not yet configured, this validation is skipped.)
- **AC-6.** `planr division list` displays `practiceCount`, `practiceDurationMinutes`, `practiceStart`, and `practiceEnd` for each division. Fields that have not been set are shown as `--`.

### Practice Generation — `planr practice generate`

- **AC-7.** `planr practice generate` (no division flag) generates practice slots for **all** divisions that have `practiceCount`, `practiceStart`, `practiceEnd`, and `practiceDurationMinutes` set. Divisions missing any of these four values are skipped with a warning line printed to stdout: `Skipping <division>: practice configuration incomplete.`
- **AC-8.** If a division already has a `PracticeSchedule` in any state (`GENERATED` or `ASSIGNED`), that division is skipped with a warning: `Skipping <division>: practices already generated. Run planr practice clear --division <name> to reset.`
- **AC-9.** For each qualifying division, the command creates one practice slot per team per `practiceCount`. A division with T teams and `practiceCount = P` produces T × P practice slots total.
- **AC-10.** On completion, the command prints a summary line per processed division: `Generated <T×P> practice slots for <division> (<T> teams × <P> practices).` followed by a final line: `Practice generation complete: <D> divisions processed, <S> total slots created.`
- **AC-11.** If no divisions qualify (all missing configuration or all already generated), the command exits with code `1` and an error message.

### Field Assignment — `planr practice assign`

- **AC-12.** `planr practice assign` collects every division that has a `PracticeSchedule` in `GENERATED` or `ASSIGNED` state, clears all existing field assignments across those divisions, and runs a single CP-SAT field/time slot assignment. This is a full re-solve; prior assignments are never preserved.
- **AC-13.** If no divisions have a `PracticeSchedule` entity, the command exits with code `1` and an error message.
- **AC-14.** Each practice slot requires exactly one team and one field/time slot. No two practice slots may share the same field and time slot (the same exclusivity rule as regular-season games).
- **AC-15.** The solver operates within each division's `[practiceStart, practiceEnd]` window. Practice slots for different divisions are constrained to their respective windows; the solver respects each division's window independently.
- **AC-16.** Field availability, field blocks, field overrides, and field division locks are applied identically to the regular-season solver.
- **AC-17.** Field slot sizing for practices uses `practiceDurationMinutes` (from the division) rather than `gameDurationMinutes`.
- **AC-18.** `maxGamesPerWeek` (when set) limits the number of practices assigned to any team within a single calendar week during the practice window. Because the practice window ends before `seasonStart` (enforced by AC-5), practice-window weeks contain no game activity; the weekly cap applies to practices alone.
- **AC-19.** `minRestDays` (when set) is enforced between consecutive practice assignments for the same team within the practice window.
- **AC-20.** Partial assignment results are acceptable. If the solver cannot assign all practice slots within the available windows, unassigned slots are reported and the command exits with code `0`. All divisions with a `PracticeSchedule` entity transition to (or remain in) `ASSIGNED` state after the command completes.
- **AC-21.** The command outputs live progress lines and a constraint summary table in the same format as `planr schedule assign`. The final status line reads: `Practice field assignment complete: <S_assigned>/<S_total> slots assigned across <D> divisions.`
- **AC-22.** The practice date windows for different divisions may overlap. The solver handles overlapping windows correctly by treating the cross-division field exclusivity constraint globally.

### Viewing — `planr practice status`

- **AC-23.** `planr practice status --division <name>` prints all practice slots for the named division in a table: team name, slot number (1-of-P, 2-of-P, …), assigned date, assigned start time, assigned field name (or `UNASSIGNED`). If no `PracticeSchedule` exists for the division, the command exits with code `1` and an error message.
- **AC-24.** `planr practice status` (no flag) prints a one-line summary per division: name, practice state (`NOT_CONFIGURED`, `NOT_STARTED`, `GENERATED`, `ASSIGNED`), and assigned/total counts.

### Reset — `planr practice clear`

- **AC-25.** `planr practice clear --division <name>` removes the `PracticeSchedule` entity for the named division, returning it to `NOT_STARTED` state. The command prompts for confirmation before clearing.
- **AC-26.** If no `PracticeSchedule` exists for the division, the command exits with code `1` and an error message.

### Data Model and Persistence

- **AC-27.** `Division` gains four new nullable fields: `practiceCount` (Integer), `practiceDurationMinutes` (Integer), `practiceStart` (LocalDate), `practiceEnd` (LocalDate). All default to `null` when absent in existing JSON files.
- **AC-28.** A new `PracticeSchedule` record is added with fields: `divisionId` (UUID), `state` (`PracticeState` enum: `GENERATED`, `ASSIGNED`), and `slots` (`List<PracticeSlot>`).
- **AC-29.** A new `PracticeSlot` record is added with fields: `slotId` (UUID), `teamId` (UUID), `slotNumber` (int, 1-based index of this practice for the team), `assignedDate` (LocalDate, nullable), `assignedStartTime` (LocalTime, nullable), `assignedFieldId` (UUID, nullable).
- **AC-30.** `League` gains a `List<PracticeSchedule>` field. The compact constructor normalizes `null` to `List.of()`. `League` schema version increments. The `LeagueStore` migration is a no-op marker for the new version; existing files without the `practiceSchedules` key deserialize safely.

### Error Handling

- **AC-31.** All I/O failures in any of the above commands exit with code `2`.

---

## Out of Scope

- Per-team practice count overrides (all teams in a division receive the same count).
- Viewing or assigning practices by individual team rather than by division.
- Cross-period conflict checking between practices and regular-season assigned games. `practiceEnd < seasonStart` is enforced by AC-5, so overlap is impossible by construction.
- Tracking attendance or outcomes for individual practice sessions.
- Per-division `maxGamesPerWeek` or `minRestDays` overrides (league-level values apply uniformly).
- Combined weekly cap across practices and games: because the practice window is fully pre-season, a team will never have both a practice and a game in the same week. `maxGamesPerWeek` therefore applies to practices-only weeks in the practice window.

---

## Open Questions

None — all questions resolved prior to implementation.

---

## Dependencies

- `Division` record gains `practiceCount`, `practiceDurationMinutes`, `practiceStart`, `practiceEnd` fields; `DivisionCommand.Edit` gains corresponding flags.
- New `PracticeSchedule`, `PracticeSlot` model records; `PracticeState` enum.
- `League` record gains `List<PracticeSchedule> practiceSchedules`; `LeagueStore` adds a schema version migration marker and increments `CURRENT_VERSION`.
- New `PracticeCommand` top-level command class with `GenerateCmd`, `AssignCmd`, `StatusCmd`, and `ClearCmd` inner classes.
- `SchedulerService` (or a thin wrapper) must be reusable for practice assignment: it accepts `PracticeSlot` lists and per-division date windows in place of the league-level config dates, sizes field slots using `practiceDurationMinutes`, and treats each `PracticeSlot` as a single-team fixture (no home/away).
- `maxGamesPerWeek` and `minRestDays` enforcement logic must be applicable to practice-only activity windows, not just game activity.
