package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One whole carton of a production order's lot written off as {@code DAMAGED}, the write-off approved and posted
 * (kltn-docs 20 §4.8, 17 §4.13 step 51): the ground a replacement production order names (17 BR-31). Every adjustment
 * asked since V20261113000300 changes one whole carton, so one write-off is one carton.
 *
 * @param adjustmentId     the posted adjustment
 * @param adjustmentNumber its number, as the adjustment screens show it
 * @param sku              the carton's SKU
 * @param stockPackageId   the carton written off
 * @param lpn              its LPN
 * @param postedAt         when the write-off was posted
 */
public record DamagedWriteOff(UUID adjustmentId, String adjustmentNumber, String sku, UUID stockPackageId, String lpn,
                              Instant postedAt) {

    public DamagedWriteOff {
        Objects.requireNonNull(adjustmentId, "adjustmentId");
        Objects.requireNonNull(adjustmentNumber, "adjustmentNumber");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(stockPackageId, "stockPackageId");
    }
}
