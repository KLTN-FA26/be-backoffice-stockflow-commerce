package com.stockflow.inventory.api;

/**
 * Storage condition a SKU needs, which decides the kind of bin it may be put away into
 * (warehouse-map rules in docs/warehouse/06). One value per inventory item, {@link #NORMAL} unless
 * said otherwise ({@code ck_inventory_items_storage_class}).
 *
 * <p>The carrier-facing flags of the old product row (hazmat, oversized) are folded into this: a
 * hazardous good is stored {@link #HAZMAT}, an oversized one {@link #OVERSIZE}.</p>
 */
public enum StorageClass {
    NORMAL,
    COLD,
    HAZMAT,
    FRAGILE,
    OVERSIZE
}
