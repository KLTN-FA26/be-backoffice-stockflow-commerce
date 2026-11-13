package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.entity.PrintAreaJpaEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PrintAreaJpaRepository extends BaseJpaRepository<PrintAreaJpaEntity> {

    List<PrintAreaJpaEntity> findByTemplateIdInOrderByTemplateIdAscSortOrderAsc(Collection<UUID> templateIds);

    /** A template's areas go whole when it is saved: they are computed, never edited one by one. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from PrintAreaJpaEntity a where a.templateId = :templateId")
    int deleteByTemplateId(@Param("templateId") UUID templateId);
}
