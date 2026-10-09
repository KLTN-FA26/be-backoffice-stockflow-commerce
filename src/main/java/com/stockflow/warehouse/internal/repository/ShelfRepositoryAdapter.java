package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.internal.domain.Shelf;
import com.stockflow.warehouse.internal.domain.ShelfRepository;
import com.stockflow.warehouse.internal.entity.ShelfJpaEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the {@link ShelfRepository} port. Loading a shelf costs <b>two queries whatever its
 * size</b>: the shelf row, then every level with its bins and their locations in one fetch join.
 */
@Repository
class ShelfRepositoryAdapter implements ShelfRepository {

    /**
     * The unique constraints a shelf write can trip, and what each means to the user. The services
     * check all of them first (the aggregate knows its own codes, the warehouse lock serialises
     * writers); these only translate a violation that slips past.
     */
    private static final Map<String, ErrorCode> UNIQUE_CONSTRAINTS = Map.of(
            "uk_shelf_warehouse_code", ErrorCode.SHELF_CODE_ALREADY_EXISTS,
            "uk_shelf_level_shelf_index", ErrorCode.SHELF_LEVEL_ALREADY_EXISTS,
            "uk_bin_level_code", ErrorCode.BIN_CODE_ALREADY_EXISTS,
            "uk_storage_location_code", ErrorCode.BIN_CODE_ALREADY_EXISTS);

    private final ShelfJpaRepository jpa;
    private final ShelfLevelJpaRepository levels;

    ShelfRepositoryAdapter(ShelfJpaRepository jpa, ShelfLevelJpaRepository levels) {
        this.jpa = jpa;
        this.levels = levels;
    }

    @Override
    public Optional<Shelf> findById(UUID id) {
        return jpa.findById(id).map(shelf -> ShelfPersistenceMapper.toDomain(shelf, levels.findWithBinsByShelfId(id)));
    }

    @Override
    public boolean existsById(UUID id) {
        return jpa.existsById(id);
    }

    @Override
    public Optional<UUID> findWarehouseIdOf(UUID shelfId) {
        return jpa.findWarehouseIdById(shelfId);
    }

    @Override
    public boolean existsByWarehouseIdAndCode(UUID warehouseId, String code) {
        return jpa.existsByWarehouseIdAndCode(warehouseId, code);
    }

    /**
     * A new shelf is persisted with its whole tree; an existing one is merged child by child
     * ({@link ShelfPersistenceMapper#merge}) onto its managed rows, and the cascade inserts what is
     * new at the flush. Flushed here so a unique violation surfaces where it can be named.
     */
    @Override
    public Shelf save(Shelf shelf) {
        Optional<ShelfJpaEntity> existing = jpa.findById(shelf.id());
        if (existing.isPresent()) {
            ShelfJpaEntity row = existing.get();
            // Initialised so new levels are appended to a loaded list, not queued on a lazy one.
            row.getLevels().size();
            ShelfPersistenceMapper.merge(shelf, row, levels.findWithBinsByShelfId(shelf.id()));
        } else {
            jpa.save(ShelfPersistenceMapper.toNewEntity(shelf));
        }
        try {
            jpa.flush();
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex, shelf);
        }
        return findById(shelf.id()).orElseThrow();
    }

    private static RuntimeException translate(DataIntegrityViolationException ex, Shelf shelf) {
        return UNIQUE_CONSTRAINTS.entrySet().stream()
                .filter(entry -> ConstraintViolations.isViolationOf(ex, entry.getKey()))
                .findFirst()
                .<RuntimeException>map(entry -> new ConflictException(entry.getValue(),
                        "Shelf %s: %s".formatted(shelf.code(), entry.getKey())))
                .orElse(ex);
    }
}
