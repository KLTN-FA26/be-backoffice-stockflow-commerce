package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.EffectiveStatus;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.repository.LayoutReadRepository.AreaRow;
import com.stockflow.warehouse.internal.repository.LayoutReadRepository.BinRow;
import com.stockflow.warehouse.internal.repository.LayoutReadRepository.BoundaryRow;
import com.stockflow.warehouse.internal.repository.LayoutReadRepository.LevelRow;
import com.stockflow.warehouse.internal.repository.LayoutReadRepository.ShelfRow;
import com.stockflow.warehouse.internal.repository.LayoutReadRepository.WarehouseRow;
import com.stockflow.warehouse.internal.repository.LayoutReadRepository.ZoneRow;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Everything needed to draw one warehouse's map, in one value: the frame, zones, shelves with their
 * levels and bins, areas and boundaries.
 *
 * <p>Places of every status are included, {@code INACTIVE} ones too - a map that silently dropped
 * them could not show an admin what to turn back on. Shelves, bins and areas carry both their own
 * {@code status} and their {@code effectiveStatus} (issue #18 D2); bins and storage areas also
 * {@code usable}, the one flag to read before putting stock anywhere.</p>
 */
public record WarehouseLayout(Warehouse warehouse, List<ZoneSummary> zones, List<Shelf> shelves, List<Area> areas,
                              List<BoundarySummary> boundaries) {

    public record Warehouse(UUID id, String prefix, String name, MapUnit mapUnit, BigDecimal mapWidth,
                            BigDecimal mapHeight, WarehouseStatus status, long version) {
    }

    /** @param effectiveStatus the narrower of the warehouse's and the shelf's own */
    public record Shelf(UUID id, UUID zoneId, String code, String name, String description, Footprint footprint,
                        boolean obstacle, PickFaces pickFaces, StorageClass defaultStorageClass,
                        LocationStatus status, LocationStatus effectiveStatus, long version, List<Level> levels) {
    }

    public record Level(UUID id, int levelIndex, BigDecimal elevation, BigDecimal usableHeight,
                        BigDecimal maxWeight, List<Bin> bins) {
    }

    /**
     * A bin and its storage location, flattened as in {@link ShelfSummary.BinEntry}.
     *
     * @param status          the location's own status
     * @param effectiveStatus the narrowest of the warehouse's, the shelf's and {@code status}
     */
    public record Bin(UUID id, String code, String description, Footprint footprint, BinType type,
                      StorageClass storageClassOverride, UUID locationId, String locationCode,
                      StorageClass storageClass, Integer capacityUnits, BigDecimal maxWeight, boolean pickable,
                      boolean putawayTarget, LocationStatus status, LocationStatus effectiveStatus, boolean usable) {
    }

    /**
     * An area and its storage location, flattened as in {@link AreaSummary}. The location fields and
     * {@code usable} are {@code null} for a {@code NON_STORAGE} area: no stock goes there at all.
     */
    public record Area(UUID id, String code, AreaType type, String name, Footprint footprint, boolean obstacle,
                       LocationStatus status, LocationStatus effectiveStatus, Boolean usable, long version,
                       UUID locationId, String locationCode, StorageClass storageClass, Integer capacityUnits,
                       BigDecimal maxWeight, Boolean pickable, Boolean putawayTarget) {
    }

    /**
     * Puts the rows together in memory: levels under their shelf and bins under their level, each
     * list keeping the order the rows came in.
     */
    static WarehouseLayout assemble(WarehouseRow warehouse, List<ZoneRow> zones, List<ShelfRow> shelves,
                                    List<LevelRow> levels, List<BinRow> bins, List<AreaRow> areas,
                                    List<BoundaryRow> boundaries) {
        WarehouseStatus frame = warehouse.status();
        Map<UUID, List<BinRow>> binsByLevel = bins.stream()
                .collect(Collectors.groupingBy(BinRow::levelId));
        Map<UUID, List<LevelRow>> levelsByShelf = levels.stream()
                .collect(Collectors.groupingBy(LevelRow::shelfId));
        return new WarehouseLayout(
                new Warehouse(warehouse.id(), warehouse.prefix(), warehouse.name(), warehouse.mapUnit(),
                        warehouse.mapWidth(), warehouse.mapHeight(), frame, warehouse.version()),
                zones.stream()
                        .map(z -> new ZoneSummary(z.id(), warehouse.id(), z.name(), z.color(), z.version()))
                        .toList(),
                shelves.stream()
                        .map(s -> shelf(frame, s, levelsByShelf.getOrDefault(s.id(), List.of()), binsByLevel))
                        .toList(),
                areas.stream().map(a -> area(frame, a)).toList(),
                boundaries.stream()
                        .map(b -> new BoundarySummary(b.id(), warehouse.id(), b.type(), b.startX(), b.startY(),
                                b.endX(), b.endY(), b.passable(), b.operationalStatus(), b.version()))
                        .toList());
    }

    private static Shelf shelf(WarehouseStatus frame, ShelfRow s, List<LevelRow> levels,
                               Map<UUID, List<BinRow>> binsByLevel) {
        return new Shelf(s.id(), s.zoneId(), s.code(), s.name(), s.description(),
                new Footprint(s.x(), s.y(), s.width(), s.length(), s.rotation()), s.obstacle(),
                new PickFaces(s.pickNorth(), s.pickEast(), s.pickSouth(), s.pickWest()), s.defaultStorageClass(),
                s.status(), EffectiveStatus.ofShelf(frame, s.status()), s.version(),
                levels.stream()
                        .map(l -> new Level(l.id(), l.levelIndex(), l.elevation(), l.usableHeight(), l.maxWeight(),
                                binsByLevel.getOrDefault(l.id(), List.of()).stream()
                                        .map(b -> bin(frame, s.status(), b))
                                        .toList()))
                        .toList());
    }

    private static Bin bin(WarehouseStatus frame, LocationStatus shelf, BinRow b) {
        LocationStatus effective = EffectiveStatus.ofBin(frame, shelf, b.status());
        return new Bin(b.id(), b.code(), b.description(),
                new Footprint(b.x(), b.y(), b.width(), b.length(), b.rotation()), b.type(),
                b.storageClassOverride(), b.locationId(), b.locationCode(), b.storageClass(), b.capacityUnits(),
                b.maxWeight(), b.pickable(), b.putawayTarget(), b.status(), effective,
                EffectiveStatus.isUsable(effective));
    }

    private static Area area(WarehouseStatus frame, AreaRow a) {
        LocationStatus effective = EffectiveStatus.ofArea(frame, a.status());
        Boolean usable = a.locationId() == null ? null : EffectiveStatus.isUsable(effective);
        return new Area(a.id(), a.code(), a.type(), a.name(),
                new Footprint(a.x(), a.y(), a.width(), a.length(), a.rotation()), a.obstacle(), a.status(),
                effective, usable, a.version(), a.locationId(), a.locationCode(), a.storageClass(),
                a.capacityUnits(), a.maxWeight(), a.pickable(), a.putawayTarget());
    }
}
