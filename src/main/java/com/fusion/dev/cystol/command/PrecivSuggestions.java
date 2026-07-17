package com.fusion.dev.cystol.command;

import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full tab-completion candidates for {@code /preciv} (and alias {@code /doa}).
 * <p>
 * Returns <em>full remaining</em> strings suitable for Brigadier greedy-string
 * suggestions (prior tokens must be preserved, or earlier args get wiped).
 */
public final class PrecivSuggestions {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final List<String> ROOT = List.of("compass", "killtop", "admin");
    private static final List<String> ADMIN = List.of("set", "wand");
    private static final List<String> SET = List.of(
            "starttime", "endtime", "ffatime", "centerspawn", "pos1", "pos2"
    );
    private static final List<String> TIME_SAMPLES = List.of("00:00:00", "12:00:00", "18:00:00");

    private PrecivSuggestions() {
    }

    public static List<String> complete(CommandSender sender, String remaining) {
        return complete(
                remaining,
                sender.hasPermission("preciv.compass"),
                sender.hasPermission("preciv.killtop"),
                sender.hasPermission("preciv.admin"),
                Instant.now()
        );
    }

    /**
     * Pure completion for tests. {@code now} drives UTC date/time sample suggestions.
     */
    public static List<String> complete(
            String remaining,
            boolean canCompass,
            boolean canKilltop,
            boolean canAdmin,
            Instant now
    ) {
        String raw = remaining == null ? "" : remaining;
        // Keep trailing empty token when the user typed a trailing space
        String[] parts = raw.isEmpty() ? new String[0] : raw.split("\\s+", -1);

        if (parts.length <= 1) {
            String prefix = parts.length == 0 ? "" : parts[0];
            List<String> out = new ArrayList<>(3);
            for (String root : ROOT) {
                if (!matchesPerm(root, canCompass, canKilltop, canAdmin)) {
                    continue;
                }
                if (startsWithIgnoreCase(root, prefix)) {
                    out.add(root);
                }
            }
            return out;
        }

        String first = parts[0].toLowerCase(Locale.ROOT);
        if (!first.equals("admin") || !canAdmin) {
            return List.of();
        }

        if (parts.length == 2) {
            return withHead(parts, 1, filterPrefix(ADMIN, parts[1]));
        }

        if (!parts[1].equalsIgnoreCase("set")) {
            // admin wand (and anything else) has no further args
            return List.of();
        }

        // admin set …
        if (parts.length == 3) {
            return withHead(parts, 2, filterPrefix(SET, parts[2]));
        }

        String target = parts[2].toLowerCase(Locale.ROOT);
        return switch (target) {
            case "starttime", "endtime" -> timeArgs(parts, now, false);
            case "ffatime" -> timeArgs(parts, now, true);
            default -> List.of(); // centerspawn / pos1 / pos2 / unknown
        };
    }

    private static boolean matchesPerm(String root, boolean canCompass, boolean canKilltop, boolean canAdmin) {
        return switch (root) {
            case "compass" -> canCompass;
            case "killtop" -> canKilltop;
            case "admin" -> canAdmin;
            default -> false;
        };
    }

    private static List<String> timeArgs(String[] parts, Instant now, boolean allowClear) {
        LocalDateTime utc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        String dateSample = DATE.format(utc);
        String timeNow = TIME.format(utc);

        // parts: admin set starttime [date] [time]
        // index: 0     1   2         3      4
        if (parts.length == 4) {
            List<String> opts = new ArrayList<>(2);
            if (allowClear) {
                opts.add("clear");
            }
            opts.add(dateSample);
            return withHead(parts, 3, filterPrefix(opts, parts[3]));
        }
        if (parts.length == 5) {
            if (allowClear && parts[3].equalsIgnoreCase("clear")) {
                return List.of();
            }
            List<String> opts = new ArrayList<>(4);
            opts.add(timeNow);
            for (String sample : TIME_SAMPLES) {
                if (!opts.contains(sample)) {
                    opts.add(sample);
                }
            }
            return withHead(parts, 4, filterPrefix(opts, parts[4]));
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (startsWithIgnoreCase(o, prefix)) {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * Rebuild greedy remaining: tokens[0..tokenIndex-1] + option for tokenIndex.
     */
    private static List<String> withHead(String[] parts, int tokenIndex, List<String> tokenOptions) {
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < tokenIndex; i++) {
            if (i > 0) {
                head.append(' ');
            }
            head.append(parts[i]);
        }
        String headStr = head.toString();
        List<String> out = new ArrayList<>(tokenOptions.size());
        for (String opt : tokenOptions) {
            out.add(headStr.isEmpty() ? opt : headStr + " " + opt);
        }
        return out;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return true;
        }
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
