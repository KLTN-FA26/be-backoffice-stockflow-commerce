package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Load plans of full-truckload shipping (SCRUM-287 PR D; kltn-docs 09 §4.2, §5, BR-09..BR-15). Every call is limited to
 * the caller's warehouses. Every change names the plan version it was decided on ({@code expectedVersion}); another
 * version is 409 {@code OPTIMISTIC_LOCK}.
 */
public interface LoadPlans {

    // ---- views -----------------------------------------------------------------------------------------------------

    /**
     * A parcel as planning shows it: sizes in whole mm (measured cm rounded up), its place ({@code vehicleSeq}) or why
     * it has none ({@code unplacedReason}). {@code partName} is its SKU ({@code SKU n/N} for a kit part).
     */
    record ParcelView(UUID packageId, String labelCode, UUID shipmentId, String shipmentCode, String partName,
                      int lengthMm, int widthMm, int heightMm, BigDecimal weightKg, boolean rotatable,
                      UUID kitGroupId, Integer kitPartNo, Integer kitPartCount, Integer vehicleSeq,
                      String unplacedReason, UUID transferOrderId) { }

    /**
     * A full-truckload shipment waiting for dispatch with the parcels no live plan holds (LP-5: chosen whole).
     * {@code packagesInOtherPlans} counts its parcels another plan holds (only after a plan left some behind).
     *
     * <p>With {@code sourceType} TRANSFER (SCRUM-287 PR T) it is a packed transfer order instead, whole:
     * {@code shipmentId}, {@code orderId} and {@code orderNumber} are null, {@code code} is the transfer's number,
     * {@code readyAt} when its packing completed, {@code transferOrderId} and {@code toWarehouseId} name it.</p>
     */
    record CandidateShipment(UUID shipmentId, String code, UUID orderId, String orderNumber,
                             boolean allowPartialShipment, Instant readyAt, int packageCount, int packagesInOtherPlans,
                             BigDecimal weightKg, long volumeMm3, List<ParcelView> packages, String sourceType,
                             UUID transferOrderId, UUID toWarehouseId) { }

    /** {@code shipmentCount}: the shipments of an ORDER plan; 1 for a TRANSFER plan's transfer order. */
    record PlanRow(UUID loadPlanId, String code, UUID warehouseId, String status, String selectionMode,
                   int shipmentCount, int packageCount, int vehicleCount, int unplacedCount, Instant createdAt,
                   Instant calculatedAt, Instant confirmedAt, long version, String sourceType) { }

    record VehicleTypeChoice(UUID vehicleTypeId, String code, String name, String kind, int availableCount,
                             String status) { }

    record ShipmentInPlan(UUID shipmentId, String code, UUID orderId, String orderNumber,
                          boolean allowPartialShipment, int packageCount) { }

    /** The transfer order of a TRANSFER plan (SCRUM-287 PR T): its number, warehouses and status (10 §5). */
    record TransferInPlan(UUID transferOrderId, String number, UUID fromWarehouseId, UUID toWarehouseId,
                          String status, int packageCount) { }

    /** The dispatch load a confirmed vehicle became (PLANNED until PR E books it). */
    record LoadRef(UUID loadId, String code, String status) { }

    /** {@code needsRecalculation}: parcels were taken out of it (an order cancelled); recalculate before confirming. */
    record VehicleSummary(int seq, UUID loadPlanVehicleId, UUID vehicleTypeId, String code, int packageCount,
                          long usedVolumeMm3, BigDecimal usedWeightKg, BigDecimal volumeFillPct,
                          BigDecimal weightFillPct, LoadRef load, boolean needsRecalculation) { }

    record Acknowledgement(Instant at, UUID by, String note) { }

    record PlanDetail(UUID loadPlanId, String code, UUID warehouseId, String sourceType, String selectionMode,
                      String status, String note, List<VehicleTypeChoice> vehicleTypes,
                      List<ShipmentInPlan> shipments, List<VehicleSummary> vehicles, List<ParcelView> packages,
                      int packageCount, int placedCount, int unplacedCount, Acknowledgement unplacedAcknowledgement,
                      Instant calculatedAt, Map<String, Object> solverMeta, Instant confirmedAt, Instant reopenedAt,
                      String reopenReason, Instant dispatchedAt, Instant cancelledAt, String cancelReason,
                      Instant createdAt, long version, TransferInPlan transferOrder, String cancelReasonCode,
                      List<PendingUnload> pendingUnloads) { }

    /**
     * A parcel still to be scanned off a vehicle of this plan that was called off after loading started (SCRUM-474
     * UNLOAD-DESIGN points 4-5): locked until then. {@code loadId} is where to scan it off ({@code POST
     * /loads/{loadId}/unloads}); {@code shipmentCode} is the shipment's code, or the transfer order's number
     * (then {@code transferOrderId} is set and {@code shipmentId} null).
     */
    record PendingUnload(UUID loadId, String loadCode, Integer vehicleSeq, UUID packageId, String labelCode,
                         UUID shipmentId, UUID transferOrderId, String shipmentCode, String reasonCode, String reason,
                         Instant requestedAt) { }

    // ---- reconciliation (design v2 §1 "nesting-reconcile": pieces in = placed + unplaced) ---------------------------

    /**
     * @param packagesIn  parcels the plan holds, plus those it left behind at confirmation
     * @param leftBehind  of the unplaced, those released at confirmation (back at the dispatch area)
     * @param notInPlan   parcels of the shipment or order (sealed, not voided) the plan does not hold: in another
     *                    plan, or left behind by an earlier one
     */
    record PieceCount(String key, String code, int packagesIn, int placed, int unplaced, int leftBehind,
                      int notInPlan, boolean balanced) { }

    /** {@code vsEstimate}: the comparison with the orders' current advisory estimates (design v2 §5). */
    record Reconciliation(PieceCount pieceCheck, List<PieceCount> shipments, List<PieceCount> orders,
                          EstimateComparison vsEstimate) { }

    /** Estimated against actual, for one vehicle type; {@code code} is the type's code. */
    record VehicleCount(String code, int estimated, int actual) { }

    /**
     * Boxes of one order line: estimated ({@code cartons x kitParts} of the estimate line) against the parcels of the
     * plan holding units of it (a parcel holding several lines counts for each; a kit part counts for its kit's line).
     */
    record LineCount(UUID orderLineId, String sku, long estimated, int actual) { }

    /** {@code deltaPct}: (actual - estimated) / estimated x 100, two decimals; null when nothing was estimated. */
    record Measure(BigDecimal estimated, BigDecimal actual, BigDecimal deltaPct) { }

    /** One order's current estimate and the vehicles it planned on its own: information only in a multi-order plan. */
    record OrderEstimate(UUID orderId, UUID estimateId, List<VehicleCount> vehiclesByType) { }

    /**
     * The plan against the CURRENT estimate of each of its orders (design v2 §5 {@code vsEstimate}).
     *
     * <p>Only what can be compared is: parcels, volume, weight and boxes by line count the plan's parcels of the orders
     * that HAVE a current estimate; orders without one are listed in {@code notEstimated} and left out. Vehicle counts
     * are compared only in a single-order plan - each estimate planned its order alone, so in a multi-order plan the
     * sum of the estimates' vehicles says nothing about the shared trucks; there {@code vehiclesByType} is empty and
     * {@code orders} reports each estimate's vehicles for information.</p>
     *
     * @param estimateIds     the current estimates compared
     * @param notEstimated    orders of the plan with no current estimate (flag {@code NO_ESTIMATE}); not compared
     * @param vehiclesCompared whether vehicle counts were compared (a single-order plan with its estimate)
     * @param vehiclesByType  estimated against actual per vehicle type; empty unless {@code vehiclesCompared}
     * @param orders          each estimated order's own estimate and vehicles
     * @param volumeMm3       estimated box volume against the volume of the estimated orders' parcels
     * @param weightKg        estimated weight against the weight of the estimated orders' parcels
     * @param flags           {@code NO_ESTIMATE}, {@code ESTIMATE_INCOMPLETE} (an estimate left lines out for missing
     *                        sizes, LP2-3), {@code ESTIMATE_UNPLACED} (the estimate itself left boxes out),
     *                        {@code MORE_VEHICLES}, {@code FEWER_VEHICLES} (single-order plans only),
     *                        {@code MORE_PARCELS}, {@code FEWER_PARCELS}, {@code VOLUME_ABOVE}, {@code WEIGHT_ABOVE}
     *                        (more than 10 % above the estimate)
     */
    record EstimateComparison(List<UUID> estimateIds, List<UUID> notEstimated, boolean vehiclesCompared,
                              List<VehicleCount> vehiclesByType, List<OrderEstimate> orders,
                              List<LineCount> cartonsByLine, Measure volumeMm3, Measure weightKg,
                              List<String> flags) { }

    // ---- commands --------------------------------------------------------------------------------------------------

    /**
     * @param sourceType      ORDER (default: {@code shipmentIds}) or TRANSFER ({@code transferOrderIds}: one packed
     *                        transfer, 09 §4.2 step 1); a plan never holds both (LOAD_PLAN_SOURCE_MISMATCH)
     */
    record CreatePlan(UUID requestId, UUID warehouseId, List<UUID> shipmentIds, List<UUID> vehicleTypeIds,
                      String selectionMode, String note, String sourceType, List<UUID> transferOrderIds) {

        public CreatePlan(UUID requestId, UUID warehouseId, List<UUID> shipmentIds, List<UUID> vehicleTypeIds,
                          String selectionMode, String note) {
            this(requestId, warehouseId, shipmentIds, vehicleTypeIds, selectionMode, note, null, null);
        }
    }

    /** {@code transferOrderIds} for a TRANSFER plan (its one transfer), {@code shipmentIds} for an ORDER plan. */
    record Inputs(Long expectedVersion, List<UUID> shipmentIds, List<UUID> vehicleTypeIds, String selectionMode,
                  List<UUID> transferOrderIds) {

        public Inputs(Long expectedVersion, List<UUID> shipmentIds, List<UUID> vehicleTypeIds, String selectionMode) {
            this(expectedVersion, shipmentIds, vehicleTypeIds, selectionMode, null);
        }
    }

    /**
     * A parcel's place in a manual edit: corner and placed sizes; {@code orientation} optional - the sizes decide, and
     * an orientation that does not give them is {@code ROTATION_NOT_ALLOWED}.
     */
    record BoxPosition(UUID ref, int xMm, int yMm, int zMm, int wMm, int hMm, int dMm, String orientation) { }

    record VehicleBoxes(int seq, List<BoxPosition> boxes) { }

    record PlacementEdit(Long expectedVersion, List<VehicleBoxes> vehicles, List<UUID> tray) { }

    // ---- operations ------------------------------------------------------------------------------------------------

    /** Full-truckload shipments of the warehouse waiting for dispatch, with the parcels free to plan. */
    default List<CandidateShipment> candidates(UUID warehouseId, UUID loadPlanId) {
        return candidates(warehouseId, loadPlanId, null);
    }

    /**
     * As {@link #candidates(UUID, UUID)} for {@code sourceType} ORDER (the default); TRANSFER: the packed full-truckload
     * transfer orders leaving from the warehouse, whole, that no other live plan holds (SCRUM-287 PR T).
     */
    List<CandidateShipment> candidates(UUID warehouseId, UUID loadPlanId, String sourceType);

    /** A new DRAFT plan, idempotent by {@code requestId}; shipments and vehicle types may be given at once. */
    PlanDetail create(CreatePlan request, CurrentUser actor);

    PageResponse<PlanRow> list(UUID warehouseId, String status, String search, int page, int size);

    PlanDetail get(UUID loadPlanId);

    /** The shipments (whole), vehicle types and mode; from PLANNED back to DRAFT (09 §5). */
    PlanDetail setInputs(UUID loadPlanId, Inputs inputs, CurrentUser actor);

    /** Runs the planner over the whole plan and stores the result: PLANNED. */
    PlanDetail calculate(UUID loadPlanId, Long expectedVersion, CurrentUser actor);

    /** The vehicle as the 3D viewer's {@link Layout} (FE #71), built by {@code Layouts.of} as the estimate's are. */
    Layout layout(UUID loadPlanId, int seq);

    List<Layout> layouts(UUID loadPlanId);

    /** A manual edit of the whole plan, all or nothing (BR-11): 422 PLACEMENT_RULE_VIOLATION. */
    PlanDetail editPlacements(UUID loadPlanId, PlacementEdit edit, CurrentUser actor);

    /** Re-solves one vehicle with its parcels and, optionally, parcels taken from the tray. */
    PlanDetail recalculateVehicle(UUID loadPlanId, int seq, Long expectedVersion, List<UUID> addPackageIds,
                                  CurrentUser actor);

    /** BR-13; LP-4: 409 ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED when an order that may not ship in parts would. */
    PlanDetail acknowledgeUnplaced(UUID loadPlanId, Long expectedVersion, String note, CurrentUser actor);

    /** CONFIRMED; one PLANNED dispatch load per vehicle. BR-13, LP-4. */
    PlanDetail confirm(UUID loadPlanId, Long expectedVersion, CurrentUser actor);

    /** CONFIRMED back to PLANNED while no vehicle loads (BR-14); its dispatch loads are cancelled (LP-11). */
    PlanDetail reopen(UUID loadPlanId, Long expectedVersion, String reason, CurrentUser actor);

    /** CANCELLED while no vehicle loads (BR-14); its parcels and dispatch loads are released. */
    PlanDetail cancel(UUID loadPlanId, Long expectedVersion, String reason, CurrentUser actor);

    /**
     * SCRUM-474 UNLOAD-DESIGN (09 BR-14): the whole plan called off after loading started, under control - CANCELLED at
     * once with a reason code and a note; the vehicles being loaded are cancelled with their scanned parcels locked until
     * each is scanned off ({@code POST /loads/{id}/unloads}) or confirmed off by a warehouse manager.
     *
     * @throws com.stockflow.common.error.BusinessException {@code LOAD_PLAN_VEHICLE_DISPATCHED} when a vehicle already
     *         left (cancel the others one by one); {@code INVALID_LOAD_PLAN_TRANSITION} when not CONFIRMED;
     *         {@code OPTIMISTIC_LOCK}; {@code VALIDATION_FAILED}
     */
    PlanDetail cancelControlled(UUID loadPlanId, Long expectedVersion,
                                com.stockflow.fulfillment.internal.domain.UnloadReasonCode code, String note,
                                CurrentUser actor);

    Reconciliation reconciliation(UUID loadPlanId);
}
