# Tech Spec: Team Schedule Statistics View

**Date:** 2026-05-23  
**PRD:** `features/2026-05-23-team-schedule-stats-view.md`  
**Status:** Ready for implementation

---

## Overview

This is a pure presentation change: two new rendering blocks (Home/Away Balance table and Head-to-Head Matchup table) appended to the team schedule view. All computation is in-memory over the already-loaded `List<TeamGame>`; there are no new data model types, no new services, and no additional I/O. 

The entire change is contained in `ScheduleCommand.java`. A single new static helper method `printTeamScheduleStats(List<TeamGame>)` handles both blocks, iterating divisions in first-occurrence order and printing the balance table followed by the head-to-head table for each division before moving to the next.

The computation is O(G) for the balance table (one pass over games) and O(G + N²) for the head-to-head matrix (one pass to accumulate, one N×N pass to render), where G is game count and N is team count per division. Both are sub-millisecond for any realistic league.

---

## Component Diagram

```
planr schedule view
        │
        ▼
ViewCmd.call()
  │
  ├─ store.load()                             [existing — loads League record]
  ├─ ScheduleState.of(league)                 [existing — derives NONE/TEAM_SCHEDULE/DRAFT/FINALIZED]
  │
  ├─ [TEAM_SCHEDULE state | --team-schedule flag]
  │     │
  │     ├─ printTeamScheduleTable(games)      [existing — #/HOME/AWAY/DIVISION table]
  │     ├─ System.out.println()               [blank separator line]
  │     └─ printTeamScheduleStats(games)      [NEW — balance + head-to-head blocks per division]
  │           │
  │           ├─ group games by divisionName  [preserving first-occurrence order via LinkedHashMap]
  │           │
  │           └─ for each division:
  │                 ├─ printBalanceBlock(divisionGames, divisionName)   [NEW]
  │                 ├─ System.out.println()                              [blank between tables]
  │                 ├─ printHeadToHeadBlock(divisionGames, divisionName) [NEW]
  │                 └─ System.out.println()                              [blank between divisions]
  │
  └─ [DRAFT/FINALIZED full view]
        └─ printFullScheduleTable(...)        [existing, unchanged]
```

**New methods added to `ScheduleCommand` (all `static`, `package-private` for testability):**

| Method | Responsibility |
|---|---|
| `printTeamScheduleStats(List<TeamGame>)` | Entry point: groups games by division, drives the per-division print loop |
| `printBalanceBlock(List<TeamGame>, String)` | Renders the HOME/AWAY BALANCE table for one division |
| `printHeadToHeadBlock(List<TeamGame>, String)` | Renders the HEAD-TO-HEAD grid for one division |

No new classes. No new files.

---

## Data Model

No model changes. The existing `TeamGame` record supplies every field needed:

| Field | Used for |
|---|---|
| `homeTeamName` | Accumulate home counts; row labels in H2H grid |
| `awayTeamName` | Accumulate away counts; column labels in H2H grid |
| `divisionName` | Grouping key |

**Derived structures (local to helpers, not persisted):**

```
// Balance block
Map<String, Integer> homeCount   — teamName → home game count
Map<String, Integer> awayCount   — teamName → away game count
List<String> sortedTeams         — alphabetically sorted team names

// Head-to-head block
Map<String, Integer> teamIndex   — teamName → row/column index
int[][] matrix                   — matrix[homeIdx][awayIdx] = game count
List<String> sortedTeams         — same sorted list, shared with balance block
```

---

## API Contracts

No new subcommands. No flags added or removed. Behavioral change only.

### `planr schedule view` — modified output

**Trigger conditions for new sections:**
- `ScheduleState.of(league) == TEAM_SCHEDULE` (no flag needed), OR
- `ViewCmd.teamScheduleView == true` (i.e., `--team-schedule` flag) in any state

**New stdout content appended after the existing matchup table:**

```
[blank line]

HOME/AWAY BALANCE — <divisionName>
<TEAM col>  HOME  AWAY  TOTAL  BALANCE
<-repeat->  ----  ----  -----  -------
<name>         N     N      N       +N
<name>         N     N      N        0
<name>         N     N      N       -N *
TOTAL          N     N      N

[blank line]

HEAD-TO-HEAD — <divisionName> (row = home team, column = away team)
<pad>         <Team1>  <Team2>  <Team3>  ...
-----------   -------  -------  -------  ...
<Team1>           —        N        N *  ...
<Team2>           N        —        N    ...
...

[blank line]

[repeat block for next division if multi-division league]
```

**Balance column formatting:**
- `BALANCE` displayed as: `+N` (positive), `-N` (negative, using ASCII hyphen), `0` (zero, no sign)
- Flag: ` *` appended after the balance value when `|balance| > 1`

**Head-to-head cell formatting:**
- Diagonal: `—` (U+2014 em dash)
- Non-diagonal: integer count as decimal string
- Flagged cell: count followed by `*` (no space), e.g., `0*`, `2*`, when the cell value differs from the row mode (see Mode Calculation below)

**Column widths:**
- Balance table `TEAM` column: `max(4, max(teamName.length() for all teams in division))`
- Balance table numeric columns: fixed at `max(header_length, digit_count_of_max_value)`, minimum 4
- Head-to-head row-label column: `max(teamName.length() for all teams in division)`
- Head-to-head data columns: `max(teamName.length(), max_cell_content_length)` per column — ensures column header aligns with values

**Exit codes:** unchanged (`0` = success, `1` = validation error, `2` = I/O error).

---

## Critical Path Walkthrough

### Path 1: `planr schedule view` — TEAM_SCHEDULE state, 4-team single division

```
1.  store.load() → League { teamSchedule: TeamSchedule([12 TeamGame records]), schedule: null }
2.  ScheduleState.of(league) → TEAM_SCHEDULE
3.  Branch: state == TEAM_SCHEDULE → true
4.  printTeamScheduleTable(games)   → prints 12-row matchup table
5.  System.out.println()            → blank line
6.  printTeamScheduleStats(games)
      a. LinkedHashMap: "Majors" → [12 games]  (single division)
      b. printBalanceBlock([12 games], "Majors")
           i.   One pass: homeCount = {A:3, B:2, C:4, D:3}, awayCount = {A:3, B:4, C:2, D:3}
           ii.  sortedTeams = [A, B, C, D]
           iii. teamW = max(4, 1) = 4; use max actual name length
           iv.  Print header + separator
           v.   A: home=3, away=3, total=6, balance=0 → no flag
                B: home=2, away=4, total=6, balance=-2 → flag " *"
                C: home=4, away=2, total=6, balance=+2 → flag " *"
                D: home=3, away=3, total=6, balance=0 → no flag
           vi.  TOTAL row: 12, 12, 24
      c. System.out.println()
      d. printHeadToHeadBlock([12 games], "Majors")
           i.   matrix[A][B]=2, matrix[A][C]=1, matrix[A][D]=1 (example)
                ... (full 4×4 matrix)
           ii.  For row A: nonDiag=[2,1,1] → mode=1 → cell[A][B]=2 is flagged
           iii. Print header row (indented by rowLabelW)
           iv.  Print each row with cell values and flags
      e. System.out.println()   → trailing blank after last division
7.  return 0
```

### Path 2: `planr schedule view --team-schedule` — FINALIZED state, 2 divisions

```
1.  store.load() → League { teamSchedule: TeamSchedule([...]), schedule: Schedule([...]) }
2.  ScheduleState.of(league) → FINALIZED
3.  teamScheduleView = true → branch taken (same as Path 1)
4.  printTeamScheduleTable(league.teamSchedule().games())
5.  printTeamScheduleStats(league.teamSchedule().games())
      a. LinkedHashMap: "Majors" → [N games], "AAA" → [M games]
      b. For "Majors": printBalanceBlock → println → printHeadToHeadBlock → println
      c. For "AAA":    printBalanceBlock → println → printHeadToHeadBlock → println
6.  return 0
```

### Path 3: Error — no team schedule

```
1.  store.load() → League { teamSchedule: null, schedule: null }
2.  ScheduleState.of(league) → NONE
3.  state == NONE → existing guard fires: System.err.println("Error: No schedule generated yet...")
4.  return 1
    [new helpers never called]
```

---

## Mode Calculation (AC-12)

For each row in the head-to-head grid, the mode of the non-diagonal cells determines which cells are flagged:

```
nonDiagValues = [matrix[row][col] for col != row]

frequency map: value → count
mode = value with highest count
  tie-breaking: if two values share the max count, choose the lower value
  (conservative — flags more cells rather than fewer in ambiguous cases)

flag cell[row][col] if matrix[row][col] != mode (and col != row)
```

If all non-diagonal cells are equal (no deviation from mode), no cells are flagged. This is the common case for a well-balanced single round-robin.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| New method(s) vs. new class | Extract `ScheduleStatsRenderer` class vs. static helpers in `ScheduleCommand` | Static helpers in `ScheduleCommand` | No new abstraction warranted for stateless, purely presentational logic; consistent with existing `printTeamScheduleTable` pattern | `ScheduleCommand` grows; if Phase 2 stats are added later, extract then |
| Single `printTeamScheduleStats` vs. separate `printBalanceSection` + `printH2HSection` called from `ViewCmd` | Two calls from ViewCmd vs. one | Single `printTeamScheduleStats` driving a per-division loop | Keeps both tables for a division adjacent in output; prevents division order being computed twice | Slightly less composable if one section is ever toggled independently |
| Division ordering | Alphabetical vs. `league.divisions()` list order vs. first-occurrence from games | First-occurrence from games (via `LinkedHashMap`) | Matches PRD "division list order"; `TeamScheduleService` emits round-robin games in `league.divisions()` order, so first-occurrence is equivalent without needing to pass `League` to the helper | Relies on `TeamScheduleService` output ordering contract; if that order ever changes, display order silently changes too |
| `*` flag in head-to-head | Append to cell (e.g., `2*`) vs. separate column | Append to cell | Compact; consistent with balance table `*` treatment | Cell column width must account for the extra character; handled in `colW` calculation |
| Diagonal cell character | ASCII `--` vs. Unicode `—` (em dash) | `—` (U+2014) | Cleaner visual separation; Java's stdout handles UTF-8 on all target platforms | May render as `?` on a misconfigured terminal; acceptable tradeoff given CLI targets modern terminals |
| Trailing blank line | After last division vs. not | After every division including last | Avoids "is this the last division?" check; simpler loop body | One trailing blank line after the last block; acceptable |

---

## Operational Concerns

- **No new failure modes.** Both helpers operate on data already loaded from disk; if `store.load()` succeeds, the helpers cannot throw.
- **Performance.** O(G + N²) per division where G ≤ ~200 games and N ≤ ~10 teams in any realistic league. Total runtime contribution: < 1ms.
- **Rollback.** Single-file change to `ScheduleCommand.java`. Revert is `git revert`; no data migration, no schema change, no state impact.
- **Testing.** The three new static methods are `static` with no instance state; `CommandTestBase` stdout capture covers end-to-end output assertions. Unit tests on the helpers directly can be written without file I/O by constructing `List<TeamGame>` inline.

---

## Implementation Plan

**Task 1 — Add `printTeamScheduleStats` and sub-helpers to `ScheduleCommand.java`**

1. After `printTeamScheduleTable`, add the call in `ViewCmd.call()`:
   ```java
   System.out.println();
   printTeamScheduleStats(league.teamSchedule().games());
   ```
   This appears in two places in `ViewCmd.call()`: the `TEAM_SCHEDULE` branch and the `--team-schedule` branch.

2. Implement `static void printTeamScheduleStats(List<TeamGame> games)`:
   - Groups games into a `LinkedHashMap<String, List<TeamGame>>` by `divisionName`
   - For each entry: calls `printBalanceBlock`, prints blank line, calls `printHeadToHeadBlock`, prints blank line

3. Implement `static void printBalanceBlock(List<TeamGame> games, String divisionName)`:
   - Accumulate `homeCount` and `awayCount` maps
   - Sort team names alphabetically
   - Compute column widths
   - Print header, separator, per-team rows (with `*` flag if `|balance| > 1`), and totals row

4. Implement `static void printHeadToHeadBlock(List<TeamGame> games, String divisionName)`:
   - Build sorted team list; build index map
   - Allocate and fill `int[][] matrix`
   - Compute column widths per column (`max(teamName.length(), max_cell_content_width)`)
   - For each row: compute mode of non-diagonal values; print cells with `*` flag where value ≠ mode

**Task 2 — Write `ScheduleCommandTest` test cases** (integration via `CommandTestBase`):

| Test | Assertion |
|---|---|
| `view` in TEAM_SCHEDULE — balance section present | stdout contains `HOME/AWAY BALANCE` |
| `view` in TEAM_SCHEDULE — correct HOME/AWAY counts | stdout contains expected per-team count values |
| `view` in TEAM_SCHEDULE — balance flag on imbalanced teams | stdout contains ` *` on rows where `|balance| > 1` |
| `view` in TEAM_SCHEDULE — head-to-head section present | stdout contains `HEAD-TO-HEAD` |
| `view` in TEAM_SCHEDULE — diagonal cells | stdout contains `—` |
| `view` in TEAM_SCHEDULE — H2H cell flag on non-mode values | stdout contains `*` on cells that deviate from row mode |
| `view --team-schedule` in DRAFT — both sections present | stdout contains both `HOME/AWAY BALANCE` and `HEAD-TO-HEAD` |
| Full `view` in DRAFT (no `--team-schedule`) — no stats | stdout does NOT contain `HOME/AWAY BALANCE` |
| Multi-division — both divisions appear in stats | stdout contains two `HOME/AWAY BALANCE` blocks with correct division names |

---

## Out of Scope / Future Work

- **Phase 2 stats** (field utilization, per-week load distribution) — deferred; belongs to a separate enhancement of the `DRAFT`/`FINALIZED` full view.
- **`--stats` toggle flag** — not needed per the resolved PRD open question; stats are always shown alongside team schedule data.
- **Filtering stats by `--division` or `--team`** — explicitly out of scope per PRD.
- **Export of stats tables** — explicitly out of scope per PRD.
