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
 * <p>{@code FAILED_TRIP_FEE} is kept for rows written before WF-05 decision Q-19 and is <b>never made any more</b>: it
 * was the fee item of a trip that failed because the customer refused the goods or gave a wrong address, charged when
 * Sales decided to deliver again. That path went with "deliver again" (#377), and Q-19 removed the charge: a failed trip
 * is not charged without a contract clause that says so (Luật Thương mại 2005 Điều 300-304). The value stays so the rows
 * already written still read, are paid and count as a fee item - never refunded, never lowered; the database checks
 * are unchanged.</p>
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
