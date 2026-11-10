package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; delivery is made durable by Spring Modulith's
 * event publication registry, so a listener that crashes mid-handling gets the event again.
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record OrderReleased(
        UUID orderId,
        String warehouseCode,
        Instant releasedAt,
        /*
         * 4B-8 (kltn-docs 17 §4.5, BR-29): the delivery batch released - payment makes due what that batch waits for
         * (15 §4.1). Null for an order without a delivery schedule, and in events written before batches were released
         * one by one: every installment before delivery is then due.
         */
        UUID deliveryBatchId
) {

    /** Without a delivery batch: the whole order released at once. */
    public OrderReleased(UUID orderId, String warehouseCode, Instant releasedAt) {
        this(orderId, warehouseCode, releasedAt, null);
    }
}
