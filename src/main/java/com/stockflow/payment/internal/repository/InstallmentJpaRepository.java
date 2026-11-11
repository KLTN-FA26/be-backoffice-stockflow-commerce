package com.stockflow.payment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.payment.internal.entity.InstallmentJpaEntity;

import java.util.List;
import java.util.UUID;

interface InstallmentJpaRepository extends BaseJpaRepository<InstallmentJpaEntity> {

    List<InstallmentJpaEntity> findByOrderIdOrderBySeqAsc(UUID orderId);

    boolean existsByContractId(UUID contractId);
}
