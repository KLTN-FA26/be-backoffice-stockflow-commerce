package com.stockflow.order.internal.service;

import com.stockflow.contracts.StockWrittenOffByCount;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * WF4-COUNT-EDGE (a), kltn-docs 20 §4.2 step 8: an approved count wrote a carton off, so what orders hold of its SKU at
 * that warehouse by SKU is recomputed and the part no carton covers any more is given up - the order is short of it
 * again, as at its confirmation ({@link ShippingWarehouseService#giveUpUncovered}).
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
}
