package com.fusion.dev.cystol.command;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Paper failure mode: handler has an action but Brigadier never registered it.
 */
class AdminCommandsSyncTest {

    @Test
    void everyHandlerActionIsABrigadierFirstTokenOrSet() {
        Set<String> first = new HashSet<>(AdminCommands.ALL_FIRST_TOKENS);
        for (String action : AdminCommands.HANDLER_ADMIN_ACTIONS) {
            assertTrue(first.contains(action),
                    "Handler action missing from Brigadier first-token list: " + action
                            + " — add to AdminCommands + PaperCommandRegistrar");
        }
    }

    @Test
    void setkillsIsRegisteredAsArgLeaf() {
        assertTrue(AdminCommands.LEAF_WITH_ARGS.contains("setkills"));
        assertTrue(AdminCommands.ALL_FIRST_TOKENS.contains("setkills"));
        assertTrue(AdminCommands.HANDLER_ADMIN_ACTIONS.contains("setkills"));
    }

    @Test
    void noArgLeavesAndArgLeavesPartitionFirstTokens() {
        Set<String> union = new HashSet<>(AdminCommands.LEAF_NO_ARGS);
        union.addAll(AdminCommands.LEAF_WITH_ARGS);
        assertEquals(union, new HashSet<>(AdminCommands.ALL_FIRST_TOKENS));
        // no overlap
        for (String s : AdminCommands.LEAF_NO_ARGS) {
            assertTrue(!AdminCommands.LEAF_WITH_ARGS.contains(s), "overlap: " + s);
        }
    }

    @Test
    void phaseAndSetTargetsNonEmpty() {
        assertTrue(AdminCommands.PHASES.contains("hunt"));
        assertTrue(AdminCommands.SET_TARGETS.contains("spawnpos1"));
        assertTrue(AdminCommands.SET_TARGETS.contains("starttime"));
    }
}
