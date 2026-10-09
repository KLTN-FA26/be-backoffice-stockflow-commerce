package com.stockflow.inventory.internal.domain;

import com.stockflow.inventory.api.InventoryItemPolicy;

import java.util.Optional;
import java.util.UUID;

/**
 * Read port over {@code inventory.inventory_items}: what receiving must ask of a SKU. A port rather
 * than an aggregate because nothing in this module edits inventory items yet; the inventory-item
 * screens (SCRUM-70) will own the table and can implement this from their own repository.
 */
public interface InventoryItemDirectory {

    Optional<InventoryItemPolicy> policyOf(UUID inventoryItemId);
}
