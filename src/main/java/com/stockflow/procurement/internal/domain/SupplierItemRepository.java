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
     * Inserts a {@linkplain SupplierItem#isNew new} item or updates a stored one; opens the first price
     * period on insert and applies {@code priceChange} otherwise. An update that finds no open period (a
     * row carried over without history) opens one at {@code now} even when the price is unchanged.
     */
    SupplierItem save(SupplierItem item, Optional<PriceChange> priceChange, Instant now);

    void delete(SupplierItem item);

    boolean usedByOpenPurchaseOrder(UUID supplierId, UUID inventoryItemId);

    PageResponse<SupplierItem> listBySupplier(UUID supplierId, int page, int size, String sort);

    /** By supplier name; the service puts the item's default supplier first. */
    List<SkuSupplierRow> suppliersOf(UUID inventoryItemId);

    record SkuSupplierRow(SupplierItem item, CatalogSupplier supplier) {}
}
