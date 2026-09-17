package com.gameops.craft.domain;

import java.time.Instant;
import java.util.List;

/** Immutable snapshot row of recipe_version; inputs/outputs are parsed from JSON. */
public record RecipeVersion(
        long id,
        long recipeId,
        int versionNo,
        String status,
        List<ItemQty> inputs,
        List<ItemQty> outputs,
        Instant startTime,
        Instant endTime,
        int craftTimeoutSeconds,
        Instant publishedAt
) {
    public boolean isOpenAt(Instant now) {
        if (startTime != null && now.isBefore(startTime())) {
            return false;
        }
        return endTime == null || !now.isAfter(endTime);
    }
}
