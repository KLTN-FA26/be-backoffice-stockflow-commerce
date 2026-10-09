package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Specs;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.MapExtent;
import com.stockflow.warehouse.internal.domain.Placement;
import com.stockflow.warehouse.internal.domain.Warehouse;
import com.stockflow.warehouse.internal.domain.WarehouseRepository;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.entity.WarehouseJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Implements the {@link WarehouseRepository} port and the warehouse list on Spring Data. */
@Repository
class WarehouseRepositoryAdapter implements WarehouseRepository, WarehouseSearchRepository {

    /** See {@code StockItemRepositoryAdapter.LOCK_TIMEOUT} for what this does and does not bound. */
    private static final Map<String, Object> LOCK_TIMEOUT = Map.of("jakarta.persistence.lock.timeout", 3000);

    private final WarehouseJpaRepository jpa;
    private final EntityManager entityManager;

    WarehouseRepositoryAdapter(WarehouseJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Warehouse> findById(UUID id) {
        return jpa.findById(id).map(WarehousePersistenceMapper::toDomain);
    }

    /**
     * Load, then {@code refresh} under {@code PESSIMISTIC_WRITE}: a locking query would return the
     * instance already in the persistence context unchanged, and the lock would guard stale state
     * (the reasoning is spelled out on {@code StockItemRepositoryAdapter.findByIdForUpdate}).
     */
    @Override
    public Optional<Warehouse> findByIdForUpdate(UUID id) {
        return jpa.findById(id).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE, LOCK_TIMEOUT);
            return WarehousePersistenceMapper.toDomain(entity);
        });
    }

    @Override
    public boolean existsById(UUID id) {
        return jpa.existsById(id);
    }

    @Override
    public boolean existsByPrefix(String prefix) {
        return jpa.existsByPrefix(prefix);
    }

    @Override
    public MapExtent findOccupiedExtent(UUID warehouseId) {
        WarehouseJpaRepository.ExtentRow row = jpa.findOccupiedExtent(warehouseId);
        return row == null ? MapExtent.EMPTY : new MapExtent(row.getRightEdge(), row.getBottomEdge());
    }

    @Override
    public List<Placement> findPlacements(UUID warehouseId) {
        return jpa.findPlacements(warehouseId).stream()
                .map(row -> new Placement(row.getId(), Placement.Kind.valueOf(row.getKind()), row.getCode(),
                        new Footprint(row.getX(), row.getY(), row.getWidth(), row.getLength(), row.getRotation())))
                .toList();
    }

    /**
     * Load-and-copy rather than {@code save(toNewEntity(...))}: the entity's application-assigned
     * id would make Spring Data merge, with a SELECT before every INSERT (CLAUDE.md §5).
     * Flushed here so a duplicate prefix surfaces inside this method, where it can be named.
     */
    @Override
    public Warehouse save(Warehouse warehouse) {
        WarehouseJpaEntity row = jpa.findById(warehouse.id())
                .map(existing -> {
                    WarehousePersistenceMapper.apply(warehouse, existing);
                    return existing;
                })
                .orElseGet(() -> WarehousePersistenceMapper.toNewEntity(warehouse));
        try {
            return WarehousePersistenceMapper.toDomain(jpa.saveAndFlush(row));
        } catch (DataIntegrityViolationException ex) {
            // Until contract C2 the legacy code column holds the prefix too and is unique as well, and
            // Postgres reports whichever index it checks first. TODO(C2): uk_warehouse_prefix only.
            if (ConstraintViolations.isViolationOf(ex, "uk_warehouse_prefix")
                    || ConstraintViolations.isViolationOf(ex, "uk_warehouse_code")) {
                throw new ConflictException(ErrorCode.WAREHOUSE_PREFIX_ALREADY_EXISTS,
                        "Prefix " + warehouse.prefix() + " is already used by another warehouse");
            }
            throw ex;
        }
    }

    @Override
    public Page<Warehouse> search(String term, WarehouseStatus status, Pageable pageable) {
        Specification<WarehouseJpaEntity> matchesTerm = Specification
                .<WarehouseJpaEntity>where(Specs.contains("prefix", term))
                .or(Specs.contains("name", term));
        Specification<WarehouseJpaEntity> specification = matchesTerm.and(Specs.eq("status", status));
        return jpa.findAll(specification, pageable).map(WarehousePersistenceMapper::toDomain);
    }
}
