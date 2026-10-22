package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code fulfillment-load-estimates} (V20261022001200): CREATE and READ for the order
 * coordinator and the warehouse manager (SYSTEM_ADMIN holds all). No page of its own: the estimate opens from an order.
 */
@PermissionResource(code = FulfillmentResources.LOAD_ESTIMATES, group = "Fulfillment", label = "Load estimates",
        apiPath = "/api/v1/fulfillment/load-estimates", actions = {Action.CREATE, Action.READ})
final class FulfillmentLoadEstimateResourceDeclaration {
    private FulfillmentLoadEstimateResourceDeclaration() { }
}
