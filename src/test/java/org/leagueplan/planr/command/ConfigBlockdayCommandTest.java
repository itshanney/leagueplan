package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ConfigBlockdayCommandTest extends CommandTestBase {

    // -------------------------------------------------------------------------
    // config blockday add
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("config blockday add")
    class Add {

        @Test
        @DisplayName("exits 0 and prints confirmation using a full day name")
        void successWithFullName() {
            int exit = execute("config", "blockday", "add", "--day", "sunday");
            assertEquals(0, exit);
            String out = stdout();
            assertTrue(out.contains("Sunday"));
            assertTrue(out.contains("blocked"));
        }

        @Test
        @DisplayName("exits 0 and prints confirmation using a 3-letter abbreviation")
        void successWithAbbreviation() {
            int exit = execute("config", "blockday", "add", "--day", "sun");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Sunday"));
        }

        @Test
        @DisplayName("exits 1 when the day is already blocked")
        void failsOnDuplicateBlock() {
            execute("config", "blockday", "add", "--day", "sunday");
            int exit = execute("config", "blockday", "add", "--day", "sunday");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already a blocked day"));
        }

        @Test
        @DisplayName("exits 1 for an unrecognized day name")
        void failsOnUnrecognizedDay() {
            int exit = execute("config", "blockday", "add", "--day", "funday");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Unrecognized day"));
        }

        @Test
        @DisplayName("day is persisted — subsequent list shows it")
        void dayIsPersistedToStore() {
            execute("config", "blockday", "add", "--day", "sunday");
            execute("config", "blockday", "list");
            assertTrue(stdout().contains("Sunday"));
        }

        @Test
        @DisplayName("emits singular 'entry' conflict warning when exactly one field-level entry exists on that day")
        void emitsSingularConflictWarning() {
            execute("config", "set", "--start", "2026-06-01", "--end", "2026-08-31");
            execute("field", "add", "Riverside Park");
            // June 7, 2026 is a Sunday
            execute("field", "block", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "10:00", "--end", "12:00");

            execute("config", "blockday", "add", "--day", "sunday");

            String out = stdout();
            assertTrue(out.contains("Warning"));
            assertTrue(out.contains("1 field-level entry"), "should use singular 'entry'");
        }

        @Test
        @DisplayName("conflict warning mentions that FieldDateOverride entries take precedence over the block")
        void conflictWarningMentionsPrecedence() {
            execute("config", "set", "--start", "2026-06-01", "--end", "2026-08-31");
            execute("field", "add", "Riverside Park");
            // June 7, 2026 is a Sunday — add a date override
            execute("field", "override", "add", "Riverside Park",
                "--date", "2026-06-07", "--start", "09:00", "--end", "14:00");

            execute("config", "blockday", "add", "--day", "sunday");

            String out = stdout();
            assertTrue(out.contains("Warning"));
            assertTrue(out.contains("FieldDateOverride"),
                "warning should mention FieldDateOverride precedence");
        }

        @Test
        @DisplayName("does not emit conflict warning when no field-level entries exist on that day")
        void noWarningWhenNoConflicts() {
            execute("config", "set", "--start", "2026-06-01", "--end", "2026-08-31");
            int exit = execute("config", "blockday", "add", "--day", "sunday");
            assertEquals(0, exit);
            assertFalse(stdout().contains("Warning"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("config", "blockday", "add", "--day", "sunday");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // config blockday remove
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("config blockday remove")
    class Remove {

        @Test
        @DisplayName("exits 0 and prints confirmation when the day is blocked")
        void success() {
            execute("config", "blockday", "add", "--day", "sunday");
            int exit = execute("config", "blockday", "remove", "--day", "sunday");
            assertEquals(0, exit);
            String out = stdout();
            assertTrue(out.contains("Sunday"));
            assertTrue(out.contains("removed"));
        }

        @Test
        @DisplayName("day is actually removed — subsequent list shows empty state")
        void dayIsRemovedFromStore() {
            execute("config", "blockday", "add", "--day", "sunday");
            execute("config", "blockday", "remove", "--day", "sunday");
            execute("config", "blockday", "list");
            assertTrue(stdout().contains("No days of the week are blocked"));
        }

        @Test
        @DisplayName("exits 1 when the day is not currently blocked")
        void failsWhenDayNotBlocked() {
            int exit = execute("config", "blockday", "remove", "--day", "sunday");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not a blocked day"));
        }

        @Test
        @DisplayName("exits 1 for an unrecognized day name")
        void failsOnUnrecognizedDay() {
            int exit = execute("config", "blockday", "remove", "--day", "xyz");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Unrecognized day"));
        }

        @Test
        @DisplayName("removes only the targeted day — other blocked days are preserved")
        void preservesOtherBlockedDays() {
            execute("config", "blockday", "add", "--day", "saturday");
            execute("config", "blockday", "add", "--day", "sunday");
            execute("config", "blockday", "remove", "--day", "sunday");
            execute("config", "blockday", "list");
            String out = stdout();
            assertTrue(out.contains("Saturday"));
            assertFalse(out.contains("Sunday"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("config", "blockday", "remove", "--day", "sunday");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // config blockday list
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("config blockday list")
    class List {

        @Test
        @DisplayName("exits 0 and shows empty-state message when no days are blocked")
        void emptyState() {
            int exit = execute("config", "blockday", "list");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No days of the week are blocked"));
        }

        @Test
        @DisplayName("shows 'Blocked days of week:' heading when days are blocked")
        void showsHeading() {
            execute("config", "blockday", "add", "--day", "sunday");
            execute("config", "blockday", "list");
            assertTrue(stdout().contains("Blocked days of week:"));
        }

        @Test
        @DisplayName("lists blocked days sorted Monday through Sunday regardless of insertion order")
        void sortedMondayThroughSunday() {
            execute("config", "blockday", "add", "--day", "sunday");
            execute("config", "blockday", "add", "--day", "saturday");
            execute("config", "blockday", "list");
            String out = stdout();
            int satPos = out.indexOf("Saturday");
            int sunPos = out.indexOf("Sunday");
            assertTrue(satPos >= 0 && sunPos >= 0, "both days should appear");
            assertTrue(satPos < sunPos, "Saturday must appear before Sunday");
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("config", "blockday", "list");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }
}
