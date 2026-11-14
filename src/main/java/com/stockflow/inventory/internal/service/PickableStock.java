package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The stock an order may be allocated in one warehouse (module 06 BR-12, docs 04c X-SHORT step 03): sellable rows on
 * pickable, usable locations, never a layer an open recount has frozen.
 *
 * <p>One definition for every path that allocates an order's units to bins: a release, and a short pick reallocated
 * (X-SHORT). Two copies of this filter would drift the day a third rule
 * joins it, and an allocation planned by the old copy would send a picker to a bin nobody may pick from.</p>
 *
 * <p>Read as projections, so nothing lands in the persistence context before the caller's locked reads. A plain
 * helper over three ports rather than a bean of its own: it holds no state and opens no transaction.</p>
 */
final class PickableStock {

    private final StockItemRepository stockItems;
    private final LocationDirectory locations;
    private final CycleCountRepository cycleCounts;

    PickableStock(StockItemRepository stockItems, LocationDirectory locations, CycleCountRepository cycleCounts) {
        this.stockItems = stockItems;
        this.locations = locations;
        this.cycleCounts = cycleCounts;
    }

    /**
     * Sellable stock of {@code sku} on pickable, usable locations of {@code warehouseCode}, never the row
     * {@code excluded}: a short pick's carton (kltn-docs 07 BR-09). Only that carton is in doubt - the picker scanned it
     * by its LPN - so the other cartons of its bin stay pickable.
     */
    List<StockAllocator.Candidate> candidates(Sku sku, String warehouseCode, StockItemId excluded) {
        Set<StockItemId> frozen = cycleCounts.frozen(List.of(sku.code())).stream()
                .map(layer -> new StockItemId(layer.stockItemId())).collect(Collectors.toSet());
        List<StockAllocator.Candidate> inWarehouse = stockItems.findAvailabilityBySku(sku).stream()
                .filter(c -> c.location().warehouseCode().equals(warehouseCode))
                .filter(c -> !c.stockItemId().equals(excluded))
                .filter(c -> !frozen.contains(c.stockItemId()))
                .toList();
        Map<String, UUID> pickable = locations.pickable(inWarehouse.stream().map(c -> c.location().code()).toList());
        return inWarehouse.stream().filter(c -> pickable.containsKey(c.location().code())).toList();
    }

    /**
     * Units of each SKU a release could allocate, across every warehouse: whole sealed cartons nothing holds, on
     * pickable, usable locations, none a recount has frozen - what {@link #candidates} offers the planner, summed (team
     * plan 4C-2; kltn-docs 07 BR-03, 20 §5.6). Loose units, an opened carton and a carton in a receiving, staging or
     * quarantine area are on hand but never allocated, so they are not promised. Absent keys mean zero.
     */
    Map<String, Long> allocatableBySku(Collection<String> skus) {
        return allocatable(skus).stream().collect(Collectors.groupingBy(StockItemRepository.Allocatable::sku,
                Collectors.summingLong(StockItemRepository.Allocatable::units)));
    }

    /**
     * The same units of one SKU, per warehouse code (review P-1): a warehouse promises - and a hold at it takes - only
     * what a release there could allocate. Absent keys mean zero.
     */
    Map<String, Long> allocatableByWarehouse(Sku sku) {
        return allocatable(List.of(sku.code())).stream().collect(Collectors.groupingBy(
                row -> new com.stockflow.inventory.internal.domain.LocationId(row.locationCode()).warehouseCode(),
                Collectors.summingLong(StockItemRepository.Allocatable::units)));
    }

    /**
     * WF4-COUNT-EDGE (a): the units of whole free cartons of {@code sku} on pickable, usable locations of
     * {@code warehouseCode} - {@link #allocatableByWarehouse}'s, with the cartons a recount has frozen counted in: a
     * carton whose count is still pending is in doubt, not missing, and covers a hold until its own variance is decided.
     */
    long coverableAt(Sku sku, String warehouseCode) {
        List<StockItemRepository.Allocatable> rows = stockItems.findAllocatable(List.of(sku.code())).stream()
                .filter(row -> new com.stockflow.inventory.internal.domain.LocationId(row.locationCode()).warehouseCode()
                        .equals(warehouseCode))
                .toList();
        if (rows.isEmpty()) {
            return 0;
        }
        Map<String, UUID> pickable = locations.pickable(rows.stream()
                .map(StockItemRepository.Allocatable::locationCode).distinct().toList());
        return rows.stream().filter(row -> pickable.containsKey(row.locationCode()))
                .mapToLong(StockItemRepository.Allocatable::units).sum();
    }

    /** The one filter: the rows of whole free cartons, less the frozen ones and those on no pickable location. */
    private List<StockItemRepository.Allocatable> allocatable(Collection<String> skus) {
        List<StockItemRepository.Allocatable> rows = stockItems.findAllocatable(skus);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<StockItemId> frozen = cycleCounts.frozen(skus).stream()
                .map(layer -> new StockItemId(layer.stockItemId())).collect(Collectors.toSet());
        Map<String, UUID> pickable = locations.pickable(rows.stream()
                .map(StockItemRepository.Allocatable::locationCode).distinct().toList());
        return rows.stream()
                .filter(row -> !frozen.contains(row.id()) && pickable.containsKey(row.locationCode()))
                .toList();
    }
}
