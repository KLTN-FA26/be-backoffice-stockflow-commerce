package com.stockflow.order.internal.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The duplicate-order check of checkout (SCRUM-190, kltn-docs 14 edge case "trùng đơn"): the same customer placing the
 * same content again within a window is asked to confirm before a second order is created, so a customer who orders
 * again from their history, or clicks twice from two devices, does not buy twice by accident.
 *
 * <p>Two orders have the same content when their lines, merged by SKU and design, total the same quantity of each; the
 * order of the lines does not matter. A cancelled order does not count. Not on {@code order :: api}: only this module's
 * controller asks. It is a courtesy to the customer, not an invariant: two requests racing each other may both pass,
 * which is why idempotency stays the job of the request id.</p>
 */
public interface DuplicateOrders {

    /** One line of what is being placed. {@code designSnapshotId} is null for stock goods. */
    record Line(String sku, int quantity, UUID designSnapshotId) {
    }

    /** The order that makes the new one suspect. */
    record Earlier(UUID orderId, String orderNumber, Instant placedAt) {
    }

    /**
     * The customer's most recent live order within the window with the same content, if any. Empty when this
     * {@code requestId} already produced an order: a retry returns that order and is not a duplicate of itself.
     */
    Optional<Earlier> findSuspected(UUID customerId, UUID requestId, List<Line> lines);
}
