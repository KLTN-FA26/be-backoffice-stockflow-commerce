package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>inventory</b> module when an adjustment asked by hand wrote a whole carton off and was approved
 * (kltn-docs 20 §4.3, §4.8) - damaged, lost, or any other reason; a count's shortage is
 * {@link StockWrittenOffByCount}. The warehouse has a carton fewer of the SKU, so what orders hold of it there by SKU,
 * before their release, may no longer be covered: the consumer, {@code order}, recomputes those holds, and the order
 * that gives a carton up is short again - which is what lets a damaged carton of a production order's goods be made up
 * by a replacement production order (17 §4.13 step 51: the carton held for the order is let go). A carton an order had
 * allocated is not written off by hand: the adjustment is refused while it is held.
 *
 * <p>Consumers receive it in-process through {@code @ApplicationModuleListener}, after the commit; delivery is made
 * durable by Spring Modulith's event publication registry, so a listener must be idempotent.</p>
 *
 * @param sku           the SKU of the carton
 * @param warehouseCode the warehouse the carton was booked in
 * @param quantity      units written off (the whole carton)
 * @param reasonCode    the adjustment's reason, as stored
 * @param decidedBy     who approved the write-off
 */
public record StockCartonWrittenOff(
        String sku,
        String warehouseCode,
        int quantity,
        String reasonCode,
        UUID decidedBy
) {
}
