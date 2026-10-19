package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.entity.CustomerJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link CustomerJpaEntity}.
 *
 * <p>STARTER STUB. CRUD and {@code Specification} queries come from {@link BaseJpaRepository}.
 * Add derived finders (e.g. {@code Optional<CustomerJpaEntity> findByEmail(String)}) as the
 * service needs them. Package-private on purpose: other modules reach customers through
 * {@code CustomerService}, never through this repository.</p>
 */
interface CustomerJpaRepository extends BaseJpaRepository<CustomerJpaEntity> {
    Optional<CustomerJpaEntity> findByUserId(UUID userId);
    Optional<CustomerJpaEntity> findByEmailIgnoreCase(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CustomerJpaEntity c where c.id = :id")
    Optional<CustomerJpaEntity> lockById(@Param("id") UUID id);

    /** Mirrors uk_customer_tax_code (V20261017000830): the code is another customer's, whatever its status. */
    @Query("""
            select count(c) > 0 from CustomerJpaEntity c
            where c.taxCode = :taxCode
              and (:exceptId is null or c.id <> :exceptId)
            """)
    boolean existsTaxCode(@Param("taxCode") String taxCode, @Param("exceptId") UUID exceptId);

    /** {@code [segmentId, count]} for every group that holds a customer. */
    @Query("select c.segmentId, count(c) from CustomerJpaEntity c where c.segmentId is not null group by c.segmentId")
    java.util.List<Object[]> countBySegment();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CustomerJpaEntity c where c.userId = :userId")
    Optional<CustomerJpaEntity> lockByUserId(@Param("userId") UUID userId);
}
