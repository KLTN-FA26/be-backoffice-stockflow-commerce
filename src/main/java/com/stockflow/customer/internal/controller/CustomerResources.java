package com.stockflow.customer.internal.controller;

public final class CustomerResources {
    public static final String CUSTOMERS = "customer-customers";
    public static final String ADDRESSES = "customer-addresses";
    /** Activate, suspend or close a customer account: the Sales Manager's (V20261022000200). */
    public static final String STATUS = "customer-status";
    /** Commercial terms: payment terms allowed, deposit share, credit limit and days (SCRUM-427). */
    public static final String CREDIT_PROFILES = "customer-credit-profiles";

    /** 4B-1: the default payment schedule - READ, UPDATE (Sales), APPROVE (an installment after delivery: accounting). */
    public static final String PAYMENT_SCHEDULES = "customer-payment-schedules";
    /** Customer groups and who is in them (P7): Sales Manager manages, Sales reads (V20261017000600). */
    public static final String SEGMENTS = "customer-segments";

    /**
     * WF-05 E1: a customer's legal profile - what a contract and an e-invoice name the buyer by. A resource of its own,
     * not {@code customer-customers}: the accountant corrects an invoice e-mail without being able to edit customers,
     * and a portal customer, who holds {@code customer-customers:UPDATE} for its own record, holds none of it.
     */
    public static final String LEGAL_PROFILES = "customer-legal-profiles";

    private CustomerResources() {
    }
}
