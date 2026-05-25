package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.junit.jupiter.api.Assertions.*;

class DayParserTest {

    // -------------------------------------------------------------------------
    // parse() — full names
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("parse() full names")
    class ParseFullNames {

        @Test
        @DisplayName("parses monday")
        void monday() { assertEquals(DayOfWeek.MONDAY, DayParser.parse("monday").orElseThrow()); }

        @Test
        @DisplayName("parses tuesday")
        void tuesday() { assertEquals(DayOfWeek.TUESDAY, DayParser.parse("tuesday").orElseThrow()); }

        @Test
        @DisplayName("parses wednesday")
        void wednesday() { assertEquals(DayOfWeek.WEDNESDAY, DayParser.parse("wednesday").orElseThrow()); }

        @Test
        @DisplayName("parses thursday")
        void thursday() { assertEquals(DayOfWeek.THURSDAY, DayParser.parse("thursday").orElseThrow()); }

        @Test
        @DisplayName("parses friday")
        void friday() { assertEquals(DayOfWeek.FRIDAY, DayParser.parse("friday").orElseThrow()); }

        @Test
        @DisplayName("parses saturday")
        void saturday() { assertEquals(DayOfWeek.SATURDAY, DayParser.parse("saturday").orElseThrow()); }

        @Test
        @DisplayName("parses sunday")
        void sunday() { assertEquals(DayOfWeek.SUNDAY, DayParser.parse("sunday").orElseThrow()); }
    }

    // -------------------------------------------------------------------------
    // parse() — abbreviations
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("parse() abbreviations")
    class ParseAbbreviations {

        @Test
        @DisplayName("parses mon")
        void mon() { assertEquals(DayOfWeek.MONDAY, DayParser.parse("mon").orElseThrow()); }

        @Test
        @DisplayName("parses tue")
        void tue() { assertEquals(DayOfWeek.TUESDAY, DayParser.parse("tue").orElseThrow()); }

        @Test
        @DisplayName("parses wed")
        void wed() { assertEquals(DayOfWeek.WEDNESDAY, DayParser.parse("wed").orElseThrow()); }

        @Test
        @DisplayName("parses thu")
        void thu() { assertEquals(DayOfWeek.THURSDAY, DayParser.parse("thu").orElseThrow()); }

        @Test
        @DisplayName("parses fri")
        void fri() { assertEquals(DayOfWeek.FRIDAY, DayParser.parse("fri").orElseThrow()); }

        @Test
        @DisplayName("parses sat")
        void sat() { assertEquals(DayOfWeek.SATURDAY, DayParser.parse("sat").orElseThrow()); }

        @Test
        @DisplayName("parses sun")
        void sun() { assertEquals(DayOfWeek.SUNDAY, DayParser.parse("sun").orElseThrow()); }
    }

    // -------------------------------------------------------------------------
    // parse() — case insensitivity and whitespace
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("parse() case insensitivity")
    class ParseCaseInsensitive {

        @Test
        @DisplayName("accepts MONDAY (all uppercase)")
        void uppercaseFull() {
            assertEquals(DayOfWeek.MONDAY, DayParser.parse("MONDAY").orElseThrow());
        }

        @Test
        @DisplayName("accepts Wednesday (title case)")
        void titleCase() {
            assertEquals(DayOfWeek.WEDNESDAY, DayParser.parse("Wednesday").orElseThrow());
        }

        @Test
        @DisplayName("accepts MON (uppercase abbreviation)")
        void uppercaseAbbr() {
            assertEquals(DayOfWeek.MONDAY, DayParser.parse("MON").orElseThrow());
        }

        @Test
        @DisplayName("accepts Sun (mixed-case abbreviation)")
        void mixedCaseAbbr() {
            assertEquals(DayOfWeek.SUNDAY, DayParser.parse("Sun").orElseThrow());
        }

        @Test
        @DisplayName("trims surrounding whitespace before parsing")
        void trimsWhitespace() {
            assertEquals(DayOfWeek.FRIDAY, DayParser.parse("  friday  ").orElseThrow());
        }
    }

    // -------------------------------------------------------------------------
    // parse() — invalid inputs
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("parse() invalid inputs")
    class ParseInvalidInputs {

        @Test
        @DisplayName("returns empty for null")
        void nullInput() {
            assertTrue(DayParser.parse(null).isEmpty());
        }

        @Test
        @DisplayName("returns empty for empty string")
        void emptyString() {
            assertTrue(DayParser.parse("").isEmpty());
        }

        @Test
        @DisplayName("returns empty for a completely unrecognized string")
        void garbage() {
            assertTrue(DayParser.parse("funday").isEmpty());
        }

        @Test
        @DisplayName("returns empty for a partial name that is not a valid abbreviation")
        void partialName() {
            assertTrue(DayParser.parse("wedn").isEmpty());
        }

        @Test
        @DisplayName("returns empty for a numeric string")
        void numericString() {
            assertTrue(DayParser.parse("3").isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // hint()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hint() returns a non-blank descriptive string")
    void hintIsDescriptive() {
        String hint = DayParser.hint();
        assertNotNull(hint);
        assertFalse(hint.isBlank());
        assertTrue(hint.contains("monday") || hint.contains("mon"),
            "hint should mention full names or abbreviations");
    }

    // -------------------------------------------------------------------------
    // displayName()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("displayName()")
    class DisplayNameTests {

        @Test
        @DisplayName("returns title-case Monday for MONDAY")
        void monday() { assertEquals("Monday", DayParser.displayName(DayOfWeek.MONDAY)); }

        @Test
        @DisplayName("returns title-case Wednesday for WEDNESDAY")
        void wednesday() { assertEquals("Wednesday", DayParser.displayName(DayOfWeek.WEDNESDAY)); }

        @Test
        @DisplayName("returns title-case Sunday for SUNDAY")
        void sunday() { assertEquals("Sunday", DayParser.displayName(DayOfWeek.SUNDAY)); }

        @Test
        @DisplayName("all seven days produce title-case names")
        void allDaysAreTitleCase() {
            for (DayOfWeek day : DayOfWeek.values()) {
                String name = DayParser.displayName(day);
                assertTrue(Character.isUpperCase(name.charAt(0)),
                    "First char of " + name + " should be uppercase");
                assertTrue(name.substring(1).equals(name.substring(1).toLowerCase()),
                    "Rest of " + name + " should be lowercase");
            }
        }
    }
}
