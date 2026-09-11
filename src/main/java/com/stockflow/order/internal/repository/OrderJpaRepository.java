package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.entity.OrderJpaEntity;

import com.stockflow.common.security.ScopedJpaRepository;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for orders. Package-private; the module uses the domain port.
 *
 * <p>It extends {@link ScopedJpaRepository} because {@code OrderJpaEntity} is a
 * {@link com.stockflow.common.security.ScopedEntity}: an order belongs to a customer, and a
 * customer must never see another customer's orders through an endpoint they are otherwise
 * entitled to call.</p>
 *
 * <p>The queries fetch-join {@code lines} but not {@code lines.reservationIds}: Hibernate cannot
 * fetch two collection levels in one query without a cartesian product, and the element collection
 * is EAGER, so it is loaded per line by id — a handful of primary-key lookups, batched.</p>
 */
interface OrderJpaRepository extends ScopedJpaRepository<OrderJpaEntity> {

    /**
     * Supplies the attribute names the data-scope filter needs. See {@link ScopedJpaRepository}.
     *
     * <p>Everything reached through {@code findAllInScope} / {@code findByIdInScope} is filtered to
     * the rows the caller may see. The hand-written queries below are deliberately <b>not</b>: they
     * are id lookups used by the write path, where the caller already holds the id it was given, and
     * by listeners running outside any request. A list endpoint must use the scoped methods -
     * {@code ArchitectureTest} enforces that.</p>
     */
    @Override
    default OrderJpaEntity scopePrototype() {
        return OrderJpaEntity.SCOPE_PROTOTYPE;
    }

    @Query("""
            select distinct o from OrderJpaEntity o
            left join fetch o.lines
            where o.id = :id
            """)
    Optional<OrderJpaEntity> findByIdWithLines(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select o from OrderJpaEntity o where o.id = :id")
    Optional<OrderJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select distinct o from OrderJpaEntity o
            left join fetch o.lines
            where o.requestId = :requestId
            """)
    Optional<OrderJpaEntity> findByRequestIdWithLines(@Param("requestId") UUID requestId);

    @Query("""
            select distinct o from OrderJpaEntity o
            left join fetch o.lines
            where o.customerId = :customerId
            order by o.placedAt desc
            """)
    List<OrderJpaEntity> findByCustomerIdWithLines(@Param("customerId") UUID customerId);
}
