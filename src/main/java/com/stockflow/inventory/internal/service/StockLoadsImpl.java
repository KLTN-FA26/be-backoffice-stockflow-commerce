package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.api.CartonSpec;
import com.stockflow.inventory.api.StockLoads;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The capacity board's measures of stock (kltn-docs 06 §4.6). Two kinds of hold make up what is still to leave: an
 * allocation names its carton, so it is measured from that carton; a SKU hold names only units, so it is rounded up to
 * whole cartons of the SKU's standard count (01 BR-03: one carton spec per SKU) and measured from the spec.
 */
@Service
@Transactional(readOnly = true)
class StockLoadsImpl implements StockLoads {

    private static final BigDecimal CM3_PER_M3 = new BigDecimal(1_000_000);

    private final BinOccupancyQuery space;
    private final InventoryPolicyRepository policies;

    StockLoadsImpl(BinOccupancyQuery space, InventoryPolicyRepository policies) {
        this.space = space;
        this.policies = policies;
    }

    @Override
    public Map<String, Load> loadAt(Collection<String> locationCodes) {
        Map<String, Load> out = new HashMap<>();
        space.loadAt(locationCodes).forEach((code, load) -> out.put(code, of(load)));
        return out;
    }

    @Override
    public Load outboundAt(String warehouseCode) {
        Load allocated = of(space.allocatedAt(warehouseCode));
        List<BinOccupancyQuery.HeldUnits> held = space.heldUnitsAt(warehouseCode);
        Map<String, CartonSpec> specs = policies.findCartonSpecs(held.stream().map(BinOccupancyQuery.HeldUnits::sku)
                .toList());
        Load holds = Load.NONE;
        for (BinOccupancyQuery.HeldUnits h : held) {
            holds = holds.plus(cartonsOf(h.units(), specs.get(h.sku())));
        }
        return allocated.plus(holds);
    }

    /**
     * {@code units} of a SKU as whole cartons of its spec, rounded up. Without a spec (no carton unit) even the number of
     * cartons is unknown: the SKU counts as one unmeasured carton, so the caller sees that something is missing.
     */
    static Load cartonsOf(long units, CartonSpec spec) {
        if (spec == null || spec.unitsPerCarton() <= 0) {
            return new Load(1, BigDecimal.ZERO, BigDecimal.ZERO, 1);
        }
        long cartons = (units + spec.unitsPerCarton() - 1) / spec.unitsPerCarton();
        boolean sized = spec.lengthCm() != null && spec.widthCm() != null && spec.heightCm() != null;
        BigDecimal volume = sized
                ? spec.lengthCm().multiply(spec.widthCm()).multiply(spec.heightCm())
                        .multiply(BigDecimal.valueOf(cartons)).divide(CM3_PER_M3)
                : BigDecimal.ZERO;
        BigDecimal weight = spec.weightKg() == null ? BigDecimal.ZERO
                : spec.weightKg().multiply(BigDecimal.valueOf(cartons));
        return new Load(cartons, volume, weight, sized ? 0 : cartons);
    }

    private static Load of(BinOccupancyQuery.Load load) {
        return new Load(load.cartons(), load.volumeM3(), load.weightKg(), load.unmeasuredCartons());
    }
}
