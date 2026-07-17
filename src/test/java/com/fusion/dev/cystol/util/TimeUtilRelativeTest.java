package com.fusion.dev.cystol.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeUtilRelativeTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void relativeOneDayIsExactly24h() {
        Instant t = TimeUtil.parseScheduleInput("-r 1d", NOW).orElseThrow();
        assertEquals(NOW.plus(Duration.ofDays(1)), t);
    }

    @Test
    void relativeComposite() {
        Instant t = TimeUtil.parseScheduleInput("-r 1d2h5m8s", NOW).orElseThrow();
        assertEquals(NOW.plusSeconds(1 * 86400L + 2 * 3600L + 5 * 60L + 8), t);
    }

    @Test
    void relativeAttachedFlag() {
        Instant t = TimeUtil.parseScheduleInput("-r30m", NOW).orElseThrow();
        assertEquals(NOW.plusSeconds(1800), t);
    }

    @Test
    void absoluteStillWorks() {
        Instant t = TimeUtil.parseScheduleInput("2026/07/17 14:18:00", NOW).orElseThrow();
        assertEquals(Instant.parse("2026-07-17T14:18:00Z"), t);
    }

    @Test
    void commandArgsRelative() {
        Instant t = TimeUtil.parseCommandTimeArgs(
                new String[]{"set", "starttime", "-r", "1h"}, 2, NOW
        ).orElseThrow();
        assertEquals(NOW.plusSeconds(3600), t);
    }

    @Test
    void emptyRelativeRejected() {
        assertTrue(TimeUtil.parseRelativeDuration("").isEmpty());
        assertTrue(TimeUtil.parseScheduleInput("-r", NOW).isEmpty());
    }
}
