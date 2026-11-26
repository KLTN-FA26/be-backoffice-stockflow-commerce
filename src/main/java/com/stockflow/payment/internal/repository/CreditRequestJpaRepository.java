package com.stockflow.payment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.payment.internal.domain.CreditRequestStatus;
import com.stockflow.payment.internal.entity.CreditRequestJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

interface CreditRequestJpaRepository extends BaseJpaRepository<CreditRequestJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CreditRequestJpaEntity r where r.id = :id")
    Optional<CreditRequestJpaEntity> findForUpdate(@Param("id") UUID id);

    Optional<CreditRequestJpaEntity> findFirstByCustomerIdAndStatusIn(UUID customerId,
                                                                     Collection<CreditRequestStatus> statuses);
}
