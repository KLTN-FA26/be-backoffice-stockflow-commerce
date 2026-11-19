package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * The order goes to its warehouse to be shipped - released (kltn-docs 17 §4.2 step 5), or, for an order AWAITING_STOCK
 * picked before its release whose part held goes towards dispatch first with a partial shipment allowed, its first
 * delivery ready (owner decision SCOPE-PARTIAL-1105; 17 §4.5 step 18). Either way payment makes due the installments
 * before delivery it names (15 §4.1 step 2); in the second case the order is not released yet, and the release that
 * follows once its batch is held in full publishes this again for the same batch (an installment already due stays as
 * it is).
 *
 * <p>Published by the <b>order</b> module. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; delivery is made durable by Spring Modulith's
 * event publication registry, so a listener that crashes mid-handling gets the event again.
 * See {@code package-info.java} for the rules on changing this contract.</p>
 */
public record OrderReleased(
        UUID orderId,
        String warehouseCode,
        Instant releasedAt,
        /*
         * 4B-8 (kltn-docs 17 §4.5, BR-29): the delivery batch released - or whose part held goes first (SCOPE-PARTIAL-1105)
         * - payment makes due what that batch waits for (15 §4.1). Null for an order without a delivery schedule, and in
         * events written before batches were released one by one: every installment before delivery is then due.
         */
        UUID deliveryBatchId
) {

    /** Without a delivery batch: the whole order released at once. */
    public OrderReleased(UUID orderId, String warehouseCode, Instant releasedAt) {
        this(orderId, warehouseCode, releasedAt, null);
    }
}
