package com.stockflow.warehouse.internal.domain;

/**
 * What owns a storage location: a bin on a shelf level, a storage area on the floor, or a print subcontractor
 * holding this warehouse's blanks (V20261017000470, decision Q7). Decides the shape of its location code -
 * {@code prefix-shelf-level-bin}, {@code prefix-area} or {@code prefix-SUB-supplier}.
 */
public enum StorageLocationKind {
    BIN,
    AREA,
    /**
     * Legacy. Not on the map: blanks a print subcontractor held for this warehouse, outside available-to-promise.
     * Subcontracting went with purchasing (4A-8) and no location of this kind is made any more; the constant stays
     * for the rows that have it until 4A-9 archives {@code warehouse.subcontractor_site}.
     */
    SUBCONTRACTOR
}
