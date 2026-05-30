package org.leagueplan.planr.command;

import java.io.PrintStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class CalendarRenderer {

  private static final int COL_WIDTH = 10;
  private static final int MARGIN = 5;
  // Grid center position: MARGIN + half of 7 columns = 5 + 35 = 40
  private static final int GRID_CENTER = MARGIN + (7 * COL_WIDTH) / 2;

  private static final String[] DAY_ABBR = {
    "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"
  };

  private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

  // Sort: time ASC → GAME(0) < PLAYOFF(1) < PRACTICE(2) → division → description
  static final Comparator<CalendarCommand.CalendarEvent> EVENT_ORDER =
      Comparator.comparing(CalendarCommand.CalendarEvent::time)
          .thenComparingInt(e -> e.type().ordinal())
          .thenComparing(
              CalendarCommand.CalendarEvent::divisionName, String.CASE_INSENSITIVE_ORDER)
          .thenComparing(
              CalendarCommand.CalendarEvent::description, String.CASE_INSENSITIVE_ORDER);

  private CalendarRenderer() {}

  // ---------------------------------------------------------------------------
  // Weekly view
  // ---------------------------------------------------------------------------

  static void renderWeekly(
      List<CalendarCommand.CalendarEvent> events, LocalDate weekStart, PrintStream out) {

    LocalDate weekEnd = weekStart.plusDays(6);
    out.printf(
        "Week of %s (%s) — %s (%s)%n%n",
        weekStart, titleCase(dayAbbr(weekStart)), weekEnd, titleCase(dayAbbr(weekEnd)));

    for (int d = 0; d < 7; d++) {
      LocalDate day = weekStart.plusDays(d);
      out.printf("  %s %s%n", DAY_ABBR[d], day);

      List<CalendarCommand.CalendarEvent> dayEvents =
          events.stream()
              .filter(e -> e.date().equals(day))
              .sorted(EVENT_ORDER)
              .toList();

      if (dayEvents.isEmpty()) {
        out.println("    (no events)");
      } else {
        dayEvents.forEach(e -> out.println(formatEventLine(e)));
      }

      if (d < 6) out.println();
    }

    out.println();
    printWeeklySummary(events, out);
  }

  private static void printWeeklySummary(
      List<CalendarCommand.CalendarEvent> events, PrintStream out) {
    long g = countByType(events, CalendarCommand.EventType.GAME);
    long po = countByType(events, CalendarCommand.EventType.PLAYOFF);
    long p = countByType(events, CalendarCommand.EventType.PRACTICE);
    out.printf("%d events this week  (G: %d  PO: %d  P: %d)%n", events.size(), g, po, p);
  }

  // ---------------------------------------------------------------------------
  // Monthly view
  // ---------------------------------------------------------------------------

  static void renderMonthly(
      List<CalendarCommand.CalendarEvent> events, YearMonth month, PrintStream out) {

    renderMonthlyGrid(events, month, out);
    out.println();
    out.println("Legend: G = Game   PO = Playoff   P = Practice");
    out.println();
    renderMonthlyListing(events, out);
    out.println();
    printMonthlySummary(events, month, out);
  }

  private static void renderMonthlyGrid(
      List<CalendarCommand.CalendarEvent> events, YearMonth month, PrintStream out) {

    String title = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"));
    int titlePad = GRID_CENTER - title.length() / 2;
    out.println(" ".repeat(Math.max(0, titlePad)) + title);

    // Headers right-aligned to match the right-aligned day numbers below them.
    StringBuilder header = new StringBuilder(" ".repeat(MARGIN));
    for (String abbr : DAY_ABBR) {
      header.append(String.format("%" + COL_WIDTH + "s", abbr));
    }
    out.println(header.toString().stripTrailing());

    // Pre-compute per-date event counts: [GAME, PLAYOFF, PRACTICE] indexed by EventType.ordinal()
    Map<LocalDate, long[]> countsByDate = buildCountMap(events);

    LocalDate monthEnd = month.atEndOfMonth();
    LocalDate rowStart = toSundayOnOrBefore(month.atDay(1));

    while (!rowStart.isAfter(monthEnd)) {
      emitWeekRow(rowStart, month, countsByDate, out);
      rowStart = rowStart.plusWeeks(1);
    }
  }

  private static void emitWeekRow(
      LocalDate rowStart,
      YearMonth month,
      Map<LocalDate, long[]> countsByDate,
      PrintStream out) {

    StringBuilder l1 = new StringBuilder(" ".repeat(MARGIN)); // date numbers
    StringBuilder l2 = new StringBuilder(" ".repeat(MARGIN)); // G counts
    StringBuilder l3 = new StringBuilder(" ".repeat(MARGIN)); // P counts
    StringBuilder l4 = new StringBuilder(" ".repeat(MARGIN)); // PO counts

    for (int i = 0; i < 7; i++) {
      LocalDate day = rowStart.plusDays(i);
      boolean inMonth = YearMonth.from(day).equals(month);
      long[] counts = countsByDate.getOrDefault(day, new long[3]);

      l1.append(String.format("%" + COL_WIDTH + "s", inMonth ? day.getDayOfMonth() : ""));
      l2.append(cellCount(inMonth, counts[CalendarCommand.EventType.GAME.ordinal()], "G"));
      l3.append(cellCount(inMonth, counts[CalendarCommand.EventType.PRACTICE.ordinal()], "P"));
      l4.append(cellCount(inMonth, counts[CalendarCommand.EventType.PLAYOFF.ordinal()], "PO"));
    }

    out.println(l1.toString().stripTrailing());
    out.println(l2.toString().stripTrailing());
    out.println(l3.toString().stripTrailing());
    out.println(l4.toString().stripTrailing());
  }

  private static String cellCount(boolean inMonth, long count, String suffix) {
    String value = (inMonth && count > 0) ? count + suffix : "";
    return String.format("%" + COL_WIDTH + "s", value);
  }

  private static void renderMonthlyListing(
      List<CalendarCommand.CalendarEvent> events, PrintStream out) {

    Map<LocalDate, List<CalendarCommand.CalendarEvent>> byDate = new TreeMap<>();
    for (CalendarCommand.CalendarEvent e : events) {
      byDate.computeIfAbsent(e.date(), k -> new ArrayList<>()).add(e);
    }

    boolean first = true;
    for (Map.Entry<LocalDate, List<CalendarCommand.CalendarEvent>> entry : byDate.entrySet()) {
      if (!first) out.println();
      first = false;

      LocalDate date = entry.getKey();
      out.printf("  %s %s%n", dayAbbr(date), date);
      entry.getValue().stream().sorted(EVENT_ORDER).forEach(e -> out.println(formatEventLine(e)));
    }
  }

  private static void printMonthlySummary(
      List<CalendarCommand.CalendarEvent> events, YearMonth month, PrintStream out) {
    String title = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"));
    long g = countByType(events, CalendarCommand.EventType.GAME);
    long po = countByType(events, CalendarCommand.EventType.PLAYOFF);
    long p = countByType(events, CalendarCommand.EventType.PRACTICE);
    out.printf("%d events in %s  (G: %d  PO: %d  P: %d)%n", events.size(), title, g, po, p);
  }

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  static String formatEventLine(CalendarCommand.CalendarEvent e) {
    String tag =
        switch (e.type()) {
          case GAME -> "[G]";
          case PLAYOFF -> "[PO]";
          case PRACTICE -> "[P]";
        };
    return String.format(
        "    %s  %s  %s  @  %s  (%s)",
        e.time().format(TIME_FMT), tag, e.description(), e.fieldName(), e.divisionName());
  }

  // Returns the Sunday on or before the given date (ISO: Mon=1 ... Sun=7).
  // Sunday-first: subtract (dayOfWeek % 7) — Sun(7%7=0), Mon(1), ..., Sat(6).
  static LocalDate toSundayOnOrBefore(LocalDate date) {
    int dow = date.getDayOfWeek().getValue();
    return date.minusDays(dow % 7);
  }

  // 3-letter uppercase abbreviation for a date's day of week (Sun=index 0).
  private static String dayAbbr(LocalDate date) {
    int index = date.getDayOfWeek().getValue() % 7; // Sun=7%7=0, Mon=1, ..., Sat=6
    return DAY_ABBR[index];
  }

  // Converts "SUN" → "Sun" for use in the weekly header line.
  private static String titleCase(String abbr) {
    return abbr.charAt(0) + abbr.substring(1).toLowerCase();
  }

  private static Map<LocalDate, long[]> buildCountMap(
      List<CalendarCommand.CalendarEvent> events) {
    Map<LocalDate, long[]> map = new TreeMap<>();
    for (CalendarCommand.CalendarEvent e : events) {
      long[] counts = map.computeIfAbsent(e.date(), k -> new long[3]);
      counts[e.type().ordinal()]++;
    }
    return map;
  }

  private static long countByType(
      List<CalendarCommand.CalendarEvent> events, CalendarCommand.EventType type) {
    return events.stream().filter(e -> e.type() == type).count();
  }
}
