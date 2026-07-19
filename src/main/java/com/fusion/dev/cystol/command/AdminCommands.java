package com.fusion.dev.cystol.command;

import java.util.List;
import java.util.Set;

/**
 * Single source of truth for {@code /preciv admin …} surface area.
 * <p>
 * <b>When adding a command:</b> update these lists <em>and</em> register a real Brigadier
 * node in {@link PaperCommandRegistrar}. Paper only runs what is in that tree — a
 * {@link PrecivCommand} {@code switch} case alone will not run. Tab complete uses these
 * lists; {@code AdminCommandsSyncTest} fails if the handler set drifts from first tokens.
 */
public final class AdminCommands {

    private AdminCommands() {
    }

    /**
     * Zero-arg admin leaves (Brigadier literal → {@code admin <name>} only).
     */
    public static final List<String> LEAF_NO_ARGS = List.of(
            "status",
            "startnow",
            "ffanow",
            "endnow",
            "pause",
            "unpause",
            "forcetp",
            "forcespawnrtp",
            "forceceremony",
            "resetflags",
            "eligible",
            "reload",
            "wand",
            "spawnwand"
    );

    /**
     * Admin leaves that take further tokens (registered with their own argument trees).
     */
    public static final List<String> LEAF_WITH_ARGS = List.of(
            "clearkills",
            "setkills",
            "phase",
            "set"
    );

    /** Full admin first-token list for suggestions / sync checks. */
    public static final List<String> ALL_FIRST_TOKENS;

    static {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(LEAF_NO_ARGS.size() + LEAF_WITH_ARGS.size());
        all.addAll(LEAF_NO_ARGS);
        all.addAll(LEAF_WITH_ARGS);
        ALL_FIRST_TOKENS = List.copyOf(all);
    }

    /** Every admin action {@link PrecivCommand} must handle (including nested {@code set}). */
    public static final Set<String> HANDLER_ADMIN_ACTIONS = Set.of(
            "status", "startnow", "ffanow", "endnow", "pause", "unpause",
            "forcetp", "forcespawnrtp", "forceceremony", "resetflags", "eligible",
            "clearkills", "setkills", "reload", "phase", "wand", "spawnwand", "set"
    );

    public static final List<String> PHASES = List.of(
            "idle", "paused", "countdown", "hunt", "ffa", "ended"
    );

    public static final List<String> SET_TARGETS = List.of(
            "starttime", "ffatime", "endtime", "centerspawn", "pos1", "pos2",
            "spawnpos1", "spawnpos2"
    );
}
