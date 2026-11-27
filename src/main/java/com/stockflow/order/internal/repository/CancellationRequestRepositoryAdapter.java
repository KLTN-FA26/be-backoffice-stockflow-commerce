package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.Specs;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.CancellationRequestStatus;
import com.stockflow.order.internal.entity.OrderCancellationRequestJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class CancellationRequestRepositoryAdapter implements CancellationRequestRepository, CancellationRequestSearch {

    private final OrderCancellationRequestJpaRepository jpa;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    CancellationRequestRepositoryAdapter(OrderCancellationRequestJpaRepository jpa,
                                         org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.jpa = jpa;
        this.jdbc = jdbc;
    }

    @Override
    public java.util.Map<UUID, OrderFacts> orderFacts(java.util.Collection<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<UUID, OrderFacts> facts = new java.util.HashMap<>();
        jdbc.query("""
                SELECT id, order_number, customer_id, status, total_amount, currency, printing_started_at
                  FROM ordering.customer_order WHERE id IN (:ids)""",
                java.util.Map.of("ids", java.util.List.copyOf(orderIds)), rs -> {
                    java.sql.Timestamp printing = rs.getTimestamp("printing_started_at");
                    UUID id = rs.getObject("id", UUID.class);
                    facts.put(id, new OrderFacts(id, rs.getString("order_number"),
                            rs.getObject("customer_id", UUID.class), rs.getString("status"),
                            rs.getBigDecimal("total_amount")
                                    .setScale(0, java.math.RoundingMode.HALF_UP),
                            rs.getString("currency"), printing == null ? null : printing.toInstant()));
                });
        return facts;
    }

    @Override
    public Optional<CancellationRequest> findById(UUID id) {
        return jpa.findById(id).map(CancellationRequestRepositoryAdapter::toDomain);
    }

    @Override
    public boolean hasPending(UUID orderId) {
        return jpa.existsByOrderIdAndStatus(orderId, CancellationRequestStatus.PENDING);
    }

    @Override
    public Optional<CancellationRequest> findPending(UUID orderId) {
        return jpa.findFirstByOrderIdAndStatus(orderId, CancellationRequestStatus.PENDING)
                .map(CancellationRequestRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<CancellationRequest> latestOf(UUID orderId) {
        return jpa.findFirstByOrderIdOrderByRequestedAtDescIdDesc(orderId)
                .map(CancellationRequestRepositoryAdapter::toDomain);
    }

    @Override
    public java.util.Map<UUID, CancellationRequestStatus> latestStatuses(java.util.Collection<UUID> orderIds) {
        java.util.Map<UUID, CancellationRequestStatus> latest = new java.util.HashMap<>();
        if (orderIds.isEmpty()) {
            return latest;
        }
        // Newest first: the first row of each order is its latest request.
        jpa.findByOrderIdsNewestFirst(orderIds).forEach(row -> latest.putIfAbsent(row.getOrderId(), row.getStatus()));
        return latest;
    }

    @Override
    public CancellationRequest save(CancellationRequest request) {
        OrderCancellationRequestJpaEntity entity = jpa.findById(request.id()).orElseGet(() ->
                new OrderCancellationRequestJpaEntity(request.id(), request.orderId(), request.requestedBy(),
                        request.reasonCode(), request.note(), request.requestedAt()));
        entity.decide(request.status(), request.decidedBy(), request.decidedAt(), request.decisionNote(),
                request.retainedPercent());
        return toDomain(jpa.saveAndFlush(entity));
    }

    @Override
    public Page<CancellationRequest> search(CancellationRequestStatus status, UUID orderId, Pageable pageable) {
        Specification<OrderCancellationRequestJpaEntity> spec = Specification
                .<OrderCancellationRequestJpaEntity>where(Specs.eq("status", status))
                .and(Specs.eq("orderId", orderId));
        return jpa.findAll(spec, pageable).map(CancellationRequestRepositoryAdapter::toDomain);
    }

    private static CancellationRequest toDomain(OrderCancellationRequestJpaEntity e) {
        return new CancellationRequest(e.getId(), e.getOrderId(), e.getRequestedBy(), e.getReasonCode(), e.getNote(),
                e.getRequestedAt(), e.getStatus(), e.getDecidedBy(), e.getDecidedAt(), e.getDecisionNote(),
                e.getRetainedPercent(), e.getVersion());
    }
}
