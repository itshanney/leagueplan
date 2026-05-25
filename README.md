# planr

A command-line tool for little league organizers to configure divisions, teams, and fields, then generate and manage game schedules.

All data is stored locally in `~/.planr/league.json`.

---

## Installation

Requires Java 25 and Gradle 9.4.1.

```
gradle installDist
```

This produces a runnable script at `build/install/planr/bin/planr`. Add it to your PATH or invoke it directly.

---

## Quick start

```bash
# 1. Configure league-wide schedule parameters
planr config set --sunrise 09:00 --sunset 18:00 --start 2026-06-01 --end 2026-08-31

# 2. Add a division and its teams
planr division add "Majors" --duration 120 --target 10
planr team add "Majors" "Blue Jays"
planr team add "Majors" "Cardinals"
planr team add "Majors" "Red Sox"
planr team add "Majors" "Yankees"

# 3. Add a field
planr field add "Riverside Park" --address "100 River Rd"

# (optional) restrict availability by day of the week for all fields
planr config dow set --day wednesday --start 16:00 --end 21:00
planr config blockday add --day sunday

# 4. Phase 1 — generate the team schedule (matchups only, no dates yet)
planr schedule generate

# 5. Review and optionally adjust home/away assignments
planr schedule view
planr schedule game edit 3 --home Cardinals

# 6. Phase 2 — assign dates, times, and fields
echo yes | planr schedule assign

# 7. Review, finalize, and export
planr schedule view
planr schedule finalize
planr schedule export
```

---

## Commands

### League configuration

League configuration sets parameters used by schedule generation. Sunrise, sunset, and season dates are required before Phase 2 (`planr schedule assign`) can run.

```
planr config set [--sunrise <HH:mm>] [--sunset <HH:mm>] [--start <YYYY-MM-DD>] [--end <YYYY-MM-DD>]
planr config show
```

- `--sunrise` and `--sunset` define the default open window applied to every field on every calendar day
- `--start` and `--end` define the season date range
- Each option is independent; `config set` merges with existing values rather than replacing them

`config show` also displays any configured day-of-week windows and blocked days (see below).

**Example**

```
$ planr config set --sunrise 09:00 --sunset 18:00 --start 2026-06-01 --end 2026-08-31
League config updated.

$ planr config show
League Configuration
--------------------
Sunrise:        09:00
Sunset:         18:00
Season start:   2026-06-01
Season end:     2026-08-31

Day-of-week windows:
  Wednesday: 16:00 – 21:00

Blocked days of week:
  Sunday
```

---

### Day-of-week windows

Day-of-week windows narrow the effective open window for **all fields** on a specific day of the week. For example, if fields generally open at 09:00 but Wednesday evenings start at 16:00, set a Wednesday window instead of adding identical blocks to every field. A day-of-week window takes precedence over the global sunrise/sunset but is overridden by any field-level `FieldDateOverride` on a specific date.

```
planr config dow set   --day <DAY> --start <HH:mm> --end <HH:mm>
planr config dow clear --day <DAY>
planr config dow list
```

- `<DAY>` accepts full names (`wednesday`) or 3-letter abbreviations (`wed`), case-insensitively
- Setting a window for a day that already has one replaces it
- If field-level blocks or overrides already exist on matching dates within the season, a warning is printed with the count

**Example**

```
$ planr config dow set --day wednesday --start 16:00 --end 21:00
Day-of-week window set: Wednesday 16:00–21:00.

$ planr config dow list
DAY          OPEN   CLOSE
-----------  -----  -----
Wednesday    16:00  21:00
```

---

### Blocked days of the week

Blocked days mark a day of the week as unavailable for **all fields** throughout the season (e.g., no games on Sundays). A blocked day removes all slots on matching dates. A `FieldDateOverride` on a specific field and date still takes precedence, allowing individual rescued dates (e.g., a rescheduled game on an otherwise-blocked Sunday).

```
planr config blockday add    --day <DAY>
planr config blockday remove --day <DAY>
planr config blockday list
```

- `<DAY>` accepts full names or 3-letter abbreviations, case-insensitively
- Adding a day that is already blocked exits with an error
- When existing field-level entries fall on matching dates within the season, a warning is printed noting that `FieldDateOverride` entries still take precedence

**Example**

```
$ planr config blockday add --day sunday
Sunday added to blocked days.

$ planr config blockday list
Blocked days of week:
  Sunday
```

---

### Divisions

Divisions group teams by age or skill level. Each division carries a game duration (used to pack the field schedule) and a season target (games per team).

```
planr division add <name> --duration <minutes> --target <n>
planr division edit <name> [--name <new-name>] [--duration <minutes>] [--target <n>]
planr division delete <name>
planr division list
```

The minimum valid target is `N−1` where N is the number of teams (enough for one full round-robin). A division can only be deleted when it has no teams.

**Example**

```
$ planr division add "Majors" --duration 120 --target 10
Division "Majors" added (120 min/game).

$ planr division list
DIVISION    DURATION    TARGET    TEAMS
--------    --------    ------    -----
Majors      120 min     10        0
```

---

### Teams

Teams belong to a division. Team names must be unique within their division (case-insensitive).

```
planr team add <division> <team>
planr team edit <division> <team> --name <new-name>
planr team delete <division> <team>
planr team list <division>
```

**Example**

```
$ planr team add "Majors" "Blue Jays"
Team "Blue Jays" added to division "Majors".

$ planr team list "Majors"
Blue Jays
Cardinals
Red Sox
Yankees
```

---

### Fields

Fields are the physical locations where games are played. By default, every field is available during the league-wide sunrise-to-sunset window on every day of the season. Use blocks and overrides to record exceptions.

```
planr field add <name> [--address <address>]
planr field edit <name> [--name <new-name>] [--address <address>]
planr field delete <name>
planr field list
```

Deleting a field also removes all its blocks and date overrides.

**Example**

```
$ planr field add "Riverside Park" --address "100 River Rd"
Field "Riverside Park" added.

$ planr field list
NAME               ADDRESS        BLOCKS    OVERRIDES
---------------    -----------    ------    ---------
Riverside Park     100 River Rd   0         0
```

---

### Field blocks

Blocks mark specific date/time ranges when a field is unavailable (e.g., a holiday, a maintenance window). They are subtracted from the effective open window on that date.

```
planr field block add <field> --date <YYYY-MM-DD> --start <HH:mm> --end <HH:mm>
planr field block edit <field> <number> [--date <YYYY-MM-DD>] [--start <HH:mm>] [--end <HH:mm>]
planr field block delete <field> <number>
planr field block list <field>
```

**Example**

```
$ planr field block add "Riverside Park" --date 2026-07-04 --start 00:00 --end 23:59
Block #1 added to field "Riverside Park" (2026-07-04 00:00–23:59).

$ planr field block list "Riverside Park"
#    DATE        START  END
-    ----------  -----  -----
1    2026-07-04  00:00  23:59
```

---

### Field date overrides

Overrides replace the league-level sunrise/sunset with a custom open window on a specific field and date (e.g., a field with extended evening lighting, or an earlier close for a school night).

```
planr field override add <field> --date <YYYY-MM-DD> --start <HH:mm> --end <HH:mm>
planr field override edit <field> <number> [--date <YYYY-MM-DD>] [--start <HH:mm>] [--end <HH:mm>]
planr field override delete <field> <number>
planr field override list <field>
```

At most one override per field per date. Blocks are applied on top of the override window.

---

### Schedule

Schedule generation is split into two phases, with a review step in between.

#### Phase 1 — generate team schedule

```
planr schedule generate
```

Generates a single round-robin for each eligible division (`N*(N-1)/2` matchups, one per team pair), then runs fill rounds until each team reaches the division's `targetGamesPerTeam`. Home/away assignments are balanced by tracking each team's running imbalance.

Prints fill-round progress logs and a full matchup table. Re-running while a team schedule or draft already exists prompts for confirmation; blocked if a finalized schedule exists.

**Preconditions:** season dates configured; at least one division with ≥ 2 teams; per-division target ≥ N−1.

#### Review and edit home/away

```
planr schedule game edit <number> --home <team>
```

Makes the named team the home team for the specified game (and the other team away). Available in `TEAM_SCHEDULE` and `DRAFT` states. No-op if the team is already home.

#### Phase 2 — assign dates, times, and fields

```
planr schedule assign
```

Reads the confirmed team schedule and runs the OR-Tools CP-SAT solver to assign each game a date, start time, and field. Displays a feasibility estimate per division before prompting for confirmation.

**Preconditions:** team schedule exists; at least one field configured; season dates configured.

**Constraints enforced:**
- **C1** — each game assigned exactly once
- **C2** — no two games on the same field overlap (including a 15-minute buffer between games)
- **C3** — no team plays more than once on the same calendar day

**Objective:** minimise the maximum number of games any team plays in a single ISO calendar week (spreads the season evenly).

#### View, status, export, finalize

```
planr schedule view [--division <name>] [--team <name>] [--field <name>]
planr schedule status
planr schedule export
planr schedule finalize
```

- **`view`** — in `TEAM_SCHEDULE` state, shows the matchup-only table (`#`, `HOME`, `AWAY`, `DIVISION`). In `DRAFT`/`FINALIZED`, shows the full table with date, time, and field. Filters by division, team, or field apply in `DRAFT`/`FINALIZED` only.
- **`status`** — shows the current state (`TEAM_SCHEDULE`, `DRAFT`, or `FINALIZED`) with per-division game counts, targets, and team counts.
- **`export`** — writes JSON to stdout. In `TEAM_SCHEDULE` state: `game_number`, `home_team`, `away_team`, `division_name`. In `DRAFT`/`FINALIZED`: adds `date`, `start_time`, `field_name`, and `status`.
- **`finalize`** — promotes a draft to `FINALIZED` after interactive confirmation. Irreversible.

#### Override individual games

```
planr schedule game override <number> [--date <date>] [--start <HH:mm>] [--field <field>] [--home <team>] [--away <team>]
```

Adjusts one game on a finalized schedule. Any combination of fields may be changed. A non-blocking warning is printed to stderr if the change creates a field conflict. Overridden games are marked with `*` in `planr schedule view`.

---

## Phase 1 algorithm: how team schedules are built

`planr schedule generate` runs in two stages — a complete round-robin followed by fill rounds — to produce a matchup list where every team plays roughly the same number of home and away games.

### Stage 1 — circle-method round-robin

For each eligible division (≥ 2 teams), the scheduler generates one complete round-robin using the classic circle method:

1. One team is fixed in position; the remaining `N−1` teams form a rotating list.
2. If N is odd, a null bye-slot is appended so the team count is always even.
3. There are `N−1` rounds. In each round, `N/2` pairs are read from the circle:
   - Pair 0: fixed team vs. last position in the rotating list.
   - Pairs 1…N/2−1: `rotating[i−1]` vs. `rotating[N−2−i]` (symmetric about the centre).
   - Any pairing involving the bye-slot is discarded.
4. After each round, the rotating list advances by moving its last element to the front.

This produces exactly `N*(N-1)/2` games — one for every distinct team pair — with no team playing twice in the same round.

**Home/away assignment in Stage 1:** each pair appears in a fixed column index (`specI`) that stays consistent across all rounds as the circle rotates. The left team is home when `(specI + r) % 2 == 0`, otherwise the right team is home. Because `specI` is constant per pair but `r` increments each round, home advantage alternates between the two teams on successive meetings. For a four-team division this produces exactly 1 home and 1 away game per team after two meetings.

### Stage 2 — fill rounds

After the round-robin, each team may have fewer games than the division's `targetGamesPerTeam`. Fill rounds run repeatedly until all teams reach the target or no more pairs can be formed:

1. For each division, collect all teams still below target.
2. Sort them: fewest games first; UUID as a stable tiebreaker to keep output deterministic.
3. Pair greedily: teams at positions 0+1, 2+3, 4+5, … each form one game. The last team is skipped if an odd number remain below target in this round.
4. **Home/away in fill rounds:** the team with the larger away-over-home imbalance (`awayCount − homeCount`) gets the home slot. Ties go to the team that sorted first (fewer games so far). This continuously re-balances home/away counts so no team accumulates a large advantage.
5. Counters are updated and the process repeats. It terminates when every team is at target **or** when a full pass produces no new games (which happens with an odd team count — the last unpaired team can never get its final game from another team in the same position).

### Game number assignment

Game numbers are not assigned during generation. After all stage-1 and fill-round games are collected in order, a single pass assigns stable 1-based integers (`1, 2, 3, …`). This means game numbers are globally ordered: all of division A's round-robin games appear before division B's, and fill games appear after all round-robin games.

### Example: 4 teams, target 6

| Stage | Round | Games produced | Notes |
|---|---|---|---|
| Round-robin | 1 | A-B, C-D | 3 games per round, 3 rounds |
| Round-robin | 2 | A-C, D-B | circle rotates once |
| Round-robin | 3 | A-D, B-C | circle rotates again |
| Fill | 1 | A-B, C-D | all 4 teams need 3 more; paired by deficit |
| Fill | 2 | A-C, D-B | still 3 short each |
| Fill | 3 | A-D, B-C | targets reached — done |

After 6 fill games, every team has exactly 6 games, 3 home and 3 away.

---

## Phase 2 algorithm: how field assignment works

`planr schedule assign` takes the confirmed matchup list from Phase 1 and finds a legal assignment of dates, times, and fields using [OR-Tools CP-SAT](https://developers.google.com/optimization/reference/python/sat/python/cp_model), Google's constraint-programming solver.

### Step 1 — enumerate valid slots

Before the solver runs, the scheduler enumerates every valid start time across the entire season:

1. Walk every calendar date from `--start` to `--end`.
2. For each date and field, determine the open window using a four-level precedence rule:
   1. **`FieldDateOverride`** — if a date-specific override exists for this field, use its `openStart`→`openEnd`. This wins over everything, including blocked days, allowing individual rescued dates on an otherwise-blocked day.
   2. **Blocked day** — if the day of week is in the league's blocked-days list and no override applies, the date produces no slots.
   3. **Day-of-week window** — if a league-wide day-of-week window is configured for this day (e.g., Wednesdays open at 16:00), use its `openStart`→`openEnd` in place of the global sunrise/sunset.
   4. **Global sunrise/sunset** — fall back to the league-wide `sunrise`→`sunset` window.
3. Subtract any field-level blocks (`planr field block`) that fall on that date. This may fragment the open window into multiple sub-ranges.
4. Within each sub-range, advance a cursor in 15-minute increments. Each position where a game of the division's duration fits before the window closes becomes one **slot** `(date, field, startTime)`.

Slots are enumerated separately for each division because divisions have different game durations (a 90-minute division gets more slots per day than a 120-minute one). The total slot count is printed in the feasibility check line before the solver starts.

### Step 2 — build the constraint model

The solver works on a matrix of **boolean decision variables**: one `BoolVar` per `(fixture, slot)` pair. Setting a variable to `true` means "assign this game to this slot." Three constraints restrict which assignments are legal:

**C1 — each game assigned at most once**

For each fixture, at most one of its slot variables may be `true`. This is expressed as a single `addAtMostOne` over all slot variables for that fixture. An auxiliary boolean `isAssigned[f]` equals the sum, recording whether the fixture received a slot at all.

`addAtMostOne` (not `addExactlyOne`) is intentional: if there are fewer slots than games, the solver assigns as many as it can and saves a partial Draft rather than failing.

**C2 — no two games overlap on the same field**

Rather than checking every pair of games on the same field for time overlap (which grows as O(N²)), each variable is registered under every 15-minute tick it would occupy:

```
ticks covered = [slotStartMinute, slotStartMinute + gameDuration + 15-minute buffer)
                in 15-minute steps
```

For each `(field, date, tick)` bucket, `addAtMostOne` ensures at most one game is active at that tick. This bounds the number of C2 constraints to `numFields × numDays × ticksPerDay` regardless of how many fixtures exist, and the 15-minute buffer guarantees turnover time between consecutive games on a field.

**C3 — no team plays twice on the same calendar day**

For each `(team, date)` pair, `addAtMostOne` is applied across all games where that team appears (home or away) on that date.

### Step 3 — define the objective

The solver optimises a two-level objective encoded as a single weighted sum:

```
maximise:  bigM × totalAssigned  −  maxWeekLoad
where  bigM = totalFixtures + 1
```

- **Primary goal (totalAssigned):** assign as many games as possible. The `bigM` coefficient guarantees lexicographic dominance — gaining one more assigned game always outweighs any reduction in `maxWeekLoad`.
- **Secondary goal (maxWeekLoad):** minimise the maximum number of games any team plays in a single ISO calendar week. `maxWeekLoad` is tracked by adding one constraint per `(team, ISO week)` pair: `sum(vars for that team-week) ≤ maxWeekLoad`. When the season has enough capacity for all games, the solver uses its remaining freedom to spread games evenly across the calendar.

### Step 4 — solve and collect results

The CP-SAT solver runs for up to 300 seconds. Progress is streamed to stdout at the 25%, 50%, and 75% time marks. The solver may finish early if it proves the solution is optimal.

After the solver returns:

- **OPTIMAL or FEASIBLE** — every variable where `booleanValue == true` becomes a `ScheduledGame`. Games are sorted by date, then start time, then field name. A Draft is saved regardless of whether all fixtures were assigned.
- **UNKNOWN** — the solver timed out without finding any feasible solution (returned before placing a single game). This indicates the season window or field availability is too constrained. A failure message is returned and no Draft is saved.
- **INFEASIBLE** — theoretically unreachable with `addAtMostOne` constraints; treated as an internal error.

---

## Notes

- Division and field names are matched case-insensitively in all commands
- Exit code `0` = success, `1` = validation error, `2` = data file I/O error
- Run `planr --help` or append `--help` to any subcommand for usage details
