package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.entity.AddressJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.customer.internal.domain.AddressType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link AddressJpaEntity}. STARTER STUB — see
 * {@link CustomerJpaRepository} for the conventions.
 */
interface AddressJpaRepository extends BaseJpaRepository<AddressJpaEntity> {
    List<AddressJpaEntity> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update AddressJpaEntity a set a.defaultAddress = false "
            + "where a.customerId = :customerId and a.type = :type and a.defaultAddress = true")
    int clearDefault(@Param("customerId") UUID customerId, @Param("type") AddressType type);
}
