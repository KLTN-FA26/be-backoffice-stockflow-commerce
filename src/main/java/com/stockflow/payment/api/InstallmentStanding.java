package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Where an order's installments stand (4B-6, kltn-docs 15 §5.2), for the order module's decisions: whether it may be
 * confirmed, or resumed from a hold, and what its goods wait for before they leave.
 *
 * @param opened               whether the order has installments at all (an order of a signed contract does)
 * @param onSigningPaid        every installment due on signing is PAID (15 BR-07); true when there is none
 * @param beforeDeliveryUnpaid what the installments paid before delivery still owe (15 BR-08)
 * @param dueBeforeHandover    what must be paid before goods leave: the installments before delivery (BR-08) and
 *                             those on signing (BR-07: the order was confirmed by them - a receipt voided since must
 *                             not let the goods leave while the hold it calls for is still on its way)
 * @param total                what the installments not cancelled amount to
 * @param paid                 what has been paid on them
 * @param paymentStatus        §5.2, from the installments not cancelled; null when it has none
 */
public record InstallmentStanding(UUID orderId, boolean opened, boolean onSigningPaid, BigDecimal beforeDeliveryUnpaid,
                                  BigDecimal dueBeforeHandover, BigDecimal total, BigDecimal paid,
                                  ContractPaymentStatus paymentStatus) {

    /** An order without installments: nothing waits for them. */
    public static InstallmentStanding none(UUID orderId) {
        return new InstallmentStanding(orderId, false, true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null);
    }

    /** kltn-docs 15 §5.2: worked out from the installments, never stored. */
    public enum ContractPaymentStatus {
        UNPAID,
        PARTIALLY_PAID,
        PAID
    }
}
