package com.stockflow.inventory.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.entity.StockMovementJpaEntity;

import java.util.Optional;
import java.util.UUID;

interface StockMovementJpaRepository extends BaseJpaRepository<StockMovementJpaEntity> {

    Optional<StockMovementJpaEntity> findFirstByMovementTypeAndReferenceTypeAndReferenceId(
            StockMovement.MovementType movementType, StockMovement.ReferenceType referenceType, UUID referenceId);

    boolean existsByMovementTypeAndReferenceTypeAndReasonAndToLocationCodeAndFromStatusAndToStatus(
            StockMovement.MovementType movementType, StockMovement.ReferenceType referenceType, String reason,
            String toLocationCode, com.stockflow.inventory.internal.domain.StockStatus fromStatus,
            com.stockflow.inventory.internal.domain.StockStatus toStatus);
}
