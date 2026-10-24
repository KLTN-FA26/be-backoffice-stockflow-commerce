package com.stockflow.inventory.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.entity.StockMovementJpaEntity;

import java.util.Optional;
import java.util.UUID;

interface StockMovementJpaRepository extends BaseJpaRepository<StockMovementJpaEntity> {

    Optional<StockMovementJpaEntity> findFirstByMovementTypeAndReferenceTypeAndReferenceId(
            StockMovement.MovementType movementType, StockMovement.ReferenceType referenceType, UUID referenceId);

    @org.springframework.data.jpa.repository.Query("select coalesce(sum(m.quantity), 0) from StockMovementJpaEntity m"
            + " where m.movementType = :movementType and m.referenceType = :referenceType"
            + " and m.referenceId = :referenceId")
    long sumQuantityByReference(
            @org.springframework.data.repository.query.Param("movementType") StockMovement.MovementType movementType,
            @org.springframework.data.repository.query.Param("referenceType")
            StockMovement.ReferenceType referenceType,
            @org.springframework.data.repository.query.Param("referenceId") UUID referenceId);

    boolean existsByMovementTypeAndReferenceTypeAndReasonAndToLocationCodeAndFromStatusAndToStatus(
            StockMovement.MovementType movementType, StockMovement.ReferenceType referenceType, String reason,
            String toLocationCode, com.stockflow.inventory.internal.domain.StockStatus fromStatus,
            com.stockflow.inventory.internal.domain.StockStatus toStatus);

    boolean existsByMovementTypeAndReferenceTypeAndReasonAndFromLocationCode(StockMovement.MovementType movementType,
            StockMovement.ReferenceType referenceType, String reason, String fromLocationCode);

    boolean existsByMovementTypeAndReferenceTypeAndReasonAndToLocationCode(StockMovement.MovementType movementType,
            StockMovement.ReferenceType referenceType, String reason, String toLocationCode);
}
