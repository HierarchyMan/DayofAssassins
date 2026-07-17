package com.fusion.dev.cystol.ceremony;

import com.fusion.dev.cystol.kill.DenseRanking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dense-rank reward filter: eligible when {@code 1 <= place <= maxPlace}.
 * Ties share place, so two #1s both qualify when {@code maxPlace >= 1}.
 */
public final class RewardEligibility {

    private RewardEligibility() {
    }

    /**
     * @param ranking dense ranking (any order)
     * @param maxPlace highest place that receives a reward; {@code <= 0} yields empty
     */
    public static List<DenseRanking.Entry> eligible(List<DenseRanking.Entry> ranking, int maxPlace) {
        Objects.requireNonNull(ranking, "ranking");
        if (maxPlace <= 0 || ranking.isEmpty()) {
            return List.of();
        }
        List<DenseRanking.Entry> out = new ArrayList<>();
        for (DenseRanking.Entry e : ranking) {
            if (e.place() >= 1 && e.place() <= maxPlace) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }
}
