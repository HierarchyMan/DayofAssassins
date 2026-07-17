package com.fusion.dev.cystol.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * UTC+0 time parse/format for admin times: yyyy/MM/dd HH:mm:ss
 */
public final class TimeUtil {

    public static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

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
