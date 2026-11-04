package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code fulfillment-vehicle-types} (V20261022000700): READ for warehouse staff, the warehouse
 * manager and the order coordinator; UPDATE for the warehouse manager (SYSTEM_ADMIN holds all).
 */
@PermissionResource(code = FulfillmentResources.VEHICLE_TYPES, group = "Fulfillment", label = "Vehicle types",
        route = "/fulfillment/vehicle-types", apiPath = "/api/v1/fulfillment/vehicle-types",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE})
final class FulfillmentVehicleTypeResourceDeclaration {
    private FulfillmentVehicleTypeResourceDeclaration() { }
}
