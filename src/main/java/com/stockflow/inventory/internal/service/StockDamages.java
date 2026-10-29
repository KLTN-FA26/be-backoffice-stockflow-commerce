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

    /**
     * A return-to-supplier decision carried out: all its units handed to the supplier, or the supplier's carrier,
     * under a delivery note. They leave quarantine and the books in this transaction - on_hand of the DAMAGED row falls,
     * one {@code SUPPLIER_RETURN} ledger line, reference {@code DAMAGE_REPORT}. The debit note is procurement's. The
     * same request again answers with the report as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code DAMAGE_REPORT_NOT_FOUND}; {@code NOT_FOUND} for a
     *         decision not on the report; {@code DAMAGE_RETURN_NOT_SHIPPABLE} for another kind of decision or one
     *         shipped already; {@code DAMAGED_STOCK_HELD}, {@code CONFLICT} when the units are no longer there unheld;
     *         {@code VALIDATION_FAILED} without a delivery note; {@code OUT_OF_DATA_SCOPE}
     */
    DamageReportView shipToSupplier(UUID reportId, UUID dispositionId, ShipToSupplierCommand command);

    /**
     * The supplier refused some units of a return-to-supplier decision, or took fewer than were offered: they stay in
     * quarantine and wait for a decision again. The same request again answers with the report as it is.
     *
     * @throws com.stockflow.common.error.BusinessException as {@link #shipToSupplier}; {@code VALIDATION_FAILED}
     *         without a note
     */
    DamageReportView refuseReturn(UUID reportId, UUID dispositionId, RefuseReturnCommand command);

    /** @throws com.stockflow.common.error.BusinessException {@code DAMAGE_REPORT_NOT_FOUND}, also outside the caller's warehouses */
    DamageReportView find(UUID reportId);

    /** Reports, newest first, of the caller's warehouses; {@code open} true is the manager's queue. */
    /** @param status OPEN, AWAITING_SUPPLIER or CLOSED; null for every report */
    PageResponse<DamageReportRow> list(UUID warehouseId, String status, String sku, String origin, int page, int size,
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

    /** @param quantity units handed over now; null for all the decision has left */
    record ShipToSupplierCommand(UUID requestId, Integer quantity, String deliveryNote, String note, UUID shippedBy) {
    }

    record RefuseReturnCommand(UUID requestId, int quantity, String note, UUID recordedBy) {
    }

    /** One report as a screen shows it. {@code status} OPEN while units wait for a decision, CLOSED after. */
    record DamageReportView(UUID id, String number, String origin, String status, UUID warehouseId, String sku,
                            String lotNumber, String serialNumber, Instant receivedAt, UUID sourceStockItemId,
                            String sourceLocationCode, UUID sourceStockPackageId, UUID damagedStockItemId,
                            String damageLocationCode, int quantity, int decidedQuantity, int pendingQuantity,
                            String reason, String note, UUID orderId, UUID pickLineId, UUID reservationId,
                            UUID impairedReservationId, UUID reportedBy, Instant reportedAt, List<PhotoView> photos,
                            List<DispositionView> dispositions, int awaitingSupplierQuantity,
                            List<SupplierHandOverView> supplierHandOvers) {
    }

    /** Units of a return-to-supplier decision handed over ({@code SHIPPED}, under a delivery note) or refused. */
    record SupplierHandOverView(UUID id, UUID dispositionId, String outcome, int quantity, String deliveryNote,
                                String note, UUID recordedBy, Instant recordedAt) {
    }

    /** A photo of a report; its bytes are fetched through {@link #photoLink}. */
    record PhotoView(UUID id, String originalName, String contentType, long sizeBytes) {
    }

    /** One decision of a report. */
    /**
     * @param handedOverQuantity RETURN_TO_SUPPLIER: units handed to the supplier so far
     * @param refusedQuantity    RETURN_TO_SUPPLIER: units the supplier refused (they wait for a decision again)
     */
    record DispositionView(UUID id, String disposition, int quantity, String note, String recordNumber,
                           UUID adjustmentId, String restoreLocationCode, UUID supplierId, String supplierName,
                           UUID decidedBy, Instant decidedAt, int handedOverQuantity, int refusedQuantity) {
    }

    /** One report in a list. */
    record DamageReportRow(UUID id, String number, String origin, String status, UUID warehouseId, String sku,
                           String lotNumber, String sourceLocationCode, String damageLocationCode, int quantity,
                           int decidedQuantity, int pendingQuantity, String reason, UUID orderId, UUID reportedBy,
                           Instant reportedAt, int awaitingSupplierQuantity) {
    }
}
