package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.Boundary;
import com.stockflow.warehouse.internal.domain.BoundaryRepository;
import com.stockflow.warehouse.internal.entity.BoundaryJpaEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Implements the {@link BoundaryRepository} port on Spring Data. */
@Repository
class BoundaryRepositoryAdapter implements BoundaryRepository {

    private final BoundaryJpaRepository jpa;

    BoundaryRepositoryAdapter(BoundaryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Boundary> findById(UUID id) {
        return jpa.findById(id).map(WarehousePersistenceMapper::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return jpa.existsById(id);
    }

    @Override
    public Optional<UUID> findWarehouseIdOf(UUID boundaryId) {
        return jpa.findWarehouseIdById(boundaryId);
    }

    /** Load-and-copy; see {@code WarehouseRepositoryAdapter.save}. No unique key to translate. */
    @Override
    public Boundary save(Boundary boundary) {
        BoundaryJpaEntity row = jpa.findById(boundary.id())
                .map(existing -> {
                    WarehousePersistenceMapper.apply(boundary, existing);
                    return existing;
                })
                .orElseGet(() -> WarehousePersistenceMapper.toNewEntity(boundary));
        return WarehousePersistenceMapper.toDomain(jpa.saveAndFlush(row));
    }

    @Override
    public void deleteById(UUID boundaryId) {
        jpa.deleteById(boundaryId);
        jpa.flush();
    }
}
