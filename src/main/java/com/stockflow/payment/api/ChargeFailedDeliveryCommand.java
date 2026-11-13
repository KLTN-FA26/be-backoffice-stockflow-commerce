package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A trip failed because the customer refused the goods or gave a wrong address, and Sales decided to deliver again
 * (WF-05 fix plan, section 2; kltn-docs 09 §4.3): what the order module hands
 * {@link PaymentService#chargeFailedDelivery} in the deciding transaction.
 *
 * @param amount    what the trip is charged at, whole dong, more than zero - entered by Sales, never computed
 * @param label     what the customer reads on the item, e.g. the shipment it was for
 */
public record ChargeFailedDeliveryCommand(UUID orderId, BigDecimal amount, String label, UUID chargedBy,
                                          Instant chargedAt) {

    public ChargeFailedDeliveryCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(chargedAt, "chargedAt");
    }
}
