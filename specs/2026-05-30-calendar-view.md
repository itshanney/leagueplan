# Tech Spec: `planr calendar` — Unified Calendar View

**Date:** 2026-05-29  
**Feature spec:** `features/2026-05-30-calendar-view.md`

---

## Overview

`planr calendar` is a new read-only top-level command that merges events from all three schedule types — regular-season games, playoff games, and practice slots — into a unified ASCII calendar. The implementation adds two new files: `CalendarCommand.java` (picocli routing, data collection, filter enforcement) and `CalendarRenderer.java` (pure rendering logic with no I/O or model dependencies). No changes to the data model, JSON schema, or `LeagueStore` are required. All three event sources are already persisted with assigned dates and field IDs; the only new work is normalization, filtering, and ASCII layout.

---

## Component Diagram

```
PlanrApp
  └── CalendarCommand          — top-level command; arg parsing, validation,
        │                        event collection, rendering dispatch
        │  call()
        │    1. validate filter flags (at most one of --division/--team/--field)
        │    2. validate mode/date flag combinations
        │    3. store.load() → League
        │    4. resolveFilter(league)  — entity existence check
        │    5. collectEvents(league)  — all three sources → List<CalendarEvent>
        │    6. applyFilter(events)    — division / team / field
        │    7. guard: empty after filter → exit 1
        │    8. resolveWindow(events)  — pick week or month if not specified
        │    9. CalendarRenderer.renderWeekly() or renderMonthly() → stdout
        │
        ├── CalendarEvent (private static record)
        │     date, time, type (GAME|PLAYOFF|PRACTICE),
        │     description, fieldName, divisionName, filterTeams
        │
        └── CalendarRenderer (package-private class, command package)
              renderWeekly(events, weekStart, out)
              renderMonthly(events, yearMonth, out)
              — pure functions; no I/O, no League references
```

---

## Data Model

### `CalendarEvent` (internal, not persisted)

| Field | Type | Source |
|---|---|---|
| `date` | `LocalDate` | Assigned date from source event |
| `time` | `LocalTime` | Assigned start time from source event |
| `type` | `EventType` (enum) | `GAME`, `PLAYOFF`, or `PRACTICE` |
| `description` | `String` | Formatted display string (see mapping below) |
| `fieldName` | `String` | Denormalized from source or resolved via `league.fields()` |
| `divisionName` | `String` | Denormalized from source or resolved via `league.divisions()` |
| `filterTeams` | `List<String>` | Lower-cased team names for `--team` filter; empty for PLAYOFF |

`CalendarEvent` is a `private static record` inside `CalendarCommand`. It is never serialized.

### Source-to-CalendarEvent mapping

| Source type | Eligibility | `description` | `fieldName` | `divisionName` | `filterTeams` |
|---|---|---|---|---|---|
| `ScheduledGame` | `game.date() != null` | `"{home} vs {away}"` | `game.fieldName()` (denormalized) | `game.divisionName()` (denormalized) | `[home.toLowerCase(), away.toLowerCase()]` |
| `PlayoffGame` | `pg.assignedDate() != null` | `"{positionA} vs {positionB}"` | resolved via `league.fields()` by `pg.assignedFieldId()` | resolved via `league.divisions()` by parent `Playoff.divisionId()` | `[]` (empty — bracket references cannot match a team name) |
| `PracticeSlot` | `slot.assignedDate() != null` | team name (resolved) | resolved via `league.fields()` by `slot.assignedFieldId()` | resolved via `league.divisions()` by parent `PracticeSchedule.divisionId()` | `[teamName.toLowerCase()]` |

### Resolution helpers (private static in CalendarCommand)

```
resolveTeamName(League, UUID teamId) → String
  league.divisions().stream()
    .flatMap(d -> d.teams().stream())
    .filter(t -> t.id().equals(teamId))
    .findFirst().map(Team::name).orElse("Unknown")

resolveFieldName(League, UUID fieldId) → String
  league.fields().stream()
    .filter(f -> f.id().equals(fieldId))
    .findFirst().map(Field::name).orElse("Unknown")
```

Neither helper is expected to return `"Unknown"` in practice — a field or team UUID stored in an assigned slot always corresponds to a live entity. `"Unknown"` is a defensive fallback only.

---

## API Contracts

### Command signature

```
planr calendar [--weekly | --monthly]
               [--division <name> | --team <name> | --field <name>]
               [--week <YYYY-MM-DD> | --month <YYYY-MM>]
```

All flags are optional. Picocli `@ArgGroup(exclusive = true)` enforces the mode mutual-exclusion; a second `@ArgGroup(exclusive = true)` enforces the filter mutual-exclusion. Flag/mode conflicts (`--week` with `--monthly`) are validated in `call()`.

### Exit codes

| Code | Condition |
|---|---|
| `0` | Successful render (even if the target week/month has zero events after filtering) |
| `1` | Argument error (multiple filters; mismatched date/mode flag); entity not found; no assigned events exist after filtering |
| `2` | I/O failure reading `league.json` |

### Filter semantics

| Filter | Match condition |
|---|---|
| `--division <name>` | `event.divisionName().equalsIgnoreCase(name)` |
| `--team <name>` | `event.filterTeams()` contains `name.toLowerCase()` |
| `--field <name>` | `event.fieldName().equalsIgnoreCase(name)` |

**Playoff + team filter:** playoff `CalendarEvent`s have an empty `filterTeams` list and therefore never match a `--team` filter. To view playoff games, use `--division`. This is intentional: bracket position references (`W of G1`) cannot be matched to a team name before the bracket resolves, and showing all division playoff games when a team filter is active would violate the filter contract. Document in the command's `--team` flag description string.

---

## Critical Path Walkthrough

### Weekly view: `planr calendar --week 2026-05-31 --division Majors`

1. Picocli routes to `CalendarCommand.call()`. `--weekly` is the default mode.
2. Validate: one filter (`--division`). No mode/date conflict.
3. `store.load()` → `League`.
4. `resolveFilter`: `league.findDivision("Majors")` → present. No error.
5. `collectEvents(league)`:
   - Iterate `league.schedule().games()` (if non-null) → filter `date != null` → map to GAME CalendarEvents.
   - Iterate `league.playoffs()` → for each `Playoff p`, iterate `p.games()` → filter `assignedDate != null` → resolve fieldName, divisionName → map to PLAYOFF CalendarEvents.
   - Iterate `league.practiceSchedules()` → for each `PracticeSchedule ps`, iterate `ps.slots()` → filter `assignedDate != null` → resolve teamName, fieldName, divisionName → map to PRACTICE CalendarEvents.
6. `applyFilter`: keep only events where `divisionName.equalsIgnoreCase("Majors")`.
7. Guard: filtered list is non-empty → continue.
8. `resolveWindow`: `--week 2026-05-31` → ISO week-of-year containing 2026-05-31 → Sunday 2026-05-25 through Saturday 2026-05-31 (Sunday-first convention).
9. Filter events to `date in [2026-05-25, 2026-05-31]`.
10. `CalendarRenderer.renderWeekly(events, weekStart=2026-05-25, out=System.out)`:
    - Print header line.
    - For each of the 7 days (Sun through Sat):
      - Print day header.
      - Sort events for that day: time ASC → GAME < PLAYOFF < PRACTICE → divisionName → description.
      - Print each event line or `(no events)`.
      - Print blank separator.
    - Print summary line.
11. Exit 0.

### Monthly view: `planr calendar --monthly --month 2026-05`

1–7. Same as weekly walkthrough except `--monthly` mode detected, `--month 2026-05` parsed to `YearMonth.of(2026, 5)`.
8. `resolveWindow`: use `YearMonth.of(2026, 5)` directly.
9. Filter events to `date.getYear() == 2026 && date.getMonthValue() == 5`.
10. `CalendarRenderer.renderMonthly(events, ym=2026-05, out=System.out)`:
    - Compute first Sunday on or before the first of the month.
    - **Grid section:**
      - Print centered month-year title.
      - Print column header row.
      - For each week row (up to 6 rows, each 4 output lines):
        - Line 1 (dates): right-align day number in each 10-char column; blank for out-of-month cells.
        - Line 2 (G count): count GAME events for each date, right-aligned with `G` suffix; blank if zero.
        - Line 3 (P count): count PRACTICE events for each date, right-aligned with `P` suffix; blank if zero.
        - Line 4 (PO count): count PLAYOFF events for each date, right-aligned with `PO` suffix; blank if zero.
    - Print legend.
    - **Event listing section:**
      - Collect all events in month, group by date, sort dates ascending.
      - For each date with ≥1 event: print day header, print event lines (same format as weekly), print blank separator.
    - Print summary line.
11. Exit 0.

### No events after filter: `planr calendar --team "Red Sox"` (no schedule assigned)

1–6. Same collection and filter.
7. Guard triggers: `events.isEmpty()` → `System.err.println(...)` → exit 1 with message: `No assigned events found. Run schedule/playoff/practice assign first.`

---

## CalendarRenderer Implementation Notes

`CalendarRenderer` is a package-private class in `org.leagueplan.planr.command`. It holds no instance state. Its two public methods both accept a `PrintStream out` parameter so they are trivially testable without capturing `System.out`.

### Weekly rendering constants

```
INDENT   = "  "          (2 spaces for day header; 4 for event lines)
DAY_ABBR = ["SUN","MON","TUE","WED","THU","FRI","SAT"]
```

### Monthly rendering constants

```
COL_WIDTH = 10           (characters per column)
MARGIN    = 5            (left indent spaces)
TOTAL_W   = MARGIN + 7 * COL_WIDTH = 75
TITLE_PAD = (TOTAL_W - titleStr.length()) / 2
```

### Event sort comparator (shared by both views)

```java
Comparator<CalendarEvent> EVENT_ORDER =
    Comparator.comparing(CalendarEvent::time)
      .thenComparing(e -> e.type().ordinal())   // GAME=0 < PLAYOFF=1 < PRACTICE=2
      .thenComparing(CalendarEvent::divisionName, String.CASE_INSENSITIVE_ORDER)
      .thenComparing(CalendarEvent::description, String.CASE_INSENSITIVE_ORDER);
```

### Monthly grid: week row iteration

```
firstSunday = monthStart adjusted back to Sunday (using DayOfWeek.SUNDAY)
for week 0..5:
    weekStart = firstSunday.plusWeeks(week)
    if weekStart.isAfter(monthEnd) → break
    emit 4 lines for this week row
```

A May 2026 month (May 1=Fri) requires 6 week rows (Apr 27–May 2, May 3–9, …, May 31–Jun 6) — the grid always stops when the first day of the next row is past the end of the month. Do not emit trailing empty rows beyond the last day of the month.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risks Accepted |
|---|---|---|---|---|
| `CalendarEvent` location | (a) private static record in `CalendarCommand`; (b) package-private class; (c) top-level `model` record | Private static record in `CalendarCommand` | It is purely internal to the calendar feature; no other command needs it; avoids polluting the model package | Harder to unit-test in isolation — mitigated by testing through `CalendarCommand` |
| `CalendarRenderer` location | (a) static methods on `CalendarCommand`; (b) separate class in `command` package; (c) new `view` sub-package | Separate class in `command` package | Clean separation of rendering from routing/collection without adding a new package; consistent with how `ScheduleCommand` delegates rendering to `printFullScheduleTable` helpers | Slightly larger `command` package |
| Playoff + `--team` filter | (a) include all division playoff games when team is in that division; (b) bracket-text contains-match; (c) exclude playoff events from team filter entirely | Exclude (empty `filterTeams`) | Bracket references like `W of G1` cannot be matched to a team name before resolution; including all division playoff games would violate the filter's stated semantics ("a single team's schedule") | Organizers cannot see playoff events when filtering by team; workaround is `--division` |
| `PrintStream` vs direct `System.out` | (a) renderer takes `PrintStream`; (b) renderer writes directly to `System.out` | `PrintStream` parameter | Enables deterministic unit tests without capturing system streams | Requires tests to construct a `ByteArrayOutputStream` |
| Default week/month when omitted | (a) current calendar date; (b) earliest event date | Earliest event date across filtered events | Renders something useful immediately — today's date may be before the season | If all events are far in the past, behavior is still sensible |
| Six vs. variable week rows in monthly grid | (a) always 6 rows; (b) stop after last day of month | Stop after last day | Avoids one or two fully blank rows at the end of months that fit in 4–5 rows | Row count varies (4–6) across months — acceptable because the spec requires no fixed-height between months |

---

## Operational Concerns

- **No state changes.** `planr calendar` is read-only. It calls `store.load()` once and writes only to stdout/stderr. No risk to `league.json`.
- **Performance.** The event collection iterates three lists (games, playoff games, practice slots). For a typical little league dataset (< 500 total events), this is instantaneous. No caching or streaming needed.
- **Failure mode.** If `store.load()` throws `IOException`, exit 2 with a stderr message. No other failure modes exist — all rendering is in-memory.
- **Test strategy.** Two test classes:
  - `CalendarCommandTest` — command-level integration tests using `CommandTestBase` (same pattern as `ScheduleCommandTest`, `PracticeCommandTest`). Tests cover: filter validation, entity-not-found, no-events error, weekly/monthly output spot-checks.
  - `CalendarRendererTest` — unit tests for `renderWeekly` and `renderMonthly` using a `ByteArrayOutputStream`. Tests cover: empty list (no-events notice), single event, multi-type event sort order, grid alignment spot-check for month with known layout.

---

## Implementation Checklist

### `PlanrApp.java`

- [ ] Add `CalendarCommand.class` to the `subcommands` array in the `@Command` annotation.

### `CalendarCommand.java` (new file: `src/main/java/org/leagueplan/planr/command/CalendarCommand.java`)

- [ ] Declare `@Command(name = "calendar", ...)` class implementing `Callable<Integer>`.
- [ ] Add `@ParentCommand PlanrApp app` field.
- [ ] Add `@ArgGroup(exclusive = true)` for `--weekly` / `--monthly` (boolean flags; `--weekly` is the default when neither is set).
- [ ] Add `@ArgGroup(exclusive = true)` for `--division` / `--team` / `--field` (String options, all optional).
- [ ] Add `--week <YYYY-MM-DD>` (`LocalDate`) and `--month <YYYY-MM>` (`YearMonth`) optional options.
- [ ] Implement `call()`:
  - [ ] Validate mode/date flag combinations (exit 1 on mismatch).
  - [ ] `store.load()`.
  - [ ] `resolveFilter()` — entity lookup and existence check.
  - [ ] `collectEvents()` — all three sources.
  - [ ] `applyFilter()`.
  - [ ] Guard empty events → exit 1.
  - [ ] `resolveWindow()`.
  - [ ] Dispatch to `CalendarRenderer`.
- [ ] Implement `private static CalendarEvent` record with fields: `date`, `time`, `type`, `description`, `fieldName`, `divisionName`, `filterTeams`.
- [ ] Implement `EventType` enum: `GAME`, `PLAYOFF`, `PRACTICE` (ordinal order used for sort).
- [ ] Implement `resolveTeamName(League, UUID)` private static helper.
- [ ] Implement `resolveFieldName(League, UUID)` private static helper.
- [ ] Implement `collectGames(League)` — maps `ScheduledGame` → `CalendarEvent`.
- [ ] Implement `collectPlayoffGames(League)` — iterates `Playoff` → `PlayoffGame`.
- [ ] Implement `collectPracticeSlots(League)` — iterates `PracticeSchedule` → `PracticeSlot`.

### `CalendarRenderer.java` (new file: `src/main/java/org/leagueplan/planr/command/CalendarRenderer.java`)

- [ ] Package-private class, no constructor arguments, no instance state.
- [ ] Implement `renderWeekly(List<CalendarEvent> events, LocalDate weekStart, PrintStream out)`.
- [ ] Implement `renderMonthly(List<CalendarEvent> events, YearMonth month, PrintStream out)`.
- [ ] Implement `EVENT_ORDER` static comparator.
- [ ] Implement `formatEventLine(CalendarEvent e)` → `"    HH:MM  [TYPE]  {desc}  @  {field}  ({div})"`.
- [ ] Monthly grid: implement 4-line fixed-height week row emitter.
- [ ] Monthly event listing: group by date, emit date headers and event lines.

### `CalendarCommandTest.java` (new file: `src/test/java/org/leagueplan/planr/command/CalendarCommandTest.java`)

- [ ] Extend `CommandTestBase`.
- [ ] `MultipleFilters` — exits 1 when `--division` and `--team` both supplied.
- [ ] `ModeDateMismatch` — exits 1 when `--monthly` and `--week` supplied together.
- [ ] `EntityNotFound` — exits 1 with error message when `--division Unknown` supplied.
- [ ] `NoAssignedEvents` — exits 1 when no Phase 2 has run.
- [ ] `WeeklyViewSmoke` — after schedule assign, `planr calendar --weekly --week <assigned-date>` exits 0; stdout contains the header line and at least one `[G]` event line.
- [ ] `MonthlyViewSmoke` — after schedule assign, `planr calendar --monthly --month <assigned-month>` exits 0; stdout contains the grid header and legend.
- [ ] `FilterByDivision` — weekly view with `--division` shows only that division's events.
- [ ] `FilterByField` — weekly view with `--field` shows only that field's events.
- [ ] `FilterByTeam` — weekly view with `--team` shows game and practice events; does not show playoff events.

### `CalendarRendererTest.java` (new file: `src/test/java/org/leagueplan/planr/command/CalendarRendererTest.java`)

- [ ] `weeklyNoEvents` — empty list produces `(no events)` for each day.
- [ ] `weeklySortOrder` — GAME before PLAYOFF before PRACTICE within same time slot.
- [ ] `weeklyEventLineFormat` — spot-check formatted event line against expected string.
- [ ] `monthlyGridAlignment` — for a known event set, spot-check that `3G` appears in the correct column and row position.
- [ ] `monthlyEventListing` — dates with no events are omitted; dates with events appear in ascending order.
- [ ] `monthlyFixedHeight` — all week rows emit exactly 4 lines even when all event-type counts are zero.

---

## Out of Scope / Future Work

- **iCal export:** The `CalendarEvent` normalized record is a natural source for an `.ics` generator; deferred to a future `planr export --format ical` command.
- **Interactive week/month navigation:** Not feasible in a CLI without a TUI library. Deferred for the future web app.
- **ANSI color:** Easy to add via JANSI once the ASCII layout is stable. Deferred — current output must pipe-safe.
- **`League.findField(UUID)`:** The spec resolves field IDs via `league.fields().stream().filter(...)` inline. A named helper on `League` would be cleaner but is a separate refactor.
- **Combined weekly+monthly filters (`--division Majors --team "Red Sox"`):** Explicitly out of scope per the feature spec. Single-filter constraint kept to avoid the UX complexity of conjunctive filtering.
