package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.entity.PurchaseOrderJpaEntity;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link PurchaseOrderJpaEntity}. */
interface PurchaseOrderJpaRepository extends BaseJpaRepository<PurchaseOrderJpaEntity> {

    /** For a single-PO read: the lazy {@code lines} collection is fetch-joined here only — see
     *  {@code ProductJpaRepository.findByIdWithImages} for why never on the paginated list query. */
    @EntityGraph(attributePaths = "lines")
    Optional<PurchaseOrderJpaEntity> findWithLinesById(UUID id);

    List<PurchaseOrderJpaEntity> findBySupplierIdAndExpectedAtAndStatusNotIn(
            UUID supplierId, LocalDate expectedAt, List<PurchaseOrderStatus> excludedStatuses);
}
