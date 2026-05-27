package org.leagueplan.planr.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end command tests for `planr practice`.
 *
 * <p>Excludes `practice assign` (requires the CP-SAT solver + stdin interaction). All tests use
 * 2-team divisions with small practice counts (1-2) to stay fast.
 *
 * <p>Setup pattern: - Add division with --practice-count, --practice-duration-minutes,
 * --practice-start, --practice-end - Add teams - Add field + config for assign tests only
 */
class PracticeCommandTest extends CommandTestBase {

  private static final String SEASON_START = "2026-06-01";
  private static final String PRAC_START = "2026-04-01";
  private static final String PRAC_END = "2026-05-15";

  // ---------------------------------------------------------------------------
  // Setup helpers
  // ---------------------------------------------------------------------------

  private void addConfiguredDivision(String name, int practiceCount) {
    execute("division", "add", name, "--duration", "60", "--target", "4");
    execute(
        "division",
        "edit",
        name,
        "--practice-count",
        String.valueOf(practiceCount),
        "--practice-duration-minutes",
        "60",
        "--practice-start",
        PRAC_START,
        "--practice-end",
        PRAC_END);
    execute("config", "set", "--start", SEASON_START, "--end", "2026-08-31");
  }

  private void addTeams(String division, String... teams) {
    for (String team : teams) {
      execute("team", "add", division, team);
    }
  }

  private int clearPractice(String division, String stdinResponse) {
    InputStream original = System.in;
    System.setIn(new ByteArrayInputStream(stdinResponse.getBytes()));
    try {
      return execute("practice", "clear", "--division", division);
    } finally {
      System.setIn(original);
    }
  }

  // ---------------------------------------------------------------------------
  // practice generate
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("practice generate")
  class Generate {

    @Test
    @DisplayName("exits 0 and prints per-division slot count for a configured division")
    void successSingleDivision() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");

      int exit = execute("practice", "generate");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Generated 4 practice slots for Majors"));
      assertTrue(stdout().contains("2 teams × 2 practices"));
    }

    @Test
    @DisplayName("exits 0 and prints summary line when multiple divisions qualify")
    void summaryLineMultipleDivisions() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("division", "add", "Minors", "--duration", "60", "--target", "4");
      execute(
          "division",
          "edit",
          "Minors",
          "--practice-count",
          "1",
          "--practice-duration-minutes",
          "60",
          "--practice-start",
          PRAC_START,
          "--practice-end",
          PRAC_END);
      addTeams("Minors", "Red Sox", "Yankees");

      int exit = execute("practice", "generate");
      assertEquals(0, exit);
      assertTrue(stdout().contains("2 division(s) processed"));
    }

    @Test
    @DisplayName("skips a division with incomplete practice configuration")
    void skipsUnconfiguredDivision() {
      execute("division", "add", "Unconfigured", "--duration", "60", "--target", "4");
      addTeams("Unconfigured", "Team A", "Team B");

      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "generate");
      assertTrue(stdout().contains("Skipping Unconfigured"));
    }

    @Test
    @DisplayName("skips a division that already has a practice schedule")
    void skipsAlreadyGeneratedDivision() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "generate");
      execute("practice", "generate");
      assertTrue(stdout().contains("Skipping Majors: practices already generated"));
    }

    @Test
    @DisplayName("skips a configured division with no teams")
    void skipsConfiguredDivisionWithNoTeams() {
      addConfiguredDivision("Empty", 2);
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "generate");
      assertTrue(stdout().contains("Skipping Empty: no teams configured"));
    }

    @Test
    @DisplayName("exits 1 when no divisions qualify")
    void failsWhenNoDivisionsQualify() {
      execute("division", "add", "Unconfigured", "--duration", "60", "--target", "4");

      int exit = execute("practice", "generate");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No divisions qualify"));
    }

    @Test
    @DisplayName("persists practice schedule so subsequent status shows GENERATED")
    void persistsAcrossInvocations() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "generate");
      execute("practice", "status");
      assertTrue(stdout().contains("GENERATED"));
    }

    @Test
    @DisplayName("exits 2 on corrupt data file")
    void ioErrorReturns2() throws Exception {
      corruptLeagueFile();
      assertEquals(2, execute("practice", "generate"));
    }
  }

  // ---------------------------------------------------------------------------
  // practice status (summary)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("practice status (summary)")
  class StatusSummary {

    @Test
    @DisplayName("shows NOT_CONFIGURED for a division with no practice config")
    void notConfiguredState() {
      execute("division", "add", "Bare", "--duration", "60", "--target", "4");
      execute("practice", "status");
      assertTrue(stdout().contains("NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("shows NOT_STARTED for a fully configured division with no schedule")
    void notStartedState() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "status");
      assertTrue(stdout().contains("NOT_STARTED"));
    }

    @Test
    @DisplayName("shows GENERATED after practice generate runs")
    void generatedState() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");
      execute("practice", "status");
      assertTrue(stdout().contains("GENERATED"));
    }

    @Test
    @DisplayName("shows ASSIGNED and TOTAL counts after generate")
    void showsAssignedAndTotal() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");
      execute("practice", "status");
      // 2 teams × 2 practices = 4 total; none assigned yet
      assertTrue(stdout().contains("4")); // total count
    }

    @Test
    @DisplayName("exits 0 with 'No divisions configured' when league is empty")
    void noDivisionsConfigured() {
      int exit = execute("practice", "status");
      assertEquals(0, exit);
      assertTrue(stdout().contains("No divisions configured"));
    }

    @Test
    @DisplayName("exits 2 on corrupt data file")
    void ioErrorReturns2() throws Exception {
      corruptLeagueFile();
      assertEquals(2, execute("practice", "status"));
    }
  }

  // ---------------------------------------------------------------------------
  // practice status --division (detail)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("practice status --division (detail)")
  class StatusDetail {

    @Test
    @DisplayName("prints per-slot table with team names and UNASSIGNED rows")
    void printsDetailTable() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");

      int exit = execute("practice", "status", "--division", "Majors");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Blue Jays"));
      assertTrue(stdout().contains("Cardinals"));
      assertTrue(stdout().contains("UNASSIGNED"));
    }

    @Test
    @DisplayName("prints division header with period dates")
    void printsPeriodHeader() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");

      execute("practice", "status", "--division", "Majors");
      assertTrue(stdout().contains(PRAC_START));
      assertTrue(stdout().contains(PRAC_END));
    }

    @Test
    @DisplayName("slot numbers appear as '1 of N' and '2 of N'")
    void slotNumbersAreCorrect() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays");
      execute("practice", "generate");

      execute("practice", "status", "--division", "Majors");
      assertTrue(stdout().contains("1 of 2"));
      assertTrue(stdout().contains("2 of 2"));
    }

    @Test
    @DisplayName("division lookup is case-insensitive")
    void caseInsensitiveLookup() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");
      int exit = execute("practice", "status", "--division", "majors");
      assertEquals(0, exit);
    }

    @Test
    @DisplayName("exits 1 when division does not exist")
    void failsWhenDivisionNotFound() {
      int exit = execute("practice", "status", "--division", "Ghost");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 1 when no practice schedule exists for a valid division")
    void failsWhenNoScheduleForDivision() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      int exit = execute("practice", "status", "--division", "Majors");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No practice schedule exists"));
    }
  }

  // ---------------------------------------------------------------------------
  // practice clear
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("practice clear")
  class Clear {

    @Test
    @DisplayName("removes the schedule after 'yes' confirmation and exits 0")
    void successWithYesConfirmation() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");

      int exit = clearPractice("Majors", "yes\n");
      assertEquals(0, exit);
      assertTrue(stdout().contains("cleared"));
    }

    @Test
    @DisplayName("schedule is gone after clear — status shows NOT_STARTED")
    void scheduleRemovedAfterClear() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");
      clearPractice("Majors", "yes\n");

      execute("practice", "status");
      assertTrue(stdout().contains("NOT_STARTED"));
    }

    @Test
    @DisplayName("does not remove schedule on non-'yes' input")
    void cancelledOnNonYes() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");

      int exit = clearPractice("Majors", "no\n");
      assertEquals(0, exit);
      assertTrue(stdout().contains("Cancelled"));

      execute("practice", "status");
      assertTrue(stdout().contains("GENERATED"));
    }

    @Test
    @DisplayName("confirmation prompt includes slot count")
    void promptIncludesSlotCount() {
      addConfiguredDivision("Majors", 2);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("practice", "generate");

      clearPractice("Majors", "no\n");
      // 2 teams × 2 practices = 4 slots
      assertTrue(stdout().contains("4 slot(s)"));
    }

    @Test
    @DisplayName("exits 1 when division does not exist")
    void failsWhenDivisionNotFound() {
      int exit = execute("practice", "clear", "--division", "Ghost");
      assertEquals(1, exit);
      assertTrue(stderr().contains("not found"));
    }

    @Test
    @DisplayName("exits 1 when no practice schedule exists for the division")
    void failsWhenNoScheduleExists() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      int exit = execute("practice", "clear", "--division", "Majors");
      assertEquals(1, exit);
      assertTrue(stderr().contains("No practice schedule exists"));
    }

    @Test
    @DisplayName("exits 2 on corrupt data file")
    void ioErrorReturns2() throws Exception {
      corruptLeagueFile();
      assertEquals(2, execute("practice", "clear", "--division", "Majors"));
    }
  }

  // ---------------------------------------------------------------------------
  // Generate → Status → Clear lifecycle
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("generate → status → clear lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("full cycle: generate transitions to GENERATED; clear reverts to NOT_STARTED")
    void generateThenClear() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "status");
      assertTrue(stdout().contains("NOT_STARTED"));

      execute("practice", "generate");
      execute("practice", "status");
      assertTrue(stdout().contains("GENERATED"));

      clearPractice("Majors", "yes\n");
      execute("practice", "status");
      assertTrue(stdout().contains("NOT_STARTED"));
    }

    @Test
    @DisplayName("can re-generate after clearing")
    void canRegenerateAfterClear() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");

      execute("practice", "generate");
      clearPractice("Majors", "yes\n");

      int exit = execute("practice", "generate");
      assertEquals(0, exit);
      execute("practice", "status");
      assertTrue(stdout().contains("GENERATED"));
    }

    @Test
    @DisplayName("two divisions generate independently and both show up in status")
    void twoDivisionsIndependent() {
      addConfiguredDivision("Majors", 1);
      addTeams("Majors", "Blue Jays", "Cardinals");
      execute("division", "add", "Minors", "--duration", "60", "--target", "4");
      execute(
          "division",
          "edit",
          "Minors",
          "--practice-count",
          "1",
          "--practice-duration-minutes",
          "60",
          "--practice-start",
          PRAC_START,
          "--practice-end",
          PRAC_END);
      addTeams("Minors", "Red Sox", "Yankees");

      execute("practice", "generate");
      execute("practice", "status");
      assertTrue(stdout().contains("Majors"));
      assertTrue(stdout().contains("Minors"));
    }
  }
}
