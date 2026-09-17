package com.stockflow.order.internal.repository;

import com.stockflow.order.api.OrderSummary;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * SCRUM-245/WBS 3.17.7 order-history list — deliberately not part of {@link OrderRepository}, the
 * aggregate's domain port. Same reasoning as {@code procurement.PurchaseOrderSearchRepository}:
 * {@code Page}/{@code Pageable} are banned from {@code internal.domain}, so listing lives here,
 * implemented by the same adapter class.
 *
 * <p>Never fetch-joins {@code lines} for the same reason the sibling search repositories never do —
 * combining it with {@code Pageable} triggers Hibernate's in-memory pagination trap. Every {@link
 * OrderSummary} this returns has empty {@code lines}; only {@code OrderRepository.findById} (a
 * single row) loads them.</p>
 */
public interface OrderSearchRepository {

    Page<OrderSummary> findByCustomerId(UUID customerId, Pageable pageable);
}
