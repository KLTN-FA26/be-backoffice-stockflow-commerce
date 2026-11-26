package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The stock an order may be allocated in one warehouse (module 06 BR-12, docs 04c X-SHORT step 03): free cartons -
 * AVAILABLE, no live allocation - on pickable, usable locations, never a carton an open recount has frozen. A carton
 * standing in a receiving, staging or quarantine area is on no pickable location, which is how staged stock stays out
 * of every plan and every promise (20 BR-20).
 *
 * <p>One definition for every path that allocates cartons to an order: a release, and a short pick reallocated
 * (X-SHORT). Two copies of this filter would drift the day a third rule
 * joins it, and an allocation planned by the old copy would send a picker to a bin nobody may pick from.</p>
 *
 * <p>Read as projections, so nothing lands in the persistence context before the caller's locked reads. A plain
 * helper over three ports rather than a bean of its own: it holds no state and opens no transaction.</p>
 */
final class PickableStock {

    private final StockPackageRepository stockPackages;
    private final LocationDirectory locations;
    private final CycleCountRepository cycleCounts;

    PickableStock(StockPackageRepository stockPackages, LocationDirectory locations, CycleCountRepository cycleCounts) {
        this.stockPackages = stockPackages;
        this.locations = locations;
        this.cycleCounts = cycleCounts;
    }

    /**
     * Free cartons of {@code sku} on pickable, usable locations of {@code warehouseCode}, never the carton
     * {@code excluded}: a short pick's carton (kltn-docs 07 BR-09). Only that carton is in doubt - the picker scanned it
     * by its LPN - so the other cartons of its bin stay pickable.
     */
    List<StockAllocator.Candidate> candidates(Sku sku, String warehouseCode, StockPackageId excluded) {
        Set<StockPackageId> frozen = cycleCounts.frozen(List.of(sku.code())).stream()
                .map(layer -> new StockPackageId(layer.stockPackageId())).collect(Collectors.toSet());
        List<StockAllocator.Candidate> inWarehouse = stockPackages.findAvailabilityBySku(sku).stream()
                .filter(c -> c.location().warehouseCode().equals(warehouseCode))
                .filter(c -> !c.stockPackageId().equals(excluded))
                .filter(c -> !frozen.contains(c.stockPackageId()))
                .toList();
        Map<String, UUID> pickable = locations.pickable(inWarehouse.stream().map(c -> c.location().code()).toList());
        return inWarehouse.stream().filter(c -> pickable.containsKey(c.location().code())).toList();
    }

    /**
     * Units of each SKU a release could allocate, across every warehouse: whole sealed cartons nothing holds, on
     * pickable, usable locations, none a recount has frozen - what {@link #candidates} offers the planner, in units of
     * their counts (team plan 4C-2; kltn-docs 07 BR-03, 20 §5.6). A carton in a receiving, staging or quarantine area
     * is in stock but never allocated, so it is not promised. Absent keys mean zero.
     */
    Map<String, Long> allocatableBySku(Collection<String> skus) {
        return allocatable(skus).stream().collect(Collectors.groupingBy(StockPackageRepository.Allocatable::sku,
                Collectors.summingLong(StockPackageRepository.Allocatable::units)));
    }

    /**
     * The same units of one SKU, per warehouse code (review P-1): a warehouse promises - and a hold at it takes - only
     * what a release there could allocate. Absent keys mean zero.
     */
    Map<String, Long> allocatableByWarehouse(Sku sku) {
        return allocatable(List.of(sku.code())).stream().collect(Collectors.groupingBy(
                row -> new com.stockflow.inventory.internal.domain.LocationId(row.locationCode()).warehouseCode(),
                Collectors.summingLong(StockPackageRepository.Allocatable::units)));
    }

    /**
     * The cartons of {@code unitsPerCarton} of one SKU a release could allocate, per warehouse code (ADR-0011): the
     * cartons of {@link #allocatableByWarehouse} that are of exactly that count, counted. A hold of cartons of
     * 1000 is promised - and later served - from these only; a free carton of another count is not one of them.
     * Absent keys mean none.
     */
    Map<String, Long> allocatableCartonsByWarehouse(Sku sku, int unitsPerCarton) {
        return allocatable(List.of(sku.code())).stream().filter(row -> row.units() == unitsPerCarton)
                .collect(Collectors.groupingBy(
                        row -> new com.stockflow.inventory.internal.domain.LocationId(row.locationCode()).warehouseCode(),
                        Collectors.counting()));
    }

    /**
     * WF4-COUNT-EDGE (a): the whole free cartons of {@code sku} on pickable, usable locations of {@code warehouseCode},
     * how many of each count - {@link #allocatableByWarehouse}'s, with the cartons a recount has frozen counted in: a
     * carton whose count is still pending is in doubt, not missing, and covers a hold until its own variance is decided.
     * By count because a hold is covered only by cartons of its own (ADR-0011). Absent keys mean none.
     */
    Map<Integer, Long> coverableCartonsAt(Sku sku, String warehouseCode) {
        List<StockPackageRepository.Allocatable> rows = stockPackages.findAllocatable(List.of(sku.code())).stream()
                .filter(row -> new com.stockflow.inventory.internal.domain.LocationId(row.locationCode()).warehouseCode()
                        .equals(warehouseCode))
                .toList();
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> pickable = locations.pickable(rows.stream()
                .map(StockPackageRepository.Allocatable::locationCode).distinct().toList());
        return rows.stream().filter(row -> pickable.containsKey(row.locationCode()))
                .collect(Collectors.groupingBy(StockPackageRepository.Allocatable::units, Collectors.counting()));
    }

    /** The one filter: the free cartons, less the frozen ones and those on no pickable location. */
    private List<StockPackageRepository.Allocatable> allocatable(Collection<String> skus) {
        List<StockPackageRepository.Allocatable> rows = stockPackages.findAllocatable(skus);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<StockPackageId> frozen = cycleCounts.frozen(skus).stream()
                .map(layer -> new StockPackageId(layer.stockPackageId())).collect(Collectors.toSet());
        Map<String, UUID> pickable = locations.pickable(rows.stream()
                .map(StockPackageRepository.Allocatable::locationCode).distinct().toList());
        return rows.stream()
                .filter(row -> !frozen.contains(row.id()) && pickable.containsKey(row.locationCode()))
                .toList();
    }
}
