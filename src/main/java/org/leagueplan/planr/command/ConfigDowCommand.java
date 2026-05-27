package org.leagueplan.planr.command;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.leagueplan.planr.model.DayOfWeekWindow;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "dow",
    description = "Manage day-of-week availability windows for all fields.",
    subcommands = {
      ConfigDowCommand.SetCmd.class,
      ConfigDowCommand.ClearCmd.class,
      ConfigDowCommand.ListCmd.class
    },
    mixinStandardHelpOptions = true)
public class ConfigDowCommand implements Runnable {

  @ParentCommand ConfigCommand configCmd;
  @Spec CommandSpec spec;

  static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  static Optional<LocalTime> parseTime(String input) {
    if (input == null) return Optional.empty();
    try {
      return Optional.of(LocalTime.parse(input.trim(), TIME_FORMAT));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /**
   * Counts FieldBlock and FieldDateOverride entries on dates matching {@code day}. Scoped to
   * [seasonStart, seasonEnd] when configured; otherwise counts all entries.
   */
  static int countConflicts(League league, DayOfWeek day) {
    LeagueConfig config = league.config();
    LocalDate seasonStart = (config != null) ? config.seasonStart() : null;
    LocalDate seasonEnd = (config != null) ? config.seasonEnd() : null;

    int count = 0;
    for (Field field : league.fields()) {
      for (var block : field.blocks()) {
        if (block.date().getDayOfWeek() == day && inSeason(block.date(), seasonStart, seasonEnd)) {
          count++;
        }
      }
      for (var override : field.dateOverrides()) {
        if (override.date().getDayOfWeek() == day
            && inSeason(override.date(), seasonStart, seasonEnd)) {
          count++;
        }
      }
    }
    return count;
  }

  private static boolean inSeason(LocalDate date, LocalDate seasonStart, LocalDate seasonEnd) {
    if (seasonStart == null || seasonEnd == null) return true;
    return !date.isBefore(seasonStart) && !date.isAfter(seasonEnd);
  }

  // --- Set ---

  @Command(name = "set", description = "Set a day-of-week availability window for all fields.")
  static class SetCmd implements Callable<Integer> {

    @ParentCommand ConfigDowCommand parent;

    @Option(
        names = "--day",
        required = true,
        paramLabel = "<DAY>",
        description = "Day of week (e.g. wednesday, wed).")
    String dayStr;

    @Option(
        names = "--start",
        required = true,
        paramLabel = "<HH:mm>",
        description = "Window open time.")
    String startStr;

    @Option(
        names = "--end",
        required = true,
        paramLabel = "<HH:mm>",
        description = "Window close time.")
    String endStr;

    @Override
    public Integer call() {
      Optional<DayOfWeek> day = DayParser.parse(dayStr);
      if (day.isEmpty()) {
        System.err.printf(
            "Error: Unrecognized day \"%s\". Accepted: %s.%n", dayStr, DayParser.hint());
        return 1;
      }
      Optional<LocalTime> start = parseTime(startStr);
      if (start.isEmpty()) {
        System.err.printf(
            "Error: Invalid time \"%s\". Use HH:mm format (e.g., 16:00).%n", startStr);
        return 1;
      }
      Optional<LocalTime> end = parseTime(endStr);
      if (end.isEmpty()) {
        System.err.printf("Error: Invalid time \"%s\". Use HH:mm format (e.g., 21:00).%n", endStr);
        return 1;
      }
      if (!end.get().isAfter(start.get())) {
        System.err.printf(
            "Error: End time (%s) must be after start time (%s).%n", endStr, startStr);
        return 1;
      }
      try {
        League league = parent.configCmd.app.store.load();
        LeagueConfig existing = (league.config() != null) ? league.config() : LeagueConfig.empty();
        LeagueConfig updated =
            existing.withDowWindowSet(new DayOfWeekWindow(day.get(), start.get(), end.get()));
        parent.configCmd.app.store.save(league.withConfig(updated));
        System.out.printf(
            "Day-of-week window set: %s %s–%s.%n",
            DayParser.displayName(day.get()),
            start.get().format(TIME_FORMAT),
            end.get().format(TIME_FORMAT));
        int conflicts = countConflicts(league, day.get());
        if (conflicts > 0) {
          System.out.printf(
              "Warning: %d field-level %s exist on %ss within the season. "
                  + "Review with 'planr field block list' and 'planr field override list'.%n",
              conflicts, conflicts == 1 ? "entry" : "entries", DayParser.displayName(day.get()));
        }
        return 0;
      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }
  }

  // --- Clear ---

  @Command(name = "clear", description = "Remove a day-of-week availability window.")
  static class ClearCmd implements Callable<Integer> {

    @ParentCommand ConfigDowCommand parent;

    @Option(
        names = "--day",
        required = true,
        paramLabel = "<DAY>",
        description = "Day of week to clear (e.g. wednesday, wed).")
    String dayStr;

    @Override
    public Integer call() {
      Optional<DayOfWeek> day = DayParser.parse(dayStr);
      if (day.isEmpty()) {
        System.err.printf(
            "Error: Unrecognized day \"%s\". Accepted: %s.%n", dayStr, DayParser.hint());
        return 1;
      }
      try {
        League league = parent.configCmd.app.store.load();
        LeagueConfig config = (league.config() != null) ? league.config() : LeagueConfig.empty();
        boolean exists = config.dowWindows().stream().anyMatch(w -> w.day() == day.get());
        if (!exists) {
          System.err.printf(
              "Error: No day-of-week window configured for %s.%n",
              DayParser.displayName(day.get()));
          return 1;
        }
        LeagueConfig updated = config.withDowWindowRemoved(day.get());
        parent.configCmd.app.store.save(league.withConfig(updated));
        System.out.printf("Day-of-week window for %s removed.%n", DayParser.displayName(day.get()));
        return 0;
      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }
  }

  // --- List ---

  @Command(name = "list", description = "List all day-of-week availability windows.")
  static class ListCmd implements Callable<Integer> {

    @ParentCommand ConfigDowCommand parent;

    @Override
    public Integer call() {
      try {
        League league = parent.configCmd.app.store.load();
        LeagueConfig config = (league.config() != null) ? league.config() : LeagueConfig.empty();
        if (config.dowWindows().isEmpty()) {
          System.out.println(
              "No day-of-week windows configured. Use 'planr config dow set' to add one.");
          return 0;
        }
        printTable(config);
        return 0;
      } catch (IOException e) {
        System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
        return 2;
      }
    }

    private void printTable(LeagueConfig config) {
      String fmt = "%-11s  %-5s  %-5s%n";
      System.out.printf(fmt, "DAY", "OPEN", "CLOSE");
      System.out.printf(fmt, "-----------", "-----", "-----");
      config.dowWindows().stream()
          .sorted(Comparator.comparingInt(w -> w.day().getValue()))
          .forEach(
              w ->
                  System.out.printf(
                      fmt,
                      DayParser.displayName(w.day()),
                      w.openStart().format(TIME_FORMAT),
                      w.openEnd().format(TIME_FORMAT)));
    }
  }
}
