package com.stockflow.order.internal.service;

import com.stockflow.contracts.StockCartonWrittenOff;
import com.stockflow.contracts.StockWrittenOffByCount;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A carton written off leaves the warehouse a carton fewer of its SKU, so what orders hold of it there by SKU is
 * recomputed and the part no carton covers any more is given up - the order is short of it again, as at its
 * confirmation ({@link ShippingWarehouseService#giveUpUncovered}). Two announcements, the same recompute:
 * <ul>
 *   <li>an approved count's shortage (WF4-COUNT-EDGE a, kltn-docs 20 §4.2 step 8);</li>
 *   <li>an approved write-off asked by hand - damaged, lost, any reason (20 §4.3, §4.8). Without it a damaged carton
 *       held for an order stayed counted as held until the pick came up short, and the order never looked short
 *       enough for its replacement production order to be drawn up (17 §4.13 steps 51-52, SCRUM-839).</li>
 * </ul>
 *
 * <p>After the commit and asynchronous ({@code @ApplicationModuleListener}): the approval holds the SKU lock, and the
 * recompute takes the order's row before it, as every writer of holds does. A failure is thrown, so the publication
 * stays incomplete in the registry and is retried; the recompute is idempotent.</p>
 */
@Component
class CountWriteOffListener {

    private final ShippingWarehouseService shippingWarehouses;

    CountWriteOffListener(ShippingWarehouseService shippingWarehouses) {
        this.shippingWarehouses = shippingWarehouses;
    }

    @ApplicationModuleListener
    public void on(StockWrittenOffByCount event) {
        shippingWarehouses.giveUpUncovered(event.sku(), event.warehouseCode());
    }

    @ApplicationModuleListener
    public void on(StockCartonWrittenOff event) {
        shippingWarehouses.giveUpUncovered(event.sku(), event.warehouseCode());
    }
}
