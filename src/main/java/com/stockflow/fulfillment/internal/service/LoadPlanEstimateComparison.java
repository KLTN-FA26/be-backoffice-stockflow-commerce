package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
import com.stockflow.fulfillment.internal.entity.LoadEstimateJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageLineJpaEntity;
import com.stockflow.fulfillment.internal.repository.LoadEstimateJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageLineJpaRepository;
import com.stockflow.order.api.OrderSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A load plan against the advisory estimates made before packing (design v2 §1 "comparison with the estimate", §5
 * {@code reconciliation.vsEstimate}; SCRUM-287 PR D on top of PR EST): for each order of the plan its CURRENT estimate
 * (LP2-1: advisory only - nothing is refused on it), compared by vehicle type, by order line (boxes), volume and weight.
 *
 * <p>What is compared on the plan's side: the parcels it holds or left behind at confirmation (the pieces of the piece
 * check) and its active vehicles. An estimate covers what its order still had to ship when it was made.</p>
 */
@Component
class LoadPlanEstimateComparison {

    /** Above the estimate by more than this, in percent, is flagged. */
    static final BigDecimal TOLERANCE_PCT = BigDecimal.TEN;

    private final LoadEstimates estimates;
    private final LoadEstimateJpaRepository estimateRows;
    private final PackageLineJpaRepository packageLines;

    LoadPlanEstimateComparison(LoadEstimates estimates, LoadEstimateJpaRepository estimateRows,
                               PackageLineJpaRepository packageLines) {
        this.estimates = estimates;
        this.estimateRows = estimateRows;
        this.packageLines = packageLines;
    }

    /**
     * @param orders   the plan's orders; {@link LoadPlanParcels.Parcel#order()} ties each parcel to one
     * @param parcels  the plan's pieces (held, or left behind at confirmation)
     * @param vehicles the plan's active vehicles
     */
    LoadPlans.EstimateComparison compare(Collection<OrderSummary> orders,
                                         Collection<LoadPlanParcels.Parcel> parcels,
                                         List<LoadPlan.Vehicle> vehicles) {
        List<UUID> estimateIds = new ArrayList<>();
        List<UUID> without = new ArrayList<>();
        Set<UUID> estimatedOrders = new LinkedHashSet<>();
        Set<String> flags = new LinkedHashSet<>();
        Map<String, Integer> estimatedByType = new TreeMap<>();
        List<LoadPlans.OrderEstimate> perOrder = new ArrayList<>();
        Map<UUID, LoadPlans.LineCount> byLine = new LinkedHashMap<>();
        BigDecimal estimatedVolume = BigDecimal.ZERO;
        BigDecimal estimatedWeight = BigDecimal.ZERO;
        for (OrderSummary order : orders) {
            // Asked first, so a missing estimate is no exception crossing the read's transaction.
            if (estimateRows.findBySourceTypeAndSourceIdAndStatus(LoadEstimates.SourceType.ORDER.name(),
                    order.orderId(), LoadEstimateJpaEntity.CURRENT).isEmpty()) {
                without.add(order.orderId());
                continue;
            }
            LoadEstimates.LoadEstimateView e = estimates.current(LoadEstimates.SourceType.ORDER, order.orderId());
            estimateIds.add(e.id());
            estimatedOrders.add(order.orderId());
            if (e.incomplete()) {
                flags.add("ESTIMATE_INCOMPLETE");
            }
            if (!e.unplaced().isEmpty()) {
                flags.add("ESTIMATE_UNPLACED");
            }
            Map<String, Integer> own = new TreeMap<>();
            for (Layout v : e.vehicles()) {
                own.merge(v.code(), 1, Integer::sum);
                estimatedByType.merge(v.code(), 1, Integer::sum);
            }
            perOrder.add(new LoadPlans.OrderEstimate(order.orderId(), e.id(), own.entrySet().stream()
                    .map(t -> new LoadPlans.VehicleCount(t.getKey(), t.getValue(), 0)).toList()));
            for (LoadEstimates.EstimateLine line : e.lines()) {
                long boxes = line.cartons() * Math.max(1, line.kitParts());
                byLine.merge(line.orderLineId(), new LoadPlans.LineCount(line.orderLineId(), line.sku(), boxes, 0),
                        (a, b) -> new LoadPlans.LineCount(a.orderLineId(), a.sku(), a.estimated() + b.estimated(), 0));
                Layout.Dims d = line.dimsMm();
                if (d != null) {
                    estimatedVolume = estimatedVolume.add(BigDecimal.valueOf((long) d.l() * d.w() * d.h())
                            .multiply(BigDecimal.valueOf(boxes)));
                }
                if (line.weightKg() != null) {
                    BigDecimal weight = line.weightKg().multiply(BigDecimal.valueOf(boxes));
                    if (line.partial() != null && line.partial().weightKg() != null) {
                        // The remainder carton weighs pro rata (LP2-4), not a full carton.
                        weight = weight.subtract(line.weightKg().multiply(BigDecimal.valueOf(Math.max(1,
                                line.kitParts())))).add(line.partial().weightKg());
                    }
                    estimatedWeight = estimatedWeight.add(weight);
                }
            }
        }
        if (!without.isEmpty()) {
            flags.add("NO_ESTIMATE");
        }
        if (estimatedOrders.isEmpty()) {
            return new LoadPlans.EstimateComparison(estimateIds, without, false, List.of(), List.of(), List.of(),
                    null, null, List.copyOf(flags));
        }

        // Vehicles: only a single-order plan compares - each estimate planned its order alone.
        boolean vehiclesCompared = orders.size() == 1;
        List<LoadPlans.VehicleCount> types = new ArrayList<>();
        if (vehiclesCompared) {
            Map<String, int[]> byType = new TreeMap<>();
            estimatedByType.forEach((code, n) -> byType.computeIfAbsent(code, k -> new int[2])[0] += n);
            for (LoadPlan.Vehicle v : vehicles) {
                byType.computeIfAbsent(v.spec().code(), k -> new int[2])[1]++;
            }
            int estimatedVehicles = 0;
            int actualVehicles = 0;
            for (Map.Entry<String, int[]> t : byType.entrySet()) {
                types.add(new LoadPlans.VehicleCount(t.getKey(), t.getValue()[0], t.getValue()[1]));
                estimatedVehicles += t.getValue()[0];
                actualVehicles += t.getValue()[1];
            }
            if (actualVehicles > estimatedVehicles) {
                flags.add("MORE_VEHICLES");
            } else if (actualVehicles < estimatedVehicles) {
                flags.add("FEWER_VEHICLES");
            }
        }

        // Parcels, volume, weight, lines: only the parcels of the orders that have an estimate.
        List<LoadPlanParcels.Parcel> compared = parcels.stream()
                .filter(p -> p.order() != null && estimatedOrders.contains(p.order().orderId())).toList();
        Map<UUID, Set<UUID>> linesOfParcel = new HashMap<>();
        for (PackageLineJpaEntity l : packageLines.findByPackageIdIn(compared.stream()
                .map(LoadPlanParcels.Parcel::packageId).toList())) {
            linesOfParcel.computeIfAbsent(l.getPackageId(), k -> new LinkedHashSet<>()).add(l.getOrderLineId());
        }
        Map<UUID, Integer> actualByLine = new HashMap<>();
        BigDecimal actualVolume = BigDecimal.ZERO;
        BigDecimal actualWeight = BigDecimal.ZERO;
        for (LoadPlanParcels.Parcel p : compared) {
            Set<UUID> lines = new LinkedHashSet<>(linesOfParcel.getOrDefault(p.packageId(), Set.of()));
            if (p.row().getKit() != null) {
                lines.add(p.row().getKit().orderLineId());
            }
            lines.forEach(line -> actualByLine.merge(line, 1, Integer::sum));
            CargoPackage c = p.carton();
            if (c != null) {
                actualVolume = actualVolume.add(BigDecimal.valueOf((long) c.lengthMm() * c.widthMm() * c.heightMm()));
                actualWeight = actualWeight.add(c.weightKg());
            }
        }
        List<LoadPlans.LineCount> lines = new ArrayList<>();
        long estimatedBoxes = 0;
        for (LoadPlans.LineCount l : byLine.values()) {
            lines.add(new LoadPlans.LineCount(l.orderLineId(), l.sku(), l.estimated(),
                    actualByLine.getOrDefault(l.orderLineId(), 0)));
            estimatedBoxes += l.estimated();
        }
        if (compared.size() > estimatedBoxes) {
            flags.add("MORE_PARCELS");
        } else if (compared.size() < estimatedBoxes) {
            flags.add("FEWER_PARCELS");
        }
        LoadPlans.Measure volume = measure(estimatedVolume, actualVolume);
        LoadPlans.Measure weight = measure(estimatedWeight, actualWeight);
        if (above(volume)) {
            flags.add("VOLUME_ABOVE");
        }
        if (above(weight)) {
            flags.add("WEIGHT_ABOVE");
        }
        return new LoadPlans.EstimateComparison(estimateIds, without, vehiclesCompared, types, perOrder, lines, volume,
                weight, List.copyOf(flags));
    }

    private static LoadPlans.Measure measure(BigDecimal estimated, BigDecimal actual) {
        BigDecimal delta = estimated.signum() == 0 ? null : actual.subtract(estimated).multiply(BigDecimal.valueOf(100))
                .divide(estimated, 2, RoundingMode.HALF_UP);
        return new LoadPlans.Measure(estimated, actual, delta);
    }

    private static boolean above(LoadPlans.Measure m) {
        return m.deltaPct() != null && m.deltaPct().compareTo(TOLERANCE_PCT) > 0;
    }
}
