package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * What to plan: the cartons, the vehicle types the plan may use with their counts, the selection mode, the declared
 * sets, and how long to search.
 *
 * @param distribution       how sets spread over vehicles; {@link KitDistribution#AUTO} tries both
 * @param equalizeFill       even out the fill across vehicles once the count is settled ("chia đều")
 * @param targetVehicleCount the count wanted, or null for the fewest that fit; reported unreachable when exceeded
 * @param seed               shifts every random draw; the same input and seed give the same plan. 0 draws exactly
 *                           what the original Python solver drew
 * @param budget             the real time cap. The search stops when it has nothing left to improve or the budget is
 *                           spent, and returns the best plan it has
 */
public record LoadPlanRequest(List<CargoPackage> cartons, List<VehicleSpec> vehicleTypes, SelectionMode mode,
                              List<KitSpec> kits, KitDistribution distribution, boolean equalizeFill,
                              Integer targetVehicleCount, long seed, Duration budget) {

    /** The default budget; see {@code stockflow.fulfillment.load-planning.solver-budget}. */
    public static final Duration DEFAULT_BUDGET = Duration.ofSeconds(5);

    public LoadPlanRequest {
        cartons = List.copyOf(Objects.requireNonNull(cartons, "cartons"));
        vehicleTypes = List.copyOf(Objects.requireNonNull(vehicleTypes, "vehicleTypes"));
        Objects.requireNonNull(mode, "mode");
        kits = kits == null ? List.of() : List.copyOf(kits);
        distribution = distribution == null ? KitDistribution.AUTO : distribution;
        budget = budget == null ? DEFAULT_BUDGET : budget;
        if (budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("the budget must be positive");
        }
        if (targetVehicleCount != null && targetVehicleCount < 1) {
            throw new IllegalArgumentException("the target vehicle count must be positive");
        }
    }

    public static LoadPlanRequest of(List<CargoPackage> cartons, List<VehicleSpec> vehicleTypes, SelectionMode mode) {
        return new LoadPlanRequest(cartons, vehicleTypes, mode, List.of(), KitDistribution.AUTO, false, null, 0L,
                DEFAULT_BUDGET);
    }
}
