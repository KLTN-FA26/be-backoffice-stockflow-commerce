package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.Area;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.entity.AreaJpaEntity;

/** The Area aggregate - the area and its storage location, if it has one - to its rows and back. */
final class AreaPersistenceMapper {

    private AreaPersistenceMapper() {
    }

    static Area toDomain(AreaJpaEntity area) {
        return new Area(area.getId(), area.getWarehouseId(), area.getCode(), area.getType(), area.getName(),
                new Footprint(area.getX(), area.getY(), area.getWidth(), area.getLength(), area.getRotation()),
                area.isObstacle(), area.getStatus(),
                area.getLocation() == null ? null : StorageLocationPersistenceMapper.toDomain(area.getLocation()),
                area.getVersion());
    }

    static AreaJpaEntity toNewEntity(Area area) {
        Footprint f = area.footprint();
        return new AreaJpaEntity(area.id(), area.warehouseId(),
                area.location() == null ? null : StorageLocationPersistenceMapper.toNewEntity(area.location()),
                area.code(), area.type(), area.name(), f.x(), f.y(), f.width(), f.length(), f.rotation(),
                area.obstacle(), area.status());
    }

    /**
     * Whether {@code after} differs from {@code before} in its location's settings and nowhere on the
     * area row itself. Hibernate versions a row by its own columns, so such an edit would change
     * {@code storage_location} and leave the area's version where it was - and a stale form still
     * carrying that version would pass the check and quietly put the old settings back.
     */
    static boolean changesOnlyTheLocation(Area before, Area after) {
        boolean areaUnchanged = before.type() == after.type() && before.name().equals(after.name())
                && before.footprint().equals(after.footprint()) && before.obstacle() == after.obstacle()
                && before.status() == after.status();
        return areaUnchanged && before.location() != null && after.location() != null
                && (before.location().storageClass() != after.location().storageClass()
                || !before.location().settings().equals(after.location().settings()));
    }

    /**
     * Copies {@code area} onto its managed row. A location the row does not have yet - a
     * {@code NON_STORAGE} area that became a storage area - is attached and inserted by cascade at the
     * next flush, ahead of the area's update.
     */
    static void apply(Area area, AreaJpaEntity row) {
        Footprint f = area.footprint();
        row.apply(area.type(), area.name(), f.x(), f.y(), f.width(), f.length(), f.rotation(), area.obstacle(),
                area.status());
        if (area.location() == null) {
            return;
        }
        if (row.getLocation() == null) {
            row.attachLocation(StorageLocationPersistenceMapper.toNewEntity(area.location()));
        } else {
            StorageLocationPersistenceMapper.apply(area.location(), row.getLocation());
        }
    }
}
