package com.stockflow.payment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.payment.internal.entity.CustomerTransferJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface CustomerTransferJpaRepository extends BaseJpaRepository<CustomerTransferJpaEntity>,
        JpaSpecificationExecutor<CustomerTransferJpaEntity> {

    boolean existsByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM CustomerTransferJpaEntity t
             WHERE t.customerId = :customer AND t.unallocatedAmount > 0
             ORDER BY t.receivedOn, t.recordedAt, t.id""")
    List<CustomerTransferJpaEntity> lockWithCredit(@Param("customer") UUID customerId);
}
