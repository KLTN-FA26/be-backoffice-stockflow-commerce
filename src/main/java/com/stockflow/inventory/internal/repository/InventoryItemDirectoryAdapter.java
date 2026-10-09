package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.internal.domain.InventoryItemDirectory;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One read by primary key on this module's own table; no entity until the item screens need one. */
@Repository
class InventoryItemDirectoryAdapter implements InventoryItemDirectory {

    private final EntityManager entityManager;

    InventoryItemDirectoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<InventoryItemPolicy> policyOf(UUID inventoryItemId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT sku, lot_tracked, expiry_tracked, qc_required
                          FROM inventory.inventory_items WHERE id = :id""")
                .setParameter("id", inventoryItemId)
                .getResultList();
        return rows.stream().findFirst().map(row -> new InventoryItemPolicy(inventoryItemId, (String) row[0],
                (Boolean) row[1], (Boolean) row[2], (Boolean) row[3]));
    }

    @Override
    public Optional<InventoryItemPolicy> policyOf(String sku) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, lot_tracked, expiry_tracked, qc_required
                          FROM inventory.inventory_items WHERE sku = :sku""")
                .setParameter("sku", sku)
                .getResultList();
        return rows.stream().findFirst().map(row -> new InventoryItemPolicy((UUID) row[0], sku,
                (Boolean) row[1], (Boolean) row[2], (Boolean) row[3]));
    }

    @Override
    public java.util.Map<UUID, String> skusOf(java.util.Collection<UUID> inventoryItemIds) {
        if (inventoryItemIds.isEmpty()) {
            return java.util.Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT id, sku FROM inventory.inventory_items WHERE id IN (:ids)")
                .setParameter("ids", java.util.Set.copyOf(inventoryItemIds))
                .getResultList();
        var skus = new java.util.HashMap<UUID, String>();
        rows.forEach(row -> skus.put((UUID) row[0], (String) row[1]));
        return skus;
    }
}
