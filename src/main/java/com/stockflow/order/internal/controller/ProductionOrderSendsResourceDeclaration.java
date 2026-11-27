package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code production-order-sends} (V20261213000200; kltn-docs 17 §2): those told when a
 * production order is sent - the Sales Manager and the administrator, now that nobody approves one. It guards no
 * endpoint of its own - the notification of a production order sent goes to its holders - so it is declared here, as
 * {@link DeliveryDelayAlertsResourceDeclaration} is. READ rather than an action of its own: the action set is closed
 * ({@link Action}) and the back office parses it.
 */
@PermissionResource(code = OrderResources.PRODUCTION_ORDER_SENDS, group = "Production",
        label = "Production orders: told when one is sent", route = "/production/orders",
        apiPath = "/api/v1/production-orders", actions = {Action.READ})
final class ProductionOrderSendsResourceDeclaration {
    private ProductionOrderSendsResourceDeclaration() { }
}
