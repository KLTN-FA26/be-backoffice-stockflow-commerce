package com.stockflow.customer.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.customer.internal.entity.CustomerSalesOwnerHistoryJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** Spring Data repository for {@link CustomerSalesOwnerHistoryJpaEntity}; reached through {@link SalesOwnerHistoryAdapter}. */
interface SalesOwnerHistoryJpaRepository extends BaseJpaRepository<CustomerSalesOwnerHistoryJpaEntity> {

    /** Newest first; {@code ix_customer_sales_owner_history_customer} serves it. */
    Page<CustomerSalesOwnerHistoryJpaEntity> findByCustomerIdOrderByChangedAtDescIdDesc(UUID customerId,
                                                                                        Pageable pageable);

    long countByCustomerId(UUID customerId);
}
