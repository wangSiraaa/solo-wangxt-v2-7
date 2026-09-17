package com.gameops.craft.domain;

import java.time.Instant;

public record CraftOrder(
        long id,
        String orderNo,
        long playerId,
        long recipeId,
        long recipeVersionId,
        int versionNo,
        String recipeCode,
        String recipeName,
        String status,
        String statusReason,
        Instant preoccupyDeadline,
        Instant committedAt,
        Instant closedAt,
        String revokeRefNo,
        Instant createdAt
) {}
