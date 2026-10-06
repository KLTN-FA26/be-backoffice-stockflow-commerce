package com.stockflow.warehouse.internal.domain;

/**
 * What owns a storage location: a bin on a shelf level, or a storage area on the floor. Decides the
 * shape of its location code - {@code prefix-shelf-level-bin} or {@code prefix-area}.
 */
public enum StorageLocationKind {
    BIN,
    AREA
}
