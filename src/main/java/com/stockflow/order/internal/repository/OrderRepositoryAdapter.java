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
import com.stockflow.order.internal.domain.ContractOrderSaved;
import org.springframework.context.ApplicationEventPublisher;
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

    private final ApplicationEventPublisher events;

    OrderRepositoryAdapter(OrderJpaRepository jpa, OrderNumberSequence sequence,
                           OrderStatusHistoryJpaRepository history, OrderShipmentJpaRepository shipments,
                           java.time.Clock clock, jakarta.persistence.EntityManager entityManager,
                           ApplicationEventPublisher events) {
        this.events = events;
        this.entityManager = entityManager;
        this.shipments = shipments;
        this.jpa = jpa;
        this.sequence = sequence;
        this.history = history;
        this.clock = clock;
    }

    @Override
    public java.util.List<UUID> waitingForStockAtShippingWarehouse() {
        @SuppressWarnings("unchecked")
        java.util.List<UUID> ids = entityManager.createNativeQuery("""
                        SELECT id FROM ordering.customer_order
                         WHERE status IN ('CONFIRMED', 'AWAITING_STOCK', 'READY_TO_FULFILL', 'PICKING', 'PACKED',
                                          'PARTIALLY_FULFILLED')
                           AND shipping_warehouse_id IS NOT NULL
                         ORDER BY contract_signed_at, id""")
                .getResultList();
        return ids;
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
    public List<com.stockflow.order.api.OrderHoldState> holdStates(java.util.Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : jpa.holdStates(ids);
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
                // is no edge. Every other column already holds its final value, which every step's CHECKs accept -
                // except the hold columns when the order ends ON_HOLD after a step that is not (a payment confirms it,
                // then its lapsed stock holds it: PENDING_PAYMENT → CONFIRMED → ON_HOLD). A step off hold carries
                // none of them, as ck_order_hold_* require; the final values come back for the last step.
                var finalHoldReason = managed.getHoldReason();
                var finalStatusBeforeHold = managed.getStatusBeforeHold();
                var finalHoldNote = managed.getHoldNote();
                for (int i = 0; i < steps.size() - 1; i++) {
                    OrderStatus step = steps.get(i).to();
                    managed.moveTo(step);
                    if (step != OrderStatus.ON_HOLD) {
                        managed.holdColumns(null, null, null);
                    }
                    jpa.saveAndFlush(managed);
                }
                managed.holdColumns(finalHoldReason, finalStatusBeforeHold, finalHoldNote);
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
            announce(order);
            return toDomain(saved);
        }
        OrderJpaEntity inserted = jpa.save(OrderPersistenceMapper.toNewEntity(order));
        // A new order is inserted in the status it was placed in: one history row, from nothing.
        recordTransition(order, new OrderTransition(null, order.status(), null, null, null));
        saveShipments(order);
        announce(order);
        return toDomain(inserted);
    }

    /**
     * 4A-7: an order of a contract saved is announced here, where every path writing one passes, so its delay alerts
     * are recomputed after the commit whichever operation changed it. Other orders have no delivery batches to be late.
     */
    private void announce(Order order) {
        if (order.paidByContract()) {
            events.publishEvent(new ContractOrderSaved(order.id().value()));
        }
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
                    shipment.handedOverBy()).withCarriage(shipment.carriage())
                    .withDeliveryBatch(shipment.deliveryBatchId()));
            row.apply(shipment.status(), shipment.outcomeAt(), shipment.outcomeBy(), shipment.failureReason(),
                    shipment.failureNote());
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
                .and(matches(criteria.search()))
                .and(inWarehouses(criteria.warehouseIds()));
        return jpa.findAll(spec, pageable).map(OrderPersistenceMapper::toListSummary);
    }

    /**
     * Orders shipping from (4B-7) or released to one of {@code ids}; null: no limit. Not {@code Specs.eq}/{@code in},
     * which answer "all" for a missing value: an empty set here is a caller assigned to no warehouse, who sees nothing.
     */
    private static Specification<OrderJpaEntity> inWarehouses(java.util.Set<UUID> ids) {
        if (ids == null) {
            return null;
        }
        return (root, query, cb) -> ids.isEmpty() ? cb.disjunction()
                : cb.or(root.get("shippingWarehouseId").in(ids), root.get("warehouseId").in(ids));
    }

    @Override
    public boolean inWarehouses(UUID orderId, java.util.Set<UUID> warehouseIds) {
        if (orderId == null || warehouseIds == null || warehouseIds.isEmpty()) {
            return false;
        }
        return jpa.count(Specification.<OrderJpaEntity>where((root, query, cb) -> cb.equal(root.get("id"), orderId))
                .and(inWarehouses(warehouseIds))) > 0;
    }

    @Override
    public List<OrderStatusChange> history(UUID orderId) {
        return history.findByOrderIdOrderByOccurredAtAscCreatedAtAsc(orderId).stream()
                .map(row -> new OrderStatusChange(
                        row.getFromStatus() == null ? null : statusOrNull(row.getFromStatus()),
                        statusOrNull(row.getToStatus()), row.getReason(), row.getOccurredAt(), row.getActorId()))
                .toList();
    }

    /**
     * The warehouse scope is applied here, by the order's warehouse ({@code WarehouseScope.warehousesIn}): the order
     * row has no warehouse attribute for the generic data-scope filter, since an order has no warehouse until it is
     * released, and every candidate is released.
     */
    @Override
    public Page<com.stockflow.order.api.FulfillmentCandidate> awaitingTask(UUID warehouseId, Pageable pageable) {
        // createTask's rule (review of #150): READY_TO_FULFILL, and no task yet.
        Specification<OrderJpaEntity> openable = (root, query, cb) -> cb.and(
                cb.isNull(root.get("fulfilmentStartedAt")), cb.equal(root.get("status"), OrderStatus.READY_TO_FULFILL));
        Specification<OrderJpaEntity> spec = Specification.<OrderJpaEntity>where(openable)
                .and(Specs.eq("warehouseId", warehouseId))
                .and(com.stockflow.common.security.WarehouseScope.warehousesIn("warehouseId"));
        Page<OrderJpaEntity> orders = jpa.findAll(spec, pageable);
        Map<UUID, List<com.stockflow.order.api.FulfillmentCandidate.Line>> lines = new HashMap<>();
        List<UUID> ids = orders.getContent().stream().map(OrderJpaEntity::getId).toList();
        if (!ids.isEmpty()) {
            for (Object[] row : jpa.findLineRowsByOrder(ids)) {
                lines.computeIfAbsent((UUID) row[0], id -> new java.util.ArrayList<>())
                        .add(new com.stockflow.order.api.FulfillmentCandidate.Line((UUID) row[1], (String) row[2],
                                ((Number) row[3]).intValue(), row[4] != null));
            }
        }
        return orders.map(order -> new com.stockflow.order.api.FulfillmentCandidate(order.getId(),
                order.getOrderNumber(), order.getCustomerId(), null, order.getWarehouseId(), order.getPlacedAt(),
                order.getReleasedAt(), lines.getOrDefault(order.getId(), List.of())));
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
