package com.stockflow.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the goods of a return came back (team plan 4D-3; kltn-docs 17 §4.10 step
 * 39): the warehouse confirmed the return receipt that scanned them ({@code ReturnedGoodsReceived}), and they are in
 * QUARANTINE at {@code locationCode}, out of sale until a warehouse manager releases or writes off each carton
 * (20 §4.8). Published once per return receipt: a return may come back in several parcels, each on a receipt of its
 * own (WF-05 TH-3), and {@code parcel} numbers them from 1. Each line counts the units of its SKU in the cartons
 * received by every parcel so far; {@code receivedSku} is the line's own. {@code discrepancy} is true when this parcel
 * held more units of a line than approved, a SKU the return did not claim, or - when no more is awaited - fewer than
 * approved: Sales follow it up with the customer outside the system (03 edge cases).
 *
 * <p>WF-05 BE-24: {@code status} is the return's status once this parcel was counted - {@code RECEIVED} when no more
 * goods are awaited and Sales must propose the refund, {@code AWAITING_RETURN} while more parcels are to come; null in
 * publications stored before.</p>
 *
 * <p>Before 4D-3 it was published per parcel the warehouse counted in loose, QC deciding afterwards; the shape is kept
 * (see {@code package-info.java} for the rules on changing this contract).</p>
 */
public record ReturnReceived(
        UUID returnRequestId,
        String rmaNumber,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String locationCode,
        List<Line> lines,
        boolean discrepancy,
        UUID receivedBy,
        Instant receivedAt,
        Integer parcel,
        String status,
        String receiptNumber
) {

    public ReturnReceived {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** As published before the receipt's number was carried (WF-05 BE-24). */
    public ReturnReceived(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                          String locationCode, List<Line> lines, boolean discrepancy, UUID receivedBy,
                          Instant receivedAt, Integer parcel, String status) {
        this(returnRequestId, rmaNumber, orderId, orderNumber, customerId, locationCode, lines, discrepancy,
                receivedBy, receivedAt, parcel, status, null);
    }

    /** As published before the return's status was carried (WF-05 BE-24). */
    public ReturnReceived(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                          String locationCode, List<Line> lines, boolean discrepancy, UUID receivedBy,
                          Instant receivedAt, Integer parcel) {
        this(returnRequestId, rmaNumber, orderId, orderNumber, customerId, locationCode, lines, discrepancy,
                receivedBy, receivedAt, parcel, null, null);
    }

    /** One parcel, as published before a return took several (null {@code parcel} reads as the first). */
    public ReturnReceived(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                          String locationCode, List<Line> lines, boolean discrepancy, UUID receivedBy,
                          Instant receivedAt) {
        this(returnRequestId, rmaNumber, orderId, orderNumber, customerId, locationCode, lines, discrepancy,
                receivedBy, receivedAt, null, null, null);
    }

    /** Which parcel of the return this was: 1 for the first; a B2B customer may ship the goods in several. */
    public int parcelNumber() {
        return parcel == null ? 1 : parcel;
    }

    /**
     * {@code receivedQuantity} units of {@code receivedSku} arrived for the line ({@code sku}) approved for
     * {@code approvedQuantity}, every parcel so far counted together.
     */
    public record Line(UUID orderLineId, String sku, String receivedSku, int approvedQuantity, int receivedQuantity) {
    }
}
