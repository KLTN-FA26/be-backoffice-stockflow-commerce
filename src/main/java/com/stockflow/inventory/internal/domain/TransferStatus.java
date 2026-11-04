package com.stockflow.inventory.internal.domain;

/**
 * Transfer order lifecycle (docs 10 §5, SCRUM-327). Mapped {@code EnumType.STRING} onto
 * {@code ck_transfer_order_status}.
 *
 * <pre>
 *   DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──pick──▶ PICKING ──dispatch──▶ IN_TRANSIT
 *     │ └──submit, under the threshold──────────────▶ APPROVED ◀──cancel pick── PICKING
 *     │                    └──reject──▶ DRAFT
 *     └──cancel──▶ CANCELLED ◀──cancel── APPROVED
 *   IN_TRANSIT ──receive──▶ PARTIALLY_RECEIVED / RECEIVED ──▶ COMPLETED / CLOSED   (SCRUM-328)
 * </pre>
 *
 * <p>No journey is tracked: a transfer stays IN_TRANSIT until the destination counts it in.</p>
 */
public enum TransferStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    PICKING,
    IN_TRANSIT,
    PARTIALLY_RECEIVED,
    RECEIVED,
    COMPLETED,
    CLOSED,
    CANCELLED;

    /** BR-04: once the goods have left the source, a transfer can only be closed, never cancelled. */
    public boolean canTransitionTo(TransferStatus target) {
        return switch (this) {
            case DRAFT -> target == PENDING_APPROVAL || target == APPROVED || target == CANCELLED;
            case PENDING_APPROVAL -> target == APPROVED || target == DRAFT;
            case APPROVED -> target == PICKING || target == CANCELLED;
            case PICKING -> target == IN_TRANSIT || target == APPROVED;
            case IN_TRANSIT -> target == PARTIALLY_RECEIVED || target == RECEIVED;
            case PARTIALLY_RECEIVED -> target == RECEIVED || target == CLOSED;
            case RECEIVED -> target == COMPLETED;
            case COMPLETED, CLOSED, CANCELLED -> false;
        };
    }
}
