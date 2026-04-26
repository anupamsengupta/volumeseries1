package com.quickysoft.power.volume.service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * High-performance UUID v4 generator using {@link ThreadLocalRandom}.
 * <p>
 * {@code UUID.randomUUID()} uses {@code SecureRandom}, which acquires a lock
 * and reads system entropy (~1–3 μs per call). For domain objects where
 * cryptographic randomness is unnecessary (interval IDs, series IDs),
 * this generator produces valid v4 UUIDs at ~50 ns per call — a 20–60× speedup.
 * <p>
 * The generated UUIDs conform to RFC 4122 variant 2, version 4 (random).
 */
public final class FastUUID {

    private FastUUID() {}

    /**
     * Generate a random UUID v4 using ThreadLocalRandom (no lock, no system entropy).
     */
    public static UUID generate() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long msb = rng.nextLong();
        long lsb = rng.nextLong();

        // Set version 4 (bits 12-15 of msb)
        msb = (msb & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        // Set variant 2 (bits 62-63 of lsb)
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

        return new UUID(msb, lsb);
    }
}
