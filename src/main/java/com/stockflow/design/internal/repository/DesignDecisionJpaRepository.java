package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.entity.DesignDecisionJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface DesignDecisionJpaRepository extends BaseJpaRepository<DesignDecisionJpaEntity> {
    Page<DesignDecisionJpaEntity> findByDraftIdOrderByCreatedAtDescIdDesc(UUID draftId, Pageable pageable);
}
