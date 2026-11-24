package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code sales-lost-shipment-closures} (WF-05 decision Q-12, BE-17): APPROVE closes the units of a shipment the
 * carrier reported lost and confirmed in writing. Declared ahead of its endpoint by the WF-05 shared base (PR-0), so the
 * grant is seeded once (V20261216000050); the order module guards the endpoint with it.
 *
 * <p>A marker, not a bean: {@code PermissionCatalogValidator} scans the classpath for {@code @PermissionResource}.</p>
 */
@PermissionResource(
        code = OrderResources.LOST_SHIPMENT_CLOSURES,
        group = "Sales",
        label = "Close a shipment the carrier lost",
        route = "/sales/orders",
        apiPath = "/api/v1/orders/{orderId}/shipments/{shipmentId}/lost-closure",
        actions = {Action.APPROVE})
final class LostShipmentClosuresResourceDeclaration {

    private LostShipmentClosuresResourceDeclaration() {
    }
}
