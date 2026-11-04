package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.procurement.internal.entity.SupplierItemPriceJpaEntity;

import java.util.Optional;
import java.util.UUID;

interface SupplierItemPriceJpaRepository extends BaseJpaRepository<SupplierItemPriceJpaEntity> {

    /** The period whose price is in force now: at most one ({@code ex_supplier_item_prices_overlap}). */
    Optional<SupplierItemPriceJpaEntity> findBySupplierItemIdAndEffectiveToIsNull(UUID supplierItemId);
}
