# planr

A command-line tool for little league organizers to configure divisions, teams, and fields in preparation for schedule generation.

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

```
planr division add "Majors" --duration 120
planr team add "Majors" "Blue Jays"
planr team add "Majors" "Cardinals"

planr field add "Riverside Park" --address "100 River Rd"
planr field window add "Riverside Park" --day Saturday --start 09:00 --end 17:00
```

---

## Commands

### Divisions

Divisions group teams by age or skill level. Each division has a game duration that the scheduler uses to pack fields without overlap.

```
planr division add <name> --duration <minutes>
planr division edit <name> [--name <new-name>] [--duration <minutes>]
planr division delete <name>
planr division list
```

**Examples**

```
$ planr division add "Majors" --duration 120
Division "Majors" added (120 min/game).

$ planr division add "T-Ball" --duration 60
Division "T-Ball" added (60 min/game).

$ planr division edit "Majors" --duration 90
Division "Majors" updated.

$ planr division list
DIVISION    DURATION    TEAMS
--------    --------    -----
Majors      90 min      0
T-Ball      60 min      0
```

A division can only be deleted when it has no teams assigned to it.

---

### Teams

Teams belong to a division. Team names must be unique within their division.

```
planr team add <division> <team>
planr team edit <division> <team> --name <new-name>
planr team delete <division> <team>
planr team list <division>
```

**Examples**

```
$ planr team add "Majors" "Blue Jays"
Team "Blue Jays" added to division "Majors".

$ planr team add "Majors" "Cardinals"
Team "Cardinals" added to division "Majors".

$ planr team list "Majors"
Blue Jays
Cardinals

$ planr team edit "Majors" "Blue Jays" --name "Royals"
Team "Blue Jays" renamed to "Royals" in division "Majors".

$ planr team delete "Majors" "Cardinals"
Team "Cardinals" removed from division "Majors".
```

---

### Fields

Fields are the physical locations where games are played. Each field has a name and an optional address.

```
planr field add <name> [--address <address>]
planr field edit <name> [--name <new-name>] [--address <address>]
planr field delete <name>
planr field list
```

**Examples**

```
$ planr field add "Riverside Park" --address "100 River Rd"
Field "Riverside Park" added.

$ planr field add "Eastside Diamond"
Field "Eastside Diamond" added.

$ planr field list
NAME               ADDRESS        WINDOWS
---------------    -----------    -------
Riverside Park     100 River Rd   0
Eastside Diamond   (none)         0
```

To clear a field's address, pass an empty string: `planr field edit "Eastside Diamond" --address ""`

Deleting a field also deletes all of its availability windows.

---

### Availability windows

Availability windows tell the scheduler when each field can be used. Each window defines a recurring weekly time block — optionally restricted to a single division.

```
planr field window add <field> --day <day> --start <HH:mm> --end <HH:mm> [--division <division>]
planr field window edit <field> <window-number> [--day <day>] [--start <HH:mm>] [--end <HH:mm>] [--division <division>] [--clear-division]
planr field window delete <field> <window-number>
planr field window list <field>
```

- `--day` accepts full names (`Saturday`) or 3-letter abbreviations (`Sat`), case-insensitive
- `--start` and `--end` use 24-hour `HH:mm` format
- `--division` restricts the window to games from that division only; omit for any division
- `--clear-division` removes a division restriction from an existing window
- Window numbers in `edit` and `delete` correspond to the `#` column shown by `planr field window list`

**Examples**

```
$ planr field window add "Riverside Park" --day Saturday --start 09:00 --end 17:00
Availability window #1 added to field "Riverside Park" (Saturday 09:00-17:00, all divisions).

$ planr field window add "Riverside Park" --day Sunday --start 09:00 --end 12:00 --division Majors
Availability window #2 added to field "Riverside Park" (Sunday 09:00-12:00, Majors only).

$ planr field window list "Riverside Park"
#    DAY        START  END    DIVISION
-    ---------  -----  -----  -------------
1    Saturday   09:00  17:00  All divisions
2    Sunday     09:00  12:00  Majors

$ planr field window edit "Riverside Park" 2 --end 14:00
Availability window #2 on "Riverside Park" updated.

$ planr field window delete "Riverside Park" 1
Availability window #1 on "Riverside Park" deleted.
```

---

## Notes

- Division and field names are matched case-insensitively in all commands
- Exit code `0` = success, `1` = validation error, `2` = data file I/O error
- Run `planr --help` or append `--help` to any subcommand for usage details
