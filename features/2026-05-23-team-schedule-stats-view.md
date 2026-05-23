# Team Schedule Statistics View

**Date:** 2026-05-23  
**Status:** Ready for implementation

---

## Problem Statement

After `planr schedule generate` completes Phase 1, organizers can inspect individual matchups with `planr schedule view` but have no way to assess schedule quality at a glance. There is no summary of how many home vs. away games each team has been assigned, nor any indication of how many times each pair of teams will face each other. Without these views, an organizer cannot detect imbalances or communicate the schedule structure to coaches and parents before approving Phase 2.

---

## Proposed Solution

Extend `planr schedule view` in the `TEAM_SCHEDULE` state to print two additional sections after the existing matchup table: a **Home/Away Balance table** and a **Head-to-Head Matchup table**. Both sections are always shown by default whenever team schedule data is visible — that is, in `TEAM_SCHEDULE` state and when `--team-schedule` is used in `DRAFT` or `FINALIZED` states. No flag is required to opt in.

Both sections are per-division, printed once per eligible division in division list order. The head-to-head table shows only teams within the same division; there is no cross-division summary.

No new subcommands are introduced. No changes to the JSON data model or the Phase 1 algorithm.

---

## User Stories

1. **As a league organizer**, I want to see each team's home and away game counts after Phase 1 generation, so that I can verify no team is severely disadvantaged before approving Phase 2.

2. **As a league organizer**, I want to see how many times each pair of teams plays each other, so that I can confirm the schedule isn't inadvertently over-scheduling a rivalry or under-scheduling a matchup.

3. **As a league organizer**, I want balance issues flagged in the output, so that I can decide whether to use `planr schedule game edit` to correct assignments before committing to Phase 2.

4. **As a league organizer**, I want the stats output per division, so that I can review each division's schedule independently in a multi-division league.

---

## Acceptance Criteria

### Home/Away Balance Table

- **AC-1.** When `planr schedule view` is run in `TEAM_SCHEDULE` state, the output includes a "HOME/AWAY BALANCE" section for each division with at least one team game.
- **AC-2.** The balance table has columns: `TEAM`, `HOME`, `AWAY`, `TOTAL`, `BALANCE` (where BALANCE = HOME − AWAY).
- **AC-3.** Each team in the division appears on exactly one row, sorted alphabetically by team name.
- **AC-4.** HOME + AWAY = TOTAL for every row.
- **AC-5.** BALANCE is displayed with an explicit sign (e.g., `+2`, `-2`, `0`).
- **AC-6.** Any team whose `|BALANCE| > 1` has its row flagged with a `*` marker so the organizer can identify it without mental arithmetic. This threshold is `|BALANCE| > 1`; flagging is informational only and does not affect exit code.
- **AC-7.** The last row of the table shows a `TOTAL` row with the sum of HOME, AWAY, and TOTAL columns; the BALANCE cell on the totals row is blank.

### Head-to-Head Matchup Table

- **AC-8.** When `planr schedule view` is run in `TEAM_SCHEDULE` state, the output includes a "HEAD-TO-HEAD" section for each division.
- **AC-9.** The head-to-head table is an N×N grid where rows represent the home team and columns represent the away team. Both axes are sorted alphabetically by team name.
- **AC-10.** Each cell shows the count of games where the row team is home against the column team. Diagonal cells (a team vs. itself) display `—`.
- **AC-11.** A cell value of 0 is shown as `0` (not blank), so the organizer can identify pairs that never play in a given direction.
- **AC-12.** When all non-diagonal cells in a row have the same value (uniform home distribution for that team), no special marker is shown. When any cell in a row differs from the mode value for that row, that cell is flagged with `*`.
- **AC-13.** The head-to-head table contains only teams from the same division. No cross-division rows or columns appear.

### Availability

- **AC-14.** The stats sections appear by default in `TEAM_SCHEDULE` state — no flag is required.
- **AC-15.** The stats sections appear by default when `planr schedule view --team-schedule` is run in `DRAFT` or `FINALIZED` state, reflecting the Phase 1 matchup assignments rather than Phase 2 dates.
- **AC-16.** The stats sections do NOT appear in the full `DRAFT`/`FINALIZED` view (without `--team-schedule`), which is date- and field-focused.
- **AC-17.** Exit code is `0` on success and `1` on the same error conditions as today.

### Formatting

- **AC-18.** Column widths for team names adapt to the longest team name in the division (no truncation, no fixed max width).
- **AC-19.** The head-to-head table header row is indented to align with row labels.
- **AC-20.** Each division's stats block is preceded by the division name as a section sub-header.
- **AC-21.** A blank line separates the matchup table from the stats sections, and separates each division's stats block from the next.

---

## Example Output

```
Schedule status: TEAM_SCHEDULE

#   HOME         AWAY         DIVISION
--  -----------  -----------  --------
1   Blue Jays    Cardinals    Majors
2   Red Sox      Yankees      Majors
3   Cardinals    Blue Jays    Majors
...

HOME/AWAY BALANCE — Majors
TEAM         HOME  AWAY  TOTAL  BALANCE
-----------  ----  ----  -----  -------
Blue Jays       3     3      6        0
Cardinals       3     3      6        0
Red Sox         4     2      6       +2 *
Yankees         2     4      6       -2 *
TOTAL          12    12     24

HEAD-TO-HEAD — Majors (row = home team, column = away team)
             Blue Jays  Cardinals  Red Sox  Yankees
-----------  ---------  ---------  -------  -------
Blue Jays        —           1        2        1
Cardinals        2           —        1        1
Red Sox          1           1        —        2 *
Yankees          2           2        1        —
```

*(Example uses illustrative counts only.)*

---

## Out of Scope

- Any changes to the `planr schedule generate` command's own output. Stats are only surfaced via `planr schedule view`.
- Phase 2 statistics (field utilization, per-week load distribution). Those belong to the full schedule view.
- Filtering the stats sections by `--team` or `--division`. The entire per-division stats block is always shown; the existing matchup-table filters are unaffected.
- Interactive editing from within the stats view. Organizers use `planr schedule game edit` separately.
- Export of stats tables to JSON or CSV.
- Color or ANSI highlighting. Output must work in non-color terminals and when piped to a file.
- Cross-division head-to-head summaries.

---

## Dependencies

- `planr schedule generate` (Phase 1) must have run and produced a `TEAM_SCHEDULE` state before `planr schedule view` can display stats.
- The existing `printTeamScheduleTable` helper in `ScheduleCommand` will need companion rendering helpers for the two new table types; no other service or model changes are required.
- No external dependencies.
