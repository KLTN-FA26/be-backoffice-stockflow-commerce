package com.stockflow.order.internal.service;

import com.stockflow.contracts.ReleaseReason;
import com.stockflow.contracts.StockReservationReleased;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Cancels an order whose stock hold ran out before it was paid (kltn-docs 14 BR-07, 15 step 4).
 *
 * <p>Inventory owns the deadline and expires the hold; what that means for the order is the order's
 * business, so it reacts here to the fact inventory announces. Only {@code RESERVATION_EXPIRED}
 * matters: every other release reason is the consequence of something the order already did.</p>
 *
 * <p>Asynchronous and after commit, like every module listener: the sweep is never slowed down or
 * rolled back by the order side, and a failed cancellation is retried from the event registry.</p>
 */
@Component("orderInventoryEventListener")
class InventoryEventListener {

    private final OrderServiceImpl orders;

    InventoryEventListener(OrderServiceImpl orders) {
        this.orders = orders;
    }

    @ApplicationModuleListener
    public void on(StockReservationReleased event) {
        if (event.reason() == ReleaseReason.RESERVATION_EXPIRED) {
            orders.cancelAfterHoldExpired(event.orderId());
        }
    }
}
