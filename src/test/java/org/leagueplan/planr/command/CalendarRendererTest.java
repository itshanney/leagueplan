package org.leagueplan.planr.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CalendarRenderer.
 *
 * <p>All tests construct CalendarEvents directly (no CLI, no file system) and capture output via
 * ByteArrayOutputStream. May 2026 is used as the reference month throughout: May 1, 2026 = Friday.
 */
class CalendarRendererTest {

  // ---------------------------------------------------------------------------
  // Shared constants — right edge of each day column (0-indexed, 5-char margin + 10-char columns)
  // ---------------------------------------------------------------------------

  private static final int SUN_END = 14;  // MARGIN + 1*COL_WIDTH - 1
  private static final int TUE_END = 34;  // MARGIN + 3*COL_WIDTH - 1
  private static final int FRI_END = 64;  // MARGIN + 6*COL_WIDTH - 1
  private static final int SAT_END = 74;  // MARGIN + 7*COL_WIDTH - 1

  // ---------------------------------------------------------------------------
  // Factories
  // ---------------------------------------------------------------------------

  private static CalendarCommand.CalendarEvent game(
      LocalDate date, LocalTime time, String home, String away, String field, String division) {
    return new CalendarCommand.CalendarEvent(
        date,
        time,
        CalendarCommand.EventType.GAME,
        home + " vs " + away,
        field,
        division,
        List.of(home.toLowerCase(), away.toLowerCase()));
  }

  private static CalendarCommand.CalendarEvent playoff(
      LocalDate date, LocalTime time, String posA, String posB, String field, String division) {
    return new CalendarCommand.CalendarEvent(
        date,
        time,
        CalendarCommand.EventType.PLAYOFF,
        posA + " vs " + posB,
        field,
        division,
        List.of());
  }

  private static CalendarCommand.CalendarEvent practice(
      LocalDate date, LocalTime time, String team, String field, String division) {
    return new CalendarCommand.CalendarEvent(
        date,
        time,
        CalendarCommand.EventType.PRACTICE,
        team,
        field,
        division,
        List.of(team.toLowerCase()));
  }

  private static String render(
      List<CalendarCommand.CalendarEvent> events, LocalDate weekStart) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    CalendarRenderer.renderWeekly(events, weekStart, new PrintStream(bos));
    return bos.toString();
  }

  private static String renderMonthly(
      List<CalendarCommand.CalendarEvent> events, YearMonth month) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    CalendarRenderer.renderMonthly(events, month, new PrintStream(bos));
    return bos.toString();
  }

  private static List<String> lines(String output) {
    return Arrays.asList(output.split("\n", -1));
  }

  // ---------------------------------------------------------------------------
  // Weekly view
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("weekly view")
  class WeeklyView {

    private static final LocalDate WEEK_START = LocalDate.of(2026, 5, 3); // Sunday

    @Test
    @DisplayName("shows (no events) for every day when event list is empty")
    void emptyEventsShowNoEventsOnEachDay() {
      String out = render(List.of(), WEEK_START);
      long noEventLineCount = Arrays.stream(out.split("\n"))
          .filter(l -> l.contains("(no events)"))
          .count();
      assertEquals(7, noEventLineCount);
    }

    @Test
    @DisplayName("header line shows week bounds with title-case day abbreviations")
    void headerLineIsCorrect() {
      String out = render(List.of(), WEEK_START);
      String headerLine = lines(out).get(0);
      assertEquals("Week of 2026-05-03 (Sun) — 2026-05-09 (Sat)", headerLine);
    }

    @Test
    @DisplayName("day section headers are uppercase three-letter abbreviations")
    void daySectionHeadersAreUppercase() {
      String out = render(List.of(), WEEK_START);
      assertTrue(out.contains("  SUN 2026-05-03"));
      assertTrue(out.contains("  MON 2026-05-04"));
      assertTrue(out.contains("  TUE 2026-05-05"));
      assertTrue(out.contains("  WED 2026-05-06"));
      assertTrue(out.contains("  THU 2026-05-07"));
      assertTrue(out.contains("  FRI 2026-05-08"));
      assertTrue(out.contains("  SAT 2026-05-09"));
    }

    @Test
    @DisplayName("game event line has correct format: time [G] desc @ field (division)")
    void gameEventLineFormat() {
      CalendarCommand.CalendarEvent e = game(
          WEEK_START.plusDays(6), // Saturday
          LocalTime.of(9, 30),
          "Blue Jays", "Cardinals", "Riverside Park", "Majors");

      String out = render(List.of(e), WEEK_START);
      assertTrue(out.contains("    09:30  [G]  Blue Jays vs Cardinals  @  Riverside Park  (Majors)"));
    }

    @Test
    @DisplayName("playoff event line uses [PO] tag and bracket position strings")
    void playoffEventLineFormat() {
      CalendarCommand.CalendarEvent e = playoff(
          WEEK_START.plusDays(1), // Monday
          LocalTime.of(14, 0),
          "W of G1", "L of G2", "Hillside", "Majors");

      String out = render(List.of(e), WEEK_START);
      assertTrue(out.contains("    14:00  [PO]  W of G1 vs L of G2  @  Hillside  (Majors)"));
    }

    @Test
    @DisplayName("practice event line has team name only (no '(practice)' suffix)")
    void practiceEventLineOmitsPracticeSuffix() {
      CalendarCommand.CalendarEvent e = practice(
          WEEK_START.plusDays(2), // Tuesday
          LocalTime.of(10, 0),
          "Blue Jays", "Riverside Park", "Majors");

      String out = render(List.of(e), WEEK_START);
      assertTrue(out.contains("    10:00  [P]  Blue Jays  @  Riverside Park  (Majors)"));
      assertFalse(out.contains("(practice)"));
    }

    @Test
    @DisplayName("events on the same day are sorted time ascending")
    void sameTypeSortsByTimeAscending() {
      LocalDate sat = WEEK_START.plusDays(6);
      CalendarCommand.CalendarEvent late = game(sat, LocalTime.of(14, 0), "A", "B", "F", "D");
      CalendarCommand.CalendarEvent early = game(sat, LocalTime.of(9, 0), "C", "D", "F", "D");

      String out = render(List.of(late, early), WEEK_START);
      int earlyIdx = out.indexOf("09:00");
      int lateIdx = out.indexOf("14:00");
      assertTrue(earlyIdx < lateIdx);
    }

    @Test
    @DisplayName("when times are equal, GAME sorts before PLAYOFF, PLAYOFF before PRACTICE")
    void typeOrderIsGamePlayoffPractice() {
      LocalDate sat = WEEK_START.plusDays(6);
      LocalTime same = LocalTime.of(10, 0);
      CalendarCommand.CalendarEvent p = practice(sat, same, "Blue Jays", "F", "D");
      CalendarCommand.CalendarEvent po = playoff(sat, same, "W of G1", "L of G2", "F", "D");
      CalendarCommand.CalendarEvent g = game(sat, same, "A", "B", "F", "D");

      String out = render(List.of(p, po, g), WEEK_START);
      int gIdx = out.indexOf("[G]");
      int poIdx = out.indexOf("[PO]");
      int pIdx = out.indexOf("[P]");
      assertTrue(gIdx < poIdx, "GAME should appear before PLAYOFF");
      assertTrue(poIdx < pIdx, "PLAYOFF should appear before PRACTICE");
    }

    @Test
    @DisplayName("when time and type are equal, events sort alphabetically by division then description")
    void tieBreaksByDivisionThenDescription() {
      LocalDate sat = WEEK_START.plusDays(6);
      LocalTime same = LocalTime.of(10, 0);
      CalendarCommand.CalendarEvent zDiv = game(sat, same, "Z", "Z", "F", "Zebras");
      CalendarCommand.CalendarEvent aDiv = game(sat, same, "A", "A", "F", "Alpha");

      String out = render(List.of(zDiv, aDiv), WEEK_START);
      int aIdx = out.indexOf("(Alpha)");
      int zIdx = out.indexOf("(Zebras)");
      assertTrue(aIdx < zIdx);
    }

    @Test
    @DisplayName("events on the wrong day of the week do not appear in any day section")
    void eventsOnlyAppearOnTheirDay() {
      // Event on Monday; the Saturday section should show (no events)
      CalendarCommand.CalendarEvent e = game(
          WEEK_START.plusDays(1), // Monday
          LocalTime.of(10, 0), "A", "B", "F", "D");

      String out = render(List.of(e), WEEK_START);
      String satHeader = "  SAT " + WEEK_START.plusDays(6);
      int satIdx = out.indexOf(satHeader);
      assertTrue(satIdx >= 0, "SAT section header not found in output");
      assertTrue(out.substring(satIdx).contains("(no events)"));
    }

    @Test
    @DisplayName("summary line correctly counts each event type")
    void summaryLineCountsTypes() {
      LocalDate sat = WEEK_START.plusDays(6);
      CalendarCommand.CalendarEvent g1 = game(sat, LocalTime.of(9, 0), "A", "B", "F", "D");
      CalendarCommand.CalendarEvent g2 = game(sat, LocalTime.of(11, 0), "C", "D", "F", "D");
      CalendarCommand.CalendarEvent po = playoff(sat, LocalTime.of(14, 0), "W", "L", "F", "D");
      CalendarCommand.CalendarEvent p = practice(sat, LocalTime.of(8, 0), "T", "F", "D");

      String out = render(List.of(g1, g2, po, p), WEEK_START);
      assertTrue(out.contains("4 events this week  (G: 2  PO: 1  P: 1)"));
    }

    @Test
    @DisplayName("week always spans exactly 7 days (Sun through Sat)")
    void weekSpansSevenDays() {
      String out = render(List.of(), WEEK_START);
      // Header shows Sun and Sat bounding dates
      assertTrue(out.startsWith("Week of 2026-05-03 (Sun) — 2026-05-09 (Sat)"));
    }
  }

  // ---------------------------------------------------------------------------
  // Monthly view — calendar grid
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("monthly view — calendar grid")
  class MonthlyGrid {

    // May 2026: starts Friday (DayOfWeek=5), ends Sunday May 31.
    // 6 week rows needed.
    private static final YearMonth MAY_2026 = YearMonth.of(2026, 5);

    // Events used for grid alignment tests:
    //   May 2  (SAT, col 6, week row 1): 2 games
    //   May 8  (FRI, col 5, week row 2): 1 practice
    //   May 9  (SAT, col 6, week row 2): 3 games
    //   May 12 (TUE, col 2, week row 3): 3 practices
    //   May 16 (SAT, col 6, week row 3): 1 playoff

    private static List<CalendarCommand.CalendarEvent> alignmentEvents() {
      return List.of(
          game(LocalDate.of(2026, 5, 2), LocalTime.of(9, 0), "A", "B", "F", "D"),
          game(LocalDate.of(2026, 5, 2), LocalTime.of(11, 0), "C", "D", "F", "D"),
          practice(LocalDate.of(2026, 5, 8), LocalTime.of(9, 0), "Team1", "F", "D"),
          game(LocalDate.of(2026, 5, 9), LocalTime.of(9, 0), "A", "B", "F", "D"),
          game(LocalDate.of(2026, 5, 9), LocalTime.of(11, 0), "C", "D", "F", "D"),
          game(LocalDate.of(2026, 5, 9), LocalTime.of(13, 0), "E", "F", "F", "D"),
          practice(LocalDate.of(2026, 5, 12), LocalTime.of(9, 0), "T1", "F", "D"),
          practice(LocalDate.of(2026, 5, 12), LocalTime.of(11, 0), "T2", "F", "D"),
          practice(LocalDate.of(2026, 5, 12), LocalTime.of(13, 0), "T3", "F", "D"),
          playoff(LocalDate.of(2026, 5, 16), LocalTime.of(10, 0), "W of G1", "L of G2", "F", "D"));
    }

    // Grid lines for May 2026: title(0), header(1), then 6 rows × 4 lines (lines 2-25)
    private static List<String> gridLines(List<CalendarCommand.CalendarEvent> events) {
      return lines(renderMonthly(events, MAY_2026));
    }

    @Test
    @DisplayName("month title is centered at position 36 in an 80-char terminal")
    void titleIsCenteredCorrectly() {
      String title = gridLines(List.of()).get(0);
      // "May 2026" (8 chars) should start at position 36 (= grid center 40 - 4)
      assertEquals(36, title.indexOf("May 2026"));
    }

    @Test
    @DisplayName("column header uses Sun-Sat order, right-aligned in 10-char columns")
    void columnHeaderOrder() {
      String header = gridLines(List.of()).get(1);
      // Each 3-char abbreviation is right-aligned in its 10-char column, so the
      // abbreviation starts at MARGIN + colIndex*COL_WIDTH + 7 (= 10 - 3 padding spaces).
      assertEquals(12, header.indexOf("SUN")); // col 0: 5 + 0*10 + 7
      assertEquals(22, header.indexOf("MON")); // col 1: 5 + 1*10 + 7
      assertEquals(32, header.indexOf("TUE")); // col 2: 5 + 2*10 + 7
      assertEquals(42, header.indexOf("WED")); // col 3
      assertEquals(52, header.indexOf("THU")); // col 4
      assertEquals(62, header.indexOf("FRI")); // col 5
      assertEquals(72, header.indexOf("SAT")); // col 6
    }

    @Test
    @DisplayName("May 2026 produces exactly 6 week rows of 4 lines each")
    void mayProducesSixWeekRowsOfFourLines() {
      List<String> gl = gridLines(List.of());
      // Lines 2-25 are the 6 week rows (24 lines total)
      // Line 26 is blank, line 27 is the legend
      String legendLine = "";
      for (int i = 0; i < gl.size(); i++) {
        if (gl.get(i).startsWith("Legend:")) {
          // Verify the 24 grid data lines come before it
          assertEquals(27, i, "Legend should be at line index 27");
          legendLine = gl.get(i);
          break;
        }
      }
      assertFalse(legendLine.isEmpty());
    }

    @Test
    @DisplayName("each week row in the output always emits exactly 4 lines")
    void eachWeekRowEmitsFourLines() {
      // Use an event-free month to get all-blank event rows — must still emit 4 lines
      List<String> gl = gridLines(List.of());
      // lines 2-25 are the 6 × 4 grid rows
      // Verify no week row collapses: count total grid-data lines between header and legend
      int headerIdx = 1;
      int legendIdx = -1;
      for (int i = 0; i < gl.size(); i++) {
        if (gl.get(i).startsWith("Legend:")) {
          legendIdx = i;
          break;
        }
      }
      // Between header (exclusive) and blank line before legend (exclusive) = 24 lines + 1 blank
      // legend is at index 27, blank at 26, grid data lines = 2..25 (24 lines)
      int gridDataLines = legendIdx - headerIdx - 2; // subtract blank line before legend
      assertEquals(24, gridDataLines, "6 week rows × 4 lines = 24 grid data lines");
    }

    @Test
    @DisplayName("day numbers are right-aligned in their respective columns")
    void dayNumbersRightAligned() {
      List<String> gl = gridLines(alignmentEvents());
      // Week row 1 date line (line 2): May 1=Fri at pos 64, May 2=Sat at pos 74
      String dateLine1 = gl.get(2);
      assertEquals('1', dateLine1.charAt(FRI_END)); // May 1 in Fri col
      assertEquals('2', dateLine1.charAt(SAT_END)); // May 2 in Sat col

      // Week row 3 date line (line 10): 2-digit day 12=Tue at pos 33-34
      String dateLine3 = gl.get(10);
      // "12" ends at TUE_END (34), starts at 33
      assertEquals('1', dateLine3.charAt(TUE_END - 1));
      assertEquals('2', dateLine3.charAt(TUE_END));
    }

    @Test
    @DisplayName("out-of-month cells are blank (no day number, no counts)")
    void outOfMonthCellsAreBlank() {
      List<String> gl = gridLines(alignmentEvents());
      // Week row 1 date line: columns Sun-Thu (positions 5-54) are out-of-month for May 2026
      String dateLine = gl.get(2);
      // Positions 5-54 should all be spaces
      String beforeFri = dateLine.substring(0, 55); // positions 0-54
      assertTrue(beforeFri.isBlank(), "Out-of-month cells should be blank");
    }

    @Test
    @DisplayName("game count 2G appears in Sat column of G line for week row 1")
    void gameCountInCorrectColumn() {
      List<String> gl = gridLines(alignmentEvents());
      // Week row 1, G line = line 3
      String gLine = gl.get(3).stripTrailing();
      // May 2 (SAT) has 2 games; "2G" right-aligned in Sat col (ends at pos 74)
      assertTrue(gLine.endsWith("2G"));
      assertEquals(SAT_END - 1, gLine.lastIndexOf('2'));
    }

    @Test
    @DisplayName("practice count 1P appears in Fri column of P line for week row 2")
    void practiceCountFriColumn() {
      List<String> gl = gridLines(alignmentEvents());
      // Week row 2 (lines 6-9), P line = line 8
      String pLine = gl.get(8).stripTrailing();
      // May 8 (FRI) has 1 practice; "1P" right-aligned in Fri col (ends at 64)
      // Sat col is blank so the line ends at Fri col
      assertTrue(pLine.endsWith("1P"));
      assertEquals(FRI_END - 1, pLine.lastIndexOf('1'));
    }

    @Test
    @DisplayName("practice count 3P appears in Tue column of P line for week row 3")
    void practiceCountTueColumn() {
      List<String> gl = gridLines(alignmentEvents());
      // Week row 3 (lines 10-13), P line = line 12
      String pLine = gl.get(12);
      // May 12 (TUE) has 3 practices; "3P" right-aligned in Tue col (ends at pos 34)
      // "3P" starts at 33, ends at 34
      assertEquals(TUE_END - 1, pLine.indexOf("3P"));
    }

    @Test
    @DisplayName("playoff count 1PO appears in Sat column of PO line for week row 3")
    void playoffCountInCorrectColumn() {
      List<String> gl = gridLines(alignmentEvents());
      // Week row 3 (lines 10-13), PO line = line 13
      String poLine = gl.get(13).stripTrailing();
      // May 16 (SAT) has 1 playoff; "1PO" right-aligned in Sat col (ends at 74)
      assertTrue(poLine.endsWith("1PO"));
    }

    @Test
    @DisplayName("zero counts render as blank, not as '0G'")
    void zeroCountIsBlankNotZeroG() {
      List<String> gl = gridLines(alignmentEvents());
      // Week row 3 G line (line 11): no games on May 10-16
      String gLine = gl.get(11);
      assertFalse(gLine.contains("0G"), "Zero game count should be blank, not '0G'");
      assertFalse(gLine.contains("0P"), "Zero practice count should be blank, not '0P'");
      assertFalse(gLine.contains("0PO"), "Zero playoff count should be blank, not '0PO'");
    }

    @Test
    @DisplayName("legend line is present and correctly formatted")
    void legendLineIsPresent() {
      String out = renderMonthly(List.of(), MAY_2026);
      assertTrue(out.contains("Legend: G = Game   PO = Playoff   P = Practice"));
    }

    @Test
    @DisplayName("month starting on Sunday has no blank leading cells in first week row")
    void monthStartingOnSundayHasNoLeadingBlanks() {
      // March 1, 2026 is a Sunday — first week row starts on March 1 itself
      YearMonth march2026 = YearMonth.of(2026, 3);
      List<String> gl = lines(renderMonthly(List.of(), march2026));
      String firstDateLine = gl.get(2);
      // Sun col (position 5-14): should contain "1" at position 14
      assertEquals('1', firstDateLine.charAt(SUN_END));
    }
  }

  // ---------------------------------------------------------------------------
  // Monthly view — event listing
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("monthly view — event listing")
  class MonthlyEventListing {

    private static final YearMonth MAY_2026 = YearMonth.of(2026, 5);

    @Test
    @DisplayName("dates with events appear in ascending date order")
    void eventListingIsChronological() {
      List<CalendarCommand.CalendarEvent> events = List.of(
          game(LocalDate.of(2026, 5, 16), LocalTime.of(10, 0), "A", "B", "F", "D"),
          game(LocalDate.of(2026, 5, 2), LocalTime.of(10, 0), "C", "D", "F", "D"));

      String out = renderMonthly(events, MAY_2026);
      int may2Idx = out.indexOf("SAT 2026-05-02");
      int may16Idx = out.indexOf("SAT 2026-05-16");
      assertTrue(may2Idx < may16Idx);
    }

    @Test
    @DisplayName("dates with no events are not listed in the event listing")
    void datesWithNoEventsAreOmitted() {
      // Only an event on May 9; May 10 has no events
      List<CalendarCommand.CalendarEvent> events = List.of(
          game(LocalDate.of(2026, 5, 9), LocalTime.of(10, 0), "A", "B", "F", "D"));

      String out = renderMonthly(events, MAY_2026);
      assertFalse(out.contains("2026-05-10"), "May 10 has no events and should not appear in listing");
    }

    @Test
    @DisplayName("event listing day headers use uppercase day abbreviations")
    void listingUsesUppercaseDayAbbreviations() {
      List<CalendarCommand.CalendarEvent> events = List.of(
          game(LocalDate.of(2026, 5, 9), LocalTime.of(10, 0), "A", "B", "F", "D")); // Saturday

      String out = renderMonthly(events, MAY_2026);
      assertTrue(out.contains("  SAT 2026-05-09"));
      assertFalse(out.contains("  Sat 2026-05-09"), "Day abbreviation should be uppercase");
    }

    @Test
    @DisplayName("events within a day are sorted by time then type")
    void eventsWithinDayAreSorted() {
      LocalDate day = LocalDate.of(2026, 5, 9);
      CalendarCommand.CalendarEvent practice = practice(day, LocalTime.of(10, 0), "T", "F", "D");
      CalendarCommand.CalendarEvent game = game(day, LocalTime.of(10, 0), "A", "B", "F", "D");

      String out = renderMonthly(List.of(practice, game), MAY_2026);
      int gIdx = out.lastIndexOf("[G]"); // after the legend
      int pIdx = out.lastIndexOf("[P]");
      assertTrue(gIdx < pIdx, "GAME should precede PRACTICE at the same time");
    }

    @Test
    @DisplayName("event listing uses identical format to weekly view event lines")
    void eventListingFormatMatchesWeeklyView() {
      LocalDate day = LocalDate.of(2026, 5, 9);
      CalendarCommand.CalendarEvent e = game(day, LocalTime.of(9, 30), "Blue Jays", "Cardinals", "Riverside", "Majors");

      String out = renderMonthly(List.of(e), MAY_2026);
      assertTrue(out.contains("    09:30  [G]  Blue Jays vs Cardinals  @  Riverside  (Majors)"));
    }

    @Test
    @DisplayName("empty event list renders grid and summary with zero counts")
    void emptyEventListRendersCleanly() {
      String out = renderMonthly(List.of(), MAY_2026);
      assertTrue(out.contains("Legend: G = Game   PO = Playoff   P = Practice"));
      assertTrue(out.contains("0 events in May 2026  (G: 0  PO: 0  P: 0)"));
    }

    @Test
    @DisplayName("summary line correctly counts games, playoffs, and practices")
    void summaryLineCountsAllTypes() {
      List<CalendarCommand.CalendarEvent> events = List.of(
          game(LocalDate.of(2026, 5, 9), LocalTime.of(9, 0), "A", "B", "F", "D"),
          game(LocalDate.of(2026, 5, 9), LocalTime.of(11, 0), "C", "D", "F", "D"),
          playoff(LocalDate.of(2026, 5, 16), LocalTime.of(10, 0), "W", "L", "F", "D"),
          practice(LocalDate.of(2026, 5, 12), LocalTime.of(9, 0), "T", "F", "D"),
          practice(LocalDate.of(2026, 5, 12), LocalTime.of(11, 0), "U", "F", "D"),
          practice(LocalDate.of(2026, 5, 12), LocalTime.of(13, 0), "V", "F", "D"));

      String out = renderMonthly(events, MAY_2026);
      assertTrue(out.contains("6 events in May 2026  (G: 2  PO: 1  P: 3)"));
    }
  }

  // ---------------------------------------------------------------------------
  // toSundayOnOrBefore
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("toSundayOnOrBefore")
  class ToSundayOnOrBefore {

    @Test
    @DisplayName("Sunday maps to itself")
    void sundayReturnsSameDay() {
      LocalDate sunday = LocalDate.of(2026, 5, 3); // Sunday
      assertEquals(sunday, CalendarRenderer.toSundayOnOrBefore(sunday));
    }

    @Test
    @DisplayName("Monday maps to the preceding Sunday")
    void mondayMapsToPrecedingSunday() {
      LocalDate monday = LocalDate.of(2026, 5, 4);
      assertEquals(LocalDate.of(2026, 5, 3), CalendarRenderer.toSundayOnOrBefore(monday));
    }

    @Test
    @DisplayName("Saturday maps to 6 days prior Sunday")
    void saturdayMapsSixDaysBack() {
      LocalDate saturday = LocalDate.of(2026, 5, 9);
      assertEquals(LocalDate.of(2026, 5, 3), CalendarRenderer.toSundayOnOrBefore(saturday));
    }

    @Test
    @DisplayName("all 7 days of one week map to the same Sunday")
    void allSevenDaysMapToSameSunday() {
      LocalDate expectedSunday = LocalDate.of(2026, 5, 3);
      for (int d = 0; d < 7; d++) {
        LocalDate day = expectedSunday.plusDays(d);
        assertEquals(
            expectedSunday,
            CalendarRenderer.toSundayOnOrBefore(day),
            "Day " + day + " should map to " + expectedSunday);
      }
    }

    @Test
    @DisplayName("month-boundary: crosses from April into May correctly")
    void monthBoundaryCrossing() {
      // April 30, 2026 = Thursday. Preceding Sunday = April 26.
      LocalDate thursday = LocalDate.of(2026, 4, 30);
      assertEquals(LocalDate.of(2026, 4, 26), CalendarRenderer.toSundayOnOrBefore(thursday));
    }
  }

  // ---------------------------------------------------------------------------
  // formatEventLine
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("formatEventLine")
  class FormatEventLine {

    @Test
    @DisplayName("game format: 4-space indent, time, [G], desc, @, field, (division)")
    void gameFormat() {
      CalendarCommand.CalendarEvent e = game(
          LocalDate.of(2026, 5, 9), LocalTime.of(9, 0),
          "Blue Jays", "Cardinals", "Riverside Park", "Majors");
      assertEquals(
          "    09:00  [G]  Blue Jays vs Cardinals  @  Riverside Park  (Majors)",
          CalendarRenderer.formatEventLine(e));
    }

    @Test
    @DisplayName("playoff format uses [PO] tag")
    void playoffFormat() {
      CalendarCommand.CalendarEvent e = playoff(
          LocalDate.of(2026, 5, 9), LocalTime.of(14, 0),
          "W of G1", "L of G2", "Hillside", "Majors");
      assertEquals(
          "    14:00  [PO]  W of G1 vs L of G2  @  Hillside  (Majors)",
          CalendarRenderer.formatEventLine(e));
    }

    @Test
    @DisplayName("practice format uses [P] tag and team name as description")
    void practiceFormat() {
      CalendarCommand.CalendarEvent e = practice(
          LocalDate.of(2026, 5, 9), LocalTime.of(10, 30),
          "Blue Jays", "Riverside Park", "Majors");
      assertEquals(
          "    10:30  [P]  Blue Jays  @  Riverside Park  (Majors)",
          CalendarRenderer.formatEventLine(e));
    }

    @Test
    @DisplayName("time is zero-padded to HH:MM format")
    void timeIsZeroPadded() {
      CalendarCommand.CalendarEvent e = game(
          LocalDate.of(2026, 5, 9), LocalTime.of(8, 5),
          "A", "B", "F", "D");
      assertTrue(CalendarRenderer.formatEventLine(e).startsWith("    08:05  "));
    }
  }
}
