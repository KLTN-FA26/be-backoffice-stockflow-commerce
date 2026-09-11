package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.entity.OrderJpaEntity;

import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Implements the order domain port on top of Spring Data. */
@Repository
class OrderRepositoryAdapter implements OrderRepository {

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
}
