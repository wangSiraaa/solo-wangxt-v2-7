package com.gameops.craft.domain;

import java.time.Instant;

/**
 * Batch synthesis plan header. The recipe version and per-unit inputs/outputs are
 * fixed at creation; workers finish every claimed unit against that snapshot even
 * if a new version is published or the activity window closes mid-run.
 */
public record BatchPlan(
        long id,
        String planNo,
        long playerId,
        long recipeId,
        long recipeVersionId,
        int versionNo,
        int totalUnits,
        String inputsJson,
        String outputsJson,
        String status,
        String statusReason,
        boolean stopNewUnits,
        boolean cancelRequested,
        int completedCount,
        int failedCount,
        int skippedCount,
        Instant createdAt,
        Instant finishedAt
) {
    public boolean isTerminal() {
        return switch (status) {
            case "COMPLETED", "PARTIAL", "CANCELLED", "FAILED" -> true;
            default -> false;
        };
    }
}
