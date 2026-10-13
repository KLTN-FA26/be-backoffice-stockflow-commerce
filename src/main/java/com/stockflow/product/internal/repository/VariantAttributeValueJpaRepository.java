package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.VariantAttributeValueJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link VariantAttributeValueJpaEntity}. */
public interface VariantAttributeValueJpaRepository extends BaseJpaRepository<VariantAttributeValueJpaEntity> {

    /** The options of a whole page of variants in one query, so a listing does not cost a query per row. */
    List<VariantAttributeValueJpaEntity> findByVariantIdIn(Collection<UUID> variantIds);
}
