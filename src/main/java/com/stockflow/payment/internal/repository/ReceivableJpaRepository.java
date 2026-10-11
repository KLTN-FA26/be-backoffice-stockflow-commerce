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

    Optional<ReceivableJpaEntity> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM ReceivableJpaEntity r
             WHERE r.customerId = :customer
               AND r.status <> com.stockflow.payment.internal.domain.ReceivableStatus.PAID
             ORDER BY r.dueDate, r.issuedAt, r.id""")
    List<ReceivableJpaEntity> lockUnpaid(@Param("customer") UUID customerId);
}
