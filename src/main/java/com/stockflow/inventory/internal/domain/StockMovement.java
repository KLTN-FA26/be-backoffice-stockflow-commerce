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
 * @param lotId    the lot the units belong to (SCRUM-142); null lets the database resolve it from the stock row
 *                 the line touched, when that is unambiguous ({@code tg_stock_movement_lot})
 * @param orderId  the customer order the units left for or came back from (a pick, a put-back, a package),
 *                 so the lot trace reaches the order without reading another module's tables (SCRUM-153)
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
        UUID lotId,
        UUID orderId,
        StockPackageId fromStockPackage,
        StockPackageId toStockPackage
) {

    /** The same line, of the lot {@code lot} (null keeps the database's resolution). */
    public StockMovement ofLot(UUID lot) {
        return new StockMovement(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType,
                referenceId, reason, actorId, occurredAt, lot, orderId, fromStockPackage, toStockPackage);
    }

    /** The same line, naming the customer order the units moved for. */
    public StockMovement forOrder(UUID order) {
        return new StockMovement(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType,
                referenceId, reason, actorId, occurredAt, lotId, order, fromStockPackage, toStockPackage);
    }

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

    /**
     * A line of loose stock on both sides, naming no customer order, whose lot the database resolves from the stock
     * row it touched.
     */
    public StockMovement(UUID id, MovementType type, Sku sku, String lotNumber, LocationId from, LocationId to,
                         int quantity, StockStatus status, StockStatus toStatus, ReferenceType referenceType,
                         UUID referenceId, String reason, UUID actorId, Instant occurredAt) {
        this(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType, referenceId, reason,
                actorId, occurredAt, null, null, null, null);
    }

    /** The same line, the units having left this stock package ({@code null}: loose stock). */
    public StockMovement leaving(StockPackageId stockPackage) {
        return new StockMovement(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType,
                referenceId, reason, actorId, occurredAt, lotId, orderId, stockPackage, toStockPackage);
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
        /**
         * A completed package whose custom-printed goods left stock with it (decision 2026-10-16). Historical only:
         * since G4-Q2 (2026-10-21) printed goods leave when their production order completes and no line names a
         * package any more; the value stays for the lines written before.
         */
        PACKAGE,
        /**
         * A damage report (V20261025030000): units found damaged moved to quarantine as DAMAGED, or a manager's
         * decision restored them to a bin. A write-off references its posted {@code STOCK_ADJUSTMENT} instead.
         */
        DAMAGE_REPORT
    }
}
