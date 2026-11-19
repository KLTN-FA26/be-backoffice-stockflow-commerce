package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.security.CurrentUser;

import java.util.Collection;
import java.util.UUID;

/**
 * The warehouse manager cancels a shipment that waits for dispatch (kltn-docs 09 §5.1 "Cancelled", 08 §5.1; owner
 * decision RTM-RTDCANCEL, 2026-11-19). A narrow interface of its own: only this module's web layer calls it, and no
 * caller of {@link Shipments} needs it.
 */
public interface ShipmentCancellations {

    /**
     * @param reason            why the shipment is cancelled; required, at most 500 characters
     * @param damagedPackageIds the shipment's cartons (package ids) found damaged; empty or null for none
     */
    record Cancellation(String reason, Collection<UUID> damagedPackageIds) { }

    /**
     * Cancels a READY_TO_DISPATCH shipment, in one transaction:
     * <ol>
     *   <li>the shipment CANCELLED with the reason, on its timeline;</li>
     *   <li>its cartons out of it and out of the order, their labels void: the damaged ones into quarantine, the others
     *       back onto a bin - both through the put-back queue;</li>
     *   <li>the order held {@code INVENTORY} (from PACKED, or from wherever it is), its lines lacking those cartons, as
     *       for a carton removed at the check station; the hold of each carton no longer stands for its delivery
     *       batch;</li>
     *   <li>the task's check back to IN_PROGRESS when it was COMPLETED, so the replacement cartons the warehouse
     *       manager reallocates ({@code PickLists.reallocateShort}) are picked, checked and completed into a new
     *       shipment; the reallocation that finds stock for the last of them resolves the hold, and the order goes
     *       back to the status it was held from.</li>
     * </ol>
     *
     * @return the shipment's id
     * @throws com.stockflow.common.error.BusinessException {@code SHIPMENT_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code VALIDATION_FAILED} without a reason, or for a damaged carton not in the shipment;
     *         {@code SHIPMENT_STATE_CONFLICT} for a shipment not READY_TO_DISPATCH; {@code PACKAGE_IN_LOAD_PLAN} while
     *         a live load plan holds a carton of it, or it is on an open load; {@code PACKAGE_UNLOAD_PENDING} while a
     *         carton of it is still to be scanned off a vehicle that was called off; {@code PICK_TASK_STATE_CONFLICT}
     *         once its order left fulfilment
     */
    UUID cancel(UUID shipmentId, Cancellation cancellation, CurrentUser actor);
}
