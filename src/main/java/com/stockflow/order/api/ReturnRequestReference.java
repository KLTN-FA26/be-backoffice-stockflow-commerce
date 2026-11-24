package com.stockflow.order.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A return request, as a return receipt names it (team plan 4D-2).
 *
 * @param awaitingGoods   Sales approved it and its goods have not all come back yet ({@code AWAITING_RETURN})
 * @param status          its status as the order module names it, for a message that says why not
 * @param shipmentId      the delivered shipment it is against (WF-05 TH-1): only that shipment's cartons come back on
 *                        its receipt. Null for a return of an order delivered without shipments, whose cartons are any
 *                        its deliveries carried
 * @param receiptOpenable whether a return receipt may be opened for it now (WF-05 Q-11): approved and its refund not
 *                        settled - AWAITING_RETURN, RECEIVED, SETTLEMENT_PENDING, EXPIRED, or CANCELLED after approval.
 *                        A late parcel is received on a document instead of standing in the warehouse with none
 * @param claimedLines    what it approved, per SKU (WF-05 Q-11, BE-22): its receipt takes only goods of those SKUs, up to
 *                        those units; anything else is the customer's, kept in custody on the return request
 * @param draftReceiptId  the DRAFT return receipt counting a parcel of it now, null when none (WF-05 BE-23)
 * @param receiptRefusal  why no receipt may be opened for it now, in words for the warehouse; null when one may
 *                        (audit round 2: also its order CLOSED, L-3, or its approved units claimed by another return, M2)
 * @param claimedBy       when another return claimed the units it approved (M2): those returns' RMA numbers - the goods
 *                        are received on one of them instead; empty otherwise
 */
public record ReturnRequestReference(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                                     String status, boolean awaitingGoods, UUID shipmentId, boolean receiptOpenable,
                                     List<ClaimedLine> claimedLines, UUID draftReceiptId, String receiptRefusal,
                                     List<String> claimedBy) {

    /**
     * Units of one SKU the return approved, its lines of that SKU together: the most of it a receipt brings back for
     * the return.
     */
    public record ClaimedLine(String sku, int approvedQuantity) {

        public ClaimedLine {
            Objects.requireNonNull(sku, "sku");
        }
    }

    public ReturnRequestReference {
        claimedLines = claimedLines == null ? List.of() : List.copyOf(claimedLines);
        claimedBy = claimedBy == null ? List.of() : List.copyOf(claimedBy);
    }

    /** As named before the refusal was said (audit round 2): openable or not, by the return's own status. */
    public ReturnRequestReference(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                                  String status, boolean awaitingGoods, UUID shipmentId, boolean receiptOpenable,
                                  List<ClaimedLine> claimedLines, UUID draftReceiptId) {
        this(id, rmaNumber, orderId, orderNumber, customerId, status, awaitingGoods, shipmentId, receiptOpenable,
                claimedLines, draftReceiptId, null, List.of());
    }

    /** As named before late parcels and claimed lines (WF-05 Q-11): a receipt is opened while it awaits its goods. */
    public ReturnRequestReference(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                                  String status, boolean awaitingGoods, UUID shipmentId) {
        this(id, rmaNumber, orderId, orderNumber, customerId, status, awaitingGoods, shipmentId, awaitingGoods,
                List.of(), null);
    }

    /** Against the order as a whole. */
    public ReturnRequestReference(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                                  String status, boolean awaitingGoods) {
        this(id, rmaNumber, orderId, orderNumber, customerId, status, awaitingGoods, null);
    }

    /** What the return approved of {@code sku}: 0 for a SKU it does not claim. */
    public int approvedOf(String sku) {
        return claimedLines.stream().filter(line -> line.sku().equals(sku))
                .mapToInt(ClaimedLine::approvedQuantity).sum();
    }
}
