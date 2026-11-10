package com.stockflow.inventory.api;

import java.util.List;
import java.util.Optional;

/**
 * Cartons that come back to the warehouse (team plan 4D-2; kltn-docs 03 §4.2, BR-03, BR-14): a shipment the carrier
 * brought back, or the goods of an approved return request. A return receipt ({@code procurement}) scans them by LPN
 * and, once confirmed, has them received here: each comes back whole, as the carton it left as - its LPN, SKU, count
 * and lot - into quarantine, out of ATP until a warehouse manager releases it or writes it off (20 §4.8).
 *
 * <p>Its own interface rather than more of {@link InventoryService}: only the return receipt brings a carton back from
 * outside, and the narrower the surface, the fewer callers can.</p>
 */
public interface CartonReturns {

    /**
     * The carton {@code lpn} as the books know it, in any state; empty when no carton has this LPN. A read for the
     * receipt's scan, not a decision: whether it may come back is {@link #receive}'s, under its locks. No warehouse
     * scope here: the receipt asking is in the caller's scope, and the carton is shown only once the receipt has
     * matched it to its own shipment or order.
     */
    Optional<ReturnedCarton> find(String lpn);

    /**
     * Brings the cartons back into stock at the QUARANTINE area {@code command.locationCode()}: each CONSUMED →
     * IN_STOCK there, sealed, its row QUARANTINE with the carton's count, one RECEIPT ledger line per carton naming the
     * receipt ({@code GOODS_RECEIPT}) and the order. Joins the caller's transaction, so the receipt closes with the
     * stock or not at all. Locks the SKUs (sorted), then every carton (in id order), then their rows. Not idempotent by
     * itself: a carton already back is refused, so the receipt's own status is what answers a retry.
     *
     * @return the cartons as they now stand, in the order named
     * @throws com.stockflow.common.error.BusinessException {@code STOCK_PACKAGE_NOT_FOUND}; {@code LOCATION_NOT_FOUND},
     *         {@code LOCATION_NOT_USABLE}, {@code LOCATION_AREA_MISMATCH} (not a QUARANTINE area);
     *         {@code MOVE_ACROSS_WAREHOUSES} (a carton of another warehouse); {@code INVALID_STOCK_PACKAGE_TRANSITION}
     *         (a carton not CONSUMED: in stock already, lost, never received); {@code STOCK_PACKAGE_TAKEN_WHOLE};
     *         403 for an area outside the caller's warehouses
     */
    List<ReturnedCarton> receive(ReceiveReturnedCartonsCommand command);
}
