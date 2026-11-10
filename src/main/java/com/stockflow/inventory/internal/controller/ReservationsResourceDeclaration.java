package com.stockflow.inventory.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares the second permission resource this module owns.
 *
 * <p>{@code @PermissionResource} is a type-level annotation and {@link InventoryController} can
 * only carry one, yet the controller serves two distinct resources: reading stock and managing
 * reservations. They are separated because the permissions genuinely differ — a warehouse clerk
 * reads stock all day and must never be able to cancel a customer's hold.</p>
 *
 * <p>An empty marker class is the cheapest way to declare the second row. The alternative, a
 * second controller with one endpoint in it, splits a cohesive API across two files to satisfy an
 * annotation limit.</p>
 *
 * <p>It is deliberately <b>not</b> a Spring bean — there is nothing to instantiate. That works
 * because {@code PermissionCatalogValidator} scans the classpath for {@code @PermissionResource}
 * rather than walking the bean registry. Had it walked the registry, this declaration would have
 * been invisible, the resource would have been missing from the catalog, and the two endpoints it
 * guards would have returned 403 to everyone with no way to grant the permission.</p>
 *
 * <p>No CREATE: a hold is taken by Sales choosing the order's shipping warehouse (kltn-docs 17 §4.2), in-process,
 * never over HTTP. The checkout's reservation endpoint went with V20261117000300.</p>
 */
@PermissionResource(
        code = InventoryResources.RESERVATIONS,
        group = "Inventory",
        label = "Stock reservations",
        route = "/inventory/reservations",
        apiPath = "/api/v1/inventory/reservations",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE, Action.DELETE})
final class ReservationsResourceDeclaration {

    private ReservationsResourceDeclaration() {
    }
}
