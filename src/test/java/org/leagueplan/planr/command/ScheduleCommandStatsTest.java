package org.leagueplan.planr.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.leagueplan.planr.model.TeamGame;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ScheduleCommand.printBalanceBlock and printHeadToHeadBlock.
 *
 * These tests call the package-private static helpers directly (no CLI round-trip) and
 * capture stdout via the CommandTestBase redirect. Each test arranges a minimal game list,
 * acts by calling the static method, then asserts on the captured output.
 */
class ScheduleCommandStatsTest extends CommandTestBase {

    private static final UUID DIV = UUID.nameUUIDFromBytes("div".getBytes());

    /** Creates a TeamGame where home and away are identified by name only (ID is deterministic). */
    private static TeamGame game(String home, String away) {
        return new TeamGame(
            UUID.randomUUID(), 1,
            UUID.nameUUIDFromBytes(home.getBytes()), home,
            UUID.nameUUIDFromBytes(away.getBytes()), away,
            DIV, "Majors", 60
        );
    }

    /** Creates a TeamGame in a named division. */
    private static TeamGame game(String home, String away, String divisionName) {
        return new TeamGame(
            UUID.randomUUID(), 1,
            UUID.nameUUIDFromBytes(home.getBytes()), home,
            UUID.nameUUIDFromBytes(away.getBytes()), away,
            DIV, divisionName, 60
        );
    }

    // -------------------------------------------------------------------------
    // printBalanceBlock
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("printBalanceBlock")
    class BalanceBlock {

        @Test
        @DisplayName("section header includes the division name after the em-dash")
        void sectionHeaderIncludesDivisionName() {
            ScheduleCommand.printBalanceBlock(List.of(game("Alpha", "Beta")), "Premier");
            assertTrue(stdout().contains("HOME/AWAY BALANCE — Premier"));
        }

        @Test
        @DisplayName("teams are sorted alphabetically regardless of first-occurrence order")
        void teamsAreSortedAlphabetically() {
            // Games arrive in Zebra→Alpha→Mango order; rows must appear Alpha, Mango, Zebra
            List<TeamGame> games = List.of(
                game("Zebra", "Alpha"),
                game("Alpha", "Mango"),
                game("Mango", "Zebra")
            );
            ScheduleCommand.printBalanceBlock(games, "Majors");
            String out = stdout();
            int alphaPos = out.indexOf("Alpha");
            int mangoPos = out.indexOf("Mango");
            int zebraPos = out.indexOf("Zebra");
            assertTrue(alphaPos < mangoPos, "Alpha must appear before Mango");
            assertTrue(mangoPos < zebraPos, "Mango must appear before Zebra");
        }

        @Test
        @DisplayName("HOME + AWAY = TOTAL for every team row")
        void homePlusAwayEqualsTotal() {
            // Alpha: home=3, away=1, total=4 — verify "4" appears in the alpha line
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Beta", "Alpha")
            );
            ScheduleCommand.printBalanceBlock(games, "Majors");
            String alphaLine = Arrays.stream(stdout().split("\n"))
                .filter(l -> l.trim().startsWith("Alpha"))
                .findFirst().orElse("");
            assertFalse(alphaLine.isEmpty(), "Alpha row must be present");
            // Alpha: home=3, away=1, total=4
            assertTrue(alphaLine.contains("4"), "TOTAL column for Alpha must be 4 (3+1)");
        }

        @Test
        @DisplayName("BALANCE equals HOME minus AWAY")
        void balanceEqualsHomeMinusAway() {
            // Alpha: home=3, away=1 → balance=+2; Beta: home=1, away=3 → balance=-2
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Beta", "Alpha")
            );
            ScheduleCommand.printBalanceBlock(games, "Majors");
            String out = stdout();
            assertTrue(out.contains("+2"), "BALANCE for Alpha (3−1=+2) must appear as '+2'");
            assertTrue(out.contains("-2"), "BALANCE for Beta (1−3=−2) must appear as '-2'");
        }

        @Test
        @DisplayName("zero balance is shown as '0' without any sign character")
        void zeroBalanceDisplaysAsZeroWithoutSign() {
            // Alpha: home=1, away=1 → balance=0
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Beta", "Alpha")
            );
            ScheduleCommand.printBalanceBlock(games, "Majors");
            String out = stdout();
            assertFalse(out.contains("+0"), "zero balance must not appear as '+0'");
            assertFalse(out.contains("-0"), "zero balance must not appear as '-0'");
            assertTrue(out.contains("0"), "'0' must appear for a balanced team");
        }

        @Test
        @DisplayName("positive balance is shown with an explicit leading '+' sign")
        void positiveBalanceDisplaysWithPlusSign() {
            // Alpha: home=2, away=0 → balance=+2
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Alpha", "Beta")
            );
            ScheduleCommand.printBalanceBlock(games, "Majors");
            assertTrue(stdout().contains("+2"), "positive balance must appear with a '+' prefix");
        }

        @Test
        @DisplayName("negative balance is shown with a leading hyphen (ASCII '-')")
        void negativeBalanceDisplaysWithHyphen() {
            // Beta: home=0, away=2 → balance=-2
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Alpha", "Beta")
            );
            ScheduleCommand.printBalanceBlock(games, "Majors");
            assertTrue(stdout().contains("-2"), "negative balance must appear with a '-' prefix");
        }

        @Test
        @DisplayName("|balance| = 1 does not trigger the flag (threshold is strictly > 1)")
        void absoluteBalanceOfOneIsNotFlagged() {
            // Alpha: home=2, away=1 → balance=+1; Beta: home=1, away=2 → balance=-1
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Beta", "Alpha")
            );
            ScheduleCommand.printBalanceBlock(games, "Majors");
            assertFalse(stdout().contains(" *"),
                "rows with |balance|=1 must not be flagged");
        }

        @Test
        @DisplayName("|balance| = 2 triggers the ' *' flag")
        void absoluteBalanceOfTwoIsFlaged() {
            // Alpha: home=3, away=1 → balance=+2 → flagged
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Beta", "Alpha")
            );
            ScheduleCommand.printBalanceBlock(games, "Majors");
            assertTrue(stdout().contains(" *"),
                "rows with |balance|=2 must carry the ' *' flag");
        }

        @Test
        @DisplayName("TOTAL row sums HOME, AWAY, and TOTAL correctly")
        void totalRowShowsCorrectSums() {
            // Alpha: home=2, away=1; Beta: home=1, away=2 → TOTAL: HOME=3, AWAY=3, TOTAL=6
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Beta", "Alpha")
            );
            ScheduleCommand.printBalanceBlock(games, "Majors");
            String totalLine = Arrays.stream(stdout().split("\n"))
                .filter(l -> l.trim().startsWith("TOTAL"))
                .findFirst().orElse("");
            assertFalse(totalLine.isEmpty(), "TOTAL row must be present");
            assertTrue(totalLine.contains("3"), "TOTAL HOME+AWAY subtotals must each be 3");
            assertTrue(totalLine.contains("6"), "TOTAL grand total must be 6");
        }

        @Test
        @DisplayName("TOTAL row has no BALANCE column — no sign character appears on that line")
        void totalRowOmitsBalanceColumn() {
            // Alpha: home=3, away=1 → balance=+2 (would normally show '+'); TOTAL row must not
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Beta", "Alpha")
            );
            ScheduleCommand.printBalanceBlock(games, "Majors");
            String totalLine = Arrays.stream(stdout().split("\n"))
                .filter(l -> l.trim().startsWith("TOTAL"))
                .findFirst().orElse("");
            assertFalse(totalLine.isEmpty(), "TOTAL row must be present");
            assertFalse(totalLine.contains("+"), "TOTAL row must not include a '+' balance value");
            assertFalse(totalLine.contains(" *"), "TOTAL row must not include a flag");
        }
    }

    // -------------------------------------------------------------------------
    // printHeadToHeadBlock
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("printHeadToHeadBlock")
    class HeadToHeadBlock {

        @Test
        @DisplayName("section header includes division name and home/away axis annotation")
        void sectionHeaderIncludesDivisionNameAndAnnotation() {
            ScheduleCommand.printHeadToHeadBlock(List.of(game("Alpha", "Beta")), "Premier");
            String out = stdout();
            assertTrue(out.contains("HEAD-TO-HEAD — Premier"),
                "division name must appear in the section header");
            assertTrue(out.contains("row = home team"),
                "header must annotate rows as home team");
            assertTrue(out.contains("column = away team"),
                "header must annotate columns as away team");
        }

        @Test
        @DisplayName("column headers are sorted alphabetically")
        void columnHeadersAreSortedAlphabetically() {
            List<TeamGame> games = List.of(
                game("Zebra", "Alpha"),
                game("Alpha", "Mango"),
                game("Mango", "Zebra")
            );
            ScheduleCommand.printHeadToHeadBlock(games, "Majors");
            // Line 0 = section header; line 1 = column header row
            String headerLine = stdout().split("\n")[1];
            int alphaPos = headerLine.indexOf("Alpha");
            int mangoPos = headerLine.indexOf("Mango");
            int zebraPos = headerLine.indexOf("Zebra");
            assertTrue(alphaPos >= 0, "Alpha must appear in column headers");
            assertTrue(alphaPos < mangoPos, "Alpha must come before Mango in column headers");
            assertTrue(mangoPos < zebraPos, "Mango must come before Zebra in column headers");
        }

        @Test
        @DisplayName("row labels are sorted alphabetically")
        void rowLabelsAreSortedAlphabetically() {
            List<TeamGame> games = List.of(
                game("Zebra", "Alpha"),
                game("Alpha", "Mango"),
                game("Mango", "Zebra")
            );
            ScheduleCommand.printHeadToHeadBlock(games, "Majors");
            String[] lines = stdout().split("\n");
            // lines[0]=header, lines[1]=columns, lines[2]=separator, lines[3..5]=rows
            int alphaRow = -1, mangoRow = -1, zebraRow = -1;
            for (int i = 3; i < lines.length; i++) {
                String t = lines[i].trim();
                if (t.startsWith("Alpha")) alphaRow = i;
                else if (t.startsWith("Mango")) mangoRow = i;
                else if (t.startsWith("Zebra")) zebraRow = i;
            }
            assertTrue(alphaRow > 0 && alphaRow < mangoRow,
                "Alpha row must come before Mango row");
            assertTrue(mangoRow < zebraRow,
                "Mango row must come before Zebra row");
        }

        @Test
        @DisplayName("diagonal cells contain the em dash character (U+2014)")
        void diagonalCellsContainEmDash() {
            ScheduleCommand.printHeadToHeadBlock(
                List.of(game("Alpha", "Beta"), game("Beta", "Alpha")), "Majors");
            assertTrue(stdout().contains("—"),
                "diagonal cells must show '—' (U+2014)");
        }

        @Test
        @DisplayName("zero matchup count is displayed as '0', not as a blank")
        void zeroMatchupDisplaysAsZeroNotBlank() {
            // Alpha hosts Beta, Beta hosts Gamma, Gamma hosts Alpha — none host the third pair
            // → matrix[Alpha][Gamma]=0, matrix[Beta][Alpha]=0, matrix[Gamma][Beta]=0
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Beta", "Gamma"),
                game("Gamma", "Alpha")
            );
            ScheduleCommand.printHeadToHeadBlock(games, "Majors");
            assertTrue(stdout().contains("0"),
                "cell with zero matchups must print '0', not blank");
        }

        @Test
        @DisplayName("non-zero matchup count is displayed as a plain integer")
        void nonZeroMatchupDisplaysAsInteger() {
            // Alpha hosts Beta 2 times
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Beta", "Alpha")
            );
            ScheduleCommand.printHeadToHeadBlock(games, "Majors");
            // Row Alpha, col Beta = 2 (mode for row Alpha with 2 values is 2, no flag)
            // Row Beta, col Alpha = 1 (mode for row Beta is 1, no flag)
            // Both appear as plain integers somewhere in the output
            assertTrue(stdout().contains("2"),
                "a count of 2 must appear as '2'");
        }

        @Test
        @DisplayName("when all non-diagonal cells in a row are equal, no cell is flagged")
        void uniformRowValuesProduceNoFlags() {
            // Each pair plays once in each direction → all non-diagonal = 1 → mode=1 → no flags
            List<TeamGame> games = List.of(
                game("A", "B"), game("B", "A"),
                game("A", "C"), game("C", "A"),
                game("B", "C"), game("C", "B")
            );
            ScheduleCommand.printHeadToHeadBlock(games, "Majors");
            assertFalse(stdout().contains("*"),
                "no cell must be flagged when all non-diagonal values in a row are equal");
        }

        @Test
        @DisplayName("cell deviating from row mode is flagged with '*' appended directly (no space)")
        void deviatingCellFlaggedWithAsteriskDirectlyAppended() {
            // A hosts B twice, A hosts C once → row A non-diag = [2, 1] → tie → mode=1 → "2" flagged as "2*"
            List<TeamGame> games = List.of(
                game("A", "B"),
                game("A", "B"),
                game("A", "C"),
                game("B", "C")
            );
            ScheduleCommand.printHeadToHeadBlock(games, "Majors");
            String out = stdout();
            assertTrue(out.contains("2*"),
                "flagged cell must be count immediately followed by '*' with no space");
            assertFalse(out.contains("2 *"),
                "there must be no space between the count and the '*' flag");
        }

        @Test
        @DisplayName("when row-mode frequency ties, the lower value is chosen as mode")
        void modeTieBreakChoosesLowerValue() {
            // Row "A": non-diagonal = [B=1, C=0, D=1, E=0] → freq {1:2, 0:2} → tie → mode=0 → "1" values flagged
            // If tie-break were wrong (mode=1), zeros would be flagged as "0*" instead.
            UUID idA = UUID.nameUUIDFromBytes("A".getBytes());
            UUID idB = UUID.nameUUIDFromBytes("B".getBytes());
            UUID idC = UUID.nameUUIDFromBytes("C".getBytes());
            UUID idD = UUID.nameUUIDFromBytes("D".getBytes());
            UUID idE = UUID.nameUUIDFromBytes("E".getBytes());
            List<TeamGame> games = List.of(
                new TeamGame(UUID.randomUUID(), 1, idA, "A", idB, "B", DIV, "X", 60),
                new TeamGame(UUID.randomUUID(), 2, idA, "A", idD, "D", DIV, "X", 60),
                new TeamGame(UUID.randomUUID(), 3, idB, "B", idC, "C", DIV, "X", 60),
                new TeamGame(UUID.randomUUID(), 4, idC, "C", idE, "E", DIV, "X", 60),
                new TeamGame(UUID.randomUUID(), 5, idD, "D", idB, "B", DIV, "X", 60)
            );
            ScheduleCommand.printHeadToHeadBlock(games, "X");
            String out = stdout();
            assertTrue(out.contains("1*"),
                "when mode is 0 by tie-break, cells with value 1 must be flagged as '1*'");
            assertFalse(out.contains("0*"),
                "zero must not be flagged when it is the mode chosen by tie-break");
        }

        @Test
        @DisplayName("column header row is indented by spaces equal to the row-label column width")
        void columnHeaderRowIsIndentedByRowLabelWidth() {
            // "Alpha" is 5 chars → rowLabelW=5; header line must start with 5 spaces
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Beta", "Alpha")
            );
            ScheduleCommand.printHeadToHeadBlock(games, "Majors");
            String[] lines = stdout().split("\n");
            // lines[0]=section header, lines[1]=column header row
            String columnHeaderLine = lines[1];
            assertTrue(columnHeaderLine.startsWith("     "),
                "column header row must start with at least 5 spaces (rowLabelW for 'Alpha')");
            assertFalse(columnHeaderLine.trim().isEmpty(),
                "column header row must contain team names after the indent");
        }

        @Test
        @DisplayName("column separator width is at least as wide as the team name in that column")
        void columnSeparatorWidthMeetsTeamNameLength() {
            // "LongTeamName" is 12 chars; its column separator must have ≥12 consecutive dashes
            List<TeamGame> games = List.of(
                game("LongTeamName", "Short"),
                game("Short", "LongTeamName")
            );
            ScheduleCommand.printHeadToHeadBlock(games, "Majors");
            assertTrue(stdout().contains("------------"),
                "separator for a 12-char team name column must contain at least 12 consecutive dashes");
        }

        @Test
        @DisplayName("matrix rows represent home team and columns represent away team")
        void matrixOrientationIsHomeRowAwayColumn() {
            // Alpha hosts Beta 3 times; Beta never hosts Alpha
            // → matrix[Alpha][Beta]=3 (row Alpha, col Beta); matrix[Beta][Alpha]=0 (row Beta, col Alpha)
            List<TeamGame> games = List.of(
                game("Alpha", "Beta"),
                game("Alpha", "Beta"),
                game("Alpha", "Beta")
            );
            ScheduleCommand.printHeadToHeadBlock(games, "Majors");
            String[] lines = stdout().split("\n");
            // lines[3] = Alpha row, lines[4] = Beta row (alphabetical order, after header+sep)
            String alphaRow = lines[3]; // Alpha as home team
            String betaRow  = lines[4]; // Beta as home team
            // Alpha row has count=3 in the Beta column (the non-diagonal cell for Beta hosting Alpha=0 is Beta's row)
            assertTrue(alphaRow.contains("3"),
                "row Alpha (home) must show 3 in the Beta (away) column");
            assertTrue(betaRow.contains("0"),
                "row Beta (home) must show 0 in the Alpha (away) column — Beta never hosted Alpha");
        }
    }
}
