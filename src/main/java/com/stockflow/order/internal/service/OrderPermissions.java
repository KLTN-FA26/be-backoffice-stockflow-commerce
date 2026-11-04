package com.stockflow.order.internal.service;

/**
 * Permission resources of the order module that a service checks as well as a controller. The controller layer reads
 * them through {@code OrderResources}; they live here because a service may not depend on the controller package.
 *
 * <p>Every check is a permission, never a role name (ADR-0008, lead's rule 2026-10-22).</p>
 */
public final class OrderPermissions {

    /**
     * LP-1: UPDATE chooses how an order travels - {@code PUT /orders/{id}/delivery-mode}, and a mode other than the
     * order's at release, decided under the order's row lock. Seeded by V20261022000800.
     */
    public static final String DELIVERY_MODES = "sales-order-delivery-modes";

    private OrderPermissions() {
    }
}
