package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.api.CartonSpec;
import com.stockflow.inventory.api.OccupiedVolume;
import com.stockflow.inventory.api.WarehouseOccupancy;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.WarehouseOccupancyQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inventory's half of the capacity board (kltn-docs 06 §4.6; BE1). Read-only, in the caller's transaction.
 *
 * <p>A carton of a SKU is measured by its carton spec ({@link InventoryPolicyRepository#findCartonSpecs}, the rule of
 * {@code InventoryControlService.cartonSpec}); a carton standing somewhere, by its own carton unit. They are the same
 * unit for every carton a SKU is received in since V20261111000110, so what is coming and what is there are measured
 * alike.</p>
 */
@Service
@Transactional(readOnly = true)
class WarehouseOccupancyImpl implements WarehouseOccupancy {

    private static final BigDecimal CUBIC_CM_PER_M3 = BigDecimal.valueOf(1_000_000L);

    private final WarehouseOccupancyQuery query;
    private final InventoryPolicyRepository policies;

    WarehouseOccupancyImpl(WarehouseOccupancyQuery query, InventoryPolicyRepository policies) {
        this.query = query;
        this.policies = policies;
    }

    @Override
    public OccupiedVolume occupiedIn(String warehousePrefix) {
        BigDecimal volume = BigDecimal.ZERO.setScale(6);
        BigDecimal weight = BigDecimal.ZERO;
        int cartons = 0;
        int withoutSize = 0;
        Map<String, BigDecimal> byLocation = new LinkedHashMap<>();
        for (WarehouseOccupancyQuery.AtLocation at : query.inStockByLocation(warehousePrefix)) {
            volume = volume.add(at.volumeM3());
            weight = weight.add(at.weightKg());
            cartons += at.cartons();
            withoutSize += at.cartonsWithoutSize();
            byLocation.put(at.locationCode(), at.volumeM3());
        }
        return new OccupiedVolume(volume, weight, cartons, withoutSize, byLocation);
    }

    /** A SKU hold is whole cartons of the SKU's carton spec (17 BR-21): its units over the standard count, times a carton. */
    @Override
    public BigDecimal heldVolumeM3(String warehousePrefix) {
        Map<String, Long> held = query.skuHoldUnits(warehousePrefix);
        Map<String, CartonSpec> specs = held.isEmpty() ? Map.of() : policies.findCartonSpecs(held.keySet());
        BigDecimal volume = query.allocatedVolumeM3(warehousePrefix);
        for (Map.Entry<String, Long> hold : held.entrySet()) {
            CartonSpec spec = specs.get(hold.getKey());
            BigDecimal carton = spec == null ? null : volumeOf(spec);
            if (carton == null || spec.unitsPerCarton() <= 0) {
                continue;
            }
            volume = volume.add(carton.multiply(BigDecimal.valueOf(hold.getValue()))
                    .divide(BigDecimal.valueOf(spec.unitsPerCarton()), 6, RoundingMode.HALF_UP));
        }
        return volume;
    }

    @Override
    public Map<String, BigDecimal> cartonVolumesM3(Collection<String> skus) {
        Map<String, BigDecimal> volumes = new HashMap<>();
        if (skus == null || skus.isEmpty()) {
            return volumes;
        }
        policies.findCartonSpecs(skus).forEach((sku, spec) -> {
            BigDecimal volume = volumeOf(spec);
            if (volume != null) {
                volumes.put(sku, volume);
            }
        });
        return volumes;
    }

    /** Length x width x height in m³, six decimals; null while a dimension is not declared. */
    private static BigDecimal volumeOf(CartonSpec spec) {
        if (!positive(spec.lengthCm()) || !positive(spec.widthCm()) || !positive(spec.heightCm())) {
            return null;
        }
        return spec.lengthCm().multiply(spec.widthCm()).multiply(spec.heightCm())
                .divide(CUBIC_CM_PER_M3, 6, RoundingMode.HALF_UP);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
