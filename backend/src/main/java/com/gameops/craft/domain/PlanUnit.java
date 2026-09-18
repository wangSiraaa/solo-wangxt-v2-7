package com.gameops.craft.domain;

import java.time.Instant;

/**
 * One synthesis (one unit_no) inside a batch plan.
 *
 * State machine, crash windows:
 *   PENDING -> RUNNING (lease held) -> DEDUCTED (materials consumed, durable) -> DONE (rewards issued)
 *   PENDING/RUNNING -> SKIPPED (plan stop gate: cancel / activity end / earlier shortage)
 *   RUNNING/DEDUCTED -> FAILED (unrecoverable error)
 *
 * A lease may expire (worker crash). The row's status is the recovery anchor:
 *   RUNNING with expired lease and no CONSUME rows  -> restart deduction
 *   DEDUCTED with expired lease and no PRODUCE rows  -> issue the missing reward
 *   DEDUCTED with expired lease and PRODUCE present  -> only rewrite DONE (Tx3 crash window)
 */
public record PlanUnit(
        long id,
        long planId,
        String planNo,
        int unitNo,
        long playerId,
        long recipeId,
        long recipeVersionId,
        String orderNo,
        String status,
        String statusReason,
        String leaseOwner,
        Instant leaseExpiresAt,
        int attempts,
        Instant startedAt,
        Instant deductedAt,
        Instant completedAt,
        Instant createdAt
) {
    public boolean leaseExpired(Instant now) {
        return leaseExpiresAt == null || !leaseExpiresAt.isAfter(now);
    }
}
