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
     * Units of each SKU a release could allocate, across every warehouse (a checkout reservation is promised against
     * the whole system): whole sealed cartons nothing holds, on pickable bins, none a recount has frozen -
     * what {@link #candidates} offers the planner, summed (team plan 4C-2; kltn-docs 07 BR-03, 20 §5.6). Loose units
     * and an opened carton are on hand but never allocated, so they are not promised. Absent keys mean zero.
     */
    Map<String, Long> allocatableBySku(Collection<String> skus) {
        List<StockItemRepository.Allocatable> rows = stockItems.findAllocatable(skus);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Set<StockItemId> frozen = cycleCounts.frozen(skus).stream()
                .map(layer -> new StockItemId(layer.stockItemId())).collect(Collectors.toSet());
        Map<String, UUID> pickable = locations.pickable(rows.stream()
                .map(StockItemRepository.Allocatable::locationCode).distinct().toList());
        return rows.stream()
                .filter(row -> !frozen.contains(row.id()) && pickable.containsKey(row.locationCode()))
                .collect(Collectors.groupingBy(StockItemRepository.Allocatable::sku,
                        Collectors.summingLong(StockItemRepository.Allocatable::units)));
    }
}
