package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The stock an order may be allocated in one warehouse (module 06 BR-12, docs 04c X-SHORT step 03): sellable rows on
 * pickable, usable locations, never a layer an open recount has frozen.
 *
 * <p>One definition for every path that allocates an order's units to bins: a release, a short pick reallocated
 * (X-SHORT), damaged units replaced at picking (SCRUM-277). Two copies of this filter would drift the day a third rule
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

    /** Sellable stock of {@code sku} on pickable, usable locations of {@code warehouseCode}, never at {@code excluded}. */
    List<StockAllocator.Candidate> candidates(Sku sku, String warehouseCode, LocationId excluded, Instant now) {
        Set<StockItemId> frozen = cycleCounts.frozen(List.of(sku.code()), BusinessCalendar.date(now)).stream()
                .map(layer -> new StockItemId(layer.stockItemId())).collect(Collectors.toSet());
        List<StockAllocator.Candidate> inWarehouse = stockItems.findAvailabilityBySku(sku).stream()
                .filter(c -> c.location().warehouseCode().equals(warehouseCode))
                .filter(c -> !c.location().equals(excluded))
                .filter(c -> !frozen.contains(c.stockItemId()))
                .toList();
        Map<String, UUID> pickable = locations.pickable(inWarehouse.stream().map(c -> c.location().code()).toList());
        return inWarehouse.stream().filter(c -> pickable.containsKey(c.location().code())).toList();
    }
}
