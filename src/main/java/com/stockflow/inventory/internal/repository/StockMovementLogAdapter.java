package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.persistence.Specs;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.entity.StockMovementJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class StockMovementLogAdapter implements StockMovementLog, StockLedgerSearch {

    private final StockMovementJpaRepository jpa;

    StockMovementLogAdapter(StockMovementJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void append(StockMovement m) {
        jpa.save(new StockMovementJpaEntity(m.id(), m.type(), m.sku().code(), m.lotNumber(),
                m.from() == null ? null : m.from().code(), m.to() == null ? null : m.to().code(),
                m.quantity(),
                m.from() == null ? null : m.status(), m.to() == null ? null : m.status(),
                m.referenceType(), m.referenceId(), m.reason(), m.actorId(), m.occurredAt()));
    }

    @Override
    public Optional<StockMovement> findByReference(StockMovement.MovementType type,
                                                   StockMovement.ReferenceType referenceType, UUID referenceId) {
        return jpa.findFirstByMovementTypeAndReferenceTypeAndReferenceId(type, referenceType, referenceId)
                .map(StockMovementLogAdapter::toDomain);
    }

    @Override
    public Page<StockMovement> search(Criteria criteria, Pageable pageable) {
        Specification<StockMovementJpaEntity> spec = Specification
                .<StockMovementJpaEntity>where(Specs.eq("sku", upper(criteria.sku())))
                .and(Specs.eq("movementType", criteria.type()))
                .and(location(upper(criteria.location())));
        return jpa.findAll(spec, pageable).map(StockMovementLogAdapter::toDomain);
    }

    private static Specification<StockMovementJpaEntity> location(String code) {
        if (code == null) {
            return null;
        }
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("fromLocationCode"), code), cb.equal(root.get("toLocationCode"), code));
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    static StockMovement toDomain(StockMovementJpaEntity e) {
        return new StockMovement(e.getId(), e.getMovementType(), new Sku(e.getSku()), e.getLotNumber(),
                e.getFromLocationCode() == null ? null : new LocationId(e.getFromLocationCode()),
                e.getToLocationCode() == null ? null : new LocationId(e.getToLocationCode()),
                e.getQuantity(),
                e.getFromStatus() != null ? e.getFromStatus() : e.getToStatus(),
                e.getReferenceType(), e.getReferenceId(), e.getReason(), e.getActorId(), e.getOccurredAt());
    }
}
