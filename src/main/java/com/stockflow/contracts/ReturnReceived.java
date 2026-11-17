package com.stockflow.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the goods of a return came back (team plan 4D-3; kltn-docs 17 §4.10 step
 * 39): the warehouse confirmed the return receipt that scanned them ({@code ReturnedGoodsReceived}), and they are in
 * QUARANTINE at {@code locationCode}, out of sale until a warehouse manager releases or writes off each carton
 * (20 §4.8). A return comes back on one receipt, so {@code parcel} is 1. Each line counts the units of its SKU in the
 * cartons received; {@code receivedSku} is the line's own. {@code discrepancy} is true when the cartons held fewer or
 * more units of a line than approved, or a SKU the return did not claim: Sales follow it up with the customer outside
 * the system (03 edge cases).
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
        Integer parcel
) {

    public ReturnReceived {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One parcel, as published before a return took several (null {@code parcel} reads as the first). */
    public ReturnReceived(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                          String locationCode, List<Line> lines, boolean discrepancy, UUID receivedBy,
                          Instant receivedAt) {
        this(returnRequestId, rmaNumber, orderId, orderNumber, customerId, locationCode, lines, discrepancy,
                receivedBy, receivedAt, null);
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
