package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.util.Objects;

/**
 * The unit a count is taken on: loose stock of a SKU and lot in one bin, or one LPN carton.
 *
 * <p>Two open counts must never hold the same slot, so equality and {@link #lockKey()} are what the
 * duplicate check and the advisory lock are built on. A blank lot is normalised to null here, once,
 * because "no lot" arrives as null, empty and blank from different callers and would otherwise be
 * three different slots.</p>
 */
public record CountSlot(LocationId location, Sku sku, String lotNumber, StockPackageId stockPackageId) {

    public CountSlot {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(sku, "sku");
        lotNumber = lotNumber == null || lotNumber.isBlank() ? null : lotNumber.trim();
    }

    public static CountSlot loose(LocationId location, Sku sku, String lotNumber) {
        return new CountSlot(location, sku, lotNumber, null);
    }

    public static CountSlot carton(LocationId location, Sku sku, String lotNumber, StockPackageId stockPackageId) {
        return new CountSlot(location, sku, lotNumber, Objects.requireNonNull(stockPackageId, "stockPackageId"));
    }

    public boolean isCarton() {
        return stockPackageId != null;
    }

    /**
     * The advisory-lock key. A carton is identified by its package alone (the LPN is the identity, the bin is
     * where it happens to sit); loose stock by bin, SKU and lot.
     */
    public String lockKey() {
        if (isCarton()) {
            return "pkg|" + stockPackageId.value();
        }
        return location.code() + "|" + sku.code() + "|" + (lotNumber == null ? "" : lotNumber);
    }
}
