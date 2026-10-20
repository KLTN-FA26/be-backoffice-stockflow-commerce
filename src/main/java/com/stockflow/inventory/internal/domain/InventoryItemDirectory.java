package com.stockflow.inventory.internal.domain;

import com.stockflow.inventory.api.InventoryItemPolicy;

import java.util.Optional;
import java.util.UUID;

/**
 * Read port over {@code inventory.inventory_items}: what receiving must ask of a SKU. A port rather
 * than an aggregate because it only reads; edits to an item go through {@code InventoryItems}, which
 * applies the rules against the same table.
 */
public interface InventoryItemDirectory {

    Optional<InventoryItemPolicy> policyOf(UUID inventoryItemId);

    Optional<InventoryItemPolicy> policyOf(String sku);

    /** The receiving policy of each item that exists; one query for a whole list of lines (SCRUM-464). */
    java.util.Map<UUID, InventoryItemPolicy> policiesOf(java.util.Collection<UUID> inventoryItemIds);

    /** The SKU of each item that exists; one query for a whole list of lines. */
    java.util.Map<UUID, String> skusOf(java.util.Collection<UUID> inventoryItemIds);

    /** The id of the item of each SKU that has one; one query for a whole list of SKUs. */
    java.util.Map<String, UUID> itemIdsOf(java.util.Collection<String> skus);
}
