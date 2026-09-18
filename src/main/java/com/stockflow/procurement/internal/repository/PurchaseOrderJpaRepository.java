package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.entity.PurchaseOrderJpaEntity;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link PurchaseOrderJpaEntity}. */
interface PurchaseOrderJpaRepository extends BaseJpaRepository<PurchaseOrderJpaEntity> {

    boolean existsBySupplierIdAndStatusIn(UUID supplierId, List<PurchaseOrderStatus> statuses);

    /** For a single-PO read: the lazy {@code lines} collection is fetch-joined here only — see
     *  {@code ProductJpaRepository.findByIdWithImages} for why never on the paginated list query. */
    @EntityGraph(attributePaths = "lines")
    Optional<PurchaseOrderJpaEntity> findWithLinesById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "lines")
    @Query("select po from PurchaseOrderJpaEntity po where po.id = :id")
    Optional<PurchaseOrderJpaEntity> findWithLinesByIdForUpdate(UUID id);

    List<PurchaseOrderJpaEntity> findBySupplierIdAndExpectedAtAndStatusNotIn(
            UUID supplierId, LocalDate expectedAt, List<PurchaseOrderStatus> excludedStatuses);

    /** SCRUM-119/WBS 3.2.7 status dashboard. One row per status that has at least one purchase
     *  order — the adapter fills in the missing (zero-count) statuses itself. */
    @Query("select po.status, count(po) from PurchaseOrderJpaEntity po group by po.status")
    List<Object[]> countByStatus();

    // Supplier spend (SCRUM-119) is NOT a query method here — see PurchaseOrderRepositoryAdapter's
    // own javadoc on why it is built with the Criteria API instead of a static @Query string.
}
