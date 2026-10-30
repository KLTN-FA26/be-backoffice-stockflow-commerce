package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.loadplanning.KitWarning;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The advisory load estimate (SCRUM-287 PR EST; design v2 §2, §5): how a released order's lines would load on trucks
 * or containers before anything is packed, from the item master data. Advisory only (LP2-1): it never confirms a plan
 * and never books a vehicle - kltn-docs 09 BR-09 lets only Packed parcels into a real load plan.
 *
 * <p>Only what is still to ship is estimated: per line, its quantity less what went out in shipments not returned and
 * the units closed out of the order. Each line's carton comes from the packaging-spec resolver (LP2-2 to LP2-5); a
 * line whose item has no size or weight at any level is blocked on its own (LP2-3): left out of the plan, listed in
 * {@code missing} with its item, the estimate {@code incomplete}. Only when no line resolves is the estimate refused,
 * 422 {@code LOAD_ESTIMATE_PACKAGING_MISSING}.
 * The planner runs synchronously within {@code stockflow.fulfillment.load-planning.solver-budget} (LP2-10) and its
 * answer is mapped to the 3D viewer's {@link Layout}. A new estimate of a source supersedes the current one.</p>
 */
public interface LoadEstimates {

    enum SourceType {
        /** A sales order, released and not yet shipped. */
        ORDER,
        /** A transfer order (kltn-docs 10), from creation until dispatch, at its source warehouse (SCRUM-287 PR T). */
        TRANSFER
    }

    record Source(SourceType type, UUID id) { }

    /**
     * @param sources        what to estimate; one source per estimate for now
     * @param warehouseId    required for a CONFIRMED order (not released yet: the warehouse the release dialog is about
     *                       to send it to); for a released order its own warehouse is used and a different one refused
     * @param vehicleTypeIds the vehicle types the plan may use; empty: every active type
     * @param selectionMode  null: SINGLE_TYPE (docs 09 §3 default "Một loại")
     */
    record CreateEstimate(List<Source> sources, UUID warehouseId, List<UUID> vehicleTypeIds,
                          SelectionMode selectionMode) {

        /** Without a target warehouse: a released order. */
        public CreateEstimate(List<Source> sources, List<UUID> vehicleTypeIds, SelectionMode selectionMode) {
            this(sources, null, vehicleTypeIds, selectionMode);
        }
    }

    /**
     * How one order line is packed, as the resolver decided.
     *
     * @param rule     the rule level used (DEFAULT_SHIPPING_UNIT, LARGEST_PACKING_UNIT, ITEM_BOX, ITEM_UNIT)
     * @param uom      the unit the carton is (PALLET, CTN, the item's unit)
     * @param factor   base units per carton
     * @param quantity the units estimated: what is still to ship (ordered less shipped less closed), in base units
     * @param cartons  cartons of the line, the partial one included; each is {@code kitParts} boxes
     * @param partial  the remainder carton: a full-size carton holding fewer units (LP2-4), or null
     * @param dimsMm   one box: length, width, height in mm
     * @param weightKg one full box
     * @param kitParts boxes per carton: above 1, a kit of same-size boxes loaded together (LP2-5)
     */
    record EstimateLine(UUID orderLineId, String sku, UUID inventoryItemId, int quantity, String rule, String uom,
                        int factor, long cartons, Partial partial, Layout.Dims dimsMm, BigDecimal weightKg,
                        boolean rotatable, int kitParts) { }

    record Partial(int units, BigDecimal weightKg) { }

    /** Something the resolver skipped or noticed on a line (an incomplete packing unit, an unknown unit weight). */
    record EstimateWarning(UUID orderLineId, String sku, String code, String unitOfMeasure, List<String> fields,
                           String message) { }

    /**
     * A line blocked because its item has no size or weight at any level (LP2-3): left out of the plan; the screen
     * links to the inventory item to complete.
     *
     * @param missingFields the item's empty (or zero) fields: lengthCm, widthCm, heightCm, weightKg
     */
    record MissingLine(UUID orderLineId, String sku, UUID inventoryItemId, List<String> missingFields) { }

    /**
     * What the planner reported about its run.
     *
     * @param seed            the fixed seed: the same order, vehicle types and mode estimate the same way unless the
     *                        budget ran out ({@code budgetExhausted})
     * @param kitBalance      per vehicle, part code to how many of its boxes ride in it
     */
    record EstimateMeta(long seed, long budgetMillis, String algorithm, int setsTotal, int minVehicles,
                        boolean budgetExhausted, long elapsedMillis, long bestFoundMillis, int placedBoxes,
                        int unplacedBoxes, List<Map<String, Integer>> kitBalance, List<KitWarning> kitWarnings) { }

    record LoadEstimateView(UUID id, SourceType sourceType, UUID sourceId, UUID warehouseId, String status,
                            SelectionMode selectionMode, List<UUID> vehicleTypeIds, List<EstimateLine> lines,
                            List<MissingLine> missing, boolean incomplete, List<EstimateWarning> warnings,
                            List<Layout> vehicles, List<Layout.UnplacedLine> unplaced, EstimateMeta meta, Instant createdAt, String createdBy) { }

    /**
     * Estimates a source and makes it the current estimate of that source, superseding the previous one.
     *
     * @throws com.stockflow.common.error.BusinessException
     *         {@code NOT_FOUND} (no such order, transfer or warehouse), {@code VALIDATION_FAILED} (a CONFIRMED order without a
     *         warehouse, or a released one with another), {@code LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE} (DRAFT,
     *         PENDING_PAYMENT, ON_HOLD, shipped or later, nothing left to ship - also when the order changed so while it
     *         was planned),
     *         {@code OUT_OF_DATA_SCOPE}, {@code VEHICLE_TYPE_NOT_FOUND}, {@code VEHICLE_TYPE_INACTIVE},
     *         {@code LOAD_ESTIMATE_PACKAGING_MISSING} (no line resolves), {@code LOAD_ESTIMATE_TOO_MANY_CARTONS}
     */
    LoadEstimateView create(CreateEstimate command);

    /**
     * One carton line of a trial estimate (LP2-9): what an EasyCargo sheet row says. The sizes are as measured; the
     * length runs along the vehicle, as the sheet's Length does.
     *
     * @param name      what the box is (the sheet's Description): the viewer's legend and colour key; null or blank:
     *                  the group, else {@code Line <n>}
     * @param group     the sheet's Group, a label only; null or blank: none
     * @param quantity  how many identical boxes
     * @param rotatable false keeps the box as measured (the sheet's Rotable)
     */
    record TrialLine(String name, String group, int lengthMm, int widthMm, int heightMm, int quantity,
                     boolean rotatable) { }

    /** @param vehicleTypeIds the vehicle types the plan may use; empty: every active type */
    record TrialEstimate(List<TrialLine> lines, List<UUID> vehicleTypeIds, SelectionMode selectionMode) { }

    /**
     * A trial's answer: the viewer's contract of an estimate, without the identity and packaging of a stored one
     * ({@code vehicles}, {@code unplaced}, {@code meta}). Weights in it are the placeholder, not data.
     */
    record TrialView(SelectionMode selectionMode, List<UUID> vehicleTypeIds, List<Layout> vehicles,
                     List<Layout.UnplacedLine> unplaced, EstimateMeta meta) { }

    /**
     * The trial estimate (LP2-9; owner decision TRIAL-FLOW 29/10): how a pasted box list loads, with no source and no
     * packaging. The planner runs as for an estimate (same seed, budget and box cap) but the payload is ignored - the
     * list has no weights, as the original tool's - and <b>nothing is persisted</b>: no estimate row, no event.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} (no line, a size or quantity
     *         that is not positive), {@code VEHICLE_TYPE_NOT_FOUND}, {@code VEHICLE_TYPE_INACTIVE},
     *         {@code LOAD_ESTIMATE_TOO_MANY_CARTONS}
     */
    TrialView trial(TrialEstimate command);

    /** @throws com.stockflow.common.error.BusinessException {@code LOAD_ESTIMATE_NOT_FOUND}, {@code OUT_OF_DATA_SCOPE} */
    LoadEstimateView get(UUID id);

    /**
     * The current estimate of a source.
     *
     * @throws com.stockflow.common.error.BusinessException {@code LOAD_ESTIMATE_NOT_FOUND} when it has none,
     *         {@code OUT_OF_DATA_SCOPE}
     */
    LoadEstimateView current(SourceType sourceType, UUID sourceId);
}
