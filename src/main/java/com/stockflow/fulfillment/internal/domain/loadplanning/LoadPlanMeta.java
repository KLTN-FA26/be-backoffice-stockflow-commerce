package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.List;
import java.util.Map;

/**
 * What the solver reports about its own run (the original's {@code meta}).
 *
 * @param minVehicles        the volume floor: fewer vehicles than this cannot hold the cargo even packed solid
 * @param targetUnreachable  a target vehicle count was asked and the plan needs more
 * @param kitBalance         per vehicle, in plan order: part code to how many of its parts ride in it
 * @param budgetExhausted    the real time budget ran out before the search finished; only such a run may differ
 *                           from another run of the same input and seed
 * @param elapsedMillis      wall time of the run
 * @param bestFoundMillis    wall time at which the plan finally returned was found
 */
public record LoadPlanMeta(String algorithm, int setsTotal, int minVehicles, Integer targetVehicles,
                           boolean targetUnreachable, String distribution, List<Map<String, Integer>> kitBalance,
                           List<KitWarning> kitWarnings, String combo, boolean equalized, boolean budgetExhausted,
                           long workTicks, long elapsedMillis, long bestFoundMillis) { }
