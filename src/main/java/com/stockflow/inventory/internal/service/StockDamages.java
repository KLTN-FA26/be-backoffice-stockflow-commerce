package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.inventory.internal.domain.DamageDisposition;
import com.stockflow.inventory.internal.domain.DamageReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Damaged stock (SCRUM-475; docs 03 §5, BR-STK-003): reported on a shelf with photos, decided by a warehouse manager.
 * The pick-time report is {@code inventory :: consumption}'s {@code PickDamageReporting}; both write the same report.
 *
 * <p>Inside {@code internal}, like {@link StockOperations}: only this module's web layer calls it. No other module
 * reports a shelf's damage, and the one that reports a pick's has its own door.</p>
 */
public interface StockDamages {

    /**
     * Free units of one stock row found damaged. They move, DAMAGED, to a quarantine area of the warehouse (one
     * {@code MOVE} ledger line), leave available-to-promise at once, and wait for a decision. Idempotent on the
     * request id. Photos are stored first, before any lock, and deleted again if anything after them fails.
     *
     * @throws com.stockflow.common.error.BusinessException {@code STOCK_ITEM_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code DAMAGE_PHOTO_REQUIRED}, {@code UNSUPPORTED_MEDIA_TYPE}, {@code PAYLOAD_TOO_LARGE};
     *         {@code STOCK_STATUS_MISMATCH} unless AVAILABLE; {@code STOCK_EXPIRED};
     *         {@code DAMAGE_EXCEEDS_FREE_STOCK} beyond the free units; {@code DAMAGE_AREA_MISSING},
     *         {@code LOCATION_NOT_FOUND}, {@code LOCATION_NOT_USABLE}, {@code LOCATION_AREA_MISMATCH} for the
     *         quarantine area; {@code VALIDATION_FAILED} (OTHER without a note, a serial unit counted twice)
     */
    DamageReportView report(ReportDamageCommand command, List<FileUpload> photos);

    /**
     * A warehouse manager decides some of a report's units: written off (a DAMAGED stock adjustment posted at once,
     * the reporter as requester, the decider as approver, and a destruction record number), sent back to the
     * supplier (intent only), or restored to a bin as AVAILABLE. Never the reporter (four eyes). Idempotent on the
     * request id.
     *
     * @throws com.stockflow.common.error.BusinessException {@code DAMAGE_REPORT_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code DAMAGE_SELF_DISPOSITION}; {@code DAMAGE_DISPOSITION_EXCEEDS_PENDING}; {@code DAMAGED_STOCK_HELD}
     *         while an order still holds the units; {@code DAMAGE_RESTORE_LOCATION_INVALID}, {@code STOCK_EXPIRED},
     *         {@code STOCK_STATUS_MISMATCH} for a restore; {@code VALIDATION_FAILED} for a missing note
     */
    DamageReportView dispose(UUID reportId, DisposeDamageCommand command);

    /** @throws com.stockflow.common.error.BusinessException {@code DAMAGE_REPORT_NOT_FOUND}, also outside the caller's warehouses */
    DamageReportView find(UUID reportId);

    /** Reports, newest first, of the caller's warehouses; {@code open} true is the manager's queue. */
    PageResponse<DamageReportRow> list(UUID warehouseId, Boolean open, String sku, String origin, int page, int size,
                                       String sort);

    /** A short-lived link to one photo, for the browser to fetch from object storage directly. */
    DownloadLink photoLink(UUID reportId, UUID photoId);

    /** The usable quarantine areas of a warehouse, by code: where the screen lets the reporter take damaged goods. */
    List<String> damageAreas(UUID warehouseId);

    /** Free units of one stock row, found damaged. */
    record ReportDamageCommand(UUID requestId, UUID stockItemId, int quantity, DamageReason reason, String note,
                               String damageLocationCode, UUID reportedBy) {
    }

    /** A manager's decision on some units of a report. */
    record DisposeDamageCommand(UUID requestId, DamageDisposition disposition, int quantity, String note,
                                String restoreLocationCode, UUID decidedBy) {
    }

    /** One report as a screen shows it. {@code status} OPEN while units wait for a decision, CLOSED after. */
    record DamageReportView(UUID id, String number, String origin, String status, UUID warehouseId, String sku,
                            String lotNumber, String serialNumber, Instant receivedAt, UUID sourceStockItemId,
                            String sourceLocationCode, UUID sourceStockPackageId, UUID damagedStockItemId,
                            String damageLocationCode, int quantity, int decidedQuantity, int pendingQuantity,
                            String reason, String note, UUID orderId, UUID pickLineId, UUID reservationId,
                            UUID impairedReservationId, UUID reportedBy, Instant reportedAt, List<PhotoView> photos,
                            List<DispositionView> dispositions) {
    }

    /** A photo of a report; its bytes are fetched through {@link #photoLink}. */
    record PhotoView(UUID id, String originalName, String contentType, long sizeBytes) {
    }

    /** One decision of a report. */
    record DispositionView(UUID id, String disposition, int quantity, String note, String recordNumber,
                           UUID adjustmentId, String restoreLocationCode, UUID supplierId, String supplierName,
                           UUID decidedBy, Instant decidedAt) {
    }

    /** One report in a list. */
    record DamageReportRow(UUID id, String number, String origin, String status, UUID warehouseId, String sku,
                           String lotNumber, String sourceLocationCode, String damageLocationCode, int quantity,
                           int decidedQuantity, int pendingQuantity, String reason, UUID orderId, UUID reportedBy,
                           Instant reportedAt) {
    }
}
