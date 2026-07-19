package com.fusion.dev.cystol.command;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tab-completion tree: permissions, multi-token heads, time tokens — not string presence.
 */
class PrecivSuggestionsTest {

    private static final Instant NOW =
            LocalDateTime.of(2026, 7, 17, 15, 30, 0).toInstant(ZoneOffset.UTC);

    @Test
    void rootRespectsPermissionsAndPrefix() {
        assertEquals(List.of("compass", "killtop", "gui", "nobypass", "admin"),
                PrecivSuggestions.complete("", true, true, true, NOW));
        assertEquals(List.of("compass"), PrecivSuggestions.complete("", true, false, false, NOW));
        assertEquals(List.of("admin"), PrecivSuggestions.complete("ad", false, false, true, NOW));
        assertEquals(List.of("gui"), PrecivSuggestions.complete("g", false, false, true, NOW));
        assertEquals(List.of("nobypass"), PrecivSuggestions.complete("no", false, false, true, NOW));
        assertTrue(PrecivSuggestions.complete("", false, false, false, NOW).isEmpty());
        assertTrue(PrecivSuggestions.complete("admin ", true, true, false, NOW).isEmpty());
        assertTrue(PrecivSuggestions.complete("nobypass", true, true, false, NOW).isEmpty());
    }

    @Test
    void adminRootListsOpsCommands() {
        List<String> admin = PrecivSuggestions.complete("admin ", true, true, true, NOW);
        assertTrue(admin.stream().anyMatch(s -> s.equals("admin status")));
        assertTrue(admin.stream().anyMatch(s -> s.equals("admin forcetp")));
        assertTrue(admin.stream().anyMatch(s -> s.equals("admin forcespawnrtp")));
        assertTrue(admin.stream().anyMatch(s -> s.equals("admin phase")));
        assertTrue(admin.stream().anyMatch(s -> s.equals("admin set")));
        assertTrue(admin.stream().anyMatch(s -> s.equals("admin clearkills")));
        assertTrue(admin.stream().anyMatch(s -> s.equals("admin wand")));
        assertTrue(admin.stream().anyMatch(s -> s.equals("admin spawnwand")));
    }

    @Test
    void adminPhaseAndClearkillsTokens() {
        List<String> phases = PrecivSuggestions.complete("admin phase ", true, true, true, NOW);
        assertTrue(phases.contains("admin phase hunt"));
        assertTrue(phases.contains("admin phase ffa"));
        assertEquals(List.of("admin phase hunt"),
                PrecivSuggestions.complete("admin phase h", true, true, true, NOW));

        assertEquals(List.of("admin clearkills confirm"),
                PrecivSuggestions.complete("admin clearkills ", true, true, true, NOW));
    }

    @Test
    void adminSetTargetsAndTimeTokens() {
        List<String> targets = PrecivSuggestions.complete("admin set ", true, true, true, NOW);
        assertEquals(8, targets.size());
        assertTrue(targets.stream().allMatch(s -> s.startsWith("admin set ")));
        assertTrue(targets.stream().anyMatch(s -> s.equals("admin set spawnpos1")));
        assertTrue(targets.stream().anyMatch(s -> s.equals("admin set spawnpos2")));
        assertEquals(List.of("admin set starttime"),
                PrecivSuggestions.complete("admin set st", true, true, true, NOW));
        assertEquals(List.of("admin set spawnpos1", "admin set spawnpos2"),
                PrecivSuggestions.complete("admin set spawn", true, true, true, NOW));

        String date = DateTimeFormatter.ofPattern("yyyy/MM/dd")
                .format(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        String time = DateTimeFormatter.ofPattern("HH:mm:ss")
                .format(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        List<String> startOpts = PrecivSuggestions.complete("admin set starttime ", true, true, true, NOW);
        assertTrue(startOpts.contains("admin set starttime " + date));
        assertTrue(startOpts.stream().anyMatch(s -> s.contains("-r")));
        List<String> times = PrecivSuggestions.complete(
                "admin set starttime " + date + " ", true, true, true, NOW);
        assertTrue(times.contains("admin set starttime " + date + " " + time));
        assertTrue(times.contains("admin set starttime " + date + " 00:00:00"));

        List<String> rel = PrecivSuggestions.complete("admin set starttime -r ", true, true, true, NOW);
        assertTrue(rel.stream().anyMatch(s -> s.endsWith("1h") || s.contains("1h")));

        List<String> ffa = PrecivSuggestions.complete("admin set ffatime ", true, true, true, NOW);
        assertTrue(ffa.contains("admin set ffatime clear"));
        assertTrue(ffa.contains("admin set ffatime " + date));
    }
}
