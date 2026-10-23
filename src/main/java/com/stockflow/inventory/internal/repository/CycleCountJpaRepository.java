package com.stockflow.inventory.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.inventory.internal.entity.CycleCountJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CycleCountJpaRepository extends BaseJpaRepository<CycleCountJpaEntity> {

    @Query("select distinct c from CycleCountJpaEntity c left join fetch c.lines where c.id = :id")
    Optional<CycleCountJpaEntity> findByIdWithLines(@Param("id") UUID id);

    /** A page of counts with their lines in one query; the ids bound the page, so nothing is paged in memory. */
    @Query("select distinct c from CycleCountJpaEntity c left join fetch c.lines where c.id in :ids")
    List<CycleCountJpaEntity> findAllWithLines(@Param("ids") Collection<UUID> ids);
}
