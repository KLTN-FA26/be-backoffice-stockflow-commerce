package com.stockflow.warehouse.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the two read models on JPQL projections. Every query selects columns into a record, so
 * nothing is managed, nothing is lazily loaded later, and the persistence context stays empty.
 *
 * <p>Plain {@link EntityManager} queries rather than {@code @Query} methods: each query reads a
 * different entity, and on a Spring Data repository a method returning something other than the
 * repository's own entity is rewritten as a DTO projection - see {@code ShelfLevelJpaRepository}.
 * The record is passed as the result type and Hibernate calls its canonical constructor with the
 * selected values, in order.</p>
 */
@Repository
class LayoutReadRepositoryAdapter implements LayoutReadRepository, LocationLookupRepository {

    private final EntityManager entityManager;

    LayoutReadRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<WarehouseRow> findWarehouse(UUID warehouseId) {
        return entityManager.createQuery("""
                        select w.id, w.prefix, w.name, w.mapUnit, w.mapWidth, w.mapHeight, w.status, w.version
                        from WarehouseJpaEntity w
                        where w.id = :warehouseId
                        """, WarehouseRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList().stream().findFirst();
    }

    @Override
    public List<ZoneRow> findZones(UUID warehouseId) {
        return entityManager.createQuery("""
                        select z.id, z.name, z.color, z.version
                        from ZoneJpaEntity z
                        where z.warehouseId = :warehouseId
                        order by z.name
                        """, ZoneRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList();
    }

    @Override
    public List<ShelfRow> findShelves(UUID warehouseId) {
        return entityManager.createQuery("""
                        select s.id, s.zoneId, s.code, s.name, s.description, s.x, s.y, s.width, s.length,
                               s.rotation, s.obstacle, s.pickNorth, s.pickEast, s.pickSouth, s.pickWest,
                               s.defaultStorageClass, s.status, s.version
                        from ShelfJpaEntity s
                        where s.warehouseId = :warehouseId
                        order by s.code
                        """, ShelfRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList();
    }

    @Override
    public List<LevelRow> findLevels(UUID warehouseId) {
        return entityManager.createQuery("""
                        select l.id, s.id, l.levelIndex, l.elevation, l.usableHeight, l.maxWeight
                        from ShelfLevelJpaEntity l
                        join l.shelf s
                        where s.warehouseId = :warehouseId
                        order by l.levelIndex
                        """, LevelRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList();
    }

    /** An inner join to the location: every bin has one ({@code fk_bin_location} is not null). */
    @Override
    public List<BinRow> findBins(UUID warehouseId) {
        return entityManager.createQuery("""
                        select b.id, l.id, b.code, b.description, b.x, b.y, b.width, b.length, b.rotation,
                               b.type, b.storageClassOverride, loc.id, loc.locationCode, loc.storageClass,
                               loc.capacityUnits, loc.maxWeight, loc.pickable, loc.putawayTarget, loc.status
                        from BinJpaEntity b
                        join b.level l
                        join l.shelf s
                        join b.location loc
                        where s.warehouseId = :warehouseId
                        order by b.code
                        """, BinRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList();
    }

    /** A left join to the location: a {@code NON_STORAGE} area has none. */
    @Override
    public List<AreaRow> findAreas(UUID warehouseId) {
        return entityManager.createQuery("""
                        select a.id, a.code, a.type, a.name, a.x, a.y, a.width, a.length, a.rotation,
                               a.obstacle, a.status, a.version, loc.id, loc.locationCode, loc.storageClass,
                               loc.capacityUnits, loc.maxWeight, loc.pickable, loc.putawayTarget
                        from AreaJpaEntity a
                        left join a.location loc
                        where a.warehouseId = :warehouseId
                        order by a.code
                        """, AreaRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList();
    }

    @Override
    public List<BoundaryRow> findBoundaries(UUID warehouseId) {
        return entityManager.createQuery("""
                        select b.id, b.type, b.startX, b.startY, b.endX, b.endY, b.passable,
                               b.operationalStatus, b.version
                        from BoundaryJpaEntity b
                        where b.warehouseId = :warehouseId
                        order by b.id
                        """, BoundaryRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList();
    }

    @Override
    public Optional<LocationRow> findByLocationCode(String locationCode) {
        return findLocation("loc.locationCode = :key", locationCode);
    }

    @Override
    public Optional<LocationRow> findById(UUID locationId) {
        return findLocation("loc.id = :key", locationId);
    }

    /**
     * The location, its warehouse and - for a bin - its shelf, in one query. An area is not joined:
     * its status and its location's are always written together (issue #18 D8), so the location's
     * status already is the area's. The bin side is a left join, since an area's location has no bin.
     */
    private Optional<LocationRow> findLocation(String condition, Object key) {
        return entityManager.createQuery("""
                        select loc.id, loc.kind, loc.warehouseId, loc.locationCode, loc.storageClass,
                               loc.status, w.status, s.status
                        from StorageLocationJpaEntity loc
                        join WarehouseJpaEntity w on w.id = loc.warehouseId
                        left join BinJpaEntity b on b.location = loc
                        left join b.level l
                        left join l.shelf s
                        where\s""" + condition, LocationRow.class)
                .setParameter("key", key)
                .getResultList().stream().findFirst();
    }
}
