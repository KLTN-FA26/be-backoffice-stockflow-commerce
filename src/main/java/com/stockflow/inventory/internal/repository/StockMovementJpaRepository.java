package com.stockflow.inventory.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.entity.StockMovementJpaEntity;

import java.util.Optional;
import java.util.UUID;

interface StockMovementJpaRepository extends BaseJpaRepository<StockMovementJpaEntity> {

    Optional<StockMovementJpaEntity> findFirstByMovementTypeAndReferenceTypeAndReferenceIdOrderByIdAsc(
            StockMovement.MovementType movementType, StockMovement.ReferenceType referenceType, UUID referenceId);

    java.util.List<StockMovementJpaEntity> findByMovementTypeAndReferenceTypeAndReferenceIdOrderByIdAsc(
            StockMovement.MovementType movementType, StockMovement.ReferenceType referenceType, UUID referenceId);

    @org.springframework.data.jpa.repository.Query("""
            select distinct m.stockPackageId, m.toLocationCode from StockMovementJpaEntity m
            where m.movementType = com.stockflow.inventory.internal.domain.StockMovement.MovementType.MOVE
              and m.referenceType = :referenceType and m.referenceId = :referenceId
            """)
    java.util.List<Object[]> cartonsMovedBy(
            @org.springframework.data.repository.query.Param("referenceType") StockMovement.ReferenceType referenceType,
            @org.springframework.data.repository.query.Param("referenceId") UUID referenceId);

    long countByMovementTypeInAndReferenceTypeAndReferenceId(
            java.util.Collection<StockMovement.MovementType> movementTypes,
            StockMovement.ReferenceType referenceType, UUID referenceId);
}
