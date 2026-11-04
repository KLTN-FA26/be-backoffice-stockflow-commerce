package com.stockflow.warehouse.internal.service;

import java.util.List;
import java.util.Objects;

/**
 * The labels of one print request, in the order they are printed, with the warehouse they belong to.
 *
 * @param warehousePrefix names the downloaded file
 * @param warehouseName   printed on every label, so a label found in the wrong building says so
 */
public record LocationLabelSheet(String warehousePrefix, String warehouseName, List<LocationLabel> labels) {

    public LocationLabelSheet {
        Objects.requireNonNull(warehousePrefix, "warehousePrefix");
        Objects.requireNonNull(warehouseName, "warehouseName");
        labels = List.copyOf(labels);
    }
}
