package com.gameops.craft.domain;

import java.time.Instant;

/**
 * Header of a batch craft plan. The recipe version and the per-unit
 * inputs/outputs snapshots are frozen here at creation time; every unit must
 * finish against that snapshot regardless of later recipe publications.
 */
public record CraftPlan(
        long id,
        String planNo,
        long playerId,
        long recipeId,
        long recipeVersionId,
        int versionNo,
        String recipeCode,
        String recipeName,
        int totalCount,
        String status,
        boolean stopFlag,
        String stopReason,
        int completedCount,
        int skippedCount,
        int failedCount,
        String inputsJson,
        String outputsJson,
        Instant cancelledAt,
        Instant finishedAt,
        Instant createdAt
) {
    public boolean isTerminal() {
        return switch (status) {
            case "COMPLETED", "PARTIAL", "CANCELLED", "FAILED" -> true;
            default -> false;
        };
    }
}
