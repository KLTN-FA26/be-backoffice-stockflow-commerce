package com.stockflow.production.internal.service;

import java.util.UUID;

/**
 * The workshop's side of a sample round: how a SAMPLE production order moves the round it was made for
 * (03-state-machines §17: SUBMITTED → IN_PRODUCTION → READY when the sample production order completes).
 *
 * <p>A contract inside the production module for the production-order workflow of SCRUM-396..400 — no
 * endpoint and no event, because both sides are this module and the round must move in the same
 * transaction as the production order, or a crash between the two leaves a finished sample stuck
 * IN_PRODUCTION. The caller names the production order, which is what the workshop holds.</p>
 *
 * <p>Both calls are idempotent: a workflow that reports twice, or reports completion without having
 * reported the start, converges on the same round state. Neither checks the production order's own
 * status; the caller reports after it has moved the order, in its transaction.</p>
 */
public interface SampleProductionProgress {

    /**
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} for an unknown order,
     *         {@code VALIDATION_FAILED} for an ORDER production order, {@code INVALID_SAMPLE_REQUEST_TRANSITION}
     *         for a round that was never submitted
     */
    void productionStarted(UUID productionOrderId);

    /** Same refusals as {@link #productionStarted}; the round becomes READY for Sales to send. */
    void productionCompleted(UUID productionOrderId);
}
