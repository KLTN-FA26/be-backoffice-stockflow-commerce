package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.KitDistribution;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanRequest;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanResult;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanner;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * One vehicle of a confirmed plan laid out again for the parcels it keeps (kltn-docs 09 §4.2 step 9: "chuyến Planned mới
 * của xe dùng sơ đồ tính lại cho các kiện còn lại (hệ thống tính lại riêng xe đó)"): the search
 * {@code LoadPlanService.recalculateVehicle} runs for one vehicle - its own type, one type, the same budget - over the
 * kept parcels only.
 *
 * <p>The inputs are read inside the caller's transaction ({@link #inputs}); the search itself ({@link #solve}) is pure
 * computation and is run by the caller with no transaction held (SOLVER-TX, as {@code LoadPlanService} does).</p>
 */
@Component
class VehicleRelayout {

    /** What the search is given, and the parcels' measures the plan needs to store its answer. */
    record Inputs(LoadPlanRequest request, Map<UUID, CargoPackage> cartons) { }

    private final LoadPlanParcels parcels;
    private final Duration budget;
    private final Duration wallLimit;
    private Function<LoadPlanRequest, LoadPlanResult> solver = new LoadPlanner()::plan;

    VehicleRelayout(LoadPlanParcels parcels,
                    @Value("${stockflow.fulfillment.load-planning.solver-budget:PT5S}") Duration budget,
                    @Value("${stockflow.fulfillment.load-planning.solver-wall-limit:PT12S}") Duration wallLimit) {
        this.parcels = parcels;
        this.budget = budget;
        this.wallLimit = wallLimit == null ? LoadPlanRequest.DEFAULT_WALL_LIMIT : wallLimit;
    }

    /** For the tests: the search to run in place of the planner. */
    void solveWith(Function<LoadPlanRequest, LoadPlanResult> search) {
        this.solver = search;
    }

    /** The kept parcels, measured, offered to the vehicle's own type alone. */
    Inputs inputs(LoadPlan.Vehicle vehicle, Collection<UUID> kept) {
        Map<UUID, LoadPlanParcels.Parcel> described = parcels.of(kept);
        LoadPlanParcels.requireMeasured(described.values());
        Map<UUID, CargoPackage> cartons = new LinkedHashMap<>();
        described.forEach((id, p) -> cartons.put(id, p.carton()));
        LoadPlanRequest request = new LoadPlanRequest(List.copyOf(cartons.values()), List.of(vehicle.spec()),
                SelectionMode.SINGLE_TYPE, List.of(), KitDistribution.AUTO, false, null, 0L, budget, wallLimit);
        return new Inputs(request, cartons);
    }

    LoadPlanResult solve(LoadPlanRequest request) {
        return solver.apply(request);
    }
}
