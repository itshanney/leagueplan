package org.leagueplan.planr.command;

import java.time.DayOfWeek;
import java.util.Map;
import java.util.Optional;

class DayParser {

  private static final Map<String, DayOfWeek> ABBREVIATIONS =
      Map.of(
          "MON", DayOfWeek.MONDAY,
          "TUE", DayOfWeek.TUESDAY,
          "WED", DayOfWeek.WEDNESDAY,
          "THU", DayOfWeek.THURSDAY,
          "FRI", DayOfWeek.FRIDAY,
          "SAT", DayOfWeek.SATURDAY,
          "SUN", DayOfWeek.SUNDAY);

  static Optional<DayOfWeek> parse(String input) {
    if (input == null) return Optional.empty();
    String upper = input.trim().toUpperCase();
    DayOfWeek abbr = ABBREVIATIONS.get(upper);
    if (abbr != null) return Optional.of(abbr);
    try {
      return Optional.of(DayOfWeek.valueOf(upper));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  static String hint() {
    return "monday–sunday or mon–sun (case-insensitive)";
  }

  static String displayName(DayOfWeek day) {
    String name = day.name();
    return Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase();
  }
}
