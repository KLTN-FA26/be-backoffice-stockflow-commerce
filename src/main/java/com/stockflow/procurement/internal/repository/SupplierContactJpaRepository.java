package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.entity.SupplierContactJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SupplierContactJpaRepository extends BaseJpaRepository<SupplierContactJpaEntity> {

    Optional<SupplierContactJpaEntity> findBySupplierIdAndPrimaryTrue(UUID supplierId);

    List<SupplierContactJpaEntity> findBySupplierIdInAndPrimaryTrue(Collection<UUID> supplierIds);
}
