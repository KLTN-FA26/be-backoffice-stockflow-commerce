package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.domain.PutawayRanker.Bin;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery.BinContents;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery.SkuLot;
import com.stockflow.warehouse.api.PutawayCandidate;
import com.stockflow.warehouse.api.PutawayHold;
import com.stockflow.warehouse.api.PutawayTrips;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the bin ranking is fed, gathered once for every caller that asks "which bin takes these cartons": a putaway,
 * a putaway trip planning its cart, and a move of a group of cartons (issue #412). The ranking itself is the pure
 * {@link com.stockflow.inventory.internal.domain.PutawayRanker}; this class only reads the bins, what stands in them
 * and the space held for cartons on their way, so the callers cannot drift apart on what counts as taken.
 *
 * <p>Plain reads with no transaction of their own: they join the caller's.</p>
 */
@Component
class BinSuggestionInputs {

    private final WarehouseService warehouses;
    private final PutawayTrips trips;
    private final BinOccupancyQuery occupancy;

    BinSuggestionInputs(WarehouseService warehouses, PutawayTrips trips, BinOccupancyQuery occupancy) {
        this.warehouses = warehouses;
        this.trips = trips;
        this.occupancy = occupancy;
    }

    /**
     * Space that counts as taken although no carton stands there yet (kltn-docs 05 BR-16): a putaway trip holds it
     * for a carton on its cart, or the cart being planned has already given it to an earlier carton.
     */
    record Reserved(String locationCode, String sku, String lotNumber, int units, BigDecimal volumeM3,
                    BigDecimal weightKg) {
    }

    /** The putaway targets of the warehouse, as the ranker reads them. */
    List<Bin> bins(UUID warehouseId) {
        return warehouses.putawayCandidates(warehouseId).stream().map(BinSuggestionInputs::binOf).toList();
    }

    /** One putaway target as the ranker reads it. */
    static Bin binOf(PutawayCandidate candidate) {
        return new Bin(candidate.locationCode(), CartonPlacement.spaceOf(candidate.size()),
                candidate.capacityVolumeM3(), candidate.maxWeightKg());
    }

    /**
     * What the putaway trips on their way hold in the warehouse, except for the carton of {@code exceptTaskId}: a
     * carton does not compete with the space held for itself. A hold in an OVERFLOW area is left out - an area has
     * no volume to run out of (05 BR-13).
     */
    List<Reserved> heldByTrips(UUID warehouseId, UUID exceptTaskId) {
        List<PutawayHold> holds = trips.holds(warehouseId, null).stream()
                .filter(hold -> !hold.putawayTaskId().equals(exceptTaskId) && hold.stockPackageId() != null).toList();
        if (holds.isEmpty()) {
            return List.of();
        }
        Map<UUID, BinOccupancyQuery.CartonLoad> loads = occupancy.loadsOf(holds.stream()
                .map(PutawayHold::stockPackageId).distinct().toList());
        Map<UUID, Optional<StorageLocationView>> locations = new HashMap<>();
        List<Reserved> reserved = new ArrayList<>();
        for (PutawayHold hold : holds) {
            BinOccupancyQuery.CartonLoad load = loads.get(hold.stockPackageId());
            StorageLocationView location = locations.computeIfAbsent(hold.locationId(), warehouses::findLocation)
                    .orElse(null);
            if (load != null && location != null && location.kind() == StorageLocationView.Kind.BIN) {
                reserved.add(new Reserved(location.locationCode(), load.sku(), load.lotNumber(), load.units(),
                        load.volumeM3(), load.weightKg()));
            }
        }
        return reserved;
    }

    /**
     * The occupancy of each bin for this group of cartons: what stands there, the cartons of {@code leaveOut} not
     * counted, plus the space {@code reserved} - a reserved carton counts as standing there, its SKU and lot included
     * when they are the group's. Then reduced for the group ({@link BinContents#toOccupancy}): the same SKU / lot only
     * when the bin holds all of the group. A bin holding nothing and reserved for nothing is absent.
     */
    Map<String, Occupancy> occupancy(Collection<String> locationCodes, Set<SkuLot> group, Collection<UUID> leaveOut,
                                     List<Reserved> reserved) {
        Map<String, BinContents> contents = new HashMap<>(occupancy.contentsOf(locationCodes, group, leaveOut));
        Set<String> groupSkus = new HashSet<>();
        group.forEach(pair -> groupSkus.add(pair.sku()));
        for (Reserved r : reserved) {
            BinContents standing = contents.getOrDefault(r.locationCode(), BinContents.EMPTY);
            Set<String> skus = new HashSet<>(standing.skus());
            if (r.sku() != null && groupSkus.contains(r.sku())) {
                skus.add(r.sku());
            }
            Set<SkuLot> lots = new HashSet<>(standing.lots());
            SkuLot pair = new SkuLot(r.sku(), r.lotNumber());
            if (r.lotNumber() != null && group.contains(pair)) {
                lots.add(pair);
            }
            contents.put(r.locationCode(), new BinContents(standing.units() + r.units(),
                    standing.volumeM3().add(r.volumeM3() == null ? BigDecimal.ZERO : r.volumeM3()),
                    standing.weightKg().add(r.weightKg() == null ? BigDecimal.ZERO : r.weightKg()), skus, lots));
        }
        Map<String, Occupancy> out = new HashMap<>();
        contents.forEach((code, c) -> out.put(code, c.toOccupancy(group)));
        return out;
    }

    /** The usable OVERFLOW areas of a warehouse, by location code. */
    List<String> overflowAreas(UUID warehouseId) {
        return CartonPlacement.overflowAreas(warehouses, warehouseId);
    }

    /** What each of these cartons takes of a bin; see {@link BinOccupancyQuery#needsOf}. */
    Map<UUID, Need> needsOf(Collection<UUID> cartonIds) {
        return occupancy.needsOf(cartonIds);
    }

    /** What each of these cartons adds to a bin; see {@link BinOccupancyQuery#loadsOf}. */
    Map<UUID, BinOccupancyQuery.CartonLoad> loadsOf(Collection<UUID> cartonIds) {
        return occupancy.loadsOf(cartonIds);
    }
}
