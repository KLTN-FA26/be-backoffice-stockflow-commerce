package com.stockflow.warehouse.internal.controller;

/**
 * Permission resource codes of the warehouse module. Exactly the codes seeded in
 * {@code V20260903000100} and {@code V20260928006000}: a code that differs by one character is a
 * permission no role can be granted.
 */
public final class WarehouseResources {

    /** Warehouses: create, edit, (de)activate. */
    public static final String WAREHOUSES = "warehouse-warehouses";
    /** Everything on a map: zones, shelves, levels, bins, areas, boundaries, storage locations. */
    public static final String LOCATIONS = "warehouse-locations";

    private WarehouseResources() {
    }
}
