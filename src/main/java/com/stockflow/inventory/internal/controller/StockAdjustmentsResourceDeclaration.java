package com.stockflow.inventory.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/** Declares {@code inventory-stock-adjustments}; see {@link ReservationsResourceDeclaration} for why a marker class. */
@PermissionResource(
        code = InventoryResources.STOCK_ADJUSTMENTS,
        group = "Inventory",
        label = "Stock adjustments",
        route = "/inventory/adjustments",
        apiPath = "/api/v1/inventory/adjustments",
        actions = {Action.VIEW_PAGE, Action.READ, Action.APPROVE})
final class StockAdjustmentsResourceDeclaration {

    private StockAdjustmentsResourceDeclaration() {
    }
}
