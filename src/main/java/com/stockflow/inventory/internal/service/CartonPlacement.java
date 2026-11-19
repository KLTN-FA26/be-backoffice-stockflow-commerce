package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.CartonFit;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.warehouse.api.BinSize;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Does this carton fit that bin?" for every move of a carton into a bin (kltn-docs 06 BR-01): putaway (05 BR-04),
 * put-back (07 BR-12) and a move, by hand or by a document, from {@code OVERFLOW} too (11 BR-10). One place, so the
 * three cannot drift apart; {@link CartonFit} is the rule and {@code tg_stock_package_fits_bin} states it again.
 *
 * <p>Only the size: a putaway also weighs the bin's free volume and load ({@code PutawayRanker}), a move and a
 * put-back do not (11 §4.1 assumption, open question C24). An area takes any carton: its size is not checked
 * (11 BR-10).</p>
 */
@Component
class CartonPlacement {

    private final WarehouseService warehouses;
    private final BinOccupancyQuery space;
    private final StockPackageRepository stockPackages;

    CartonPlacement(WarehouseService warehouses, BinOccupancyQuery space, StockPackageRepository stockPackages) {
        this.warehouses = warehouses;
        this.space = space;
        this.stockPackages = stockPackages;
    }

    /** The bin's inside as the fit rule reads it; null for an area or an unknown location. */
    static CartonFit.Space spaceOf(BinSize size) {
        return size == null ? null : new CartonFit.Space(size.width(), size.length(), size.usableHeight());
    }

    /**
     * Refuses the whole move when {@code locationCode} is a bin and one of the cartons does not fit it.
     *
     * @param cartons carton id to its LPN, which the refusal names
     * @throws BusinessException {@code CARTON_DOES_NOT_FIT_BIN}, naming the first carton that does not fit
     */
    void requireFits(String locationCode, Map<UUID, String> cartons) {
        if (cartons.isEmpty()) {
            return;
        }
        StorageLocationView location = warehouses.findLocation(locationCode).orElse(null);
        if (location == null || location.kind() != StorageLocationView.Kind.BIN) {
            return;
        }
        requireFits(location, cartons);
    }

    /** As {@link #requireFits(String, Map)}, for one carton known by its id; a carton that does not exist is let be. */
    void requireFits(String locationCode, StockPackageId carton) {
        stockPackages.findById(carton).map(StockPackage::lpn)
                .ifPresent(lpn -> requireFits(locationCode, Map.of(carton.value(), lpn)));
    }

    /** As {@link #requireFits(String, Map)}, for a location already read. */
    void requireFits(StorageLocationView location, Map<UUID, String> cartons) {
        CartonFit.Space bin = spaceOf(location.binSize());
        if (bin == null || cartons.isEmpty()) {
            return;
        }
        Map<UUID, Need> needs = space.needsOf(cartons.keySet());
        List<UUID> ordered = cartons.keySet().stream().sorted().toList();
        for (UUID id : ordered) {
            Need need = needs.get(id);
            if (need != null && !CartonFit.fits(need.carton(), bin)) {
                throw doesNotFit(cartons.get(id), location.locationCode());
            }
        }
    }

    /**
     * Where a carton of a cancelled order may go back to (kltn-docs 07 BR-12): a bin it fits, or - only when no bin
     * of its warehouse takes it - an OVERFLOW area of that warehouse (05 BR-13). Any other area is not judged here.
     *
     * @throws BusinessException {@code CARTON_DOES_NOT_FIT_BIN}; {@code PUT_BACK_OVERFLOW_NOT_ALLOWED} for an OVERFLOW
     *         area while a bin still takes the carton
     */
    void requirePutBackTarget(String locationCode, StockPackageId carton) {
        StorageLocationView location = warehouses.findLocation(locationCode).orElse(null);
        if (location != null && location.kind() == StorageLocationView.Kind.AREA
                && location.areaType() == StorageLocationView.AreaType.OVERFLOW) {
            if (!fittingNoBin(location.warehouseId(), List.of(carton.value())).contains(carton.value())) {
                throw new BusinessException(ErrorCode.PUT_BACK_OVERFLOW_NOT_ALLOWED,
                        "A bin still takes this carton; OVERFLOW is for a carton no bin takes");
            }
            return;
        }
        requireFits(locationCode, carton);
    }

    /**
     * The cartons among {@code cartonIds} that no bin of the warehouse takes, dimension by dimension (06 BR-01): the
     * ones that may go into OVERFLOW (07 BR-12, 05 BR-13). The bins are those the warehouse stores in - the putaway
     * targets, usable down to their shelf; with none, every carton fits nowhere. A carton without a full size cannot be
     * judged and counts as fitting, as {@link CartonFit} lets it through.
     */
    java.util.Set<UUID> fittingNoBin(UUID warehouseId, java.util.Collection<UUID> cartonIds) {
        if (cartonIds.isEmpty()) {
            return java.util.Set.of();
        }
        List<CartonFit.Space> bins = warehouses.putawayCandidates(warehouseId).stream()
                .map(candidate -> spaceOf(candidate.size())).toList();
        Map<UUID, Need> needs = space.needsOf(cartonIds);
        java.util.Set<UUID> nowhere = new java.util.HashSet<>();
        for (UUID id : cartonIds) {
            Need need = needs.get(id);
            boolean fits = !bins.isEmpty() && (need == null
                    || bins.stream().anyMatch(bin -> CartonFit.fits(need.carton(), bin)));
            if (!fits) {
                nowhere.add(id);
            }
        }
        return nowhere;
    }

    /** The usable OVERFLOW areas of a warehouse, by location code. */
    List<String> overflowAreas(UUID warehouseId) {
        return warehouses.areasOf(warehouseId, StorageLocationView.AreaType.OVERFLOW).stream()
                .filter(StorageLocationView::usable).map(StorageLocationView::locationCode).toList();
    }

    static BusinessException doesNotFit(String lpn, String locationCode) {
        return new BusinessException(ErrorCode.CARTON_DOES_NOT_FIT_BIN,
                "Carton %s does not fit bin %s".formatted(lpn, locationCode),
                List.of(), Map.of("lpn", lpn, "locationCode", locationCode));
    }
}
