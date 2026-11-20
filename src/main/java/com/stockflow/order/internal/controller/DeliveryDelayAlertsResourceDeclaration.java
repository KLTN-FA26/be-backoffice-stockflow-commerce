package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code delivery-delay-alerts} (V20261209000400; kltn-docs 17 §3, BR-30): those who follow
 * delivery. It guards no endpoint of its own - {@link DelayAlertController} reads it off the caller to decide whether
 * DELIVERY_LATE is in the list, and the delay alert notification tells its holders of a late delivery - so it is
 * declared here. Held by Sales, the Sales Manager, the warehouse manager and the administrator, not by the Production
 * Coordinator, who reads the production alerts only.
 */
@PermissionResource(code = OrderResources.DELIVERY_DELAY_ALERTS, group = "Production",
        label = "Delay alerts: late deliveries (DELIVERY_LATE)", route = "/sales/orders",
        apiPath = "/api/v1/delay-alerts", actions = {Action.READ})
final class DeliveryDelayAlertsResourceDeclaration {
    private DeliveryDelayAlertsResourceDeclaration() { }
}
