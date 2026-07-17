package com.fusion.dev.cystol.event;

public enum EventPhase {
    IDLE,
    /** Schedule frozen — clock does not advance side effects until unpaused. */
    PAUSED,
    COUNTDOWN,
    HUNT,
    FFA,
    ENDED
}
