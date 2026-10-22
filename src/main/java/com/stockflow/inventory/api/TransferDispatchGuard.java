package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * What inventory asks the module that packs and loads transfers before it changes a transfer's picking or dispatches
 * it by hand (SCRUM-287 PR T; kltn-docs 10 BR-04, 09 BR-14). An interface in {@code inventory :: api} implemented
 * <b>elsewhere</b> ({@code fulfillment}), as {@link SkuRetirement}: fulfillment depends on inventory, so a call from
 * inventory into {@code fulfillment :: api} would close a cycle. Absent (an inventory-only module test), nothing is
 * packed and nothing is asked.
 */
public interface TransferDispatchGuard {

    /**
     * Picking of the transfer is being cancelled before any of its goods left (10 §5 Picking → Approved): its parcels
     * not on a vehicle are voided and its load plan is cancelled, in the caller's transaction.
     *
     * @throws com.stockflow.common.error.BusinessException {@code LOAD_PLAN_LOADING_STARTED} while a vehicle of its
     *         plan is being loaded (09 BR-14: only a controlled unloading, not built yet)
     */
    void beforePickingCancelled(UUID transferId, Instant at);

    /**
     * A manual dispatch is asked for (SCRUM-327).
     *
     * @throws com.stockflow.common.error.BusinessException {@code TRANSFER_DISPATCH_BY_LOAD_PLAN} once the transfer is
     *         being packed for a load plan: the last vehicle's signature dispatches it
     */
    void requireManualDispatch(UUID transferId);
}
