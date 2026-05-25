# League-Wide Availability Configuration

**Date:** 2026-05-25  
**Status:** Ready for implementation

---

## Problem Statement

Entering field availability restrictions today requires repeating the same block on every field individually. Two of the most common real-world constraints — "games start later on weekdays because of school" and "we never play on Sundays" — cannot be expressed at the league level. Organizers must instead add the same blocks manually to every field, which is tedious, error-prone, and hard to maintain when the league has multiple fields.

---

## Proposed Solution

Add two new league-level availability primitives stored in `LeagueConfig`:

1. **Day-of-week windows** — A per-day-of-week open/close time that overrides the global `sunrise`/`sunset` for all fields on that day. For example: "Wednesdays open at 16:00 and close at 21:00."

2. **Blocked days of week** — A set of days of the week on which no games are ever scheduled across all fields. For example: "Sundays are never available." A `FieldDateOverride` on a specific field and date can still override a blocked day for that field on that date.

Both are managed through new second-level subcommands under `planr config`: `planr config dow` (day-of-week windows) and `planr config blockday` (blocked days). The `planr config show` output is extended to display both.

The scheduler (`SchedulerService`) is updated to respect both primitives when generating available slots during Phase 2 field assignment. No changes to the Phase 1 team-schedule algorithm.

---

## User Stories

1. **As a league organizer**, I want to set a different open/close window for a specific day of the week (applied to all fields), so that I don't have to manually block every field on every weekday occurrence.

2. **As a league organizer**, I want to mark an entire day of the week as unavailable for all fields, so that I can guarantee no games are ever scheduled on that day without managing per-field blocks.

3. **As a league organizer**, I want to remove a day-of-week window or unblock a previously blocked day, so that I can adjust the schedule as the season setup evolves.

4. **As a league organizer**, I want to see all day-of-week windows and blocked days in `planr config show`, so that I can review the full availability configuration in one place.

5. **As a league organizer**, I want blocked days and day-of-week windows to take effect immediately the next time `planr schedule assign` runs, so that re-running Phase 2 produces slots that respect the updated configuration.

---

## Acceptance Criteria

### Day-of-Week Windows — `planr config dow`

- **AC-1.** `planr config dow set --day <DAY> --start <HH:mm> --end <HH:mm>` stores a window for that day; `<DAY>` is accepted case-insensitively as a full day name (e.g., `wednesday`, `WEDNESDAY`) or standard 3-letter abbreviation (e.g., `wed`, `WED`). At most one window exists per day of the week; running `set` on a day that already has a window replaces it.
- **AC-2.** `--start` and `--end` are required for `set`. If either is missing, the command exits with code `1` and an error message.
- **AC-3.** `--end` must be strictly after `--start`; if not, the command exits with code `1` and an error message naming both values.
- **AC-4.** `planr config dow clear --day <DAY>` removes the window for that day. If no window exists for that day, the command exits with code `1` and an error message.
- **AC-5.** `planr config dow list` prints a table of all configured day-of-week windows. Rows are sorted by day of the week (Monday through Sunday). If no windows are configured, the command prints a descriptive empty-state message and exits `0`.
- **AC-6.** The `dow list` table columns are: `DAY`, `OPEN`, `CLOSE`. Times display in `HH:mm` format.
- **AC-7.** After a successful `dow set`, if any field in the league has a `FieldDateOverride` or `FieldBlock` entry whose date falls on the affected day of the week and within the configured season window, the command prints a warning to `stdout` naming the count of conflicting entries and advising the organizer to review them. The command still exits `0`; the warning is informational only.

### Blocked Days — `planr config blockday`

- **AC-8.** `planr config blockday add --day <DAY>` marks that day of the week as blocked for all fields. `<DAY>` follows the same case-insensitive name/abbreviation rules as AC-1. If the day is already blocked, the command exits with code `1` and an error message.
- **AC-9.** `planr config blockday remove --day <DAY>` unblocks a previously blocked day. If the day is not currently blocked, the command exits with code `1` and an error message.
- **AC-10.** `planr config blockday list` prints the list of blocked days of the week, sorted Monday through Sunday. If none are blocked, the command prints a descriptive empty-state message and exits `0`.
- **AC-11.** After a successful `blockday add`, if any field in the league has a `FieldDateOverride` or `FieldBlock` entry whose date falls on the newly blocked day of the week and within the configured season window, the command prints a warning to `stdout` naming the count of conflicting entries and noting that `FieldDateOverride` entries on those specific dates will still override the block. The command still exits `0`; the warning is informational only.

### `planr config show` Updates

- **AC-12.** `planr config show` displays a "Day-of-week windows" section listing all configured windows in `DAY: HH:mm – HH:mm` format, sorted Monday through Sunday. If no windows exist, the section shows "(none)".
- **AC-13.** `planr config show` displays a "Blocked days of week" section listing blocked days sorted Monday through Sunday. If no days are blocked, the section shows "(none)".

### Scheduler Behavior

- **AC-14.** When `planr schedule assign` generates candidate slots for a given field and date, if the date's day of the week is in the blocked days set and no `FieldDateOverride` exists for that field on that specific date, the field produces zero slots for that date.
- **AC-15.** For a date not blocked (or where a `FieldDateOverride` overrides the block for a specific field), if a day-of-week window is configured for that day, the candidate slot window for all fields on that date is `[dowWindow.openStart, dowWindow.openEnd)` rather than `[config.sunrise, config.sunset)`.
- **AC-16.** Precedence order (highest wins): `FieldDateOverride` (specific date on a specific field) → blocked day → day-of-week window → global sunrise/sunset. A `FieldBlock` (time-range subtraction) still applies within whichever open window is in effect.
- **AC-17.** Adding or removing a day-of-week window or blocked day does not automatically invalidate a `DRAFT` or `FINALIZED` schedule; it takes effect only when `planr schedule assign` is re-run.

### Data Model and Persistence

- **AC-18.** Both primitives are stored in `LeagueConfig`. The `League` schema version increments to `5`. The `LeagueStore` migration from v4 → v5 initializes both new fields to empty lists so existing league files load without error.
- **AC-19.** Day-of-week values serialize to/from their string name (e.g., `"WEDNESDAY"`) consistent with the existing `DayOfWeek` serialization in use by the `JavaTimeModule`.

### Error Handling

- **AC-20.** An unrecognized `<DAY>` value (not a valid full name or 3-letter abbreviation, case-insensitively) exits with code `1` and an error message listing the accepted formats.
- **AC-21.** All I/O failures exit with code `2`.

---

## Out of Scope

- Per-field day-of-week windows. This feature applies the same window to all fields; per-field overrides on specific dates remain the responsibility of `planr field override`.
- Blocking specific calendar dates league-wide (as opposed to recurring days of the week). That use case is still served by adding per-field `FieldBlock` entries.
- Automatically regenerating or invalidating a `DRAFT`/`FINALIZED` schedule when availability config changes.
- Any changes to the Phase 1 team-schedule algorithm (`TeamScheduleService`).
- Visual display of dow windows or blocked days in the schedule view output.

---

## Open Questions

None — all questions resolved prior to implementation.

---

## Dependencies

- `LeagueConfig` model record must be extended with two new fields; `LeagueStore` must add a v4→v5 migration.
- `SchedulerService.assign()` must be updated to consult the two new primitives when enumerating candidate slots, using the precedence order defined in AC-16.
- `ConfigCommand` must be extended with two new second-level subcommand classes (`ConfigDowCommand`, `ConfigBlockdayCommand`), following the same nested-subcommand pattern as `FieldBlockCommand` and `FieldOverrideCommand` under `FieldCommand`.
- No external libraries required; `DayOfWeek` parsing can use `DayOfWeek.valueOf(name.toUpperCase())` with a manual abbreviation lookup table.
