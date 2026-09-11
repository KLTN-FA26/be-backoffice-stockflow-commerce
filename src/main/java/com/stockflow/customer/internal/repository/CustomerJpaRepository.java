package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.entity.CustomerJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/**
 * Spring Data repository for {@link CustomerJpaEntity}.
 *
 * <p>STARTER STUB. CRUD and {@code Specification} queries come from {@link BaseJpaRepository}.
 * Add derived finders (e.g. {@code Optional<CustomerJpaEntity> findByEmail(String)}) as the
 * service needs them. Package-private on purpose: other modules reach customers through
 * {@code CustomerService}, never through this repository.</p>
 */
interface CustomerJpaRepository extends BaseJpaRepository<CustomerJpaEntity> {

    // TODO: add finders the service needs, e.g. findByEmail, findBySegmentId.
}
