package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * What receiving must ask of a SKU (docs 01): whether a lot number and an expiry date are
 * mandatory (BR-03), and whether it goes through the QC area on the way in (the 3-step flow).
 */
public record InventoryItemPolicy(
        UUID inventoryItemId,
        String sku,
        boolean lotTracked,
        boolean expiryTracked,
        boolean qcRequired
) {
}
