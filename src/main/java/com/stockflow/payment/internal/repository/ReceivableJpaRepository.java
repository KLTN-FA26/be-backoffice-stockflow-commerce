package com.stockflow.payment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.payment.internal.entity.ReceivableJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ReceivableJpaRepository extends BaseJpaRepository<ReceivableJpaEntity>,
        JpaSpecificationExecutor<ReceivableJpaEntity> {

    /** The order's receivable not tied to a shipment: a whole-order delivery's, or a cancellation fee's. */
    Optional<ReceivableJpaEntity> findByOrderIdAndShipmentIdIsNull(UUID orderId);

    /** G3-2: one receivable per delivered shipment of a split order. */
    Optional<ReceivableJpaEntity> findByOrderIdAndShipmentId(UUID orderId, UUID shipmentId);

    List<ReceivableJpaEntity> findByOrderIdOrderByDueDateAscIssuedAtAsc(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReceivableJpaEntity r WHERE r.id = :id")
    Optional<ReceivableJpaEntity> lockById(@Param("id") UUID id);

    /** G2-3: the customer's own receivables only; a carrier-collected balance is the carrier's to pay. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM ReceivableJpaEntity r
             WHERE r.customerId = :customer
               AND r.collector = com.stockflow.payment.internal.domain.Collector.CUSTOMER
               AND r.status <> com.stockflow.payment.internal.domain.ReceivableStatus.PAID
             ORDER BY r.dueDate, r.issuedAt, r.id""")
    List<ReceivableJpaEntity> lockUnpaid(@Param("customer") UUID customerId);
}
