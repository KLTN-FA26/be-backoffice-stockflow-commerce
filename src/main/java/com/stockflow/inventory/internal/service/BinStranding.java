package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.domain.CartonFit;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery.CartonIn;
import com.stockflow.warehouse.api.BinSize;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.spi.BinOccupants;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * kltn-docs 06 BR-23 (decided 2026-11-05): a change of measures must not strand a carton - leave one standing in a
 * bin it no longer fits, or a bin holding more volume than it now has. Two changes can do it, and both ask here:
 *
 * <ul>
 *   <li>the warehouse map resizing a bin or a level's clear height ({@link BinOccupants}, the warehouse's SPI, asked
 *       synchronously by its shelf service so the edit is refused, not undone);</li>
 *   <li>a carton unit's length, width or height changed ({@code UomConversionServiceImpl.change}).</li>
 * </ul>
 *
 * <p>The judgement is the one every move makes ({@link CartonFit}) plus the bin's volume against the cartons' volumes.
 * What is not measured is not judged: a carton without a full size, a bin without a clear height. Areas are not
 * judged: area volume (06 BR-19, a stacking height) is not modelled yet.</p>
 *
 * <p>Not serialised against moves: a carton moved into a bin while the bin is being resized may slip past both
 * checks. The fit trigger ({@code tg_stock_package_fits_bin}) judges the move against the bin as committed.</p>
 */
@Component
class BinStranding implements BinOccupants {

    private final BinOccupancyQuery space;
    private final WarehouseService warehouses;

    BinStranding(BinOccupancyQuery space, WarehouseService warehouses) {
        this.space = space;
        this.warehouses = warehouses;
    }

    @Override
    public List<Stranded> strandedBy(Map<String, BinSize> newSizes) {
        if (newSizes.isEmpty()) {
            return List.of();
        }
        return judge(space.cartonsIn(newSizes.keySet()), newSizes::get);
    }

    /**
     * Refuses a new size for a carton unit when a carton of it would no longer fit the bin it stands in, or would
     * overfill it.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARTONS_WOULD_NOT_FIT}, naming the bins and cartons
     */
    void requireUnitResizable(UUID unitId, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {
        List<String> codes = space.locationsOfUnit(unitId);
        if (codes.isEmpty()) {
            return;
        }
        Map<String, BinSize> bins = warehouses.findLocations(codes).stream()
                .filter(location -> location.kind() == StorageLocationView.Kind.BIN && location.binSize() != null)
                .collect(Collectors.toMap(StorageLocationView::locationCode, StorageLocationView::binSize));
        List<CartonIn> resized = space.cartonsIn(bins.keySet()).stream()
                .map(c -> c.unitId().equals(unitId) ? new CartonIn(c.locationCode(), c.cartonId(), c.unitId(), c.lpn(),
                        new CartonFit.Carton(lengthCm, widthCm, heightCm, c.carton().rotatable())) : c)
                .toList();
        BinOccupants.refuse(judge(resized, bins::get), "the carton unit's new measures");
    }

    private static List<Stranded> judge(List<CartonIn> cartons, Function<String, BinSize> sizeOf) {
        Map<String, List<CartonIn>> byBin = cartons.stream()
                .collect(Collectors.groupingBy(CartonIn::locationCode, LinkedHashMap::new, Collectors.toList()));
        List<Stranded> out = new ArrayList<>();
        byBin.forEach((code, inBin) -> {
            BinSize size = sizeOf.apply(code);
            if (size == null) {
                return;
            }
            CartonFit.Space bin = CartonPlacement.spaceOf(size);
            inBin.stream().filter(c -> !CartonFit.fits(c.carton(), bin))
                    .forEach(c -> out.add(new Stranded(code, c.lpn(), Why.DOES_NOT_FIT)));
            BigDecimal volume = size.volumeM3();
            BigDecimal taken = inBin.stream().map(c -> c.carton().volumeM3()).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (volume != null && taken.compareTo(volume) > 0) {
                out.add(new Stranded(code, null, Why.OVER_VOLUME));
            }
        });
        return out;
    }
}
