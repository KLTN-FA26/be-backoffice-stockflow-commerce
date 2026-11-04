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

    CancellationRequestRepositoryAdapter(OrderCancellationRequestJpaRepository jpa) {
        this.jpa = jpa;
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
