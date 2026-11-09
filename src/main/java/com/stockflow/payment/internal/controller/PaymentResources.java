package com.stockflow.payment.internal.controller;

/** Permission resource codes owned by the payment module. */
final class PaymentResources {

    /** What customers owe on delivered credit orders (SCRUM-431). */
    static final String RECEIVABLES = "payment-receivables";
    /** Money customers sent to pay what they owe, as recorded from the bank statement. */
    static final String CUSTOMER_TRANSFERS = "payment-customer-transfers";
    /** G2-3: one order's payment history - not the list of every customer's transfers. */
    static final String ORDER_PAYMENTS = "payment-order-payments";
    /** 4B-6: the installments of signed contracts - the accountant's, Sales' and (OWN) the customer's. */
    static final String INSTALLMENTS = "payment-installments";
    /** 4B-6: receipts the accountant records against installments from the bank statement, and voids. */
    static final String RECEIPTS = "payment-receipts";

    private PaymentResources() {
    }
}
