package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A vehicle type as the planner sees it: the inside of its cargo box, what it may carry, how many of it the plan may
 * use (kltn-docs 09 §3 {@code VehicleType}; BR-12) and the clearance its reinforced ceiling corners need (BR-10).
 *
 * <p>Any cargo box: a pickup's tray, a truck's box or a container (decision LP-6). Deliberately not the catalogue
 * entity: the planner needs these numbers and nothing else, so the catalogue can change without touching it.</p>
 *
 * @param availableCount the most vehicles of this type the plan may use; 0 takes the type out of the plan
 * @param gussetMm       the edge of the reinforced ceiling corners to keep clear; 0 when the type has none. Per type
 *                       (decision LP-12), because a pickup's canopy and a container's corner castings differ
 */
public record VehicleSpec(UUID typeId, String code, int innerLengthMm, int innerWidthMm, int innerHeightMm,
                          BigDecimal maxPayloadKg, int availableCount, int gussetMm) {

    /** The clearance of the earlier solver, for a type that does not say otherwise. */
    public static final int DEFAULT_GUSSET_MM = 150;

    public VehicleSpec {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(maxPayloadKg, "maxPayloadKg");
        if (innerLengthMm <= 0 || innerWidthMm <= 0 || innerHeightMm <= 0) {
            throw new IllegalArgumentException("vehicle type " + code + " has an inner size that is not positive");
        }
        if (maxPayloadKg.signum() <= 0) {
            throw new IllegalArgumentException("vehicle type " + code + " has a payload that is not positive");
        }
        if (availableCount < 0) {
            throw new IllegalArgumentException("vehicle type " + code + " has a negative available count");
        }
        if (gussetMm < 0) {
            throw new IllegalArgumentException("vehicle type " + code + " has a negative corner clearance");
        }
    }

    long innerVolumeMm3() {
        return (long) innerLengthMm * innerWidthMm * innerHeightMm;
    }
}
