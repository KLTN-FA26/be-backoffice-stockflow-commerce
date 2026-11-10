package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code sales-order-releases} (UPDATE: release an order - by delivery batch since 4B-8 - to a warehouse, the
 * warehouse manager's, kltn-docs 17 BR-03), which {@link OrderReleaseController} guards the release with. Team plan 5-7
 * declares the same class with the holds resource beside it; the final merge keeps one.
 *
 * <p>Markers, not beans, for the reason {@code ReservationsResourceDeclaration} gives:
 * {@code PermissionCatalogValidator} scans the classpath for {@code @PermissionResource}.</p>
 */
final class OrderReleaseResourceDeclarations {

    private OrderReleaseResourceDeclarations() {
    }

    @PermissionResource(
            code = OrderResources.RELEASES,
            group = "Sales",
            label = "Order release to a warehouse",
            route = "/sales/orders",
            apiPath = "/api/v1/orders/{orderId}/release",
            actions = {Action.UPDATE})
    static final class Releases {
        private Releases() {
        }
    }
}
