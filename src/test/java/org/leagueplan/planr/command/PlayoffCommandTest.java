package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end command tests for `planr playoff`.
 *
 * Excludes `playoff assign` (requires the CP-SAT solver and stdin interaction; covered separately
 * in manual/integration testing). All tests use 2-4 teams to keep bracket generation fast.
 *
 * Test data:
 *   Division "Majors" with 2 teams: Blue Jays, Cardinals
 *   Division "Minors" with 3 teams: Red Sox, Yankees, Cubs
 *   Division "AAA" with 4 teams: Dodgers, Mets, Braves, Phillies
 */
class PlayoffCommandTest extends CommandTestBase {

    // ---------------------------------------------------------------------------
    // Setup helpers
    // ---------------------------------------------------------------------------

    private void addDivisionWithTeams(String division, int duration, String... teams) {
        execute("division", "add", division, "--duration", String.valueOf(duration), "--target", "4");
        for (String team : teams) {
            execute("team", "add", division, team);
        }
    }

    private int generatePlayoff(String division, String start, String end, String... seeds) {
        List<String> args = new ArrayList<>();
        args.addAll(List.of("playoff", "generate",
            "--division", division, "--start", start, "--end", end));
        for (String seed : seeds) {
            args.add("--seeds");
            args.add(seed);
        }
        return execute(args.toArray(new String[0]));
    }

    private int clearPlayoff(String division, String stdinResponse) {
        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream(stdinResponse.getBytes()));
        try {
            return execute("playoff", "clear", "--division", division);
        } finally {
            System.setIn(original);
        }
    }

    // ---------------------------------------------------------------------------
    // playoff generate
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("playoff generate")
    class Generate {

        @Test
        @DisplayName("exits 0 and prints bracket summary for a valid 2-team bracket")
        void successTwoTeams() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            int exit = generatePlayoff("Majors", "2026-06-14", "2026-06-28",
                "Blue Jays", "Cardinals");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Playoff generated for Majors"));
            assertTrue(stdout().contains("2 teams"));
        }

        @Test
        @DisplayName("exits 0 and prints correct bye count for a 3-team bracket (1 bye)")
        void successThreeTeams() {
            addDivisionWithTeams("Minors", 60, "Red Sox", "Yankees", "Cubs");
            int exit = generatePlayoff("Minors", "2026-06-14", "2026-06-28",
                "Red Sox", "Yankees", "Cubs");
            assertEquals(0, exit);
            assertTrue(stdout().contains("1 bye(s)"));
        }

        @Test
        @DisplayName("exits 0 and prints 0 byes for a 4-team bracket")
        void successFourTeams() {
            addDivisionWithTeams("AAA", 60, "Dodgers", "Mets", "Braves", "Phillies");
            int exit = generatePlayoff("AAA", "2026-06-14", "2026-06-28",
                "Dodgers", "Mets", "Braves", "Phillies");
            assertEquals(0, exit);
            assertTrue(stdout().contains("0 bye(s)"));
        }

        @Test
        @DisplayName("bracket summary table includes team names in seed positions")
        void bracketTableContainsTeamNames() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28",
                "Blue Jays", "Cardinals");
            assertTrue(stdout().contains("Blue Jays"));
            assertTrue(stdout().contains("Cardinals"));
        }

        @Test
        @DisplayName("seed matching is case-insensitive")
        void seedMatchingIsCaseInsensitive() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            int exit = generatePlayoff("Majors", "2026-06-14", "2026-06-28",
                "blue jays", "cardinals");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("persists playoff so subsequent status shows GENERATED state")
        void persistsAcrossInvocations() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            execute("playoff", "status");
            assertTrue(stdout().contains("GENERATED"));
        }

        @Test
        @DisplayName("exits 1 when division does not exist")
        void failsWhenDivisionNotFound() {
            int exit = generatePlayoff("NonExistent", "2026-06-14", "2026-06-28",
                "TeamA", "TeamB");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when end date is before start date")
        void failsWhenEndBeforeStart() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            int exit = generatePlayoff("Majors", "2026-06-28", "2026-06-14",
                "Blue Jays", "Cardinals");
            assertEquals(1, exit);
            assertTrue(stderr().contains("must not be before"));
        }

        @Test
        @DisplayName("exits 1 when start date is invalid")
        void failsOnInvalidStartDate() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            int exit = generatePlayoff("Majors", "not-a-date", "2026-06-28",
                "Blue Jays", "Cardinals");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Invalid date"));
        }

        @Test
        @DisplayName("exits 1 when seed count does not match team count")
        void failsWhenSeedCountMismatch() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            // 3 seeds for a 2-team division triggers the mismatch error (not the < 2 guard)
            int exit = generatePlayoff("Majors", "2026-06-14", "2026-06-28",
                "Blue Jays", "Cardinals", "Tigers");
            assertEquals(1, exit);
            assertTrue(stderr().contains("has 3 name(s) but division"));
        }

        @Test
        @DisplayName("exits 1 when an unrecognized team name is in --seeds")
        void failsOnUnrecognizedSeedName() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            int exit = generatePlayoff("Majors", "2026-06-14", "2026-06-28",
                "Blue Jays", "Tigers"); // Tigers not in division
            assertEquals(1, exit);
            assertTrue(stderr().contains("Unrecognized team name(s)"));
            assertTrue(stderr().contains("Tigers"));
        }

        @Test
        @DisplayName("exits 1 when duplicate team names are supplied in --seeds")
        void failsOnDuplicateSeeds() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            int exit = generatePlayoff("Majors", "2026-06-14", "2026-06-28",
                "Blue Jays", "Blue Jays");
            assertEquals(1, exit);
            assertTrue(stderr().contains("Duplicate team names"));
        }

        @Test
        @DisplayName("exits 1 when a playoff already exists for the division")
        void failsWhenPlayoffAlreadyExists() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            int exit = generatePlayoff("Majors", "2026-07-01", "2026-07-14",
                "Blue Jays", "Cardinals");
            assertEquals(1, exit);
            assertTrue(stderr().contains("already exists"));
        }

        @Test
        @DisplayName("exits 2 on corrupt data file")
        void ioErrorReturns2() throws Exception {
            corruptLeagueFile();
            int exit = generatePlayoff("Majors", "2026-06-14", "2026-06-28",
                "TeamA", "TeamB");
            assertEquals(2, exit);
        }
    }

    // ---------------------------------------------------------------------------
    // playoff status (summary view)
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("playoff status (summary)")
    class StatusSummary {

        @Test
        @DisplayName("prints NOT_STARTED for all divisions when no playoffs exist")
        void notStartedWhenNoPlayoffs() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            int exit = execute("playoff", "status");
            assertEquals(0, exit);
            assertTrue(stdout().contains("NOT_STARTED"));
        }

        @Test
        @DisplayName("shows GENERATED after a playoff is generated")
        void generatedState() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            execute("playoff", "status");
            assertTrue(stdout().contains("GENERATED"));
        }

        @Test
        @DisplayName("shows all divisions even when only some have playoffs")
        void showsAllDivisionsInSummary() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            addDivisionWithTeams("Minors", 60, "Red Sox", "Yankees", "Cubs");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            execute("playoff", "status");
            assertTrue(stdout().contains("Majors"));
            assertTrue(stdout().contains("Minors"));
            assertTrue(stdout().contains("GENERATED"));
            assertTrue(stdout().contains("NOT_STARTED"));
        }

        @Test
        @DisplayName("exits 0 with 'No divisions configured' when no divisions exist")
        void noDivisionsConfigured() {
            int exit = execute("playoff", "status");
            assertEquals(0, exit);
            assertTrue(stdout().contains("No divisions configured"));
        }

        @Test
        @DisplayName("exits 2 on corrupt data file")
        void ioErrorReturns2() throws Exception {
            corruptLeagueFile();
            assertEquals(2, execute("playoff", "status"));
        }
    }

    // ---------------------------------------------------------------------------
    // playoff status --division (detail view)
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("playoff status --division (detail)")
    class StatusDetail {

        @Test
        @DisplayName("prints full bracket table after generation")
        void printsBracketTableAfterGenerate() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            int exit = execute("playoff", "status", "--division", "Majors");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Blue Jays"));
            assertTrue(stdout().contains("Cardinals"));
            assertTrue(stdout().contains("Winners R1"));
            assertTrue(stdout().contains("Championship"));
        }

        @Test
        @DisplayName("prints period header with start and end dates")
        void printsPeriodHeader() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            execute("playoff", "status", "--division", "Majors");
            assertTrue(stdout().contains("2026-06-14"));
            assertTrue(stdout().contains("2026-06-28"));
        }

        @Test
        @DisplayName("division lookup is case-insensitive")
        void caseInsensitiveDivisionLookup() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            int exit = execute("playoff", "status", "--division", "majors");
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("shows UNASSIGNED for unassigned real games")
        void showsUnassignedForNewBracket() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            execute("playoff", "status", "--division", "Majors");
            assertTrue(stdout().contains("UNASSIGNED"));
        }

        @Test
        @DisplayName("shows BYE for bye slots in a 3-team bracket")
        void showsByeForByeSlots() {
            addDivisionWithTeams("Minors", 60, "Red Sox", "Yankees", "Cubs");
            generatePlayoff("Minors", "2026-06-14", "2026-06-28",
                "Red Sox", "Yankees", "Cubs");
            execute("playoff", "status", "--division", "Minors");
            assertTrue(stdout().contains("BYE"));
        }

        @Test
        @DisplayName("shows conditional marker (*) for the re-match slot")
        void showsConditionalMarker() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            execute("playoff", "status", "--division", "Majors");
            assertTrue(stdout().contains("*"));
            assertTrue(stdout().contains("conditional"));
        }

        @Test
        @DisplayName("exits 1 when division does not exist")
        void failsWhenDivisionNotFound() {
            int exit = execute("playoff", "status", "--division", "Ghost");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when no playoff exists for a valid division")
        void failsWhenNoPlayoffForDivision() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            int exit = execute("playoff", "status", "--division", "Majors");
            assertEquals(1, exit);
            assertTrue(stderr().contains("No playoff exists"));
        }
    }

    // ---------------------------------------------------------------------------
    // playoff clear
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("playoff clear")
    class Clear {

        @Test
        @DisplayName("removes the playoff after 'yes' confirmation and exits 0")
        void successWithYesConfirmation() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            int exit = clearPlayoff("Majors", "yes\n");
            assertEquals(0, exit);
            assertTrue(stdout().contains("cleared"));
        }

        @Test
        @DisplayName("playoff is no longer findable after successful clear")
        void playoffRemovedAfterClear() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            clearPlayoff("Majors", "yes\n");

            execute("playoff", "status");
            assertTrue(stdout().contains("NOT_STARTED"));
        }

        @Test
        @DisplayName("does not remove the playoff on non-'yes' input")
        void cancelledOnNonYes() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            int exit = clearPlayoff("Majors", "no\n");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Cancelled"));

            // Playoff should still exist
            execute("playoff", "status");
            assertTrue(stdout().contains("GENERATED"));
        }

        @Test
        @DisplayName("exits 1 when division does not exist")
        void failsWhenDivisionNotFound() {
            int exit = execute("playoff", "clear", "--division", "Ghost");
            assertEquals(1, exit);
            assertTrue(stderr().contains("not found"));
        }

        @Test
        @DisplayName("exits 1 when no playoff exists for the division")
        void failsWhenNoPlayoffExists() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            int exit = execute("playoff", "clear", "--division", "Majors");
            assertEquals(1, exit);
            assertTrue(stderr().contains("No playoff exists"));
        }

        @Test
        @DisplayName("exits 2 on corrupt data file")
        void ioErrorReturns2() throws Exception {
            corruptLeagueFile();
            assertEquals(2, execute("playoff", "clear", "--division", "Majors"));
        }
    }

    // ---------------------------------------------------------------------------
    // Generate → Status → Clear cycle
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("generate → status → clear lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("full lifecycle: generate transitions to GENERATED; clear reverts to NOT_STARTED")
        void generateThenClear() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");

            // Before generate: NOT_STARTED
            execute("playoff", "status");
            assertTrue(stdout().contains("NOT_STARTED"));

            // Generate
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");

            // After generate: GENERATED
            execute("playoff", "status");
            assertTrue(stdout().contains("GENERATED"));

            // Clear
            clearPlayoff("Majors", "yes\n");

            // After clear: NOT_STARTED again
            execute("playoff", "status");
            assertTrue(stdout().contains("NOT_STARTED"));
        }

        @Test
        @DisplayName("can re-generate after clearing")
        void canRegenerateAfterClear() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            clearPlayoff("Majors", "yes\n");

            // Re-generate with different dates
            int exit = generatePlayoff("Majors", "2026-07-01", "2026-07-14",
                "Cardinals", "Blue Jays"); // reversed seed order
            assertEquals(0, exit);

            execute("playoff", "status");
            assertTrue(stdout().contains("GENERATED"));
        }

        @Test
        @DisplayName("two divisions can each have their own independent playoff")
        void twoDivisionsIndependentPlayoffs() {
            addDivisionWithTeams("Majors", 60, "Blue Jays", "Cardinals");
            addDivisionWithTeams("Minors", 60, "Red Sox", "Yankees", "Cubs");

            generatePlayoff("Majors", "2026-06-14", "2026-06-28", "Blue Jays", "Cardinals");
            generatePlayoff("Minors", "2026-06-14", "2026-06-28", "Red Sox", "Yankees", "Cubs");

            execute("playoff", "status");
            assertTrue(stdout().contains("GENERATED")); // at least one
            // Both should show GENERATED
            long generatedCount = Arrays.stream(stdout().split("\n"))
                .filter(l -> l.contains("GENERATED")).count();
            assertEquals(2, generatedCount);
        }
    }
}
