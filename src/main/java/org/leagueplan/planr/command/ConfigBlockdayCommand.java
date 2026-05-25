package org.leagueplan.planr.command;

import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.LeagueConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
    name = "blockday",
    description = "Manage blocked days of week for all fields.",
    subcommands = {
        ConfigBlockdayCommand.AddCmd.class,
        ConfigBlockdayCommand.RemoveCmd.class,
        ConfigBlockdayCommand.ListCmd.class
    },
    mixinStandardHelpOptions = true
)
public class ConfigBlockdayCommand implements Runnable {

    @ParentCommand ConfigCommand configCmd;
    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    // --- Add ---

    @Command(name = "add", description = "Mark a day of the week as unavailable for all fields.")
    static class AddCmd implements Callable<Integer> {

        @ParentCommand ConfigBlockdayCommand parent;

        @Option(names = "--day", required = true, paramLabel = "<DAY>",
                description = "Day of week to block (e.g. sunday, sun).")
        String dayStr;

        @Override
        public Integer call() {
            Optional<DayOfWeek> day = DayParser.parse(dayStr);
            if (day.isEmpty()) {
                System.err.printf("Error: Unrecognized day \"%s\". Accepted: %s.%n",
                    dayStr, DayParser.hint());
                return 1;
            }
            try {
                League league = parent.configCmd.app.store.load();
                LeagueConfig config = (league.config() != null)
                    ? league.config() : LeagueConfig.empty();
                if (config.blockedDays().contains(day.get())) {
                    System.err.printf("Error: %s is already a blocked day.%n",
                        DayParser.displayName(day.get()));
                    return 1;
                }
                LeagueConfig updated = config.withBlockedDayAdded(day.get());
                parent.configCmd.app.store.save(league.withConfig(updated));
                System.out.printf("%s added to blocked days.%n",
                    DayParser.displayName(day.get()));
                int conflicts = ConfigDowCommand.countConflicts(league, day.get());
                if (conflicts > 0) {
                    System.out.printf(
                        "Warning: %d field-level %s exist on %ss within the season. "
                        + "FieldDateOverride entries on those specific dates will still "
                        + "take precedence over this block.%n",
                        conflicts,
                        conflicts == 1 ? "entry" : "entries",
                        DayParser.displayName(day.get()));
                }
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- Remove ---

    @Command(name = "remove", description = "Unblock a previously blocked day of the week.")
    static class RemoveCmd implements Callable<Integer> {

        @ParentCommand ConfigBlockdayCommand parent;

        @Option(names = "--day", required = true, paramLabel = "<DAY>",
                description = "Day of week to unblock (e.g. sunday, sun).")
        String dayStr;

        @Override
        public Integer call() {
            Optional<DayOfWeek> day = DayParser.parse(dayStr);
            if (day.isEmpty()) {
                System.err.printf("Error: Unrecognized day \"%s\". Accepted: %s.%n",
                    dayStr, DayParser.hint());
                return 1;
            }
            try {
                League league = parent.configCmd.app.store.load();
                LeagueConfig config = (league.config() != null)
                    ? league.config() : LeagueConfig.empty();
                if (!config.blockedDays().contains(day.get())) {
                    System.err.printf("Error: %s is not a blocked day.%n",
                        DayParser.displayName(day.get()));
                    return 1;
                }
                LeagueConfig updated = config.withBlockedDayRemoved(day.get());
                parent.configCmd.app.store.save(league.withConfig(updated));
                System.out.printf("%s removed from blocked days.%n",
                    DayParser.displayName(day.get()));
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }

    // --- List ---

    @Command(name = "list", description = "List all blocked days of the week.")
    static class ListCmd implements Callable<Integer> {

        @ParentCommand ConfigBlockdayCommand parent;

        @Override
        public Integer call() {
            try {
                League league = parent.configCmd.app.store.load();
                LeagueConfig config = (league.config() != null)
                    ? league.config() : LeagueConfig.empty();
                if (config.blockedDays().isEmpty()) {
                    System.out.println(
                        "No days of the week are blocked. "
                        + "Use 'planr config blockday add' to block one.");
                    return 0;
                }
                System.out.println("Blocked days of week:");
                config.blockedDays().stream()
                    .sorted(Comparator.comparingInt(DayOfWeek::getValue))
                    .forEach(d -> System.out.printf("  %s%n", DayParser.displayName(d)));
                return 0;
            } catch (IOException e) {
                System.err.printf("Error: Failed to access league data: %s%n", e.getMessage());
                return 2;
            }
        }
    }
}
