package com.fusion.dev.cystol.command;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrecivSuggestionsTest {

    private static final Instant NOW =
            LocalDateTime.of(2026, 7, 17, 15, 30, 0).toInstant(ZoneOffset.UTC);

    @Test
    void rootSuggestsAllWhenPermitted() {
        List<String> s = PrecivSuggestions.complete("", true, true, true, NOW);
        assertEquals(List.of("compass", "killtop", "admin"), s);
    }

    @Test
    void rootFiltersByPermission() {
        assertEquals(List.of("compass"), PrecivSuggestions.complete("", true, false, false, NOW));
        assertEquals(List.of("killtop"), PrecivSuggestions.complete("kill", false, true, false, NOW));
        assertEquals(List.of("admin"), PrecivSuggestions.complete("ad", false, false, true, NOW));
        assertTrue(PrecivSuggestions.complete("", false, false, false, NOW).isEmpty());
    }

    @Test
    void adminSubcommands() {
        List<String> s = PrecivSuggestions.complete("admin ", true, true, true, NOW);
        assertEquals(List.of("admin set", "admin wand"), s);

        List<String> w = PrecivSuggestions.complete("admin w", true, true, true, NOW);
        assertEquals(List.of("admin wand"), w);
    }

    @Test
    void adminDeniedProducesNothingPastRoot() {
        assertTrue(PrecivSuggestions.complete("admin ", true, true, false, NOW).isEmpty());
    }

    @Test
    void setTargetsPreserveHead() {
        List<String> s = PrecivSuggestions.complete("admin set ", true, true, true, NOW);
        assertTrue(s.contains("admin set starttime"));
        assertTrue(s.contains("admin set endtime"));
        assertTrue(s.contains("admin set ffatime"));
        assertTrue(s.contains("admin set centerspawn"));
        assertTrue(s.contains("admin set pos1"));
        assertTrue(s.contains("admin set pos2"));
        assertEquals(6, s.size());

        List<String> partial = PrecivSuggestions.complete("admin set st", true, true, true, NOW);
        assertEquals(List.of("admin set starttime"), partial);
    }

    @Test
    void starttimeSuggestsDateThenTime() {
        String date = DateTimeFormatter.ofPattern("yyyy/MM/dd")
                .format(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        String time = DateTimeFormatter.ofPattern("HH:mm:ss")
                .format(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        List<String> dateOpts = PrecivSuggestions.complete("admin set starttime ", true, true, true, NOW);
        assertEquals(List.of("admin set starttime " + date), dateOpts);

        List<String> timeOpts = PrecivSuggestions.complete(
                "admin set starttime " + date + " ", true, true, true, NOW);
        assertTrue(timeOpts.contains("admin set starttime " + date + " " + time));
        assertTrue(timeOpts.contains("admin set starttime " + date + " 00:00:00"));
        assertTrue(timeOpts.contains("admin set starttime " + date + " 12:00:00"));
        assertTrue(timeOpts.contains("admin set starttime " + date + " 18:00:00"));
    }

    @Test
    void ffatimeSuggestsClearAndDate() {
        String date = DateTimeFormatter.ofPattern("yyyy/MM/dd")
                .format(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        List<String> s = PrecivSuggestions.complete("admin set ffatime ", true, true, true, NOW);
        assertTrue(s.contains("admin set ffatime clear"));
        assertTrue(s.contains("admin set ffatime " + date));
        assertEquals(2, s.size());
    }

    @Test
    void noArgsAfterWandOrPos() {
        assertTrue(PrecivSuggestions.complete("admin wand ", true, true, true, NOW).isEmpty());
        assertTrue(PrecivSuggestions.complete("admin set pos1 ", true, true, true, NOW).isEmpty());
        assertTrue(PrecivSuggestions.complete("admin set centerspawn ", true, true, true, NOW).isEmpty());
    }

    @Test
    void unknownRootHasNoDeeperSuggestions() {
        assertTrue(PrecivSuggestions.complete("nope ", true, true, true, NOW).isEmpty());
    }

    @Test
    void partialRootFilter() {
        List<String> s = PrecivSuggestions.complete("co", true, true, true, NOW);
        assertEquals(List.of("compass"), s);
        assertFalse(s.contains("killtop"));
    }
}
