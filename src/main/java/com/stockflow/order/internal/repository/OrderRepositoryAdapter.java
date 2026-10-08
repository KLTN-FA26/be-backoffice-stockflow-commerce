package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.entity.OrderJpaEntity;

import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Specs;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.internal.entity.OrderStatusHistoryJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Implements the order domain port, plus the list-only search interface, on top of Spring Data.
 *  Same shape as {@code procurement.PurchaseOrderRepositoryAdapter} — look up, apply, save. */
@Repository
class OrderRepositoryAdapter implements OrderRepository, OrderSearchRepository {

    private final OrderJpaRepository jpa;
    private final OrderNumberSequence sequence;
    private final OrderStatusHistoryJpaRepository history;
    private final java.time.Clock clock;

    OrderRepositoryAdapter(OrderJpaRepository jpa, OrderNumberSequence sequence,
                           OrderStatusHistoryJpaRepository history, java.time.Clock clock) {
        this.jpa = jpa;
        this.sequence = sequence;
        this.history = history;
        this.clock = clock;
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return jpa.findByIdWithLines(id.value()).map(OrderPersistenceMapper::toDomain);
    }

    @Override
    public Optional<Order> findByIdForUpdate(OrderId id) {
        return jpa.findByIdForUpdate(id.value())
                .flatMap(locked -> jpa.findByIdWithLines(locked.getId()))
                .map(OrderPersistenceMapper::toDomain);
    }

    /**
     * Ownership check via the inherited, ambient-scoped {@code findByIdInScope} (no lock, no fetch
     * join — it exists purely to answer "may the caller see this row?"), then the real fetch
     * through the already-proven {@link #findByIdForUpdate} path once that answer is yes. Two
     * queries rather than teaching one query both jobs, so each half stays exactly as tested as it
     * already was.
     */
    @Override
    public Optional<Order> findByIdInScope(OrderId id) {
        return jpa.findByIdInScope(id.value())
                .flatMap(scoped -> findByIdForUpdate(new OrderId(scoped.getId())));
    }

    @Override
    public Optional<Order> findByRequestId(UUID requestId) {
        return jpa.findByRequestIdWithLines(requestId).map(OrderPersistenceMapper::toDomain);
    }

    @Override
    public List<Order> findByCustomerId(UUID customerId) {
        return jpa.findByCustomerIdWithLines(customerId).stream()
                .map(OrderPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public OrderNumber nextOrderNumber(LocalDate date) {
        return OrderNumber.of(date, sequence.nextFor(date));
    }

    @Override
    public Order save(Order order) {
        Optional<OrderJpaEntity> existing = jpa.findByIdWithLines(order.id().value());
        if (existing.isPresent()) {
            OrderJpaEntity managed = existing.get();
            recordTransition(order, managed.getStatus());
            OrderPersistenceMapper.applyToEntity(order, managed);
            // saveAndFlush, not save: @PreUpdate (which sets lastModifiedAt/lastModifiedBy) only
            // runs at flush time, so reading the entity back from a plain save() here would return
            // its stale pre-update audit fields even though the eventual UPDATE is correct.
            return OrderPersistenceMapper.toDomain(jpa.saveAndFlush(managed));
        }
        Order saved = OrderPersistenceMapper.toDomain(
                jpa.save(OrderPersistenceMapper.toNewEntity(order)));
        recordTransition(order, null);
        return saved;
    }

    /**
     * Status history is written here, where every save passes, rather than at each transition in
     * the service: a transition added later is recorded without anyone remembering to.
     */
    private void recordTransition(Order order, OrderStatus previous) {
        if (order.status() == previous) {
            return;
        }
        history.save(new OrderStatusHistoryJpaEntity(Identifiers.newId(), order.id().value(),
                previous == null ? null : previous.name(), order.status().name(),
                order.status() == OrderStatus.CANCELLED ? order.cancellationReason() : null,
                clock.instant()));
    }

    @Override
    public Page<OrderSummary> search(Criteria criteria, Pageable pageable) {
        Specification<OrderJpaEntity> spec = Specification.<OrderJpaEntity>where(
                        Specs.eq("customerId", criteria.customerId()))
                .and(statusIn(criteria.statuses()))
                .and(placedBetween(criteria.placedFrom(), criteria.placedBefore()))
                .and(matches(criteria.search()));
        return jpa.findAll(spec, pageable).map(OrderPersistenceMapper::toListSummary);
    }

    @Override
    public List<OrderStatusChange> history(UUID orderId) {
        return history.findByOrderIdOrderByOccurredAtAscCreatedAtAsc(orderId).stream()
                .map(row -> new OrderStatusChange(
                        row.getFromStatus() == null ? null : statusOrNull(row.getFromStatus()),
                        statusOrNull(row.getToStatus()), row.getReason(), row.getOccurredAt()))
                .toList();
    }

    /** A status name this build no longer knows reads as null rather than failing the whole history. */
    private static OrderStatus statusOrNull(String name) {
        try {
            return OrderStatus.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static Specification<OrderJpaEntity> statusIn(List<OrderStatus> statuses) {
        // Empty means "not filtered" here, which Specs.in would read as "match nothing".
        return statuses == null || statuses.isEmpty() ? null : Specs.in("status", statuses);
    }

    private static Specification<OrderJpaEntity> placedBetween(java.time.Instant from, java.time.Instant before) {
        if (from == null && before == null) {
            return null;
        }
        return (root, query, cb) -> {
            var path = root.<java.time.Instant>get("placedAt");
            if (from == null) {
                return cb.lessThan(path, before);
            }
            return before == null ? cb.greaterThanOrEqualTo(path, from)
                    : cb.and(cb.greaterThanOrEqualTo(path, from), cb.lessThan(path, before));
        };
    }

    /** Contains, case-insensitive, over the fields a coordinator types from a phone call. */
    private static Specification<OrderJpaEntity> matches(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return Specification.<OrderJpaEntity>where(Specs.contains("orderNumber", search))
                .or(Specs.contains("contactName", search))
                .or(Specs.contains("contactPhone", search))
                .or(Specs.contains("shippingAddress.recipientName", search))
                .or(Specs.contains("shippingAddress.phone", search));
    }

    @Override
    public Page<OrderSummary> findByCustomerId(UUID customerId, Pageable pageable) {
        Specification<OrderJpaEntity> spec = Specs.eq("customerId", customerId);
        return jpa.findAll(spec, pageable).map(OrderPersistenceMapper::toSummaryWithoutLines);
    }
}
