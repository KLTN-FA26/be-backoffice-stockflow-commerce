package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the carrier reports an order delivered and the Order
 * Coordinator records it (kltn-docs 09 §4.3, 17 §4.3; SCRUM-431).
 *
 * <p>Payment opens a receivable for what a credit order still owes, due on the delivery day plus
 * {@code creditTermDays} (15 §4.3 step 2). {@code paymentTerm} is the name of the order's term;
 * {@code creditTermDays} is null unless it is CREDIT.</p>
 *
 * Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made
 * durable by Spring Modulith's event publication registry, so a listener that crashes mid-handling
 * gets the event again. See {@code package-info.java} for the rules on changing this contract.
 */
public record OrderDelivered(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String paymentTerm,
        BigDecimal orderTotal,
        BigDecimal paidAmount,
        String currency,
        Integer creditTermDays,
        Instant deliveredAt
) {
}
