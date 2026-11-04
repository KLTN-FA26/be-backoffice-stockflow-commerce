package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.ProductAttributeJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Spring Data repository for {@link ProductAttributeJpaEntity}. */
public interface ProductAttributeJpaRepository extends BaseJpaRepository<ProductAttributeJpaEntity> {

    /** Two queries rather than one: fetching two bags at once is a MultipleBagFetchException. */
    @Query("select distinct pa from ProductAttributeJpaEntity pa left join fetch pa.options where pa.productId = :productId")
    List<ProductAttributeJpaEntity> findWithOptionsByProductId(@Param("productId") UUID productId);

    @Query("select distinct pa from ProductAttributeJpaEntity pa left join fetch pa.descriptiveValues where pa.productId = :productId")
    List<ProductAttributeJpaEntity> findWithDescriptiveValuesByProductId(@Param("productId") UUID productId);

    @Query(value = """
            select o.attribute_value_id from product.product_attribute_options o
            join product.product_attributes pa on pa.id = o.product_attribute_id
            where pa.product_id = :productId
              and exists (select 1 from product.variant_attribute_values v where v.product_attribute_option_id = o.id)
            """, nativeQuery = true)
    Set<UUID> valueIdsUsedByVariants(@Param("productId") UUID productId);
}
