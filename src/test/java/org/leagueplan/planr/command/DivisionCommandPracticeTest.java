package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for practice-related flags on `planr division edit` and the new columns in
 * `planr division list`.
 *
 * These flags were added in v0.10.0:
 *   --practice-count <n>
 *   --practice-duration-minutes <n>
 *   --practice-start <YYYY-MM-DD>
 *   --practice-end <YYYY-MM-DD>
 *
 * Season configured at 2026-06-01 so practice-start/end validation (must be < seasonStart)
 * has a defined reference point.
 */
class DivisionCommandPracticeTest extends CommandTestBase {

    private static final String SEASON_START = "2026-06-01";

    private void addDivision(String name) {
        execute("division", "add", name, "--duration", "60", "--target", "4");
    }

    private void setSeasonStart(String start) {
        execute("config", "set", "--start", start, "--end", "2026-08-31");
    }

    // ---------------------------------------------------------------------------
    // division edit --practice-count
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("division edit --practice-count")
    class PracticeCount {

        @Test
        @DisplayName("persists practice count and shows in division list")
        void persistsPracticeCount() {
            addDivision("Majors");
            int exit = execute("division", "edit", "Majors", "--practice-count", "3");
            assertEquals(0, exit);

            execute("division", "list");
            assertTrue(stdout().contains("3"));
        }

        @Test
        @DisplayName("exits 1 when practice count is zero")
        void failsOnZeroCount() {
            addDivision("Majors");
            int exit = execute("division", "edit", "Majors", "--practice-count", "0");
            assertEquals(1, exit);
            assertTrue(stderr().contains("positive integer"));
        }

        @Test
        @DisplayName("exits 1 when practice count is negative")
        void failsOnNegativeCount() {
            addDivision("Majors");
            int exit = execute("division", "edit", "Majors", "--practice-count", "-1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("positive integer"));
        }
    }

    // ---------------------------------------------------------------------------
    // division edit --practice-duration-minutes
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("division edit --practice-duration-minutes")
    class PracticeDurationMinutes {

        @Test
        @DisplayName("persists practice duration and shows in division list")
        void persistsPracticeDuration() {
            addDivision("Majors");
            int exit = execute("division", "edit", "Majors",
                "--practice-duration-minutes", "90");
            assertEquals(0, exit);

            execute("division", "list");
            assertTrue(stdout().contains("90 min"));
        }

        @Test
        @DisplayName("exits 1 when practice duration is zero")
        void failsOnZeroDuration() {
            addDivision("Majors");
            int exit = execute("division", "edit", "Majors",
                "--practice-duration-minutes", "0");
            assertEquals(1, exit);
            assertTrue(stderr().contains("positive integer"));
        }

        @Test
        @DisplayName("exits 1 when practice duration is negative")
        void failsOnNegativeDuration() {
            addDivision("Majors");
            int exit = execute("division", "edit", "Majors",
                "--practice-duration-minutes", "-30");
            assertEquals(1, exit);
            assertTrue(stderr().contains("positive integer"));
        }
    }

    // ---------------------------------------------------------------------------
    // division edit --practice-start / --practice-end
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("division edit --practice-start and --practice-end")
    class PracticeDates {

        @Test
        @DisplayName("persists practice window dates and shows in division list")
        void persistsDates() {
            addDivision("Majors");
            setSeasonStart(SEASON_START);

            int exit = execute("division", "edit", "Majors",
                "--practice-start", "2026-04-01",
                "--practice-end", "2026-05-15");
            assertEquals(0, exit);

            execute("division", "list");
            assertTrue(stdout().contains("2026-04-01"));
            assertTrue(stdout().contains("2026-05-15"));
        }

        @Test
        @DisplayName("exits 1 when --practice-end is before --practice-start")
        void failsWhenEndBeforeStart() {
            addDivision("Majors");
            setSeasonStart(SEASON_START);

            int exit = execute("division", "edit", "Majors",
                "--practice-start", "2026-05-15",
                "--practice-end", "2026-04-01");
            assertEquals(1, exit);
            assertTrue(stderr().contains("must not be before"));
        }

        @Test
        @DisplayName("exits 1 when --practice-start is not before seasonStart")
        void failsWhenStartOnOrAfterSeasonStart() {
            addDivision("Majors");
            setSeasonStart(SEASON_START);

            int exit = execute("division", "edit", "Majors",
                "--practice-start", SEASON_START); // same day as seasonStart
            assertEquals(1, exit);
            assertTrue(stderr().contains("must be before seasonStart"));
        }

        @Test
        @DisplayName("exits 1 when --practice-end is not before seasonStart")
        void failsWhenEndOnOrAfterSeasonStart() {
            addDivision("Majors");
            setSeasonStart(SEASON_START);

            int exit = execute("division", "edit", "Majors",
                "--practice-start", "2026-04-01",
                "--practice-end", "2026-06-15"); // after seasonStart
            assertEquals(1, exit);
            assertTrue(stderr().contains("must be before seasonStart"));
        }

        @Test
        @DisplayName("exits 1 on invalid --practice-start date format")
        void failsOnInvalidStartDate() {
            addDivision("Majors");
            int exit = execute("division", "edit", "Majors",
                "--practice-start", "not-a-date");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid date"));
        }

        @Test
        @DisplayName("exits 1 on invalid --practice-end date format")
        void failsOnInvalidEndDate() {
            addDivision("Majors");
            int exit = execute("division", "edit", "Majors",
                "--practice-end", "2026/05/15");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid date"));
        }

        @Test
        @DisplayName("can set --practice-start without --practice-end when no existing end stored")
        void canSetStartAloneWithNoExistingEnd() {
            addDivision("Majors");
            setSeasonStart(SEASON_START);

            // Only start is given; no stored end → validation uses null for effective end
            int exit = execute("division", "edit", "Majors",
                "--practice-start", "2026-04-01");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("validates new start against stored end when only --practice-start is provided")
        void validatesNewStartAgainstStoredEnd() {
            addDivision("Majors");
            setSeasonStart(SEASON_START);
            execute("division", "edit", "Majors",
                "--practice-start", "2026-04-01",
                "--practice-end", "2026-05-01");

            // New start that is after stored end should fail
            int exit = execute("division", "edit", "Majors",
                "--practice-start", "2026-05-15"); // after stored end 2026-05-01
            assertEquals(1, exit);
            assertTrue(stderr().contains("must not be before"));
        }
    }

    // ---------------------------------------------------------------------------
    // division list — new columns
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("division list — practice columns")
    class ListColumns {

        @Test
        @DisplayName("shows column headers PRAC_COUNT, PRAC_MIN, PRAC_START, PRAC_END")
        void showsColumnHeaders() {
            addDivision("Majors");
            execute("division", "list");
            assertTrue(stdout().contains("PRAC_COUNT"));
            assertTrue(stdout().contains("PRAC_MIN"));
            assertTrue(stdout().contains("PRAC_START"));
            assertTrue(stdout().contains("PRAC_END"));
        }

        @Test
        @DisplayName("shows '--' for unconfigured practice fields")
        void showsDashForUnconfiguredFields() {
            addDivision("Majors");
            execute("division", "list");
            // At least four '--' placeholders for the four null fields
            long dashCount = java.util.Arrays.stream(stdout().split("\\s+"))
                .filter("--"::equals).count();
            assertTrue(dashCount >= 4, "Expected at least 4 '--' placeholders, got " + dashCount);
        }

        @Test
        @DisplayName("shows configured values after division edit")
        void showsConfiguredValues() {
            addDivision("Majors");
            setSeasonStart(SEASON_START);
            execute("division", "edit", "Majors",
                "--practice-count", "3",
                "--practice-duration-minutes", "75",
                "--practice-start", "2026-04-01",
                "--practice-end", "2026-05-15");

            execute("division", "list");
            assertTrue(stdout().contains("3"));
            assertTrue(stdout().contains("75 min"));
            assertTrue(stdout().contains("2026-04-01"));
            assertTrue(stdout().contains("2026-05-15"));
        }
    }

    // ---------------------------------------------------------------------------
    // Full practice configuration round-trip
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("full practice configuration round-trip")
    class RoundTrip {

        @Test
        @DisplayName("all four fields can be set, persisted, and overwritten independently")
        void allFieldsRoundTrip() {
            addDivision("Majors");
            setSeasonStart(SEASON_START);

            execute("division", "edit", "Majors",
                "--practice-count", "2",
                "--practice-duration-minutes", "60",
                "--practice-start", "2026-04-01",
                "--practice-end", "2026-05-01");

            // Update only the count — other fields must be preserved
            execute("division", "edit", "Majors", "--practice-count", "4");

            execute("division", "list");
            assertTrue(stdout().contains("4")); // new count
            assertTrue(stdout().contains("60 min")); // preserved duration
            assertTrue(stdout().contains("2026-04-01")); // preserved start
            assertTrue(stdout().contains("2026-05-01")); // preserved end
        }

        @Test
        @DisplayName("exits 0 when all four practice flags are set in one command")
        void singleCommandSetsAllFour() {
            addDivision("Majors");
            setSeasonStart(SEASON_START);

            int exit = execute("division", "edit", "Majors",
                "--practice-count", "3",
                "--practice-duration-minutes", "60",
                "--practice-start", "2026-04-01",
                "--practice-end", "2026-05-15");
            assertEquals(0, exit);
        }
    }
}
