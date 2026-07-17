package com.fusion.dev.cystol.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UTC wall times ({@code yyyy/MM/dd HH:mm:ss}) and relative offsets from now
 * ({@code -r 1d2h5m8s}, missing units treated as 0).
 */
public final class TimeUtil {

    public static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    /**
     * Relative duration: optional d/h/m/s tokens, any order is not allowed — fixed order d then h then m then s,
     * each optional, at least one required. Examples: {@code 1d}, {@code 2h30m}, {@code 1d2h5m8s}, {@code 90s}.
     */
    private static final Pattern RELATIVE = Pattern.compile(
            "^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$",
            Pattern.CASE_INSENSITIVE
    );

    private TimeUtil() {
    }

    public static Optional<Instant> parseUtc(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(raw.trim(), FORMAT);
            return Optional.of(ldt.toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static String formatUtc(Instant instant) {
        return FORMAT.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * Parse admin/GUI time input.
     * <ul>
     *   <li>{@code -r 1d2h5m} / {@code -r1d} — duration from {@code now}</li>
     *   <li>{@code yyyy/MM/dd HH:mm:ss} — absolute UTC</li>
     * </ul>
     * Unspecified relative units are 0 (so {@code 1d} = exactly 24h from now, 0s).
     */
    public static Optional<Instant> parseScheduleInput(String raw, Instant now) {
        if (raw == null || raw.isBlank() || now == null) {
            return Optional.empty();
        }
        String s = raw.trim();
        boolean relative = false;
        if (s.regionMatches(true, 0, "-r", 0, 2)) {
            relative = true;
            s = s.substring(2).trim();
        }
        if (s.isEmpty()) {
            return Optional.empty();
        }
        if (relative) {
            return parseRelativeDuration(s).map(now::plus);
        }
        // Absolute may be "date time" or single token
        return parseUtc(s);
    }

    /**
     * Parse relative duration string without {@code -r} prefix.
     */
    public static Optional<Duration> parseRelativeDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = RELATIVE.matcher(s);
        if (!m.matches()) {
            return Optional.empty();
        }
        // Need at least one group non-null
        if (m.group(1) == null && m.group(2) == null && m.group(3) == null && m.group(4) == null) {
            return Optional.empty();
        }
        long days = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long hours = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long minutes = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        long seconds = m.group(4) == null ? 0 : Long.parseLong(m.group(4));
        long total = days * 86_400L + hours * 3_600L + minutes * 60L + seconds;
        if (total < 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(total));
    }

    /**
     * Join remaining command args into a time string, detecting {@code -r}.
     * Examples: {@code ["-r","1d2h"]}, {@code ["-r1d"]}, {@code ["2026/07/17","14:00:00"]}.
     */
    public static Optional<Instant> parseCommandTimeArgs(String[] args, int fromIndex, Instant now) {
        if (args == null || fromIndex >= args.length) {
            return Optional.empty();
        }
        StringBuilder b = new StringBuilder();
        for (int i = fromIndex; i < args.length; i++) {
            if (i > fromIndex) {
                b.append(' ');
            }
            b.append(args[i]);
        }
        return parseScheduleInput(b.toString(), now);
    }

    /**
     * Human countdown: omits zero high units (e.g. 2d 3h 15m, 45s).
     */
    public static String formatCountdown(long totalSeconds) {
        if (totalSeconds < 0) {
            totalSeconds = 0;
        }
        long days = TimeUnit.SECONDS.toDays(totalSeconds);
        long hours = TimeUnit.SECONDS.toHours(totalSeconds) % 24;
        long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append('d');
        }
        if (hours > 0 || days > 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(hours).append('h');
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(minutes).append('m');
        }
        if (days == 0 && hours == 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(seconds).append('s');
        } else if (minutes == 0 && seconds > 0 && days == 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(seconds).append('s');
        }
        if (sb.isEmpty()) {
            return "0s";
        }
        return sb.toString();
    }
}
