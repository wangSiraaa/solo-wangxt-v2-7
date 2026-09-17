package com.gameops.craft.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Human-sortable business document numbers: prefix + yyMMdd + 8 random chars.
 * 20 chars total (matches CHAR(20) columns).
 */
public final class DocNumbers {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC);

    private DocNumbers() {}

    public static String next(String prefix, Clock clock) {
        StringBuilder sb = new StringBuilder(prefix).append(DATE.format(Instant.now(clock)));
        int tail = 20 - sb.length();
        for (int i = 0; i < tail; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
