package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.StorageClass;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Read side for the inventory item list. Outside the domain port for the same reason as {@link StockBrowseSearch}:
 * paging is Spring Data's vocabulary, and the list needs only a few columns of a row that has thirty.
 */
public interface InventoryItemSearch {

    record Row(UUID id, String sku, String unitOfMeasure, String barcode, StorageClass storageClass) {
    }

    /**
     * @param sku exact match
     * @param q   substring of the SKU or the barcode, case-insensitive; {@code %} and {@code _} match themselves
     */
    record Criteria(String sku, String q, StorageClass storageClass) {
    }

    /** Ordered by SKU, which is unique, so a page boundary never moves between two reads. */
    Page<Row> items(Criteria criteria, Pageable pageable);
}
