package com.stockflow.warehouse.internal.domain;

/**
 * What owns a storage location: a bin on a shelf level, a storage area on the floor, or a print subcontractor
 * holding this warehouse's blanks (V20261015000470, decision Q7). Decides the shape of its location code -
 * {@code prefix-shelf-level-bin}, {@code prefix-area} or {@code prefix-SUB-supplier}.
 */
public enum StorageLocationKind {
    BIN,
    AREA,
    /** Not on the map: blanks a subcontractor holds for this warehouse, outside available-to-promise. */
    SUBCONTRACTOR
}
