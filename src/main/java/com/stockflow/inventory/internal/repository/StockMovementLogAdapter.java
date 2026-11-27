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

import java.util.Collection;
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
        // stock_movement.stock_package_id is NOT NULL: stock is whole cartons, so a line with no carton records nothing
        // that can happen. The record already refuses one; said here again, by name, where the row is written.
        if (m.stockPackage() == null) {
            throw new IllegalArgumentException("A ledger line names the carton it is about");
        }
        jpa.save(new StockMovementJpaEntity(m.id(), m.type(), m.sku().code(), m.lotNumber(),
                m.from() == null ? null : m.from().code(), m.to() == null ? null : m.to().code(),
                m.from() == null ? null : m.status(), m.to() == null ? null : m.toStatus(),
                m.referenceType(), m.referenceId(), m.reason(), m.actorId(), m.occurredAt(), m.lotId(), m.orderId(),
                m.stockPackage().value())
                .askedBy(m.requestedBy(), m.countedBy()));
    }

    @Override
    public java.util.List<MovedCarton> cartonsMovedBy(StockMovement.ReferenceType referenceType, UUID referenceId) {
        return jpa.cartonsMovedBy(referenceType, referenceId).stream()
                .map(row -> new MovedCarton(new com.stockflow.inventory.internal.domain.StockPackageId((UUID) row[0]),
                        new com.stockflow.inventory.internal.domain.LocationId((String) row[1])))
                .toList();
    }

    @Override
    public Optional<StockMovement> findByReference(StockMovement.MovementType type,
                                                   StockMovement.ReferenceType referenceType, UUID referenceId) {
        return jpa.findFirstByMovementTypeAndReferenceTypeAndReferenceIdOrderByIdAsc(type, referenceType, referenceId)
                .map(StockMovementLogAdapter::toDomain);
    }

    @Override
    public java.util.List<StockMovement> findAllByReference(StockMovement.MovementType type,
                                                            StockMovement.ReferenceType referenceType,
                                                            UUID referenceId) {
        return jpa.findByMovementTypeAndReferenceTypeAndReferenceIdOrderByIdAsc(type, referenceType, referenceId)
                .stream().map(StockMovementLogAdapter::toDomain).toList();
    }

    @Override
    public int countByReference(Collection<StockMovement.MovementType> types,
                                StockMovement.ReferenceType referenceType, UUID referenceId) {
        return types.isEmpty() ? 0 : Math.toIntExact(jpa.countByMovementTypeInAndReferenceTypeAndReferenceId(types,
                referenceType, referenceId));
    }

    @Override
    public Page<StockMovement> search(Criteria criteria, Pageable pageable) {
        Specification<StockMovementJpaEntity> spec = Specification
                .<StockMovementJpaEntity>where(Specs.eq("sku", upper(criteria.sku())))
                .and(Specs.eq("movementType", criteria.type()))
                .and(location(upper(criteria.location())))
                .and(criteria.occurredFrom() == null ? null : (root, query, cb) ->
                        cb.greaterThanOrEqualTo(root.get("occurredAt"), criteria.occurredFrom()))
                .and(criteria.occurredBefore() == null ? null : (root, query, cb) ->
                        cb.lessThan(root.get("occurredAt"), criteria.occurredBefore()))
                .and(inWarehouse(criteria.warehousePrefix()))
                .and(carton(criteria.stockPackageId()))
                .and(UnderCountSpecs.unless(criteria.hideCountedSlots(), UnderCountSpecs.movementsOutsideCountedSlots()))
                .and(com.stockflow.common.security.WarehouseScope.locationsIn("fromLocationCode", "toLocationCode"));
        return jpa.findAll(spec, pageable).map(StockMovementLogAdapter::toDomain);
    }

    private static Specification<StockMovementJpaEntity> location(String code) {
        if (code == null) {
            return null;
        }
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("fromLocationCode"), code), cb.equal(root.get("toLocationCode"), code));
    }

    /** The lines about the carton: its history. */
    private static Specification<StockMovementJpaEntity> carton(UUID stockPackageId) {
        if (stockPackageId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("stockPackageId"), stockPackageId);
    }

    /**
     * Lines with either side in the warehouse: its location codes start with its prefix and a dash (06 BR-13), the
     * pattern {@link com.stockflow.common.security.WarehouseScope#locationPattern} spells for every query.
     */
    private static Specification<StockMovementJpaEntity> inWarehouse(String prefix) {
        if (prefix == null) {
            return null;
        }
        String pattern = com.stockflow.common.security.WarehouseScope.locationPattern(prefix);
        return (root, query, cb) -> cb.or(cb.like(root.get("fromLocationCode"), pattern, '\\'),
                cb.like(root.get("toLocationCode"), pattern, '\\'));
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    static StockMovement toDomain(StockMovementJpaEntity e) {
        return new StockMovement(e.getId(), e.getMovementType(), new Sku(e.getSku()), e.getLotNumber(),
                e.getFromLocationCode() == null ? null : new LocationId(e.getFromLocationCode()),
                e.getToLocationCode() == null ? null : new LocationId(e.getToLocationCode()),
                e.getFromStatus() != null ? e.getFromStatus() : e.getToStatus(),
                e.getToStatus() != null ? e.getToStatus() : e.getFromStatus(),
                e.getReferenceType(), e.getReferenceId(), e.getReason(), e.getActorId(), e.getOccurredAt(),
                e.getLotId(), e.getOrderId(),
                new com.stockflow.inventory.internal.domain.StockPackageId(e.getStockPackageId()),
                e.getRequestedBy(), e.getCountedBy());
    }
}
