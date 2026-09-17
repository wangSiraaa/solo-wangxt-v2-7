package com.gameops.craft.domain;

import java.time.Instant;

/**
 * One craft position inside a batch plan. State machine:
 *
 *   PENDING --(lease)--> LEASED --(deduct ok, reward ok, writeback)--> DONE
 *      |                    |
 *      |                    +-- crash at any stage; after lease expiry another
 *      |                    |   worker reclaims and resumes from durable state:
 *      |                    |   order PREOCCUPIED -> commit (never deduct again)
 *      |                    |   order COMMITTED    -> only write back (never reward again)
 *      |                    +-- unrecoverable failure --> FAILED
 *      |
 *      +-- plan stop/cancel (only while still not started) --> SKIPPED
 *
 * Each unit owns at most one craft_order (unique column) and that order's ledger
 * unique keys are the final at-most-once anchors for CONSUME / PRODUCE rows.
 */
public record CraftPlanUnit(
        long id,
        long planId,
        String planNo,
        int unitNo,
        String status,
        String leaseOwner,
        String leaseToken,
        Instant leasedAt,
        Instant leaseExpiresAt,
        int attempts,
        String orderNo,
        Instant consumedAt,
        Instant rewardedAt,
        Instant finishedAt,
        String skipReason,
        String failReason,
        Instant createdAt
) {
    public boolean leaseExpired(Instant now) {
        return leaseExpiresAt != null && !now.isBefore(leaseExpiresAt);
    }
}
