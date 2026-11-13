package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * The inventory item a receipt line or a return names: its id and SKU. There is nothing more to ask of it: every SKU is
 * lot-tracked, so every line needs a lot number (kltn-docs 01 BR-17, 03 BR-03; team plan 5-5 removed the per-item
 * flag); and there is no expiry, since cups and packaging have none (01 BR-17, 03 BR-06).
 */
public record InventoryItemPolicy(UUID inventoryItemId, String sku) {
}
