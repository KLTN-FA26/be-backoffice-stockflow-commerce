package com.stockflow.order.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An order's delivery schedule as its customer sees it on the portal ({@code GET /api/v1/orders/me/{orderId}/delivery-
 * batches}, 17 BR-28): each batch's window in force and where it stands, without who moved a window or how many
 * cartons are held. Published for the back-office assistant's reply draft (assistant contract §1.2), which may tell a
 * customer only what the customer may already read about their own order. Read-only.
 */
public interface DeliverySchedules {

    /**
     * One delivery batch.
     *
     * @param state AWAITING_STOCK, READY, IN_DELIVERY, DELIVERED or CLOSED, the order module's names
     * @param late  its window in force has ended and it has not been delivered
     */
    record Batch(int seq, LocalDate windowStart, LocalDate windowEnd, String state, boolean late) {
    }

    /**
     * The schedule in force of {@code customerId}'s order {@code orderId}, earliest batch first; empty for an order
     * without a signed contract or without a schedule (released whole), and for an order that is not the customer's.
     */
    List<Batch> customerSchedule(UUID customerId, UUID orderId);
}
