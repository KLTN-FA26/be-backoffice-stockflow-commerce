package com.stockflow.contracts;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published by the <b>payment</b> module. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; delivery is made durable by Spring Modulith's
 * event publication registry, so a listener that crashes mid-handling gets the event again.
 * See {@code package-info.java} for the rules on changing this contract.
 *
 * @param paymentId unique per money movement counted for the order: the payment, or the allocation of
 *                  a customer transfer to the order's receivable
 * @param source    where the money came from: null for a payment of the order, {@code
 *                  RECEIVABLE_ALLOCATION} for a customer transfer (or a carrier's remittance) paying a receivable of
 *                  the order (SCRUM-431), {@code ORDER_ALLOCATION} for a customer transfer paying the order itself -
 *                  prepaid, deposit or the balance before delivery (G2-3), {@code FEE_RECEIVABLE_ALLOCATION} for a
 *                  transfer paying the receivable of a fee a remainder closure left owed (re-review of #150: the
 *                  order counts it as that fee's); added as a nullable field, as the contract rules allow
 */
public record PaymentCaptured(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        PaymentMethod method,
        String source
) {

    public PaymentCaptured(UUID orderId, UUID paymentId, BigDecimal amount, String currency, PaymentMethod method) {
        this(orderId, paymentId, amount, currency, method, null);
    }
}
