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
 * @param stockPackage the carton the line is about (ADR-0011, V20261230000200). One, not one per side: stock is whole
 *                 sealed cartons, so nothing is ever taken out of one carton into another, and whether the carton
 *                 arrived, moved or left is said by {@code type} and by which of {@code from} / {@code to} is set.
 *                 Every line written now names it ({@code ck_stock_movement_names_carton}); null only on a line read
 *                 back from before stock was only cartons
 * @param requestedBy who asked for the adjustment an {@code ADJUSTMENT} line posts (WF-05 fix plan, section 8); null on
 *                 every other line. {@code actorId} is who approved it: the ledger alone then names both pairs of eyes
 * @param countedBy   who counted the line, when that adjustment is a cycle count's variance; null otherwise
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
        StockPackageId stockPackage,
        UUID requestedBy,
        UUID countedBy
) {

    /** A line that posts no adjustment: nobody requested or counted it. */
    public StockMovement(UUID id, MovementType type, Sku sku, String lotNumber, LocationId from, LocationId to,
                         int quantity, StockStatus status, StockStatus toStatus, ReferenceType referenceType,
                         UUID referenceId, String reason, UUID actorId, Instant occurredAt, UUID lotId, UUID orderId,
                         StockPackageId stockPackage) {
        this(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType, referenceId, reason,
                actorId, occurredAt, lotId, orderId, stockPackage, null, null);
    }

    /**
     * The same line, posting an adjustment {@code requester} asked for and, for a count's variance, {@code counter}
     * counted. Only an {@code ADJUSTMENT} line names them ({@code ck_stock_movement_adjustment_people}).
     */
    public StockMovement askedBy(UUID requester, UUID counter) {
        return new StockMovement(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType,
                referenceId, reason, actorId, occurredAt, lotId, orderId, stockPackage, requester, counter);
    }

    /** The same line, of the lot {@code lot} (null keeps the database's resolution). */
    public StockMovement ofLot(UUID lot) {
        return new StockMovement(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType,
                referenceId, reason, actorId, occurredAt, lot, orderId, stockPackage, requestedBy, countedBy);
    }

    /** The same line, naming the customer order the units moved for. */
    public StockMovement forOrder(UUID order) {
        return new StockMovement(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType,
                referenceId, reason, actorId, occurredAt, lotId, order, stockPackage, requestedBy, countedBy);
    }

    public StockMovement {
        if (quantity <= 0) {
            throw new IllegalArgumentException("A ledger line moves a positive quantity");
        }
        // ck_stock_movement_adjustment_people: only the posting of an adjustment names who asked and who counted.
        if ((requestedBy != null || countedBy != null) && type != MovementType.ADJUSTMENT) {
            throw new IllegalArgumentException("Only an ADJUSTMENT line names who requested and who counted");
        }
        toStatus = toStatus == null ? status : toStatus;
    }

    /**
     * A line naming no customer order, whose lot the database resolves from the stock row it touched, and no carton
     * yet: {@link #ofCarton} names it before the line is appended.
     */
    public StockMovement(UUID id, MovementType type, Sku sku, String lotNumber, LocationId from, LocationId to,
                         int quantity, StockStatus status, StockStatus toStatus, ReferenceType referenceType,
                         UUID referenceId, String reason, UUID actorId, Instant occurredAt) {
        this(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType, referenceId, reason,
                actorId, occurredAt, null, null, null);
    }

    /**
     * The same line, about this carton. There is no "leaving" and "arriving" carton any more (ADR-0011): a line is one
     * whole carton, and {@code from} / {@code to} say which way it went.
     */
    public StockMovement ofCarton(StockPackageId carton) {
        return new StockMovement(id, type, sku, lotNumber, from, to, quantity, status, toStatus, referenceType,
                referenceId, reason, actorId, occurredAt, lotId, orderId, carton, requestedBy, countedBy);
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
        CONSUMPTION,
        /** Goods a customer sent back under a return request, into quarantine (SCRUM-260, OQ-12). */
        RETURN_IN,
        /**
         * Legacy: damaged goods a damage report handed back to their supplier (V20261101030000). Damage reports went with
         * team plan 5-6 (V20261114000400); the value stays for the lines written before.
         */
        SUPPLIER_RETURN
    }

    /** The subset of {@code ck_stock_movement_reference} this module writes so far. */
    public enum ReferenceType {
        MOVE_TASK, PUTAWAY_TASK,
        /** Legacy: lines transfers between warehouses wrote (dispatch); nothing writes one any more. */
        TRANSFER_ORDER_LINE,
        ORDER, STOCK_ADJUSTMENT, GOODS_RECEIPT_LINE, QC_INSPECTION, PICK_LINE,
        /** Legacy: lines in-house production wrote (issue, hand-over, completion); nothing writes one any more. */
        PRODUCTION_ORDER,
        EXPIRY_SWEEP,
        /**
         * A completed package whose custom-printed goods left stock with it (decision 2026-10-16). Historical only:
         * from G4-Q2 (2026-10-21) printed goods left when their production order completed, and since in-house
         * production was removed they are picked like any stock; the value stays for the lines written before.
         */
        PACKAGE,
        /**
         * Legacy: a damage report (V20261101030000) moved units found damaged to quarantine as DAMAGED, or restored them
         * to a bin. Damage reports went with team plan 5-6 (V20261114000400); the value stays for the lines written
         * before.
         */
        DAMAGE_REPORT,
        /** A return line (SCRUM-260): goods counted in from the customer, and out of quarantine again. */
        RETURN_REQUEST_LINE,
        /**
         * The carton loose or opened stock was boxed into when every unit went into a whole carton (V20261111000110,
         * team plan 4C-1): a MOVE that stays in place, into the carton this names, or that relabels a carton's units
         * of no lot as lot {@code UNTRACED}. Historical only: the application never repacks, it moves whole cartons.
         */
        STOCK_PACKAGE,
        /**
         * A return receipt (team plan 4D-2; kltn-docs 03 §4.2, BR-14): a carton that came back, received whole into
         * quarantine, one RECEIPT line per carton naming it ({@code ck_stock_movement_return_receipt}). The receipt
         * itself, not a line of it: a return receipt has cartons, not lines.
         */
        GOODS_RECEIPT,
        /**
         * A return request that refunded nothing (WF-05 Q-4): its goods were never agreed back, so the stock-in of each
         * of its cartons is reversed - one {@code ADJUSTMENT} line out of quarantine, the whole carton
         * ({@code ck_stock_movement_return_refused}) - and the carton is held for the customer.
         */
        RETURN_REQUEST
    }
}
