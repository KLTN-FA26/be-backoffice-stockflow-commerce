package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * What receiving must ask of a SKU (docs 01): whether a lot number is mandatory (BR-03). There is nothing to ask about
 * expiry: cups and packaging have none, and a receipt records none (01 BR-17, 03 BR-06).
 */
public record InventoryItemPolicy(UUID inventoryItemId, String sku, boolean lotTracked) {
}
