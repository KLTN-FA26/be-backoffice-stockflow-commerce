package com.stockflow.procurement.internal.domain;

import com.stockflow.common.api.PageResponse;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the supplier catalog: no JPA rows or Specifications escape the adapter. */
public interface SupplierItemRepository {

    /**
     * Reads the supplier {@code FOR SHARE}, so a concurrent deactivation waits for this
     * transaction instead of slipping an inactive supplier in behind a catalog write.
     */
    Optional<CatalogSupplier> supplierForShare(UUID supplierId);

    /**
     * The same slice without the lock, for reads: Postgres refuses {@code FOR SHARE} in a read-only
     * transaction.
     */
    Optional<CatalogSupplier> findSupplier(UUID supplierId);

    Optional<SupplierItem> findById(UUID supplierItemId);

    Optional<SupplierItem> findByIdForUpdate(UUID supplierItemId);

    /**
     * Transaction-scoped advisory lock on one inventory item's catalog, serialising the
     * "at most one preferred supplier" check across rows that no single row lock covers.
     */
    void lockCatalogOf(UUID inventoryItemId);

    Optional<SupplierItem> findPreferred(UUID inventoryItemId);

    /**
     * Inserts or updates; opens the first price period on insert and applies {@code priceChange}
     * otherwise.
     */
    SupplierItem save(SupplierItem item, Optional<PriceChange> priceChange, Instant now);

    void delete(SupplierItem item);

    /**
     * The supplier's catalog rows for the given SKUs in one query, keyed by inventory item id; a SKU the
     * supplier does not list is simply absent. Locked {@code FOR SHARE}, so a concurrent catalog delete
     * waits for the purchase order transaction instead of stranding a draft line on a row that is gone.
     * Postgres refuses the lock in a read-only transaction.
     */
    Map<UUID, SupplierItem> listedForShare(UUID supplierId, Collection<UUID> inventoryItemIds);

    boolean usedByOpenPurchaseOrder(UUID supplierId, UUID inventoryItemId);

    PageResponse<SupplierItem> listBySupplier(UUID supplierId, int page, int size, String sort);

    /** Preferred first, then by supplier name. */
    List<SkuSupplierRow> suppliersOf(UUID inventoryItemId);

    record SkuSupplierRow(SupplierItem item, CatalogSupplier supplier) {}
}
