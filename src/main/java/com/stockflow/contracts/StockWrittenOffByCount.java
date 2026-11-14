package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>inventory</b> module when an approved count variance wrote a carton off (kltn-docs 20 §4.2 step 8,
 * a count's shortage), whatever the carton held. The warehouse has a carton fewer of the SKU, so what orders hold of it
 * there by SKU, before their release, may no longer be covered (WF4-COUNT-EDGE a): the consumer, {@code order},
 * recomputes those holds. A carton an order had allocated is announced on its own as {@link StockHeldCartonWrittenOff}.
 * Consumers receive it in-process through {@code @ApplicationModuleListener}, after the commit; delivery is made durable
 * by Spring Modulith's event publication registry, so a listener must be idempotent.
 *
 * @param sku           the SKU of the carton
 * @param warehouseCode the warehouse the carton was booked in
 * @param quantity      units written off (the whole carton)
 * @param decidedBy     who approved the variance
 */
public record StockWrittenOffByCount(
        String sku,
        String warehouseCode,
        int quantity,
        UUID decidedBy
) {
}
