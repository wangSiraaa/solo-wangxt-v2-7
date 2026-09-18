package com.gameops.craft.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Human-sortable business document numbers: prefix + yyMMdd + random/nano tail.
 * 20 chars total (matches CHAR(20) columns).
 *
 * The tail mixes a SecureRandom value with a process-wide monotonic counter and the current
 * nanoTime so that concurrent calls within the same millisecond (e.g. many batch units claimed
 * by parallel workers) do not collide on the unique order_no / ref_no constraints.
 */
public final class DocNumbers {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC);
    private static final AtomicLong SEQ = new AtomicLong();

    private DocNumbers() {}

    public static String next(String prefix, Clock clock) {
        StringBuilder sb = new StringBuilder(prefix).append(DATE.format(Instant.now(clock)));
        int tail = 20 - sb.length();
        long entropy = RANDOM.nextLong()
                ^ System.nanoTime()
                ^ SEQ.getAndIncrement() * 0x9E3779B97F4A7C15L;
        for (int i = 0; i < tail; i++) {
            sb.append(ALPHABET[(int) Math.floorMod(entropy, ALPHABET.length)]);
            entropy /= ALPHABET.length;
            if (i % 4 == 3) {
                entropy ^= RANDOM.nextLong();
            }
        }
        return sb.toString();
    }
}
