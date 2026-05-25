package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the dow-window and blocked-day sections of "config show".
 * General config show behavior is covered in ConfigCommandTest.
 */
class ConfigShowDowBlockdayTest extends CommandTestBase {

    @Nested
    @DisplayName("config show — Day-of-week windows section")
    class DowWindowsSection {

        @Test
        @DisplayName("shows 'Day-of-week windows:' heading")
        void showsDowHeading() {
            execute("config", "show");
            assertTrue(stdout().contains("Day-of-week windows:"));
        }

        @Test
        @DisplayName("shows '(none)' when no dow windows are configured")
        void showsNoneWhenEmpty() {
            execute("config", "show");
            String out = stdout();
            // The (none) should appear under the Day-of-week windows section
            assertTrue(out.contains("Day-of-week windows:"));
            assertTrue(out.contains("(none)"));
        }

        @Test
        @DisplayName("shows configured window with day name, open, and close times")
        void showsConfiguredWindow() {
            execute("config", "dow", "set", "--day", "wednesday", "--start", "16:00", "--end", "21:00");
            execute("config", "show");
            String out = stdout();
            assertTrue(out.contains("Wednesday"));
            assertTrue(out.contains("16:00"));
            assertTrue(out.contains("21:00"));
        }

        @Test
        @DisplayName("shows multiple windows sorted Monday through Sunday")
        void showsWindowsSortedByDay() {
            execute("config", "dow", "set", "--day", "sunday", "--start", "09:00", "--end", "12:00");
            execute("config", "dow", "set", "--day", "monday", "--start", "09:00", "--end", "18:00");
            execute("config", "show");
            String out = stdout();
            int mondayPos = out.indexOf("Monday");
            int sundayPos = out.indexOf("Sunday");
            assertTrue(mondayPos >= 0 && sundayPos >= 0);
            assertTrue(mondayPos < sundayPos, "Monday must appear before Sunday");
        }
    }

    @Nested
    @DisplayName("config show — Blocked days of week section")
    class BlockedDaysSection {

        @Test
        @DisplayName("shows 'Blocked days of week:' heading")
        void showsBlockedHeading() {
            execute("config", "show");
            assertTrue(stdout().contains("Blocked days of week:"));
        }

        @Test
        @DisplayName("shows '(none)' when no days are blocked")
        void showsNoneWhenEmpty() {
            execute("config", "show");
            String out = stdout();
            assertTrue(out.contains("Blocked days of week:"));
            assertTrue(out.contains("(none)"));
        }

        @Test
        @DisplayName("shows each blocked day by name")
        void showsBlockedDay() {
            execute("config", "blockday", "add", "--day", "sunday");
            execute("config", "show");
            assertTrue(stdout().contains("Sunday"));
        }

        @Test
        @DisplayName("shows multiple blocked days sorted Monday through Sunday")
        void showsBlockedDaysSortedByDay() {
            execute("config", "blockday", "add", "--day", "sunday");
            execute("config", "blockday", "add", "--day", "saturday");
            execute("config", "show");
            String out = stdout();
            int satPos = out.indexOf("Saturday");
            int sunPos = out.indexOf("Sunday");
            assertTrue(satPos >= 0 && sunPos >= 0);
            assertTrue(satPos < sunPos, "Saturday must appear before Sunday");
        }
    }

    @Nested
    @DisplayName("config show — both sections together")
    class BothSectionsTogether {

        @Test
        @DisplayName("shows both sections even when both are empty")
        void showsBothSectionsWhenBothEmpty() {
            execute("config", "show");
            String out = stdout();
            assertTrue(out.contains("Day-of-week windows:"));
            assertTrue(out.contains("Blocked days of week:"));
        }

        @Test
        @DisplayName("shows both sections populated after separate set and add commands")
        void showsBothSectionsPopulated() {
            execute("config", "dow", "set", "--day", "friday", "--start", "17:00", "--end", "22:00");
            execute("config", "blockday", "add", "--day", "sunday");
            execute("config", "show");
            String out = stdout();
            assertTrue(out.contains("Friday"));
            assertTrue(out.contains("17:00"));
            assertTrue(out.contains("Sunday"));
        }

        @Test
        @DisplayName("new sections do not overwrite or displace the existing four config fields")
        void doesNotDisplaceExistingFields() {
            execute("config", "set", "--sunrise", "08:00", "--sunset", "19:00");
            execute("config", "dow", "set", "--day", "monday", "--start", "09:00", "--end", "18:00");
            execute("config", "blockday", "add", "--day", "sunday");
            execute("config", "show");
            String out = stdout();
            assertTrue(out.contains("Sunrise"));
            assertTrue(out.contains("08:00"));
            assertTrue(out.contains("Sunset"));
            assertTrue(out.contains("19:00"));
            assertTrue(out.contains("Monday"));
            assertTrue(out.contains("Sunday"));
        }
    }
}
