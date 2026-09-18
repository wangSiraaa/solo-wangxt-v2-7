package com.gameops.craft.domain;

import java.time.Instant;

public record LedgerEntry(
        long id,
        String refNo,
        long playerId,
        String itemCode,
        String entryType,
        long qtyDelta,
        String relatedRef,
        String status,
        String remark,
        String planNo,
        Integer unitNo,
        Instant createdAt
) {}
