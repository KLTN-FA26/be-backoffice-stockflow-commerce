package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.KitSpec;
import com.stockflow.inventory.api.PackagingSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The planner's cartons for an estimate, from each order line's resolved packaging (design v2 §2-§3; LP2-4, LP2-5).
 * Pure: the same lines always give the same cartons in the same order, which is half of what makes an estimate
 * repeatable (the planner's fixed seed is the other half).
 *
 * <ul>
 *   <li>A line of {@code c} cartons gives cartons {@code <lineId>#1 .. #c}, {@code sourceRef} the line id, part code and
 *       label the SKU.</li>
 *   <li>The remainder carton (LP2-4) is the line's last one: full size, the resolver's partial weight.</li>
 *   <li>A kit (LP2-5, {@code kitParts} N above 1): every unit is a set of N same-size boxes, parts {@code SKU/1 ..
 *       SKU/N}, sharing the kit group {@code <lineId>/<unit>} so the planner keeps each set in one vehicle; the boxes
 *       are numbered on through the line ({@code <lineId>#1 .. #c*N}). The set is also declared ({@link KitSpec}
 *       {@code SKU -> SKU/1..N}) for the parts the planner has to load loose.</li>
 * </ul>
 */
final class EstimateCartons {

    /**
     * The most boxes one estimate places. The planner runs synchronously in the request (LP2-10); the benchmark went to
     * 800 cartons, and an order beyond a few thousand cartons is not a truckload but a schedule of them.
     */
    static final int MAX_BOXES = 3_000;

    private EstimateCartons() {
    }

    /** One resolved order line. */
    record Line(UUID orderLineId, PackagingSpec spec) { }

    /** The cartons in line order, the declared kits, and each carton's label (its SKU) by ref. */
    record Built(List<CargoPackage> cartons, List<KitSpec> kits, Map<String, String> labels) { }

    static Built build(String groupKey, List<Line> lines) {
        long boxes = 0;
        for (Line line : lines) {
            boxes += line.spec().totalCartons() * Math.max(1, line.spec().kitParts());
        }
        if (boxes > MAX_BOXES) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_TOO_MANY_CARTONS,
                    "The order makes " + boxes + " boxes; an estimate places at most " + MAX_BOXES);
        }
        List<CargoPackage> cartons = new ArrayList<>();
        Map<String, KitSpec> kits = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (Line line : lines) {
            PackagingSpec spec = line.spec();
            if (!spec.resolved()) {
                throw new IllegalArgumentException("line " + line.orderLineId() + " has no packaging");
            }
            String lineId = line.orderLineId().toString();
            String sku = spec.sku();
            PackagingSpec.CartonSpec carton = spec.carton();
            int parts = Math.max(1, spec.kitParts());
            long units = spec.totalCartons();
            int n = 0;
            for (long unit = 1; unit <= units; unit++) {
                boolean partial = unit == units && spec.partialCarton() != null;
                BigDecimal weight = partial ? perBox(spec.partialCarton().weightKg(), parts) : carton.weightKg();
                for (int part = 1; part <= parts; part++) {
                    String ref = lineId + "#" + (++n);
                    cartons.add(new CargoPackage(ref, lineId, carton.lengthMm(), carton.widthMm(), carton.heightMm(),
                            weight, carton.rotatable(), parts > 1 ? sku + "/" + part : sku,
                            parts > 1 ? lineId + "/" + unit : null, groupKey, false));
                    labels.put(ref, sku);
                }
            }
            if (parts > 1) {
                Map<String, Integer> members = new LinkedHashMap<>();
                for (int part = 1; part <= parts; part++) {
                    members.put(sku + "/" + part, 1);
                }
                kits.putIfAbsent(sku, new KitSpec(sku, members));
            }
        }
        return new Built(List.copyOf(cartons), List.copyOf(kits.values()), Map.copyOf(labels));
    }

    /**
     * One box of a partial kit unit. The resolver never splits a kit (its level has a factor of 1), so this only
     * guards the arithmetic: the unit's weight shared by its boxes, never below a gram.
     */
    private static BigDecimal perBox(BigDecimal unitWeight, int parts) {
        BigDecimal each = parts == 1 ? unitWeight : unitWeight.divide(BigDecimal.valueOf(parts), 3, RoundingMode.HALF_UP);
        return each.signum() > 0 ? each : new BigDecimal("0.001");
    }
}
