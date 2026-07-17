package com.fusion.dev.cystol.ceremony;

/**
 * Display place for end ceremony titles.
 * Players with kill rows use dense rank; unranked players never get places 1–3
 * (avoids SILVER/#2 + heroic FX when only one scorer exists).
 */
public final class CeremonyPlace {

    /** Minimum place number that maps to {@code end.title.place-other} (not gold/silver/bronze). */
    public static final int UNRANKED_FLOOR = 4;

    private CeremonyPlace() {
    }

    /**
     * @param rankedPlace dense place if the player has a kill row; ignored when {@code hasRankEntry} is false
     * @param hasRankEntry true when the player appears in the frozen ranking
     * @param maxRankedPlace highest place among ranked entries (0 if ranking empty)
     */
    public static int displayPlace(boolean hasRankEntry, int rankedPlace, int maxRankedPlace) {
        if (hasRankEntry) {
            return rankedPlace;
        }
        // No kills row: always place-other styling (never 1–3 medals)
        if (maxRankedPlace <= 0) {
            return UNRANKED_FLOOR;
        }
        return Math.max(UNRANKED_FLOOR, maxRankedPlace + 1);
    }

    public static boolean isTopThreeMedal(int place) {
        return place >= 1 && place <= 3;
    }
}
