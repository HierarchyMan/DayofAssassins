package com.fusion.dev.cystol.teleport;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory "nobypass" mode: when active, {@code preciv.teleport.bypass} and
 * {@code preciv.spawn.rtp.bypass} are ignored so staff are subject to hunt TP lock
 * and spawn-world RTP like everyone else.
 *
 * <p>Not persisted. Initial value comes from {@code nobypass.enabled-at-start}.
 * Toggle with {@code /preciv nobypass}.
 */
public final class NoBypassService {

    private final AtomicBoolean active;

    public NoBypassService(boolean enabledAtStart) {
        this.active = new AtomicBoolean(enabledAtStart);
    }

    public boolean isActive() {
        return active.get();
    }

    /** Flip state; returns the new value. */
    public boolean toggle() {
        while (true) {
            boolean cur = active.get();
            if (active.compareAndSet(cur, !cur)) {
                return !cur;
            }
        }
    }

    public void setActive(boolean value) {
        active.set(value);
    }
}
