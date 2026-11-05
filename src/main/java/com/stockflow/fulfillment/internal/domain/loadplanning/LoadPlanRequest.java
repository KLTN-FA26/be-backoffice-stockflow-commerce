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
 * @param budget             how much search the plan gets, in the search's own work units (seconds of work, see
 *                           {@code WorkClock}): deterministic, the same on a slow or a fast machine. The search stops
 *                           when it has nothing left to improve or the budget is spent, and returns the best plan it
 *                           has
 * @param wallLimit          the real time cap (SOLVER-CAP): when it passes, the search stops at once and returns the
 *                           best plan found so far, so a slow machine cannot hang a request. Null: {@link
 *                           #DEFAULT_WALL_LIMIT}
 * @param ignorePayload      trial mode only (LP2-9): the payload of every vehicle is not a cap - a carton may be heavier
 *                           than a type's payload and a vehicle may carry more than it. Everything else (size, door,
 *                           support, counts) holds. False for every real estimate and load plan (BR-10)
 */
public record LoadPlanRequest(List<CargoPackage> cartons, List<VehicleSpec> vehicleTypes, SelectionMode mode,
                              List<KitSpec> kits, KitDistribution distribution, boolean equalizeFill,
                              Integer targetVehicleCount, long seed, Duration budget, Duration wallLimit,
                              boolean ignorePayload) {

    /** A request that honours the payload, which is every request but a trial. */
    public LoadPlanRequest(List<CargoPackage> cartons, List<VehicleSpec> vehicleTypes, SelectionMode mode,
                           List<KitSpec> kits, KitDistribution distribution, boolean equalizeFill,
                           Integer targetVehicleCount, long seed, Duration budget, Duration wallLimit) {
        this(cartons, vehicleTypes, mode, kits, distribution, equalizeFill, targetVehicleCount, seed, budget, wallLimit,
                false);
    }

    /** A trial or real request with the default real time cap. */
    public LoadPlanRequest(List<CargoPackage> cartons, List<VehicleSpec> vehicleTypes, SelectionMode mode,
                           List<KitSpec> kits, KitDistribution distribution, boolean equalizeFill,
                           Integer targetVehicleCount, long seed, Duration budget, boolean ignorePayload) {
        this(cartons, vehicleTypes, mode, kits, distribution, equalizeFill, targetVehicleCount, seed, budget, null,
                ignorePayload);
    }

    /** The default work budget; see {@code stockflow.fulfillment.load-planning.solver-budget}. */
    public static final Duration DEFAULT_BUDGET = Duration.ofSeconds(5);

    /** The default real time cap; see {@code stockflow.fulfillment.load-planning.solver-wall-limit}. */
    public static final Duration DEFAULT_WALL_LIMIT = Duration.ofSeconds(12);

    /** A request with the default real time cap. */
    public LoadPlanRequest(List<CargoPackage> cartons, List<VehicleSpec> vehicleTypes, SelectionMode mode,
                           List<KitSpec> kits, KitDistribution distribution, boolean equalizeFill,
                           Integer targetVehicleCount, long seed, Duration budget) {
        this(cartons, vehicleTypes, mode, kits, distribution, equalizeFill, targetVehicleCount, seed, budget, null,
                false);
    }

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
        wallLimit = wallLimit == null ? DEFAULT_WALL_LIMIT : wallLimit;
        if (wallLimit.isNegative() || wallLimit.isZero()) {
            throw new IllegalArgumentException("the wall limit must be positive");
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
