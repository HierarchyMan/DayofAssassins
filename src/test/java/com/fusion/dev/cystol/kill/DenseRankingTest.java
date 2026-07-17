package com.fusion.dev.cystol.kill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DenseRankingTest {

    @Test
    void sharedFirstThenSecondNotThird() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<DenseRanking.Entry> ranked = DenseRanking.rank(List.of(
                new DenseRanking.KillRecord(a, "Alice", 10),
                new DenseRanking.KillRecord(b, "Bob", 10),
                new DenseRanking.KillRecord(c, "Cara", 8)
        ));
        assertEquals(3, ranked.size());
        // both 10 are place 1
        assertEquals(1, ranked.stream().filter(e -> e.kills() == 10).findFirst().orElseThrow().place());
        assertEquals(2, ranked.stream().filter(e -> e.kills() == 10).count());
        assertEquals(2, ranked.stream().filter(e -> e.kills() == 8).findFirst().orElseThrow().place());
    }

    @Test
    void multiTierDensePlaces() {
        List<DenseRanking.Entry> ranked = DenseRanking.rank(List.of(
                new DenseRanking.KillRecord(UUID.randomUUID(), "A", 10),
                new DenseRanking.KillRecord(UUID.randomUUID(), "B", 10),
                new DenseRanking.KillRecord(UUID.randomUUID(), "C", 8),
                new DenseRanking.KillRecord(UUID.randomUUID(), "D", 8),
                new DenseRanking.KillRecord(UUID.randomUUID(), "E", 5)
        ));
        assertEquals(1, placeForKills(ranked, 10));
        assertEquals(2, placeForKills(ranked, 8));
        assertEquals(3, placeForKills(ranked, 5));
    }

    private static int placeForKills(List<DenseRanking.Entry> ranked, int kills) {
        return ranked.stream().filter(e -> e.kills() == kills).findFirst().orElseThrow().place();
    }
}
