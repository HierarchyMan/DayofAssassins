package com.fusion.dev.cystol.kill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Dense end places + live competition / first-to-reach. */
class DenseRankingTest {

    @Test
    void densePlacesShareAndAdvanceByOne() {
        List<DenseRanking.Entry> ranked = DenseRanking.rankDense(List.of(
                new DenseRanking.KillRecord(UUID.randomUUID(), "A", 10, 100),
                new DenseRanking.KillRecord(UUID.randomUUID(), "B", 10, 200),
                new DenseRanking.KillRecord(UUID.randomUUID(), "C", 8, 50),
                new DenseRanking.KillRecord(UUID.randomUUID(), "D", 8, 60),
                new DenseRanking.KillRecord(UUID.randomUUID(), "E", 5, 1)
        ));
        assertEquals(5, ranked.size());
        assertEquals(1, placeForKills(ranked, 10));
        assertEquals(2, ranked.stream().filter(e -> e.kills() == 10).count());
        assertEquals(2, placeForKills(ranked, 8));
        assertEquals(3, placeForKills(ranked, 5));
    }

    @Test
    void competitionGivesUniquePlacesAndFirstToReachWins() {
        UUID early = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID late = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        // Both 2 kills; early reached first → #1 even if name would sort after
        List<DenseRanking.Entry> live = DenseRanking.rankCompetition(List.of(
                new DenseRanking.KillRecord(late, "Zed", 2, 2_000L),
                new DenseRanking.KillRecord(early, "Alice", 2, 1_000L)
        ));
        assertEquals(2, live.size());
        assertEquals("Alice", live.get(0).name());
        assertEquals(1, live.get(0).place());
        assertEquals("Zed", live.get(1).name());
        assertEquals(2, live.get(1).place());

        // Dense end: both place 1
        List<DenseRanking.Entry> end = DenseRanking.rankDense(List.of(
                new DenseRanking.KillRecord(late, "Zed", 2, 2_000L),
                new DenseRanking.KillRecord(early, "Alice", 2, 1_000L)
        ));
        assertEquals(1, end.get(0).place());
        assertEquals(1, end.get(1).place());
        assertEquals("Alice", end.get(0).name()); // still ordered by first-to-reach
    }

    @Test
    void defaultRankIsDense() {
        List<DenseRanking.Entry> ranked = DenseRanking.rank(List.of(
                new DenseRanking.KillRecord(UUID.randomUUID(), "A", 3),
                new DenseRanking.KillRecord(UUID.randomUUID(), "B", 3)
        ));
        assertEquals(1, ranked.get(0).place());
        assertEquals(1, ranked.get(1).place());
    }

    private static int placeForKills(List<DenseRanking.Entry> ranked, int kills) {
        return ranked.stream().filter(e -> e.kills() == kills).findFirst().orElseThrow().place();
    }
}
