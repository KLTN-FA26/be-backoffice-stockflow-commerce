package com.stockflow.payment.internal.domain;

/**
 * When an installment falls due (kltn-docs 15 §4.1 step 2): at the signature, when the order is released to be shipped
 * ({@code BEFORE_DELIVERY}, which has no due day and blocks the handover until paid, BR-08), or when the goods are
 * delivered ({@code AFTER_DELIVERY}, the customer's receivable, due N days later, BR-09). The same names as the
 * contract's plan ({@code ordering.contract_payment_installment.milestone}).
 *
 * <p>{@code CANCELLATION_FEE} is no plan row's milestone: it marks the cancellation fee item (4B-9, 15 §4.6 step 18),
 * due from the day the order was cancelled or its rest closed. It sits here rather than in a separate "kind" because
 * the fee item is paid and counted exactly as an installment is (15 §5.1), and every reader that asks "which
 * milestone" already leaves it out by asking for its own.</p>
 *
 * <p>{@code FAILED_TRIP_FEE} is the other fee item (WF-05 fix plan, section 2): what the customer pays for a trip
 * that failed because they refused the goods or gave a wrong address, when Sales decide to deliver again. Due from the
 * day of that decision, it names no plan row either; no settlement counts its money as collected for goods, so it is
 * never refunded.</p>
 */
public enum InstallmentMilestone {
    ON_SIGNING,
    BEFORE_DELIVERY,
    AFTER_DELIVERY,
    CANCELLATION_FEE,
    FAILED_TRIP_FEE;

    /** A fee item rather than a row of the contract's plan ({@code ck_installment_fee_item}). */
    public boolean feeItem() {
        return this == CANCELLATION_FEE || this == FAILED_TRIP_FEE;
    }
}
