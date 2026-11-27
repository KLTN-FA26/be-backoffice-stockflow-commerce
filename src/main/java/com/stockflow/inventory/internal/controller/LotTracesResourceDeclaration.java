package com.stockflow.inventory.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code inventory-lot-traces} (V20261209000300; kltn-docs 20 §3): the lot trace report - origin, cartons
 * left by location, holds, customer orders - and the lot's ledger lines. Split from {@code inventory-stock-movements}
 * because 20 §3 gives the report to Sales (customer care) and the Production Coordinator too, who do not read the
 * whole stock ledger. See {@link ReservationsResourceDeclaration} for why a marker class.
 */
@PermissionResource(
        code = InventoryResources.LOT_TRACES,
        group = "Inventory",
        label = "Lot traceability",
        route = "/inventory/lots",
        apiPath = "/api/v1/inventory/lots",
        actions = {Action.VIEW_PAGE, Action.READ})
final class LotTracesResourceDeclaration {

    private LotTracesResourceDeclaration() {
    }
}
