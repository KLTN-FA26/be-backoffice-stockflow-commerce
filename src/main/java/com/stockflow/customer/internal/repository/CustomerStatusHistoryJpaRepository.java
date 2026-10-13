package com.stockflow.customer.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.customer.internal.entity.CustomerStatusHistoryJpaEntity;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link CustomerStatusHistoryJpaEntity}. */
interface CustomerStatusHistoryJpaRepository extends BaseJpaRepository<CustomerStatusHistoryJpaEntity> {

    List<CustomerStatusHistoryJpaEntity> findByCustomerIdOrderByChangedAtAscCreatedAtAsc(UUID customerId);
}
