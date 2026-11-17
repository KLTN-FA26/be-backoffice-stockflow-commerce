package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.CustomizationTemplateJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomizationTemplateJpaRepository extends BaseJpaRepository<CustomizationTemplateJpaEntity> {

    List<CustomizationTemplateJpaEntity> findByVariantIdOrderByCodeAsc(UUID variantId);

    Optional<CustomizationTemplateJpaEntity> findByVariantIdAndCode(UUID variantId, String code);

    List<CustomizationTemplateJpaEntity> findByVariantIdInAndActiveTrue(Collection<UUID> variantIds);

    boolean existsByVariantIdAndCode(UUID variantId, String code);

    /**
     * The template, locked for a change. Called first in its transaction, before anything else loads the row: a copy
     * already in the persistence context would be returned as it was read, and the lock would protect nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CustomizationTemplateJpaEntity t where t.id = :id")
    Optional<CustomizationTemplateJpaEntity> lockById(@Param("id") UUID id);
}
