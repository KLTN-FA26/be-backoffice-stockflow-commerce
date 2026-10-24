package com.stockflow.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when Sales approve a return request (03 §12), in full or for fewer units.
 *
 * <p>{@code returnRequired}: the customer sends the approved units back, writing {@code rmaNumber} on the parcel,
 * before {@code returnDeadline} - the return expires otherwise. False: the defect was accepted on the photos and the
 * goods stay with the customer (low value, or nothing to send for a short delivery); {@code returnDeadline} is null.
 * Notification tells the customer what to do next; the warehouse expects the parcel.</p>
 *
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record ReturnApproved(
        UUID returnRequestId,
        String rmaNumber,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        boolean returnRequired,
        Instant returnDeadline,
        List<Line> lines,
        UUID approvedBy,
        Instant approvedAt
) {

    public ReturnApproved {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** {@code approvedQuantity} of the units asked for; 0 for a line not approved. */
    public record Line(UUID orderLineId, String sku, int requestedQuantity, int approvedQuantity) {
    }
}
