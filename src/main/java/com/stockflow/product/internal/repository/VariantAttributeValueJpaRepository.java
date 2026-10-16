package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.VariantAttributeValueJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link VariantAttributeValueJpaEntity}. */
public interface VariantAttributeValueJpaRepository extends BaseJpaRepository<VariantAttributeValueJpaEntity> {

    /** The options of a whole page of variants in one query, so a listing does not cost a query per row. */
    List<VariantAttributeValueJpaEntity> findByVariantIdIn(Collection<UUID> variantIds);

    /** The options of every variant of a product, for comparing combinations by what they are. */
    @Query("select o from VariantAttributeValueJpaEntity o where o.variantId in "
            + "(select v.id from VariantJpaEntity v where v.productId = :productId)")
    List<VariantAttributeValueJpaEntity> findByProductId(@Param("productId") UUID productId);
}
