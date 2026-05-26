package org.leagueplan.planr.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class FieldLockCommandTest extends CommandTestBase {

    @BeforeEach
    void setUp() {
        execute("field", "add", "Riverside Park");
        execute("division", "add", "Majors", "--duration", "60", "--target", "2");
        execute("division", "add", "AAA", "--duration", "60", "--target", "2");
    }

    // -------------------------------------------------------------------------
    // field lock add
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field lock add")
    class Add {

        @Test
        @DisplayName("exits 0 and prints confirmation on success")
        void success() {
            int exit = execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-08-31");
            assertEquals(0, exit);
            String out = stdout();
            assertTrue(out.contains("#1"));
            assertTrue(out.contains("Riverside Park"));
            assertTrue(out.contains("Majors"));
            assertTrue(out.contains("2026-06-01"));
            assertTrue(out.contains("2026-08-31"));
        }

        @Test
        @DisplayName("lock number increments with each add")
        void lockNumberIncrements() {
            execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-06-30");
            execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "AAA",
                "--start", "2026-07-01", "--end", "2026-07-31");
            assertTrue(stdout().contains("#2"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "lock", "add",
                "--field", "NonExistent", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-08-31");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when division does not exist")
        void failsWhenDivisionNotFound() {
            int exit = execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "NonExistent",
                "--start", "2026-06-01", "--end", "2026-08-31");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when start date format is invalid")
        void failsOnInvalidStartDate() {
            int exit = execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "June 1", "--end", "2026-08-31");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid date"));
        }

        @Test
        @DisplayName("exits 1 when end date format is invalid")
        void failsOnInvalidEndDate() {
            int exit = execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "08/31/2026");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid date"));
        }

        @Test
        @DisplayName("exits 1 when end date is before start date")
        void failsWhenEndBeforeStart() {
            int exit = execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-08-31", "--end", "2026-06-01");
            assertEquals(1, exit);
            assertTrue(stderr().contains("before start date"));
        }

        @Test
        @DisplayName("single-day lock (start == end) is accepted")
        void singleDayLockIsAccepted() {
            int exit = execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-15", "--end", "2026-06-15");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("exits 1 when new lock overlaps an existing lock on the same field")
        void failsOnOverlapWithExistingLock() {
            execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-06-30");
            int exit = execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "AAA",
                "--start", "2026-06-15", "--end", "2026-07-15");
            assertEquals(1, exit);
            assertTrue(stderr().contains("overlaps"));
            assertTrue(stderr().contains("#1"));
        }

        @Test
        @DisplayName("non-overlapping consecutive locks are accepted")
        void consecutiveLocksAreAccepted() {
            execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-06-30");
            int exit = execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "AAA",
                "--start", "2026-07-01", "--end", "2026-07-31");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("field lookup is case-insensitive")
        void fieldLookupIsCaseInsensitive() {
            int exit = execute("field", "lock", "add",
                "--field", "riverside park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-08-31");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("division lookup is case-insensitive")
        void divisionLookupIsCaseInsensitive() {
            int exit = execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "majors",
                "--start", "2026-06-01", "--end", "2026-08-31");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-08-31");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field lock delete
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field lock delete")
    class Delete {

        @BeforeEach
        void addLock() {
            execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-08-31");
        }

        @Test
        @DisplayName("exits 0 and prints confirmation on success")
        void success() {
            int exit = execute("field", "lock", "delete",
                "--field", "Riverside Park", "--index", "1");
            assertEquals(0, exit);
            String out = stdout();
            assertTrue(out.contains("deleted"));
            assertTrue(out.contains("#1"));
            assertTrue(out.contains("Riverside Park"));
        }

        @Test
        @DisplayName("deleted lock is absent from list")
        void deletedLockAbsentFromList() {
            execute("field", "lock", "delete", "--field", "Riverside Park", "--index", "1");
            execute("field", "lock", "list", "--field", "Riverside Park");
            assertTrue(stdout().contains("No locks"));
        }

        @Test
        @DisplayName("deletion confirmation includes the resolved division name")
        void deletionIncludesDivisionName() {
            int exit = execute("field", "lock", "delete",
                "--field", "Riverside Park", "--index", "1");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Majors"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "lock", "delete",
                "--field", "NonExistent", "--index", "1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when index is out of range")
        void failsWhenIndexOutOfRange() {
            int exit = execute("field", "lock", "delete",
                "--field", "Riverside Park", "--index", "99");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when field has no locks")
        void failsWhenNoLocksExist() {
            execute("field", "add", "Empty Field");
            int exit = execute("field", "lock", "delete",
                "--field", "Empty Field", "--index", "1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("No locks"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "lock", "delete",
                "--field", "Riverside Park", "--index", "1");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field lock list
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field lock list")
    class ListCmd {

        @Test
        @DisplayName("exits 0 and shows guidance when no locks exist for any field")
        void noLocksShowsMessage() {
            int exit = execute("field", "lock", "list");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No division locks"));
        }

        @Test
        @DisplayName("exits 0 and shows field-specific guidance when field has no locks")
        void noLocksForFieldShowsMessage() {
            int exit = execute("field", "lock", "list", "--field", "Riverside Park");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No locks"));
            assertTrue(stdout().contains("Riverside Park"));
        }

        @Test
        @DisplayName("shows column headers when locks exist")
        void showsColumnHeaders() {
            execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-08-31");
            execute("field", "lock", "list");
            String out = stdout();
            assertTrue(out.contains("FIELD"));
            assertTrue(out.contains("DIVISION"));
            assertTrue(out.contains("START"));
            assertTrue(out.contains("END"));
        }

        @Test
        @DisplayName("shows lock data with correct field name, division, and dates")
        void showsLockData() {
            execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-08-31");
            execute("field", "lock", "list");
            String out = stdout();
            assertTrue(out.contains("Riverside Park"));
            assertTrue(out.contains("Majors"));
            assertTrue(out.contains("2026-06-01"));
            assertTrue(out.contains("2026-08-31"));
        }

        @Test
        @DisplayName("--field filter shows only that field's locks")
        void fieldFilterShowsOnlyMatchingField() {
            execute("field", "add", "Other Field");
            execute("field", "lock", "add",
                "--field", "Riverside Park", "--division", "Majors",
                "--start", "2026-06-01", "--end", "2026-06-30");
            execute("field", "lock", "add",
                "--field", "Other Field", "--division", "AAA",
                "--start", "2026-07-01", "--end", "2026-07-31");

            execute("field", "lock", "list", "--field", "Riverside Park");
            String out = stdout();
            assertTrue(out.contains("Riverside Park"));
            assertFalse(out.contains("Other Field"));
        }

        @Test
        @DisplayName("exits 1 when --field filter names a nonexistent field")
        void failsWhenFilteredFieldNotFound() {
            int exit = execute("field", "lock", "list", "--field", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "lock", "list");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }
}
