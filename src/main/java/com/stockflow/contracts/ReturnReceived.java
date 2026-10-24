package com.stockflow.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the warehouse counts a returned parcel in (03 §12). The goods are in
 * QUARANTINE at {@code locationCode} (OQ-12), never sellable until QC decides. {@code discrepancy} is true when the
 * parcel held fewer or more units than approved, another SKU than the line's, or something unexpected: Sales follow
 * it up with the customer.
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
        Instant receivedAt
) {

    public ReturnReceived {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** {@code receivedQuantity} units of {@code receivedSku} arrived for the line ({@code sku}) approved for {@code approvedQuantity}. */
    public record Line(UUID orderLineId, String sku, String receivedSku, int approvedQuantity, int receivedQuantity) {
    }
}
