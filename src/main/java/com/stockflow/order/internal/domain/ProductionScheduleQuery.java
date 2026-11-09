package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read port of the production schedule and the delay flags (4A-7, kltn-docs 17 §4.12): one statement per kind of
 * fact, all inside the {@code ordering} schema - warehouse codes and customer names are the caller's to look up.
 */
public interface ProductionScheduleQuery {

    /** An open production order as the schedule shows it (step 47). */
    record ScheduledProduction(UUID productionOrderId, String code, ProductionOrderStatus status, UUID producerId,
                               String producerCode, String producerName, UUID salesOrderId, String orderNumber,
                               UUID customerId, UUID deliveryBatchId, int batchSeq, LocalDate windowStart,
                               LocalDate windowEnd, UUID receivingWarehouseId, LocalDate needByDate,
                               LocalDate expectedCompletionDate, int cartons, int receivedCartons) {
    }

    /** A sales order whose batches the schedule or the sweep looks at. */
    record FollowedOrder(UUID salesOrderId, String orderNumber, UUID customerId, UUID shippingWarehouseId,
                         String status) {
    }

    /** An alert with what names its subject, for the order page and the alert list. */
    record AlertView(UUID id, DelayAlertType type, UUID salesOrderId, String orderNumber, UUID productionOrderId,
                     String productionOrderCode, UUID deliveryBatchId, Integer batchSeq, UUID warehouseId,
                     Instant openedAt, Instant closedAt) {
    }

    /**
     * The production orders still open (DRAFT to PARTIALLY_RECEIVED), by need-by day then code. Every filter null means
     * "any"; {@code from} / {@code to} bound the need-by day, both included; {@code warehouses} not null: only those
     * received at one of them (a warehouse-bound caller's).
     */
    List<ScheduledProduction> openProductionOrders(UUID producerId, UUID warehouseId, LocalDate from, LocalDate to,
                                                   Collection<UUID> warehouses);

    /** The expected completion day of each production order of the sales order that has one (17 §4.3 step 8). */
    Map<UUID, LocalDate> expectedCompletionDates(UUID salesOrderId);

    /**
     * The orders in one of {@code statuses} with a signed contract, oldest signed first. {@code warehouseId} and
     * {@code warehouses} as for {@link #openProductionOrders}, on the order's shipping warehouse (an order with none
     * passes neither).
     */
    List<FollowedOrder> ordersIn(Collection<String> statuses, UUID warehouseId, Collection<UUID> warehouses);

    /** One page of alerts and how many there are in all. */
    record AlertPage(List<AlertView> items, long total) {
    }

    /**
     * Alerts, newest first: of one sales order and type when given, open ones only when {@code openOnly}; with
     * {@code warehouses} not null, only those whose warehouse - read where it lives now - is one of them.
     */
    AlertPage alerts(UUID salesOrderId, DelayAlertType type, boolean openOnly, Collection<UUID> warehouses, int limit,
                     long offset);
}
