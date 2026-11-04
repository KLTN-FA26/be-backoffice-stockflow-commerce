package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * What inventory asks the module that packs and loads transfers before it cancels a transfer's picking (SCRUM-287 PR T; kltn-docs 10 BR-04, 09 BR-14). An interface in {@code inventory :: api} implemented
 * <b>elsewhere</b> ({@code fulfillment}), as {@link SkuRetirement}: fulfillment depends on inventory, so a call from
 * inventory into {@code fulfillment :: api} would close a cycle. Absent (an inventory-only module test), nothing is
 * packed and nothing is asked.
 */
public interface TransferDispatchGuard {

    /**
     * Picking of the transfer is being cancelled before any of its goods left (10 §5 Picking → Approved): its parcels
     * not on a vehicle are voided and its load plan is cancelled, in the caller's transaction.
     *
     * @throws com.stockflow.common.error.BusinessException {@code TRANSFER_LOADING_IN_PROGRESS} while a vehicle of its
     *         plan is being loaded (09 BR-14, UNLOAD-DESIGN point 10: run the controlled cancel of the plan first);
     *         {@code PACKAGE_UNLOAD_PENDING} while a parcel of the transfer is still to be scanned off a vehicle that
     *         was called off
     */
    void beforePickingCancelled(UUID transferId, Instant at);
}
