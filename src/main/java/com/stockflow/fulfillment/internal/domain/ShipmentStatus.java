package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a shipment (kltn-docs 09 §5). Mapped {@code EnumType.STRING}.
 *
 * <pre>
 *   READY_TO_DISPATCH ─handover + manifest─▶ HANDED_OVER ─▶ DELIVERED
 *          │                                      └──────▶ DELIVERY_FAILED ─▶ DELIVERED (delivered on a retry)
 *          └─order cancelled / parcels re-opened─▶ CANCELLED            └──▶ RETURNED (back through a receipt)
 * </pre>
 */
public enum ShipmentStatus {
    READY_TO_DISPATCH,
    HANDED_OVER,
    DELIVERED,
    DELIVERY_FAILED,
    RETURNED,
    CANCELLED;

    /** The 09 §5 table, as one exhaustive switch. */
    public boolean canTransitionTo(ShipmentStatus target) {
        return switch (this) {
            case READY_TO_DISPATCH -> target == HANDED_OVER || target == CANCELLED;
            case HANDED_OVER -> target == DELIVERED || target == DELIVERY_FAILED;
            case DELIVERY_FAILED -> target == DELIVERED || target == RETURNED;
            case DELIVERED, RETURNED, CANCELLED -> false;
        };
    }

    /** A shipment whose goods count as shipped or on their way: not cancelled and not back at the warehouse. */
    public boolean isLive() {
        return this != CANCELLED && this != RETURNED;
    }
}
