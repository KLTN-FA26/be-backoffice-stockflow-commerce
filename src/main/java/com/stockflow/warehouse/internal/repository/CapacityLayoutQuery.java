package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The rows of a warehouse's map the capacity board is computed from (kltn-docs 06 §4.6; BE1): every bin with its size
 * and the statuses that decide whether it is usable, the shelves, zones and storage areas it is grouped by. Five
 * projections over the warehouse schema, in the style of {@link LayoutReadRepositoryAdapter}: nothing is managed and
 * nothing loads lazily afterwards.
 *
 * <p>Every bin of the warehouse, not only the putaway targets: a bin kept out of putaway still holds cartons and its
 * volume is still the warehouse's (06 §4.6 step 3, "bin đang dùng được").</p>
 */
@Repository
public class CapacityLayoutQuery {

    /** The warehouse row: its prefix, status and the board's factor. */
    public record WarehouseRow(UUID id, String prefix, String name, WarehouseStatus status,
                               BigDecimal utilizationFactor) {
    }

    /** One bin: its location, its shelf and the three measures of its volume. */
    public record BinRow(String locationCode, LocationStatus locationStatus, UUID shelfId, LocationStatus shelfStatus,
                         BigDecimal width, BigDecimal length, BigDecimal usableHeight) {
    }

    public record ShelfRow(UUID id, String code, String name, UUID zoneId, LocationStatus status) {
    }

    public record ZoneRow(UUID id, String name) {
    }

    /** A storage area - a {@code NON_STORAGE} one has no location and is not listed. */
    public record AreaRow(UUID id, String code, String name, LocationStatus status, String locationCode) {
    }

    private final EntityManager entityManager;

    CapacityLayoutQuery(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Optional<WarehouseRow> warehouse(UUID warehouseId) {
        return entityManager.createQuery("""
                        select w.id, w.prefix, w.name, w.status, w.utilizationFactor
                        from WarehouseJpaEntity w
                        where w.id = :warehouseId
                        """, WarehouseRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList().stream().findFirst();
    }

    public List<BinRow> bins(UUID warehouseId) {
        return entityManager.createQuery("""
                        select loc.locationCode, loc.status, s.id, s.status, b.width, b.length, l.usableHeight
                        from BinJpaEntity b
                        join b.level l
                        join l.shelf s
                        join b.location loc
                        where s.warehouseId = :warehouseId
                        order by loc.locationCode
                        """, BinRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList();
    }

    public List<ShelfRow> shelves(UUID warehouseId) {
        return entityManager.createQuery("""
                        select s.id, s.code, s.name, s.zoneId, s.status
                        from ShelfJpaEntity s
                        where s.warehouseId = :warehouseId
                        order by s.code
                        """, ShelfRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList();
    }

    public List<ZoneRow> zones(UUID warehouseId) {
        return entityManager.createQuery("""
                        select z.id, z.name
                        from ZoneJpaEntity z
                        where z.warehouseId = :warehouseId
                        order by z.name
                        """, ZoneRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList();
    }

    public List<AreaRow> storageAreas(UUID warehouseId) {
        return entityManager.createQuery("""
                        select a.id, a.code, a.name, a.status, loc.locationCode
                        from AreaJpaEntity a
                        join a.location loc
                        where a.warehouseId = :warehouseId
                        order by a.code
                        """, AreaRow.class)
                .setParameter("warehouseId", warehouseId)
                .getResultList();
    }
}
