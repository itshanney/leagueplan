# Changelog

All notable changes to `planr` are documented here. Each entry references the product requirements document (PRD) in `features/` and the technical design document in `specs/` that drove it.

---

## [0.8.0] — Scheduling Constraints & Field Division Locks

**PRD:** `features/2026-05-25-scheduling-constraints.md`  
**Spec:** `specs/2026-05-25-scheduling-constraints.md`

Adds three new scheduling constraints: a per-team weekly game cap, a minimum rest-day gap between a team's games, and date-range field division locks. The first two are stored as nullable `Integer` fields on `LeagueConfig` and enforced as hard CP-SAT constraints inside `SchedulerService`. The third introduces a new `FieldDivisionLock` record on each `Field`; it is bidirectional — it excludes other divisions from the locked field and pins the owning division to it during the lock period (E1 errata). Schema advances from v5 to v6.

### Added

- **`planr config set --max-games-per-week <N>`** — Sets a hard cap on the number of games any team may be scheduled in a single ISO calendar week. Validated as a positive integer (≥ 1). Stored as a nullable `Integer` on `LeagueConfig`; null means "use the system default of 2." Merges with other config fields on each `set` call.

- **`planr config set --rest-days <N>`** — Sets the minimum number of calendar days that must separate any two games for the same team. Validated as a non-negative integer (≥ 0; 0 disables the rest constraint). Stored as a nullable `Integer` on `LeagueConfig`; null means "use the system default of 1."

- **`planr field lock add --field <name> --division <name> --start <YYYY-MM-DD> --end <YYYY-MM-DD>`** — Locks a field to a single division for an inclusive date range. Field and division names are resolved case-insensitively. Rejects malformed dates, end-before-start, unknown field or division, and any new range that overlaps an existing lock on the same field (lists conflicting lock indices in the error message). Bidirectional: during the lock period the locked field is unavailable to other divisions, and the owning division may only use its locked field (not unlocked fields).

- **`planr field lock delete --field <name> --index <N>`** — Deletes a lock by 1-based index. Confirms the deleted lock's field name, resolved division name, and date range. Errors if the field has no locks, or if the index is out of range.

- **`planr field lock list [--field <name>]`** — Lists all field division locks in a `FIELD`, `#`, `DIVISION`, `START`, `END` table sorted by field name then start date. Optional `--field` filter restricts output to one field. Shows a distinct empty-state message when no filter is applied vs. when the named field has no locks. Division names are resolved live; shows `[unknown]` if the division was deleted after the lock was created.

- **`FieldDivisionLock` record** — `(UUID divisionId, LocalDate startDate, LocalDate endDate)`. No `id` field; identified by field name + 1-based index within `field.divisionLocks()`, consistent with `FieldBlock`. Serialized by the existing `JavaTimeModule`.

- **`SchedulerService.DEFAULT_MAX_GAMES_PER_WEEK = 2`** and **`DEFAULT_MIN_REST_DAYS = 1`** — Public constants used by both the solver (as fallback defaults) and `ConfigCommand.ShowCmd` (to render the `(default)` label).

### Changed

- **`planr config show`** — Renders two new lines after `Season end:`: `Max games/week: <N>` and `Min rest days: <N>`. Each line appends `(default)` when the value has not been explicitly configured; no suffix when explicitly set.

- **`planr schedule assign`** — Prints active constraint config before the confirmation prompt: `Scheduling constraints: max N game(s)/week per team, min N rest day(s) between games.` After the constraint summary, prints any field division locks that overlap the season window.

- **`LeagueConfig` record** — Gains two new nullable fields: `Integer maxGamesPerWeek` (position 7) and `Integer minRestDays` (position 8). Compact constructor leaves both as `null` (does not normalize to defaults, so `config show` can distinguish "not set" from "explicitly set to the default value"). New mutation helpers: `withMaxGamesPerWeek(Integer)` and `withMinRestDays(Integer)`.

- **`Field` record** — Gains `List<FieldDivisionLock> divisionLocks` as the sixth parameter. Compact constructor normalizes `null` to `List.of()`. New mutation helpers: `withLockAdded(FieldDivisionLock)` and `withLockRemoved(int zeroBasedIndex)`.

- **`SchedulerService.buildAndSolve()`** — Two new hard CP-SAT constraint groups:
  - **C4 (max games per week):** for each `(team, ISO week)` pair, `sum(assigned vars) ≤ weekCap`. The existing soft `maxWeekLoad` minimization objective is preserved alongside the new hard cap.
  - **C5 (minimum rest days):** for each team and each pair of dates `(D, D+r)` where `r ∈ [1, restDays]`, adds `addAtMostOne` on the combined set of game vars for those two dates. Because C3 already limits each team to at most one game per day, each combined group has at most 2 literals — O(teams × dates × restDays) constraints, each trivial.

- **`SchedulerService.enumerateAllSlots()` and `estimateAvailableSlots()`** — Both methods now apply bidirectional field lock filtering. In addition to skipping fields locked to other divisions, they skip fields that are not locked to the current division when that division has an active lock on some other field for that date (i.e., the division is pinned to its own locked field).

- **`FieldCommand`** — Registers `FieldLockCommand` as a subcommand. `AddCmd` updated to pass an empty `divisionLocks` list to the `Field` constructor. `EditCmd.applyEdits()` threads `field.divisionLocks()` through unchanged. `ListCmd` adds a `LOCKS` column.

- **`LeagueStore`** — v<4 migration updated to pass an empty `divisionLocks` list to `Field` constructors. New v5→v6 migration block: no-op version stamp. `Field.divisionLocks` absent from older JSON is normalized to `List.of()` by the compact constructor; `LeagueConfig.maxGamesPerWeek` and `minRestDays` absent from older JSON deserialize as `null` (use default).

- **`League.CURRENT_VERSION`** — Advanced from `5` to `6`.

### Tests

- **`FieldLockCommandTest`** — 22 tests across `Add`, `Delete`, and `ListCmd` nested classes: success, lock number increment, field/division not found, invalid date formats, end-before-start, single-day lock accepted, overlap detection (reports conflicting index numbers), consecutive non-overlapping locks accepted, case-insensitive field and division lookup, empty-list error on delete, index out of range, field-filter on list, filter for nonexistent field, I/O errors throughout.

- **`ConfigCommandTest`** additions — 13 new tests: `--max-games-per-week` valid/zero/negative; `--rest-days` valid (including 0)/negative; both options together; persistence to `config show`; `config show` renders `Max games/week` and `Min rest days` fields; `(default)` label when unset; no `(default)` label when explicitly set for each field.

- **`SchedulerServiceTest`** additions — 13 new tests:
  - **C4:** configured week cap of 1 is enforced across all teams; default cap of 2 is respected.
  - **C5:** default 1-day rest is enforced (no back-to-back games); `restDays=0` produces a complete valid schedule.
  - **Field division lock (exclusion side):** locked field gives 0 slots to non-owning division; locked field gives same slots to owning division as an unlocked field; partial-period lock opens the field to other divisions outside the lock window; locked field's games belong only to the owning division.
  - **E1 (bidirectional pinning):** all owning division games assigned to the locked field only; adding an unlocked field does not increase slot count for the pinned division; pinning releases after the lock date range expires (slot count doubles in the post-lock period); division locked to two different fields in sequential periods receives slots from each field only during its respective period; two divisions each pinned to their own field use only their assigned field — no game lands on the shared open field.

---

## [0.7.0] — League-Wide Day-of-Week Availability Windows & Blocked Days

**PRD:** `features/2026-05-25-league-wide-availability-config.md`  
**Spec:** `specs/2026-05-25-league-wide-availability-config.md`

Adds two new league-wide availability controls that apply to all fields simultaneously: recurring day-of-week open windows (e.g., Wednesdays open at 16:00 instead of the global sunrise) and blocked days of the week (e.g., no availability on Sundays). Both are stored in `LeagueConfig` and are respected by the Phase 2 scheduler via a four-level precedence rule: `FieldDateOverride` → blocked day → day-of-week window → global sunrise/sunset. A `FieldDateOverride` can still rescue an individual date on an otherwise-blocked day. Schema advances from v4 to v5.

### Added

- **`planr config dow set --day <DAY> --start <HH:mm> --end <HH:mm>`** — Sets a recurring availability window for all fields on the specified day of the week. Replaces any existing window for that day. Accepts full day names or 3-letter abbreviations, case-insensitively. Rejects invalid day names, malformed times, and end ≤ start. Prints a conflict warning (with count) when field-level blocks or overrides exist on matching dates within the configured season; scans all entries when no season is set.

- **`planr config dow clear --day <DAY>`** — Removes the day-of-week window for the specified day. Exits 1 if no window exists for that day.

- **`planr config dow list`** — Lists all configured day-of-week windows in a `DAY / OPEN / CLOSE` table sorted Monday through Sunday. Shows an empty-state message when none are configured.

- **`planr config blockday add --day <DAY>`** — Marks a day of the week as unavailable for all fields. Exits 1 if the day is already blocked. Prints a conflict warning when field-level entries exist on matching dates within the season; the warning explicitly notes that `FieldDateOverride` entries on specific dates still take precedence over the block.

- **`planr config blockday remove --day <DAY>`** — Removes a day-of-week block. Exits 1 if the day is not currently blocked.

- **`planr config blockday list`** — Lists all blocked days sorted Monday through Sunday. Shows an empty-state message when none are configured.

- **`DayOfWeekWindow` record** — `(DayOfWeek day, LocalTime openStart, LocalTime openEnd)`. Stored in `LeagueConfig.dowWindows`.

- **`DayParser` utility** — Package-private helper shared by `ConfigDowCommand` and `ConfigBlockdayCommand`. `parse(String)` accepts full day names and 3-letter abbreviations case-insensitively, returning `Optional<DayOfWeek>`. `displayName(DayOfWeek)` returns title-case names (e.g., "Wednesday"). `hint()` returns the accepted-format description used in error messages.

### Changed

- **`planr config show`** — Now renders two additional sections after the four existing fields: "Day-of-week windows:" (sorted Mon→Sun, "(none)" when empty) and "Blocked days of week:" (sorted Mon→Sun, "(none)" when empty).

- **`LeagueConfig` record** — Gains two new fields: `List<DayOfWeekWindow> dowWindows` and `List<DayOfWeek> blockedDays`. The compact constructor normalizes both `null` to `List.of()`, ensuring safe deserialization of v4 files that omit these keys. New mutation helpers: `withDowWindowSet(DayOfWeekWindow)`, `withDowWindowRemoved(DayOfWeek)`, `withBlockedDayAdded(DayOfWeek)`, `withBlockedDayRemoved(DayOfWeek)`. `LeagueConfig.empty()` initializes both lists to `List.of()`.

- **`SchedulerService`** — The `resolveOpenWindow(LeagueConfig, Field, LocalDate)` private helper now applies the four-level precedence rule. Both `enumerateAllSlots()` (used by the solver) and `estimateAvailableSlots()` (used by pre-Phase-2 feasibility warnings) delegate to this helper. A blocked day with no field-level override yields `null` (no slots for that date); a day-of-week window replaces global sunrise/sunset when no override or block applies.

- **`planr config set`** — Preserves `dowWindows` and `blockedDays` when merging config values; previously-stored windows and blocks are not cleared by a `config set` call.

- **Schema v4→v5 migration** — `LeagueStore.load()` now migrates v4 files: version is stamped to 5 and the file is written back to disk. No structural data transformation is required because the compact constructor normalizes the missing keys to empty lists during deserialization. A version bump prevents re-running the migration on subsequent loads.

- **`League.CURRENT_VERSION`** — Advanced from `4` to `5`.

### Tests

- **`LeagueConfigTest`** — 12 tests: compact constructor null normalization for `dowWindows` and `blockedDays`; all four mutation helpers (`withDowWindowSet` add/replace/preserve-others/immutability, `withDowWindowRemoved` remove/no-op/preserve-others, `withBlockedDayAdded` add/append/immutability, `withBlockedDayRemoved` remove/no-op/preserve-others).

- **`DayParserTest`** — 22 tests: full names for all 7 days, abbreviations for all 7 days, case insensitivity (uppercase, title-case, mixed), whitespace trimming, invalid inputs (null, empty, garbage, partial, numeric), `hint()` content, `displayName()` title-case for all 7 days.

- **`ConfigDowCommandTest`** — 17 tests across `set`, `clear`, and `list` nested classes: success with full/abbreviated name, replace existing window, unrecognized day, invalid start/end time, end ≤ start, singular/plural conflict warning, no warning when no conflicts, season-scoped warning, all-entries warning when no season, I/O error (exit 2); clear success/not-found/persistence; list empty state/headers/sort order/times shown.

- **`ConfigBlockdayCommandTest`** — 17 tests across `add`, `remove`, and `list` nested classes: success with full/abbreviated name, duplicate rejection, unrecognized day, persistence, singular conflict warning, precedence note in warning, no-warning case, I/O error; remove success/persistence/not-blocked/unrecognized day/preserves others; list empty state/heading/sort order.

- **`ConfigShowDowBlockdayTest`** — 11 tests: DOW heading always present, `(none)` when empty, configured windows shown with times, sorted Mon→Sun; blocked-days heading, `(none)`, day names, sorted; both sections together when empty, both populated, coexistence with existing four config fields.

- **`SchedulerServiceDowTest`** — 10 tests using `estimateAvailableSlots()` (no CP-SAT invocation): blocked day → 0 slots, blocked day in multi-day season reduces count by exactly one day, all 7 days blocked → 0; `FieldDateOverride` rescues slots on a blocked day and uses the override window (not global sunrise/sunset); DOW window narrows slot count to configured hours, other-day window doesn't affect the tested day, window applied consistently across all matching days; full precedence chain (override beats DOW window, DOW window beats global).

- **`LeagueStoreTest`** — 2 new migration tests: v4 file with no `dowWindows`/`blockedDays` keys migrates to v5 with empty lists; migrated file is written back so a second load reads v5 without re-migrating.

---

## [0.6.1] — Schedule Lifecycle, Viewing, Export & Backward Compatibility

**PRD:** `features/2026-05-17-league-planner-core-scheduling-v2.md`  
**Spec:** `specs/2026-05-24-schedule-lifecycle-view-export-migration.md`

Closes out the v2 scheduling PRD with the remaining four sections: schedule lifecycle (finalize + game override/edit), schedule viewing (tabular `planr schedule view` with division/team filters), schedule export (`planr schedule export` JSON to stdout), and backward compatibility (v3→v4 schema migration that drops legacy field availability windows).

### Added

- **`planr schedule finalize`** — Promotes a Draft schedule to Finalized (irreversible). Prints a warning and requires the user to type `yes` at the prompt before writing. Exit code `1` if not in DRAFT state; exit code `1` if the user types anything other than `yes`.

- **`planr schedule game override`** — Available in FINALIZED state only. Accepts any combination of `--date`, `--start`, `--field`, `--home`, `--away` to mutate a single game. Non-blocking conflict check warns to stderr when the new time/field overlaps another game (15-minute buffer) but saves regardless. No constraint re-validation is performed.

- **`planr schedule game edit`** — Available in TEAM_SCHEDULE and DRAFT states. `--home <team>` or `--away <team>` swaps home/away for a given game ID. The `game edit` command is blocked in FINALIZED state (use `game override` instead).

- **`planr schedule view`** — Renders a tabular schedule to stdout. Routes automatically: shows the matchup table if state is TEAM_SCHEDULE, shows the full field/time schedule if state is DRAFT or FINALIZED. Optional `--division` and `--team` filters (case-insensitive). The current round or date range is shown in the header. Games where the filtered team is home are marked with `*` in the position column.

- **`planr schedule export`** — Serializes the schedule to a JSON array on stdout; game count annotation goes to stderr (redirectable with `>`). Routes automatically to team schedule format in TEAM_SCHEDULE state. Full schedule format includes `date`, `start_time`, `field_name`, `home_team`, `away_team`, `division_name`, and `status`. Team schedule format includes `game_number` (integer), `home_team`, `away_team`, and `division_name`; the `--team-schedule` flag forces this path in DRAFT/FINALIZED state. Export is blocked in FINALIZED state when `--team-schedule` is passed.

- **`ScheduleState` enum** — `NONE`, `TEAM_SCHEDULE`, `DRAFT`, `FINALIZED`. Derived from `(league.teamSchedule(), league.schedule())` nullability via `ScheduleState.of(league)`; never persisted.

- **`ScheduleStatus` enum** — `DRAFT`, `FINALIZED`. Persisted inside `Schedule.status`.

### Changed

- **Schema v3→v4 migration** — `LeagueStore.load()` now migrates v3 files: per-field `availabilityWindows` keys are dropped (silently ignored by Jackson), `Field.blocks` and `Field.dateOverrides` are initialized to empty lists, and `LeagueConfig.empty()` is added if absent. A one-time stderr warning ("Field availability windows…") is printed on the first load of a pre-v4 file; subsequent loads of the now-v4 file produce no warning. The migrated file is written back to disk immediately after migration.

- **`planr schedule assign`** — Blocked in FINALIZED state (exit code `1`).

### Tests

- **`ScheduleCommandTest`** — 20 new tests across `GameEdit`, `Assign`, `Finalize`, `View`, `Export`, and `GameOverride` nested classes:
  - `GameEdit`: fails when no team schedule exists, fails in FINALIZED state, fails when team not in game, no-op when team already home, swaps home/away in DRAFT, exits on corrupted data, succeeds in DRAFT state, DRAFT status preserved after edit
  - `Assign`: fails when schedule is FINALIZED
  - `Finalize`: prints irreversibility warning before the confirmation prompt
  - `View`: shows FINALIZED status in header, shows TEAM_SCHEDULE status in header, renders matchup table with `--team-schedule` flag in FINALIZED state, division filter is case-insensitive, team filter is case-insensitive
  - `Export`: auto-exports team schedule in TEAM_SCHEDULE state, team schedule JSON omits date/time/field, `game_number` is a numeric integer, stderr annotation mentions "team schedule"
  - `GameOverride`: succeeds with out-of-season date (no constraint re-validation), multiple option overrides in one call succeed

- **`LeagueStoreTest`** — 4 new migration and warning tests:
  - `load_migratesV3ToV4ClearingFieldCollections`: v3 JSON with `availabilityWindows` → v4 with empty `blocks` and `dateOverrides`
  - `load_printsMigrationWarningToStderrOnPreV4File`: stderr contains "Field availability windows" on first v3 load
  - `load_noPrintWarningForNativeV4File`: natively-created v4 file produces no migration warning
  - `load_doesNotPrintWarningOnSubsequentLoadOfV4File`: second load of an already-migrated v4 file produces no warning

---

## [0.6.0] — Phase 2 Partial Schedules, Solver Progress & Constraint Summary

**PRD:** `features/2026-05-17-league-planner-core-scheduling-v2.md`  
**Spec:** `specs/2026-05-23-phase2-field-assignment.md`

Upgrades Phase 2 field assignment in three areas: the solver now saves a partial draft instead of failing when slots are insufficient; live `[M:SS]` progress lines stream to stdout during the solve; and a per-division constraint summary is printed after every run. The solver time budget extends from 60 to 300 seconds.

### Added

- **Partial schedule support** — `planr schedule assign` no longer returns a hard failure when any division has fewer available field slots than games. The CP-SAT model is updated from a hard `addExactlyOne` per fixture to a soft `addAtMostOne` plus an `isAssigned` BoolVar. The objective is changed to a weighted maximize: `bigM × totalAssigned − maxWeekLoad`, where `bigM = totalFixtures + 1` guarantees lexicographic dominance (more assigned games always beats better week-load balance). The solver assigns as many games as possible and saves a Draft regardless. Exit code remains 0 for partial schedules.

- **Live solver progress output** — Four timestamped `[M:SS]` lines are streamed to stdout during Phase 2:
  - `[0:00] Phase 2 started. N games across D division(s).` — emitted by `AssignCmd` before calling the service.
  - `[0:XX] Feasibility check passed. Solver started.` or `[0:XX] Feasibility check: <division> deficit (N games, M slots). Solver started.` — emitted after slot enumeration.
  - `[M:SS] Solver progress: ~N% of time budget used.` — emitted by `ProgressCallback.onSolutionCallback()` at 25 %/50 %/75 % of the time budget using `wallTime()`.
  - `[M:SS] Solver complete. N of T games assigned (target-met|partial[, optimal]).` — emitted after `solver.solve()` returns.

- **Constraint summary** — Always printed after every Phase 2 run. Tabular per-division output showing games requested, slots available, used/available ratio, and status (`target-met` or `partial (N unassigned)`). Footer line reads `"All targets met."` or `"Warning: N game(s) could not be assigned."`.

- **Per-team shortfall** — Printed only when `targetMet == false`. For each division with unassigned games, lists every team that fell short with its `assigned/requested` game count (e.g., `Cardinals: 4/6 games assigned`). Computed in `AssignCmd` by diffing `league.teamSchedule()` against `result.games()`.

- **`DivisionSummary` record** — New transient record in the `scheduler` package: `divisionName`, `gamesRequested`, `gamesAssigned`, `slotsAvailable`. Derived methods: `targetMet()` (`gamesAssigned == gamesRequested`), `usedAvailRatio()`. Not persisted to `league.json`.

### Changed

- **`ScheduleResult.Success`** — Gains two new fields: `boolean targetMet` and `List<DivisionSummary> divisionSummaries`. The `ScheduleResult.success(...)` factory method updated to accept all four parameters.

- **Solver time limit** — `SOLVER_TIME_LIMIT_SECONDS` extended from 60 to 300. The solver terminates early when CP-SAT proves OPTIMAL; the extended budget provides headroom for larger leagues.

- **C2 (field non-overlap) constraint** — Rewritten from a pairwise O(N²) loop over conflicting `(field, date)` game pairs to a time-tick-bucket approach. Each `BoolVar` is registered under every 15-minute tick it occupies; one `addAtMostOne` is added per `(fieldId, date, tick)` bucket. This bounds C2 to at most `numFields × numDays × ticksPerDay` constraints regardless of fixture count, eliminating the OOM error that occurred at scale.

- **Final status line** — Updated to a three-case format:
  - Target met, optimal: `Draft schedule saved: N games assigned (target-met, optimal distribution).`
  - Target met, not optimal: `Draft schedule saved: N games assigned (target-met, good distribution — optimizer ran up to 300s).`
  - Partial: `Draft schedule saved: N of M games assigned (partial).`

- **Hard failure cases** — `UNKNOWN` solver status (timed out before finding any feasible solution) now returns `ScheduleResult.Failure` with a diagnostic message and does not save a draft. `INFEASIBLE` status (theoretically impossible with `addAtMostOne`) also returns `Failure` with an internal-error message.

### Tests

- **`SchedulerServiceTest`** — 8 new tests in the new `DivisionSummary accuracy` section: `targetMet` correctness, per-division summary presence, `gamesRequested` fixture count accuracy, `gamesAssigned` vs actual game count consistency, `slotsAvailable` vs `estimateAvailableSlots()` agreement, single-slot partial schedule (1 game assigned from 12 fixtures), zero-slot division (90-min game in 60-min window), and alphabetical summary ordering.

- **`ScheduleCommandTest`** — New `@Nested` class `ProgressAndConstraintSummary` with 11 tests covering: Phase 2 start and solver-complete progress lines, constraint summary presence, `target-met` and `All targets met` footer on success, `partial` status and `Teams that fell short` section on partial schedules, `N/M games assigned` fraction format, absence of shortfall output on full assignment, and `Draft schedule saved` / `(partial)` final status line variants.

---

## [0.5.1] — Phase 1 Algorithm Corrections

**Spec:** `specs/2026-05-18-phase1-team-schedule.md` (Errata E1 and E2)

Corrects two errors in the Phase 1 team schedule algorithm that shipped in 0.5.0.

### Fixed

- **Minimum target validation (E1):** `planr schedule generate` now correctly rejects a per-division target below `N−1` (the minimum for one full round-robin), not `2*(N−1)` as previously enforced. For a 4-team division the minimum is now 3, not 6.

- **Single round-robin, not double (E2):** Phase 1 now generates a *single* round-robin (Pass A only, `N*(N-1)/2` games per division) and relies on fill rounds to reach the target. The former implementation generated a double round-robin (Pass A + Pass B, `N*(N-1)` base games), silently over-generating games for any target below `2*(N−1)`.

- **Fixture identity in the Phase 2 solver (E2 follow-on):** `Fixture` gains a `gameId: UUID` field (carried from `TeamGame.id()`). The CP-SAT model now keys per-fixture constraints on `gameId` rather than structural equality. Previously, fill games with the same matchup direction as an earlier round-robin game collapsed into a single solver constraint, causing the solver to under-schedule fill-heavy leagues.

---

## [0.5.0] — Two-Phase Schedule Generation

**PRD:** `features/2026-05-17-league-planner-core-scheduling-v2.md`  
**Spec:** `specs/2026-05-18-phase1-team-schedule.md`

Splits schedule generation into two explicit user-controlled phases. Phase 1 produces a reviewable team schedule (matchups with home/away assignments) using a deterministic single round-robin plus fill rounds algorithm. Phase 2 consumes that schedule and runs the OR-Tools CP-SAT solver to assign dates, times, and fields. Organizers can inspect and edit home/away assignments between phases.

### Added

**`planr schedule generate`** (Phase 1 — replaces the former single-step `generate`)
- Generates a team schedule for every eligible division using the circle-method single round-robin algorithm (`N*(N-1)/2` games per division). Additional fill rounds bring each team's game count up to the division's `targetGamesPerTeam`, with home/away assignments balanced by tracking each team's running imbalance.
- Requires season start/end dates in `planr config` and at least one division with ≥ 2 teams. Per-division target must be ≥ N−1 (minimum for one full round-robin).
- Prints fill-round progress logs, then a tabular team schedule (`#`, `HOME`, `AWAY`, `DIVISION`), then guidance for the next steps.
- Re-running when a team schedule or draft already exists prompts for confirmation before discarding the existing work. Blocked if a finalized schedule exists.
- Status after Phase 1: `TEAM_SCHEDULE`.

**`planr schedule assign`** (Phase 2 — field/time assignment)
- Reads the confirmed team schedule and runs the CP-SAT solver to assign each fixture to a date, time slot, and field. Season dates and sunrise/sunset hours are read from `planr config`.
- Displays a condensed team schedule summary and per-division feasibility estimates before prompting for confirmation.
- On success, saves a **Draft** schedule. Status after Phase 2: `DRAFT`.
- Enforces the same three constraints as before (C1 exact assignment, C2 no field overlap with 15-minute buffer, C3 no team double-booked per day) and the same weekly-load-balancing objective.

**`planr schedule game edit <number> --home <team>`**
- Available in `TEAM_SCHEDULE` and `DRAFT` states.
- Swaps home and away for the specified game, designating the named team as home. No-op if the team is already home. Error if the team is not participating in that game.

**`planr schedule view --team-schedule`**
- Available in `TEAM_SCHEDULE` and `DRAFT` states. Shows the matchup-only table (no dates or fields) even after Phase 2 has run.

**`planr schedule export --team-schedule`**
- Available in `TEAM_SCHEDULE` and `DRAFT` states. Exports a JSON array with `game_number`, `home_team`, `away_team`, `division_name` fields.

### Changed

- **`planr schedule status`** — new `TEAM_SCHEDULE` state shows per-division game counts alongside the division's target and team count. The `DRAFT` / `FINALIZED` output is unchanged.
- **`planr schedule view`** — when state is `TEAM_SCHEDULE`, shows the matchup-only table automatically (no date/time/field columns).
- **`planr schedule export`** — when state is `TEAM_SCHEDULE`, automatically exports the team schedule JSON (no `--team-schedule` flag required).

### New model types
- `TeamGame` record — a single fixture in the team schedule: `id`, `gameNumber`, `homeTeamId`, `homeTeamName`, `awayTeamId`, `awayTeamName`, `divisionId`, `divisionName`, `gameDurationMinutes`. Includes `withSwappedHomeAway()`.
- `TeamSchedule` record — an ordered list of `TeamGame` records. Includes `withGameReplaced(gameNumber, replacement)` and `findGame(gameNumber)`.
- `ScheduleState` enum — `NONE`, `TEAM_SCHEDULE`, `DRAFT`, `FINALIZED`; derived from `(league.teamSchedule(), league.schedule())` nullability.
- `TeamScheduleResult` sealed interface — `Success(schedule, fillRoundLogs)` or `Failure(message)`.
- `TeamScheduleService` — encapsulates the Phase 1 algorithm (circle-method single round-robin + fill rounds).

### Changed (model / persistence)
- `League` record: gains `teamSchedule: TeamSchedule` (nullable, position 4, between `fields` and `schedule`). All `withX(...)` helpers forward it. New helpers: `withTeamSchedule(TeamSchedule)`, `withTeamScheduleCleared()` (nulls both `teamSchedule` and `schedule`).
- `SchedulerService`: `generate(League, LocalDate, LocalDate)` replaced by `assign(League)` (reads season dates from `league.config()`, reads fixtures from `league.teamSchedule()`). New public method `estimateAvailableSlots(League, UUID, int)` for pre-Phase-2 feasibility warnings.
- `LeagueStore` migration constructors updated to pass `null` for `teamSchedule`; no version bump required (existing v4 files load with `teamSchedule = null` via `FAIL_ON_UNKNOWN_PROPERTIES=false`).

---

## [0.4.0] — v2 Entity Management

**PRD:** `features/2026-05-17-league-planner-core-scheduling-v2.md`  
**Spec:** `specs/2026-05-17-v2-entity-management-cli.md`

This slice upgrades the entity management layer to satisfy the v2 product requirements. The availability model is redesigned from the ground up: fields are now open by default during configurable league-wide daylight hours, and organizers record only the exceptions. The schema advances from version 3 to version 4; prior availability windows are discarded on migration.

### Added

**League config (`planr config`)**
- `planr config set` — set league-level sunrise and sunset times (`HH:mm`) and optional season start/end dates (`YYYY-MM-DD`). Each option is independent; calling `set` a second time merges with the existing config rather than replacing it. Validates that sunset is after sunrise (when both are provided in the same call) and that season end is after season start.
- `planr config show` — display the current league configuration; shows `(not set)` for any field not yet configured.
- Sunrise and sunset times define the default open window applied to every field on every calendar day in the season. They are required prerequisites for schedule generation.

**Field blocks (`planr field block`)**
- `planr field block add <field> --date --start --end` — block a specific date/time range on a field. Multiple blocks per field are allowed. Each block is a date-specific exception that subtracts time from the field's effective open window.
- `planr field block edit <field> <number> [--date] [--start] [--end]` — edit a block by 1-based index; unspecified fields are preserved from the existing block.
- `planr field block delete <field> <number>` — delete a block by 1-based index.
- `planr field block list <field>` — list all blocks for a field in a tabular `#`, `DATE`, `START`, `END` format.

**Per-date open window overrides (`planr field override`)**
- `planr field override add <field> --date --start --end` — override the league-level sunrise/sunset for a specific field on a specific date. At most one override per field per date; adding a second override for the same date is rejected.
- `planr field override edit <field> <number> [--date] [--start] [--end]` — edit an override by 1-based index. If `--date` is changed, validates uniqueness against the remaining overrides.
- `planr field override delete <field> <number>` — delete an override by 1-based index.
- `planr field override list <field>` — list all per-date overrides for a field: `#`, `DATE`, `OPEN START`, `OPEN END`.

**New model records**
- `LeagueConfig` — holds `sunriseTime`, `sunsetTime`, `seasonStart`, `seasonEnd`; top-level on `League`.
- `FieldBlock` — date-specific blocked time range on a field.
- `FieldDateOverride` — per-date replacement for the league-level open window on a specific field.

### Changed

**Division management**
- `planr division add` now requires `--target <n>` (positive integer): the target number of games per team for the season. Exit code 1 if omitted or ≤ 0.
- `planr division edit` now accepts `--target <n>` to update the target; the error message for no options provided now lists `--target` alongside `--name` and `--duration`.
- `planr division list` now shows a `TARGET` column. Divisions migrated from earlier schema versions have `targetGamesPerTeam = 0`, displayed as `0*` with a trailing warning directing the organizer to configure a target.

**Field management**
- `planr field delete` — cascade message now reports blocks and overrides removed (e.g., `Field "X" deleted (2 block(s), 1 override(s) removed).`).
- `planr field list` — `WINDOWS` column replaced by `BLOCKS` and `OVERRIDES` columns.
- `planr field window` subcommand group retired entirely. All day-of-week recurring windows (`AvailabilityWindow`) are removed.

**Schedule generation**
- Precondition for `planr schedule generate` changed: a field with availability windows is no longer required. Instead, the league config must have `sunriseTime` and `sunsetTime` set. Specific error: `"Error: Schedule generation requires league config with sunrise and sunset times. Run 'planr config set --sunrise HH:mm --sunset HH:mm' first."`
- The scheduler's slot enumeration algorithm is rewritten: for each date in the season range, for each field, the effective open window is determined by (1) the field's `FieldDateOverride` for that date if present, otherwise (2) the league-level sunrise/sunset. `FieldBlock` entries for that date are subtracted from the open window to produce available sub-windows; slots are enumerated within each sub-window at 15-minute grid intervals.

**Data model**
- `Division` record gains `targetGamesPerTeam: int` (position 3, before `teams`).
- `Field` record: `List<AvailabilityWindow> windows` replaced by `List<FieldBlock> blocks` and `List<FieldDateOverride> dateOverrides`.
- `League` record gains `config: LeagueConfig` (position 1, after `version`). All `withX(...)` helpers forward the config field.
- `League.CURRENT_VERSION` advanced from `3` to `4`.
- `League.empty()` initializes `config` to `LeagueConfig.empty()` (all fields null).

**Persistence**
- `LeagueStore` migration chain extended: v3 → v4 strips all `AvailabilityWindow` data, initializes `LeagueConfig.empty()`, bumps the version field, writes the migrated file atomically, and prints a one-time warning to stderr: `Warning: Field availability windows from a previous version have been removed. Please configure field blocks for the new season.`
- v1 → v2 and v2 → v3 migration guards updated to pass `null` for the new `config` parameter; the subsequent v3 → v4 guard normalizes it.

### Removed
- `AvailabilityWindow` record — deleted.
- `FieldWindowCommand` — deleted. All `planr field window` subcommands no longer exist.

---

## [0.3.0] — Schedule Generation — 2026-05-16

**PRD:** `features/2026-05-15-league-planner-core-scheduling.md` (Schedule Generation, Lifecycle, Viewing, Export sections)  
**Spec:** `specs/2026-05-16-schedule-generation-cli.md`

Adds the full schedule workflow to the `planr` CLI, delegating all constraint satisfaction to the Google OR-Tools CP-SAT solver. The `League` record gains a nullable `Schedule` field and the JSON schema advances to version 3.

### Added

**`planr schedule generate --start <date> --end <date>`**
- Generates a double round-robin (every ordered team pair plays exactly once) for each eligible division.
- Assigns each fixture to a date, time slot, and field using the OR-Tools CP-SAT solver with a 60-second wall-clock budget. If OPTIMAL is proven within the budget, the summary notes it; otherwise it reports "good distribution."
- Enforces three hard constraints:
  - **C1** — each fixture assigned exactly once.
  - **C2** — no two games on the same field overlap (including the 15-minute buffer after each game).
  - **C3** — no team plays more than once on the same calendar day.
- Objective: minimise the maximum number of games any team plays in a single ISO calendar week (spreads the season evenly).
- Pre-solve feasibility check: counts available slots per division and reports infeasibility (with division-level detail) before invoking the solver if slots < fixtures.
- Requires: at least one division with ≥ 2 teams, at least one field with at least one availability window, and a valid season date range.
- Produces a **Draft** schedule; replaces any existing draft silently.
- Prints: `Draft schedule generated: N games across D division(s) (qualifier).`
- Exit codes: 0 (success), 1 (validation or infeasibility), 2 (I/O error).

**`planr schedule status`**
- Shows: schedule status (DRAFT / FINALIZED), season date range, total game count, and per-division game counts.

**`planr schedule finalize`**
- Promotes a Draft to FINALIZED after an interactive `yes` confirmation.
- Prints a warning that finalization is irreversible. A finalized schedule cannot be regenerated.

**`planr schedule view [--division <name>] [--team <name>] [--field <name>]`**
- Tabular view of the schedule: `#`, `DATE`, `START`, `FIELD`, `HOME`, `AWAY`, `DIVISION`.
- Supports optional filters by division, team, or field; validates that filter values refer to known entities.
- Games overridden after finalization are marked with `*` in the `#` column.

**`planr schedule export`**
- Writes a JSON array to stdout; each element contains `date`, `start_time`, `field_name`, `home_team`, `away_team`, `division_name`, and `status` (`"draft"` or `"finalized"`).
- Game count printed to stderr.

**`planr schedule game override <number> [--date] [--start] [--field] [--home] [--away]`**
- Overrides an individual game on a **finalized** schedule. Any combination of fields may be changed. Teams are resolved within the game's original division first, then league-wide.
- Non-blocking field-conflict warning printed to stderr if the overridden game now overlaps another game at the same field on the same date (including the 15-minute buffer).
- Marks the game as `overridden = true`, surfaced in `schedule view` as the `*` suffix.

**New model types**
- `Schedule` record — holds status, season dates, and the ordered list of `ScheduledGame` records.
- `ScheduleStatus` enum — `DRAFT`, `FINALIZED`.
- `ScheduledGame` record — a fully denormalised game: UUID, date, start time, field id/name, home team id/name, away team id/name, division id/name, game duration, and overridden flag. Includes `withOverride(...)` for partial mutation.
- `SchedulerService` — encapsulates fixture generation (circle-method round-robin), slot enumeration (per-field, per-day, respecting availability windows), and the CP-SAT model build/solve loop.
- `Fixture` record — a (gameId, homeTeamId, awayTeamId, divisionId, gameDurationMinutes) tuple used internally by the solver. `gameId` carries the source `TeamGame.id()` and is used as the per-fixture constraint key so repeated matchup directions (fill games) remain distinct.
- `Slot` record — a (date, fieldId, fieldName, startTime) tuple used internally by the solver.
- `ScheduleResult` sealed interface — `Success(games, optimal)` or `Failure(message)`.

**Build**
- `com.google.ortools:ortools-java:9.10.4067` added to `dependencies`.

### Changed
- `League` record: gains `schedule: Schedule` (nullable, position 4). All `withX(...)` helpers forward it.
- `League.CURRENT_VERSION` advanced from `2` to `3`.
- `LeagueStore`: adds `Schedule` / `ScheduledGame` round-trip support; v2 → v3 migration adds `null` schedule to existing files.

---

## [0.2.0] — Field Management — 2026-05-16

**PRD:** `features/2026-05-15-league-planner-core-scheduling.md` (Field Management section)  
**Spec:** `specs/2026-05-16-field-management-cli.md`

Adds field management (`planr field`) and recurring availability windows (`planr field window`) to the CLI. The `League` record gains a `fields` list and the JSON schema advances to version 2.

Also added in this commit: `CLAUDE.md` (project guidance for Claude Code), `README.md` (user-facing documentation), and a development `league.json` artifact (not committed going forward).

### Added

**`planr field add <name> [--address <address>]`**
- Creates a field with a unique name (case-insensitive) and optional address.

**`planr field edit <name> [--name <new-name>] [--address <address>]`**
- Renames a field or updates its address. Pass `""` (empty string) to clear the address.

**`planr field delete <name>`**
- Deletes a field and all associated availability windows.

**`planr field list`**
- Tabular list of all fields: `NAME`, `ADDRESS`, `WINDOWS`.

**`planr field window add <field> --day <day> --start <HH:mm> --end <HH:mm> [--division <name>]`**
- Adds a recurring day-of-week availability window to a field. Accepts full day names or 3-letter abbreviations, case-insensitively. An optional `--division` restriction limits the window to one division's games.

**`planr field window edit <field> <number> [--day] [--start] [--end] [--division] [--clear-division]`**
- Edits a window by 1-based index; unspecified fields are preserved. `--clear-division` and `--division` are mutually exclusive.

**`planr field window delete <field> <number>`**
- Deletes a window by 1-based index.

**`planr field window list <field>`**
- Tabular list of windows: `#`, `DAY`, `START`, `END`, `DIVISION` (shows "All divisions" or the division name; shows "[deleted]" with a trailing warning for orphaned division references).

**New model types**
- `Field` record — UUID, name, optional address, list of `AvailabilityWindow` records.
- `AvailabilityWindow` record — UUID, day-of-week, start time, end time, optional division UUID.

### Changed
- `League` record: gains `fields: List<Field>` (position 2). All `withX(...)` helpers forward it.
- `League.CURRENT_VERSION` advanced from `1` to `2`.
- `League.empty()` returns `new League(2, List.of(), List.of(), null)`.
- `LeagueStore`: adds `jackson-datatype-jsr310` (`JavaTimeModule`) for `LocalTime` / `DayOfWeek` support; registers a custom `HH:mm` serializer for `LocalTime` (overriding the jsr310 default of `HH:mm:ss`); v1 → v2 migration adds an empty `fields` list.
- `build.gradle`: adds `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2`.

---

## [0.1.1] — CI Pipeline — 2026-05-15

Adds a `Jenkinsfile` defining a basic declarative pipeline with Checkout, Build (`gradle assemble`), and Test (`gradle test`) stages, using an `openjdk:25` Docker agent.

---

## [0.1.0] — Division & Team Management — 2026-05-15

**PRD:** `features/2026-05-15-league-planner-core-scheduling.md` (Division & Team Management section)  
**Spec:** `specs/2026-05-15-division-team-management-cli.md`

Initial working release of `planr`. Establishes the core CLI skeleton, persistence layer, and immutable model pattern. All state lives in `~/.planr/league.json`; every mutating operation writes the file atomically (temp file + `Files.move(ATOMIC_MOVE)`).

### Added

**`planr division add <name> --duration <minutes>`**
- Creates a division with a unique name (case-insensitive) and a positive game duration.

**`planr division edit <name> [--name <new-name>] [--duration <minutes>]`**
- Renames a division or changes its game duration. At least one option required.

**`planr division delete <name>`**
- Deletes a division. Rejected if the division has any teams; the error message names the team count and instructs removing them first.

**`planr division list`**
- Tabular list of all divisions: `DIVISION`, `DURATION`, `TEAMS`.

**`planr team add <division> <name>`**
- Adds a team to a division. Name must be unique within the division (case-insensitive).

**`planr team edit <division> <name> --name <new-name>`**
- Renames a team within a division. Case-insensitive match for both division and existing team name.

**`planr team delete <division> <name>`**
- Removes a team from its division.

**`planr team list <division>`**
- Lists all teams in a division, sorted alphabetically (case-insensitive).

**Infrastructure**
- `PlanrApp` — root Picocli `@Command`; owns the `LeagueStore` singleton and wires subcommands via `@ParentCommand`.
- `LeagueStore` — reads and writes `~/.planr/league.json` atomically. Initialises the file with `League.empty()` on first run.
- `League` record — `version: int`, `divisions: List<Division>`, immutable; exposes `findDivision`, `hasDivision`, `withDivisionAdded`, `withDivisionReplaced`, `withDivisionRemoved`.
- `Division` record — `id: UUID`, `name: String`, `gameDurationMinutes: int`, `teams: List<Team>`; exposes team mutation helpers.
- `Team` record — `id: UUID`, `name: String`.
- Exit code conventions: `0` success, `1` validation error, `2` I/O error. All success output to stdout, all errors to stderr.
- Full JUnit 5 test suite: `DivisionCommandTest`, `TeamCommandTest`, `DivisionTest`, `LeagueTest`, `LeagueStoreTest`. Tests isolated via `user.home` redirect to `build/test-home`; serial execution to avoid file contention.
- `build.gradle`: Java 25 toolchain, Picocli 4.7.6, Jackson 2.17.2, JUnit Jupiter 5.10.2, `picocli-codegen` annotation processor (for future GraalVM native image), `-parameters` compiler flag (for Jackson record parameter name binding without `@JsonCreator`).

---

## [0.0.1] — Repository Initialisation — 2026-05-15

- Added `.gitignore` and MIT `LICENSE`.
