package com.stockflow.order.internal.service;

import com.stockflow.contracts.PutawayCompleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Goods put away, or a hold that came free, are stock to promise: the orders of a contract waiting at a warehouse -
 * CONFIRMED, or AWAITING_STOCK for a production order's goods (4A-6) - hold what they can now, the earliest signed first
 * (4B-7, kltn-docs 17 §4.2 step 3: "hàng nhập về sau bù đợt sớm nhất còn thiếu"; §4.3 step 11). A failure for one order
 * is logged and the next arrival tries again. Returns put back on a bin come through the put-away they get (4D): a
 * quarantine release's, which announces {@code QuarantineStockReleased} rather than {@code PutawayCompleted}.
 */
@Component("orderStockArrivalListener")
class StockArrivalListener {

    private final ShippingWarehouseService shippingWarehouses;

    StockArrivalListener(ShippingWarehouseService shippingWarehouses) {
        this.shippingWarehouses = shippingWarehouses;
    }

    @ApplicationModuleListener
    public void on(PutawayCompleted event) {
        shippingWarehouses.topUpWaiting();
    }

    /**
     * A carton left quarantine and was put away (WF-05 fix plan, section 2): how the cartons of a shipment the carrier
     * brought back become stock again. They are held for the order they came back for first - its delivery failure
     * case wants them, decided or not - and only what is left is anyone else's to top up from.
     */
    @ApplicationModuleListener
    public void on(com.stockflow.contracts.QuarantineStockReleased event) {
        shippingWarehouses.topUpWaiting();
    }

    /**
     * A hold came free - an order cancelled, a hold released by hand, a checkout's hold expired: its units are stock to
     * promise again, to the orders short of that SKU.
     */
    @ApplicationModuleListener
    public void on(com.stockflow.contracts.StockReservationReleased event) {
        shippingWarehouses.topUpWaiting(event.sku());
    }
}
