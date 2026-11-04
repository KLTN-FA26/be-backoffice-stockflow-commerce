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
 * @param kind           only a {@link VehicleKind#CONTAINER} gets the 45-foot corner-post gussets
 * @param doorWidthMm    the door opening's width, or null for no limit (decision LP3-DOOR): a carton enters along the
 *                       length, so the face it shows the door is its placed width x height
 * @param doorHeightMm   the door opening's height, or null for no limit
 * @param availableCount the most vehicles of this type the plan may use; 0 takes the type out of the plan
 * @param gussetMm       the edge of the reinforced ceiling corners to keep clear; 0 when the type has none. Per type
 *                       (decision LP-12), because a pickup's canopy and a container's corner castings differ
 */
public record VehicleSpec(UUID typeId, String code, VehicleKind kind, int innerLengthMm, int innerWidthMm,
                          int innerHeightMm, BigDecimal maxPayloadKg, int availableCount, int gussetMm,
                          Integer doorWidthMm, Integer doorHeightMm) {

    /** The clearance of the earlier solver, for a type that does not say otherwise. */
    public static final int DEFAULT_GUSSET_MM = 150;

    public VehicleSpec {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(kind, "kind");
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
        if (doorWidthMm != null && doorWidthMm <= 0 || doorHeightMm != null && doorHeightMm <= 0) {
            throw new IllegalArgumentException("vehicle type " + code + " has a door size that is not positive");
        }
    }

    /** A vehicle type whose door is no smaller than its cargo box. */
    public VehicleSpec(UUID typeId, String code, VehicleKind kind, int innerLengthMm, int innerWidthMm,
                       int innerHeightMm, BigDecimal maxPayloadKg, int availableCount, int gussetMm) {
        this(typeId, code, kind, innerLengthMm, innerWidthMm, innerHeightMm, maxPayloadKg, availableCount, gussetMm,
                null, null);
    }

    /** Whether a carton showing the door a {@code w x h} face (placed sizes along x and y) passes it. */
    public boolean passesDoor(int w, int h) {
        return (doorWidthMm == null || w <= doorWidthMm) && (doorHeightMm == null || h <= doorHeightMm);
    }

    long innerVolumeMm3() {
        return (long) innerLengthMm * innerWidthMm * innerHeightMm;
    }
}
