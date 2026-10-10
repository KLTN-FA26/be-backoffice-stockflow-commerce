package com.stockflow.customer.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.customer.internal.entity.CreditProfileJpaEntity;

import java.util.Optional;
import java.util.UUID;

interface CreditProfileJpaRepository extends BaseJpaRepository<CreditProfileJpaEntity> {

    Optional<CreditProfileJpaEntity> findByCustomerId(UUID customerId);
}
