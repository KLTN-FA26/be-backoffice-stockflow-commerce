package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One vehicle of the plan and what goes in it (kltn-docs 09 §3 {@code LoadPlanVehicle}, {@code LoadPlacement}).
 *
 * @param seq             1-based position in the plan
 * @param volumeFillPct   loaded volume over the inner volume, in percent, two decimals
 * @param weightFillPct   loaded weight over the payload, in percent, two decimals
 */
public record PlannedVehicle(UUID vehicleTypeId, String vehicleCode, int seq, List<Placement> placements,
                             long usedVolumeMm3, BigDecimal totalWeightKg, BigDecimal volumeFillPct,
                             BigDecimal weightFillPct) { }
