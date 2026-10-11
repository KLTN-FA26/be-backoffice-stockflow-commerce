package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.MediaJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaJpaRepository extends BaseJpaRepository<MediaJpaEntity> {

    List<MediaJpaEntity> findByVariantIdOrderBySortOrderAscIdAsc(UUID variantId);

    Optional<MediaJpaEntity> findByIdAndVariantId(UUID id, UUID variantId);

    Optional<MediaJpaEntity> findByUploadKey(String uploadKey);

    long countByVariantId(UUID variantId);

    @Query("select coalesce(max(m.sortOrder), -1) + 1 from MediaJpaEntity m where m.variantId = :variantId")
    int nextSortOrder(@Param("variantId") UUID variantId);
}
