package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code fulfillment-load-plans} (V20261022001300): VIEW_PAGE and READ for warehouse staff, the
 * warehouse manager and the order coordinator; CREATE and UPDATE for warehouse staff and the warehouse manager
 * (SYSTEM_ADMIN holds all).
 */
@PermissionResource(code = FulfillmentResources.LOAD_PLANS, group = "Fulfillment", label = "Load plans",
        route = "/fulfillment/load-plans", apiPath = "/api/v1/fulfillment/load-plans",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
final class FulfillmentLoadPlanResourceDeclaration {
    private FulfillmentLoadPlanResourceDeclaration() { }
}
