package org.leagueplan.planr.scheduler;

import org.leagueplan.planr.model.AvailabilityWindow;
import org.leagueplan.planr.model.Division;
import org.leagueplan.planr.model.Field;
import org.leagueplan.planr.model.League;
import org.leagueplan.planr.model.ScheduledGame;
import org.leagueplan.planr.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SchedulerService.generate().
 *
 * All tests use small leagues (2-4 teams per division) so the solver completes in milliseconds.
 * The key behavioral guarantees checked are correctness of constraints, not solver performance.
 */
class SchedulerServiceTest {

    private static final LocalDate SEASON_START = LocalDate.of(2026, 6, 1);
    // June–August: 13 Saturdays (June 6,13,20,27; July 4,11,18,25; Aug 1,8,15,22,29).
    // With C3 (at most 1 game per team per day) and 4 teams: 2 games/Saturday max.
    // 13 Saturdays × 2 games = 26 slots → covers a 4-team division (12 games).
    // 3-team: 1 game/Saturday max (only 3 teams, 1 sits out) → 13 slots → covers 6 games.
    private static final LocalDate SEASON_END   = LocalDate.of(2026, 8, 31);
    // Short season used only in infeasibility tests where total slot count must be < fixture count.
    private static final LocalDate SHORT_SEASON_END = LocalDate.of(2026, 6, 30);

    // Window 09:00-18:00, 60-min games, 15-min grid → 33 slots per Saturday per field.

    // ---------------------------------------------------------------------------
    // League builder helpers
    // ---------------------------------------------------------------------------

    private static Team team(String name) {
        return new Team(UUID.randomUUID(), name);
    }

    private static Division division(String name, int duration, Team... teams) {
        return new Division(UUID.randomUUID(), name, duration, List.of(teams));
    }

    private static AvailabilityWindow window(DayOfWeek day, String start, String end) {
        return new AvailabilityWindow(UUID.randomUUID(), day,
            LocalTime.parse(start), LocalTime.parse(end), null);
    }

    private static AvailabilityWindow windowForDiv(DayOfWeek day, String start, String end, UUID divisionId) {
        return new AvailabilityWindow(UUID.randomUUID(), day,
            LocalTime.parse(start), LocalTime.parse(end), divisionId);
    }

    private static Field field(String name, AvailabilityWindow... windows) {
        return new Field(UUID.randomUUID(), name, null, List.of(windows));
    }

    private static League league(List<Division> divisions, List<Field> fields) {
        return new League(3, divisions, fields, null);
    }

    /** Build a minimal 2-team league with 1 Saturday field. 2 games required. */
    private static League twoTeamLeague() {
        Team t1 = team("Blue Jays");
        Team t2 = team("Cardinals");
        Division div = division("Majors", 60, t1, t2);
        Field f = field("Riverside Park", window(DayOfWeek.SATURDAY, "09:00", "18:00"));
        return league(List.of(div), List.of(f));
    }

    /** Build a 4-team league. 12 games (4*3) required. */
    private static League fourTeamLeague() {
        Team t1 = team("Blue Jays");
        Team t2 = team("Cardinals");
        Team t3 = team("Red Sox");
        Team t4 = team("Yankees");
        Division div = division("Majors", 60, t1, t2, t3, t4);
        Field f = field("Riverside Park", window(DayOfWeek.SATURDAY, "09:00", "18:00"));
        return league(List.of(div), List.of(f));
    }

    /** Build a 3-team league (odd count). 6 games (3*2) required. */
    private static League threeTeamLeague() {
        Team t1 = team("Blue Jays");
        Team t2 = team("Cardinals");
        Team t3 = team("Red Sox");
        Division div = division("Majors", 60, t1, t2, t3);
        Field f = field("Riverside Park", window(DayOfWeek.SATURDAY, "09:00", "18:00"));
        return league(List.of(div), List.of(f));
    }

    private ScheduleResult generate(League l) {
        return new SchedulerService().generate(l, SEASON_START, SEASON_END);
    }

    /** Uses the short (June-only) season where 4 Saturdays → 4 slots — triggers pre-solve infeasibility for ≥5 fixtures. */
    private ScheduleResult generateShort(League l) {
        return new SchedulerService().generate(l, SEASON_START, SHORT_SEASON_END);
    }

    // ---------------------------------------------------------------------------
    // Fixture count correctness
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("generates exactly N*(N-1) games for a 2-team division")
    void twoTeamDivisionProducesTwoGames() {
        ScheduleResult result = generate(twoTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        assertEquals(2, ((ScheduleResult.Success) result).games().size());
    }

    @Test
    @DisplayName("generates exactly N*(N-1) games for a 4-team division")
    void fourTeamDivisionProducesTwelveGames() {
        ScheduleResult result = generate(fourTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        assertEquals(12, ((ScheduleResult.Success) result).games().size());
    }

    @Test
    @DisplayName("generates exactly N*(N-1) games for a 3-team division (odd count)")
    void threeTeamDivisionProducesSixGames() {
        ScheduleResult result = generate(threeTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        assertEquals(6, ((ScheduleResult.Success) result).games().size());
    }

    // ---------------------------------------------------------------------------
    // Home/away balance: each ordered pair appears exactly once
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("each team-pair appears exactly once with each home/away assignment")
    void eachTeamPairAppearsOnceInEachDirection() {
        ScheduleResult result = generate(fourTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        // For every (home, away) ordered pair, count occurrences.
        // Each should appear exactly once.
        for (int i = 0; i < games.size(); i++) {
            ScheduledGame gi = games.get(i);
            long count = games.stream()
                .filter(g -> g.homeTeamId().equals(gi.homeTeamId())
                          && g.awayTeamId().equals(gi.awayTeamId()))
                .count();
            assertEquals(1, count,
                "Ordered pair (" + gi.homeTeamName() + ", " + gi.awayTeamName()
                + ") should appear exactly once");
        }
    }

    @Test
    @DisplayName("the reverse of every game (home↔away swapped) also exists in the schedule")
    void reverseOfEveryGameAlsoExists() {
        ScheduleResult result = generate(fourTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        for (ScheduledGame g : games) {
            long reverseCount = games.stream()
                .filter(other -> other.homeTeamId().equals(g.awayTeamId())
                              && other.awayTeamId().equals(g.homeTeamId()))
                .count();
            assertEquals(1, reverseCount,
                "Reverse of (" + g.homeTeamName() + " vs " + g.awayTeamName()
                + ") should exist exactly once");
        }
    }

    // ---------------------------------------------------------------------------
    // Field conflict constraint (C2)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("no two games on the same field overlap including the 15-minute buffer")
    void noFieldConflictsIncludingBuffer() {
        ScheduleResult result = generate(fourTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        for (int i = 0; i < games.size(); i++) {
            ScheduledGame a = games.get(i);
            for (int j = i + 1; j < games.size(); j++) {
                ScheduledGame b = games.get(j);
                if (!a.fieldId().equals(b.fieldId()) || !a.date().equals(b.date())) continue;
                int aStart = toMinutes(a.startTime());
                int aEnd   = aStart + a.gameDurationMinutes() + 15;
                int bStart = toMinutes(b.startTime());
                int bEnd   = bStart + b.gameDurationMinutes() + 15;
                assertFalse(aStart < bEnd && bStart < aEnd,
                    "Field conflict: games " + i + " and " + j + " on "
                    + a.fieldName() + " at " + a.date());
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Team double-booking constraint (C3)
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("no team plays more than once on the same calendar day")
    void noTeamPlaysMoreThanOncePerDay() {
        ScheduleResult result = generate(fourTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        for (int i = 0; i < games.size(); i++) {
            ScheduledGame a = games.get(i);
            for (int j = i + 1; j < games.size(); j++) {
                ScheduledGame b = games.get(j);
                if (!a.date().equals(b.date())) continue;
                boolean teamOverlap =
                    a.homeTeamId().equals(b.homeTeamId()) ||
                    a.homeTeamId().equals(b.awayTeamId()) ||
                    a.awayTeamId().equals(b.homeTeamId()) ||
                    a.awayTeamId().equals(b.awayTeamId());
                assertFalse(teamOverlap,
                    "Team double-booked on " + a.date() + ": games " + i + " and " + j);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Season date boundary
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("all games fall within the configured season date range")
    void allGamesFallWithinSeasonDates() {
        ScheduleResult result = generate(twoTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        for (ScheduledGame g : games) {
            assertFalse(g.date().isBefore(SEASON_START),
                "Game date " + g.date() + " is before season start " + SEASON_START);
            assertFalse(g.date().isAfter(SEASON_END),
                "Game date " + g.date() + " is after season end " + SEASON_END);
        }
    }

    @Test
    @DisplayName("all games are scheduled on a day with a matching field window")
    void allGamesScheduledOnWindowDay() {
        ScheduleResult result = generate(twoTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        // Window is Saturday only, so all games must be on Saturday.
        for (ScheduledGame g : games) {
            assertEquals(DayOfWeek.SATURDAY, g.date().getDayOfWeek(),
                "Game on " + g.date() + " is not on Saturday");
        }
    }

    // ---------------------------------------------------------------------------
    // Division-restricted windows
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("games respect division-restricted windows — restricted window is used only for its division")
    void divisionRestrictedWindowsRespected() {
        Team majorsT1 = team("Blue Jays");
        Team majorsT2 = team("Cardinals");
        Team aaaT1    = team("Red Sox");
        Team aaaT2    = team("Yankees");

        Division majors = division("Majors", 60, majorsT1, majorsT2);
        Division aaa    = division("AAA",    60, aaaT1,    aaaT2);

        // Saturday window restricted to Majors only.
        // Sunday window restricted to AAA only.
        AvailabilityWindow majorsWindow =
            windowForDiv(DayOfWeek.SATURDAY, "09:00", "18:00", majors.id());
        AvailabilityWindow aaaWindow =
            windowForDiv(DayOfWeek.SUNDAY, "09:00", "18:00", aaa.id());
        Field f = field("Riverside Park", majorsWindow, aaaWindow);

        League l = league(List.of(majors, aaa), List.of(f));
        ScheduleResult result = generate(l);

        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        for (ScheduledGame g : games) {
            if (g.divisionName().equals("Majors")) {
                assertEquals(DayOfWeek.SATURDAY, g.date().getDayOfWeek(),
                    "Majors game should only be on Saturday");
            } else {
                assertEquals(DayOfWeek.SUNDAY, g.date().getDayOfWeek(),
                    "AAA game should only be on Sunday");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Multi-division schedules
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("schedules all fixtures across two divisions independently")
    void schedulesAllFixturesAcrossTwoDivisions() {
        Team m1 = team("Blue Jays"), m2 = team("Cardinals");
        Team a1 = team("Red Sox"),   a2 = team("Yankees");
        Division majors = division("Majors", 60, m1, m2);
        Division aaa    = division("AAA",    60, a1, a2);
        Field f = field("Riverside Park", window(DayOfWeek.SATURDAY, "09:00", "18:00"));

        League l = league(List.of(majors, aaa), List.of(f));
        ScheduleResult result = generate(l);

        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        // 2 teams × 2 divisions = 2 games each = 4 total
        assertEquals(4, games.size());

        long majorsCount = games.stream()
            .filter(g -> g.divisionName().equals("Majors")).count();
        long aaaCount = games.stream()
            .filter(g -> g.divisionName().equals("AAA")).count();
        assertEquals(2, majorsCount);
        assertEquals(2, aaaCount);
    }

    // ---------------------------------------------------------------------------
    // Infeasibility detection
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("returns Failure when there are fewer slots than fixtures for a division")
    void returnsFailureWhenInsufficientSlots() {
        // 4 teams → 12 games needed. Window 09:00-10:00 → exactly 1 slot per Saturday.
        // Short season (June only) → 4 Saturdays → 4 slots < 12 → pre-solve catches infeasibility.
        Team t1 = team("A"), t2 = team("B"), t3 = team("C"), t4 = team("D");
        Division div = division("Majors", 60, t1, t2, t3, t4);
        Field f = field("Riverside Park",
            new AvailabilityWindow(UUID.randomUUID(), DayOfWeek.SATURDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), null));
        League l = league(List.of(div), List.of(f));

        ScheduleResult result = generateShort(l);
        assertInstanceOf(ScheduleResult.Failure.class, result);
    }

    @Test
    @DisplayName("failure message names the infeasible division and includes game counts")
    void failureMessageNamesInfeasibleDivision() {
        Team t1 = team("A"), t2 = team("B"), t3 = team("C"), t4 = team("D");
        Division div = division("Majors", 60, t1, t2, t3, t4);
        Field f = field("Riverside Park",
            new AvailabilityWindow(UUID.randomUUID(), DayOfWeek.SATURDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), null));
        League l = league(List.of(div), List.of(f));

        ScheduleResult result = generateShort(l);
        assertInstanceOf(ScheduleResult.Failure.class, result);
        String msg = ((ScheduleResult.Failure) result).message();
        assertTrue(msg.contains("Majors"),
            "Failure message should name the infeasible division; got: " + msg);
        assertTrue(msg.contains("12"),
            "Failure message should include 12 games required; got: " + msg);
    }

    @Test
    @DisplayName("feasible division is listed as OK in the failure diagnostic when another division is infeasible")
    void feasibleDivisionListedAsOkInFailureDiagnostic() {
        // Majors: infeasible (4 teams, only 4 slots in short season)
        // AAA:    feasible  (2 teams, 4 slots ≥ 2 games needed)
        Team m1 = team("A"), m2 = team("B"), m3 = team("C"), m4 = team("D");
        Team a1 = team("E"), a2 = team("F");
        Division majors = division("Majors", 60, m1, m2, m3, m4);
        Division aaa    = division("AAA",    60, a1, a2);
        Field narrow = field("Narrow",
            new AvailabilityWindow(UUID.randomUUID(), DayOfWeek.SATURDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), null));
        League l = league(List.of(majors, aaa), List.of(narrow));

        ScheduleResult result = generateShort(l);
        assertInstanceOf(ScheduleResult.Failure.class, result);
        String msg = ((ScheduleResult.Failure) result).message();
        assertTrue(msg.contains("AAA") && msg.contains("OK"),
            "Failure message should list AAA as OK; got: " + msg);
    }

    // ---------------------------------------------------------------------------
    // Result metadata
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("success result has overridden=false on all games")
    void allGamesHaveOverriddenFalse() {
        ScheduleResult result = generate(twoTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        ((ScheduleResult.Success) result).games()
            .forEach(g -> assertFalse(g.overridden(), "Freshly generated game should not be overridden"));
    }

    @Test
    @DisplayName("games are sorted by date then start time then field name")
    void gamesAreSortedByDateThenStartThenField() {
        ScheduleResult result = generate(fourTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        for (int i = 1; i < games.size(); i++) {
            ScheduledGame prev = games.get(i - 1);
            ScheduledGame curr = games.get(i);
            int cmp = prev.date().compareTo(curr.date());
            if (cmp == 0) cmp = prev.startTime().compareTo(curr.startTime());
            if (cmp == 0) cmp = prev.fieldName().compareTo(curr.fieldName());
            assertTrue(cmp <= 0,
                "Games not sorted at index " + i + ": " + prev.date() + " " + prev.startTime()
                + " vs " + curr.date() + " " + curr.startTime());
        }
    }

    @Test
    @DisplayName("denormalized team and field names match league configuration")
    void denormalizedNamesMatchLeagueConfiguration() {
        ScheduleResult result = generate(twoTeamLeague());
        assertInstanceOf(ScheduleResult.Success.class, result);
        List<ScheduledGame> games = ((ScheduleResult.Success) result).games();

        for (ScheduledGame g : games) {
            assertTrue(
                g.homeTeamName().equals("Blue Jays") || g.homeTeamName().equals("Cardinals"),
                "Unexpected home team name: " + g.homeTeamName());
            assertTrue(
                g.awayTeamName().equals("Blue Jays") || g.awayTeamName().equals("Cardinals"),
                "Unexpected away team name: " + g.awayTeamName());
            assertEquals("Riverside Park", g.fieldName());
            assertEquals("Majors", g.divisionName());
        }
    }

    // ---------------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------------

    private static int toMinutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
}
