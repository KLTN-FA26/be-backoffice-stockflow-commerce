package com.stockflow.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a return request (RMA, SCRUM-243) is opened - by the customer on the portal
 * or by Sales on their behalf. Sales review it next; notification tells them (and confirms to the customer), reporting
 * counts claims by reason.
 *
 * <p>{@code reasonCode} is the order module's {@code ReturnReason} name (PRINT_DEFECT, DESIGN_MISMATCH,
 * DAMAGED_IN_TRANSIT, WRONG_ITEM, SHORT_DELIVERY, OTHER), {@code requestedResolution} what the customer asked for
 * (REPLACEMENT, REFUND, CREDIT_NOTE), {@code channel} PORTAL or BACK_OFFICE.</p>
 *
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record ReturnRequested(
        UUID returnRequestId,
        String rmaNumber,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String reasonCode,
        String requestedResolution,
        String channel,
        List<Line> lines,
        UUID requestedBy,
        Instant requestedAt
) {

    public ReturnRequested {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** {@code quantity} units of the order line claimed. */
    public record Line(UUID orderLineId, String sku, int quantity) {
    }
}
