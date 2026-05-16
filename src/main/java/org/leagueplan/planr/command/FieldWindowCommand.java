package org.leagueplan.planr.command;

import org.leagueplan.planr.model.AvailabilityWindow;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
    name = "window",
    description = "Manage availability windows for a field.",
    subcommands = {
        FieldWindowCommand.AddCmd.class,
        FieldWindowCommand.EditCmd.class,
        FieldWindowCommand.DeleteCmd.class,
        FieldWindowCommand.ListCmd.class
    },
    mixinStandardHelpOptions = true
)
public class FieldWindowCommand implements Runnable {

    @ParentCommand FieldCommand fieldCmd;
    @Spec CommandSpec spec;

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    static Optional<DayOfWeek> parseDayOfWeek(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        String normalized = input.trim().toUpperCase();
        try {
            return Optional.of(DayOfWeek.valueOf(normalized));
        } catch (IllegalArgumentException ignored) {}
        if (normalized.length() == 3) {
            for (DayOfWeek day : DayOfWeek.values()) {
                if (day.name().startsWith(normalized)) {
                    return Optional.of(day);
                }
            }
        }
        return Optional.empty();
    }

    static Optional<LocalTime> parseTime(String input) {
        if (input == null) return Optional.empty();
        try {
            return Optional.of(LocalTime.parse(input.trim(), TIME_FORMAT));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    static String formatDay(DayOfWeek day) {
        return day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    static String resolveDivisionLabel(League league, UUID divisionId) {
        if (divisionId == null) return "All divisions";
        return league.divisions().stream()
            .filter(d -> d.id().equals(divisionId))
            .findFirst()
            .map(Division::name)
            .orElse("[deleted]");
    }

    static void printInvalidWindowError(Field field, int windowNumber) {
        if (field.windows().isEmpty()) {
            System.err.printf("Error: Window #%d not found for field \"%s\" (no windows exist).%n",
                windowNumber, field.name());
        } else {
            System.err.printf("Error: Window #%d not found for field \"%s\" (1-%d are valid).%n",
                windowNumber, field.name(), field.windows().size());
        }
    }

    // --- Add ---

    @Command(name = "add", description = "Add an availability window to a field.")
    static class AddCmd implements Callable<Integer> {

        @ParentCommand FieldWindowCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Option(names = "--day", required = true, paramLabel = "<day>",
                description = "Day of week (e.g., Monday, Mon).")
        String dayStr;

        @Option(names = "--start", required = true, paramLabel = "<HH:mm>",
                description = "Start time in 24-hour format.")
        String startStr;

        @Option(names = "--end", required = true, paramLabel = "<HH:mm>",
                description = "End time in 24-hour format.")
        String endStr;

        @Option(names = "--division", paramLabel = "<division>",
                description = "Restrict window to this division only.")
        String divisionName;

        @Override
        public Integer call() {
            Optional<DayOfWeek> day = parseDayOfWeek(dayStr);
            if (day.isEmpty()) {
                System.err.printf(
                    "Error: Invalid day \"%s\". Use a day name such as Monday, Tuesday, ..., Sunday.%n",
                    dayStr);
                return 1;
            }
            Optional<LocalTime> start = parseTime(startStr);
            if (start.isEmpty()) {
                System.err.printf("Error: Invalid time \"%s\". Use HH:mm format (e.g., 09:00).%n", startStr);
                return 1;
            }
            Optional<LocalTime> end = parseTime(endStr);
            if (end.isEmpty()) {
                System.err.printf("Error: Invalid time \"%s\". Use HH:mm format (e.g., 17:00).%n", endStr);
                return 1;
            }
            if (!end.get().isAfter(start.get())) {
                System.err.println("Error: End time must be after start time.");
                return 1;
            }
            try {
                League league = parent.fieldCmd.app.store.load();
                Optional<Field> existing = league.findField(fieldName);
                if (existing.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", fieldName);
                    return 1;
                }
                UUID divisionId = null;
                if (divisionName != null) {
                    Optional<Division> div = league.findDivision(divisionName);
                    if (div.isEmpty()) {
                        System.err.printf("Error: Division \"%s\" not found.%n", divisionName);
                        return 1;
                    }
                    divisionId = div.get().id();
                }
                AvailabilityWindow window = new AvailabilityWindow(
                    UUID.randomUUID(), day.get(), start.get(), end.get(), divisionId);
                Field updated = existing.get().withWindowAdded(window);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(existing.get().id(), updated));
                int windowNumber = updated.windows().size();
                String divLabel = (divisionName != null) ? divisionName + " only" : "all divisions";
                System.out.printf(
                    "Availability window #%d added to field \"%s\" (%s %s-%s, %s).%n",
                    windowNumber, existing.get().name(),
                    formatDay(day.get()),
                    start.get().format(TIME_FORMAT), end.get().format(TIME_FORMAT),
                    divLabel);
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Edit ---

    @Command(name = "edit", description = "Edit an availability window.")
    static class EditCmd implements Callable<Integer> {

        @ParentCommand FieldWindowCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Parameters(index = "1", paramLabel = "<window-number>", description = "1-based window number from 'planr field window list'.")
        int windowNumber;

        @Option(names = "--day", paramLabel = "<day>", description = "New day of week.")
        String newDayStr;

        @Option(names = "--start", paramLabel = "<HH:mm>", description = "New start time.")
        String newStartStr;

        @Option(names = "--end", paramLabel = "<HH:mm>", description = "New end time.")
        String newEndStr;

        @Option(names = "--division", paramLabel = "<division>", description = "Restrict to this division.")
        String newDivisionName;

        @Option(names = "--clear-division", description = "Remove division restriction (make unrestricted).")
        boolean clearDivision;

        @Override
        public Integer call() {
            if (newDayStr == null && newStartStr == null && newEndStr == null
                    && newDivisionName == null && !clearDivision) {
                System.err.println(
                    "Error: At least one of --day, --start, --end, --division, or --clear-division must be provided.");
                return 1;
            }
            if (newDivisionName != null && clearDivision) {
                System.err.println("Error: --division and --clear-division cannot be used together.");
                return 1;
            }

            // Parse any supplied time/day values before touching the store.
            Optional<DayOfWeek> newDay = Optional.empty();
            if (newDayStr != null) {
                newDay = parseDayOfWeek(newDayStr);
                if (newDay.isEmpty()) {
                    System.err.printf(
                        "Error: Invalid day \"%s\". Use a day name such as Monday, Tuesday, ..., Sunday.%n",
                        newDayStr);
                    return 1;
                }
            }
            Optional<LocalTime> newStart = Optional.empty();
            if (newStartStr != null) {
                newStart = parseTime(newStartStr);
                if (newStart.isEmpty()) {
                    System.err.printf("Error: Invalid time \"%s\". Use HH:mm format (e.g., 09:00).%n", newStartStr);
                    return 1;
                }
            }
            Optional<LocalTime> newEnd = Optional.empty();
            if (newEndStr != null) {
                newEnd = parseTime(newEndStr);
                if (newEnd.isEmpty()) {
                    System.err.printf("Error: Invalid time \"%s\". Use HH:mm format (e.g., 17:00).%n", newEndStr);
                    return 1;
                }
            }

            try {
                League league = parent.fieldCmd.app.store.load();
                Optional<Field> fieldOpt = league.findField(fieldName);
                if (fieldOpt.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", fieldName);
                    return 1;
                }
                Field field = fieldOpt.get();
                int zeroBasedIndex = windowNumber - 1;
                if (zeroBasedIndex < 0 || zeroBasedIndex >= field.windows().size()) {
                    printInvalidWindowError(field, windowNumber);
                    return 1;
                }
                AvailabilityWindow existing = field.windows().get(zeroBasedIndex);

                // Effective values: supplied option wins, otherwise keep existing.
                LocalTime effectiveStart = newStart.orElse(existing.startTime());
                LocalTime effectiveEnd = newEnd.orElse(existing.endTime());
                if (!effectiveEnd.isAfter(effectiveStart)) {
                    System.err.println("Error: End time must be after start time.");
                    return 1;
                }

                UUID resolvedDivisionId;
                if (clearDivision) {
                    resolvedDivisionId = null;
                } else if (newDivisionName != null) {
                    Optional<Division> div = league.findDivision(newDivisionName);
                    if (div.isEmpty()) {
                        System.err.printf("Error: Division \"%s\" not found.%n", newDivisionName);
                        return 1;
                    }
                    resolvedDivisionId = div.get().id();
                } else {
                    resolvedDivisionId = existing.divisionId();
                }

                AvailabilityWindow updated = new AvailabilityWindow(
                    existing.id(),
                    newDay.orElse(existing.dayOfWeek()),
                    effectiveStart,
                    effectiveEnd,
                    resolvedDivisionId);
                Field updatedField = field.withWindowReplaced(zeroBasedIndex, updated);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(field.id(), updatedField));
                System.out.printf("Availability window #%d on \"%s\" updated.%n",
                    windowNumber, field.name());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Delete ---

    @Command(name = "delete", description = "Delete an availability window.")
    static class DeleteCmd implements Callable<Integer> {

        @ParentCommand FieldWindowCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Parameters(index = "1", paramLabel = "<window-number>", description = "1-based window number from 'planr field window list'.")
        int windowNumber;

        @Override
        public Integer call() {
            try {
                League league = parent.fieldCmd.app.store.load();
                Optional<Field> fieldOpt = league.findField(fieldName);
                if (fieldOpt.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", fieldName);
                    return 1;
                }
                Field field = fieldOpt.get();
                int zeroBasedIndex = windowNumber - 1;
                if (zeroBasedIndex < 0 || zeroBasedIndex >= field.windows().size()) {
                    printInvalidWindowError(field, windowNumber);
                    return 1;
                }
                Field updated = field.withWindowRemoved(zeroBasedIndex);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(field.id(), updated));
                System.out.printf("Availability window #%d on \"%s\" deleted.%n",
                    windowNumber, field.name());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- List ---

    @Command(name = "list", description = "List all availability windows for a field.")
    static class ListCmd implements Callable<Integer> {

        @ParentCommand FieldWindowCommand parent;

        @Parameters(index = "0", paramLabel = "<field>", description = "Field name.")
        String fieldName;

        @Override
        public Integer call() {
            try {
                League league = parent.fieldCmd.app.store.load();
                Optional<Field> fieldOpt = league.findField(fieldName);
                if (fieldOpt.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", fieldName);
                    return 1;
                }
                Field field = fieldOpt.get();
                if (field.windows().isEmpty()) {
                    System.out.printf(
                        "No availability windows for field \"%s\". Use 'planr field window add' to create one.%n",
                        field.name());
                    return 0;
                }
                printTable(league, field);
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }

        private void printTable(League league, Field field) {
            int divWidth = Math.max("DIVISION".length(),
                field.windows().stream()
                    .mapToInt(w -> resolveDivisionLabel(league, w.divisionId()).length())
                    .max().orElse(0));

            String fmt = "%-3s  %-9s  %-5s  %-5s  %-" + divWidth + "s%n";
            System.out.printf(fmt, "#", "DAY", "START", "END", "DIVISION");
            System.out.printf(fmt, "-", "---------", "-----", "-----", "-".repeat(divWidth));

            int orphanCount = 0;
            for (int i = 0; i < field.windows().size(); i++) {
                AvailabilityWindow w = field.windows().get(i);
                String divLabel = resolveDivisionLabel(league, w.divisionId());
                if ("[deleted]".equals(divLabel)) orphanCount++;
                System.out.printf(fmt,
                    i + 1,
                    formatDay(w.dayOfWeek()),
                    w.startTime().format(TIME_FORMAT),
                    w.endTime().format(TIME_FORMAT),
                    divLabel);
            }

            if (orphanCount > 0) {
                System.out.printf("%nWarning: %d window(s) reference deleted divisions."
                    + " Update or remove them before generating a schedule.%n", orphanCount);
            }
        }
    }
}
