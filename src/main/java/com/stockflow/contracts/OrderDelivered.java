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
 * <p>Split shipments (decision G4-Q3, G3-2): one event per delivered shipment, {@code shipmentId} naming it and
 * {@code billedAmount} what the order bills for it - the goods it carried, less money paid ahead; payment opens one
 * receivable per shipment for exactly that. Both are null when the whole order was delivered at once without
 * shipments, and payment then bills {@code orderTotal - paidAmount} as before.</p>
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
        Instant deliveredAt,
        UUID shipmentId,
        BigDecimal billedAmount
) {

    /**
     * The whole order delivered at once, as before split shipments: payment bills what it still owes. Events stored
     * before {@code shipmentId} and {@code billedAmount} existed read them as null.
     */
    public OrderDelivered(UUID orderId, String orderNumber, UUID customerId, String paymentTerm, BigDecimal orderTotal,
                          BigDecimal paidAmount, String currency, Integer creditTermDays, Instant deliveredAt) {
        this(orderId, orderNumber, customerId, paymentTerm, orderTotal, paidAmount, currency, creditTermDays,
                deliveredAt, null, null);
    }
}
