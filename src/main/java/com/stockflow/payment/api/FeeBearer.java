package com.stockflow.payment.api;

/**
 * Who bears the fee Sales kept with a cancellation or a closure of the rest (kltn-docs 15 §4.6 steps 18-19; owner
 * decisions D46 and D50, 2026-11-28). The order module hands it in with the fee: {@code order :: CancellationFeeBearer},
 * by name - payment cannot read the order module's types.
 */
public enum FeeBearer {

    /** The customer is charged: the money collected pays the fee first, the rest is refundable, a shortfall is owed. */
    CUSTOMER,

    /**
     * The company bears it: the customer is charged nothing - all the money collected is refundable, no fee item, no
     * receivable - and the amount is kept as a cost of the company ({@link OrderSettlementResult#feeBorneByCompany}).
     */
    COMPANY
}
