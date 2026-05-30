# Calendar View — Unified Schedule Visualization

**Date:** 2026-05-29  
**Status:** Finalized

---

## Problem Statement

planr produces three separate schedule types — regular-season games, playoffs, and practices — each viewable only through its own command with tabular output. An organizer managing a multi-division league must context-switch between `planr schedule view`, `planr playoff assign`, and `planr practice view` to understand what any given week looks like, and has no way to see how all events land on a calendar without manually cross-referencing. This friction increases the chance of scheduling conflicts the organizer does not catch, and makes it difficult to communicate the season calendar to coaches.

---

## Proposed Solution

Add a new top-level `planr calendar` command that renders all assigned schedule events — regular-season games, playoff games, and practice slots — in a unified ASCII calendar view. Two layout modes are supported:

- **Weekly** (default): lists all events day-by-day for a single 7-day week, with times, participants, field, and event type.
- **Monthly**: renders a traditional calendar grid for a single month where each day cell shows abbreviated event-type counts, followed by a full chronological event listing for that month.

The same filters used by `planr schedule view` (`--division`, `--team`, `--field`) apply here, so an organizer can zoom in on a single team's full week or a single field's full month. Only events with assigned dates (Phase 2 or practice assignment complete) are shown; unassigned slots are silently excluded.

Both views use Sunday as the first day of the week (standard US sports convention) and target an 80-character terminal width.

---

## User Stories

1. **As a league organizer**, I want to see all games, playoffs, and practices for a given week on one screen, so that I can spot conflicts or overloaded days without opening multiple commands.

2. **As a league organizer**, I want to view the monthly calendar for a specific division, so that I can share a season overview with division coordinators and coaches.

3. **As a league organizer**, I want to filter the calendar to a single team, so that I can hand a coach their complete season schedule — practices, games, and playoffs — in one view.

4. **As a league organizer**, I want to filter the calendar by field, so that I can verify a specific facility's usage across the season and avoid overbooking.

5. **As a league organizer**, I want to navigate forward or backward a week (or month) by re-running the command with a different date argument, so that I can review the full season at my own pace without additional tooling.

---

## Acceptance Criteria

### Command Structure

- **AC-1.** `planr calendar` is a new top-level command with two mutually exclusive mode flags: `--weekly` and `--monthly`. When neither flag is supplied, `--weekly` is the default.
- **AC-2.** `planr calendar` accepts three optional filter flags — `--division <name>`, `--team <name>`, `--field <name>` — which are case-insensitive and function identically to the filters in `planr schedule view`. At most one filter may be specified per invocation. Specifying more than one filter exits with code `1` and an error message.
- **AC-3.** `planr calendar --weekly` accepts an optional `--week <YYYY-MM-DD>` flag. The supplied date may be any day within the target week; the calendar renders the Sunday-through-Saturday week containing that date. When omitted, the command defaults to the earliest week that contains at least one assigned event (across all visible schedule types after filtering).
- **AC-4.** `planr calendar --monthly` accepts an optional `--month <YYYY-MM>` flag identifying the target month. When omitted, the command defaults to the earliest month that contains at least one assigned event (across all visible schedule types after filtering).
- **AC-5.** If no assigned events exist (after filtering) in the entire dataset, the command exits with code `1` and a message: `No assigned events found. Run schedule/playoff/practice assign first.`
- **AC-6.** If a `--division`, `--team`, or `--field` filter is supplied that does not match any known entity, the command exits with code `1` and an error message naming the unrecognized value.
- **AC-7.** If `--week` is supplied with `--monthly` (or `--month` with `--weekly`), the command exits with code `1` and an error message.

### Weekly View Layout

- **AC-8.** The weekly view header line reads: `Week of YYYY-MM-DD (DOW) — YYYY-MM-DD (DOW)` where the two dates are the Sunday and Saturday of the target week and DOW is the abbreviated day name (e.g., `Sun`, `Sat`).
- **AC-9.** Each of the seven days (Sun through Sat) is rendered as a day section: a day header line (`  SUN YYYY-MM-DD`, `  MON YYYY-MM-DD`, etc.) followed by zero or more indented event lines. Days with no events display a single indented line: `    (no events)`.
- **AC-10.** Each event line within a day section is formatted as: `    HH:MM  [TYPE]  <description>  @  <field name>  (<division name>)` where:
  - `[TYPE]` is `[G]` for regular-season game, `[PO]` for playoff game, `[P]` for practice.
  - For games: description is `<home team> vs <away team>`.
  - For practices: description is `<team name>`.
  - For playoff games: description uses the existing bracket position strings (e.g., `W of G3 vs L of G2`), or resolved team names when both participants are known.
- **AC-11.** Within a day section, events are sorted ascending by start time. When two events share the same start time, regular-season games sort before playoff games, which sort before practices; within each type, events sort alphabetically by division name then by description.
- **AC-12.** A blank line separates each day section from the next.
- **AC-13.** At the end of the weekly view, a one-line summary is printed: `<N> events this week  (G: <g>  PO: <po>  P: <p>)` reflecting counts after filtering.

### Monthly View Layout — Calendar Grid

- **AC-14.** The monthly view header is the month and year centered over the calendar grid: e.g., `             May 2026`.
- **AC-15.** The grid uses a Sun–Sat column order. Each column is exactly 10 characters wide; 7 columns produce a 70-character grid. A 5-character left indent centers the grid in an 80-character terminal (5-character visual margin on each side). The column header line reads: `     Sun       Mon       Tue       Wed       Thu       Fri       Sat` (each day name left-aligned within its 10-character column). The month-and-year title is centered over the 70-character grid, which for an 80-character terminal is 36 leading spaces (e.g., `                                    May 2026`).
- **AC-16.** Each day cell has a fixed height of exactly 4 output lines. All 7 cells in a week are rendered together one line at a time, producing 4 lines of terminal output per week row. The 4 lines are:
  - **Line 1 (date):** The day number right-aligned within the 10-character column. Blank for cells outside the displayed month.
  - **Line 2 (games):** The count of regular-season games for that date, right-aligned within the column with a `G` suffix (e.g., `3G`). Blank — not `0G` — when the count is zero.
  - **Line 3 (practices):** The count of practice events for that date, right-aligned within the column with a `P` suffix (e.g., `2P`). Blank — not `0P` — when the count is zero.
  - **Line 4 (playoffs):** The count of playoff games for that date, right-aligned within the column with a `PO` suffix (e.g., `1PO`). Blank — not `0PO` — when the count is zero.

  All 4 lines are always emitted for every week row, including rows where all event-type lines are blank. This ensures the grid is the same height in every row.
- **AC-17.** Days outside the displayed month (leading/trailing padding cells) are shown as blank cells across all 4 lines.
- **AC-18.** A legend line is printed below the grid: `Legend: G = Game   PO = Playoff   P = Practice`.

### Monthly View Layout — Event Listing

- **AC-19.** After the calendar grid and legend, the monthly view prints a blank line followed by a chronological event listing for all events in the month. Only dates that have at least one event are shown; dates with no events are omitted entirely.
- **AC-20.** Each date in the listing is preceded by a day header line formatted as `  DOW YYYY-MM-DD` (e.g., `  SAT 2026-05-02`), using the same three-letter uppercase day abbreviation as the weekly view.
- **AC-21.** Each event under a date header uses the identical format as the weekly view event line (AC-10): `    HH:MM  [TYPE]  <description>  @  <field name>  (<division name>)`. Sort order within a date follows AC-11.
- **AC-22.** A blank line separates each date group from the next within the event listing.
- **AC-23.** At the end of the monthly view (after the event listing), a one-line summary is printed: `<N> events in <Month YYYY>  (G: <g>  PO: <po>  P: <p>)` reflecting counts after filtering.

### Data Eligibility

- **AC-24.** A regular-season game is included only if it has an assigned date and start time (i.e., the schedule is in `DRAFT` or `FINALIZED` state).
- **AC-25.** A playoff game is included only if it has an assigned date and start time (fields `assignedDate` and `assignedStartTime` are non-null).
- **AC-26.** A practice slot is included only if it has an assigned date and start time (fields `assignedDate` and `assignedStartTime` are non-null on the `PracticeSlot`).
- **AC-27.** Unassigned slots of any type are silently excluded; they are not counted in summaries and do not cause errors.

### Error Handling

- **AC-28.** All I/O failures exit with code `2` and a message to stderr.
- **AC-29.** Exit code is `0` for all successful renders, including weeks or months with zero events after filtering (which print a "no events" notice rather than an error).

---

## Example Output

### Weekly View (`planr calendar --weekly --week 2026-05-30 --division Majors`)

```
Week of 2026-05-25 (Sun) — 2026-05-31 (Sat)

  SUN 2026-05-25
    (no events)

  MON 2026-05-26
    (no events)

  TUE 2026-05-27
    09:00  [P]  Blue Jays @ Hillside Field  (Majors)
    09:00  [P]  Cardinals @ Riverside Field  (Majors)
    10:30  [P]  Red Sox @ Hillside Field  (Majors)
    10:30  [P]  Yankees @ Riverside Field  (Majors)

  WED 2026-05-28
    (no events)

  THU 2026-05-29
    (no events)

  FRI 2026-05-30
    (no events)

  SAT 2026-05-31
    09:00  [G]  Red Sox vs Yankees @ Hillside Field  (Majors)
    11:30  [G]  Blue Jays vs Cardinals @ Riverside Field  (Majors)
    14:00  [PO]  W of G1 vs W of G2 @ Hillside Field  (Majors)

7 events this week  (G: 2  PO: 1  P: 4)
```

### Monthly View (`planr calendar --monthly --month 2026-05 --division Majors`)

Each column is 10 chars wide; all values right-aligned. Every week is exactly 4 output lines (date / G count / P count / PO count). Blank lines are always emitted.

```
                                    May 2026
     Sun       Mon       Tue       Wed       Thu       Fri       Sat
                                                                1         2
                                                                         1G


              3                                                 8         9
                                                                         3G
                                                               4P

             10        11        12        13        14        15        16
                                                                         3G
                                 4P
                                                                        1PO
             17        18        19        20        21        22        23



             24        25        26        27        28        29        30
                                                               2G
                                 4P

             31



Legend: G = Game   PO = Playoff   P = Practice

  FRI 2026-05-08
    10:00  [P]  Blue Jays @ Hillside Field  (Majors)
    10:00  [P]  Cardinals @ Riverside Field  (Majors)
    11:30  [P]  Red Sox @ Hillside Field  (Majors)
    11:30  [P]  Yankees @ Riverside Field  (Majors)

  SAT 2026-05-09
    09:00  [G]  Blue Jays vs Cardinals @ Riverside Field  (Majors)
    11:30  [G]  Red Sox vs Yankees @ Hillside Field  (Majors)
    14:00  [G]  Cardinals vs Yankees @ Riverside Field  (Majors)

  TUE 2026-05-12
    09:00  [P]  Blue Jays @ Hillside Field  (Majors)
    09:00  [P]  Cardinals @ Riverside Field  (Majors)
    10:30  [P]  Red Sox @ Hillside Field  (Majors)
    10:30  [P]  Yankees @ Riverside Field  (Majors)

  SAT 2026-05-16
    09:00  [G]  Red Sox vs Cardinals @ Riverside Field  (Majors)
    11:30  [G]  Yankees vs Blue Jays @ Hillside Field  (Majors)
    14:00  [G]  Red Sox vs Blue Jays @ Riverside Field  (Majors)
    14:00  [PO]  W of G1 vs W of G2 @ Hillside Field  (Majors)

  TUE 2026-05-26
    09:00  [P]  Blue Jays @ Hillside Field  (Majors)
    09:00  [P]  Cardinals @ Riverside Field  (Majors)
    10:30  [P]  Red Sox @ Hillside Field  (Majors)
    10:30  [P]  Yankees @ Riverside Field  (Majors)

  FRI 2026-05-29
    09:00  [G]  Yankees vs Cardinals @ Riverside Field  (Majors)
    11:30  [G]  Blue Jays vs Red Sox @ Hillside Field  (Majors)

18 events in May 2026  (G: 9  PO: 1  P: 8)
```

*(Example uses illustrative counts only.)*

---

## Out of Scope

- Interactive navigation (arrow keys to move between weeks/months within a single invocation). Navigation is achieved by re-running the command with a different `--week` or `--month` argument.
- Color or ANSI highlighting. Output must work in non-color terminals and when piped to a file.
- Export to iCal (`.ics`) or any other calendar file format.
- Showing unassigned/pending events. Only fully assigned slots appear.
- A combined `--all` view that spans the full season without specifying a week or month. The date argument selects the window.
- Multiple simultaneous filters (e.g., `--division Majors --team "Red Sox"`). At most one filter per invocation.
- Per-event game numbers or slot IDs. The calendar is a read-only display aid; cross-referencing to `planr schedule view` game numbers is out of scope.
- Cross-command integration: no changes to `planr schedule view`, `planr practice view`, or `planr playoff` output formats.

---

## Open Questions

None — all questions resolved prior to spec finalization.

---

## Dependencies

- `planr schedule assign` (`DRAFT` or `FINALIZED` state) must have run for regular-season games to appear.
- `planr playoff assign` must have run for playoff events to appear.
- `planr practice assign` must have run for practice slots to appear. Displaying practice slot field names and team names requires resolving `assignedFieldId` and `teamId` from the `League` entity (lookups are already available via `league.findField()` and existing team lookup helpers).
- New `PlanrApp`-level `CalendarCommand` class; no changes to existing command classes.
- A shared rendering utility (e.g., `CalendarRenderer`) that accepts a normalized list of calendar events (date, time, type, description, field, division) and produces weekly or monthly output; this utility is reusable and has no dependency on schedule-type-specific model records.
- No new model records, JSON schema changes, or migrations required.
