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

    static BusinessException doesNotFit(String lpn, String locationCode) {
        return new BusinessException(ErrorCode.CARTON_DOES_NOT_FIT_BIN,
                "Carton %s does not fit bin %s".formatted(lpn, locationCode),
                List.of(), Map.of("lpn", lpn, "locationCode", locationCode));
    }
}
