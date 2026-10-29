package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a return request is refused (03 §12): by Sales at review
 * ({@code stage = REVIEW}, outside policy), or after QC found no defect in any unit received
 * ({@code stage = INSPECTION}; the goods go back to the customer or are disposed of with their consent). The reason
 * Sales typed stays with the return (the portal shows it); the order goes back to waiting for its return window and
 * its money.
 *
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record ReturnRejected(
        UUID returnRequestId,
        String rmaNumber,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String stage,
        UUID rejectedBy,
        Instant rejectedAt
) {
}
