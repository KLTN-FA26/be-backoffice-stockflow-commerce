package com.stockflow.procurement.internal.domain;

import com.stockflow.common.api.PageResponse;

import java.time.Instant;
import java.util.List;
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

    boolean usedByOpenPurchaseOrder(UUID supplierId, UUID inventoryItemId);

    PageResponse<SupplierItem> listBySupplier(UUID supplierId, int page, int size, String sort);

    /** Preferred first, then by supplier name. */
    List<SkuSupplierRow> suppliersOf(UUID inventoryItemId);

    record SkuSupplierRow(SupplierItem item, CatalogSupplier supplier) {}
}
