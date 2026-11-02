package com.stockflow.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the warehouse counts a returned parcel in (03 §12). The goods are in
 * QUARANTINE at {@code locationCode} (OQ-12), never sellable until QC decides. A return may come back in several parcels:
 * one event per parcel, {@code parcel} its number, the line counts the total so far. {@code discrepancy} is true when the
 * parcel held more units than approved, another SKU than the line's, or something unexpected: Sales follow it up with
 * the customer. A shortfall is recorded at inspection, once no more parcels are coming.
 *
 * See {@code package-info.java} for the rules on changing this contract.
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
