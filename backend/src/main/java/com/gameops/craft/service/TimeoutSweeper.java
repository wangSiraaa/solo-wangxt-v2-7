package com.gameops.craft.service;

import com.gameops.craft.domain.CraftOrder;
import com.gameops.craft.repo.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releases orders whose preoccupy window expired.
 * Each order is closed in its own transaction: CAS guarantees we never release
 * holds of an order a player committed at the last millisecond.
 */
@Component
public class TimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(TimeoutSweeper.class);

    private final OrderRepository orders;
    private final CraftTxService tx;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public TimeoutSweeper(OrderRepository orders, CraftTxService tx,
                          TransactionTemplate txTemplate, Clock clock) {
        this.orders = orders;
        this.tx = tx;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.sweeper.delay-ms:2000}")
    public void sweep() {
        Instant now = Instant.now(clock);
        List<CraftOrder> expired = orders.listExpiredPreoccupied(now, 50);
        for (CraftOrder order : expired) {
            try {
                Boolean released = txTemplate.execute(s -> tx.timeoutExpiredOrder(order.orderNo()));
                if (Boolean.TRUE.equals(released)) {
                    log.info("order {} timed out, holds released", order.orderNo());
                }
            } catch (Exception e) {
                // Next sweep retries; CAS means no double release.
                log.warn("sweep failed for {}: {}", order.orderNo(), e.getMessage());
            }
        }
    }
}
