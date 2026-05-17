package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class FieldCommandTest extends CommandTestBase {

    // -------------------------------------------------------------------------
    // field add
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field add")
    class Add {

        @Test
        @DisplayName("exits 0 and prints confirmation without an address")
        void successWithoutAddress() {
            int exit = execute("field", "add", "Riverside Park");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Riverside Park"));
            assertTrue(stdout().contains("added"));
        }

        @Test
        @DisplayName("exits 0 and prints confirmation with an address")
        void successWithAddress() {
            int exit = execute("field", "add", "Riverside Park", "--address", "123 Main St");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Riverside Park"));
        }

        @Test
        @DisplayName("persists the field so subsequent commands can see it")
        void persistsAcrossInvocations() {
            execute("field", "add", "Riverside Park");
            execute("field", "list");
            assertTrue(stdout().contains("Riverside Park"));
        }

        @Test
        @DisplayName("two distinct fields can coexist")
        void multipleFieldsCoexist() {
            assertEquals(0, execute("field", "add", "Riverside Park"));
            assertEquals(0, execute("field", "add", "Eastside Field"));
        }

        @Test
        @DisplayName("exits 1 when field name already exists (case-insensitive)")
        void failsOnDuplicateName() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "add", "riverside park");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 1 when field name is blank")
        void failsOnBlankName() {
            int exit = execute("field", "add", "   ");
            assertEquals(1, exit);
            assertTrue(stderr().contains("cannot be empty"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "add", "Riverside Park");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field edit
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field edit")
    class Edit {

        @Test
        @DisplayName("exits 0 and prints confirmation when renaming")
        void renameSucceeds() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "edit", "Riverside Park", "--name", "Eastside Field");
            assertEquals(0, exit);
            assertTrue(stdout().contains("updated"));
        }

        @Test
        @DisplayName("renamed field appears under new name in list")
        void renamedFieldAppearsUnderNewName() {
            execute("field", "add", "Riverside Park");
            execute("field", "edit", "Riverside Park", "--name", "Eastside Field");
            execute("field", "list");
            assertTrue(stdout().contains("Eastside Field"));
            assertFalse(stdout().contains("Riverside Park"));
        }

        @Test
        @DisplayName("exits 0 when updating address only")
        void addressUpdateSucceeds() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "edit", "Riverside Park", "--address", "456 Oak Ave");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("updated address appears in list")
        void updatedAddressAppearsInList() {
            execute("field", "add", "Riverside Park");
            execute("field", "edit", "Riverside Park", "--address", "456 Oak Ave");
            execute("field", "list");
            assertTrue(stdout().contains("456 Oak Ave"));
        }

        @Test
        @DisplayName("exits 0 when updating both name and address")
        void nameAndAddressUpdateSucceeds() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "edit", "Riverside Park",
                "--name", "Eastside Field", "--address", "789 Elm St");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("empty string for --address clears a previously set address")
        void emptyAddressClearsExistingAddress() {
            execute("field", "add", "Riverside Park", "--address", "123 Main St");
            execute("field", "edit", "Riverside Park", "--address", "");
            execute("field", "list");
            assertFalse(stdout().contains("123 Main St"));
            assertTrue(stdout().contains("(none)"));
        }

        @Test
        @DisplayName("matches the target field name case-insensitively")
        void matchesCaseInsensitively() {
            execute("field", "add", "Riverside Park");
            assertEquals(0, execute("field", "edit", "RIVERSIDE PARK", "--address", "100 River Rd"));
        }

        @Test
        @DisplayName("exits 1 when the target field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "edit", "NonExistent", "--name", "Other");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when neither --name nor --address is provided")
        void failsWhenNoOptionsProvided() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "edit", "Riverside Park");
            assertEquals(1, exit);
            assertTrue(stderr().contains("At least one of"));
        }

        @Test
        @DisplayName("exits 1 when new name conflicts with an existing field (case-insensitive)")
        void failsOnConflictingNewName() {
            execute("field", "add", "Riverside Park");
            execute("field", "add", "Eastside Field");
            int exit = execute("field", "edit", "Riverside Park", "--name", "eastside field");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 1 when new name is blank")
        void failsOnBlankNewName() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "edit", "Riverside Park", "--name", "   ");
            assertEquals(1, exit);
            assertTrue(stderr().contains("cannot be empty"));
        }

        @Test
        @DisplayName("exits 0 when renaming to the same name in a different case")
        void renamingToSameNameCaseVariantSucceeds() {
            execute("field", "add", "Riverside Park");
            assertEquals(0, execute("field", "edit", "Riverside Park", "--name", "riverside park"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "edit", "Riverside Park", "--name", "Other");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field delete
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field delete")
    class Delete {

        @Test
        @DisplayName("exits 0 and shows zero window count when field has no windows")
        void successWithNoWindows() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "delete", "Riverside Park");
            assertEquals(0, exit);
            assertTrue(stdout().contains("0 availability window(s) deleted"));
        }

        @Test
        @DisplayName("exits 0 and reports the correct cascade-deleted window count")
        void successWithWindowsCascades() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Sunday", "--start", "09:00", "--end", "12:00");
            int exit = execute("field", "delete", "Riverside Park");
            assertEquals(0, exit);
            assertTrue(stdout().contains("2 availability window(s) deleted"));
        }

        @Test
        @DisplayName("deleted field is absent from list; other fields survive")
        void deletedFieldAbsentFromList() {
            execute("field", "add", "Riverside Park");
            execute("field", "add", "Eastside Field");
            execute("field", "delete", "Riverside Park");
            execute("field", "list");
            assertFalse(stdout().contains("Riverside Park"));
            assertTrue(stdout().contains("Eastside Field"));
        }

        @Test
        @DisplayName("matches the target field name case-insensitively")
        void matchesCaseInsensitively() {
            execute("field", "add", "Riverside Park");
            assertEquals(0, execute("field", "delete", "RIVERSIDE PARK"));
        }

        @Test
        @DisplayName("exits 1 when the field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "delete", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "delete", "Riverside Park");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field list
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field list")
    class ListCmd {

        @Test
        @DisplayName("exits 0 and shows guidance when no fields are configured")
        void noFieldsShowsMessage() {
            int exit = execute("field", "list");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No fields configured"));
        }

        @Test
        @DisplayName("shows NAME, ADDRESS, and WINDOWS column headers")
        void showsColumnHeaders() {
            execute("field", "add", "Riverside Park");
            execute("field", "list");
            String out = stdout();
            assertTrue(out.contains("NAME"));
            assertTrue(out.contains("ADDRESS"));
            assertTrue(out.contains("WINDOWS"));
        }

        @Test
        @DisplayName("shows '(none)' for a field without an address")
        void showsNoneForFieldWithoutAddress() {
            execute("field", "add", "Riverside Park");
            execute("field", "list");
            assertTrue(stdout().contains("(none)"));
        }

        @Test
        @DisplayName("shows the address for a field that has one")
        void showsAddressWhenPresent() {
            execute("field", "add", "Riverside Park", "--address", "123 Main St");
            execute("field", "list");
            assertTrue(stdout().contains("123 Main St"));
        }

        @Test
        @DisplayName("shows window count as 0 before any windows are added")
        void showsZeroWindowCountInitially() {
            execute("field", "add", "Riverside Park");
            execute("field", "list");
            assertTrue(stdout().lines()
                .filter(l -> l.contains("Riverside Park"))
                .anyMatch(l -> l.contains("0")));
        }

        @Test
        @DisplayName("window count reflects windows added to the field")
        void windowCountUpdatesAfterWindowAdd() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Sunday", "--start", "09:00", "--end", "12:00");
            execute("field", "list");
            assertTrue(stdout().lines()
                .filter(l -> l.contains("Riverside Park"))
                .anyMatch(l -> l.contains("2")));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            assertEquals(2, execute("field", "list"));
        }
    }

    // -------------------------------------------------------------------------
    // field window add
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field window add")
    class WindowAdd {

        @Test
        @DisplayName("exits 0 and prints confirmation without a division restriction")
        void successUnrestricted() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            assertEquals(0, exit);
            assertTrue(stdout().contains("#1"));
            assertTrue(stdout().contains("Riverside Park"));
            assertTrue(stdout().contains("Saturday"));
            assertTrue(stdout().contains("09:00"));
            assertTrue(stdout().contains("17:00"));
            assertTrue(stdout().contains("all divisions"));
        }

        @Test
        @DisplayName("exits 0 and prints division label when --division is supplied")
        void successWithDivisionRestriction() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00", "--division", "Majors");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Majors only"));
        }

        @Test
        @DisplayName("accepts a 3-letter day abbreviation")
        void acceptsThreeLetterAbbreviation() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "add", "Riverside Park",
                "--day", "Sat", "--start", "09:00", "--end", "17:00");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Saturday"));
        }

        @Test
        @DisplayName("accepts a full day name case-insensitively")
        void acceptsFullDayNameCaseInsensitively() {
            execute("field", "add", "Riverside Park");
            assertEquals(0, execute("field", "window", "add", "Riverside Park",
                "--day", "SATURDAY", "--start", "09:00", "--end", "17:00"));
        }

        @Test
        @DisplayName("window number increments with each add")
        void windowNumberIncrementsWithEachAdd() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Sunday", "--start", "09:00", "--end", "12:00");
            assertTrue(stdout().contains("#2"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "window", "add", "NonExistent",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when day name is invalid")
        void failsOnInvalidDay() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "add", "Riverside Park",
                "--day", "Weekday", "--start", "09:00", "--end", "17:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid day"));
        }

        @Test
        @DisplayName("exits 1 when start time format is invalid")
        void failsOnInvalidStartTime() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "9am", "--end", "17:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid time"));
        }

        @Test
        @DisplayName("exits 1 when end time format is invalid")
        void failsOnInvalidEndTime() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "5pm");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid time"));
        }

        @Test
        @DisplayName("exits 1 when end time equals start time")
        void failsWhenEndEqualsStart() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "09:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("End time must be after start time"));
        }

        @Test
        @DisplayName("exits 1 when end time is before start time")
        void failsWhenEndBeforeStart() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "17:00", "--end", "09:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("End time must be after start time"));
        }

        @Test
        @DisplayName("exits 1 when the specified division does not exist")
        void failsWhenDivisionNotFound() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00",
                "--division", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field window edit
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field window edit")
    class WindowEdit {

        @Test
        @DisplayName("exits 0 and prints confirmation when updating the day")
        void updatesDaySuccessfully() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "edit", "Riverside Park", "1", "--day", "Sunday");
            assertEquals(0, exit);
            assertTrue(stdout().contains("updated"));
        }

        @Test
        @DisplayName("updated day appears in list")
        void updatedDayAppearsInList() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "edit", "Riverside Park", "1", "--day", "Sunday");
            execute("field", "window", "list", "Riverside Park");
            assertTrue(stdout().contains("Sunday"));
            assertFalse(stdout().contains("Saturday"));
        }

        @Test
        @DisplayName("exits 0 when updating start time only")
        void updatesStartTimeSuccessfully() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            assertEquals(0, execute("field", "window", "edit", "Riverside Park", "1", "--start", "10:00"));
        }

        @Test
        @DisplayName("exits 0 when updating end time only")
        void updatesEndTimeSuccessfully() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            assertEquals(0, execute("field", "window", "edit", "Riverside Park", "1", "--end", "18:00"));
        }

        @Test
        @DisplayName("exits 0 when setting a division restriction")
        void setsDivisionRestriction() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "edit", "Riverside Park", "1", "--division", "Majors");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("division label appears in list after setting restriction")
        void divisionLabelAppearsInListAfterSettingRestriction() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "edit", "Riverside Park", "1", "--division", "Majors");
            execute("field", "window", "list", "Riverside Park");
            assertTrue(stdout().contains("Majors"));
        }

        @Test
        @DisplayName("--clear-division removes a previously set restriction")
        void clearDivisionRemovesPreviousRestriction() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00", "--division", "Majors");
            execute("field", "window", "edit", "Riverside Park", "1", "--clear-division");
            execute("field", "window", "list", "Riverside Park");
            assertTrue(stdout().contains("All divisions"));
            assertFalse(stdout().contains("Majors"));
        }

        @Test
        @DisplayName("unspecified fields are preserved from the existing window")
        void preservesExistingValuesForUnspecifiedFields() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "edit", "Riverside Park", "1", "--day", "Sunday");
            execute("field", "window", "list", "Riverside Park");
            String out = stdout();
            assertTrue(out.contains("Sunday"));
            assertTrue(out.contains("09:00"));
            assertTrue(out.contains("17:00"));
        }

        @Test
        @DisplayName("matches field name case-insensitively")
        void matchesFieldNameCaseInsensitively() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            assertEquals(0, execute("field", "window", "edit", "RIVERSIDE PARK", "1", "--day", "Sunday"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "window", "edit", "NonExistent", "1", "--day", "Sunday");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when window number is out of range")
        void failsWhenWindowNumberOutOfRange() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "edit", "Riverside Park", "5", "--day", "Sunday");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when window number is 0")
        void failsWhenWindowNumberIsZero() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "edit", "Riverside Park", "0", "--day", "Sunday");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when no options are provided")
        void failsWhenNoOptionsProvided() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "edit", "Riverside Park", "1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("At least one of"));
        }

        @Test
        @DisplayName("exits 1 when --division and --clear-division are both supplied")
        void failsWhenBothDivisionFlagsProvided() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "edit", "Riverside Park", "1",
                "--division", "Majors", "--clear-division");
            assertEquals(1, exit);
            assertTrue(stderr().contains("cannot be used together"));
        }

        @Test
        @DisplayName("exits 1 when new end time would not be after effective start time")
        void failsWhenEffectiveEndNotAfterStart() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "edit", "Riverside Park", "1", "--end", "08:00");
            assertEquals(1, exit);
            assertTrue(stderr().contains("End time must be after start time"));
        }

        @Test
        @DisplayName("exits 1 when the specified division does not exist")
        void failsWhenDivisionNotFound() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "edit", "Riverside Park", "1",
                "--division", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "window", "edit", "Riverside Park", "1", "--day", "Sunday");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field window delete
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field window delete")
    class WindowDelete {

        @Test
        @DisplayName("exits 0 and prints confirmation")
        void success() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "delete", "Riverside Park", "1");
            assertEquals(0, exit);
            assertTrue(stdout().contains("deleted"));
        }

        @Test
        @DisplayName("deleted window is absent from list")
        void deletedWindowAbsentFromList() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "delete", "Riverside Park", "1");
            execute("field", "window", "list", "Riverside Park");
            assertTrue(stdout().contains("No availability windows"));
        }

        @Test
        @DisplayName("remaining windows shift up after a delete")
        void remainingWindowsShiftUp() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Sunday", "--start", "09:00", "--end", "12:00");
            execute("field", "window", "delete", "Riverside Park", "1");
            execute("field", "window", "list", "Riverside Park");
            String out = stdout();
            assertFalse(out.contains("Saturday"));
            assertTrue(out.contains("Sunday"));
            assertTrue(out.lines().anyMatch(l -> l.startsWith("1")));
        }

        @Test
        @DisplayName("matches field name case-insensitively")
        void matchesFieldNameCaseInsensitively() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            assertEquals(0, execute("field", "window", "delete", "RIVERSIDE PARK", "1"));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "window", "delete", "NonExistent", "1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when window number exceeds the list size")
        void failsWhenWindowNumberTooHigh() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "delete", "Riverside Park", "5");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when window number is 0")
        void failsWhenWindowNumberIsZero() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            int exit = execute("field", "window", "delete", "Riverside Park", "0");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when field exists but has no windows")
        void failsWhenFieldHasNoWindows() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "delete", "Riverside Park", "1");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "window", "delete", "Riverside Park", "1");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }

    // -------------------------------------------------------------------------
    // field window list
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("field window list")
    class WindowList {

        @Test
        @DisplayName("exits 0 and shows guidance when field has no windows")
        void noWindowsShowsMessage() {
            execute("field", "add", "Riverside Park");
            int exit = execute("field", "window", "list", "Riverside Park");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No availability windows"));
        }

        @Test
        @DisplayName("shows column headers when windows exist")
        void showsColumnHeaders() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "list", "Riverside Park");
            String out = stdout();
            assertTrue(out.contains("DAY"));
            assertTrue(out.contains("START"));
            assertTrue(out.contains("END"));
            assertTrue(out.contains("DIVISION"));
        }

        @Test
        @DisplayName("shows 'All divisions' for an unrestricted window")
        void showsAllDivisionsForUnrestrictedWindow() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "list", "Riverside Park");
            assertTrue(stdout().contains("All divisions"));
        }

        @Test
        @DisplayName("shows division name for a restricted window")
        void showsDivisionNameForRestrictedWindow() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00", "--division", "Majors");
            execute("field", "window", "list", "Riverside Park");
            assertTrue(stdout().contains("Majors"));
        }

        @Test
        @DisplayName("shows [deleted] for a window whose division was removed")
        void showsDeletedForOrphanedWindow() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00", "--division", "Majors");
            execute("division", "delete", "Majors");
            execute("field", "window", "list", "Riverside Park");
            assertTrue(stdout().contains("[deleted]"));
        }

        @Test
        @DisplayName("prints a warning when orphaned windows exist")
        void printsWarningForOrphanedWindows() {
            execute("division", "add", "Majors", "--duration", "120");
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00", "--division", "Majors");
            execute("division", "delete", "Majors");
            execute("field", "window", "list", "Riverside Park");
            assertTrue(stdout().contains("Warning"));
        }

        @Test
        @DisplayName("shows correct 1-based index numbers for each window")
        void showsCorrectIndexNumbers() {
            execute("field", "add", "Riverside Park");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Saturday", "--start", "09:00", "--end", "17:00");
            execute("field", "window", "add", "Riverside Park",
                "--day", "Sunday", "--start", "09:00", "--end", "12:00");
            execute("field", "window", "list", "Riverside Park");
            String out = stdout();
            assertTrue(out.lines().anyMatch(l -> l.trim().startsWith("1")));
            assertTrue(out.lines().anyMatch(l -> l.trim().startsWith("2")));
        }

        @Test
        @DisplayName("exits 1 when field does not exist")
        void failsWhenFieldNotFound() {
            int exit = execute("field", "window", "list", "NonExistent");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 2 on corrupted league data")
        void exitsOnCorruptedData() throws IOException {
            corruptLeagueFile();
            int exit = execute("field", "window", "list", "Riverside Park");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Failed to access league data"));
        }
    }
}
