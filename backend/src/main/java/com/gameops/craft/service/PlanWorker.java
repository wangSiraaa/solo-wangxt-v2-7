package com.gameops.craft.service;

import com.gameops.craft.domain.CraftPlan;
import com.gameops.craft.repo.PlanRepository;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background batch-plan worker. Multiple instances (threads/processes/pods) may
 * run concurrently against the same MySQL: every unit handoff is a DB lease
 * (status CAS + lease_expires_at), never a JVM lock. A crashed instance holds no
 * in-memory state; after its lease expires another instance claims the unit and
 * resumes from its durable deduct/reward checkpoints.
 *
 * Each unit executes deduct -> reward -> writeback as separate transactions
 * (see PlanService), so a kill -9 at any point is recoverable without double
 * material deduction or double reward issuance.
 */
@Component
public class PlanWorker {

    private static final Logger log = LoggerFactory.getLogger(PlanWorker.class);

    private final PlanRepository plans;
    private final PlanService planService;
    private final boolean enabled;
    private final int maxPlansPerTick;

    private final String instanceId;
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public PlanWorker(PlanRepository plans, PlanService planService,
                      @Value("${app.plan.worker.enabled:true}") boolean enabled,
                      @Value("${app.plan.worker.max-plans-per-tick:20}") int maxPlansPerTick) {
        this.plans = plans;
        this.planService = planService;
        this.enabled = enabled;
        this.maxPlansPerTick = maxPlansPerTick;
        this.instanceId = buildInstanceId();
    }

    private static String buildInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String instanceId() {
        return instanceId;
    }

    @Scheduled(fixedDelayString = "${app.plan.worker.delay-ms:1000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        // Single-JVM guard only to coalesce scheduler ticks; correctness never
        // depends on it (cross-process safety is the DB lease).
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            // Only RUNNING plans can carry work; recovery of expired leases is
            // also served here (a crashed worker leaves its plan RUNNING).
            List<CraftPlan> active = plans.listRecent(maxPlansPerTick).stream()
                    .filter(p -> "RUNNING".equals(p.status()))
                    .toList();
            for (CraftPlan plan : active) {
                drainPlan(plan);
            }
        } catch (Exception e) {
            log.warn("plan worker tick failed: {}", e.getMessage());
        } finally {
            ticking.set(false);
        }
    }

    /** Process as many units of one plan as are immediately claimable. */
    void drainPlan(CraftPlan plan) {
        int guard = 0;
        while (guard++ < plan.totalCount()) {
            String token = UUID.randomUUID().toString();
            PlanUnitTxService.ClaimResult claim;
            try {
                claim = planService.claim(plan.id(), instanceId, token);
            } catch (Exception e) {
                log.warn("plan {} claim failed: {}", plan.planNo(), e.getMessage());
                return;
            }
            if (claim.unit() == null) {
                // NO_PENDING / STOPPED / PLAN_TERMINAL / transient contention -> done for now
                return;
            }
            try {
                PlanService.UnitOutcome outcome = planService.executeUnit(
                        plan.id(), claim.unit().id(), instanceId, token);
                log.debug("plan {} unit {} -> {}", plan.planNo(), claim.unit().unitNo(),
                        outcome.result());
            } catch (Exception e) {
                // Lease remains; retry after expiry moves it to another/this worker.
                log.warn("plan {} unit {} execution error: {}", plan.planNo(),
                        claim.unit().unitNo(), e.toString());
                return;
            }
        }
    }
}
