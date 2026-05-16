# Tech Spec: Field Management — `planr` CLI

**Date:** 2026-05-16
**Status:** Ready for Implementation
**Scope:** Field Management acceptance criteria from `features/2026-05-15-league-planner-core-scheduling.md`
**Phase:** CLI prototype (follows Division & Team Management slice)

---

## Overview

This slice extends the `planr` CLI with two new subcommand groups: 
* `planr field` for managing baseball fields (name and optional address) 
* `planr field window` for managing availability windows within a field. 

Each window defines a recurring day-of-week time block — with an optional restriction to a single division — that the scheduler uses to assign games. All state flows through the same `LeagueStore` / `league.json` pattern established in the Division & Team slice. 

The only structural changes are: 
1. a new `List<Field>` collection added to the `League` record
1. two new model records (`Field`, `AvailabilityWindow`)
1. the `jackson-datatype-jsr310` dependency added to support `LocalTime` serialization
1. a schema version bump from `1` to `2` with an in-place migration. No other components change.

---

## Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│  planr (CLI entry point)                                        │
│  PlanrApp — root @Command; wires subcommands, injects store     │
└────────────────────┬────────────────────────────────────────────┘
                     │ dispatches to
     ┌───────────────┼───────────────┐
     ▼               ▼               ▼
┌──────────┐  ┌──────────┐  ┌──────────────────────────────────┐
│ Division │  │ Team     │  │ FieldCommand                     │
│ Command  │  │ Command  │  │ add / edit / delete / list       │
│(unchanged)│ │(unchanged)│  │ + nested WindowCommand subgroup  │
└──────────┘  └──────────┘  └────────────────┬─────────────────┘
                                              │ nested subcommand
                                              ▼
                                   ┌──────────────────────┐
                                   │ FieldWindowCommand   │
                                   │ add / edit / delete  │
                                   │ / list               │
                                   └──────────┬───────────┘
                                              │
                             ┌────────────────┴──────────┐
                             ▼                           ▼
                  ┌──────────────────────┐   ┌──────────────────────┐
                  │  LeagueStore         │   │  League model        │
                  │  (unchanged except   │   │  (extended with      │
                  │  migration logic)    │   │  List<Field>)        │
                  └──────────┬───────────┘   └──────────────────────┘
                             │ read / atomic write
                             ▼
                  ┌──────────────────────┐
                  │  ~/.planr/league.json│
                  └──────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| `FieldCommand` | Validates and executes field CRUD operations via `LeagueStore` |
| `FieldWindowCommand` | Validates and executes availability window CRUD operations nested under a specific field |
| `Field` (record) | Immutable value type: field identity, name, optional address, ordered list of availability windows |
| `AvailabilityWindow` (record) | Immutable value type: day-of-week block with start/end times and optional division restriction stored as a division UUID |
| `LeagueStore` | Extended with v1→v2 migration: detects `version: 1` on load, injects `fields: []`, writes back before returning |

---

## Data Model

### In-Memory Extension (Java Records)

```
League (version 2)
  ├── List<Division>       (unchanged)
  └── List<Field>          (new)
        ├── id: UUID
        ├── name: String                  (unique across all fields, case-insensitive)
        ├── address: String               (nullable; null when omitted)
        └── List<AvailabilityWindow>      (ordered; index position is the user-visible "window number")
              ├── id: UUID
              ├── dayOfWeek: DayOfWeek    (java.time.DayOfWeek enum)
              ├── startTime: LocalTime    (HH:mm, 24-hour)
              ├── endTime: LocalTime      (HH:mm, 24-hour; must be strictly after startTime)
              └── divisionId: UUID        (nullable; null = unrestricted, applies to all divisions)
```

All model classes are Java records — immutable value types. Mutation produces new record instances; `LeagueStore` replaces the in-memory root and writes atomically.

`Field` mirrors the pattern of `Division`: the list of `AvailabilityWindow` items is owned by the field record, and window operations produce a new `Field` record replacing the old one in `League`.

### JSON File Shape (`~/.planr/league.json`, version 2)

```json
{
  "version": 2,
  "divisions": [ ... ],
  "fields": [
    {
      "id": "a1b2c3d4-...",
      "name": "Riverside Park",
      "address": "123 Main St",
      "windows": [
        {
          "id": "e5f6g7h8-...",
          "dayOfWeek": "SATURDAY",
          "startTime": "09:00",
          "endTime": "17:00",
          "divisionId": null
        },
        {
          "id": "i9j0k1l2-...",
          "dayOfWeek": "SUNDAY",
          "startTime": "09:00",
          "endTime": "12:00",
          "divisionId": "f1a2b3c4-..."
        }
      ]
    }
  ]
}
```

**Serialization notes:**
- `dayOfWeek` serializes as the enum name string (`"SATURDAY"`) via `JavaTimeModule` with `WRITE_ENUMS_USING_TO_STRING`.
- `startTime` / `endTime` serialize as `"HH:mm"` strings; `JavaTimeModule` requires `WRITE_DATES_AS_TIMESTAMPS` disabled and a custom `LocalTime` serializer scoped to `"HH:mm"` format (not ISO `"HH:mm:ss"`).
- `divisionId` serializes as a UUID string or `null`.
- `address` serializes as a string or `null`.

### Schema Migration (v1 → v2)

`LeagueStore.load()` gains a migration guard after deserialization:

```
if (league.version() == 1) {
    league = new League(2, league.divisions(), List.of());
    save(league);   // write v2 file before returning
}
```

This is a one-time, in-place migration triggered on the first `planr` invocation after upgrading. It is idempotent — a second invocation sees `version: 2` and skips it.

**Assumption (flag for confirmation):** There is no rollback requirement from v2 → v1. An older `planr` binary reading a v2 file will deserialize successfully because `FAIL_ON_UNKNOWN_PROPERTIES` is disabled; it will simply not see the `fields` array.

---

## Build Configuration Changes

One new dependency in `build.gradle`:

```groovy
implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2'
```

The `ObjectMapper` in `LeagueStore` gains two additions:

```java
.registerModule(new JavaTimeModule())
.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
```

The `LocalTime` serializer must be configured to emit `"HH:mm"` (not the default `"HH:mm:ss"`). Use a `SimpleModule` to register a custom `LocalTimeSerializer` with `DateTimeFormatter.ofPattern("HH:mm")` applied to the `JavaTimeModule`.

---

## Project Structure Changes

```
src/main/java/org/leagueplan/planr/
├── PlanrApp.java                     # add FieldCommand to subcommands list
├── command/
│   ├── DivisionCommand.java          (unchanged)
│   ├── TeamCommand.java              (unchanged)
│   ├── FieldCommand.java             # NEW: @Command("field"); add/edit/delete/list + window nested group
│   └── FieldWindowCommand.java       # NEW: @Command("window"); add/edit/delete/list
├── model/
│   ├── League.java                   # extend: add List<Field>; add field helper methods
│   ├── Division.java                 (unchanged)
│   ├── Team.java                     (unchanged)
│   ├── Field.java                    # NEW: record id/name/address/windows
│   └── AvailabilityWindow.java       # NEW: record id/dayOfWeek/startTime/endTime/divisionId
└── store/
    └── LeagueStore.java              # extend: register JavaTimeModule; add v1→v2 migration
```

---

## Command Contracts

All commands print one line to `stdout` on success and one line to `stderr` on error. Exit codes: `0` = success, `1` = validation error, `2` = I/O error.

Window numbers in all window commands are **1-based** display indices corresponding to the order of windows shown by `planr field window list`. They are never UUIDs. (See Tradeoff Log.)

### Field Commands

#### `planr field add <name> [--address <address>]`

| | |
|---|---|
| Validation | `name` non-empty; name unique across fields (case-insensitive) |
| On success | Adds field with empty windows list, saves file. Prints: `Field "Riverside Park" added.` |
| On error (duplicate) | `Error: Field "Riverside Park" already exists.` Exit 1 |

#### `planr field edit <name> [--name <new-name>] [--address <address>]`

| | |
|---|---|
| Validation | Field with `<name>` must exist; at least one of `--name` or `--address` required; new name (if provided) must not conflict with an existing field; new name non-empty |
| On success | Updates field, saves file. Prints: `Field "Riverside Park" updated.` |
| On error (not found) | `Error: Field "Riverside Park" not found.` Exit 1 |
| On error (no options) | `Error: At least one of --name or --address must be provided.` Exit 1 |

**Note on clearing address:** `--address ""` (empty string) sets the address to `null` in the model. This is the only way to remove an address. An empty address string is stored as `null`; the display shows nothing in the ADDRESS column.

#### `planr field delete <name>`

| | |
|---|---|
| Validation | Field must exist |
| On success | Removes field and all its windows atomically. Prints: `Field "Riverside Park" and 3 availability window(s) deleted.` (or `… and 0 availability windows deleted.` if none) |
| On error (not found) | `Error: Field "Riverside Park" not found.` Exit 1 |

There is no guard preventing deletion of a field referenced by the scheduler — field deletion is always permitted in this slice. The scheduling slice is responsible for its own referential integrity checks.

#### `planr field list`

| | |
|---|---|
| Validation | None |
| On success (fields exist) | Tabular output — one row per field: NAME, ADDRESS, WINDOWS |
| On success (no fields) | `No fields configured. Use 'planr field add' to create one.` |

```
NAME              ADDRESS                  WINDOWS
--------------    ---------------------    -------
Riverside Park    123 Main St              3
Eastside Field    (none)                   1
```

---

### Field Window Commands

All window commands are nested under `planr field window`.

#### `planr field window add <field-name> --day <day> --start <HH:mm> --end <HH:mm> [--division <division-name>]`

| | |
|---|---|
| Validation | Field must exist; `--day` is a valid day name (full or 3-letter abbreviation, case-insensitive); `--start` and `--end` parse as `HH:mm`; `end > start`; if `--division` provided, division must exist |
| On success | Appends window to field's list, saves file. Prints: `Availability window #3 added to field "Riverside Park" (Saturday 09:00–17:00, all divisions).` or `… (Saturday 09:00–12:00, Majors only).` |
| On error (field not found) | `Error: Field "Riverside Park" not found.` Exit 1 |
| On error (invalid end time) | `Error: End time must be after start time.` Exit 1 |
| On error (invalid day) | `Error: Invalid day "Weekday". Use a day name such as Monday, Tuesday, ..., Sunday.` Exit 1 |
| On error (division not found) | `Error: Division "Majors" not found.` Exit 1 |

**Day parsing:** Accept full names (`Monday`) and 3-letter abbreviations (`Mon`), case-insensitive. Resolve to `java.time.DayOfWeek`.

#### `planr field window edit <field-name> <window-number> [--day <day>] [--start <HH:mm>] [--end <HH:mm>] [--division <division-name>] [--clear-division]`

| | |
|---|---|
| Validation | Field must exist; `<window-number>` is a valid 1-based index into the field's window list; at least one option required; same field-level validations as `add`; `--division` and `--clear-division` are mutually exclusive |
| On success | Replaces window at that index, saves file. Prints: `Availability window #2 on "Riverside Park" updated.` |
| On error (field not found) | `Error: Field "Riverside Park" not found.` Exit 1 |
| On error (invalid window number) | `Error: Window #5 not found for field "Riverside Park" (1–3 are valid).` Exit 1 |
| On error (both division flags) | `Error: --division and --clear-division cannot be used together.` Exit 1 |

`--clear-division` sets `divisionId` to `null` (unrestricted), removing any previous division restriction.

#### `planr field window delete <field-name> <window-number>`

| | |
|---|---|
| Validation | Field must exist; `<window-number>` is a valid 1-based index |
| On success | Removes window at that index (remaining windows shift up), saves file. Prints: `Availability window #2 on "Riverside Park" deleted.` |
| On error (field not found) | `Error: Field "Riverside Park" not found.` Exit 1 |
| On error (invalid window number) | `Error: Window #5 not found for field "Riverside Park" (1–3 are valid).` Exit 1 |

#### `planr field window list <field-name>`

| | |
|---|---|
| Validation | Field must exist |
| On success (windows exist) | Tabular output with columns: `#`, `DAY`, `START`, `END`, `DIVISION` |
| On success (no windows) | `No availability windows for field "Riverside Park". Use 'planr field window add' to create one.` |
| On error | `Error: Field "Riverside Park" not found.` Exit 1 |

```
#   DAY         START    END      DIVISION
-   ---------   -----    -----    -------------------
1   Saturday    09:00    17:00    All divisions
2   Sunday      09:00    12:00    Majors
3   Monday      17:00    20:00    AAA
```

If a window's `divisionId` references a division that no longer exists, the DIVISION column displays `[deleted]` and `planr field window list` prints a trailing warning line:

```
Warning: 1 window(s) reference deleted divisions. Update or remove them before generating a schedule.
```

---

## League Model Extensions

```java
// New methods on League record:
public Optional<Field> findField(String name)       // case-insensitive
public boolean hasField(String name)
public League withFieldAdded(Field field)
public League withFieldReplaced(UUID id, Field replacement)
public League withFieldRemoved(UUID id)

// New methods on Field record:
public Field withWindowAdded(AvailabilityWindow w)
public Field withWindowReplaced(int zeroBasedIndex, AvailabilityWindow w)
public Field withWindowRemoved(int zeroBasedIndex)
```

The `League` constructor signature changes from `League(int version, List<Division> divisions)` to `League(int version, List<Division> divisions, List<Field> fields)`. Jackson deserializes this via `@JsonCreator` constructor (already the pattern for records).

---

## Critical Path Walkthroughs

### 1. Add a Field with Address

```
User: planr field add "Riverside Park" --address "123 Main St"

1. Picocli parses: name="Riverside Park", address="123 Main St"
2. FieldCommand.AddCmd.call():
   a. Validate name non-empty ✓
3. LeagueStore.load() → League (v2; migration runs if needed)
4. league.hasField("Riverside Park") → false ✓
5. Build Field record: id=UUID.randomUUID(), name="Riverside Park",
   address="123 Main St", windows=List.of()
6. league.withFieldAdded(field) → new League
7. LeagueStore.save(league) — atomic write
8. Print: Field "Riverside Park" added.
9. Exit 0
```

---

### 2. Add an Availability Window with Division Restriction

```
User: planr field window add "Riverside Park" --day Saturday --start 09:00 --end 17:00 --division Majors

1. Picocli parses: fieldName="Riverside Park", day="Saturday",
   start="09:00", end="17:00", division="Majors"
2. FieldWindowCommand.AddCmd.call():
   a. Parse "Saturday" → DayOfWeek.SATURDAY ✓
   b. Parse "09:00"   → LocalTime.of(9,0)  ✓
   c. Parse "17:00"   → LocalTime.of(17,0) ✓
   d. Validate endTime.isAfter(startTime)  ✓
3. LeagueStore.load() → League
4. league.findField("Riverside Park") → Field (found) ✓
5. league.findDivision("Majors") → Division (found), divisionId = division.id() ✓
6. Build AvailabilityWindow: id=UUID.randomUUID(), dayOfWeek=SATURDAY,
   startTime=09:00, endTime=17:00, divisionId=<Majors UUID>
7. field.withWindowAdded(window) → new Field (window appended at end)
8. league.withFieldReplaced(field.id(), newField) → new League
9. LeagueStore.save(league) — atomic write
10. windowNumber = newField.windows().size()  (e.g., 1)
11. Print: Availability window #1 added to field "Riverside Park" (Saturday 09:00–17:00, Majors only).
12. Exit 0
```

**Error path (end ≤ start):**
- After parsing both times, `endTime.isAfter(startTime)` is false
- Print to stderr: `Error: End time must be after start time.`
- Exit 1 (no file write)

---

### 3. Delete a Field (cascade delete windows)

```
User: planr field delete "Riverside Park"

1. Picocli parses: name="Riverside Park"
2. FieldCommand.DeleteCmd.call():
3. LeagueStore.load() → League
4. league.findField("Riverside Park") → Field (found)
5. windowCount = field.windows().size()  (e.g., 3)
6. league.withFieldRemoved(field.id()) → new League (field + all windows gone)
7. LeagueStore.save(league) — atomic write
8. Print: Field "Riverside Park" and 3 availability window(s) deleted.
9. Exit 0
```

No confirmation prompt — deletion is immediate. The CLI spec does not require a `--force` flag for field deletion. (The web application layer will provide a confirmation modal; the CLI is a developer/admin tool.)

---

### 4. Schema Migration (v1 → v2) on First Post-Upgrade Invocation

```
User runs any planr command after upgrading to the field management release.

1. LeagueStore.load():
   a. league.json exists; deserialize → League(version=1, divisions=[...])
   b. Detect version == 1
   c. Construct League(version=2, divisions=league.divisions(), fields=List.of())
   d. save(migratedLeague) — atomic write of v2 file
   e. Return migratedLeague
2. Command proceeds normally.
```

The user sees no output related to migration — it is silent and transparent.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risk Accepted |
|---|---|---|---|---|
| Window identification for edit/delete | UUID exposed to user, 1-based index, natural key (day + start) | **1-based display index** | UUIDs are poor UX for a CLI; natural key requires too many arguments to be ergonomic; the list output is the canonical reference for the number | Index shifts after a delete: `#3` becomes `#2`. In a single-user CLI, the user is expected to re-run `list` before editing. This is acceptable for Phase 1. |
| Division reference in AvailabilityWindow | Store division name (denormalized), store division UUID | **UUID (normalized)** | Division names can be edited; a UUID reference survives renames. Display resolves UUID → name at print time. | If a division is deleted, the window's restriction becomes orphaned. The display layer shows `[deleted]` and warns; the scheduler slice is responsible for blocking schedule generation when orphaned windows exist. |
| LocalTime serialization format | ISO-8601 `HH:mm:ss`, `HH:mm` with custom serializer, store as plain String | **`LocalTime` + custom `HH:mm` serializer** | `LocalTime` enables natural `isAfter()` comparisons without parsing; `"HH:mm"` format matches what organizers expect to read in JSON export | Requires `jackson-datatype-jsr310` and a custom `LocalTime` serializer configuration. One-time setup, no ongoing cost. |
| Address field on edit: clearing | No way to clear, `--clear-address` flag, `--address ""` sets null | **`--address ""`** (empty string → `null`)  | Avoids adding a second flag; empty address has no meaningful use. Consistent with POSIX convention of overwriting with an empty value. | A user who accidentally passes `--address ""` will silently clear their address. Acceptable in a CLI context. |
| Schema migration direction | Always in-place (forward only), write both versions, prompt user | **Silent forward-only in-place migration** | The file is local; there is only one writer; forward-only migration is the established pattern here | An older binary cannot write back a valid v1 file after the v2 upgrade. This is intentional — do not mix binary versions on the same `league.json`. |
| Nested subcommand grouping | `planr field-window add`, `planr window add`, `planr field window add` | **`planr field window add`** (Picocli nested subcommand) | `planr field window` groups all window operations under `field`, making discoverability natural via `planr field --help` | Picocli nesting requires `FieldWindowCommand` to be registered as a subcommand of `FieldCommand`, not `PlanrApp`. Two-level dispatch is supported natively. |
| Deletion guard on field (like division's team guard) | Require field to have 0 windows before delete, cascade delete | **Cascade delete** | Fields and windows have no meaningful existence independently; an organizer who deletes a field intends to remove its configuration entirely. A guard would require a multi-step workflow with no benefit. | A mistyped field name causes a hard-to-undo delete. Mitigated by: (1) case-insensitive exact match (typos fail), (2) file is human-readable and recoverable. |

---

## Operational Concerns

**Error handling:** Mirrors Division & Team slice — validation errors to `stderr` + exit `1`; I/O errors to `stderr` + exit `2`. Stack traces suppressed.

**Data integrity:** The atomic write (temp file + rename) guards against partial writes, as before. The new migration write uses the same atomic path.

**Orphaned window detection:** When `planr field window list` resolves `divisionId` UUIDs, any UUID with no matching division in `league.divisions()` is displayed as `[deleted]`. A trailing warning is printed. No automatic cleanup occurs — the organizer must explicitly edit or delete the orphaned window. This is the correct behavior: the system should not silently drop configuration.

**Testing:** New unit tests cover:
- `Field` and `AvailabilityWindow` record helper methods
- `FieldCommand` — all validation paths (add, edit, delete, list)
- `FieldWindowCommand` — all validation paths including: end ≤ start rejection, invalid day, invalid window number, `--division`/`--clear-division` mutual exclusion, orphaned division display
- `LeagueStore` — v1→v2 migration: write a v1 file, call `load()`, assert v2 file on disk with empty `fields` array
- Jackson round-trip for `LocalTime` ("09:00" → `LocalTime.of(9,0)` → "09:00"), `DayOfWeek` ("SATURDAY" → `DayOfWeek.SATURDAY`)

Tests use the existing `systemProperty 'user.home', "${buildDir}/test-home"` redirect and run with `maxParallelForks = 1`.

**Capacity:** A typical league has 2–10 fields with 5–20 windows each. The JSON file remains well under 100 KB. No performance concern.

---

## Out of Scope / Future Work

- **Schedule generation** — this slice satisfies the field data prerequisite; scheduling is the next and final CLI slice before the web application.
- **Duplicate window detection** — the spec does not prohibit two windows on the same day with overlapping times on the same field. The scheduler will consume whatever windows are configured. A future validation pass could warn on overlaps.
- **Field home-team preferences** — out of scope per the product spec.
- **`$PLANR_DATA_DIR` env var override** — deferred from Division & Team slice; still deferred.
- **Multi-league support** — deferred; requires named-league flag or multiple files.
