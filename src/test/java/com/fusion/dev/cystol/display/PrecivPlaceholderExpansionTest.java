package com.fusion.dev.cystol.display;

import com.fusion.dev.cystol.kill.DenseRanking;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Positioned LB placeholder params — pure resolve, no PAPI/Bukkit.
 */
class PrecivPlaceholderExpansionTest {

    @Test
    void topSlotsNameKillsPlace() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<DenseRanking.Entry> ranking = DenseRanking.rank(List.of(
                new DenseRanking.KillRecord(a, "Alice", 10),
                new DenseRanking.KillRecord(b, "Bob", 7)
        ));

        assertEquals("Alice", PrecivPlaceholderExpansion.resolve(
                "top_1_name", null, ranking, u -> 0, "—", "0"));
        assertEquals("10", PrecivPlaceholderExpansion.resolve(
                "top_1_kills", null, ranking, u -> 0, "—", "0"));
        assertEquals("1", PrecivPlaceholderExpansion.resolve(
                "top_1_place", null, ranking, u -> 0, "—", "0"));
        assertEquals("Bob", PrecivPlaceholderExpansion.resolve(
                "top_2_name", null, ranking, u -> 0, "—", "0"));
        assertEquals("7", PrecivPlaceholderExpansion.resolve(
                "top2_kills", null, ranking, u -> 0, "—", "0"));
        assertEquals("—", PrecivPlaceholderExpansion.resolve(
                "top_4_name", null, ranking, u -> 0, "—", "0"));
        assertEquals("0", PrecivPlaceholderExpansion.resolve(
                "top_4_kills", null, ranking, u -> 0, "—", "0"));
        assertEquals("4", PrecivPlaceholderExpansion.resolve(
                "top_4_place", null, ranking, u -> 0, "—", "0"));
        assertNull(PrecivPlaceholderExpansion.resolve(
                "top_0_name", null, ranking, u -> 0, "—", "0"));
        assertNull(PrecivPlaceholderExpansion.resolve(
                "nope", null, ranking, u -> 0, "—", "0"));
    }

    @Test
    void viewerKillsAndPlace() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<DenseRanking.Entry> ranking = DenseRanking.rankCompetition(List.of(
                new DenseRanking.KillRecord(a, "Alice", 10, 1),
                new DenseRanking.KillRecord(b, "Bob", 7, 2)
        ));
        assertEquals("7", PrecivPlaceholderExpansion.resolve(
                "kills", b, ranking, u -> u.equals(b) ? 7 : 0, "—", "0"));
        assertEquals("2", PrecivPlaceholderExpansion.resolve(
                "place", b, ranking, u -> 0, "—", "0"));
        assertEquals("0", PrecivPlaceholderExpansion.resolve(
                "kills", UUID.randomUUID(), ranking, u -> 0, "—", "0"));
        assertEquals("0", PrecivPlaceholderExpansion.resolve(
                "place", UUID.randomUUID(), ranking, u -> 0, "—", "0"));
    }

    @Test
    void resolveTopFieldParsesPrevStyleSuffix() {
        // ensure top field parser used by prev_/game_ still works for live tops
        UUID a = UUID.randomUUID();
        List<DenseRanking.Entry> ranking = List.of(
                new DenseRanking.Entry(a, "Alice", 4, 1)
        );
        assertEquals("Alice", PrecivPlaceholderExpansion.resolve(
                "top1_name", null, ranking, u -> 0, "—", "0"));
        assertEquals("4", PrecivPlaceholderExpansion.resolve(
                "top_1_kills", null, ranking, u -> 0, "—", "0"));
    }
}
