package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** Quantities are in pieces and again in cartons (kltn-docs 01 BR-09). A stock row is the contents of one carton. */
@Schema(name = "WarehouseStockItem", description = "One stock row of a warehouse, in any status (SCRUM-462)")
public record WarehouseStockItemResponse(
        UUID stockItemId,
        String sku,
        @Schema(example = "HCM-A01-2-B") String locationCode,
        String lotNumber,
        @Schema(description = "Pieces in one carton of this SKU; null when it has no carton unit", nullable = true,
                example = "1000") Integer unitsPerCarton,
        @Schema(description = "Physical units present, including reserved ones; null while hiddenDuringCount",
                nullable = true) Integer onHand,
        @Schema(description = "Units held for orders; null while hiddenDuringCount", nullable = true) Integer reserved,
        @Schema(description = "onHand minus reserved; zero unless AVAILABLE; null while "
                + "hiddenDuringCount", nullable = true) Integer available,
        @Schema(description = "onHand in cartons; null while hiddenDuringCount, when there is no carton unit, or when "
                + "it is not a whole number of cartons", nullable = true) Integer onHandCartons,
        @Schema(description = "reserved in cartons; null as for onHandCartons", nullable = true)
        Integer reservedCartons,
        @Schema(description = "available in cartons; null as for onHandCartons", nullable = true)
        Integer availableCartons,
        @Schema(description = "INBOUND, AVAILABLE, QUARANTINE, BLOCKED, DAMAGED or EXPIRED") String status,
        Instant receivedAt,
        @Schema(description = "A cycle count holds this slot and the caller lacks inventory-cycle-counts:APPROVE: the "
                + "quantities are withheld so the count stays blind")
        boolean hiddenDuringCount
) {
}
