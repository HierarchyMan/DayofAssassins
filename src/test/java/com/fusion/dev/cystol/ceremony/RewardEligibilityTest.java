package com.fusion.dev.cystol.ceremony;

import com.fusion.dev.cystol.kill.DenseRanking;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardEligibilityTest {

    @Test
    void denseTiesBothEligibleAtPlaceOne() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<DenseRanking.Entry> ranking = DenseRanking.rank(List.of(
                new DenseRanking.KillRecord(a, "Alice", 10),
                new DenseRanking.KillRecord(b, "Bob", 10),
                new DenseRanking.KillRecord(c, "Carol", 5)
        ));
        // places 1,1,2
        List<DenseRanking.Entry> top1 = RewardEligibility.eligible(ranking, 1);
        assertEquals(2, top1.size());
        assertTrue(top1.stream().allMatch(e -> e.place() == 1));

        List<DenseRanking.Entry> top2 = RewardEligibility.eligible(ranking, 2);
        assertEquals(3, top2.size());
    }

    @Test
    void zeroOrEmptyYieldsNothing() {
        assertTrue(RewardEligibility.eligible(List.of(), 3).isEmpty());
        UUID a = UUID.randomUUID();
        List<DenseRanking.Entry> ranking = DenseRanking.rank(List.of(
                new DenseRanking.KillRecord(a, "Alice", 3)
        ));
        assertTrue(RewardEligibility.eligible(ranking, 0).isEmpty());
        assertTrue(RewardEligibility.eligible(ranking, -1).isEmpty());
    }
}
