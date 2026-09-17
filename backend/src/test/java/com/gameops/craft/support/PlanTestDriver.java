package com.gameops.craft.support;

import com.gameops.craft.domain.CraftPlan;
import com.gameops.craft.service.PlanService;
import com.gameops.craft.service.PlanUnitTxService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Test/deterministic driver for batch-plan units. In production the scheduled
 * PlanWorker performs exactly these steps; tests drive them manually so crash
 * windows can be injected between deduct/reward/writeback and so two workers
 * can be simulated with explicit owner/token pairs.
 */
@Component
public class PlanTestDriver {

    private final PlanService planService;

    public PlanTestDriver(PlanService planService) {
        this.planService = planService;
    }

    public static String owner(String name) {
        return "test-" + name;
    }

    public static String token() {
        return UUID.randomUUID().toString();
    }

    /** Claim the next unit for an explicit worker identity. */
    public PlanUnitTxService.ClaimResult claim(CraftPlan plan, String owner, String token) {
        return planService.claim(plan.id(), owner, token);
    }

    /** Full deduct -> reward -> writeback sequence for one claimed unit. */
    public PlanService.UnitOutcome execute(CraftPlan plan, PlanUnitTxService.ClaimResult claim,
                                           String owner, String token) {
        return planService.executeUnit(plan.id(), claim.unit().id(), owner, token);
    }

    public long leaseSeconds() {
        return planService.leaseSeconds();
    }
}
