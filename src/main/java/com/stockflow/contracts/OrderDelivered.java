package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the carrier reports an order delivered and the warehouse
 * manager records it, or a carrier webhook does (kltn-docs 09 §4.3, 17 §4.3, BR-27; SCRUM-431).
 *
 * <p>Payment makes the installments of the order's contract paid after delivery due once the order is delivered in
 * full (15 §4.1 step 2, BR-09). Split shipments (decision G4-Q3, G3-2): one event per delivered shipment,
 * {@code shipmentId} naming it; null when the whole order was delivered at once without shipments.</p>
 *
 * <p>4B-10: the money ledger billed credit orders from this event; it went, and so did the order's payment terms.
 * {@code paymentTerm}, {@code paidAmount}, {@code creditTermDays}, {@code billedAmount} and {@code balancePolicy} are
 * always null now - kept so events written before still read.</p>
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
        BigDecimal billedAmount,
        String balancePolicy
) {

    /** Before G2-3: no balance policy. */
    public OrderDelivered(UUID orderId, String orderNumber, UUID customerId, String paymentTerm, BigDecimal orderTotal,
                          BigDecimal paidAmount, String currency, Integer creditTermDays, Instant deliveredAt,
                          UUID shipmentId, BigDecimal billedAmount) {
        this(orderId, orderNumber, customerId, paymentTerm, orderTotal, paidAmount, currency, creditTermDays,
                deliveredAt, shipmentId, billedAmount, null);
    }

    /**
     * The whole order delivered at once, as before split shipments: payment bills what it still owes. Events stored
     * before {@code shipmentId} and {@code billedAmount} existed read them as null.
     */
    public OrderDelivered(UUID orderId, String orderNumber, UUID customerId, String paymentTerm, BigDecimal orderTotal,
                          BigDecimal paidAmount, String currency, Integer creditTermDays, Instant deliveredAt) {
        this(orderId, orderNumber, customerId, paymentTerm, orderTotal, paidAmount, currency, creditTermDays,
                deliveredAt, null, null, null);
    }
}
