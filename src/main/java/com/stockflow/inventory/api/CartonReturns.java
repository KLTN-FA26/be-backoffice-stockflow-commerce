package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
     * {@link #find} for many LPNs at once, in a fixed number of queries whatever their count (WF-05: the return receipt
     * screen classifies every carton of its source): each carton found, with whether it came back opened and its
     * ownership; an LPN with no carton is absent. The return it last came back on is not read. A read, not a decision.
     */
    List<ReturnedCarton> findAll(Collection<String> lpns);

    /**
     * Brings the cartons back into stock at the QUARANTINE area {@code command.locationCode()}: each CONSUMED →
     * IN_STOCK there, sealed, its row QUARANTINE with the carton's count, one RECEIPT ledger line per carton naming the
     * receipt ({@code GOODS_RECEIPT}) and the order. Joins the caller's transaction, so the receipt closes with the
     * stock or not at all. Locks the SKUs (sorted), then every carton (in id order), then their rows. Not idempotent by
     * itself: a carton already back is refused, so the receipt's own status is what answers a retry. The cartons named
     * opened ({@code command.openedLpns()}, WF-05 TH-4) are marked so: they never leave quarantine but by a write-off
     * (a release is refused with {@code RETURN_CARTON_OPENED}).
     *
     * @return the cartons as they now stand, in the order named
     * @throws com.stockflow.common.error.BusinessException {@code STOCK_PACKAGE_NOT_FOUND}; {@code LOCATION_NOT_FOUND},
     *         {@code LOCATION_NOT_USABLE}, {@code LOCATION_AREA_MISMATCH} (not a QUARANTINE area);
     *         {@code MOVE_ACROSS_WAREHOUSES} (a carton of another warehouse); {@code INVALID_STOCK_PACKAGE_TRANSITION}
     *         (a carton not CONSUMED: in stock already, lost, never received); {@code STOCK_PACKAGE_TAKEN_WHOLE};
     *         403 for an area outside the caller's warehouses; {@code RETURN_CARTON_IN_WAREHOUSE} for a carton held for
     *         the customer or destroyed on their behalf (WF-05 Q-4)
     */
    List<ReturnedCarton> receive(ReceiveReturnedCartonsCommand command);

    /**
     * The refund of return {@code returnRequestId} was decided (WF-05 Q-4), in the transaction that resolves it, and with
     * it whose each of its cartons that came back sealed and waits for the decision is - per SKU, as {@code refunded}
     * says it (audit round 3, RAD-1: "không có thoả thuận trả hàng thì hàng vẫn là của khách" is about goods, not about
     * the return as a whole). Of a SKU, the refunded units are taken first from what came back outside a sealed carton
     * (opened cartons and loose units, never stocked, Q-18), then carton by carton in label order: a carton the refund
     * reaches is the company's, whole (a carton is not split, 20 BR-14); one it does not reach - every carton of a SKU
     * refunded 0 among them - stays the customer's: its stock-in is reversed by one ADJUSTMENT line naming the return,
     * the carton leaves the books (CONSUMED) and stands where it is, in the quarantine area, held for the customer.
     * {@code tg_return_request_goods_settled} checks the same split at commit. Locks the SKUs, then the cartons, then
     * their rows - after the order and the return the caller holds, the global order. Called again it finds nothing
     * waiting and changes nothing.
     *
     * @param refunded per SKU of the return, the units back and refunded; a SKU not named refunded nothing
     * @return how many cartons were settled
     */
    int settleOwnership(UUID returnRequestId, List<RefundedSku> refunded, UUID actorId, Instant at);

    /**
     * The cartons of return {@code returnRequestId} held for the customer or whose custody ended, by LPN (WF-05 Q-4):
     * what a custody choice on the return is about. Empty when none is.
     */
    List<HeldCarton> heldFor(UUID returnRequestId);

    /**
     * What the customer chose for the goods of return {@code returnRequestId} held for them (WF-05 Q-4), as Sales
     * recorded it: the warehouse manager carries out exactly that. A new choice replaces one not yet carried out.
     *
     * @return how many cartons are held for the customer and take the instruction; 0 when none is
     * @throws com.stockflow.common.error.BusinessException {@code INVALID_STOCK_PACKAGE_TRANSITION} once a carton of it
     *         was already handed back or destroyed
     */
    int instructCustody(UUID returnRequestId, CustodyInstruction instruction);

    /**
     * Of these LPNs, those whose stock-in return {@code returnRequestId} reversed (WF-05 Q-4; audit round 3, RAD-5): the
     * return refunded nothing of them, so a receipt of that return took them into stock and the refund decision took
     * them out again - the ledger's ADJUSTMENT line naming the return says so for good. Read from the ledger, not from
     * the carton's ownership now: a carton that came back again on a later return carries that return's ownership,
     * which says nothing of this one.
     */
    Set<String> reversedBy(UUID returnRequestId, Collection<String> lpns);
}
