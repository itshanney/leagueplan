package org.leagueplan.planr.command;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.leagueplan.planr.PlanrApp;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.Playoff;
import org.leagueplan.planr.model.PlayoffGame;
import org.leagueplan.planr.model.PracticeSchedule;
import org.leagueplan.planr.model.PracticeSlot;
import org.leagueplan.planr.model.Team;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(
    name = "calendar",
    description =
        "View a unified weekly or monthly calendar of all assigned games, playoffs, and practices.",
    mixinStandardHelpOptions = true)
public class CalendarCommand implements Callable<Integer> {

  @ParentCommand PlanrApp app;

  @Option(names = "--weekly", description = "Show a weekly calendar view (default).")
  boolean weekly;

  @Option(names = "--monthly", description = "Show a monthly calendar view.")
  boolean monthly;

  @Option(
      names = "--division",
      paramLabel = "<name>",
      description = "Filter events to a single division.")
  String divisionFilter;

  @Option(
      names = "--team",
      paramLabel = "<name>",
      description =
          "Filter events to a single team. Playoff events are excluded from team-filtered"
              + " results because bracket positions cannot be matched to team names before the"
              + " bracket resolves; use --division to include playoffs.")
  String teamFilter;

  @Option(
      names = "--field",
      paramLabel = "<name>",
      description = "Filter events to a single field.")
  String fieldFilter;

  @Option(
      names = "--week",
      paramLabel = "<YYYY-MM-DD>",
      description =
          "Any date within the target week (weekly mode only). Defaults to the earliest week"
              + " with at least one assigned event.")
  LocalDate weekDate;

  @Option(
      names = "--month",
      paramLabel = "<YYYY-MM>",
      description =
          "Target month in YYYY-MM format (monthly mode only). Defaults to the earliest month"
              + " with at least one assigned event.")
  String monthArg;

  // Package-private so CalendarRenderer can reference these types.
  record CalendarEvent(
      LocalDate date,
      LocalTime time,
      EventType type,
      String description,
      String fieldName,
      String divisionName,
      List<String> filterTeams) {}

  enum EventType {
    GAME,
    PLAYOFF,
    PRACTICE
  }

  @Override
  public Integer call() {
    if (weekly && monthly) {
      System.err.println("Error: --weekly and --monthly are mutually exclusive.");
      return 1;
    }
    boolean isMonthly = monthly;

    int filterCount =
        (divisionFilter != null ? 1 : 0)
            + (teamFilter != null ? 1 : 0)
            + (fieldFilter != null ? 1 : 0);
    if (filterCount > 1) {
      System.err.println(
          "Error: At most one of --division, --team, --field may be specified.");
      return 1;
    }

    if (weekDate != null && isMonthly) {
      System.err.println("Error: --week cannot be used with --monthly.");
      return 1;
    }
    if (monthArg != null && !isMonthly) {
      System.err.println("Error: --month can only be used with --monthly.");
      return 1;
    }

    YearMonth targetMonth = null;
    if (monthArg != null) {
      try {
        targetMonth = YearMonth.parse(monthArg);
      } catch (Exception e) {
        System.err.printf(
            "Error: Invalid month \"%s\". Expected YYYY-MM format (e.g. 2026-05).%n", monthArg);
        return 1;
      }
    }

    try {
      League league = app.store.load();

      if (divisionFilter != null && league.findDivision(divisionFilter).isEmpty()) {
        System.err.printf("Error: Division \"%s\" not found.%n", divisionFilter);
        return 1;
      }
      if (teamFilter != null) {
        boolean teamExists =
            league.divisions().stream()
                .flatMap(d -> d.teams().stream())
                .anyMatch(t -> t.name().equalsIgnoreCase(teamFilter));
        if (!teamExists) {
          System.err.printf("Error: Team \"%s\" not found.%n", teamFilter);
          return 1;
        }
      }
      if (fieldFilter != null && league.findField(fieldFilter).isEmpty()) {
        System.err.printf("Error: Field \"%s\" not found.%n", fieldFilter);
        return 1;
      }

      List<CalendarEvent> events = applyFilter(collectEvents(league));

      if (events.isEmpty()) {
        System.err.println(
            "No assigned events found. Run schedule/playoff/practice assign first.");
        return 1;
      }

      if (isMonthly) {
        YearMonth ym =
            targetMonth != null
                ? targetMonth
                : YearMonth.from(
                    events.stream().map(CalendarEvent::date).min(LocalDate::compareTo).orElseThrow());
        List<CalendarEvent> window =
            events.stream().filter(e -> YearMonth.from(e.date()).equals(ym)).toList();
        CalendarRenderer.renderMonthly(window, ym, System.out);
      } else {
        LocalDate anchor = weekDate != null
            ? weekDate
            : events.stream().map(CalendarEvent::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate weekStart = toWeekStart(anchor);
        LocalDate weekEnd = weekStart.plusDays(6);
        List<CalendarEvent> window =
            events.stream()
                .filter(e -> !e.date().isBefore(weekStart) && !e.date().isAfter(weekEnd))
                .toList();
        CalendarRenderer.renderWeekly(window, weekStart, System.out);
      }

      return 0;
    } catch (IOException e) {
      System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
      return 2;
    }
  }

  private List<CalendarEvent> collectEvents(League league) {
    List<CalendarEvent> events = new ArrayList<>();
    events.addAll(collectGames(league));
    events.addAll(collectPlayoffGames(league));
    events.addAll(collectPracticeSlots(league));
    return events;
  }

  private static List<CalendarEvent> collectGames(League league) {
    if (league.schedule() == null) return List.of();
    return league.schedule().games().stream()
        .filter(g -> g.date() != null)
        .map(
            g ->
                new CalendarEvent(
                    g.date(),
                    g.startTime(),
                    EventType.GAME,
                    g.homeTeamName() + " vs " + g.awayTeamName(),
                    g.fieldName(),
                    g.divisionName(),
                    List.of(g.homeTeamName().toLowerCase(), g.awayTeamName().toLowerCase())))
        .toList();
  }

  private static List<CalendarEvent> collectPlayoffGames(League league) {
    List<CalendarEvent> events = new ArrayList<>();
    for (Playoff playoff : league.playoffs()) {
      Optional<Division> divOpt =
          league.divisions().stream()
              .filter(d -> d.id().equals(playoff.divisionId()))
              .findFirst();
      String divisionName = divOpt.map(Division::name).orElse("Unknown");
      for (PlayoffGame pg : playoff.games()) {
        if (pg.assignedDate() == null) continue;
        events.add(
            new CalendarEvent(
                pg.assignedDate(),
                pg.assignedStartTime(),
                EventType.PLAYOFF,
                pg.positionA() + " vs " + pg.positionB(),
                resolveFieldName(league, pg.assignedFieldId()),
                divisionName,
                List.of()));
      }
    }
    return events;
  }

  private static List<CalendarEvent> collectPracticeSlots(League league) {
    List<CalendarEvent> events = new ArrayList<>();
    for (PracticeSchedule ps : league.practiceSchedules()) {
      Optional<Division> divOpt =
          league.divisions().stream()
              .filter(d -> d.id().equals(ps.divisionId()))
              .findFirst();
      String divisionName = divOpt.map(Division::name).orElse("Unknown");
      for (PracticeSlot slot : ps.slots()) {
        if (slot.assignedDate() == null) continue;
        String teamName = resolveTeamName(league, slot.teamId());
        events.add(
            new CalendarEvent(
                slot.assignedDate(),
                slot.assignedStartTime(),
                EventType.PRACTICE,
                teamName,
                resolveFieldName(league, slot.assignedFieldId()),
                divisionName,
                List.of(teamName.toLowerCase())));
      }
    }
    return events;
  }

  private List<CalendarEvent> applyFilter(List<CalendarEvent> events) {
    if (divisionFilter != null) {
      return events.stream()
          .filter(e -> e.divisionName().equalsIgnoreCase(divisionFilter))
          .toList();
    }
    if (teamFilter != null) {
      String lower = teamFilter.toLowerCase();
      return events.stream().filter(e -> e.filterTeams().contains(lower)).toList();
    }
    if (fieldFilter != null) {
      return events.stream()
          .filter(e -> e.fieldName().equalsIgnoreCase(fieldFilter))
          .toList();
    }
    return events;
  }

  // Returns the Sunday on or before the given date (ISO DayOfWeek: Mon=1 ... Sun=7).
  // Sunday-first: subtract (dayOfWeek % 7) days — Sun(7%7=0), Mon(1), ..., Sat(6).
  static LocalDate toWeekStart(LocalDate date) {
    int dow = date.getDayOfWeek().getValue();
    return date.minusDays(dow % 7);
  }

  static String resolveTeamName(League league, UUID teamId) {
    return league.divisions().stream()
        .flatMap(d -> d.teams().stream())
        .filter(t -> t.id().equals(teamId))
        .findFirst()
        .map(Team::name)
        .orElse("Unknown");
  }

  static String resolveFieldName(League league, UUID fieldId) {
    if (fieldId == null) return "Unknown";
    return league.fields().stream()
        .filter(f -> f.id().equals(fieldId))
        .findFirst()
        .map(Field::name)
        .orElse("Unknown");
  }
}
