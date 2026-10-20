package com.stockflow.customer.internal.controller;

public final class CustomerResources {
    public static final String CUSTOMERS = "customer-customers";
    public static final String ADDRESSES = "customer-addresses";
    /** Commercial terms: payment terms allowed, deposit share, credit limit and days (SCRUM-427). */
    public static final String CREDIT_PROFILES = "customer-credit-profiles";
    /** Customer groups and who is in them (P7): E-commerce Admin manages, Sales reads (V20261017000600). */
    public static final String SEGMENTS = "customer-segments";

    private CustomerResources() {
    }
}
