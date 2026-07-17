package com.fusion.dev.cystol.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeUtilTest {

    @Test
    void parseAndFormatRoundTrip() {
        String raw = "2026/07/17 15:30:00";
        Instant instant = TimeUtil.parseUtc(raw).orElseThrow();
        assertEquals(raw, TimeUtil.formatUtc(instant));
    }

    @Test
    void countdownFormatsDaysHoursMinutes() {
        long secs = 2 * 86400 + 3 * 3600 + 15 * 60 + 10;
        String s = TimeUtil.formatCountdown(secs);
        assertTrue(s.contains("2d"));
        assertTrue(s.contains("3h"));
        assertTrue(s.contains("15m"));
    }

    @Test
    void countdownSecondsOnly() {
        assertEquals("45s", TimeUtil.formatCountdown(45));
    }
}
