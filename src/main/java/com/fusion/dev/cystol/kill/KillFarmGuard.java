package com.fusion.dev.cystol.kill;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory anti-farm: same killer vs same victim may only receive
 * {@code maxCredits} credited kills inside a sliding time window.
 * No DB — safe mid-event.
 */
public final class KillFarmGuard {

    private final ConcurrentHashMap<String, ArrayDeque<Long>> stamps = new ConcurrentHashMap<>();

    public static String pairKey(UUID killer, UUID victim) {
        if (killer == null || victim == null) {
            return "";
        }
        return killer + ":" + victim;
    }

    /**
     * Pure gate for tests.
     *
     * @param existingStampsMs prior credit times (mutated: pruned + maybe appended)
     * @return true if this kill may be credited
     */
    public static boolean allowCreditPure(
            ArrayDeque<Long> existingStampsMs,
            long nowMs,
            long windowMs,
            int maxCredits
    ) {
        if (maxCredits <= 0 || windowMs <= 0) {
            return true; // misconfig = do not farm-block
        }
        long cutoff = nowMs - windowMs;
        Iterator<Long> it = existingStampsMs.iterator();
        while (it.hasNext()) {
            Long t = it.next();
            if (t == null || t < cutoff) {
                it.remove();
            }
        }
        if (existingStampsMs.size() >= maxCredits) {
            return false;
        }
        existingStampsMs.addLast(nowMs);
        return true;
    }

    /**
     * @return true if credit is allowed (and this attempt was recorded)
     */
    public boolean tryRegisterCredit(UUID killer, UUID victim, int maxCredits, long windowMs) {
        return tryRegisterCredit(killer, victim, maxCredits, windowMs, System.currentTimeMillis());
    }

    public boolean tryRegisterCredit(UUID killer, UUID victim, int maxCredits, long windowMs, long nowMs) {
        if (killer == null || victim == null || killer.equals(victim)) {
            return true;
        }
        if (maxCredits <= 0 || windowMs <= 0) {
            return true;
        }
        String key = pairKey(killer, victim);
        ArrayDeque<Long> q = stamps.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            boolean ok = allowCreditPure(q, nowMs, windowMs, maxCredits);
            if (q.isEmpty()) {
                stamps.remove(key, q);
            }
            return ok;
        }
    }

    /**
     * Milliseconds until the oldest stamp in-window expires (0 if allowed now / empty).
     */
    public long remainingBlockMs(UUID killer, UUID victim, int maxCredits, long windowMs) {
        return remainingBlockMs(killer, victim, maxCredits, windowMs, System.currentTimeMillis());
    }

    public long remainingBlockMs(UUID killer, UUID victim, int maxCredits, long windowMs, long nowMs) {
        if (killer == null || victim == null || maxCredits <= 0 || windowMs <= 0) {
            return 0L;
        }
        String key = pairKey(killer, victim);
        ArrayDeque<Long> q = stamps.get(key);
        if (q == null) {
            return 0L;
        }
        synchronized (q) {
            long cutoff = nowMs - windowMs;
            while (!q.isEmpty() && (q.peekFirst() == null || q.peekFirst() < cutoff)) {
                q.pollFirst();
            }
            if (q.size() < maxCredits || q.isEmpty()) {
                return 0L;
            }
            Long oldest = q.peekFirst();
            if (oldest == null) {
                return 0L;
            }
            return Math.max(0L, (oldest + windowMs) - nowMs);
        }
    }

    public void clear() {
        stamps.clear();
    }

    public void clearPlayer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        String id = uuid.toString();
        stamps.keySet().removeIf(k -> k.startsWith(id + ":") || k.endsWith(":" + id));
    }
}
