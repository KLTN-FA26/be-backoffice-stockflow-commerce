package com.stockflow.payment.internal.domain;

/**
 * When an installment falls due (kltn-docs 15 §4.1 step 2): at the signature, when the order is released to be shipped
 * ({@code BEFORE_DELIVERY}, which has no due day and blocks the handover until paid, BR-08), or when the goods are
 * delivered ({@code AFTER_DELIVERY}, the customer's receivable, due N days later, BR-09). The same names as the
 * contract's plan ({@code ordering.contract_payment_installment.milestone}).
 *
 * <p>{@code CANCELLATION_FEE} is no plan row's milestone: it marks the cancellation fee item (4B-9, 15 §4.6 step 18),
 * due from the day the order was cancelled or its rest closed. It sits here rather than in a separate "kind" because
 * the fee item is paid, swept and counted exactly as an installment is (15 §5.1), and every reader that asks "which
 * milestone" already leaves it out by asking for its own.</p>
 */
public enum InstallmentMilestone {
    ON_SIGNING,
    BEFORE_DELIVERY,
    AFTER_DELIVERY,
    CANCELLATION_FEE
}
