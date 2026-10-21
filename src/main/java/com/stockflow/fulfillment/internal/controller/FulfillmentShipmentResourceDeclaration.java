package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code fulfillment-shipments} (seeded since V20260903000100 for warehouse staff, warehouse
 * managers and order coordinators): three controllers serve it - carriers, loads, shipments (G4-2a).
 */
@PermissionResource(code = FulfillmentResources.SHIPMENTS, group = "Fulfillment", label = "Shipments",
        route = "/fulfillment/shipments", apiPath = "/api/v1/fulfillment/shipments",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
final class FulfillmentShipmentResourceDeclaration {
    private FulfillmentShipmentResourceDeclaration() { }
}
