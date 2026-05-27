package org.leagueplan.planr.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConfigDowCommandTest extends CommandTestBase {

  // -------------------------------------------------------------------------
  // config dow set
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("config dow set")
  class Set {

    @Test
    @DisplayName("exits 0 and prints confirmation with the day and times")
    void successWithFullName() {
      int exit =
          execute(
              "config", "dow", "set", "--day", "wednesday", "--start", "16:00", "--end", "21:00");
      assertEquals(0, exit);
      String out = stdout();
      assertTrue(out.contains("Wednesday"));
      assertTrue(out.contains("16:00"));
      assertTrue(out.contains("21:00"));
    }

    @Test
    @DisplayName("exits 0 when using a 3-letter abbreviation for the day")
    void successWithAbbreviation() {
      int exit =
          execute("config", "dow", "set", "--day", "wed", "--start", "16:00", "--end", "21:00");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Wednesday"));
    }

    @Test
    @DisplayName("replaces an existing window when one already exists for that day")
    void replacesExistingWindowForSameDay() {
      execute("config", "dow", "set", "--day", "monday", "--start", "09:00", "--end", "18:00");
      execute("config", "dow", "set", "--day", "monday", "--start", "12:00", "--end", "20:00");

      execute("config", "dow", "list");
      String out = stdout();
      assertTrue(out.contains("12:00"));
      assertTrue(out.contains("20:00"));
      assertFalse(out.contains("09:00"), "original start should no longer appear");
    }

    @Test
    @DisplayName("exits 1 for an unrecognized day name")
    void failsOnUnrecognizedDay() {
      int exit =
          execute("config", "dow", "set", "--day", "funday", "--start", "09:00", "--end", "18:00");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Unrecognized day"));
    }

    @Test
    @DisplayName("exits 1 when start time format is invalid")
    void failsOnInvalidStartTime() {
      int exit =
          execute("config", "dow", "set", "--day", "monday", "--start", "9am", "--end", "18:00");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Invalid time"));
    }

    @Test
    @DisplayName("exits 1 when end time format is invalid")
    void failsOnInvalidEndTime() {
      int exit =
          execute("config", "dow", "set", "--day", "monday", "--start", "09:00", "--end", "6pm");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Invalid time"));
    }

    @Test
    @DisplayName("exits 1 when end time equals start time")
    void failsWhenEndEqualsStart() {
      int exit =
          execute("config", "dow", "set", "--day", "monday", "--start", "09:00", "--end", "09:00");
      assertEquals(1, exit);
      assertTrue(stderr().contains("after"));
    }

    @Test
    @DisplayName("exits 1 when end time is before start time")
    void failsWhenEndBeforeStart() {
      int exit =
          execute("config", "dow", "set", "--day", "monday", "--start", "18:00", "--end", "09:00");
      assertEquals(1, exit);
      assertTrue(stderr().contains("after"));
    }

    @Test
    @DisplayName(
        "emits singular 'entry' warning when exactly one field-level entry exists on that day")
    void emitsSingularConflictWarning() {
      execute("config", "set", "--start", "2026-06-01", "--end", "2026-08-31");
      execute("field", "add", "Riverside Park");
      // June 3, 2026 is a Wednesday
      execute(
          "field",
          "block",
          "add",
          "Riverside Park",
          "--date",
          "2026-06-03",
          "--start",
          "10:00",
          "--end",
          "12:00");

      execute("config", "dow", "set", "--day", "wednesday", "--start", "16:00", "--end", "21:00");

      String out = stdout();
      assertTrue(out.contains("Warning"));
      assertTrue(out.contains("1 field-level entry"), "should use singular 'entry'");
    }

    @Test
    @DisplayName(
        "emits plural 'entries' warning when multiple field-level entries exist on that day")
    void emitsPluralConflictWarning() {
      execute("config", "set", "--start", "2026-06-01", "--end", "2026-08-31");
      execute("field", "add", "Riverside Park");
      // June 3 and June 10, 2026 are both Wednesdays
      execute(
          "field",
          "block",
          "add",
          "Riverside Park",
          "--date",
          "2026-06-03",
          "--start",
          "10:00",
          "--end",
          "12:00");
      execute(
          "field",
          "block",
          "add",
          "Riverside Park",
          "--date",
          "2026-06-10",
          "--start",
          "10:00",
          "--end",
          "12:00");

      execute("config", "dow", "set", "--day", "wednesday", "--start", "16:00", "--end", "21:00");

      assertTrue(stdout().contains("2 field-level entries"), "should use plural 'entries'");
    }

    @Test
    @DisplayName("does not emit conflict warning when no field-level entries exist on that day")
    void noWarningWhenNoConflicts() {
      execute("config", "set", "--start", "2026-06-01", "--end", "2026-08-31");
      execute("field", "add", "Riverside Park");
      // June 4, 2026 is Thursday — not Wednesday
      execute(
          "field",
          "block",
          "add",
          "Riverside Park",
          "--date",
          "2026-06-04",
          "--start",
          "10:00",
          "--end",
          "12:00");

      execute("config", "dow", "set", "--day", "wednesday", "--start", "16:00", "--end", "21:00");

      assertFalse(stdout().contains("Warning"));
    }

    @Test
    @DisplayName("does not count field entries outside the configured season in the conflict count")
    void conflictWarningIsScopedToSeason() {
      // Season is June 1–Aug 31; block is on May 31 (Sunday), outside the season
      execute("config", "set", "--start", "2026-06-01", "--end", "2026-08-31");
      execute("field", "add", "Riverside Park");
      // May 31, 2026 is a Sunday — outside the June–Aug season
      execute(
          "field",
          "block",
          "add",
          "Riverside Park",
          "--date",
          "2026-05-31",
          "--start",
          "10:00",
          "--end",
          "12:00");

      execute("config", "dow", "set", "--day", "sunday", "--start", "09:00", "--end", "12:00");

      assertFalse(stdout().contains("Warning"), "block outside season should not trigger warning");
    }

    @Test
    @DisplayName("counts all field entries when no season is configured")
    void allEntriesCountedWhenNoSeasonConfigured() {
      execute("field", "add", "Riverside Park");
      execute(
          "field",
          "block",
          "add",
          "Riverside Park",
          "--date",
          "2026-06-03",
          "--start",
          "10:00",
          "--end",
          "12:00");

      int exit =
          execute(
              "config", "dow", "set", "--day", "wednesday", "--start", "16:00", "--end", "21:00");

      assertEquals(0, exit);
      assertTrue(
          stdout().contains("Warning"), "should warn even without a season when entries exist");
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      int exit =
          execute("config", "dow", "set", "--day", "monday", "--start", "09:00", "--end", "18:00");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }
  }

  // -------------------------------------------------------------------------
  // config dow clear
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("config dow clear")
  class Clear {

    @Test
    @DisplayName("exits 0 and prints confirmation when the window exists")
    void success() {
      execute("config", "dow", "set", "--day", "wednesday", "--start", "16:00", "--end", "21:00");
      int exit = execute("config", "dow", "clear", "--day", "wednesday");
      assertEquals(0, exit);
      String out = stdout();
      assertTrue(out.contains("Wednesday"));
      assertTrue(out.contains("removed"));
    }

    @Test
    @DisplayName("exits 1 when no window is configured for the specified day")
    void failsWhenWindowNotFound() {
      int exit = execute("config", "dow", "clear", "--day", "monday");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No day-of-week window configured for Monday"));
    }

    @Test
    @DisplayName("exits 1 for an unrecognized day name")
    void failsOnUnrecognizedDay() {
      int exit = execute("config", "dow", "clear", "--day", "badday");
      assertEquals(1, exit);
      assertTrue(stderr().contains("Unrecognized day"));
    }

    @Test
    @DisplayName("window is actually removed — subsequent list shows empty state")
    void windowIsRemovedFromStore() {
      execute("config", "dow", "set", "--day", "wednesday", "--start", "16:00", "--end", "21:00");
      execute("config", "dow", "clear", "--day", "wednesday");
      execute("config", "dow", "list");
      assertTrue(stdout().contains("No day-of-week windows configured"));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      int exit = execute("config", "dow", "clear", "--day", "monday");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }
  }

  // -------------------------------------------------------------------------
  // config dow list
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("config dow list")
  class List {

    @Test
    @DisplayName("exits 0 and shows empty-state message when no windows are configured")
    void emptyState() {
      int exit = execute("config", "dow", "list");
      assertEquals(0, exit);
      assertTrue(stdout().contains("No day-of-week windows configured"));
    }

    @Test
    @DisplayName("shows DAY, OPEN, and CLOSE column headers when windows exist")
    void showsColumnHeaders() {
      execute("config", "dow", "set", "--day", "monday", "--start", "09:00", "--end", "18:00");
      execute("config", "dow", "list");
      String out = stdout();
      assertTrue(out.contains("DAY"));
      assertTrue(out.contains("OPEN"));
      assertTrue(out.contains("CLOSE"));
    }

    @Test
    @DisplayName("lists windows sorted Monday through Sunday regardless of insertion order")
    void sortedMondayThroughSunday() {
      execute("config", "dow", "set", "--day", "sunday", "--start", "09:00", "--end", "12:00");
      execute("config", "dow", "set", "--day", "monday", "--start", "09:00", "--end", "18:00");
      execute("config", "dow", "list");
      String out = stdout();
      int mondayPos = out.indexOf("Monday");
      int sundayPos = out.indexOf("Sunday");
      assertTrue(mondayPos >= 0 && sundayPos >= 0, "both days should appear");
      assertTrue(mondayPos < sundayPos, "Monday must appear before Sunday");
    }

    @Test
    @DisplayName("shows the configured window times for each day")
    void showsWindowTimes() {
      execute("config", "dow", "set", "--day", "friday", "--start", "17:00", "--end", "22:00");
      execute("config", "dow", "list");
      String out = stdout();
      assertTrue(out.contains("Friday"));
      assertTrue(out.contains("17:00"));
      assertTrue(out.contains("22:00"));
    }

    @Test
    @DisplayName("exits 2 on corrupted league data")
    void exitsOnCorruptedData() throws IOException {
      corruptLeagueFile();
      int exit = execute("config", "dow", "list");
      assertEquals(2, exit);
      assertTrue(stderr().contains("Failed to access league data"));
    }
  }
}
