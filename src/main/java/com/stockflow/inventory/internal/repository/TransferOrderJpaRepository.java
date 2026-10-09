package com.stockflow.inventory.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.inventory.internal.entity.TransferOrderJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface TransferOrderJpaRepository extends BaseJpaRepository<TransferOrderJpaEntity> {

    @Query("select distinct t from TransferOrderJpaEntity t left join fetch t.lines where t.id = :id")
    Optional<TransferOrderJpaEntity> findByIdWithLines(@Param("id") UUID id);
}
