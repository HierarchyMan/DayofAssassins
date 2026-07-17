package com.fusion.dev.cystol.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend selection labels and strategy isolation (no Bukkit Player required).
 */
class VanishServiceTest {

    @Test
    void essentialsBackendLabelAndNullShortCircuit() {
        AtomicInteger calls = new AtomicInteger();
        VanishService svc = new VanishService(
                Logger.getLogger("test"),
                VanishService.Backend.ESSENTIALS,
                p -> {
                    calls.incrementAndGet();
                    return true;
                }
        );
        assertEquals(VanishService.Backend.ESSENTIALS, svc.backend());
        assertEquals("essentials", svc.backendLabel());
        // null never hits the Essentials strategy
        assertFalse(svc.isVanished(null));
        assertEquals(0, calls.get());
        // Strategy itself is exclusive (what production binds when Essentials is present)
        assertTrue(svc.backend() == VanishService.Backend.ESSENTIALS);
    }

    @Test
    void metadataBackendLabel() {
        VanishService meta = VanishService.forTests(p -> true);
        assertEquals(VanishService.Backend.METADATA, meta.backend());
        assertEquals("metadata", meta.backendLabel());
        assertFalse(meta.isVanished(null));
    }
}
