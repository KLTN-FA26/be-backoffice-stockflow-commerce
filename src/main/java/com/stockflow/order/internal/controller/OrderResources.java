package com.stockflow.order.internal.controller;

/** Permission resource codes owned by the order module. */
public final class OrderResources {

    /** Hyphens only: {@code PermissionCode} enforces {@code [a-z0-9-]{2,64}}. */
    public static final String ORDERS = "sales-orders";

    /**
     * Cancelling someone else's order from the back office (kltn-docs 17 §2, §4.5). Its own resource
     * so Sales can cancel without also being able to release orders to production, which
     * {@code sales-orders:APPROVE} guards (SCRUM-256, SCRUM-459).
     */
    public static final String ORDER_CANCELLATIONS = "sales-order-cancellations";

    /** Orders over their customer's credit limit, waiting for whoever approves credit (SCRUM-427). */
    public static final String CREDIT_HOLDS = "sales-order-credit-holds";

    private OrderResources() {
    }
}
