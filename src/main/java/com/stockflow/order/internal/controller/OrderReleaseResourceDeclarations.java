package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares the two resources {@link OrderReleaseController} split out of {@code sales-orders:APPROVE} when the order
 * coordinator role was retired (QD-12, team plan 5-7; kltn-docs 00 §2): {@code sales-order-releases} (UPDATE: release
 * an order to a warehouse - the warehouse manager's, 17 BR-03) and {@code sales-order-holds} (UPDATE: hold an order and
 * resolve the hold - Sales', 17 §5). Two resources rather than one with two actions, so a role matrix grants one
 * without the other.
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

    @PermissionResource(
            code = OrderResources.HOLDS,
            group = "Sales",
            label = "Order holds",
            route = "/sales/orders",
            apiPath = "/api/v1/orders/{orderId}/hold",
            actions = {Action.UPDATE})
    static final class Holds {
        private Holds() {
        }
    }
}
