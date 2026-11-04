package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.internal.domain.Zone;
import com.stockflow.warehouse.internal.domain.ZoneRepository;
import com.stockflow.warehouse.internal.entity.ZoneJpaEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Implements the {@link ZoneRepository} port on Spring Data. */
@Repository
class ZoneRepositoryAdapter implements ZoneRepository {

    private final ZoneJpaRepository jpa;

    ZoneRepositoryAdapter(ZoneJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Zone> findById(UUID id) {
        return jpa.findById(id).map(WarehousePersistenceMapper::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return jpa.existsById(id);
    }

    @Override
    public List<Zone> findByWarehouseId(UUID warehouseId) {
        return jpa.findByWarehouseIdOrderByNameAsc(warehouseId).stream()
                .map(WarehousePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<UUID> findWarehouseIdOf(UUID zoneId) {
        return jpa.findWarehouseIdById(zoneId);
    }

    /** Load-and-copy, flushed so a duplicate name is named here; see {@code WarehouseRepositoryAdapter.save}. */
    @Override
    public Zone save(Zone zone) {
        ZoneJpaEntity row = jpa.findById(zone.id())
                .map(existing -> {
                    WarehousePersistenceMapper.apply(zone, existing);
                    return existing;
                })
                .orElseGet(() -> WarehousePersistenceMapper.toNewEntity(zone));
        try {
            return WarehousePersistenceMapper.toDomain(jpa.saveAndFlush(row));
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.isViolationOf(ex, "uk_zone_warehouse_name")) {
                throw new ConflictException(ErrorCode.ZONE_NAME_ALREADY_EXISTS,
                        "The warehouse already has a zone named " + zone.name());
            }
            throw ex;
        }
    }
}
