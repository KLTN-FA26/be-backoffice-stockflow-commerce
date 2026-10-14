package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;

import java.util.Objects;

/**
 * What one printed location label says (SCRUM-97). {@code code} is the whole payload of both the
 * barcode and the QR code; everything else is only for the person reading the label.
 *
 * @param shelfCode  the bin's shelf; null for an area, as are {@code levelIndex} and {@code binCode}
 * @param areaName   the area's name; null for a bin
 */
public record LocationLabel(String code, StorageLocationKind kind, String shelfCode, Integer levelIndex,
                            String binCode, String areaName, StorageClass storageClass) {

    public LocationLabel {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(storageClass, "storageClass");
    }
}
