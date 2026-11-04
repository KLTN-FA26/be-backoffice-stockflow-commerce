package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * What receiving must ask of a SKU (docs 01): whether a lot number and an expiry date are
 * mandatory (BR-03), whether it goes through the QC area on the way in (the 3-step flow), and, for an
 * expiry-tracked SKU, the longest shelf life an expiry date may claim at entry (BR-RCP-004).
 *
 * <p>{@code maxShelfLifeDays} is null when no maximum is configured: the expiry date then has no upper bound.</p>
 */
public record InventoryItemPolicy(
        UUID inventoryItemId,
        String sku,
        boolean lotTracked,
        boolean expiryTracked,
        boolean qcRequired,
        Integer maxShelfLifeDays
) {

    /** A policy with no maximum shelf life. */
    public InventoryItemPolicy(UUID inventoryItemId, String sku, boolean lotTracked, boolean expiryTracked,
                               boolean qcRequired) {
        this(inventoryItemId, sku, lotTracked, expiryTracked, qcRequired, null);
    }
}
