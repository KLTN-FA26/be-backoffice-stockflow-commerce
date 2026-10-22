package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.Specs;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.LoadPlanReleaseReason;
import com.stockflow.fulfillment.internal.domain.LoadPlanStatus;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.KitDistribution;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanMeta;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanRequest;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanResult;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanner;
import com.stockflow.fulfillment.internal.domain.loadplanning.Orientation;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleKind;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.VehicleTypeJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.LoadNumbers;
import com.stockflow.fulfillment.internal.repository.LoadPlanJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadPlanNumbers;
import com.stockflow.fulfillment.internal.repository.LoadPlanPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadPlanStore;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentLocks;
import com.stockflow.fulfillment.internal.repository.VehicleTypeJpaRepository;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * See {@link LoadPlans}. Lock order: the plan's row, then the shipments' rows (id order), then the dispatch loads' rows
 * (id order). The order-cancellation path ({@link #releaseShipment}) takes the plan's row before it touches a load or a
 * shipment, so it never crosses a confirmation or a reopening.
 *
 * <p>The planner runs synchronously inside the request, capped by
 * {@code stockflow.fulfillment.load-planning.solver-budget} (decision LP2-10): it returns the best plan found when the
 * budget runs out, and reports it ({@code solverMeta.budgetExhausted}, unplaced reason {@code SEARCH_CUT_OFF}).</p>
 */
@Service
@Transactional
class LoadPlanService implements LoadPlans, LoadPlanReleases {

    private static final Logger log = LoggerFactory.getLogger(LoadPlanService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<LoadStatus> LOADING = Set.of(LoadStatus.LOADING, LoadStatus.DISPATCHED);
    private static final Set<LoadStatus> CANCELLABLE = Set.of(LoadStatus.PLANNED, LoadStatus.VEHICLE_BOOKED);

    private final LoadPlanJpaRepository plans;
    private final LoadPlanPackageJpaRepository members;
    private final com.stockflow.fulfillment.internal.repository.LoadPlanVehicleJpaRepository planVehicles;
    private final LoadPlanStore store;
    private final LoadPlanNumbers numbers;
    private final LoadPlanParcels parcels;
    private final PackageJpaRepository packages;
    private final ShipmentJpaRepository shipments;
    private final ShipmentLocks shipmentLocks;
    private final VehicleTypeJpaRepository vehicleTypes;
    private final DispatchLoadJpaRepository loads;
    private final DispatchLoadStore loadStore;
    private final LoadNumbers loadNumbers;
    private final WarehouseService warehouses;
    private final LoadPlanEstimateComparison estimates;
    private final com.stockflow.fulfillment.internal.repository.PickJpaRepository picks;
    private final com.stockflow.fulfillment.internal.repository.PackageLineJpaRepository packageLines;
    private final Clock clock;
    private final Duration budget;

    @SuppressWarnings("java:S107")
    LoadPlanService(LoadPlanJpaRepository plans, LoadPlanPackageJpaRepository members,
                    com.stockflow.fulfillment.internal.repository.LoadPlanVehicleJpaRepository planVehicles,
                    LoadPlanStore store,
                    LoadPlanNumbers numbers, LoadPlanParcels parcels, PackageJpaRepository packages,
                    ShipmentJpaRepository shipments, ShipmentLocks shipmentLocks,
                    VehicleTypeJpaRepository vehicleTypes, DispatchLoadJpaRepository loads,
                    DispatchLoadStore loadStore, LoadNumbers loadNumbers, WarehouseService warehouses,
                    LoadPlanEstimateComparison estimates,
                    com.stockflow.fulfillment.internal.repository.PickJpaRepository picks,
                    com.stockflow.fulfillment.internal.repository.PackageLineJpaRepository packageLines, Clock clock,
                    @Value("${stockflow.fulfillment.load-planning.solver-budget:PT5S}") Duration budget) {
        this.plans = plans;
        this.members = members;
        this.planVehicles = planVehicles;
        this.store = store;
        this.numbers = numbers;
        this.parcels = parcels;
        this.packages = packages;
        this.shipments = shipments;
        this.shipmentLocks = shipmentLocks;
        this.vehicleTypes = vehicleTypes;
        this.loads = loads;
        this.loadStore = loadStore;
        this.loadNumbers = loadNumbers;
        this.warehouses = warehouses;
        this.estimates = estimates;
        this.picks = picks;
        this.packageLines = packageLines;
        this.clock = clock;
        this.budget = budget;
    }

    // ------------------------------------------------------------------ candidates (09 §4.2 step 1, LP-5, LP2-1)

    @Override
    @Transactional(readOnly = true)
    public List<CandidateShipment> candidates(UUID warehouseId, UUID loadPlanId) {
        Objects.requireNonNull(warehouseId, "warehouseId");
        WarehouseScope.requireWarehouse(warehouseId);
        List<ShipmentJpaEntity> waiting = shipments.findAll(Specification.allOf(List.of(
                Specs.eq("warehouseId", warehouseId), Specs.eq("status", ShipmentStatus.READY_TO_DISPATCH),
                Specs.eq("deliveryMode", DeliveryMode.FULL_TRUCKLOAD))), Sort.by("readyAt", "id"));
        if (waiting.isEmpty()) {
            return List.of();
        }
        List<PackageJpaEntity> sealed = packages.findByShipmentIdIn(waiting.stream().map(ShipmentJpaEntity::getId)
                .toList()).stream().filter(p -> p.getStatus() == PackageStatus.SEALED).toList();
        Set<UUID> heldElsewhere = members.findByPackageIdInAndActiveTrue(sealed.stream().map(PackageJpaEntity::getId)
                        .toList()).stream().filter(m -> !m.getLoadPlanId().equals(loadPlanId))
                .map(LoadPlanPackageJpaEntity::getPackageId).collect(Collectors.toSet());
        Map<UUID, LoadPlanParcels.Parcel> described = parcels.describe(sealed);
        Map<UUID, OrderSummary> orders = parcels.ordersOf(waiting);
        List<CandidateShipment> out = new ArrayList<>();
        for (ShipmentJpaEntity s : waiting) {
            List<LoadPlanParcels.Parcel> mine = described.values().stream()
                    .filter(p -> s.getId().equals(p.row().getShipmentId())).toList();
            List<LoadPlanParcels.Parcel> free = mine.stream().filter(p -> !heldElsewhere.contains(p.packageId()))
                    .sorted(Comparator.comparingInt(p -> p.row().getPackageNo())).toList();
            if (free.isEmpty()) {
                continue;
            }
            BigDecimal weight = BigDecimal.ZERO;
            long volume = 0;
            for (LoadPlanParcels.Parcel p : free) {
                if (p.carton() != null) {
                    weight = weight.add(p.carton().weightKg());
                    volume += (long) p.carton().lengthMm() * p.carton().widthMm() * p.carton().heightMm();
                }
            }
            OrderSummary order = orders.get(s.getOrderId());
            out.add(new CandidateShipment(s.getId(), s.getCode(), s.getOrderId(),
                    order == null ? null : order.orderNumber(), allowsPartial(order), s.getReadyAt(), free.size(),
                    mine.size() - free.size(), weight, volume,
                    free.stream().map(p -> parcelView(p, null, null)).toList()));
        }
        return out;
    }

    // ------------------------------------------------------------------ the plan

    @Override
    public PlanDetail create(CreatePlan request, CurrentUser actor) {
        Objects.requireNonNull(request.requestId(), "requestId");
        numbers.lockRequest(request.requestId());
        var replay = plans.findByRequestId(request.requestId());
        if (replay.isPresent()) {
            LoadPlanJpaEntity header = replay.get();
            WarehouseScope.requireWarehouse(header.getWarehouseId());
            if (!header.getWarehouseId().equals(request.warehouseId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "That request id created another load plan");
            }
            return detail(header, store.load(header));
        }
        WarehouseScope.requireWarehouse(request.warehouseId());
        warehouses.findWarehouse(request.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
        Instant now = clock.instant();
        LoadPlan plan = LoadPlan.draft(Identifiers.newId(), numbers.next(LocalDate.ofInstant(now, BUSINESS_ZONE)),
                request.requestId(), request.warehouseId(), mode(request.selectionMode()), request.note());
        boolean withInputs = request.shipmentIds() != null && !request.shipmentIds().isEmpty()
                || request.vehicleTypeIds() != null && !request.vehicleTypeIds().isEmpty();
        if (withInputs) {
            lockPicksOf(request.shipmentIds());
            applyInputs(plan, request.shipmentIds(), request.vehicleTypeIds(), null, actor.userId(), now);
        }
        LoadPlanJpaEntity header = claim(() -> store.insert(plan, now));
        log.info("Load plan {} created at warehouse {}: {} package(s)", plan.code(), plan.warehouseId(),
                plan.activePackageIds().size());
        return detail(header, plan);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PlanRow> list(UUID warehouseId, String status, String search, int page, int size) {
        if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
        }
        List<Specification<LoadPlanJpaEntity>> filters = new ArrayList<>();
        filters.add(WarehouseScope.warehousesIn("warehouseId"));
        if (warehouseId != null) {
            filters.add(Specs.eq("warehouseId", warehouseId));
        }
        if (status != null && !status.isBlank()) {
            filters.add(Specs.eq("status", status(status)));
        }
        if (search != null && !search.isBlank()) {
            filters.add(Specs.contains("code", search.trim()));
        }
        var found = plans.findAll(Specification.allOf(filters),
                Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return Pages.toResponse(found, header -> {
            List<LoadPlanPackageJpaEntity> held = members.findByLoadPlanIdOrderByAddedAtAscIdAsc(header.getId())
                    .stream().filter(LoadPlanPackageJpaEntity::isActive).toList();
            long shipmentCount = held.stream().map(LoadPlanPackageJpaEntity::getShipmentId).distinct().count();
            long unplaced = held.stream().filter(m -> m.getUnplacedReason() != null).count();
            long vehicleCount = planVehicles.findByLoadPlanIdOrderBySeqAscCreatedAtAsc(header.getId()).stream()
                    .filter(com.stockflow.fulfillment.internal.entity.LoadPlanVehicleJpaEntity::isActive).count();
            return new PlanRow(header.getId(), header.getCode(), header.getWarehouseId(), header.getStatus().name(),
                    header.getSelectionMode().name(), (int) shipmentCount, held.size(), (int) vehicleCount,
                    (int) unplaced, header.getCreatedAt(), header.getCalculatedAt(), header.getConfirmedAt(),
                    header.getVersion());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public PlanDetail get(UUID loadPlanId) {
        LoadPlanJpaEntity header = read(loadPlanId);
        return detail(header, store.load(header));
    }

    @Override
    public PlanDetail setInputs(UUID loadPlanId, Inputs inputs, CurrentUser actor) {
        lockPicksOf(inputs.shipmentIds());
        LoadPlanJpaEntity header = lock(loadPlanId, inputs.expectedVersion());
        LoadPlan plan = store.load(header);
        Instant now = clock.instant();
        applyInputs(plan, inputs.shipmentIds(), inputs.vehicleTypeIds(), inputs.selectionMode(), actor.userId(), now);
        return saved(plan, header, now);
    }

    /**
     * The orders' fulfilment tasks, locked before the plan (id order of the orders): the lock the order-cancellation
     * listener takes first. A cancellation running meanwhile then either finishes before the parcels are claimed (the
     * shipment is CANCELLED: 409) or starts after the claim committed (it sees the plan and releases the parcels before
     * voiding them) - never between, where its void would hit tg_package_in_load_plan.
     */
    private void lockPicksOf(List<UUID> shipmentIds) {
        if (shipmentIds == null || shipmentIds.isEmpty()) {
            return;
        }
        shipmentLocks.ordersOf(new LinkedHashSet<>(shipmentIds)).stream().sorted()
                .forEach(orderId -> picks.lockByOrderId(orderId));
    }

    /** Whole shipments (LP-5) of this warehouse's full truckloads waiting for dispatch, with their free parcels. */
    private void applyInputs(LoadPlan plan, List<UUID> shipmentIds, List<UUID> typeIds, String selectionMode,
                             UUID by, Instant at) {
        Set<UUID> wanted = new LinkedHashSet<>(shipmentIds == null ? List.of() : shipmentIds);
        Map<UUID, ShipmentJpaEntity> rows = shipmentLocks.lock(wanted).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        Map<UUID, UUID> chosen = new LinkedHashMap<>();
        List<PackageJpaEntity> taken = new ArrayList<>();
        for (UUID id : wanted) {
            ShipmentJpaEntity s = rows.get(id);
            if (s == null) {
                throw new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND, "No shipment " + id);
            }
            if (s.getStatus() != ShipmentStatus.READY_TO_DISPATCH) {
                throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT, ("Shipment %s is %s; only one waiting "
                        + "for dispatch is planned").formatted(s.getCode(), s.getStatus()));
            }
            if (s.getDeliveryMode() != DeliveryMode.FULL_TRUCKLOAD || !s.getWarehouseId().equals(plan.warehouseId())) {
                throw new BusinessException(ErrorCode.SHIPMENT_NOT_FOR_LOAD, ("Shipment %s is not a full truckload of "
                        + "this plan's warehouse (09 BR-08)").formatted(s.getCode()));
            }
            List<PackageJpaEntity> sealed = packages.findByShipmentIdOrderByPackageNoAsc(id).stream()
                    .filter(p -> p.getStatus() == PackageStatus.SEALED).toList();
            Map<UUID, UUID> elsewhere = new HashMap<>();
            members.findByPackageIdInAndActiveTrue(sealed.stream().map(PackageJpaEntity::getId).toList())
                    .forEach(m -> {
                        if (!m.getLoadPlanId().equals(plan.id())) {
                            elsewhere.put(m.getPackageId(), m.getLoadPlanId());
                        }
                    });
            List<PackageJpaEntity> free = sealed.stream().filter(p -> !elsewhere.containsKey(p.getId())).toList();
            if (free.isEmpty()) {
                String held = elsewhere.values().stream().distinct().map(planId -> plans.findById(planId)
                        .map(LoadPlanJpaEntity::getCode).orElse("?")).sorted().collect(Collectors.joining(", "));
                throw new BusinessException(ErrorCode.LOAD_PLAN_PACKAGE_TAKEN, ("Shipment %s has no package free to "
                        + "plan: held by load plan %s (09 BR-09)").formatted(s.getCode(),
                        held.isEmpty() ? "-" : held));
            }
            free.forEach(p -> chosen.put(p.getId(), id));
            taken.addAll(free);
        }
        LoadPlanParcels.requireMeasured(parcels.describe(taken).values());
        List<UUID> types = typeIds == null ? plan.vehicleTypeIds() : typeIds;
        requireUsableTypes(types);
        plan.setInputs(chosen, types, selectionMode == null ? null : mode(selectionMode), by, at);
    }

    private List<VehicleTypeJpaEntity> requireUsableTypes(Collection<UUID> typeIds) {
        Set<UUID> ids = new LinkedHashSet<>(typeIds);
        Map<UUID, VehicleTypeJpaEntity> found = vehicleTypes.findAllById(ids).stream()
                .collect(Collectors.toMap(VehicleTypeJpaEntity::getId, Function.identity()));
        List<VehicleTypeJpaEntity> out = new ArrayList<>();
        for (UUID id : ids) {
            VehicleTypeJpaEntity t = found.get(id);
            if (t == null) {
                throw new BusinessException(ErrorCode.VEHICLE_TYPE_NOT_FOUND, "No vehicle type " + id);
            }
            if (!t.isActive()) {
                throw new BusinessException(ErrorCode.VEHICLE_TYPE_INACTIVE,
                        "Vehicle type %s is no longer in use".formatted(t.getCode()));
            }
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ calculation (09 §4.2 step 2)

    @Override
    public PlanDetail calculate(UUID loadPlanId, Long expectedVersion, CurrentUser actor) {
        LoadPlanJpaEntity header = lock(loadPlanId, expectedVersion);
        LoadPlan plan = store.load(header);
        if (plan.status() != LoadPlanStatus.DRAFT && plan.status() != LoadPlanStatus.PLANNED) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_PLAN_TRANSITION,
                    "Load plan %s is %s and cannot be calculated".formatted(plan.code(), plan.status()));
        }
        if (plan.activePackageIds().isEmpty() || plan.vehicleTypeIds().isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_PLAN_EMPTY,
                    "Load plan %s needs at least one shipment and one vehicle type".formatted(plan.code()));
        }
        Map<UUID, LoadPlanParcels.Parcel> held = parcels.of(plan.activePackageIds());
        LoadPlanParcels.requireMeasured(held.values());
        Map<UUID, VehicleSpec> specs = new LinkedHashMap<>();
        for (VehicleTypeJpaEntity t : requireUsableTypes(plan.vehicleTypeIds())) {
            specs.put(t.getId(), spec(t));
        }
        Map<UUID, CargoPackage> cartons = cartons(held);
        LoadPlanResult result = new LoadPlanner().plan(new LoadPlanRequest(List.copyOf(cartons.values()),
                List.copyOf(specs.values()), plan.mode(), List.of(), KitDistribution.AUTO, false, null, 0L, budget));
        Instant now = clock.instant();
        plan.applyCalculation(result, specs, Identifiers::newId, actor.userId(), now, cartons);
        header.setSolverMeta(meta(result.meta()));
        log.info("Load plan {} calculated: {} vehicle(s), {} placed, {} unplaced in {} ms{}", plan.code(),
                result.vehicles().size(), result.placedCount(), result.unplaced().size(),
                result.meta().elapsedMillis(), result.meta().budgetExhausted() ? " (budget exhausted)" : "");
        return saved(plan, header, now);
    }

    @Override
    public PlanDetail recalculateVehicle(UUID loadPlanId, int seq, Long expectedVersion, List<UUID> addPackageIds,
                                        CurrentUser actor) {
        LoadPlanJpaEntity header = lock(loadPlanId, expectedVersion);
        LoadPlan plan = store.load(header);
        if (plan.status() != LoadPlanStatus.PLANNED) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_PLAN_TRANSITION,
                    "Load plan %s is %s; only a PLANNED plan recalculates a vehicle".formatted(plan.code(),
                            plan.status()));
        }
        LoadPlan.Vehicle vehicle = plan.requireVehicle(seq);
        Set<UUID> tray = plan.unplacedPackageIds();
        Set<UUID> given = new LinkedHashSet<>();
        vehicle.placements().forEach(p -> given.add(p.packageId()));
        List<FieldError> problems = new ArrayList<>();
        for (UUID id : addPackageIds == null ? List.<UUID>of() : addPackageIds) {
            if (!tray.contains(id)) {
                problems.add(new FieldError(id.toString(), "not on the plan's tray", "LOAD_PLAN_PACKAGE_NOT_ON_TRAY"));
            }
            given.add(id);
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only packages left unplaced can be added",
                    problems);
        }
        Map<UUID, LoadPlanParcels.Parcel> all = parcels.of(plan.activePackageIds());
        // A kit goes in whole (LP-3): a part taken from the tray brings the rest of its kit from the tray.
        Set<UUID> kits = new HashSet<>();
        given.forEach(id -> {
            var kit = all.get(id).row().getKit();
            if (kit != null) {
                kits.add(kit.groupId());
            }
        });
        tray.forEach(id -> {
            var kit = all.get(id).row().getKit();
            if (kit != null && kits.contains(kit.groupId())) {
                given.add(id);
            }
        });
        Map<UUID, CargoPackage> cartons = cartons(all);
        List<CargoPackage> offered = given.stream().map(cartons::get).toList();
        LoadPlanResult result = new LoadPlanner().plan(new LoadPlanRequest(offered, List.of(vehicle.spec()),
                SelectionMode.SINGLE_TYPE, List.of(), KitDistribution.AUTO, false, null, 0L, budget));
        Instant now = clock.instant();
        plan.applyVehicleRecalculation(seq, result, cartons, now);
        log.info("Load plan {} vehicle {} recalculated: {} placed, {} unplaced", plan.code(), seq,
                result.placedCount(), result.unplaced().size());
        return saved(plan, header, now);
    }

    // ------------------------------------------------------------------ layouts (FE #71)

    @Override
    @Transactional(readOnly = true)
    public Layout layout(UUID loadPlanId, int seq) {
        LoadPlanJpaEntity header = read(loadPlanId);
        LoadPlan plan = store.load(header);
        LoadPlan.Vehicle vehicle = plan.requireVehicle(seq);
        return LoadPlanLayoutMapper.toLayout(vehicle, parcels.of(placedOn(List.of(vehicle))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Layout> layouts(UUID loadPlanId) {
        LoadPlanJpaEntity header = read(loadPlanId);
        LoadPlan plan = store.load(header);
        List<LoadPlan.Vehicle> vehicles = plan.activeVehicles();
        Map<UUID, LoadPlanParcels.Parcel> described = parcels.of(placedOn(vehicles));
        return vehicles.stream().map(v -> LoadPlanLayoutMapper.toLayout(v, described)).toList();
    }

    private static List<UUID> placedOn(List<LoadPlan.Vehicle> vehicles) {
        List<UUID> ids = new ArrayList<>();
        vehicles.forEach(v -> v.placements().forEach(p -> ids.add(p.packageId())));
        return ids;
    }

    // ------------------------------------------------------------------ manual edit (BR-11)

    @Override
    public PlanDetail editPlacements(UUID loadPlanId, PlacementEdit edit, CurrentUser actor) {
        LoadPlanJpaEntity header = lock(loadPlanId, edit.expectedVersion());
        LoadPlan plan = store.load(header);
        List<LoadPlan.VehicleEdit> edits = new ArrayList<>();
        List<FieldError> problems = new ArrayList<>();
        for (VehicleBoxes v : edit.vehicles() == null ? List.<VehicleBoxes>of() : edit.vehicles()) {
            List<LoadPlan.Placed> boxes = new ArrayList<>();
            for (BoxPosition b : v.boxes() == null ? List.<BoxPosition>of() : v.boxes()) {
                Orientation o = null;
                if (b.orientation() != null && !b.orientation().isBlank()) {
                    try {
                        o = Orientation.ofCode(b.orientation().trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        problems.add(new FieldError(String.valueOf(b.ref()), "LWH, LHW, WLH, WHL, HLW or HWL",
                                "LOAD_PLAN_ORIENTATION_INVALID"));
                    }
                }
                boxes.add(new LoadPlan.Placed(b.ref(), b.xMm(), b.yMm(), b.zMm(), b.wMm(), b.hMm(), b.dMm(), o));
            }
            edits.add(new LoadPlan.VehicleEdit(v.seq(), boxes));
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid orientation", problems);
        }
        Map<UUID, CargoPackage> cartons = cartons(parcels.of(plan.activePackageIds()));
        Instant now = clock.instant();
        plan.editPlacements(edits, edit.tray() == null ? List.of() : edit.tray(), cartons, now);
        log.info("Load plan {} edited by hand: {} placed, {} on the tray", plan.code(), plan.placedPackageIds().size(),
                plan.unplacedPackageIds().size());
        return saved(plan, header, now);
    }

    // ------------------------------------------------------------------ BR-13, confirmation, reopening, cancellation

    @Override
    public PlanDetail acknowledgeUnplaced(UUID loadPlanId, Long expectedVersion, String note, CurrentUser actor) {
        LoadPlanJpaEntity header = lock(loadPlanId, expectedVersion);
        LoadPlan plan = store.load(header);
        Map<UUID, LoadPlanParcels.Parcel> held = parcels.of(plan.activePackageIds());
        if (plan.status() == LoadPlanStatus.PLANNED) {
            requirePartialAllowed(plan, held);
        }
        Instant now = clock.instant();
        plan.acknowledgeUnplaced(actor.userId(), now, note, cartons(held));
        return saved(plan, header, now);
    }

    @Override
    public PlanDetail confirm(UUID loadPlanId, Long expectedVersion, CurrentUser actor) {
        LoadPlanJpaEntity header = lock(loadPlanId, expectedVersion);
        LoadPlan plan = store.load(header);
        Map<UUID, LoadPlanParcels.Parcel> held = parcels.of(plan.activePackageIds());
        if (plan.status() == LoadPlanStatus.PLANNED) {
            if (plan.placedPackageIds().isEmpty()) {
                throw new BusinessException(ErrorCode.LOAD_PLAN_EMPTY,
                        "Load plan %s has no package placed: nothing to confirm".formatted(plan.code()));
            }
            requirePartialAllowed(plan, held);
            // 09 BR-01: what leaves still waits for dispatch (locked: nothing hands it over or cancels it meanwhile).
            Set<UUID> leaving = new HashSet<>();
            plan.placedPackageIds().forEach(id -> leaving.add(held.get(id).row().getShipmentId()));
            for (ShipmentJpaEntity s : shipmentLocks.lock(leaving)) {
                if (s.getStatus() != ShipmentStatus.READY_TO_DISPATCH) {
                    throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT, ("Shipment %s is %s; take it out "
                            + "of the plan").formatted(s.getCode(), s.getStatus()));
                }
            }
        }
        Instant now = clock.instant();
        List<LoadPlan.Vehicle> vehicles = plan.confirm(actor.userId(), now, cartons(held));
        LoadPlanJpaEntity after = store.save(plan, header, now);
        LocalDate day = LocalDate.ofInstant(now, BUSINESS_ZONE);
        for (LoadPlan.Vehicle v : vehicles) {
            UUID id = Identifiers.newId();
            loadStore.insert(DispatchLoad.planFullTruckload(id, loadNumbers.next(day), Identifiers.newId(),
                    plan.warehouseId(), v.id()));
        }
        log.info("Load plan {} confirmed: {} vehicle(s), {} package(s) left behind", plan.code(), vehicles.size(),
                plan.members().stream().filter(m -> m.releaseReason() == LoadPlanReleaseReason.LEFT_BEHIND
                        && now.equals(m.releasedAt())).count());
        return detail(after, plan);
    }

    @Override
    public PlanDetail reopen(UUID loadPlanId, Long expectedVersion, String reason, CurrentUser actor) {
        LoadPlanJpaEntity header = lock(loadPlanId, expectedVersion);
        LoadPlan plan = store.load(header);
        List<DispatchLoadJpaEntity> vehicleLoads = loadsOf(plan);
        Instant now = clock.instant();
        plan.reopen(reason, loadingStarted(vehicleLoads), actor.userId(), now);
        PlanDetail detail = saved(plan, header, now);
        cancelLoads(vehicleLoads, "Load plan %s reopened: %s".formatted(plan.code(), reason.trim()),
                actor.userId(), now);
        return reread(plan.id(), detail);
    }

    @Override
    public PlanDetail cancel(UUID loadPlanId, Long expectedVersion, String reason, CurrentUser actor) {
        LoadPlanJpaEntity header = lock(loadPlanId, expectedVersion);
        LoadPlan plan = store.load(header);
        List<DispatchLoadJpaEntity> vehicleLoads = loadsOf(plan);
        Instant now = clock.instant();
        if (!plan.cancel(reason, loadingStarted(vehicleLoads), actor.userId(), now)) {
            return detail(header, plan);
        }
        PlanDetail detail = saved(plan, header, now);
        cancelLoads(vehicleLoads, "Load plan %s cancelled: %s".formatted(plan.code(), reason.trim()),
                actor.userId(), now);
        log.info("Load plan {} cancelled: {}", plan.code(), reason.trim());
        return reread(plan.id(), detail);
    }

    /** The detail again after the loads changed (their status shows on the vehicles). */
    private PlanDetail reread(UUID planId, PlanDetail fallback) {
        return plans.findById(planId).map(h -> detail(h, store.load(h))).orElse(fallback);
    }

    // ------------------------------------------------------------------ reconciliation (piece check)

    @Override
    @Transactional(readOnly = true)
    public Reconciliation reconciliation(UUID loadPlanId) {
        LoadPlanJpaEntity header = read(loadPlanId);
        LoadPlan plan = store.load(header);
        Set<UUID> placed = plan.placedPackageIds();
        Map<UUID, Boolean> counted = new LinkedHashMap<>();  // package id -> left behind
        for (LoadPlan.Member m : plan.members()) {
            if (m.active() || plan.status() == LoadPlanStatus.CANCELLED
                    && m.releaseReason() == LoadPlanReleaseReason.PLAN_CANCELLED) {
                counted.put(m.packageId(), false);
            } else if (m.releaseReason() == LoadPlanReleaseReason.LEFT_BEHIND) {
                counted.put(m.packageId(), true);
            }
        }
        Map<UUID, UUID> shipmentOf = new HashMap<>();
        plan.members().forEach(m -> shipmentOf.put(m.packageId(), m.shipmentId()));
        Set<UUID> shipmentIds = counted.keySet().stream().map(shipmentOf::get).collect(Collectors.toSet());
        Map<UUID, ShipmentJpaEntity> shipmentRows = shipments.findAllById(shipmentIds).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        Map<UUID, OrderSummary> orders = parcels.ordersOf(shipmentRows.values());
        Map<UUID, List<PackageJpaEntity>> sealedByShipment = packages.findByShipmentIdIn(shipmentIds).stream()
                .filter(p -> p.getStatus() != PackageStatus.VOIDED)
                .collect(Collectors.groupingBy(PackageJpaEntity::getShipmentId));

        Map<UUID, int[]> byShipment = new TreeMap<>();
        int[] total = new int[4];
        counted.forEach((packageId, leftBehind) -> {
            int[] t = byShipment.computeIfAbsent(shipmentOf.get(packageId), k -> new int[4]);
            boolean isPlaced = !leftBehind && placed.contains(packageId);
            for (int[] c : new int[][] {t, total}) {
                c[0]++;
                if (isPlaced) {
                    c[1]++;
                } else {
                    c[2]++;
                }
                if (leftBehind) {
                    c[3]++;
                }
            }
        });
        List<PieceCount> perShipment = new ArrayList<>();
        Map<UUID, int[]> byOrder = new LinkedHashMap<>();
        byShipment.forEach((shipmentId, c) -> {
            ShipmentJpaEntity s = shipmentRows.get(shipmentId);
            int all = sealedByShipment.getOrDefault(shipmentId, List.of()).size();
            perShipment.add(count(shipmentId.toString(), s == null ? null : s.getCode(), c, all - c[0]));
            if (s != null) {
                int[] o = byOrder.computeIfAbsent(s.getOrderId(), k -> new int[5]);
                for (int i = 0; i < 4; i++) {
                    o[i] += c[i];
                }
                o[4] += all - c[0];
            }
        });
        List<PieceCount> perOrder = new ArrayList<>();
        byOrder.forEach((orderId, c) -> {
            OrderSummary order = orders.get(orderId);
            perOrder.add(count(orderId.toString(), order == null ? null : order.orderNumber(), c, c[4]));
        });
        int notInPlan = perShipment.stream().mapToInt(PieceCount::notInPlan).sum();
        return new Reconciliation(count(plan.id().toString(), plan.code(), total, notInPlan), perShipment, perOrder,
                estimates.compare(plan.id()));
    }

    private static PieceCount count(String key, String code, int[] c, int notInPlan) {
        return new PieceCount(key, code, c[0], c[1], c[2], c[3], notInPlan, c[0] == c[1] + c[2]);
    }

    // ------------------------------------------------------------------ LoadPlanReleases

    @Override
    public void requireNotPlanned(Collection<UUID> packageIds) {
        if (packageIds.isEmpty()) {
            return;
        }
        List<LoadPlanPackageJpaEntity> held = members.findByPackageIdInAndActiveTrue(packageIds);
        if (!held.isEmpty()) {
            String codes = held.stream().map(LoadPlanPackageJpaEntity::getLoadPlanId).distinct()
                    .map(id -> plans.findById(id).map(LoadPlanJpaEntity::getCode).orElse("?")).sorted()
                    .collect(Collectors.joining(", "));
            throw new BusinessException(ErrorCode.PACKAGE_IN_LOAD_PLAN,
                    "The package is in load plan %s; take its shipment out of the plan first".formatted(codes));
        }
    }

    @Override
    public void releaseShipment(UUID shipmentId, Instant at) {
        List<UUID> planIds = members.findByShipmentIdAndActiveTrue(shipmentId).stream()
                .map(LoadPlanPackageJpaEntity::getLoadPlanId).distinct().sorted().toList();
        for (UUID planId : planIds) {
            LoadPlanJpaEntity header = plans.lockById(planId).orElseThrow();
            LoadPlan plan = store.load(header);
            List<DispatchLoadJpaEntity> vehicleLoads = loadsOf(plan);
            boolean reopened = plan.dropShipment(shipmentId, loadingStarted(vehicleLoads), at);
            plan.refreshUsage(cartons(parcels.of(plan.placedPackageIds())));
            store.save(plan, header, at);
            if (reopened) {
                cancelLoads(vehicleLoads, "Load plan %s back to PLANNED: an order in it was cancelled"
                        .formatted(plan.code()), null, at);
            }
            log.info("Shipment {} of a cancelled order left load plan {}{}", shipmentId, plan.code(),
                    reopened ? " (back to PLANNED)" : "");
        }
    }

    // ------------------------------------------------------------------ internals

    private LoadPlanJpaEntity read(UUID loadPlanId) {
        LoadPlanJpaEntity header = plans.findById(loadPlanId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_PLAN_NOT_FOUND));
        WarehouseScope.requireWarehouse(header.getWarehouseId());
        return header;
    }

    /** The plan's row lock, its warehouse in scope, and the version the change was decided on. */
    private LoadPlanJpaEntity lock(UUID loadPlanId, Long expectedVersion) {
        LoadPlanJpaEntity header = plans.lockById(loadPlanId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_PLAN_NOT_FOUND));
        WarehouseScope.requireWarehouse(header.getWarehouseId());
        if (expectedVersion == null || expectedVersion != header.getVersion()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, ("Load plan %s changed since version %s (now %d): "
                    + "reload it").formatted(header.getCode(), expectedVersion, header.getVersion()));
        }
        return header;
    }

    private PlanDetail saved(LoadPlan plan, LoadPlanJpaEntity header, Instant at) {
        LoadPlanJpaEntity after = claim(() -> store.save(plan, header, at));
        return detail(after, plan);
    }

    /** BR-09 under concurrency: the partial unique index is the referee; the loser gets the same 409 as the check. */
    private static LoadPlanJpaEntity claim(java.util.function.Supplier<LoadPlanJpaEntity> write) {
        try {
            return write.get();
        } catch (DataIntegrityViolationException raced) {
            String message = String.valueOf(raced.getMostSpecificCause().getMessage());
            if (message.contains("uk_load_plan_package_active")) {
                throw new BusinessException(ErrorCode.LOAD_PLAN_PACKAGE_TAKEN,
                        "A package of these shipments was just taken by another load plan (09 BR-09)");
            }
            if (message.contains("tg_load_plan_package_check")) {
                throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT,
                        "A shipment of the plan no longer waits for dispatch as a full truckload");
            }
            throw raced;
        }
    }

    /**
     * LP-4 / PACK-PARTIAL: an order that does not allow partial shipments leaves whole with this plan - nothing of it
     * left behind on the tray, and every unit it still wants placed in this plan: not packed yet, or packed in a
     * shipment in no plan or in another plan, is a partial shipment too (the rule of the parcel loads,
     * {@link PartialShipments}). Orders whose parcels carry no contents (packed before contents were recorded) are
     * not counted, as at the parcel loads.
     */
    private void requirePartialAllowed(LoadPlan plan, Map<UUID, LoadPlanParcels.Parcel> held) {
        Map<UUID, OrderSummary> refused = new TreeMap<>();
        for (UUID id : plan.unplacedPackageIds()) {
            OrderSummary order = held.get(id).order();
            if (order != null && !allowsPartial(order)) {
                refused.put(order.orderId(), order);
            }
        }
        Set<UUID> placed = plan.placedPackageIds();
        Map<UUID, Integer> leaving = new HashMap<>();
        Map<UUID, OrderSummary> ordersInPlan = new HashMap<>();
        held.values().forEach(p -> {
            if (p.order() != null) {
                ordersInPlan.put(p.order().orderId(), p.order());
            }
        });
        Map<UUID, UUID> orderOfPackage = new HashMap<>();
        held.values().forEach(p -> orderOfPackage.put(p.packageId(), p.order() == null ? null : p.order().orderId()));
        Set<UUID> recorded = new HashSet<>();
        for (var line : packageLines.findByPackageIdIn(held.keySet())) {
            UUID orderId = orderOfPackage.get(line.getPackageId());
            if (orderId != null) {
                recorded.add(orderId);
                if (placed.contains(line.getPackageId())) {
                    leaving.merge(orderId, line.getQuantity(), Integer::sum);
                }
            }
        }
        ordersInPlan.forEach((orderId, order) -> {
            if (!allowsPartial(order) && recorded.contains(orderId)
                    && leaving.getOrDefault(orderId, 0) < PartialShipments.outstandingUnits(order)) {
                refused.put(orderId, order);
            }
        });
        if (!refused.isEmpty()) {
            List<String> numbers = refused.values().stream().map(OrderSummary::orderNumber).sorted().toList();
            throw new BusinessException(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED, ("Order(s) %s do not allow "
                    + "partial shipments, and this plan does not take all of them: place every package of the order in "
                    + "this plan, or ask Sales to allow partial shipment (LP-4)").formatted(String.join(", ", numbers)),
                    numbers.stream().map(n -> FieldError.of("orderNumber", n)).toList());
        }
    }

    private static boolean allowsPartial(OrderSummary order) {
        return PartialShipments.mayShipInParts(order);
    }

    private List<DispatchLoadJpaEntity> loadsOf(LoadPlan plan) {
        List<UUID> vehicleIds = plan.vehicles().stream().map(LoadPlan.Vehicle::id).toList();
        return vehicleIds.isEmpty() ? List.of() : loads.findByLoadPlanVehicleIdIn(vehicleIds);
    }

    /** BR-14: a vehicle of the plan is being loaded, or left. */
    private static boolean loadingStarted(List<DispatchLoadJpaEntity> vehicleLoads) {
        return vehicleLoads.stream().anyMatch(l -> LOADING.contains(l.getStatus()));
    }

    /** LP-11: the vehicles' loads not loading yet - booked ones included - are cancelled with the reason. */
    private void cancelLoads(List<DispatchLoadJpaEntity> vehicleLoads, String reason, UUID by, Instant at) {
        vehicleLoads.stream().filter(l -> CANCELLABLE.contains(l.getStatus())).map(DispatchLoadJpaEntity::getId)
                .sorted().forEach(id -> loads.lockById(id).ifPresent(header -> {
                    DispatchLoad load = loadStore.load(header);
                    if (CANCELLABLE.contains(load.status()) && load.cancel(reason, by, at)) {
                        loadStore.save(load, header);
                        log.info("Load {} cancelled: {}", load.code(), reason);
                    }
                }));
    }

    private static Map<UUID, CargoPackage> cartons(Map<UUID, LoadPlanParcels.Parcel> described) {
        Map<UUID, CargoPackage> out = new LinkedHashMap<>();
        described.forEach((id, p) -> {
            if (p.carton() != null) {
                out.put(id, p.carton());
            }
        });
        return out;
    }

    private static VehicleSpec spec(VehicleTypeJpaEntity t) {
        return new VehicleSpec(t.getId(), t.getCode(), VehicleKind.valueOf(t.getKind()), t.getInnerLengthMm(),
                t.getInnerWidthMm(), t.getInnerHeightMm(), t.getMaxPayloadKg(), t.getAvailableCount(),
                t.getGussetMm(), t.getDoorWidthMm(), t.getDoorHeightMm());
    }

    private static Map<String, Object> meta(LoadPlanMeta m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("algorithm", m.algorithm());
        out.put("minVehicles", m.minVehicles());
        out.put("setsTotal", m.setsTotal());
        out.put("budgetExhausted", m.budgetExhausted());
        out.put("elapsedMillis", m.elapsedMillis());
        out.put("bestFoundMillis", m.bestFoundMillis());
        out.put("kitWarnings", m.kitWarnings().stream().map(w -> w.type() + ":" + w.kitName()).toList());
        return out;
    }

    private static SelectionMode mode(String raw) {
        if (raw == null || raw.isBlank()) {
            return SelectionMode.SINGLE_TYPE;
        }
        try {
            return SelectionMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid selection mode",
                    List.of(new FieldError("selectionMode", "SINGLE_TYPE or FEWEST_VEHICLES", "LOAD_PLAN_MODE_INVALID")));
        }
    }

    private static LoadPlanStatus status(String raw) {
        try {
            return LoadPlanStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid status",
                    List.of(new FieldError("status", "DRAFT, PLANNED, CONFIRMED, DISPATCHED or CANCELLED",
                            "LOAD_PLAN_STATUS_INVALID")));
        }
    }

    private static ParcelView parcelView(LoadPlanParcels.Parcel p, Integer seq, String unplacedReason) {
        CargoPackage c = p.carton();
        var kit = p.row().getKit();
        return new ParcelView(p.packageId(), p.row().getLabelCode(), p.row().getShipmentId(),
                p.shipment() == null ? null : p.shipment().getCode(), p.partName(), c == null ? 0 : c.lengthMm(),
                c == null ? 0 : c.widthMm(), c == null ? 0 : c.heightMm(), p.row().getWeightKg(), p.row().isRotatable(),
                kit == null ? null : kit.groupId(), kit == null ? null : kit.partNo(),
                kit == null ? null : kit.partCount(), seq, unplacedReason);
    }

    private PlanDetail detail(LoadPlanJpaEntity header, LoadPlan plan) {
        boolean cancelled = plan.status() == LoadPlanStatus.CANCELLED;
        List<LoadPlan.Member> shown = plan.members().stream().filter(m -> m.active()
                || cancelled && m.releaseReason() == LoadPlanReleaseReason.PLAN_CANCELLED).toList();
        Map<UUID, Integer> seqOf = new HashMap<>();
        Map<Integer, Integer> countOf = new HashMap<>();
        for (LoadPlan.Vehicle v : plan.activeVehicles()) {
            v.placements().forEach(p -> seqOf.put(p.packageId(), v.seq()));
            countOf.put(v.seq(), v.placements().size());
        }
        Map<UUID, LoadPlanParcels.Parcel> described = parcels.of(shown.stream().map(LoadPlan.Member::packageId)
                .toList());
        List<ParcelView> parcelViews = new ArrayList<>();
        Map<UUID, int[]> perShipment = new LinkedHashMap<>();
        for (LoadPlan.Member m : shown) {
            LoadPlanParcels.Parcel p = described.get(m.packageId());
            if (p == null) {
                continue;
            }
            parcelViews.add(parcelView(p, seqOf.get(m.packageId()),
                    m.unplacedReason() == null ? null : m.unplacedReason().name()));
            perShipment.computeIfAbsent(m.shipmentId(), k -> new int[1])[0]++;
        }
        Map<UUID, ShipmentJpaEntity> shipmentRows = shipments.findAllById(perShipment.keySet()).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        Map<UUID, OrderSummary> orders = parcels.ordersOf(shipmentRows.values());
        List<ShipmentInPlan> shipmentViews = new ArrayList<>();
        perShipment.forEach((id, n) -> {
            ShipmentJpaEntity s = shipmentRows.get(id);
            OrderSummary order = s == null ? null : orders.get(s.getOrderId());
            shipmentViews.add(new ShipmentInPlan(id, s == null ? null : s.getCode(), s == null ? null : s.getOrderId(),
                    order == null ? null : order.orderNumber(), allowsPartial(order), n[0]));
        });
        Map<UUID, VehicleTypeJpaEntity> typeRows = vehicleTypes.findAllById(plan.vehicleTypeIds()).stream()
                .collect(Collectors.toMap(VehicleTypeJpaEntity::getId, Function.identity()));
        List<VehicleTypeChoice> typeViews = plan.vehicleTypeIds().stream().map(typeRows::get).filter(Objects::nonNull)
                .map(t -> new VehicleTypeChoice(t.getId(), t.getCode(), t.getName(), t.getKind(),
                        t.getAvailableCount(), t.getStatus())).toList();
        Map<UUID, DispatchLoadJpaEntity> loadOf = new HashMap<>();
        loadsOf(plan).stream().sorted(Comparator.comparing(DispatchLoadJpaEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(l -> loadOf.merge(l.getLoadPlanVehicleId(), l, (a, b) ->
                        a.getStatus() != LoadStatus.CANCELLED && b.getStatus() == LoadStatus.CANCELLED ? a : b));
        List<VehicleSummary> vehicleViews = plan.activeVehicles().stream().map(v -> {
            DispatchLoadJpaEntity l = loadOf.get(v.id());
            return new VehicleSummary(v.seq(), v.id(), v.spec().typeId(), v.spec().code(),
                    countOf.getOrDefault(v.seq(), 0), v.usedVolumeMm3(), v.usedWeightKg(), v.volumeFillPct(),
                    v.weightFillPct(), l == null ? null : new LoadRef(l.getId(), l.getCode(), l.getStatus().name()),
                    v.needsRecalculation());
        }).toList();
        LoadPlan.Snapshot s = plan.snapshot();
        Acknowledgement ack = s.acknowledgedAt() == null ? null
                : new Acknowledgement(s.acknowledgedAt(), s.acknowledgedBy(), s.acknowledgeNote());
        return new PlanDetail(plan.id(), plan.code(), plan.warehouseId(), s.sourceType(), plan.mode().name(),
                plan.status().name(), s.note(), typeViews, shipmentViews, vehicleViews, parcelViews,
                plan.activePackageIds().size(), plan.placedPackageIds().size(), plan.unplacedPackageIds().size(), ack,
                s.calculatedAt(), header.getSolverMeta(), s.confirmedAt(), s.reopenedAt(), s.reopenReason(),
                s.dispatchedAt(), s.cancelledAt(), s.cancelReason(), header.getCreatedAt(), header.getVersion());
    }
}
