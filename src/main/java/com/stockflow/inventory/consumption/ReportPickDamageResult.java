package com.stockflow.inventory.consumption;

import java.util.List;
import java.util.UUID;

/**
 * What a damage report at picking did (SCRUM-277). The damaged units are in quarantine as DAMAGED either way; for the
 * order, one of two things happened to them:
 * <ul>
 *   <li>{@code replacements} not empty: other stock of the warehouse took their place (FEFO) - the order keeps its
 *       quantity, on new allocations the picker goes on to pick;</li>
 *   <li>{@code impairedReservationId} set: nothing could replace them, so the order keeps them allocated on the
 *       damaged units, where nobody can pick them, until the coordinator reallocates that share (X-SHORT).</li>
 * </ul>
 *
 * @param retainedQuantity units the allocation still keeps at the bin, good ones to pick (0 when all were damaged:
 *                         the allocation is then closed)
 * @param holdsAfter       the holds that now stand for the allocation on the order line, in this order: the
 *                         allocation itself while it keeps units, then the replacements or the impaired share
 * @param alreadyReported  true when this request had reported the damage before; nothing changed this time
 * @param pickLineId       the pick line the report named
 */
public record ReportPickDamageResult(UUID damageReportId, String reportNumber, String damageLocationCode, int quantity,
                                     UUID reservationId, int retainedQuantity, UUID impairedReservationId,
                                     List<Allocation> replacements, List<UUID> holdsAfter, boolean alreadyReported,
                                     UUID pickLineId) {

    public ReportPickDamageResult {
        replacements = List.copyOf(replacements);
        holdsAfter = List.copyOf(holdsAfter);
    }

    /** Without the pick line (answers built before it was carried). */
    public ReportPickDamageResult(UUID damageReportId, String reportNumber, String damageLocationCode, int quantity,
                                  UUID reservationId, int retainedQuantity, UUID impairedReservationId,
                                  List<Allocation> replacements, List<UUID> holdsAfter, boolean alreadyReported) {
        this(damageReportId, reportNumber, damageLocationCode, quantity, reservationId, retainedQuantity,
                impairedReservationId, replacements, holdsAfter, alreadyReported, null);
    }

    /** A new allocation of the order replacing damaged units: where to pick it and how many. */
    public record Allocation(UUID reservationId, String locationCode, String lotNumber, int quantity) {
    }
}
