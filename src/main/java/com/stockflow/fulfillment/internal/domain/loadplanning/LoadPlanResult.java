package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.List;

/**
 * The planner's answer (kltn-docs 09 §3 "Kế hoạch chuyến xe"): the vehicles in order with their cartons, and the
 * cartons left out with the reason. Every carton of the request is in exactly one of the two.
 */
public record LoadPlanResult(List<PlannedVehicle> vehicles, List<UnplacedPackage> unplaced, LoadPlanMeta meta) {

    public int placedCount() {
        int n = 0;
        for (PlannedVehicle v : vehicles) {
            n += v.placements().size();
        }
        return n;
    }
}
