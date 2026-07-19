package com.fusion.dev.cystol.kill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Kill leaderboard ranking.
 *
 * <ul>
 *   <li>{@link PlaceMode#COMPETITION} (live HUD): unique places 1,2,3…;
 *       ties broken by who reached their kill count first.</li>
 *   <li>{@link PlaceMode#DENSE} (event end / archive): same kill count shares place;
 *       next place is previous+1 (not skip-by-count). Example: 10,10,8 → 1,1,2.</li>
 * </ul>
 *
 * Sort always: kills DESC, then {@code reachedAtMs} ASC (earlier first), then name, uuid.
 */
public final class DenseRanking {

    public enum PlaceMode {
        /** Live display: first to reach stays ahead; places are 1..n unique. */
        COMPETITION,
        /** Ceremony / history: ties share place. */
        DENSE
    }

    public record Entry(UUID uuid, String name, int kills, int place) {
    }

    /**
     * @param reachedAtMs wall millis when this kill count was reached (0 = unknown / last)
     */
    public record KillRecord(UUID uuid, String name, int kills, long reachedAtMs) {
        public KillRecord(UUID uuid, String name, int kills) {
            this(uuid, name, kills, 0L);
        }
    }

    private DenseRanking() {
    }

    /** Dense shared-place ranking (ceremony / past-game archive). */
    public static List<Entry> rank(List<KillRecord> records) {
        return rank(records, PlaceMode.DENSE);
    }

    public static List<Entry> rankCompetition(List<KillRecord> records) {
        return rank(records, PlaceMode.COMPETITION);
    }

    public static List<Entry> rankDense(List<KillRecord> records) {
        return rank(records, PlaceMode.DENSE);
    }

    public static List<Entry> rank(List<KillRecord> records, PlaceMode mode) {
        Objects.requireNonNull(records, "records");
        PlaceMode placeMode = mode == null ? PlaceMode.DENSE : mode;

        List<KillRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator
                .comparingInt(KillRecord::kills).reversed()
                .thenComparingLong(r -> r.reachedAtMs() <= 0L ? Long.MAX_VALUE : r.reachedAtMs())
                .thenComparing(r -> r.name() == null ? "" : r.name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.uuid().toString()));

        if (placeMode == PlaceMode.COMPETITION) {
            List<Entry> out = new ArrayList<>(sorted.size());
            for (int i = 0; i < sorted.size(); i++) {
                KillRecord r = sorted.get(i);
                out.add(new Entry(r.uuid(), r.name(), r.kills(), i + 1));
            }
            return out;
        }

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
}
