package com.stockflow.order.api;

/**
 * Who bears the fee Sales keep when an order is cancelled after printing started, or when the rest of a partly
 * fulfilled order is closed (kltn-docs 17 §4.8 steps 30 and 33, 15 §4.6 steps 18-19; owner decisions D46 and D50,
 * 2026-11-28). Sales choose, on every fee, with no default: the field is required whenever a fee is entered.
 *
 * <p>Mapped by name onto {@code ordering.customer_order.cancellation_fee_bearer} and
 * {@code ordering.order_remainder_closure.fee_bearer}, whose CHECKs list the same values.</p>
 */
public enum CancellationFeeBearer {

    /** The customer is charged: the money collected pays the fee first, the rest is refunded, a shortfall is owed. */
    CUSTOMER,

    /**
     * The company bears it: the customer is charged nothing - all the money collected is refundable as if no fee were
     * kept, no fee item, no receivable - and the amount is recorded as a cost of the company for the accountant.
     */
    COMPANY;

    /** Whether the customer is spared the fee. */
    public boolean borneByCompany() {
        return this == COMPANY;
    }
}
