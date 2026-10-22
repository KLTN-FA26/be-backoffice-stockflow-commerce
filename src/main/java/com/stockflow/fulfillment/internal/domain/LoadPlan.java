package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanResult;
import com.stockflow.fulfillment.internal.domain.loadplanning.Orientation;
import com.stockflow.fulfillment.internal.domain.loadplanning.Placement;
import com.stockflow.fulfillment.internal.domain.loadplanning.PlacementValidator;
import com.stockflow.fulfillment.internal.domain.loadplanning.PlacementViolation;
import com.stockflow.fulfillment.internal.domain.loadplanning.PlannedVehicle;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import com.stockflow.fulfillment.internal.domain.loadplanning.UnplacedPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec;
import com.stockflow.fulfillment.internal.domain.loadplanning.ViolationType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>A load plan</b> of full-truckload shipping (kltn-docs 09 §4.2, §5, BR-09..BR-15; SCRUM-287 PR D): which parcels go,
 * on which vehicles, and where each one sits. The dispatcher chooses whole shipments, the vehicle types and the
 * selection mode (DRAFT); the planner places the parcels (PLANNED); the dispatcher checks the 3D model, moves parcels by
 * hand (BR-11: every move passes {@link PlacementValidator}) and recalculates single vehicles; parcels left unplaced are
 * acknowledged (BR-13) and the plan is confirmed - the parcel list and the layout are locked (BR-14), and each vehicle
 * becomes a dispatch load to book and load (PR E). A confirmed plan reopens to PLANNED while no vehicle is loading.
 *
 * <p>A vehicle whose parcels were taken out from under the dispatcher (an order cancelled out of the plan) is
 * flagged {@code needsRecalculation} - the docs' "bỏ kiện &amp; tính lại": what rested on a removed parcel may now
 * float. The plan is not confirmed while a vehicle is flagged; recalculating it, recalculating the plan or a manual
 * edit (which is checked whole) clears the flag. Confirmation also re-checks every vehicle against the loading rules
 * as a last gate.</p>
 *
 * <p>The plan holds its parcels (BR-09: at most one live plan per parcel - the database's partial unique index is the
 * net). It releases them when they leave its inputs, when it is cancelled, when its order is cancelled, and - the
 * unplaced ones - when it is confirmed: they go back to the dispatch area for a later trip.</p>
 *
 * <p>Pure Java: no Spring, no JPA. The parcels' sizes are passed in as {@link CargoPackage}s keyed by package id
 * ({@code ref} is the package id); what the plan must know of vehicles being loaded is passed in as a flag.</p>
 */
public final class LoadPlan {

    /**
     * A parcel the plan holds or held. {@code unplacedReason} is null when it has a place or before a calculation.
     * {@code shipmentId} is what the parcel leaves with: its shipment in an ORDER plan, its transfer order in a TRANSFER
     * plan (SCRUM-287 PR T - a parcel is of a shipment or of a transfer, and a plan holds one kind).
     */
    public record Member(UUID packageId, UUID shipmentId, boolean active, LoadPlanUnplacedReason unplacedReason,
                         Instant addedAt, UUID addedBy, Instant releasedAt, LoadPlanReleaseReason releaseReason) {

        Member released(LoadPlanReleaseReason reason, Instant at) {
            return new Member(packageId, shipmentId, false, unplacedReason, addedAt, addedBy, at, reason);
        }

        Member withUnplaced(LoadPlanUnplacedReason reason) {
            return new Member(packageId, shipmentId, active, reason, addedAt, addedBy, releasedAt, releaseReason);
        }
    }

    /** Where a parcel sits: millimetres, {@code w/h/d} the placed sizes along {@code x/y/z}. */
    public record Placed(UUID packageId, int x, int y, int z, int w, int h, int d, Orientation orientation) { }

    /**
     * One vehicle of the plan, with its type as it was when calculated ({@code spec}). {@code active} false once a
     * calculation replaced it; only active vehicles are part of the plan.
     */
    public record Vehicle(UUID id, int seq, boolean active, VehicleSpec spec, List<Placed> placements,
                          long usedVolumeMm3, BigDecimal usedWeightKg, BigDecimal volumeFillPct,
                          BigDecimal weightFillPct, boolean needsRecalculation) {
        public Vehicle {
            placements = List.copyOf(placements);
        }

        Vehicle retired() {
            return new Vehicle(id, seq, false, spec, placements, usedVolumeMm3, usedWeightKg, volumeFillPct,
                    weightFillPct, needsRecalculation);
        }
    }

    /** One manual edit of a vehicle (BR-11): the vehicle's seq and what is to be in it. */
    public record VehicleEdit(int seq, List<Placed> boxes) { }

    public record Snapshot(UUID id, String code, UUID requestId, UUID warehouseId, String sourceType,
                           SelectionMode mode, LoadPlanStatus status, String note, Instant calculatedAt,
                           UUID calculatedBy, Instant acknowledgedAt, UUID acknowledgedBy, String acknowledgeNote,
                           Instant confirmedAt, UUID confirmedBy, Instant reopenedAt, UUID reopenedBy,
                           String reopenReason, Instant dispatchedAt, Instant cancelledAt, UUID cancelledBy,
                           String cancelReason, long version, List<UUID> vehicleTypeIds, List<Member> members,
                           List<Vehicle> vehicles) { }

    public static final String SOURCE_ORDER = "ORDER";
    /** 09 §3 LoadPlan.source_type "TO", §4.2 step 1: the parcels of one transfer order (SCRUM-287 PR T, LP-9). */
    public static final String SOURCE_TRANSFER = "TRANSFER";

    private final UUID id;
    private final String code;
    private final UUID requestId;
    private final UUID warehouseId;
    private final String sourceType;
    private SelectionMode mode;
    private LoadPlanStatus status;
    private final String note;
    private Instant calculatedAt;
    private UUID calculatedBy;
    private Instant acknowledgedAt;
    private UUID acknowledgedBy;
    private String acknowledgeNote;
    private Instant confirmedAt;
    private UUID confirmedBy;
    private Instant reopenedAt;
    private UUID reopenedBy;
    private String reopenReason;
    private Instant dispatchedAt;
    private Instant cancelledAt;
    private UUID cancelledBy;
    private String cancelReason;
    private final long version;
    private final List<UUID> vehicleTypeIds = new ArrayList<>();
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private final List<Vehicle> vehicles = new ArrayList<>();
    /** Whether anything changed since it was read: the store writes only then. */
    private boolean changed;

    private LoadPlan(Snapshot s) {
        this.id = Objects.requireNonNull(s.id(), "id");
        this.code = Objects.requireNonNull(s.code(), "code");
        this.requestId = Objects.requireNonNull(s.requestId(), "requestId");
        this.warehouseId = Objects.requireNonNull(s.warehouseId(), "warehouseId");
        this.sourceType = s.sourceType() == null ? SOURCE_ORDER : s.sourceType();
        this.mode = Objects.requireNonNull(s.mode(), "mode");
        this.status = Objects.requireNonNull(s.status(), "status");
        this.note = s.note();
        this.calculatedAt = s.calculatedAt();
        this.calculatedBy = s.calculatedBy();
        this.acknowledgedAt = s.acknowledgedAt();
        this.acknowledgedBy = s.acknowledgedBy();
        this.acknowledgeNote = s.acknowledgeNote();
        this.confirmedAt = s.confirmedAt();
        this.confirmedBy = s.confirmedBy();
        this.reopenedAt = s.reopenedAt();
        this.reopenedBy = s.reopenedBy();
        this.reopenReason = s.reopenReason();
        this.dispatchedAt = s.dispatchedAt();
        this.cancelledAt = s.cancelledAt();
        this.cancelledBy = s.cancelledBy();
        this.cancelReason = s.cancelReason();
        this.version = s.version();
        if (s.vehicleTypeIds() != null) {
            vehicleTypeIds.addAll(s.vehicleTypeIds());
        }
        if (s.members() != null) {
            s.members().forEach(m -> members.put(m.packageId(), m));
        }
        if (s.vehicles() != null) {
            vehicles.addAll(s.vehicles());
        }
    }

    /** A new plan of customer orders (09 §4.2 step 1): DRAFT, nothing chosen yet. */
    public static LoadPlan draft(UUID id, String code, UUID requestId, UUID warehouseId, SelectionMode mode,
                                 String note) {
        return draft(id, code, requestId, warehouseId, mode, note, SOURCE_ORDER);
    }

    /** A new plan of {@code sourceType} ({@link #SOURCE_ORDER} or {@link #SOURCE_TRANSFER}): DRAFT, nothing chosen. */
    public static LoadPlan draft(UUID id, String code, UUID requestId, UUID warehouseId, SelectionMode mode,
                                 String note, String sourceType) {
        if (!SOURCE_ORDER.equals(sourceType) && !SOURCE_TRANSFER.equals(sourceType)) {
            throw new IllegalArgumentException("source type " + sourceType);
        }
        LoadPlan plan = new LoadPlan(new Snapshot(id, code, requestId, warehouseId, sourceType,
                mode == null ? SelectionMode.SINGLE_TYPE : mode, LoadPlanStatus.DRAFT, trimmed(note), null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, 0, List.of(), List.of(), List.of()));
        plan.changed = true;
        return plan;
    }

    public static LoadPlan restore(Snapshot snapshot) {
        return new LoadPlan(snapshot);
    }

    // ---- inputs (09 §4.2 step 1, 3) --------------------------------------------------------------------------------

    /**
     * Sets what the plan is calculated from: the parcels (package id to its shipment; LP-5: the service passes whole
     * shipments), the vehicle types it may use and the selection mode (LP-6). From PLANNED this goes back to DRAFT
     * (09 §5 "đổi kiện / loại xe / chế độ"): the vehicles are dropped and must be calculated again. The same inputs
     * again change nothing.
     *
     * @return the parcels newly held by this plan (to be claimed under BR-09)
     */
    public Set<UUID> setInputs(Map<UUID, UUID> parcels, List<UUID> types, SelectionMode newMode, UUID by,
                               Instant at) {
        requireStatus("change its inputs", LoadPlanStatus.DRAFT, LoadPlanStatus.PLANNED);
        Objects.requireNonNull(parcels, "parcels");
        List<UUID> newTypes = types == null ? List.of() : List.copyOf(new LinkedHashSet<>(types));
        SelectionMode wanted = newMode == null ? mode : newMode;
        Set<UUID> held = activePackageIds();
        boolean same = held.equals(parcels.keySet()) && new HashSet<>(vehicleTypeIds).equals(new HashSet<>(newTypes))
                && wanted == mode;
        if (same) {
            return Set.of();
        }
        Set<UUID> claimed = new LinkedHashSet<>();
        for (Member m : List.copyOf(members.values())) {
            if (m.active() && !parcels.containsKey(m.packageId())) {
                members.put(m.packageId(), m.released(LoadPlanReleaseReason.INPUTS_CHANGED, at));
            }
        }
        parcels.forEach((packageId, shipmentId) -> {
            Member m = members.get(packageId);
            if (m == null || !m.active()) {
                members.put(packageId, new Member(packageId, shipmentId, true, null, at, by, null, null));
                claimed.add(packageId);
            }
        });
        vehicleTypeIds.clear();
        vehicleTypeIds.addAll(newTypes);
        mode = wanted;
        backToDraft();
        changed = true;
        return claimed;
    }

    private void backToDraft() {
        vehicles.replaceAll(v -> v.active() ? v.retired() : v);
        members.replaceAll((k, m) -> m.active() ? m.withUnplaced(null) : m);
        clearAcknowledgement();
        status = LoadPlanStatus.DRAFT;
    }

    // ---- calculation (09 §4.2 step 2) ------------------------------------------------------------------------------

    /**
     * Stores the planner's answer for the whole plan: the vehicles replace the earlier ones, every held parcel is
     * placed or has its reason. PLANNED.
     *
     * @param specs the vehicle types as the planner saw them, by type id (copied onto each vehicle)
     * @param ids   new vehicle ids
     */
    public void applyCalculation(LoadPlanResult result, Map<UUID, VehicleSpec> specs, Supplier<UUID> ids, UUID by,
                                 Instant at, Map<UUID, CargoPackage> cartons) {
        requireStatus("be calculated", LoadPlanStatus.DRAFT, LoadPlanStatus.PLANNED);
        requireSomethingToPlan();
        vehicles.replaceAll(v -> v.active() ? v.retired() : v);
        for (PlannedVehicle pv : result.vehicles()) {
            VehicleSpec spec = Objects.requireNonNull(specs.get(pv.vehicleTypeId()), "spec of " + pv.vehicleCode());
            vehicles.add(withUsage(ids.get(), pv.seq(), spec, placedOf(pv.placements()), cartons));
        }
        Map<UUID, LoadPlanUnplacedReason> reasons = new HashMap<>();
        for (UnplacedPackage u : result.unplaced()) {
            reasons.put(UUID.fromString(u.ref()), LoadPlanUnplacedReason.of(u.reason()));
        }
        Set<UUID> placed = placedPackageIds();
        for (Member m : List.copyOf(members.values())) {
            if (!m.active()) {
                continue;
            }
            LoadPlanUnplacedReason reason = placed.contains(m.packageId()) ? null : reasons.get(m.packageId());
            if (reason == null && !placed.contains(m.packageId())) {
                throw new IllegalStateException("the planner neither placed nor reported package " + m.packageId());
            }
            members.put(m.packageId(), m.withUnplaced(reason));
        }
        clearAcknowledgement();
        calculatedAt = at;
        calculatedBy = by;
        status = LoadPlanStatus.PLANNED;
        changed = true;
    }

    private static List<Placed> placedOf(List<Placement> placements) {
        List<Placed> out = new ArrayList<>();
        for (Placement p : placements) {
            out.add(new Placed(UUID.fromString(p.ref()), p.x(), p.y(), p.z(), p.w(), p.h(), p.d(), p.orientation()));
        }
        return out;
    }

    // ---- manual editing (09 §4.2 step 3, BR-11) --------------------------------------------------------------------

    /**
     * A manual edit of the whole plan, all or nothing: every active vehicle with what it holds, and the tray (the
     * parcels left without a place). Every held parcel appears exactly once. Every vehicle must pass the loading rules
     * (BR-10) and a kit's parts must stay together on one vehicle (LP-3; on the tray together counts as together);
     * otherwise {@code PLACEMENT_RULE_VIOLATION} names each parcel and rule and nothing changes. A vehicle left empty
     * leaves the plan. A parcel moved to the tray is unplaced {@code MANUAL}.
     */
    public void editPlacements(List<VehicleEdit> edits, List<UUID> tray, Map<UUID, CargoPackage> cartons,
                               Instant at) {
        requireStatus("be edited", LoadPlanStatus.PLANNED);
        Map<Integer, Vehicle> bySeq = activeBySeq();
        List<FieldError> problems = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        Map<UUID, Integer> where = new HashMap<>();
        for (VehicleEdit e : edits) {
            if (!bySeq.containsKey(e.seq())) {
                problems.add(new FieldError("vehicles[" + e.seq() + "]", "no such vehicle in the plan",
                        "LOAD_PLAN_VEHICLE_UNKNOWN"));
            } else if (!seen.add(e.seq())) {
                problems.add(new FieldError("vehicles[" + e.seq() + "]", "listed twice", "LOAD_PLAN_VEHICLE_DUPLICATE"));
            }
            for (Placed p : e.boxes()) {
                track(p.packageId(), e.seq(), where, problems);
            }
        }
        for (Integer seq : bySeq.keySet()) {
            if (!seen.contains(seq)) {
                problems.add(new FieldError("vehicles[" + seq + "]", "every vehicle of the plan must be listed",
                        "LOAD_PLAN_VEHICLE_MISSING"));
            }
        }
        for (UUID t : tray == null ? List.<UUID>of() : tray) {
            track(t, 0, where, problems);
        }
        Set<UUID> held = activePackageIds();
        for (UUID packageId : where.keySet()) {
            if (!held.contains(packageId)) {
                problems.add(new FieldError(packageId.toString(), "not a package of this plan",
                        "LOAD_PLAN_PACKAGE_UNKNOWN"));
            }
        }
        for (UUID packageId : held) {
            if (!where.containsKey(packageId)) {
                problems.add(new FieldError(packageId.toString(), "every package of the plan must be listed once",
                        "LOAD_PLAN_PACKAGE_MISSING"));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The edit does not describe this plan",
                    problems);
        }

        List<PlacementViolation> violations = new ArrayList<>();
        List<List<PlacementValidator.Item>> groups = new ArrayList<>();
        Map<Integer, List<PlacementValidator.Item>> itemsBySeq = new LinkedHashMap<>();
        for (VehicleEdit e : edits) {
            Vehicle v = bySeq.get(e.seq());
            List<PlacementValidator.Item> items = new ArrayList<>();
            for (Placed p : e.boxes()) {
                CargoPackage c = carton(cartons, p.packageId());
                items.add(new PlacementValidator.Item(c, p.x(), p.y(), p.z(), p.w(), p.h(), p.d()));
                if (p.orientation() != null && !matches(p.orientation(), c, p)) {
                    violations.add(new PlacementViolation(c.ref(), ViolationType.ORIENTATION_MISMATCH));
                }
            }
            for (PlacementViolation pv : PlacementValidator.validate(v.spec(), items)) {
                violations.add(pv.ref() == null ? new PlacementViolation("vehicle:" + e.seq(), pv.rule()) : pv);
            }
            groups.add(items);
            itemsBySeq.put(e.seq(), items);
        }
        groups.add(trayItems(tray, cartons));
        violations.addAll(PlacementValidator.validateKitGroups(groups));
        if (!violations.isEmpty()) {
            throw ruleViolation(violations);
        }

        Set<UUID> unplacedBefore = unplacedPackageIds();
        for (VehicleEdit e : edits) {
            Vehicle v = bySeq.get(e.seq());
            int index = vehicles.indexOf(v);
            if (e.boxes().isEmpty()) {
                vehicles.set(index, v.retired());
                continue;
            }
            List<Placed> placed = new ArrayList<>();
            for (int i = 0; i < e.boxes().size(); i++) {
                Placed p = e.boxes().get(i);
                PlacementValidator.Item it = itemsBySeq.get(e.seq()).get(i);
                placed.add(new Placed(p.packageId(), p.x(), p.y(), p.z(), p.w(), p.h(), p.d(),
                        Orientation.of(it.carton(), p.w(), p.h(), p.d())));
            }
            vehicles.set(index, withUsage(v.id(), v.seq(), v.spec(), placed, cartons));
        }
        Set<UUID> onTray = new HashSet<>(tray == null ? List.of() : tray);
        for (Member m : List.copyOf(members.values())) {
            if (!m.active()) {
                continue;
            }
            if (onTray.contains(m.packageId())) {
                members.put(m.packageId(), m.withUnplaced(m.unplacedReason() == null ? LoadPlanUnplacedReason.MANUAL
                        : m.unplacedReason()));
            } else {
                members.put(m.packageId(), m.withUnplaced(null));
            }
        }
        if (!unplacedBefore.equals(unplacedPackageIds())) {
            clearAcknowledgement();
        }
        changed = true;
    }

    private static void track(UUID packageId, int seq, Map<UUID, Integer> where, List<FieldError> problems) {
        if (where.putIfAbsent(packageId, seq) != null) {
            problems.add(new FieldError(packageId.toString(), "listed more than once", "LOAD_PLAN_PACKAGE_DUPLICATE"));
        }
    }

    private static boolean matches(Orientation o, CargoPackage c, Placed p) {
        int[] e = o.extents(c);
        return e[0] == p.w() && e[1] == p.h() && e[2] == p.d();
    }

    private static List<PlacementValidator.Item> trayItems(Collection<UUID> tray, Map<UUID, CargoPackage> cartons) {
        List<PlacementValidator.Item> out = new ArrayList<>();
        for (UUID t : tray == null ? List.<UUID>of() : tray) {
            out.add(PlacementValidator.Item.of(carton(cartons, t), 0, 0, 0, Orientation.LWH));
        }
        return out;
    }

    // ---- one vehicle again (09 §4.2 step 3 "tính lại riêng xe đó") -------------------------------------------------

    /**
     * Stores the planner's answer for one vehicle: the parcels it was given (the vehicle's own, plus any taken from
     * the tray) are placed in it or go to the tray with the planner's reason. A vehicle left empty leaves the plan.
     */
    public void applyVehicleRecalculation(int seq, LoadPlanResult result, Map<UUID, CargoPackage> cartons,
                                          Instant at) {
        requireStatus("recalculate a vehicle", LoadPlanStatus.PLANNED);
        Vehicle v = requireVehicle(seq);
        if (result.vehicles().size() > 1) {
            throw new IllegalStateException("one vehicle recalculated into " + result.vehicles().size());
        }
        Set<UUID> unplacedBefore = unplacedPackageIds();
        int index = vehicles.indexOf(v);
        if (result.vehicles().isEmpty()) {
            vehicles.set(index, v.retired());
        } else {
            vehicles.set(index, withUsage(v.id(), v.seq(), v.spec(), placedOf(result.vehicles().getFirst().placements()),
                    cartons));
        }
        Set<UUID> placedNow = placedPackageIds();
        for (Member m : List.copyOf(members.values())) {
            if (m.active() && placedNow.contains(m.packageId())) {
                members.put(m.packageId(), m.withUnplaced(null));
            }
        }
        for (UnplacedPackage u : result.unplaced()) {
            UUID packageId = UUID.fromString(u.ref());
            Member m = members.get(packageId);
            members.put(packageId, m.withUnplaced(LoadPlanUnplacedReason.of(u.reason())));
        }
        if (!unplacedBefore.equals(unplacedPackageIds())) {
            clearAcknowledgement();
        }
        changed = true;
    }

    // ---- BR-13 ----------------------------------------------------------------------------------------------------

    /**
     * The dispatcher accepts that the unplaced parcels stay at the dispatch area (09 §4.2 step 3, BR-13). The service
     * checks the order's partial-shipment rule first (LP-4). A kit is never left half behind. Nothing unplaced, or
     * acknowledged already: nothing changes.
     */
    public boolean acknowledgeUnplaced(UUID by, Instant at, String ackNote, Map<UUID, CargoPackage> cartons) {
        requireStatus("acknowledge unplaced packages", LoadPlanStatus.PLANNED);
        requireKitsWhole(cartons);
        if (unplacedPackageIds().isEmpty() || acknowledgedAt != null) {
            return false;
        }
        acknowledgedAt = at;
        acknowledgedBy = Objects.requireNonNull(by, "by");
        acknowledgeNote = trimmed(ackNote);
        changed = true;
        return true;
    }

    // ---- confirmation, reopening, cancellation (09 §5, BR-13, BR-14) -----------------------------------------------

    /**
     * Locks the parcel list and the layout (09 §4.2 step 4). Needs a parcel placed, and the unplaced ones acknowledged
     * (BR-13); those are released - back to the dispatch area for a later trip. The service then opens one PLANNED
     * dispatch load per vehicle returned.
     */
    public List<Vehicle> confirm(UUID by, Instant at, Map<UUID, CargoPackage> cartons) {
        requireStatus("be confirmed", LoadPlanStatus.PLANNED);
        if (placedPackageIds().isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_PLAN_EMPTY,
                    "Load plan %s has no package placed: nothing to confirm".formatted(code));
        }
        List<Vehicle> flaggedVehicles = activeVehicles().stream().filter(Vehicle::needsRecalculation).toList();
        if (!flaggedVehicles.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_PLAN_NEEDS_RECALCULATION, ("Load plan %s lost parcels from "
                    + "vehicle(s) %s: recalculate them (or the plan) before confirming").formatted(code,
                    flaggedVehicles.stream().map(v -> String.valueOf(v.seq())).toList()));
        }
        requireLayoutValid(cartons);
        requireKitsWhole(cartons);
        Set<UUID> unplaced = unplacedPackageIds();
        if (!unplaced.isEmpty() && acknowledgedAt == null) {
            throw new BusinessException(ErrorCode.LOAD_PLAN_UNPLACED_NOT_ACKNOWLEDGED, ("Load plan %s leaves %d "
                    + "package(s) unplaced: acknowledge them or recalculate first").formatted(code, unplaced.size()));
        }
        for (UUID packageId : unplaced) {
            members.put(packageId, members.get(packageId).released(LoadPlanReleaseReason.LEFT_BEHIND, at));
        }
        status = LoadPlanStatus.CONFIRMED;
        confirmedAt = at;
        confirmedBy = Objects.requireNonNull(by, "by");
        changed = true;
        return activeVehicles();
    }

    /**
     * Back to PLANNED to change it (BR-14), while no vehicle is being loaded. The service cancels the vehicles'
     * dispatch loads - booked ones included, with the reason (LP-11).
     */
    public void reopen(String reason, boolean loadingStarted, UUID by, Instant at) {
        requireStatus("be reopened", LoadPlanStatus.CONFIRMED);
        requireNotLoading(loadingStarted, "reopened");
        String why = trimmed(reason);
        if (why == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Reopening a load plan says why",
                    List.of(new FieldError("reason", "required", "NotBlank")));
        }
        status = LoadPlanStatus.PLANNED;
        reopenedAt = at;
        reopenedBy = by;
        reopenReason = why;
        clearAcknowledgement();
        changed = true;
    }

    /**
     * Calls the plan off before any vehicle is loaded (09 §5; BR-14: once loading started, only the controlled
     * unloading of PR E). Its parcels go back to the dispatch area. Cancelled already: nothing changes.
     */
    public boolean cancel(String reason, boolean loadingStarted, UUID by, Instant at) {
        if (status == LoadPlanStatus.CANCELLED) {
            return false;
        }
        requireStatus("be cancelled", LoadPlanStatus.DRAFT, LoadPlanStatus.PLANNED, LoadPlanStatus.CONFIRMED);
        requireNotLoading(loadingStarted, "cancelled");
        String why = trimmed(reason);
        if (why == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Cancelling a load plan says why",
                    List.of(new FieldError("reason", "required", "NotBlank")));
        }
        for (Member m : List.copyOf(members.values())) {
            if (m.active()) {
                members.put(m.packageId(), m.released(LoadPlanReleaseReason.PLAN_CANCELLED, at));
            }
        }
        status = LoadPlanStatus.CANCELLED;
        cancelledAt = at;
        cancelledBy = by;
        cancelReason = why;
        changed = true;
        return true;
    }

    /** Every vehicle handed over and gone (BR-15): PR E calls this when the last dispatch load is DISPATCHED. */
    public void markDispatched(Instant at) {
        requireStatus("be dispatched", LoadPlanStatus.CONFIRMED);
        status = LoadPlanStatus.DISPATCHED;
        dispatchedAt = at;
        changed = true;
    }

    /**
     * The order of a shipment in the plan was cancelled (09 edge "đơn bị huỷ khi kiện đã nằm trong kế hoạch"): its
     * parcels leave the plan and their places are freed; a vehicle left empty leaves the plan. A confirmed plan goes
     * back to PLANNED when no vehicle is loading yet (the service cancels its dispatch loads); while one is loading
     * the plan stays CONFIRMED and the unloading is PR E's.
     *
     * @return whether the plan went back from CONFIRMED to PLANNED
     */
    public boolean dropShipment(UUID shipmentId, boolean loadingStarted, Instant at) {
        if (!status.isLive()) {
            return false;
        }
        Set<UUID> dropped = new HashSet<>();
        for (Member m : List.copyOf(members.values())) {
            if (m.active() && Objects.equals(m.shipmentId(), shipmentId)) {
                members.put(m.packageId(), m.released(LoadPlanReleaseReason.ORDER_CANCELLED, at));
                dropped.add(m.packageId());
            }
        }
        if (dropped.isEmpty()) {
            return false;
        }
        for (int i = 0; i < vehicles.size(); i++) {
            Vehicle v = vehicles.get(i);
            if (!v.active() || v.placements().stream().noneMatch(p -> dropped.contains(p.packageId()))) {
                continue;
            }
            List<Placed> kept = v.placements().stream().filter(p -> !dropped.contains(p.packageId())).toList();
            if (kept.isEmpty() && status != LoadPlanStatus.CONFIRMED) {
                vehicles.set(i, v.retired());
            } else {
                // "bỏ kiện & tính lại": what rested on a removed parcel may float - recalculate before confirming.
                vehicles.set(i, new Vehicle(v.id(), v.seq(), true, v.spec(), kept, volume(kept),
                        v.usedWeightKg(), v.volumeFillPct(), v.weightFillPct(), !kept.isEmpty()));
            }
        }
        changed = true;
        if (status == LoadPlanStatus.CONFIRMED && !loadingStarted) {
            status = LoadPlanStatus.PLANNED;
            vehicles.replaceAll(v -> v.active() && v.placements().isEmpty() ? v.retired() : v);
            return true;
        }
        return false;
    }

    /** Recomputes the weight figures of the vehicles after {@link #dropShipment}, once the cartons are known. */
    public void refreshUsage(Map<UUID, CargoPackage> cartons) {
        vehicles.replaceAll(v -> v.active() ? flagged(withUsage(v.id(), v.seq(), v.spec(), v.placements(), cartons),
                v.needsRecalculation()) : v);
    }

    private static Vehicle flagged(Vehicle v, boolean needsRecalculation) {
        return new Vehicle(v.id(), v.seq(), v.active(), v.spec(), v.placements(), v.usedVolumeMm3(), v.usedWeightKg(),
                v.volumeFillPct(), v.weightFillPct(), needsRecalculation);
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    public Set<UUID> activePackageIds() {
        Set<UUID> out = new LinkedHashSet<>();
        members.values().forEach(m -> {
            if (m.active()) {
                out.add(m.packageId());
            }
        });
        return out;
    }

    public Set<UUID> placedPackageIds() {
        Set<UUID> out = new LinkedHashSet<>();
        for (Vehicle v : vehicles) {
            if (v.active()) {
                v.placements().forEach(p -> out.add(p.packageId()));
            }
        }
        return out;
    }

    /** Held parcels with no place after a calculation (the tray). */
    public Set<UUID> unplacedPackageIds() {
        Set<UUID> out = new LinkedHashSet<>();
        members.values().forEach(m -> {
            if (m.active() && m.unplacedReason() != null) {
                out.add(m.packageId());
            }
        });
        return out;
    }

    public List<Vehicle> activeVehicles() {
        return vehicles.stream().filter(Vehicle::active).sorted(Comparator.comparingInt(Vehicle::seq)).toList();
    }

    public Vehicle requireVehicle(int seq) {
        Vehicle v = activeBySeq().get(seq);
        if (v == null) {
            throw new BusinessException(ErrorCode.LOAD_PLAN_VEHICLE_NOT_FOUND,
                    "Load plan %s has no vehicle %d".formatted(code, seq));
        }
        return v;
    }

    public Collection<Member> members() {
        return members.values();
    }

    public List<Vehicle> vehicles() {
        return List.copyOf(vehicles);
    }

    public Snapshot snapshot() {
        return new Snapshot(id, code, requestId, warehouseId, sourceType, mode, status, note, calculatedAt,
                calculatedBy, acknowledgedAt, acknowledgedBy, acknowledgeNote, confirmedAt, confirmedBy, reopenedAt,
                reopenedBy, reopenReason, dispatchedAt, cancelledAt, cancelledBy, cancelReason, version,
                List.copyOf(vehicleTypeIds), List.copyOf(members.values()), List.copyOf(vehicles));
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public UUID requestId() { return requestId; }
    public UUID warehouseId() { return warehouseId; }
    public String sourceType() { return sourceType; }
    /** A plan of one transfer order's parcels (SCRUM-287 PR T). */
    public boolean ofTransfer() { return SOURCE_TRANSFER.equals(sourceType); }
    public SelectionMode mode() { return mode; }
    public LoadPlanStatus status() { return status; }
    public long version() { return version; }
    public List<UUID> vehicleTypeIds() { return List.copyOf(vehicleTypeIds); }
    public Instant acknowledgedAt() { return acknowledgedAt; }
    public boolean changed() { return changed; }

    // ---- internals -------------------------------------------------------------------------------------------------

    /** The last gate of confirmation (BR-10): every vehicle as stored passes the loading rules. */
    private void requireLayoutValid(Map<UUID, CargoPackage> cartons) {
        List<PlacementViolation> violations = new ArrayList<>();
        for (Vehicle v : activeVehicles()) {
            List<PlacementValidator.Item> items = new ArrayList<>();
            for (Placed p : v.placements()) {
                items.add(new PlacementValidator.Item(carton(cartons, p.packageId()), p.x(), p.y(), p.z(), p.w(),
                        p.h(), p.d()));
            }
            for (PlacementViolation pv : PlacementValidator.validate(v.spec(), items)) {
                violations.add(pv.ref() == null ? new PlacementViolation("vehicle:" + v.seq(), pv.rule()) : pv);
            }
        }
        if (!violations.isEmpty()) {
            throw ruleViolation(violations);
        }
    }

    /** A kit's parts are all placed or all left behind (LP-3: and the placed ones on one vehicle, BR-11). */
    private void requireKitsWhole(Map<UUID, CargoPackage> cartons) {
        List<List<PlacementValidator.Item>> groups = new ArrayList<>();
        for (Vehicle v : activeVehicles()) {
            List<PlacementValidator.Item> items = new ArrayList<>();
            for (Placed p : v.placements()) {
                items.add(new PlacementValidator.Item(carton(cartons, p.packageId()), p.x(), p.y(), p.z(), p.w(),
                        p.h(), p.d()));
            }
            groups.add(items);
        }
        groups.add(trayItems(unplacedPackageIds(), cartons));
        List<PlacementViolation> split = PlacementValidator.validateKitGroups(groups);
        if (!split.isEmpty()) {
            throw ruleViolation(split);
        }
    }

    /** {@code PLACEMENT_RULE_VIOLATION} with one field error per package and rule: field = ref, code = rule. */
    public static BusinessException ruleViolation(List<PlacementViolation> violations) {
        List<FieldError> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PlacementViolation v : violations) {
            String ref = v.ref() == null ? "vehicle" : v.ref();
            if (seen.add(ref + "/" + v.rule())) {
                fields.add(new FieldError(ref, v.rule().name(), v.rule().name()));
            }
        }
        return new BusinessException(ErrorCode.PLACEMENT_RULE_VIOLATION,
                "%d placement(s) break a loading rule; nothing was changed".formatted(fields.size()), fields);
    }

    private void requireSomethingToPlan() {
        if (activePackageIds().isEmpty() || vehicleTypeIds.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_PLAN_EMPTY,
                    "Load plan %s needs at least one shipment and one vehicle type".formatted(code));
        }
    }

    private void requireNotLoading(boolean loadingStarted, String what) {
        if (loadingStarted) {
            throw new BusinessException(ErrorCode.LOAD_PLAN_LOADING_STARTED, ("Load plan %s has a vehicle being "
                    + "loaded or gone; it is not %s here (BR-14)").formatted(code, what));
        }
    }

    private void requireStatus(String what, LoadPlanStatus... allowed) {
        for (LoadPlanStatus s : allowed) {
            if (status == s) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.INVALID_LOAD_PLAN_TRANSITION,
                "Load plan %s is %s and cannot %s".formatted(code, status, what));
    }

    private void clearAcknowledgement() {
        acknowledgedAt = null;
        acknowledgedBy = null;
        acknowledgeNote = null;
    }

    private Map<Integer, Vehicle> activeBySeq() {
        Map<Integer, Vehicle> out = new LinkedHashMap<>();
        activeVehicles().forEach(v -> out.put(v.seq(), v));
        return out;
    }

    private static CargoPackage carton(Map<UUID, CargoPackage> cartons, UUID packageId) {
        CargoPackage c = cartons.get(packageId);
        if (c == null) {
            throw new IllegalStateException("no sizes for package " + packageId);
        }
        return c;
    }

    private static long volume(List<Placed> placed) {
        long volume = 0;
        for (Placed p : placed) {
            volume += (long) p.w() * p.h() * p.d();
        }
        return volume;
    }

    /** A vehicle with these placements and its loaded volume and weight, as the planner reports them. */
    static Vehicle withUsage(UUID id, int seq, VehicleSpec spec, List<Placed> placed,
                             Map<UUID, CargoPackage> cartons) {
        long volume = volume(placed);
        BigDecimal weight = BigDecimal.ZERO;
        for (Placed p : placed) {
            weight = weight.add(carton(cartons, p.packageId()).weightKg());
        }
        long inner = (long) spec.innerLengthMm() * spec.innerWidthMm() * spec.innerHeightMm();
        BigDecimal volumePct = BigDecimal.valueOf(volume).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(inner), 2, RoundingMode.HALF_UP);
        BigDecimal weightPct = weight.multiply(BigDecimal.valueOf(100))
                .divide(spec.maxPayloadKg(), 2, RoundingMode.HALF_UP);
        List<Placed> ordered = new ArrayList<>(placed);
        ordered.sort(Comparator.comparingInt(Placed::z).thenComparingInt(Placed::y).thenComparingInt(Placed::x));
        return new Vehicle(id, seq, true, spec, ordered, volume, weight, volumePct, weightPct, false);
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
