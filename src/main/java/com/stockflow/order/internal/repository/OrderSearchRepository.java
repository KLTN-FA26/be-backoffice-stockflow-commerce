package com.stockflow.order.internal.repository;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side for list screens. Outside the domain port because paging is Spring Data's vocabulary,
 * which {@code ArchitectureTest} keeps out of {@code internal.domain}.
 */
public interface OrderSearchRepository {

    Page<OrderSummary> findByCustomerId(UUID customerId, Pageable pageable);

    /**
     * @param placedFrom inclusive; null for no lower bound
     * @param placedBefore exclusive; null for no upper bound
     */
    record Criteria(String search, List<OrderStatus> statuses, UUID customerId,
                    Instant placedFrom, Instant placedBefore, java.util.Set<UUID> warehouseIds) {

        /** No warehouse limit. */
        public Criteria(String search, List<OrderStatus> statuses, UUID customerId, Instant placedFrom,
                        Instant placedBefore) {
            this(search, statuses, customerId, placedFrom, placedBefore, null);
        }
    }

    /**
     * Whether the order ships from, or was released to, one of {@code warehouseIds} (team plan 5-7 review M1): what a
     * caller limited to warehouses may read of it. False for an unknown order and for an empty set.
     */
    boolean inWarehouses(UUID orderId, java.util.Set<UUID> warehouseIds);

    /** Rows carry the contact block and shipping address — the list shows the recipient — but no lines. */
    Page<OrderSummary> search(Criteria criteria, Pageable pageable);

    List<OrderStatusChange> history(UUID orderId);

    /**
     * READY_TO_FULFILL orders of {@code warehouseId} (any when null) within the caller's warehouse scope, each with
     * its lines; the customer's name is left for the service to resolve.
     */
    Page<com.stockflow.order.api.FulfillmentCandidate> awaitingTask(UUID warehouseId, Pageable pageable);

    /**
     * How many lines each of these orders has, in one query. An order with no lines is absent from the
     * map; empty input gives an empty map without touching the database.
     */
    Map<UUID, Integer> lineCounts(Collection<UUID> orderIds);

    /**
     * Which of these orders have a shipment handed over (any outcome since), in one query: a list row carries no
     * shipments, and a PARTIALLY_FULFILLED order may have none yet (WF4-PF-STATUS, 2026-11-12). Empty input gives an
     * empty set without touching the database.
     */
    java.util.Set<UUID> withShipments(Collection<UUID> orderIds);
}
