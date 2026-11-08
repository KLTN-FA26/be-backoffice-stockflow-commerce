package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.entity.DesignProjectEventJpaEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Spring Data repository for {@link DesignProjectEventJpaEntity}: a design project's history. */
public interface DesignProjectEventJpaRepository extends BaseJpaRepository<DesignProjectEventJpaEntity> {

    Page<DesignProjectEventJpaEntity> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId, Pageable page);
}
