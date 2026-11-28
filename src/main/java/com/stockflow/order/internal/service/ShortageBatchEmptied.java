package com.stockflow.order.internal.service;

import java.util.UUID;

/**
 * A delivery batch left with no carton by "Giảm số thùng" carried out (kltn-docs 17 §4.14 step 58, D52): closed like a
 * closure of the rest for that batch, so its open production orders are stopped as a closure stops them
 * ({@link SalesOrderCancellationListener}). Inside the order module only, after the commit that emptied it.
 *
 * @param cardCode the shortage card carried out, by its code (UNH-CODE-1128)
 * @param by       who carried it out: the production orders' actor
 */
record ShortageBatchEmptied(UUID orderId, String orderNumber, UUID deliveryBatchId, int batchSeq, String cardCode,
                            UUID by) {
}
