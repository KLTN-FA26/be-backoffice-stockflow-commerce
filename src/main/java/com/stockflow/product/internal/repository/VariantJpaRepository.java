package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.entity.VariantJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VariantJpaRepository extends BaseJpaRepository<VariantJpaEntity> {

    Optional<VariantJpaEntity> findByIdAndProductId(UUID id, UUID productId);

    Optional<VariantJpaEntity> findByProductIdAndDefaultVariantTrue(UUID productId);

    Optional<VariantJpaEntity> findBySku(String sku);

    List<VariantJpaEntity> findByProductIdAndStatus(UUID productId, VariantStatus status);

    boolean existsBySku(String sku);

    boolean existsByProductIdAndAttributeSignature(UUID productId, String attributeSignature);

    @Query("select coalesce(max(v.position), -1) + 1 from VariantJpaEntity v where v.productId = :productId")
    int nextPosition(@Param("productId") UUID productId);
}
