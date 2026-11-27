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

    /** Sales-owner plan: who looks after a customer - READ (Sales, manager, accountant), UPDATE (manager: BR-S01). */
    public static final String SALES_OWNERS = "customer-sales-owners";
    /** Sales-owner plan §10.7: leads from the public form - READ, UPDATE (Sales their own, manager all). */
    public static final String SALES_LEADS = "sales-leads";
    /** Sales-owner plan §10.4: the managers' assignment board. */
    public static final String SALES_WORKLOAD = "sales-workload";
    /** Sales-owner plan §10.5: a customer's request to change salesperson - CREATE (portal), READ, APPROVE (manager). */
    public static final String OWNER_CHANGE_REQUESTS = "sales-owner-change-requests";

    private CustomerResources() {
    }
}
