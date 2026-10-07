package com.stockflow.inventory.internal.controller;

/**
 * Permission resource codes owned by the inventory module.
 *
 * <p>Constants rather than string literals for the same reason {@code Roles} exists: Java
 * annotation arguments must be compile-time constants, so {@code @RequiresPermission} cannot take
 * an enum for the resource. A typo in a literal is a silent 403 that takes an afternoon to find; a
 * typo in a constant name does not compile.</p>
 *
 * <p>Each code appears exactly twice — once on the {@code @PermissionResource} that declares it,
 * once per {@code @RequiresPermission} that guards an endpoint — and
 * {@code PermissionCatalogValidator} fails startup if a guard names a resource no controller
 * declares.</p>
 */
public final class InventoryResources {

    // Hyphens only, no dots: PermissionCode enforces [a-z0-9-]{2,64} so a code that cannot be
    // rendered in the matrix is rejected at startup rather than at the first 403.
    public static final String STOCK_ITEMS = "inventory-stock-items";
    public static final String RESERVATIONS = "inventory-reservations";

    private InventoryResources() {
    }
}
