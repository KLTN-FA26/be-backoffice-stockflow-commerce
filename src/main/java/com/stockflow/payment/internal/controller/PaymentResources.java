package com.stockflow.payment.internal.controller;

/** Permission resource codes owned by the payment module. */
final class PaymentResources {

    /** What customers owe on delivered credit orders (SCRUM-431). */
    static final String RECEIVABLES = "payment-receivables";
    /** Money customers sent to pay what they owe, as recorded from the bank statement. */
    static final String CUSTOMER_TRANSFERS = "payment-customer-transfers";

    private PaymentResources() {
    }
}
