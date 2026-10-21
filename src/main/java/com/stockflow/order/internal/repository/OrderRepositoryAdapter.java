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
import com.stockflow.order.internal.entity.OrderShipmentJpaEntity;
import com.stockflow.order.internal.entity.OrderStatusHistoryJpaEntity;
import com.stockflow.order.internal.domain.OrderShipment;
import com.stockflow.order.internal.domain.OrderTransition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Implements the order domain port, plus the list-only search interface, on top of Spring Data.
 *  Same shape as {@code procurement.PurchaseOrderRepositoryAdapter} — look up, apply, save. */
@Repository
class OrderRepositoryAdapter implements OrderRepository, OrderSearchRepository {

    private final OrderJpaRepository jpa;
    private final OrderNumberSequence sequence;
    private final OrderStatusHistoryJpaRepository history;
    private final OrderShipmentJpaRepository shipments;
    private final java.time.Clock clock;
    private final jakarta.persistence.EntityManager entityManager;

    OrderRepositoryAdapter(OrderJpaRepository jpa, OrderNumberSequence sequence,
                           OrderStatusHistoryJpaRepository history, OrderShipmentJpaRepository shipments,
                           java.time.Clock clock, jakarta.persistence.EntityManager entityManager) {
        this.entityManager = entityManager;
        this.shipments = shipments;
        this.jpa = jpa;
        this.sequence = sequence;
        this.history = history;
        this.clock = clock;
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return jpa.findByIdWithLines(id.value()).map(this::toDomain);
    }

    /** The order with its shipments (G3-2): a second, small query; most orders have none or one. */
    private Order toDomain(OrderJpaEntity entity) {
        return OrderPersistenceMapper.toDomain(entity,
                shipments.findByOrderIdOrderByHandedOverAtAscIdAsc(entity.getId()));
    }

    /**
     * The row lock, then the row as it is now. A copy already in the persistence context - {@link #findByIdInScope}
     * reads one before it locks - is not re-read by the locking query, so without the refresh the caller would decide
     * on the state from before the wait: a cancellation would not see the package completed while it waited (audit
     * of #109). Pending changes were flushed by the query itself, so the refresh loses nothing.
     */
    @Override
    public Optional<Order> findByIdForUpdate(OrderId id) {
        return jpa.findByIdForUpdate(id.value())
                .map(locked -> {
                    entityManager.refresh(locked);
                    return locked;
                })
                .flatMap(locked -> jpa.findByIdWithLines(locked.getId()))
                .map(this::toDomain);
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
        // The probe is let go before the lock: kept managed, the locking query would upgrade it with a version check
        // and answer a concurrent change (a package completed while this waited) with an optimistic-lock failure
        // instead of the current row the caller must decide on (audit of #109).
        return jpa.findByIdInScope(id.value())
                .flatMap(scoped -> {
                    entityManager.detach(scoped);
                    return findByIdForUpdate(new OrderId(scoped.getId()));
                });
    }

    @Override
    public Optional<Order> findByRequestId(UUID requestId) {
        return jpa.findByRequestIdWithLines(requestId).map(this::toDomain);
    }

    @Override
    public List<Order> findByCustomerId(UUID customerId) {
        return jpa.findByCustomerIdWithLines(customerId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public OrderNumber nextOrderNumber(LocalDate date) {
        return OrderNumber.of(date, sequence.nextFor(date));
    }

    @Override
    public Order save(Order order) {
        Optional<OrderJpaEntity> existing = jpa.findByIdWithLines(order.id().value());
        List<OrderTransition> steps = order.pullTransitions();
        if (existing.isPresent()) {
            OrderJpaEntity managed = existing.get();
            OrderStatus previous = managed.getStatus();
            OrderPersistenceMapper.applyToEntity(order, managed);
            boolean stepwise = !steps.isEmpty() && steps.getFirst().from() == previous
                    && steps.getLast().to() == order.status();
            if (stepwise) {
                // The row passes through every edge, so tg_order_status_transition checks each one: resolving a hold
                // may move the order on at once (ON_HOLD → PARTIALLY_FULFILLED → DELIVERED), and ON_HOLD → DELIVERED
                // is no edge. Every other column already holds its final value, which every step's CHECKs accept.
                for (int i = 0; i < steps.size() - 1; i++) {
                    managed.moveTo(steps.get(i).to());
                    jpa.saveAndFlush(managed);
                }
                managed.moveTo(order.status());
                steps.forEach(step -> recordTransition(order, step));
            } else if (previous != order.status()) {
                recordTransition(order, new OrderTransition(previous, order.status(), null, null, null));
            }
            // saveAndFlush, not save: @PreUpdate (which sets lastModifiedAt/lastModifiedBy) only
            // runs at flush time, so reading the entity back from a plain save() here would return
            // its stale pre-update audit fields even though the eventual UPDATE is correct. Flushed before the
            // shipments are read, so the replaced lines are written first.
            OrderJpaEntity saved = jpa.saveAndFlush(managed);
            saveShipments(order);
            return toDomain(saved);
        }
        OrderJpaEntity inserted = jpa.save(OrderPersistenceMapper.toNewEntity(order));
        // A new order is inserted in the status it was placed in: one history row, from nothing.
        recordTransition(order, new OrderTransition(null, order.status(), null, null, null));
        saveShipments(order);
        return toDomain(inserted);
    }

    /** The order's shipments: a new one is inserted with its units, a known one takes its status and outcome. */
    private void saveShipments(Order order) {
        if (order.shipments().isEmpty()) {
            return;
        }
        Map<UUID, OrderShipmentJpaEntity> rows = new HashMap<>();
        shipments.findByOrderIdOrderByHandedOverAtAscIdAsc(order.id().value())
                .forEach(row -> rows.put(row.getId(), row));
        for (OrderShipment shipment : order.shipments()) {
            OrderShipmentJpaEntity row = rows.computeIfAbsent(shipment.id(), id -> new OrderShipmentJpaEntity(id,
                    order.id().value(), shipment.quantities(), shipment.amount(), shipment.handedOverAt(),
                    shipment.handedOverBy()).withCod(shipment.codAmount()));
            row.apply(shipment.status(), shipment.outcomeAt(), shipment.outcomeBy(), shipment.failureReason(),
                    shipment.failureNote(), shipment.billedAmount());
            shipments.save(row);
        }
        shipments.flush();
    }

    /**
     * Status history is written here, where every save passes, rather than at each transition in
     * the service: a transition added later is recorded without anyone remembering to. Each change carries who made
     * it and why (SCRUM-247): a cancellation's reason, a hold's reason and note, a failed delivery's reason.
     */
    private void recordTransition(Order order, OrderTransition step) {
        String reason = step.reason();
        if (reason == null && step.to() == OrderStatus.CANCELLED) {
            reason = order.cancellationReason();
        }
        if (reason != null && reason.length() > 500) {
            reason = reason.substring(0, 500);
        }
        history.save(new OrderStatusHistoryJpaEntity(Identifiers.newId(), order.id().value(),
                step.from() == null ? null : step.from().name(), step.to().name(), reason, step.actorId(),
                step.at() == null ? clock.instant() : step.at()));
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
                        statusOrNull(row.getToStatus()), row.getReason(), row.getOccurredAt(), row.getActorId()))
                .toList();
    }

    @Override
    public Map<UUID, Integer> lineCounts(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new HashMap<>();
        for (Object[] row : jpa.countLinesByOrder(orderIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * A status name this build no longer knows reads as null rather than failing the whole history.
     * PAID, recorded before SCRUM-460, meant what CONFIRMED means now; the append-only history keeps
     * the word, the timeline shows the state.
     */
    private static OrderStatus statusOrNull(String name) {
        if ("PAID".equals(name)) {
            return OrderStatus.CONFIRMED;
        }
        // IN_FULFILMENT, recorded before G3-2, was the warehouse working on the order: picking, as far as history
        // can tell (V20261021000100 moved live rows to PICKING or PACKED).
        if ("IN_FULFILMENT".equals(name)) {
            return OrderStatus.PICKING;
        }
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
