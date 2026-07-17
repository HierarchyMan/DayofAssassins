package com.fusion.dev.cystol.ceremony;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CeremonyPlaceTest {

    @Test
    void rankedUsesDensePlace() {
        assertEquals(1, CeremonyPlace.displayPlace(true, 1, 5));
        assertEquals(2, CeremonyPlace.displayPlace(true, 2, 5));
        assertTrue(CeremonyPlace.isTopThreeMedal(1));
        assertTrue(CeremonyPlace.isTopThreeMedal(3));
        assertFalse(CeremonyPlace.isTopThreeMedal(4));
    }

    @Test
    void unrankedNeverGetsMedalPlacesEvenWithOneScorer() {
        // One scorer → maxPlace=1; zero-kill online must not become place 2 (silver)
        int place = CeremonyPlace.displayPlace(false, 0, 1);
        assertEquals(CeremonyPlace.UNRANKED_FLOOR, place);
        assertFalse(CeremonyPlace.isTopThreeMedal(place));
    }

    @Test
    void unrankedEmptyRankingUsesPlaceOther() {
        int place = CeremonyPlace.displayPlace(false, 0, 0);
        assertEquals(CeremonyPlace.UNRANKED_FLOOR, place);
        assertFalse(CeremonyPlace.isTopThreeMedal(place));
    }

    @Test
    void unrankedAfterManyScorersStillAboveMedals() {
        assertEquals(6, CeremonyPlace.displayPlace(false, 0, 5));
        assertFalse(CeremonyPlace.isTopThreeMedal(6));
    }
}
