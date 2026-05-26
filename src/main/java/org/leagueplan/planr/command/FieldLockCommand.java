package org.leagueplan.planr.command;

import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.FieldDivisionLock;
import org.leagueplan.planr.model.League;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(
    name = "lock",
    description = "Manage field division locks (restrict a field to one division for a date range).",
    subcommands = {
        FieldLockCommand.AddCmd.class,
        FieldLockCommand.DeleteCmd.class,
        FieldLockCommand.ListCmd.class
    },
    mixinStandardHelpOptions = true
)
public class FieldLockCommand implements Runnable {

    @ParentCommand FieldCommand fieldCmd;
    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    private static Optional<LocalDate> parseDate(String input) {
        if (input == null) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(input.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    // --- Add ---

    @Command(name = "add", description = "Lock a field to a division for a date range.")
    static class AddCmd implements Callable<Integer> {

        @ParentCommand FieldLockCommand parent;

        @Option(names = "--field", required = true, paramLabel = "<name>",
                description = "Field name.")
        String fieldName;

        @Option(names = "--division", required = true, paramLabel = "<name>",
                description = "Division name.")
        String divisionName;

        @Option(names = "--start", required = true, paramLabel = "<YYYY-MM-DD>",
                description = "Lock start date (inclusive).")
        String startStr;

        @Option(names = "--end", required = true, paramLabel = "<YYYY-MM-DD>",
                description = "Lock end date (inclusive).")
        String endStr;

        @Override
        public Integer call() {
            Optional<LocalDate> start = parseDate(startStr);
            if (start.isEmpty()) {
                System.err.printf("Error: Invalid date \"%s\". Expected YYYY-MM-DD.%n", startStr);
                return 1;
            }
            Optional<LocalDate> end = parseDate(endStr);
            if (end.isEmpty()) {
                System.err.printf("Error: Invalid date \"%s\". Expected YYYY-MM-DD.%n", endStr);
                return 1;
            }
            if (end.get().isBefore(start.get())) {
                System.err.printf(
                    "Error: End date %s must not be before start date %s.%n",
                    end.get(), start.get());
                return 1;
            }
            try {
                League league = parent.fieldCmd.app.store.load();

                Optional<Field> fieldOpt = league.findField(fieldName);
                if (fieldOpt.isEmpty()) {
                    System.err.printf("Error: Field \"%s\" not found.%n", fieldName);
                    return 1;
                }

                Optional<Division> divisionOpt = league.divisions().stream()
                    .filter(d -> d.name().equalsIgnoreCase(divisionName))
                    .findFirst();
                if (divisionOpt.isEmpty()) {
                    System.err.printf("Error: Division \"%s\" not found.%n", divisionName);
                    return 1;
                }

                Field field = fieldOpt.get();
                LocalDate newStart = start.get();
                LocalDate newEnd = end.get();

                List<Integer> conflicts = new ArrayList<>();
                for (int i = 0; i < field.divisionLocks().size(); i++) {
                    FieldDivisionLock existing = field.divisionLocks().get(i);
                    if (!newStart.isAfter(existing.endDate()) && !newEnd.isBefore(existing.startDate())) {
                        conflicts.add(i + 1);
                    }
                }
                if (!conflicts.isEmpty()) {
                    String indices = conflicts.stream()
                        .map(n -> "#" + n)
                        .collect(Collectors.joining(", "));
                    System.err.printf(
                        "Error: Lock date range overlaps with existing lock(s): %s. "
                        + "Use 'planr field lock list' to review.%n", indices);
                    return 1;
                }

                FieldDivisionLock lock = new FieldDivisionLock(divisionOpt.get().id(), newStart, newEnd);
                Field updated = field.withLockAdded(lock);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(field.id(), updated));

                int lockNumber = updated.divisionLocks().size();
                System.out.printf(
                    "Division lock #%d added: \"%s\" locked to \"%s\" from %s to %s.%n",
                    lockNumber, field.name(), divisionOpt.get().name(), newStart, newEnd);
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Delete ---

    @Command(name = "delete", description = "Delete a field division lock.")
    static class DeleteCmd implements Callable<Integer> {

        @ParentCommand FieldLockCommand parent;

        @Option(names = "--field", required = true, paramLabel = "<name>",
                description = "Field name.")
        String fieldName;

        @Option(names = "--index", required = true, paramLabel = "<N>",
                description = "1-based lock index from 'planr field lock list'.")
        int lockNumber;

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

                if (field.divisionLocks().isEmpty()) {
                    System.err.printf("Error: No locks exist for \"%s\".%n", field.name());
                    return 1;
                }
                int idx = lockNumber - 1;
                if (idx < 0 || idx >= field.divisionLocks().size()) {
                    System.err.printf(
                        "Error: Lock #%d not found for \"%s\" (1–%d are valid).%n",
                        lockNumber, field.name(), field.divisionLocks().size());
                    return 1;
                }

                FieldDivisionLock lock = field.divisionLocks().get(idx);
                String resolvedDivisionName = resolveDivisionName(league, lock.divisionId());

                Field updated = field.withLockRemoved(idx);
                parent.fieldCmd.app.store.save(league.withFieldReplaced(field.id(), updated));
                System.out.printf(
                    "Division lock #%d deleted (\"%s\" / \"%s\", %s to %s).%n",
                    lockNumber, field.name(), resolvedDivisionName, lock.startDate(), lock.endDate());
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- List ---

    @Command(name = "list", description = "List field division locks.")
    static class ListCmd implements Callable<Integer> {

        @ParentCommand FieldLockCommand parent;

        @Option(names = "--field", paramLabel = "<name>",
                description = "Filter to a single field (optional).")
        String fieldFilter;

        @Override
        public Integer call() {
            try {
                League league = parent.fieldCmd.app.store.load();

                List<Field> fields;
                if (fieldFilter != null) {
                    Optional<Field> fieldOpt = league.findField(fieldFilter);
                    if (fieldOpt.isEmpty()) {
                        System.err.printf("Error: Field \"%s\" not found.%n", fieldFilter);
                        return 1;
                    }
                    fields = List.of(fieldOpt.get());
                } else {
                    fields = league.fields();
                }

                record LockRow(String fieldName, int lockNumber, String divisionName,
                               LocalDate startDate, LocalDate endDate) {}

                List<LockRow> rows = new ArrayList<>();
                for (Field field : fields) {
                    for (int i = 0; i < field.divisionLocks().size(); i++) {
                        FieldDivisionLock lock = field.divisionLocks().get(i);
                        rows.add(new LockRow(
                            field.name(),
                            i + 1,
                            resolveDivisionName(league, lock.divisionId()),
                            lock.startDate(),
                            lock.endDate()));
                    }
                }

                rows.sort(Comparator.comparing(LockRow::fieldName)
                    .thenComparing(LockRow::startDate));

                if (rows.isEmpty()) {
                    if (fieldFilter != null) {
                        System.out.printf(
                            "No locks for \"%s\". Use 'planr field lock add' to create one.%n",
                            fields.get(0).name());
                    } else {
                        System.out.println(
                            "No division locks configured. Use 'planr field lock add' to create one.");
                    }
                    return 0;
                }

                int fieldW = Math.max("FIELD".length(),
                    rows.stream().mapToInt(r -> r.fieldName().length()).max().orElse(0));
                int numW = Math.max(1,
                    rows.stream().mapToInt(r -> String.valueOf(r.lockNumber()).length()).max().orElse(0));
                int divW = Math.max("DIVISION".length(),
                    rows.stream().mapToInt(r -> r.divisionName().length()).max().orElse(0));
                int dateW = 10; // YYYY-MM-DD

                String fmt = "%-" + fieldW + "s  %-" + numW + "s  %-" + divW + "s  %-"
                    + dateW + "s  %-" + dateW + "s%n";
                System.out.printf(fmt, "FIELD", "#", "DIVISION", "START", "END");
                System.out.printf(fmt, "-".repeat(fieldW), "-".repeat(numW), "-".repeat(divW),
                    "-".repeat(dateW), "-".repeat(dateW));
                for (LockRow r : rows) {
                    System.out.printf(fmt,
                        r.fieldName(), r.lockNumber(), r.divisionName(), r.startDate(), r.endDate());
                }
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    static String resolveDivisionName(League league, UUID divisionId) {
        return league.divisions().stream()
            .filter(d -> d.id().equals(divisionId))
            .findFirst()
            .map(Division::name)
            .orElse("[unknown]");
    }
}
