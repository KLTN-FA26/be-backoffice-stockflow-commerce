package com.stockflow.order.api;

/**
 * Where one shipment of an order stands, as far as the order is told (kltn-docs 09 §5 from the handover on): the
 * order learns of a shipment when it is handed over to the carrier, then of the outcome staff record.
 *
 * <pre>
 *   HANDED_OVER ─▶ DELIVERED
 *        └──────▶ DELIVERY_FAILED ─▶ DELIVERED (delivered on a retry) / RETURNED (back at the warehouse)
 * </pre>
 */
public enum OrderShipmentStatus {
    HANDED_OVER,
    DELIVERED,
    DELIVERY_FAILED,
    RETURNED;

    public boolean canTransitionTo(OrderShipmentStatus target) {
        return switch (this) {
            case HANDED_OVER -> target == DELIVERED || target == DELIVERY_FAILED;
            case DELIVERY_FAILED -> target == DELIVERED || target == RETURNED;
            case DELIVERED, RETURNED -> false;
        };
    }
}
