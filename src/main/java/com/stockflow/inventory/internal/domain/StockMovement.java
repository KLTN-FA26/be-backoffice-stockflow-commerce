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
        Instant occurredAt
) {

    public StockMovement {
        if (quantity <= 0) {
            throw new IllegalArgumentException("A ledger line moves a positive quantity");
        }
        toStatus = toStatus == null ? status : toStatus;
    }

    /** A line that does not change the status of what it moves. */
    public StockMovement(UUID id, MovementType type, Sku sku, String lotNumber, LocationId from, LocationId to,
                         int quantity, StockStatus status, ReferenceType referenceType, UUID referenceId,
                         String reason, UUID actorId, Instant occurredAt) {
        this(id, type, sku, lotNumber, from, to, quantity, status, status, referenceType, referenceId, reason,
                actorId, occurredAt);
    }

    /** The subset of {@code ck_stock_movement_type} this module writes so far. */
    public enum MovementType { RECEIPT, MOVE, ADJUSTMENT, TRANSFER_OUT }

    /** The subset of {@code ck_stock_movement_reference} this module writes so far. */
    public enum ReferenceType {
        MOVE_TASK, PUTAWAY_TASK, TRANSFER_ORDER_LINE, ORDER, STOCK_ADJUSTMENT, GOODS_RECEIPT_LINE, QC_INSPECTION
    }
}
