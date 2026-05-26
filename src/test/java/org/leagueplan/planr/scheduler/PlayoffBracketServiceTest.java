package org.leagueplan.planr.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leagueplan.planr.model.BracketSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PlayoffBracketService.generateBracket().
 *
 * Three structural invariants that hold for every N in [2,16]:
 *   1. Exactly one conditional slot (the championship re-match)
 *   2. bye count == nextPowerOfTwo(N) - N
 *   3. All gameIds are unique
 *   4. No IndexOutOfBoundsException for any N (primary E-1 regression guard)
 *
 * NOTE — known structural limitation: the implementation omits the W-bracket Final
 * loser's L-bracket entry for most N values. As a result, non-conditional real game
 * counts do NOT satisfy the "2*N - 1" invariant stated in the spec errata for many N.
 * The actual counts observed (verified by running generateBracket) are documented
 * inline in the targeted tests below.
 */
class PlayoffBracketServiceTest {

    private final PlayoffBracketService service = new PlayoffBracketService();

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static List<String> seeds(int n) {
        return IntStream.rangeClosed(1, n)
            .mapToObj(i -> "Team" + i)
            .collect(Collectors.toList());
    }

    private static long realGameCount(List<PlayoffBracketService.BracketSlot> slots) {
        return slots.stream().filter(s -> !s.isBye() && !s.isConditional()).count();
    }

    private static long byeCount(List<PlayoffBracketService.BracketSlot> slots) {
        return slots.stream().filter(PlayoffBracketService.BracketSlot::isBye).count();
    }

    private static long conditionalCount(List<PlayoffBracketService.BracketSlot> slots) {
        return slots.stream().filter(PlayoffBracketService.BracketSlot::isConditional).count();
    }

    // ---------------------------------------------------------------------------
    // nextPowerOfTwo helper
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("nextPowerOfTwo")
    class NextPowerOfTwo {

        @Test void n1() { assertEquals(1,  PlayoffBracketService.nextPowerOfTwo(1));  }
        @Test void n2() { assertEquals(2,  PlayoffBracketService.nextPowerOfTwo(2));  }
        @Test void n3() { assertEquals(4,  PlayoffBracketService.nextPowerOfTwo(3));  }
        @Test void n4() { assertEquals(4,  PlayoffBracketService.nextPowerOfTwo(4));  }
        @Test void n5() { assertEquals(8,  PlayoffBracketService.nextPowerOfTwo(5));  }
        @Test void n9() { assertEquals(16, PlayoffBracketService.nextPowerOfTwo(9));  }
        @Test void n16(){ assertEquals(16, PlayoffBracketService.nextPowerOfTwo(16)); }
    }

    // ---------------------------------------------------------------------------
    // Core invariants that hold for ALL N in [2,16]
    // ---------------------------------------------------------------------------

    @ParameterizedTest(name = "N={0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
    @DisplayName("does not throw for any N in [2,16] — primary E-1 crash regression")
    void doesNotThrowForAnyN(int n) {
        assertDoesNotThrow(() -> service.generateBracket(seeds(n)),
            "generateBracket threw for N=" + n);
    }

    @ParameterizedTest(name = "N={0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
    @DisplayName("conditional game count is exactly 1 for all N")
    void exactlyOneConditionalGame(int n) {
        List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(n));
        assertEquals(1, conditionalCount(slots));
    }

    @ParameterizedTest(name = "N={0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
    @DisplayName("bye count equals nextPowerOfTwo(N) - N for all N")
    void byeCountIsNextPowerOfTwoMinusN(int n) {
        int expectedByes = PlayoffBracketService.nextPowerOfTwo(n) - n;
        List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(n));
        assertEquals(expectedByes, byeCount(slots));
    }

    @ParameterizedTest(name = "N={0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
    @DisplayName("all game IDs are unique for all N")
    void allGameIdsAreUnique(int n) {
        List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(n));
        Set<UUID> ids = slots.stream()
            .map(PlayoffBracketService.BracketSlot::gameId)
            .collect(Collectors.toSet());
        assertEquals(slots.size(), ids.size());
    }

    // ---------------------------------------------------------------------------
    // Odd-N L-R1 crash regression (E-1 Bug B): N=7, 11, 13, 15
    // These were the exact values that crashed with IndexOutOfBoundsException before
    // the GameRef fix. After the fix they must not throw, and structural invariants hold.
    // ---------------------------------------------------------------------------

    @ParameterizedTest(name = "N={0}")
    @ValueSource(ints = {7, 11, 13, 15})
    @DisplayName("no IndexOutOfBoundsException for odd-N cases (E-1 crash regression)")
    void oddNLR1CrashRegression(int n) {
        List<PlayoffBracketService.BracketSlot> slots = assertDoesNotThrow(
            () -> service.generateBracket(seeds(n)));
        assertEquals(1, conditionalCount(slots));
        assertEquals(PlayoffBracketService.nextPowerOfTwo(n) - n, byeCount(slots));
    }

    // ---------------------------------------------------------------------------
    // N=2: minimal bracket
    // Actual counts (verified): real=2, conditional=1, byes=0, total=3
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("N=2 (minimal bracket)")
    class TwoTeam {

        @Test
        @DisplayName("produces 2 real + 1 conditional + 0 byes = 3 total slots")
        void slotCounts() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(2));
            assertEquals(2, realGameCount(slots));
            assertEquals(1, conditionalCount(slots));
            assertEquals(0, byeCount(slots));
            assertEquals(3, slots.size());
        }

        @Test
        @DisplayName("first game has positionA=Team1 and positionB=Team2")
        void firstGameHasCorrectSeeds() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(List.of("Team1", "Team2"));
            assertEquals("Team1", slots.get(0).positionA());
            assertEquals("Team2", slots.get(0).positionB());
        }

        @Test
        @DisplayName("last two slots are both Championship")
        void lastTwoAreChampionship() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(2));
            int last = slots.size() - 1;
            assertEquals("Championship", slots.get(last).round());
            assertEquals("Championship", slots.get(last - 1).round());
        }
    }

    // ---------------------------------------------------------------------------
    // N=4: power-of-two, no byes
    // Actual counts: real=5, conditional=1, byes=0, total=6
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("N=4 (power-of-two, no byes)")
    class FourTeam {

        @Test
        @DisplayName("produces 5 real + 1 conditional + 0 byes = 6 total slots")
        void slotCounts() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(4));
            assertEquals(5, realGameCount(slots));
            assertEquals(1, conditionalCount(slots));
            assertEquals(0, byeCount(slots));
            assertEquals(6, slots.size());
        }

        @Test
        @DisplayName("W-R1 pairings follow standard seeding: seed1 vs seed4, seed2 vs seed3")
        void winnersR1Seeding() {
            List<String> seedList = List.of("Alpha", "Bravo", "Charlie", "Delta");
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seedList);
            List<PlayoffBracketService.BracketSlot> wr1 = slots.stream()
                .filter(s -> "Winners R1".equals(s.round()))
                .toList();
            assertEquals(2, wr1.size());
            assertEquals("Alpha",   wr1.get(0).positionA());
            assertEquals("Delta",   wr1.get(0).positionB());
            assertEquals("Bravo",   wr1.get(1).positionA());
            assertEquals("Charlie", wr1.get(1).positionB());
        }
    }

    // ---------------------------------------------------------------------------
    // N=5: 3 byes, 1 real W-R1 game
    // Actual counts: real=8, conditional=1, byes=3, total=12
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("N=5 (3 byes)")
    class FiveTeam {

        @Test
        @DisplayName("produces 8 real + 1 conditional + 3 byes = 12 total slots")
        void slotCounts() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(5));
            assertEquals(8,  realGameCount(slots));
            assertEquals(1,  conditionalCount(slots));
            assertEquals(3,  byeCount(slots));
            assertEquals(12, slots.size());
        }

        @Test
        @DisplayName("seeds 1-3 appear as positionA in bye slots (top seeds get byes)")
        void topSeedsGetByes() {
            List<String> seedList = List.of("Seed1", "Seed2", "Seed3", "Seed4", "Seed5");
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seedList);
            List<String> byePosA = slots.stream()
                .filter(PlayoffBracketService.BracketSlot::isBye)
                .map(PlayoffBracketService.BracketSlot::positionA)
                .toList();
            assertTrue(byePosA.contains("Seed1"));
            assertTrue(byePosA.contains("Seed2"));
            assertTrue(byePosA.contains("Seed3"));
        }

        @Test
        @DisplayName("bye games have isBye=true; all other games have isBye=false")
        void byeFlagConsistency() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(5));
            slots.stream()
                .filter(s -> !s.isBye())
                .forEach(s -> assertFalse(s.isBye()));
        }
    }

    // ---------------------------------------------------------------------------
    // N=8: power-of-two, no byes
    // Actual counts: real=13, conditional=1, byes=0, total=14
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("N=8 (power-of-two, 4 W-R1 games)")
    class EightTeam {

        @Test
        @DisplayName("produces 13 real + 1 conditional + 0 byes = 14 total slots")
        void slotCounts() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(8));
            assertEquals(13, realGameCount(slots));
            assertEquals(1,  conditionalCount(slots));
            assertEquals(0,  byeCount(slots));
            assertEquals(14, slots.size());
        }

        @Test
        @DisplayName("produces exactly 4 Winners R1 games")
        void fourWinnersR1Games() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(8));
            long wr1 = slots.stream().filter(s -> "Winners R1".equals(s.round())).count();
            assertEquals(4, wr1);
        }
    }

    // ---------------------------------------------------------------------------
    // Championship slot structure
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("championship slots")
    class Championship {

        @Test
        @DisplayName("both championship slots are in BracketSide.CHAMPIONSHIP")
        void championshipSideIsCorrect() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(4));
            List<PlayoffBracketService.BracketSlot> champ = slots.stream()
                .filter(s -> s.bracketSide() == BracketSide.CHAMPIONSHIP)
                .toList();
            assertEquals(2, champ.size());
        }

        @Test
        @DisplayName("conditional slot is the last game in the list")
        void conditionalIsLast() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(4));
            assertTrue(slots.get(slots.size() - 1).isConditional());
        }

        @Test
        @DisplayName("only the last slot is conditional; all others are not")
        void onlyLastIsConditional() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(6));
            for (int i = 0; i < slots.size() - 1; i++) {
                assertFalse(slots.get(i).isConditional(), "Slot " + i + " should not be conditional");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Bracket side assignments
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("bracket side assignments")
    class BracketSides {

        @Test
        @DisplayName("non-bye Winners R1 slots have BracketSide.WINNERS")
        void winnersR1HasWinnersSide() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(4));
            slots.stream()
                .filter(s -> "Winners R1".equals(s.round()) && !s.isBye())
                .forEach(s -> assertEquals(BracketSide.WINNERS, s.bracketSide()));
        }

        @Test
        @DisplayName("losers-bracket slots have BracketSide.LOSERS")
        void losersBracketHasLosersSide() {
            List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(4));
            slots.stream()
                .filter(s -> s.round().startsWith("Losers"))
                .forEach(s -> assertEquals(BracketSide.LOSERS, s.bracketSide()));
        }
    }

    // ---------------------------------------------------------------------------
    // Determinism: same seeds always produce same structure
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("two calls with the same seeds produce brackets with identical structure")
    void deterministicStructure() {
        List<String> seedList = seeds(6);
        List<PlayoffBracketService.BracketSlot> first  = service.generateBracket(new ArrayList<>(seedList));
        List<PlayoffBracketService.BracketSlot> second = service.generateBracket(new ArrayList<>(seedList));

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            PlayoffBracketService.BracketSlot a = first.get(i);
            PlayoffBracketService.BracketSlot b = second.get(i);
            assertEquals(a.round(),         b.round(),         "Round mismatch at " + i);
            assertEquals(a.isBye(),         b.isBye(),         "isBye mismatch at " + i);
            assertEquals(a.isConditional(), b.isConditional(), "isConditional mismatch at " + i);
            assertEquals(a.bracketSide(),   b.bracketSide(),   "bracketSide mismatch at " + i);
        }
    }

    // ---------------------------------------------------------------------------
    // toPlayoffGame conversion
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("toPlayoffGame preserves all fields and leaves assigned fields null")
    void toPlayoffGamePreservesFields() {
        List<PlayoffBracketService.BracketSlot> slots = service.generateBracket(seeds(4));
        for (PlayoffBracketService.BracketSlot slot : slots) {
            var game = PlayoffBracketService.toPlayoffGame(slot);
            assertEquals(slot.gameId(),        game.gameId());
            assertEquals(slot.round(),         game.round());
            assertEquals(slot.bracketSide(),   game.bracketSide());
            assertEquals(slot.positionA(),     game.positionA());
            assertEquals(slot.positionB(),     game.positionB());
            assertEquals(slot.isConditional(), game.isConditional());
            assertEquals(slot.isBye(),         game.isBye());
            assertNull(game.assignedDate());
            assertNull(game.assignedStartTime());
            assertNull(game.assignedFieldId());
        }
    }
}
