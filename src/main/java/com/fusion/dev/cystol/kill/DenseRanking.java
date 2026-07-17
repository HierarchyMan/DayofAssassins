package com.fusion.dev.cystol.kill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Dense shared-place ranking: ties share place; next place is previous+1 (not skip).
 * Example: 10,10,8 → places 1,1,2
 */
public final class DenseRanking {

    public record Entry(UUID uuid, String name, int kills, int place) {
    }

    private DenseRanking() {
    }

    public static List<Entry> rank(List<KillRecord> records) {
        Objects.requireNonNull(records, "records");
        List<KillRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator
                .comparingInt(KillRecord::kills).reversed()
                .thenComparing(r -> r.name() == null ? "" : r.name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.uuid().toString()));

        List<Entry> out = new ArrayList<>(sorted.size());
        int place = 0;
        int lastKills = Integer.MIN_VALUE;
        boolean first = true;
        for (KillRecord r : sorted) {
            if (first || r.kills() != lastKills) {
                place++;
                lastKills = r.kills();
                first = false;
            }
            out.add(new Entry(r.uuid(), r.name(), r.kills(), place));
        }
        return out;
    }

    public record KillRecord(UUID uuid, String name, int kills) {
    }
}
