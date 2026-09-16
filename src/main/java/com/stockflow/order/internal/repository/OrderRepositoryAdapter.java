package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.entity.OrderJpaEntity;

import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.common.persistence.Specs;
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

    OrderRepositoryAdapter(OrderJpaRepository jpa, OrderNumberSequence sequence) {
        this.jpa = jpa;
        this.sequence = sequence;
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
            OrderPersistenceMapper.applyToEntity(order, managed);
            return OrderPersistenceMapper.toDomain(jpa.save(managed));
        }
        return OrderPersistenceMapper.toDomain(
                jpa.save(OrderPersistenceMapper.toNewEntity(order)));
    }

    @Override
    public Page<OrderSummary> findByCustomerId(UUID customerId, Pageable pageable) {
        Specification<OrderJpaEntity> spec = Specs.eq("customerId", customerId);
        return jpa.findAll(spec, pageable).map(OrderPersistenceMapper::toSummaryWithoutLines);
    }
}
