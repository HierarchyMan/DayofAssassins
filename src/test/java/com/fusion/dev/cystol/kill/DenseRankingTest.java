package com.fusion.dev.cystol.kill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Dense place sharing: ties share place; next rank is place+1 (not skip-by-count). */
class DenseRankingTest {

    @Test
    void densePlacesShareAndAdvanceByOne() {
        List<DenseRanking.Entry> ranked = DenseRanking.rank(List.of(
                new DenseRanking.KillRecord(UUID.randomUUID(), "A", 10),
                new DenseRanking.KillRecord(UUID.randomUUID(), "B", 10),
                new DenseRanking.KillRecord(UUID.randomUUID(), "C", 8),
                new DenseRanking.KillRecord(UUID.randomUUID(), "D", 8),
                new DenseRanking.KillRecord(UUID.randomUUID(), "E", 5)
        ));
        assertEquals(5, ranked.size());
        assertEquals(1, placeForKills(ranked, 10));
        assertEquals(2, ranked.stream().filter(e -> e.kills() == 10).count());
        assertEquals(2, placeForKills(ranked, 8));
        assertEquals(3, placeForKills(ranked, 5));
    }

    private static int placeForKills(List<DenseRanking.Entry> ranked, int kills) {
        return ranked.stream().filter(e -> e.kills() == kills).findFirst().orElseThrow().place();
    }
}
