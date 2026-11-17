package com.stockflow.payment.internal.controller;

/**
 * Permission resource codes owned by the payment module. The money ledger's ({@code payment-receivables},
 * {@code payment-customer-transfers}, {@code payment-order-payments}, {@code payment-payments},
 * {@code payment-cod-remittances}, {@code payment-settlements}) went with it (4B-10 in the code, 4B-11 in the
 * database).
 */
final class PaymentResources {

    /** 4B-6: the installments of signed contracts - the accountant's, Sales' and (OWN) the customer's. */
    static final String INSTALLMENTS = "payment-installments";
    /** 4B-6: receipts the accountant records against installments from the bank statement, and voids. */
    static final String RECEIPTS = "payment-receipts";
    /** 4B-9: what cancellations and closures left to refund, and the accountant's records of the refunds made. */
    static final String REFUNDS = "payment-refunds";

    private PaymentResources() {
    }
}
