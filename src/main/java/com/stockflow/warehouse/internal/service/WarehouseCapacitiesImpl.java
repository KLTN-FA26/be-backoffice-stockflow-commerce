package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.api.BinCapacity;
import com.stockflow.warehouse.api.BinSize;
import com.stockflow.warehouse.api.CapacityGroup;
import com.stockflow.warehouse.api.WarehouseCapacities;
import com.stockflow.warehouse.api.WarehouseCapacity;
import com.stockflow.warehouse.internal.domain.EffectiveStatus;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery.AreaRow;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery.BinRow;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery.ShelfRow;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery.WarehouseRow;
import com.stockflow.warehouse.internal.repository.CapacityLayoutQuery.ZoneRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The map's half of the capacity board (kltn-docs 06 §4.6, BR-19, BR-20; C40, C43). Reads the warehouse's bins, shelves,
 * zones and storage areas and adds up, per warehouse and per row below it, the computed volume of the bins usable now.
 *
 * <p>Usable is {@link EffectiveStatus#isUsable} of the bin, its shelf and its warehouse - the rule every other reader
 * of a location applies, so a blocked bin offers no room on the board as it takes no carton at putaway. A bin with no
 * clear height has no volume ({@link BinSize#volumeM3()}) and is counted apart while it is on the layout (C40). A shelf
 * or area taken off the layout gets no row: nothing may stand in it (06 BR-04).</p>
 */
@Service
@Transactional(readOnly = true)
class WarehouseCapacitiesImpl implements WarehouseCapacities {

    static final String NO_ZONE = "NO_ZONE";

    private final CapacityLayoutQuery layout;

    WarehouseCapacitiesImpl(CapacityLayoutQuery layout) {
        this.layout = layout;
    }

    @Override
    public Optional<WarehouseCapacity> capacityOf(UUID warehouseId) {
        if (warehouseId == null) {
            return Optional.empty();
        }
        return layout.warehouse(warehouseId).map(warehouse -> assemble(warehouse, layout.zones(warehouseId),
                layout.shelves(warehouseId), layout.bins(warehouseId), layout.storageAreas(warehouseId)));
    }

    @Override
    public List<BinCapacity> binsOf(UUID warehouseId) {
        if (warehouseId == null) {
            return List.of();
        }
        return layout.warehouse(warehouseId).map(warehouse -> layout.binDetails(warehouseId).stream()
                .filter(bin -> bin.shelfStatus() != LocationStatus.INACTIVE)
                .map(bin -> {
                    LocationStatus effective = EffectiveStatus.ofBin(warehouse.status(), bin.shelfStatus(),
                            bin.locationStatus());
                    return new BinCapacity(bin.locationCode(), bin.shelfCode(),
                            new BinSize(bin.width(), bin.length(), bin.usableHeight()).volumeM3(), bin.maxWeight(),
                            effective.name(), EffectiveStatus.isUsable(effective));
                }).toList()).orElse(List.of());
    }

    /** The board's map half from the rows read; pure, so the rules can be read in one place. */
    static WarehouseCapacity assemble(WarehouseRow warehouse, List<ZoneRow> zones, List<ShelfRow> shelves,
                                      List<BinRow> bins, List<AreaRow> areas) {
        Map<UUID, ShelfRow> shelfById = new HashMap<>();
        shelves.forEach(shelf -> shelfById.put(shelf.id(), shelf));
        Map<String, Tally> tallies = new LinkedHashMap<>();
        // The order of the rows: zones by name, the shelves of no zone, shelves by code, areas by code.
        zones.forEach(zone -> tallies.put(key("ZONE", zone.id()), new Tally(CapacityGroup.Kind.ZONE, zone.id(), null,
                zone.name())));
        if (shelves.stream().anyMatch(shelf -> onLayout(shelf) && shelf.zoneId() == null)) {
            tallies.put(NO_ZONE, new Tally(CapacityGroup.Kind.NO_ZONE, null, null, null));
        }
        shelves.stream().filter(WarehouseCapacitiesImpl::onLayout).forEach(shelf -> tallies.put(key("SHELF",
                shelf.id()), new Tally(CapacityGroup.Kind.SHELF, shelf.id(), shelf.code(), shelf.name())));
        areas.stream().filter(area -> area.status() != LocationStatus.INACTIVE).forEach(area -> tallies.put(
                key("AREA", area.id()), new Tally(CapacityGroup.Kind.AREA, area.id(), area.code(), area.name())));

        Map<String, List<String>> groupsOfLocation = new LinkedHashMap<>();
        BigDecimal binVolume = BigDecimal.ZERO;
        int withoutVolume = 0;
        for (BinRow bin : bins) {
            ShelfRow shelf = shelfById.get(bin.shelfId());
            if (shelf == null || !onLayout(shelf)) {
                continue;
            }
            List<String> keys = List.of(shelf.zoneId() == null ? NO_ZONE : key("ZONE", shelf.zoneId()),
                    key("SHELF", shelf.id()));
            groupsOfLocation.put(bin.locationCode(), keys);
            BigDecimal volume = new BinSize(bin.width(), bin.length(), bin.usableHeight()).volumeM3();
            boolean missing = volume == null && EffectiveStatus.isOnLayout(bin.shelfStatus(), bin.locationStatus());
            boolean usable = volume != null && EffectiveStatus.isUsable(EffectiveStatus.ofBin(warehouse.status(),
                    bin.shelfStatus(), bin.locationStatus()));
            if (missing) {
                withoutVolume++;
            }
            if (usable) {
                binVolume = binVolume.add(volume);
            }
            for (String key : keys) {
                Tally tally = tallies.get(key);
                if (missing) {
                    tally.withoutVolume++;
                }
                if (usable) {
                    tally.volume = tally.volume.add(volume);
                }
            }
        }
        for (AreaRow area : areas) {
            if (area.status() != LocationStatus.INACTIVE) {
                groupsOfLocation.put(area.locationCode(), List.of(key("AREA", area.id())));
            }
        }
        List<CapacityGroup> groups = new ArrayList<>();
        tallies.forEach((key, tally) -> groups.add(new CapacityGroup(key, tally.kind, tally.id, tally.code, tally.name,
                tally.kind == CapacityGroup.Kind.AREA ? null : tally.volume, tally.withoutVolume)));
        return new WarehouseCapacity(warehouse.id(), warehouse.prefix(), warehouse.name(),
                warehouse.status() == WarehouseStatus.ACTIVE, warehouse.utilizationFactor(), binVolume, withoutVolume,
                List.copyOf(groups), groupsOfLocation);
    }

    private static boolean onLayout(ShelfRow shelf) {
        return shelf.status() != LocationStatus.INACTIVE;
    }

    private static String key(String kind, UUID id) {
        return kind + ":" + id;
    }

    /** A row being added up. */
    private static final class Tally {
        private final CapacityGroup.Kind kind;
        private final UUID id;
        private final String code;
        private final String name;
        private BigDecimal volume = BigDecimal.ZERO;
        private int withoutVolume;

        private Tally(CapacityGroup.Kind kind, UUID id, String code, String name) {
            this.kind = kind;
            this.id = id;
            this.code = code;
            this.name = name;
        }
    }
}
