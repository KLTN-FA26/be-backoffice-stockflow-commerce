package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One damage report as its screen shows it: the damaged goods, where they were found and where they wait, the
 * evidence, and every decision taken on them.
 *
 * @param status          {@code OPEN} while {@code pendingQuantity} units wait for a decision, {@code CLOSED} after
 * @param origin          {@code STOCK_CHECK} (found on a shelf) or {@code PICKING} (found by a picker)
 * @param impairedReservationId PICKING only: the order's allocation left on the damaged units because no other stock
 *                        could replace them; until the coordinator reallocates it, those units cannot be decided
 */
@Schema(name = "DamageReport")
public record DamageReportResponse(
        UUID id,
        @Schema(example = "DMG-20261024-0001") String number,
        String origin,
        String status,
        UUID warehouseId,
        String sku,
        String lotNumber,
        String serialNumber,
        Instant receivedAt,
        UUID sourceStockItemId,
        @Schema(example = "HCM-A01-1-A") String sourceLocationCode,
        UUID sourceStockPackageId,
        UUID damagedStockItemId,
        @Schema(example = "HCM-QC01") String damageLocationCode,
        int quantity,
        int decidedQuantity,
        int pendingQuantity,
        @Schema(example = "BROKEN") String reason,
        String note,
        UUID orderId,
        UUID pickLineId,
        UUID reservationId,
        UUID impairedReservationId,
        UUID reportedBy,
        Instant reportedAt,
        List<Photo> photos,
        List<Disposition> dispositions
) {

    /** A photo; its bytes come from {@code GET .../photos/{photoId}/download-url}. */
    @Schema(name = "DamageReportPhoto")
    public record Photo(UUID id, String originalName, String contentType, long sizeBytes) {
    }

    /**
     * One decision.
     *
     * @param recordNumber the destruction record of a WRITE_OFF ({@code WO-yyyyMMdd-nnnn})
     * @param adjustmentId the posted stock adjustment of a WRITE_OFF
     */
    @Schema(name = "DamageDisposition")
    public record Disposition(UUID id, String disposition, int quantity, String note, String recordNumber,
                              UUID adjustmentId, String restoreLocationCode, UUID supplierId, String supplierName,
                              UUID decidedBy, Instant decidedAt) {
    }
}
