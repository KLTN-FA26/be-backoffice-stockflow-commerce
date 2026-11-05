package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.ScannedCodeQueries;
import com.stockflow.inventory.internal.domain.StockPackageBalance;
import com.stockflow.inventory.internal.repository.InventoryItemSearch;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link ScannedCodeQueries} over the carton lookup ({@link StockPackageLookup}), the screen's reads with their warehouse
 * checks, and an exact barcode read of the item table ({@link InventoryItemSearch}), shaped as the api's records.
 */
@Service
@Transactional(readOnly = true)
public class ScannedCodeQueriesImpl implements ScannedCodeQueries {

    private final StockPackageLookup lookup;
    private final InventoryItemSearch items;
    private final WarehouseService warehouses;

    ScannedCodeQueriesImpl(StockPackageLookup lookup, InventoryItemSearch items, WarehouseService warehouses) {
        this.lookup = lookup;
        this.items = items;
        this.warehouses = warehouses;
    }

    @Override
    public Carton carton(String lpn) {
        StockPackageBalance.Detail found = lookup.byLpn(lpn);
        StockPackageBalance carton = found.carton();
        return new Carton(carton.lpn(), carton.sku(), carton.unitOfMeasure(), carton.nominalQty(),
                carton.locationCode(), carton.status().name(), carton.unopened(), carton.lotNumber(),
                carton.expiryDate(), carton.onHand(), carton.reserved(), found.contents().stream()
                .map(row -> new CartonContent(row.lotNumber(), row.expiryDate(), row.status().name(), row.onHand(),
                        row.reserved())).toList());
    }

    /**
     * A code whose prefix is no warehouse's is no location: {@code LOCATION_NOT_FOUND} at once, before the screen's
     * warehouse check would refuse it as another warehouse's ({@code EVAL-CUP-12OZ} is a SKU, not a bin of a warehouse
     * "EVAL"). Warehouse prefixes are no secret; whether a bin exists in another warehouse stays one.
     */
    @Override
    public List<LocationLine> atLocation(String locationCode) {
        if (locationCode == null || !warehouses.isWarehousePrefix(WarehouseScope.prefixOf(locationCode))) {
            throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + locationCode);
        }
        return lookup.atLocation(locationCode).stream()
                .map(line -> new LocationLine(line.sku(), line.lotNumber(), line.expiryDate(), line.status().name(),
                        line.wholeCartons(), line.wholeCartonUnits(), line.openedCartons(), line.openedCartonUnits(),
                        line.looseUnits(), line.totalUnits()))
                .toList();
    }

    /** An exact match, never the item list's substring search: a short barcode is part of many SKUs. */
    @Override
    public List<String> skusWithBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return List.of();
        }
        return items.skusWithBarcode(barcode.strip());
    }
}
