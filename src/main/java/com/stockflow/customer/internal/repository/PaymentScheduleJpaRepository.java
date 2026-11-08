package com.stockflow.customer.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.customer.internal.entity.PaymentScheduleJpaEntity;

import java.util.Optional;
import java.util.UUID;

interface PaymentScheduleJpaRepository extends BaseJpaRepository<PaymentScheduleJpaEntity> {

    Optional<PaymentScheduleJpaEntity> findByCustomerId(UUID customerId);
}
