# Tech Spec: League-Wide Availability Configuration

**Date:** 2026-05-25  
**PRD:** `features/2026-05-25-league-wide-availability-config.md`  
**Status:** Ready for implementation

---

## Overview

Two new primitives — day-of-week windows and blocked days of week — are added to `LeagueConfig` and stored in `league.json`. A new model record `DayOfWeekWindow` holds a per-day window. Two second-level subcommand groups (`planr config dow` and `planr config blockday`) manage them via CRUD operations. The existing `SchedulerService` slot enumeration loop is updated to consult both primitives using a defined four-level precedence rule. The schema version bumps to 5; the migration is a trivial version-bump because `LeagueConfig`'s compact constructor normalizes null fields to empty lists, making the structural change backward-compatible with old JSON files.

---

## Component Diagram

```
planr config dow (set / clear / list)
planr config blockday (add / remove / list)
         │
         ▼
  ConfigDowCommand          ConfigBlockdayCommand
  (new, in command pkg)     (new, in command pkg)
         │                         │
         └──────────┬──────────────┘
                    ▼
             ConfigCommand          ◄── extended: registers both subcommands,
             (existing)                 updated show output
                    │
                    ▼
             LeagueStore            ◄── v4→v5 migration (version bump only)
                    │
                    ▼
              LeagueConfig          ◄── two new fields + four mutation helpers
                    │
               DayOfWeekWindow      ◄── new record (model package)
                    │
           SchedulerService         ◄── updated: enumerateAllSlots +
           (existing)                   estimateAvailableSlots apply precedence

       DayParser                    ◄── new utility (command package), shared
       (new)                            by both subcommand groups
```

**Responsibility summary per component:**

| Component | Responsibility |
|---|---|
| `DayOfWeekWindow` | Immutable record tying a `DayOfWeek` to an open/close `LocalTime` pair |
| `LeagueConfig` | Holds all league-level config including the two new primitive lists; normalizes nulls |
| `LeagueStore` | Persists/loads `League`; applies v4→v5 version-bump migration |
| `DayParser` | Parses a string into a `DayOfWeek` accepting full names and 3-letter abbreviations |
| `ConfigDowCommand` | CRUD for day-of-week windows; emits conflict warnings |
| `ConfigBlockdayCommand` | CRUD for blocked days; emits conflict warnings |
| `ConfigCommand` | Entry point for `planr config`; registers new subcommands; shows full config |
| `SchedulerService` | Enumerates candidate time slots applying the four-level precedence rule |

---

## Data Model

### New record: `DayOfWeekWindow`

**File:** `src/main/java/org/leagueplan/planr/model/DayOfWeekWindow.java`

```
DayOfWeekWindow(
    DayOfWeek day,        // e.g. WEDNESDAY
    LocalTime openStart,  // serialized as "HH:mm" by existing SimpleModule
    LocalTime openEnd
)
```

No compact constructor needed; `DayOfWeek` is an enum so it cannot be null in practice after deserialization.

### Updated record: `LeagueConfig`

Add two fields to the canonical record constructor:

```
LeagueConfig(
    LocalTime sunriseTime,
    LocalTime sunsetTime,
    LocalDate seasonStart,
    LocalDate seasonEnd,
    List<DayOfWeekWindow> dowWindows,   // NEW — at most 7 entries, one per DayOfWeek
    List<DayOfWeek> blockedDays         // NEW — at most 7 entries, distinct values
)
```

**Compact constructor** must normalize both new lists:
```
dowWindows  = (dowWindows  == null) ? List.of() : dowWindows;
blockedDays = (blockedDays == null) ? List.of() : blockedDays;
```

This normalization is the entire v5 migration: old JSON files omit both fields, Jackson sets them to null, the compact constructor produces empty lists, and a subsequent `save()` writes them as `[]`.

**`empty()` factory** — add `List.of(), List.of()` as the last two arguments.

**Mutation helpers** — add four methods following the same pattern as `Field.withBlockAdded` etc.:

| Method | Description |
|---|---|
| `withDowWindowSet(DayOfWeekWindow w)` | Returns new config with `w` inserted (or replacing existing entry for `w.day()`) |
| `withDowWindowRemoved(DayOfWeek day)` | Returns new config with any entry for `day` removed |
| `withBlockedDayAdded(DayOfWeek day)` | Returns new config with `day` appended to `blockedDays` |
| `withBlockedDayRemoved(DayOfWeek day)` | Returns new config with `day` removed from `blockedDays` |

### JSON serialization

`DayOfWeek` serializes to its `.name()` string (e.g., `"WEDNESDAY"`) via `JavaTimeModule` with `WRITE_DATES_AS_TIMESTAMPS` disabled — already the behavior for the existing `DayOfWeek` usage in the codebase. No additional `ObjectMapper` configuration is required.

`DayOfWeekWindow` serializes as a JSON object; `openStart`/`openEnd` use the existing `SimpleModule` `HH:mm` serializer.

Example persisted shape:
```json
"dowWindows": [
  { "day": "WEDNESDAY", "openStart": "16:00", "openEnd": "21:00" }
],
"blockedDays": ["SUNDAY"]
```

### Schema migration

In `LeagueStore.load()`, add a new migration block after the existing `version < 4` block:

```java
if (league.version() < 5) {
    league = new League(5, league.config(), league.divisions(), league.fields(),
                        league.teamSchedule(), league.schedule());
    save(league);
}
```

`league.config()` at this point already has `dowWindows = List.of()` and `blockedDays = List.of()` (courtesy of the compact constructor). The save persists them as empty arrays and stamps version 5 to disk.

Bump `League.CURRENT_VERSION` from `4` to `5`.

---

## CLI Contracts

This is a CLI tool; "API contracts" describe command signatures, required/optional arguments, output lines, and exit codes.

### Shared utility: `DayParser`

**File:** `src/main/java/org/leagueplan/planr/command/DayParser.java`

```java
class DayParser {
    // Returns empty if the input is not a recognized full name or 3-letter abbreviation.
    static Optional<DayOfWeek> parse(String input) { ... }
    // Returns a human-readable hint for error messages, e.g. "monday … sunday or mon … sun"
    static String hint() { ... }
}
```

Abbreviation map (case-insensitive, keyed on uppercase):

```
MON → MONDAY    TUE → TUESDAY   WED → WEDNESDAY  THU → THURSDAY
FRI → FRIDAY    SAT → SATURDAY  SUN → SUNDAY
```

Full-name parsing: `DayOfWeek.valueOf(input.trim().toUpperCase())` wrapped in try/catch.

---

### `planr config dow`

**File:** `src/main/java/org/leagueplan/planr/command/ConfigDowCommand.java`

Registered as a subcommand of `ConfigCommand`. Parent traversal for inner classes: `parent.configCmd.app.store`.

#### `planr config dow set --day <DAY> --start <HH:mm> --end <HH:mm>`

| | |
|---|---|
| `--day` | Required. Parsed via `DayParser.parse()`. |
| `--start` | Required. Parsed via existing `TIME_FORMAT`. |
| `--end` | Required. Must be strictly after `--start`; validated before load. |
| **Success stdout** | `Day-of-week window set: Wednesday 16:00–21:00.` |
| **Conflict warning** | If any field has a `FieldDateOverride` or `FieldBlock` whose date falls on the affected day and within `[seasonStart, seasonEnd]`, print: `Warning: N field-level entries exist on Wednesdays within the season. Review with 'planr field block list' and 'planr field override list'.` (Omit warning if `seasonStart`/`seasonEnd` not set; scan all entries regardless of date.) |
| **Exit codes** | 0 success, 1 validation error, 2 I/O error |

**Conflict scan algorithm for `dow set`:**
1. For each field in `league.fields()`:
   a. Count `FieldBlock` entries where `block.date().getDayOfWeek() == day` and date is within season (if configured).
   b. Count `FieldDateOverride` entries where `override.date().getDayOfWeek() == day` and date is within season.
2. Sum all counts. If > 0, emit the warning.

#### `planr config dow clear --day <DAY>`

| | |
|---|---|
| `--day` | Required. Parsed via `DayParser.parse()`. |
| **Error if no window** | `Error: No day-of-week window configured for Wednesday.` Exit 1. |
| **Success stdout** | `Day-of-week window for Wednesday removed.` |
| **Exit codes** | 0 success, 1 validation error, 2 I/O error |

#### `planr config dow list`

| | |
|---|---|
| **Empty state** | `No day-of-week windows configured. Use 'planr config dow set' to add one.` |
| **Table** | Columns: `DAY`, `OPEN`, `CLOSE`. Rows sorted `MONDAY` → `SUNDAY`. |
| **Exit codes** | 0 always (including empty state) |

Example output:
```
DAY        OPEN   CLOSE
---------  -----  -----
Wednesday  16:00  21:00
```

---

### `planr config blockday`

**File:** `src/main/java/org/leagueplan/planr/command/ConfigBlockdayCommand.java`

Registered as a subcommand of `ConfigCommand`. Parent traversal: `parent.configCmd.app.store`.

#### `planr config blockday add --day <DAY>`

| | |
|---|---|
| `--day` | Required. Parsed via `DayParser.parse()`. |
| **Error if already blocked** | `Error: Sunday is already a blocked day.` Exit 1. |
| **Success stdout** | `Sunday added to blocked days.` |
| **Conflict warning** | Same scan logic as `dow set`, but the warning message reads: `Warning: N field-level entries exist on Sundays within the season. FieldDateOverride entries on those specific dates will still take precedence over this block.` |
| **Exit codes** | 0 success, 1 validation error, 2 I/O error |

#### `planr config blockday remove --day <DAY>`

| | |
|---|---|
| `--day` | Required. Parsed via `DayParser.parse()`. |
| **Error if not blocked** | `Error: Sunday is not a blocked day.` Exit 1. |
| **Success stdout** | `Sunday removed from blocked days.` |
| **Exit codes** | 0 success, 1 validation error, 2 I/O error |

#### `planr config blockday list`

| | |
|---|---|
| **Empty state** | `No days of the week are blocked. Use 'planr config blockday add' to block one.` |
| **Output** | One day name per line, sorted `MONDAY` → `SUNDAY`. |
| **Exit codes** | 0 always |

Example output:
```
Blocked days of week:
  Sunday
```

---

### Updated: `planr config show`

Append two new sections after the existing four lines. Section header and content follow the existing indent style:

```
Day-of-week windows:
  Wednesday: 16:00 – 21:00
  (none if empty)

Blocked days of week:
  Sunday
  (none if empty)
```

Sort both sections `MONDAY` → `SUNDAY`.

---

## Critical Path Walkthrough

### 1. `planr config dow set --day wednesday --start 16:00 --end 21:00`

1. `ConfigDowCommand.SetCmd.call()` — parse and validate `--day`, `--start`, `--end`.
2. `--end` (21:00) is after `--start` (16:00) → passes validation.
3. `store.load()` → deserialize `league.json` into `League`.
4. `league.config()` is retrieved (or `LeagueConfig.empty()` if null).
5. `config.withDowWindowSet(new DayOfWeekWindow(WEDNESDAY, 16:00, 21:00))` → produces new `LeagueConfig` with the window inserted (replacing any prior WEDNESDAY entry).
6. `store.save(league.withConfig(updatedConfig))`.
7. Emit success line.
8. Conflict scan: for each field, find `FieldBlock` and `FieldDateOverride` entries on Wednesdays within the season. Aggregate count. If > 0, emit warning to stdout.
9. Exit 0.

### 2. `planr config blockday add --day sunday`

1. Parse `--day` → `DayOfWeek.SUNDAY`.
2. `store.load()`.
3. Check `config.blockedDays().contains(SUNDAY)` → if true, exit 1 with error.
4. `config.withBlockedDayAdded(SUNDAY)` → new `LeagueConfig`.
5. `store.save(...)`.
6. Emit success line.
7. Conflict scan: same field/entry scan as above. Warning message includes the FieldDateOverride precedence note.
8. Exit 0.

### 3. `planr schedule assign` with dow window and blocked days

Slot enumeration in `SchedulerService.enumerateAllSlots()` — the inner loop over `(date, field)` changes from:

**Before:**
```
openStart = config.sunriseTime()
openEnd   = config.sunsetTime()
for FieldDateOverride: if date matches, override openStart/openEnd
```

**After (per-field, per-date):**
```
DayOfWeek dow = date.getDayOfWeek()

override = field.dateOverrides().stream()
               .filter(o -> o.date().equals(date)).findFirst().orElse(null)

if override != null:
    openStart = override.openStart()
    openEnd   = override.openEnd()
    // continue to FieldBlock subtraction and slot generation

else if config.blockedDays().contains(dow):
    continue  // skip this field+date entirely — no slots

else:
    dowWindow = config.dowWindows().stream()
                    .filter(w -> w.day() == dow).findFirst().orElse(null)
    if dowWindow != null:
        openStart = dowWindow.openStart()
        openEnd   = dowWindow.openEnd()
    else:
        openStart = config.sunriseTime()
        openEnd   = config.sunsetTime()
    // continue to FieldBlock subtraction and slot generation
```

The same change applies identically to `estimateAvailableSlots()`.

---

## Tradeoff Log

| Decision | Options Considered | Choice | Rationale | Risks Accepted |
|---|---|---|---|---|
| `FieldDateOverride` beats blocked day | (a) Blocked day wins always; (b) `FieldDateOverride` wins | `FieldDateOverride` wins | User confirmed: an explicit field-level override should be able to "rescue" a normally blocked day for a rescheduled game. | Organizer may be surprised to see slots appear on a blocked day; the `blockday add` warning calls this out. |
| `DayParser` placement | Inline in each command (duplication) vs. separate utility class | Separate `DayParser` in command package | Both `ConfigDowCommand` and `ConfigBlockdayCommand` need identical parsing; duplication invites divergence. | One more file; low risk. |
| `LeagueConfig` mutation helpers vs. inline construction | Add helpers to the record vs. build new records inline in commands (existing pattern) | Add helpers | The new mutations (filter-and-replace on a list) are multi-line; inlining them would clutter the command logic. Existing `SetCmd` is simple field assignment — acceptable inline; list mutation is not. | Slightly diverges from the `ConfigCommand.SetCmd` inline pattern, but aligns with the `Field` helper pattern. |
| Warning scan scope | Scan all entries regardless of date vs. season window only | Season window only (fall back to all entries if season not configured) | Warnings outside the season are noise; entries after season end are effectively inert. | If no season is set, warning may surface a larger-than-expected count; the message is informational only so this is low risk. |
| v5 migration strategy | (a) Structural transform; (b) version bump only | Version bump only | The compact constructor on `LeagueConfig` normalizes null → empty list, so there is nothing to transform. Persisting the bumped version prevents the migration from re-running. | None — this is the cleanest path given the existing normalization pattern. |
| Day sort order in list/show | Calendar order (Mon–Sun) vs. insertion order | Calendar order (`DayOfWeek.getValue()` 1–7) | Consistent with how organizers read a week; predictable for testing. | None. |

---

## Operational Concerns

### Testing

Follow the existing `CommandTestBase` pattern (extends base, wipes `~/.planr/` redirect before each test, captures stdout/stderr).

New test classes to add:

| Class | Key test cases |
|---|---|
| `ConfigDowCommandTest` | set new window; set replaces existing; clear existing; clear non-existent (exit 1); list empty; list sorted; invalid day (exit 1); end ≤ start (exit 1); conflict warning fires; conflict warning suppressed when no entries |
| `ConfigBlockdayCommandTest` | add new; add duplicate (exit 1); remove existing; remove non-existent (exit 1); list empty; list sorted; invalid day (exit 1); conflict warning fires with FieldDateOverride note |
| `ConfigShowCommandTest` (extend existing) | show with dow windows; show with blocked days; show with both; show with neither (shows "(none)") |
| `SchedulerServiceDowTest` | assign respects blocked day (no slots on that day); assign uses dow window instead of sunrise/sunset; FieldDateOverride beats blocked day; dow window falls back to sunrise/sunset for unconfigured days |
| `LeagueStoreTest` (extend existing) | v4 file loads as v5 with empty dow/blocked lists |

### Migration verification

Load a v4 `league.json` fixture (no `dowWindows`/`blockedDays` keys) → assert version becomes 5 and both lists are empty. This can be a file-based test in `LeagueStoreTest`.

### Rollback

The `league.json` schema change is backward-safe in one direction only: a v5 file loaded by a v4 binary will fail if v4's `LeagueConfig` record does not have the new fields and `FAIL_ON_UNKNOWN_PROPERTIES` is not disabled. Since the store has `FAIL_ON_UNKNOWN_PROPERTIES` disabled, a downgrade would silently ignore `dowWindows` and `blockedDays` — data loss but no crash. Document this in a comment on the migration block.

---

## Out of Scope / Future Work

- **Per-field day-of-week windows.** The `FieldDateOverride` mechanism already handles specific-date field overrides; a per-field recurring window would require a new list on `Field`. Deferred until a concrete organizer need is demonstrated.
- **League-wide specific-date blocking.** A `List<LocalDate>` on `LeagueConfig` would round out the primitives. Deferred; per-field `FieldBlock` is the current workaround.
- **Auto-invalidation of DRAFT/FINALIZED schedule** when availability config changes. Requires a change-tracking mechanism or a hash of the inputs. Not worth the complexity for a single-organizer CLI.
- **`planr schedule assign` slot-count display differentiation.** The existing feasibility check line (e.g., `"Feasibility check passed. Solver started. 24 games across 2 division(s)."`) does not break down how many slots were eliminated by blocked days vs. dow windows vs. global sunrise/sunset. Future enhancement if organizers want that visibility.
