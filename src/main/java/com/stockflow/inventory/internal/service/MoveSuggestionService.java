package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.domain.PutawayRanker.Bin;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The bins a group of cartons may be moved into together, inside their warehouse (issue #412; kltn-docs 11 §4.1,
 * docs#46): advice for the person about to move them, nothing more. Nothing is held or locked - the bins are read as
 * they stand, and a suggestion may be stale by the time the move is sent.
 *
 * <p>The rule is putaway's ({@link PutawayRanker}): every carton fits the bin by size, and the bin's free room - its
 * volume and load, less what stands there and what putaway trips hold for cartons on their way (05 BR-16) - takes the
 * whole group, ranked in putaway's order. The cartons themselves are not counted where they stand (they are leaving),
 * and the places they stand are never suggested: a move to where a carton already is would be refused. An OVERFLOW
 * area is named only when no bin takes the group (05 BR-13).</p>
 *
 * <p>The move ({@code StockOperationsServiceImpl.moveStockPackages}) does not change: it still checks size only, never
 * room (11 BR-10, open question C24), so following a suggestion is never refused for room, and a move to a bin not
 * suggested is not refused for it either. Errors mirror the move's own so the screen sees the same answer twice:
 * a carton unknown (404), outside the caller's warehouses (403), or cartons of two warehouses (409). A carton held for
 * an order or not in stock is not refused here: the move says so, with its reason.</p>
 *
 * <p>The number of statements does not grow with the number of cartons: one read per kind of fact about them (the
 * cartons, their warehouse, their loads and sizes, the bins and what stands there). It does grow with the putaway
 * trips under way: {@code BinSuggestionInputs.heldByTrips} looks up each distinct location a trip holds space in, one
 * read per location, as putaway always has.</p>
 */
@Service
public class MoveSuggestionService {

    /** At most as many cartons as one move takes. */
    static final int MAX_CARTONS = 500;

    /**
     * @param suggestions   up to {@link PutawayRanker#MAX_SUGGESTIONS} bins, best first
     * @param overflowAreas the warehouse's usable OVERFLOW areas, named only when no bin takes the group; else empty
     */
    public record MoveSuggestions(List<PutawayRanker.Suggestion> suggestions, List<String> overflowAreas) {
    }

    private final StockPackageRepository stockPackages;
    private final WarehouseService warehouses;
    private final BinSuggestionInputs inputs;

    public MoveSuggestionService(StockPackageRepository stockPackages, WarehouseService warehouses,
                                 BinSuggestionInputs inputs) {
        this.stockPackages = stockPackages;
        this.warehouses = warehouses;
        this.inputs = inputs;
    }

    /**
     * The bins for these cartons, named by LPN in either case; an LPN named twice is one carton.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for no LPN, a blank one or more than 500;
     *                           {@code STOCK_PACKAGE_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE} for a carton in a warehouse
     *                           the caller is not assigned to; {@code MOVE_ACROSS_WAREHOUSES}
     */
    @Transactional(readOnly = true)
    public MoveSuggestions suggest(List<String> lpns) {
        // Refused before anything is read: an empty group would be taken by every bin.
        if (lpns == null || lpns.isEmpty() || lpns.size() > MAX_CARTONS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Name between 1 and %d cartons".formatted(MAX_CARTONS));
        }
        // Not StockPackageView.normalLpns: it throws IllegalArgumentException; this endpoint answers VALIDATION_FAILED.
        List<String> normal = lpns.stream().map(StockPackageView::normalLpn).toList();
        if (normal.contains(null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "An LPN is not blank");
        }
        List<String> distinct = normal.stream().distinct().toList();

        Map<String, StockPackage> found = stockPackages.findByLpns(distinct).stream()
                .collect(Collectors.toMap(StockPackage::lpn, Function.identity()));
        for (String lpn : distinct) {
            if (!found.containsKey(lpn)) {
                throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + lpn);
            }
        }
        Set<String> standing = found.values().stream().map(carton -> carton.location().code())
                .collect(Collectors.toCollection(TreeSet::new));
        standing.forEach(WarehouseScope::requireLocation);
        Set<UUID> warehouseIds = warehouses.findLocations(standing).stream().map(StorageLocationView::warehouseId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (warehouseIds.isEmpty()) {
            throw new IllegalStateException("No warehouse knows the locations " + standing);
        }
        if (warehouseIds.size() > 1) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "Cartons in %s are not in one warehouse; stock moves only inside one".formatted(standing));
        }
        UUID warehouseId = warehouseIds.iterator().next();

        List<UUID> ids = found.values().stream().map(carton -> carton.id().value()).toList();
        Set<BinOccupancyQuery.SkuLot> group = new HashSet<>();
        inputs.loadsOf(ids).values().forEach(load -> group.add(new BinOccupancyQuery.SkuLot(load.sku(),
                load.lotNumber())));
        Map<UUID, Need> measured = inputs.needsOf(ids);
        List<Need> needs = ids.stream().map(id -> measured.getOrDefault(id, Need.UNMEASURED)).toList();

        List<Bin> bins = inputs.bins(warehouseId).stream()
                .filter(bin -> !standing.contains(bin.locationCode())).toList();
        Map<String, Occupancy> occupancy = inputs.occupancy(bins.stream().map(Bin::locationCode).toList(), group, ids,
                inputs.heldByTrips(warehouseId, null));
        List<PutawayRanker.Suggestion> ranked = PutawayRanker.rank(needs, PutawayRanker.Lot.of(needs), bins,
                occupancy);
        List<String> overflow = ranked.isEmpty()
                ? inputs.overflowAreas(warehouseId).stream().filter(code -> !standing.contains(code)).toList()
                : List.of();
        return new MoveSuggestions(ranked, overflow);
    }
}
