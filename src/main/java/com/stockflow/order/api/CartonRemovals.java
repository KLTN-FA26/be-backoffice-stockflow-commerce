package com.stockflow.order.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The order's history, told that the check station removed a carton (kltn-docs 08 §4.2 step 4; owner decision WF4-F2C,
 * 2026-11-28): staff read it in {@code GET /orders/{id}/history} as an entry of type {@code CARTON_REMOVAL}, whether or
 * not the removal held the order. The entry moves no status, so it has a table of its own
 * ({@code ordering.order_carton_removal}).
 */
public interface CartonRemovals {

    /**
     * Records the removal of {@code lpn} (package {@code packageId}) from the order. In the caller's transaction;
     * idempotent per package (a request handled twice writes one entry).
     *
     * @param reason          why the carton left the order, a {@code RemovalReason} name ({@code DAMAGED})
     * @param note            the station's note on the carton, or null
     * @param continuesPartial whether the order goes on as a partial shipment (Sales allowed it, so it was not held);
     *                        false when the order was held for Sales to decide
     */
    void record(UUID orderId, UUID packageId, String lpn, String reason, String note, boolean continuesPartial,
                UUID by, Instant at);
}
