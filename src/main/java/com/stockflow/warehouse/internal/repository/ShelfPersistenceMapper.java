package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.Bin;
import com.stockflow.warehouse.internal.domain.BinDetails;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LevelMeasures;
import com.stockflow.warehouse.internal.domain.LocationSettings;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.Shelf;
import com.stockflow.warehouse.internal.domain.ShelfLevel;
import com.stockflow.warehouse.internal.domain.StorageLocation;
import com.stockflow.warehouse.internal.entity.BinJpaEntity;
import com.stockflow.warehouse.internal.entity.ShelfJpaEntity;
import com.stockflow.warehouse.internal.entity.ShelfLevelJpaEntity;
import com.stockflow.warehouse.internal.entity.StorageLocationJpaEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Shelf aggregate to its rows and back.
 *
 * <p><b>{@link #merge} never replaces a collection.</b> It updates the child with the same id in
 * place and appends children that are new; nothing is ever removed. Clearing and re-adding would
 * flush the INSERTs before the DELETEs and trip {@code uk_bin_level_code} and
 * {@code uk_storage_location_code} - and a deleted bin would break the foreign keys other modules
 * hold to its location.</p>
 */
final class ShelfPersistenceMapper {

    private ShelfPersistenceMapper() {
    }

    static Shelf toDomain(ShelfJpaEntity shelf, List<ShelfLevelJpaEntity> levels) {
        return new Shelf(shelf.getId(), shelf.getWarehouseId(), shelf.getZoneId(), shelf.getCode(),
                shelf.getName(), shelf.getDescription(),
                new Footprint(shelf.getX(), shelf.getY(), shelf.getWidth(), shelf.getLength(), shelf.getRotation()),
                shelf.isObstacle(),
                new PickFaces(shelf.isPickNorth(), shelf.isPickEast(), shelf.isPickSouth(), shelf.isPickWest()),
                shelf.getDefaultStorageClass(), shelf.getStatus(),
                levels.stream().map(ShelfPersistenceMapper::toDomain).toList(), shelf.getVersion());
    }

    private static ShelfLevel toDomain(ShelfLevelJpaEntity level) {
        return new ShelfLevel(level.getId(), level.getLevelIndex(),
                new LevelMeasures(level.getElevation(), level.getUsableHeight(), level.getMaxWeight()),
                level.getBins().stream().map(ShelfPersistenceMapper::toDomain).toList());
    }

    private static Bin toDomain(BinJpaEntity bin) {
        StorageLocationJpaEntity location = bin.getLocation();
        return new Bin(bin.getId(), bin.getCode(),
                new BinDetails(bin.getDescription(),
                        new Footprint(bin.getX(), bin.getY(), bin.getWidth(), bin.getLength(), bin.getRotation()),
                        bin.getType(), bin.getStorageClassOverride()),
                new StorageLocation(location.getId(), location.getWarehouseId(), location.getKind(),
                        location.getLocationCode(), location.getStorageClass(),
                        new LocationSettings(location.getCapacityUnits(), location.getMaxWeight(),
                                location.isPickable(), location.isPutawayTarget()),
                        location.getStatus()));
    }

    static ShelfJpaEntity toNewEntity(Shelf shelf) {
        Footprint f = shelf.footprint();
        PickFaces faces = shelf.pickFaces();
        ShelfJpaEntity entity = new ShelfJpaEntity(shelf.id(), shelf.warehouseId(), shelf.zoneId(), shelf.code(),
                shelf.name(), shelf.description(), f.x(), f.y(), f.width(), f.length(), f.rotation(),
                shelf.obstacle(), faces.north(), faces.east(), faces.south(), faces.west(),
                shelf.defaultStorageClass(), shelf.status());
        shelf.levels().forEach(level -> entity.addLevel(toNewEntity(level)));
        return entity;
    }

    /**
     * Copies {@code shelf} onto its managed rows: the shelf itself, then each level and bin by id -
     * updated in place if it has a row, appended if it is new. New rows are inserted by cascade on
     * the next flush, each bin's location before the bin.
     *
     * @param levels the shelf's level rows, bins initialised ({@code ShelfLevelJpaRepository.findWithBinsByShelfId})
     */
    static void merge(Shelf shelf, ShelfJpaEntity row, List<ShelfLevelJpaEntity> levels) {
        Footprint f = shelf.footprint();
        PickFaces faces = shelf.pickFaces();
        row.apply(shelf.zoneId(), shelf.name(), shelf.description(), f.x(), f.y(), f.width(), f.length(),
                f.rotation(), shelf.obstacle(), faces.north(), faces.east(), faces.south(), faces.west(),
                shelf.defaultStorageClass(), shelf.status());

        Map<UUID, ShelfLevelJpaEntity> levelRows = new HashMap<>();
        levels.forEach(level -> levelRows.put(level.getId(), level));
        for (ShelfLevel level : shelf.levels()) {
            ShelfLevelJpaEntity levelRow = levelRows.get(level.id());
            if (levelRow == null) {
                row.addLevel(toNewEntity(level));
                continue;
            }
            LevelMeasures m = level.measures();
            levelRow.apply(m.elevation(), m.usableHeight(), m.maxWeight());

            Map<UUID, BinJpaEntity> binRows = new HashMap<>();
            levelRow.getBins().forEach(bin -> binRows.put(bin.getId(), bin));
            for (Bin bin : level.bins()) {
                BinJpaEntity binRow = binRows.get(bin.id());
                if (binRow == null) {
                    levelRow.addBin(toNewEntity(bin));
                } else {
                    apply(bin, binRow);
                }
            }
        }
    }

    private static ShelfLevelJpaEntity toNewEntity(ShelfLevel level) {
        LevelMeasures m = level.measures();
        ShelfLevelJpaEntity entity = new ShelfLevelJpaEntity(level.id(), level.levelIndex(), m.elevation(),
                m.usableHeight(), m.maxWeight());
        level.bins().forEach(bin -> entity.addBin(toNewEntity(bin)));
        return entity;
    }

    private static BinJpaEntity toNewEntity(Bin bin) {
        Footprint f = bin.footprint();
        StorageLocation l = bin.location();
        StorageLocationJpaEntity location = new StorageLocationJpaEntity(l.id(), l.warehouseId(), l.kind(),
                l.locationCode(), l.storageClass(), l.capacityUnits(), l.maxWeight(), l.pickable(),
                l.putawayTarget(), l.status());
        return new BinJpaEntity(bin.id(), location, bin.code(), bin.details().description(), f.x(), f.y(),
                f.width(), f.length(), f.rotation(), bin.details().type(), bin.details().storageClassOverride());
    }

    private static void apply(Bin bin, BinJpaEntity row) {
        Footprint f = bin.footprint();
        row.apply(bin.details().description(), f.x(), f.y(), f.width(), f.length(), f.rotation(),
                bin.details().type(), bin.details().storageClassOverride());
        StorageLocation l = bin.location();
        row.getLocation().apply(l.storageClass(), l.capacityUnits(), l.maxWeight(), l.pickable(),
                l.putawayTarget(), l.status());
    }
}
