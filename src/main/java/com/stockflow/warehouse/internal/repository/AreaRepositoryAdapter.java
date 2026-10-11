package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.internal.domain.Area;
import com.stockflow.warehouse.internal.domain.AreaRepository;
import com.stockflow.warehouse.internal.entity.AreaJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Implements the {@link AreaRepository} port. An area loads with its location in one query. */
@Repository
class AreaRepositoryAdapter implements AreaRepository {

    private final AreaJpaRepository jpa;
    private final EntityManager entityManager;

    AreaRepositoryAdapter(AreaJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Area> findById(UUID id) {
        return jpa.findWithLocationById(id).map(AreaPersistenceMapper::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return jpa.existsById(id);
    }

    @Override
    public Optional<UUID> findWarehouseIdOf(UUID areaId) {
        return jpa.findWarehouseIdById(areaId);
    }

    @Override
    public boolean existsByWarehouseIdAndCode(UUID warehouseId, String code) {
        return jpa.existsByWarehouseIdAndCode(warehouseId, code);
    }

    /**
     * Load-and-copy onto the managed row, or persist a new one - never {@code save(toNewEntity(...))}
     * for an existing area (CLAUDE.md §5). The location goes in by cascade ahead of the area
     * ({@code fk_area_location} is not deferrable). Flushed here so a duplicate code surfaces where
     * it can be named.
     *
     * <p>An edit that touches only the location still moves the area's version
     * ({@link AreaPersistenceMapper#changesOnlyTheLocation}): the version is what the next edit is
     * checked against. {@code PESSIMISTIC_FORCE_INCREMENT}, not {@code OPTIMISTIC_}: the optimistic
     * one increments at commit, after the version has been read into the response.</p>
     *
     * <p>{@code uk_storage_location_code} can only trip in a race: the area code is unique in its
     * warehouse, the prefix across warehouses, and a bin code has four parts where an area code has
     * two.</p>
     */
    @Override
    public Area save(Area area) {
        // findById: the service has just loaded the area, so this comes from the persistence context.
        Optional<AreaJpaEntity> existing = jpa.findById(area.id());
        AreaJpaEntity row;
        if (existing.isPresent()) {
            row = existing.get();
            boolean onlyTheLocation = AreaPersistenceMapper.changesOnlyTheLocation(
                    AreaPersistenceMapper.toDomain(row), area);
            AreaPersistenceMapper.apply(area, row);
            if (onlyTheLocation) {
                entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            }
        } else {
            row = jpa.save(AreaPersistenceMapper.toNewEntity(area));
        }
        try {
            jpa.flush();
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.isViolationOf(ex, "uk_area_warehouse_code")
                    || ConstraintViolations.isViolationOf(ex, "uk_storage_location_code")) {
                throw new ConflictException(ErrorCode.AREA_CODE_ALREADY_EXISTS,
                        "The warehouse already has an area " + area.code());
            }
            throw ex;
        }
        return AreaPersistenceMapper.toDomain(row);
    }
}
