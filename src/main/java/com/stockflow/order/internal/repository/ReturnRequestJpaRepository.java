package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.ReturnRequestJpaEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ReturnRequestJpaRepository extends BaseJpaRepository<ReturnRequestJpaEntity> {

    /** A scalar: the return itself is not loaded into the persistence context. */
    @Query("select r.orderId from ReturnRequestJpaEntity r where r.id = :id")
    Optional<UUID> findOrderIdOf(@Param("id") UUID id);

    /** One return with its lines in one query: a command reads the whole aggregate, never line by line. */
    @Query("select distinct r from ReturnRequestJpaEntity r left join fetch r.lines where r.id = :id")
    Optional<ReturnRequestJpaEntity> findByIdWithLines(@Param("id") UUID id);

    /** The row lock only; the adapter refreshes and then reads the lines (see {@code ReturnRequestRepositoryAdapter}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select r from ReturnRequestJpaEntity r where r.id = :id")
    Optional<ReturnRequestJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("select distinct r from ReturnRequestJpaEntity r left join fetch r.lines where r.requestId = :requestId")
    Optional<ReturnRequestJpaEntity> findByRequestIdWithLines(@Param("requestId") UUID requestId);

    /** Every return of an order with its lines, oldest first: a handful per order at most. */
    @Query("""
            select distinct r from ReturnRequestJpaEntity r left join fetch r.lines
            where r.orderId = :orderId order by r.requestedAt, r.id""")
    List<ReturnRequestJpaEntity> findByOrderIdWithLines(@Param("orderId") UUID orderId);

    @Query("""
            select r.id from ReturnRequestJpaEntity r
            where r.status = com.stockflow.order.internal.domain.ReturnStatus.AWAITING_RETURN and r.returnDeadline < :now
            order by r.returnDeadline, r.id""")
    List<UUID> awaitingReturnPast(@Param("now") Instant now, Pageable page);
}
