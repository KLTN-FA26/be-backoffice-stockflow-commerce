package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.AttributeJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link AttributeJpaEntity}. */
public interface AttributeJpaRepository extends BaseJpaRepository<AttributeJpaEntity> {

    /** The attribute and all its values in one statement. */
    @Query("select distinct a from AttributeJpaEntity a left join fetch a.values where a.id = :id")
    Optional<AttributeJpaEntity> findWithValuesById(@Param("id") UUID id);

    @Query("select distinct a from AttributeJpaEntity a left join fetch a.values where a.id in :ids")
    List<AttributeJpaEntity> findWithValuesByIdIn(@Param("ids") Collection<UUID> ids);

    boolean existsByCode(String code);

    @Query(value = """
            select exists (select 1 from product.product_attributes where attribute_id = :id)
                or exists (select 1 from product.category_attributes where attribute_id = :id)
            """, nativeQuery = true)
    boolean isReferenced(@Param("id") UUID attributeId);

    @Query(value = """
            select exists (select 1 from product.product_attribute_options where attribute_value_id = :id)
                or exists (select 1 from product.product_descriptive_values where attribute_value_id = :id)
            """, nativeQuery = true)
    boolean isValueReferenced(@Param("id") UUID valueId);
}
