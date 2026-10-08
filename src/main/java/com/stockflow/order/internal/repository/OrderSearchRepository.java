package com.stockflow.order.internal.repository;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
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
                    Instant placedFrom, Instant placedBefore) {
    }

    /** Rows carry the contact block and shipping address — the list shows the recipient — but no lines. */
    Page<OrderSummary> search(Criteria criteria, Pageable pageable);

    List<OrderStatusChange> history(UUID orderId);
}
