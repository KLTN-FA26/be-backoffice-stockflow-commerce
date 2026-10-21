package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the stock ledger ({@code inventory.stock_movement}): what moved, from where to
 * where, and which document caused it. Append-only — a mistake is corrected by a new line, never by
 * editing an old one.
 *
 * @param from     null when stock arrived from outside (a receipt, a write-up)
 * @param to       null when stock left (a pick, a write-off)
 * @param status   the status the units had before the line; for a receipt, the status they arrived in
 * @param toStatus the status they have after it. Equal to {@code status} except when a QC decision or
 *                 a putaway changes it on the way (INBOUND to QUARANTINE, BLOCKED or AVAILABLE)
 * @param fromStockPackage the stock package (carton) the units left, if any (V20261020000300); only on a line
 *                 with a {@code from}
 * @param toStockPackage   the stock package they went into, if any; only on a line with a {@code to}
 */
public record StockMovement(
        UUID id,
        MovementType type,
        Sku sku,
        String lotNumber,
        LocationId from,
        LocationId to,
        int quantity,
        StockStatus status,
        StockStatus toStatus,
        ReferenceType referenceType,
        UUID referenceId,
        String reason,
        UUID actorId,
        Instant occurredAt,
        StockPackageId fromStockPackage,
        StockPackageId toStockPackage
) {

    public StockMovement {
        if (quantity <= 0) {
            throw new IllegalArgumentException("A ledger line moves a positive quantity");
        }
        // ck_stock_movement_package_side: a side's stock package belongs to that side's location.
        if ((fromStockPackage != null && from == null) || (toStockPackage != null && to == null)) {
            throw new IllegalArgumentException("A stock package is named only on a side that has a location");
        }
        toStatus = toStatus == null ? status : toStatus;
    }

    /** A line that names no stock package: loose stock on both sides. */
    public StockMovement(UUID id, MovementType type, Sku sku, String lotNumber, LocationId from, LocationId to,
                         int quantity, StockStatus status, StockStatus toStatus, ReferenceType referenceType,
                         UUID referenceId, String reason, UUID actorId, Instant occurredAt) {
        this(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType, referenceId, reason,
                actorId, occurredAt, null, null);
    }

    /** The same line, the units having left this stock package ({@code null}: loose stock). */
    public StockMovement leaving(StockPackageId stockPackage) {
        return new StockMovement(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType,
                referenceId, reason, actorId, occurredAt, stockPackage, toStockPackage);
    }

    /** A line that does not change the status of what it moves. */
    public StockMovement(UUID id, MovementType type, Sku sku, String lotNumber, LocationId from, LocationId to,
                         int quantity, StockStatus status, ReferenceType referenceType, UUID referenceId,
                         String reason, UUID actorId, Instant occurredAt) {
        this(id, type, sku, lotNumber, from, to, quantity, status, status, referenceType, referenceId, reason,
                actorId, occurredAt);
    }

    /** The subset of {@code ck_stock_movement_type} this module writes so far. */
    public enum MovementType {
        RECEIPT, MOVE, ADJUSTMENT, TRANSFER_OUT, STATUS_CHANGE, PICK, PUT_BACK,
        /** Held blanks a print subcontractor turned into goods received back (SCRUM-441, decision Q9). */
        CONSUMPTION
    }

    /** The subset of {@code ck_stock_movement_reference} this module writes so far. */
    public enum ReferenceType {
        MOVE_TASK, PUTAWAY_TASK, TRANSFER_ORDER_LINE, ORDER, STOCK_ADJUSTMENT, GOODS_RECEIPT_LINE, QC_INSPECTION, PICK_LINE,
        PRODUCTION_ORDER, EXPIRY_SWEEP,
        /** A completed package: its custom-printed goods leave stock with it (decision 2026-10-16). */
        PACKAGE
    }
}
