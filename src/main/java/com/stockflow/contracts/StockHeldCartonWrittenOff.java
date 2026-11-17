package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>inventory</b> module when an approved count variance wrote off a carton an order still held (an
 * allocation on it): the carton is not there, so the hold was given up and what it stood for is a shortage of the
 * order (kltn-docs 20 section 4.2 step 8, 07 section 4.4). The consumer, {@code fulfillment}, turns the order's pick
 * line for that hold SHORT and holds the order for stock ({@code INVENTORY}), where the warehouse manager reallocates
 * it. Consumers receive it in-process through {@code @ApplicationModuleListener}, after the commit; delivery is made
 * durable by Spring Modulith's event publication registry, so a listener must be idempotent.
 *
 * @param orderId       the order the given-up hold was for
 * @param reservationId the given-up hold (allocation); a pick line stands on it by this id
 * @param sku           the SKU of the carton
 * @param locationCode  where the carton was booked
 * @param quantity      units the hold covered (the whole carton)
 * @param decidedBy     who approved the shortage; the order's hold is recorded in their name
 */
public record StockHeldCartonWrittenOff(
        UUID orderId,
        UUID reservationId,
        String sku,
        String locationCode,
        int quantity,
        UUID decidedBy
) {
}
