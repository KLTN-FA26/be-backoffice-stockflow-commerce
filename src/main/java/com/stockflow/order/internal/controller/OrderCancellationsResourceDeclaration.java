package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code sales-order-cancellations}, guarding {@code POST /orders/{id}/admin-cancellation}
 * on {@link OrderController}, which already carries the {@code sales-orders} resource.
 *
 * <p>A marker, not a bean, for the reason {@code ReservationsResourceDeclaration} gives:
 * {@code PermissionCatalogValidator} scans the classpath for {@code @PermissionResource}.</p>
 */
@PermissionResource(
        code = OrderResources.ORDER_CANCELLATIONS,
        group = "Sales",
        label = "Order cancellations",
        route = "/sales/orders",
        apiPath = "/api/v1/orders/{orderId}/admin-cancellation",
        actions = {Action.UPDATE})
final class OrderCancellationsResourceDeclaration {

    private OrderCancellationsResourceDeclaration() {
    }
}
