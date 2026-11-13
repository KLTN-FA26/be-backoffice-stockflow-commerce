package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.util.Objects;

/**
 * The unit a count is taken on: one LPN carton, at the bin it is recorded at (kltn-docs 20 §4.2 a2: one count line per
 * carton in stock), with the SKU and lot it holds.
 *
 * <p>Two open counts must never hold the same carton (BR-10), so equality and {@link #lockKey()} are what the duplicate
 * check and the advisory lock are built on.</p>
 *
 * <p>A count states cartons present or not found, never loose units (BR-14; team plan 4C-3): {@link #carton} is the
 * only way to make a slot to count. A slot with no carton is only ever read back from a line written before that
 * rule ({@link #isCarton()} false), as history; nothing counts, recounts or claims it again.</p>
 */
public record CountSlot(LocationId location, Sku sku, String lotNumber, StockPackageId stockPackageId) {

    public CountSlot {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(sku, "sku");
        lotNumber = lotNumber == null || lotNumber.isBlank() ? null : lotNumber.trim();
    }

    public static CountSlot carton(LocationId location, Sku sku, String lotNumber, StockPackageId stockPackageId) {
        return new CountSlot(location, sku, lotNumber, Objects.requireNonNull(stockPackageId, "stockPackageId"));
    }

    /** False only for a loose line of the history (written before counts were by carton). */
    public boolean isCarton() {
        return stockPackageId != null;
    }

    /**
     * The advisory-lock key: the carton alone - the LPN is the identity, the bin only where it happens to sit. A loose
     * slot of the history keys by bin, SKU and lot, as it did.
     */
    public String lockKey() {
        if (isCarton()) {
            return "pkg|" + stockPackageId.value();
        }
        return location.code() + "|" + sku.code() + "|" + (lotNumber == null ? "" : lotNumber);
    }
}
