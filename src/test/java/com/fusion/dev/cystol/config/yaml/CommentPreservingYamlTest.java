package com.fusion.dev.cystol.config.yaml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * High-value cases only: missing-key merge with comment survival, ordered insert,
 * user-value ownership, and path patch that keeps key comments.
 */
class CommentPreservingYamlTest {

    @TempDir
    Path tmp;

    @Test
    void mergeAddsMissingSubtreeWithDefaultCommentsAndKeepsUserValues() throws Exception {
        String defaults = """
                # top default
                times:
                  start: ""  # empty default
                  end: ""
                # rewards section
                rewards:
                  enabled: true
                  max-place: 3
                ffa:
                  before-end-seconds: 1800
                  # nested addition
                  announce-lead-seconds: 3600
                """;
        String user = """
                # user kept this header
                times:
                  start: "2026/01/01 00:00:00"  # custom start
                  end: "2026/01/02 00:00:00"
                ffa:
                  before-end-seconds: 900
                """;

        Path file = tmp.resolve("config.yml");
        Files.writeString(file, user);

        CommentPreservingYaml.MergeResult result = CommentPreservingYaml.mergeMissing(file, defaults);
        assertTrue(result.rewritten());
        assertTrue(result.keysAdded() >= 2); // rewards + announce-lead-seconds at minimum

        String out = Files.readString(file);
        assertTrue(out.contains("# user kept this header"), "user header comment must survive");
        assertTrue(out.contains("# custom start") || out.contains("custom start"),
                "inline user comment must survive");
        assertTrue(out.contains("2026/01/01 00:00:00"), "user value must not be overwritten");
        assertTrue(out.contains("before-end-seconds: 900"), "user ffa seconds must stay");
        assertTrue(out.contains("rewards:"), "missing section must be added");
        assertTrue(out.contains("max-place"), "nested reward keys must be added");
        assertTrue(out.contains("# rewards section") || out.contains("rewards section"),
                "default section comment should come along");
        assertTrue(out.contains("announce-lead-seconds"), "nested missing key under existing parent");

        @SuppressWarnings("unchecked")
        Map<String, Object> loaded = (Map<String, Object>) CommentPreservingYaml.loadPlain(out);
        @SuppressWarnings("unchecked")
        Map<String, Object> ffa = (Map<String, Object>) loaded.get("ffa");
        assertEquals(900, ((Number) ffa.get("before-end-seconds")).intValue());
        assertEquals(3600, ((Number) ffa.get("announce-lead-seconds")).intValue());
    }

    @Test
    void mergeIsIdempotentWhenAlreadyComplete() throws Exception {
        String yaml = """
                a:
                  b: 1
                c: true
                """;
        Path file = tmp.resolve("full.yml");
        Files.writeString(file, yaml);

        CommentPreservingYaml.MergeResult first = CommentPreservingYaml.mergeMissing(file, yaml);
        assertEquals(0, first.keysAdded());
        assertFalse(first.rewritten());

        String after = Files.readString(file);
        CommentPreservingYaml.MergeResult second = CommentPreservingYaml.mergeMissing(file, yaml);
        assertEquals(0, second.keysAdded());
        assertEquals(after, Files.readString(file));
    }

    @Test
    void sequencesAreAtomicUserOwnsWholeList() throws Exception {
        String defaults = """
                teleport-lock:
                  commands:
                    - spawn
                    - home
                    - NEW_DEFAULT_ONLY
                """;
        String user = """
                teleport-lock:
                  # custom list
                  commands:
                    - spawn
                    - home
                    - warp
                """;
        Path file = tmp.resolve("list.yml");
        Files.writeString(file, user);

        CommentPreservingYaml.MergeResult result = CommentPreservingYaml.mergeMissing(file, defaults);
        assertEquals(0, result.keysAdded());

        String out = Files.readString(file);
        assertTrue(out.contains("warp"));
        assertFalse(out.contains("NEW_DEFAULT_ONLY"),
                "existing sequence keys must not pull in new list items");
        assertTrue(out.contains("# custom list") || out.contains("custom list"));
    }

    @Test
    void patchUpdatesValueButKeepsKeyComments() throws Exception {
        String user = """
                times:
                  # start time UTC
                  start: "old"
                  end: ""
                arena:
                  world: world
                """;
        Path file = tmp.resolve("patch.yml");
        Files.writeString(file, user);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("times.start", "2026/07/01 12:00:00");
        patch.put("arena.world", "event");
        assertTrue(CommentPreservingYaml.patch(file, patch));

        String out = Files.readString(file);
        assertTrue(out.contains("2026/07/01 12:00:00"));
        assertTrue(out.contains("event"));
        assertTrue(out.contains("# start time UTC") || out.contains("start time UTC"),
                "block comment on key must survive value patch");

        Yaml yaml = CommentPreservingYaml.createYaml();
        Node root = CommentPreservingYaml.composeRoot(yaml, out);
        assertTrue(root instanceof MappingNode);
        assertTrue(CommentPreservingYaml.hasPath((MappingNode) root, "times.start"));
        assertTrue(CommentPreservingYaml.countComments(root) >= 1);
    }

    @Test
    void emptyUserFileIsSeededFromDefaults() throws Exception {
        String defaults = """
                # seed
                rewards:
                  enabled: true
                """;
        Path file = tmp.resolve("empty.yml");
        Files.writeString(file, "");

        CommentPreservingYaml.MergeResult result = CommentPreservingYaml.mergeMissing(file, defaults);
        assertTrue(result.rewritten());
        assertTrue(result.keysAdded() > 0);
        String out = Files.readString(file);
        assertTrue(out.contains("rewards:"));
        assertTrue(out.contains("# seed") || out.contains("seed"));
    }

    @Test
    void managedYamlRegistryListsUserFacingFilesOnly() {
        assertEquals(List.of("config.yml", "lang.yml"), ManagedYamlFiles.ALL);
        assertFalse(ManagedYamlFiles.ALL.contains("paper-plugin.yml"));
    }
}
