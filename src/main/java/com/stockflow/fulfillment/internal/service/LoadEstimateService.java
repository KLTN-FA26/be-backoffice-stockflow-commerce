package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanMeta;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanRequest;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanResult;
import com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanner;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layouts;
import com.stockflow.fulfillment.internal.domain.loadplanning.KitDistribution;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleKind;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec;
import com.stockflow.fulfillment.internal.entity.LoadEstimateJpaEntity;
import com.stockflow.fulfillment.internal.entity.VehicleTypeJpaEntity;
import com.stockflow.fulfillment.internal.repository.LoadEstimateJpaRepository;
import com.stockflow.fulfillment.internal.repository.VehicleTypeJpaRepository;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.PackagingRequest;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpecResolver;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/**
 * See {@link LoadEstimates}. Three steps, and only the first and last touch the database:
 *
 * <ol>
 *   <li><b>Read</b> (one read-only transaction): the order, its eligibility and warehouse scope, what is still to
 *       ship per line, the vehicle types, every line's packaging through the resolver. Lines with nothing to measure
 *       are set aside (LP2-3); when no line is left, 422.</li>
 *   <li><b>Plan</b>, outside any transaction: the solver may take its whole budget (LP2-10, synchronous), and a
 *       database connection held for those seconds would be a connection the warehouse floor does not have.</li>
 *   <li><b>Store</b> (one short transaction): the order's row lock is taken and the order checked again - still in a
 *       state that may be estimated, same warehouse, same units left to ship - so an order cancelled, shipped or
 *       packed further while it was planned is answered 409 and nothing is saved. Then, under an advisory lock on the
 *       source, the current estimate is superseded and the new one inserted, so the one-current index never sees
 *       two. The answer is read back as stored.</li>
 * </ol>
 *
 * <p>The seed is fixed: the same order, vehicle types and mode give the same estimate, unless the budget ran out
 * ({@code meta.budgetExhausted}), when the plan is the best found so far.</p>
 */
@Service
class LoadEstimateService implements LoadEstimates {

    /** The planner's seed for every estimate: 0 draws exactly what the lead's Python solver drew. */
    static final long SEED = 0L;

    /**
     * Decimals of a trial carton's weight, the line's total weight shared out over its boxes: enough that a gram
     * shared by 3 000 boxes is still a positive weight, as the planner requires.
     */
    static final int TRIAL_WEIGHT_SCALE = 9;

    /**
     * Where an estimate makes sense: CONFIRMED or AWAITING_STOCK (the release dialog estimates before releasing, design
     * v2 §2, with the warehouse it is about to choose - an order waiting for stock is released from it too, 4A-6),
     * released to the warehouse, and partly fulfilled (its remaining units only). DRAFT, PENDING_PAYMENT, ON_HOLD and
     * everything from SHIPPED on are refused.
     */
    private static final Set<OrderStatus> ESTIMABLE = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.AWAITING_STOCK,
            OrderStatus.READY_TO_FULFILL, OrderStatus.PICKING, OrderStatus.PACKED, OrderStatus.PARTIALLY_FULFILLED);

    private static final TypeReference<List<EstimateLine>> LINES = new TypeReference<>() { };
    private static final TypeReference<List<EstimateWarning>> WARNINGS = new TypeReference<>() { };
    private static final TypeReference<List<MissingLine>> MISSING = new TypeReference<>() { };
    private static final TypeReference<List<Layout>> VEHICLES = new TypeReference<>() { };
    private static final TypeReference<List<Layout.UnplacedLine>> UNPLACED = new TypeReference<>() { };
    private static final TypeReference<List<UUID>> IDS = new TypeReference<>() { };

    private final LoadEstimateJpaRepository estimates;
    private final VehicleTypeJpaRepository vehicleTypes;
    private final OrderService orders;
    private final PackagingSpecResolver packaging;
    /** The LPN cartons allocated to an order's lines (09 §4.0 step 2). */
    private final InventoryService inventory;
    private final ObjectMapper json;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;
    private final Duration budget;
    private final Duration wallLimit;
    private final LoadPlanner planner = new LoadPlanner();

    LoadEstimateService(LoadEstimateJpaRepository estimates, VehicleTypeJpaRepository vehicleTypes,
                        OrderService orders, PackagingSpecResolver packaging,
                        InventoryService inventory, ObjectMapper json, PlatformTransactionManager transactionManager,
                        @Value("${stockflow.fulfillment.load-planning.solver-budget:PT5S}") Duration budget,
                        @Value("${stockflow.fulfillment.load-planning.solver-wall-limit:PT12S}") Duration wallLimit) {
        this.estimates = estimates;
        this.vehicleTypes = vehicleTypes;
        this.orders = orders;
        this.packaging = packaging;
        this.inventory = inventory;
        this.json = json;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.writes = new TransactionTemplate(transactionManager);
        if (budget == null || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("stockflow.fulfillment.load-planning.solver-budget must be positive");
        }
        if (wallLimit == null || wallLimit.isNegative() || wallLimit.isZero()) {
            throw new IllegalArgumentException(
                    "stockflow.fulfillment.load-planning.solver-wall-limit must be positive");
        }
        this.budget = budget;
        this.wallLimit = wallLimit;
    }

    /** What the read step hands the planner, and what the store step needs from it. */
    private record Prepared(UUID warehouseId, Map<UUID, Integer> remaining, List<UUID> vehicleTypeIds,
                            SelectionMode mode, LoadPlanRequest request, EstimateCartons.Built built,
                            List<EstimateLine> lines, List<MissingLine> missing, List<EstimateWarning> warnings) { }

    @Override
    public LoadEstimateView create(CreateEstimate command) {
        Source source = onlySource(command.sources());
        SelectionMode mode = command.selectionMode() == null ? SelectionMode.SINGLE_TYPE : command.selectionMode();
        List<UUID> fillLines = command.fillOrderLineIds() == null ? List.of() : command.fillOrderLineIds();
        if (!fillLines.isEmpty() && mode != SelectionMode.PRIORITY_FILL) {
            // 09 §3: the main / filler split is the input of "Ưu tiên + hàng chèn" only.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Filler lines are chosen only for PRIORITY_FILL",
                    List.of(new FieldError("fillOrderLineIds", "only with PRIORITY_FILL", "FILL_NOT_IN_MODE")));
        }
        Prepared prepared = reads.execute(s -> prepare(source.id(), command.warehouseId(), command.vehicleTypeIds(),
                mode, fillLines));

        LoadPlanResult result = planner.plan(prepared.request());
        Layouts.Mapped mapped = Layouts.of(prepared.request(), result,
                carton -> prepared.built().labels().get(carton.ref()));

        ObjectNode layout = json.createObjectNode();
        layout.set("vehicles", json.valueToTree(mapped.vehicles()));
        layout.set("unplaced", json.valueToTree(mapped.unplaced()));
        layout.set("meta", json.valueToTree(meta(result)));

        UUID id = writes.execute(s -> {
            recheck(source.id(), prepared);
            estimates.lockSource(source.type() + ":" + source.id());
            List<LoadEstimateJpaEntity> current = estimates.findAllBySourceTypeAndSourceIdAndStatus(
                    source.type().name(), source.id(), LoadEstimateJpaEntity.CURRENT);
            current.forEach(LoadEstimateJpaEntity::supersede);
            // Hibernate flushes inserts before updates: the old row must leave CURRENT first.
            estimates.saveAllAndFlush(current);
            LoadEstimateJpaEntity row = estimates.saveAndFlush(new LoadEstimateJpaEntity(Identifiers.newId(),
                    source.type().name(), source.id(), prepared.warehouseId(),
                    json.valueToTree(prepared.vehicleTypeIds()), prepared.mode().name(),
                    json.valueToTree(prepared.lines()), layout, json.valueToTree(prepared.warnings()),
                    json.valueToTree(prepared.missing())));
            return row.getId();
        });
        // Read back as stored, so the answer is exactly what GET answers later (numbers as the JSON column keeps them).
        return reads.execute(s -> view(estimates.findById(id).orElseThrow()));
    }

    /**
     * LP2-9: the trial. Only the vehicle types are read; the planner then runs outside any transaction and its answer
     * is mapped and returned - there is no write step, so nothing can be left behind (no estimate row, no event).
     * WF4-TRIAL-EST: each box weighs its line's total weight over its quantity and the payload holds (BR-10), and the
     * mode is any of the docs' modes. A line's boxes share the line's {@code sourceRef}, which is what
     * {@link SelectionMode#KEEP_LINES} keeps together; the sheet's "Hàng chèn" flag is read only by
     * {@link SelectionMode#PRIORITY_FILL} (docs 09 §3: the main / fill split is that mode's input only).
     */
    @Override
    public TrialView trial(TrialEstimate command) {
        List<TrialLine> lines = command.lines();
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A trial estimate needs at least one line",
                    List.of(new FieldError("lines", "must hold at least one line", "NotEmpty")));
        }
        long boxes = 0;
        for (int i = 0; i < lines.size(); i++) {
            TrialLine l = lines.get(i);
            if (l == null || l.lengthMm() <= 0 || l.widthMm() <= 0 || l.heightMm() <= 0 || l.quantity() <= 0
                    || l.totalWeightKg() == null || l.totalWeightKg().signum() <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Line " + (i + 1)
                        + " needs positive sizes, quantity and weight", List.of(new FieldError("lines[" + i + "]",
                        "sizes, quantity and total weight must be positive", "Positive")));
            }
            boxes += l.quantity();
        }
        if (boxes > EstimateCartons.MAX_BOXES) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_TOO_MANY_CARTONS,
                    "The list makes " + boxes + " boxes; an estimate places at most " + EstimateCartons.MAX_BOXES);
        }
        SelectionMode mode = command.selectionMode() == null ? SelectionMode.SINGLE_TYPE : command.selectionMode();
        List<VehicleSpec> types = reads.execute(s -> vehicleSpecs(command.vehicleTypeIds()));

        List<CargoPackage> cartons = new ArrayList<>();
        Map<String, String> labels = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            TrialLine l = lines.get(i);
            String group = l.group() == null || l.group().isBlank() ? null : l.group().strip();
            String name = l.name() != null && !l.name().isBlank() ? l.name().strip()
                    : group != null ? group : "Line " + (i + 1);
            String lineRef = "T" + (i + 1);
            BigDecimal weight = boxWeight(l.totalWeightKg(), l.quantity());
            boolean fill = mode == SelectionMode.PRIORITY_FILL && l.fill();
            for (int n = 1; n <= l.quantity(); n++) {
                String ref = lineRef + "#" + n;
                cartons.add(new CargoPackage(ref, lineRef, l.lengthMm(), l.widthMm(), l.heightMm(), weight,
                        l.rotatable(), name, null, group, fill));
                labels.put(ref, name);
            }
        }
        LoadPlanRequest request = new LoadPlanRequest(cartons, types, mode, List.of(), KitDistribution.AUTO, false,
                null, SEED, budget, wallLimit, false);
        LoadPlanResult result = planner.plan(request);
        Layouts.Mapped mapped = Layouts.of(request, result, c -> labels.get(c.ref()));
        return new TrialView(mode, types.stream().map(VehicleSpec::typeId).toList(), mapped.vehicles(),
                mapped.unplaced(), meta(result));
    }

    /** One box of a trial line: the line's total weight shared out evenly, never rounded down to nothing. */
    static BigDecimal boxWeight(BigDecimal totalWeightKg, int quantity) {
        BigDecimal each = totalWeightKg.divide(BigDecimal.valueOf(quantity), TRIAL_WEIGHT_SCALE, RoundingMode.HALF_UP);
        return each.signum() > 0 ? each : BigDecimal.ONE.movePointLeft(TRIAL_WEIGHT_SCALE);
    }

    /**
     * Under the order's row lock: the order may still be estimated, in the same warehouse, with the same units left
     * to ship as when it was read. Otherwise the plan answers a question nobody asks any more: 409, nothing saved.
     */
    private void recheck(UUID orderId, Prepared prepared) {
        OrderStatus status = orders.lockedStatus(orderId).orElse(null);
        OrderSummary order = status == null ? null : orders.findById(orderId).orElse(null);
        if (order == null || !ESTIMABLE.contains(status)) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE, "The order became " + status
                    + " while it was estimated; nothing was saved");
        }
        if (order.warehouseId() != null && !order.warehouseId().equals(prepared.warehouseId())
                || !remaining(order).equals(prepared.remaining())) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE, "Order " + order.orderNumber()
                    + " changed while it was estimated (released elsewhere, shipped or closed in part); estimate again");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public LoadEstimateView get(UUID id) {
        LoadEstimateJpaEntity row = estimates.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_ESTIMATE_NOT_FOUND));
        WarehouseScope.requireWarehouse(row.getWarehouseId());
        return view(row);
    }

    @Override
    @Transactional(readOnly = true)
    public LoadEstimateView current(SourceType sourceType, UUID sourceId) {
        if (sourceType == null || sourceId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "sourceType and sourceId are required",
                    List.of(new FieldError(sourceType == null ? "sourceType" : "sourceId", "is required",
                            "NotNull")));
        }
        LoadEstimateJpaEntity row = estimates.findBySourceTypeAndSourceIdAndStatus(sourceType.name(), sourceId,
                        LoadEstimateJpaEntity.CURRENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_ESTIMATE_NOT_FOUND,
                        "No current load estimate of " + sourceType + " " + sourceId));
        WarehouseScope.requireWarehouse(row.getWarehouseId());
        return view(row);
    }

    // ------------------------------------------------------------------ read

    private static Source onlySource(List<Source> sources) {
        if (sources == null || sources.size() != 1 || sources.getFirst() == null
                || sources.getFirst().type() == null || sources.getFirst().id() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Exactly one source is estimated at a time",
                    List.of(new FieldError("sources", "must hold exactly one source with its type and id", "Size")));
        }
        return sources.getFirst();
    }

    private Prepared prepare(UUID orderId, UUID requestedWarehouse, List<UUID> requestedTypes, SelectionMode mode,
                             List<UUID> fillOrderLineIds) {
        OrderSummary order = orders.findById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
        UUID warehouseId = warehouseOf(order, requestedWarehouse);
        WarehouseScope.requireWarehouse(warehouseId);
        if (!ESTIMABLE.contains(order.status())) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE, "Order " + order.orderNumber()
                    + " is " + order.status() + "; an estimate is made from confirmation until everything shipped");
        }
        Map<UUID, Integer> remaining = remaining(order);
        if (remaining.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE, "Order " + order.orderNumber()
                    + " has nothing left to ship");
        }
        List<VehicleSpec> types = vehicleSpecs(requestedTypes);

        List<OrderSummary.LineSummary> lines = order.lines().stream()
                .filter(l -> remaining.containsKey(l.lineId())).toList();
        List<PackagingSpec> specs = packaging.resolveAll(lines.stream()
                .map(l -> new PackagingRequest(new Sku(l.sku()), remaining.get(l.lineId()))).toList());

        Map<UUID, Map<String, Integer>> allocated = allocatedCartons(lines);

        List<MissingLine> missing = new ArrayList<>();
        List<EstimateCartons.Line> resolved = new ArrayList<>();
        List<EstimateWarning> warnings = new ArrayList<>();
        List<OrderSummary.LineSummary> partlyAllocated = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            OrderSummary.LineSummary line = lines.get(i);
            PackagingSpec spec = specs.get(i);
            spec.warnings().forEach(w -> warnings.add(new EstimateWarning(line.lineId(), spec.sku(), w.code().name(),
                    w.unitOfMeasure(), w.fields(), w.message())));
            List<String> absent = missingFields(spec);
            if (!absent.isEmpty()) {
                // LP2-3: only this line is blocked; the screen links to its item.
                missing.add(new MissingLine(line.lineId(), spec.sku(), spec.inventoryItemId(), absent));
                continue;
            }
            Map<String, Integer> lpns = allocated.getOrDefault(line.lineId(), Map.of());
            int allocatedUnits = lpns.values().stream().mapToInt(Integer::intValue).sum();
            int left = remaining.get(line.lineId());
            if (lpns.isEmpty() || spec.kitParts() > 1 || allocatedUnits > left) {
                // Nothing allocated yet (or a kit, or holds the line no longer needs all of): from the packaging.
                resolved.add(new EstimateCartons.Line(line.lineId(), spec));
            } else {
                resolved.add(new EstimateCartons.Line(line.lineId(), spec, List.copyOf(lpns.keySet()), null));
                if (allocatedUnits < left) {
                    partlyAllocated.add(line);
                }
            }
        }
        if (!partlyAllocated.isEmpty()) {
            // The units of a line not allocated yet (a later delivery batch) are counted from the packaging.
            List<PackagingSpec> rests = packaging.resolveAll(partlyAllocated.stream()
                    .map(l -> new PackagingRequest(new Sku(l.sku()), remaining.get(l.lineId())
                            - allocated.get(l.lineId()).values().stream().mapToInt(Integer::intValue).sum()))
                    .toList());
            Map<UUID, PackagingSpec> restOf = new HashMap<>();
            for (int i = 0; i < partlyAllocated.size(); i++) {
                restOf.put(partlyAllocated.get(i).lineId(), rests.get(i));
            }
            resolved.replaceAll(l -> restOf.containsKey(l.orderLineId())
                    ? new EstimateCartons.Line(l.orderLineId(), l.spec(), l.allocatedLpns(), restOf.get(l.orderLineId()))
                    : l);
        }
        List<EstimateLine> estimateLines = resolved.stream().map(LoadEstimateService::line).toList();
        if (resolved.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_PACKAGING_MISSING, "No line of order "
                    + order.orderNumber() + " has an item with a size and weight; complete the item(s) and estimate"
                    + " again", missing.stream().map(LoadEstimateService::missingField).toList());
        }
        EstimateCartons.Built built = EstimateCartons.build(orderId.toString(), resolved);
        Set<String> fill = fillLines(order, fillOrderLineIds);
        List<CargoPackage> cartons = built.cartons().stream()
                .map(c -> fill.contains(c.sourceRef()) ? c.asFill() : c).toList();
        LoadPlanRequest request = new LoadPlanRequest(cartons, types, mode, built.kits(),
                KitDistribution.AUTO, false, null, SEED, budget, wallLimit);
        return new Prepared(warehouseId, remaining, types.stream().map(VehicleSpec::typeId).toList(), mode, request,
                built, estimateLines, List.copyOf(missing), warnings);
    }

    /**
     * The warehouse the estimate belongs to: the order's shipping warehouse - the one it was released to, or the one
     * Sales chose while it is CONFIRMED. Docs 09 §4.0 step 1 (owner answer RTM-ESTWH, 18/11: "chặn như docs, phải chọn
     * kho trước"): an order with none chosen yet is not estimated ({@code LOAD_ESTIMATE_WAREHOUSE_NOT_CHOSEN}), unless
     * it may not be estimated at all ({@code LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE}). A warehouse the caller names must be
     * that one.
     */
    private UUID warehouseOf(OrderSummary order, UUID requested) {
        UUID shipping = order.warehouseId() != null ? order.warehouseId()
                : orders.shippingWarehouseId(order.orderId()).orElse(null);
        if (shipping == null) {
            if (!ESTIMABLE.contains(order.status())) {
                throw new BusinessException(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE, "Order " + order.orderNumber()
                        + " is " + order.status() + "; an estimate is made from confirmation until everything shipped");
            }
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_WAREHOUSE_NOT_CHOSEN, "Order " + order.orderNumber()
                    + " has no shipping warehouse yet: choose it before estimating its load");
        }
        if (requested != null && !requested.equals(shipping)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Order " + order.orderNumber()
                    + " ships from another warehouse", List.of(new FieldError("warehouseId",
                    "must be the order's shipping warehouse, or left out", "WarehouseMismatch")));
        }
        return shipping;
    }

    /**
     * Units still to ship per line: ordered, less what went out in shipments not returned, less what was closed out
     * of the order (#150). Lines with nothing left are absent: a PACK-EARLY order whose stock already shipped does
     * not put that stock back on the truck.
     */
    private static Map<UUID, Integer> remaining(OrderSummary order) {
        Map<UUID, OrderSummary.LineProgress> progress = new HashMap<>();
        if (order.lifecycle() != null) {
            order.lifecycle().lines().forEach(p -> progress.put(p.lineId(), p));
        }
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (OrderSummary.LineSummary line : order.lines()) {
            OrderSummary.LineProgress p = progress.get(line.lineId());
            int left = line.quantity() - (p == null ? 0 : p.shippedQuantity() + p.cancelledQuantity());
            if (left > 0) {
                out.put(line.lineId(), left);
            }
        }
        return out;
    }

    /**
     * The requested types in the order given (duplicates dropped), or every active type by code. A type the plan may
     * not use is refused rather than silently left out: the warehouse manager chose it.
     */
    private List<VehicleSpec> vehicleSpecs(List<UUID> requested) {
        List<VehicleTypeJpaEntity> rows = new ArrayList<>();
        if (requested == null || requested.isEmpty()) {
            vehicleTypes.findAll(Sort.by("code")).stream().filter(VehicleTypeJpaEntity::isActive).forEach(rows::add);
            if (rows.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "No vehicle type is in use",
                        List.of(new FieldError("vehicleTypeIds", "no active vehicle type to estimate with",
                                "VEHICLE_TYPE_INACTIVE")));
            }
        } else {
            for (UUID id : new LinkedHashSet<>(requested)) {
                VehicleTypeJpaEntity row = vehicleTypes.findById(id).orElseThrow(() -> new BusinessException(
                        ErrorCode.VEHICLE_TYPE_NOT_FOUND, "No vehicle type " + id));
                if (!row.isActive()) {
                    throw new BusinessException(ErrorCode.VEHICLE_TYPE_INACTIVE,
                            "Vehicle type " + row.getCode() + " is no longer in use");
                }
                rows.add(row);
            }
        }
        return rows.stream().map(LoadEstimateService::spec).toList();
    }

    private static VehicleSpec spec(VehicleTypeJpaEntity t) {
        return new VehicleSpec(t.getId(), t.getCode(), VehicleKind.valueOf(t.getKind()), t.getInnerLengthMm(),
                t.getInnerWidthMm(), t.getInnerHeightMm(), t.getMaxPayloadKg(), t.getAvailableCount(),
                t.getGussetMm(), t.getDoorWidthMm(), t.getDoorHeightMm());
    }

    /**
     * The carton fields that keep a line from being measured (LP2-3), empty when it can be: the resolver's list when it
     * found no level (all four when it named none: no carton at all), or the carton's own zero sizes or weight (nothing is defaulted, and the planner refuses a zero).
     * Only the fields really missing are named.
     */
    static List<String> missingFields(PackagingSpec spec) {
        if (!spec.resolved() || spec.carton() == null) {
            List<String> named = spec.missing() == null || spec.missing().fields() == null ? List.of()
                    : spec.missing().fields();
            return named.isEmpty() ? List.of("lengthCm", "widthCm", "heightCm", "weightKg") : List.copyOf(named);
        }
        PackagingSpec.CartonSpec c = spec.carton();
        List<String> out = new ArrayList<>();
        if (c.lengthMm() <= 0) {
            out.add("lengthCm");
        }
        if (c.widthMm() <= 0) {
            out.add("widthCm");
        }
        if (c.heightMm() <= 0) {
            out.add("heightCm");
        }
        if (c.weightKg() == null || c.weightKg().signum() <= 0) {
            out.add("weightKg");
        }
        return out;
    }

    /**
     * One blocked line in a 422, pointing at its inventory item (LP2-3): {@code field} is
     * {@code inventoryItems[<itemId>]}, so the screen links to the item, where its carton unit is declared; the message
     * names the SKU, the order line and the carton's empty fields (all four when it has no carton at all, see
     * {@link #missingFields}). Only the carton is measured (V20261215000100, kltn-docs 01 BR-03).
     */
    private static FieldError missingField(MissingLine line) {
        return new FieldError("inventoryItems[" + line.inventoryItemId() + "]", "SKU " + line.sku() + " (order line "
                + line.orderLineId() + ") has no " + String.join(", ", line.missingFields()) + " on its carton unit",
                "PackagingMissing");
    }

    /**
     * RTM-LPGROUP (19/11): the order lines ticked as filler cargo ("hàng chèn"), by the id an estimate carton carries as
     * its {@code sourceRef}. A line the order does not have is refused.
     */
    private static Set<String> fillLines(OrderSummary order, List<UUID> fillOrderLineIds) {
        if (fillOrderLineIds == null || fillOrderLineIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> lines = new HashSet<>();
        order.lines().forEach(l -> lines.add(l.lineId()));
        List<FieldError> problems = fillOrderLineIds.stream().filter(id -> !lines.contains(id))
                .map(id -> new FieldError(id.toString(), "not a line of this order", "FILL_LINE_UNKNOWN")).toList();
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Filler lines are lines of the order", problems);
        }
        Set<String> out = new HashSet<>();
        fillOrderLineIds.forEach(id -> out.add(id.toString()));
        return out;
    }

    /**
     * The stored line: the packaging of what it has left to ship, with as many cartons as it makes - one per allocated
     * LPN plus those of the rest (whose remainder carton, if any, is the line's).
     */
    private static EstimateLine line(EstimateCartons.Line l) {
        if (l.allocatedLpns().isEmpty()) {
            return line(l.orderLineId(), l.spec());
        }
        PackagingSpec spec = l.spec();
        PackagingSpec.CartonSpec c = spec.carton();
        PackagingSpec.PartialCarton rest = l.rest() == null ? null : l.rest().partialCarton();
        return new EstimateLine(l.orderLineId(), spec.sku(), spec.inventoryItemId(), spec.quantity(),
                spec.level().name(), spec.unitOfMeasure(), spec.factor(), l.cartons(),
                rest == null ? null : new Partial(rest.units(), rest.weightKg()),
                new Layout.Dims(c.lengthMm(), c.widthMm(), c.heightMm()), c.weightKg(), c.rotatable(),
                Math.max(1, spec.kitParts()));
    }

    /**
     * 09 §4.0 step 2, 07 BR-03: the LPN cartons allocated to each line and still on the shelf or staged (a live hold on
     * a carton), with the units each holds. A carton already picked is no longer held; its units count as the line's
     * remaining quantity does.
     */
    private Map<UUID, Map<String, Integer>> allocatedCartons(List<OrderSummary.LineSummary> lines) {
        List<UUID> ids = lines.stream().flatMap(l -> l.reservationIds().stream()).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, HoldView> holds = new HashMap<>();
        inventory.holds(ids).forEach(h -> holds.put(h.reservationId(), h));
        Map<UUID, Map<String, Integer>> out = new HashMap<>();
        for (OrderSummary.LineSummary line : lines) {
            Map<String, Integer> lpns = new LinkedHashMap<>();
            for (UUID id : line.reservationIds()) {
                HoldView h = holds.get(id);
                if (h != null && h.lpn() != null && h.status() == HoldView.Status.HELD) {
                    lpns.merge(h.lpn(), h.quantity(), Integer::sum);
                }
            }
            if (!lpns.isEmpty()) {
                out.put(line.lineId(), lpns);
            }
        }
        return out;
    }

    private static EstimateLine line(UUID lineId, PackagingSpec spec) {
        PackagingSpec.CartonSpec c = spec.carton();
        Partial partial = spec.partialCarton() == null ? null
                : new Partial(spec.partialCarton().units(), spec.partialCarton().weightKg());
        return new EstimateLine(lineId, spec.sku(), spec.inventoryItemId(), spec.quantity(),
                spec.level().name(), spec.unitOfMeasure(), spec.factor(), spec.totalCartons(), partial,
                new Layout.Dims(c.lengthMm(), c.widthMm(), c.heightMm()), c.weightKg(), c.rotatable(),
                Math.max(1, spec.kitParts()));
    }

    private EstimateMeta meta(LoadPlanResult result) {
        LoadPlanMeta m = result.meta();
        return new EstimateMeta(SEED, budget.toMillis(), m.algorithm(), m.setsTotal(), m.minVehicles(),
                m.budgetExhausted(), m.elapsedMillis(), m.bestFoundMillis(), result.placedCount(),
                result.unplaced().size(), m.kitBalance(), m.kitWarnings());
    }

    // ------------------------------------------------------------------ view

    private LoadEstimateView view(LoadEstimateJpaEntity row) {
        JsonNode layout = row.getLayout();
        return new LoadEstimateView(row.getId(), SourceType.valueOf(row.getSourceType()), row.getSourceId(),
                row.getWarehouseId(), row.getStatus(), SelectionMode.valueOf(row.getMode()),
                read(row.getVehicleTypeIds(), IDS), read(row.getLines(), LINES), read(row.getMissing(), MISSING),
                row.isIncomplete(), read(row.getWarnings(), WARNINGS), read(layout.get("vehicles"), VEHICLES),
                read(layout.get("unplaced"), UNPLACED),
                layout.hasNonNull("meta") ? json.convertValue(layout.get("meta"), EstimateMeta.class) : null,
                row.getCreatedAt(), row.getCreatedBy());
    }

    private <T> List<T> read(JsonNode node, TypeReference<List<T>> type) {
        return node == null || node.isNull() ? List.of() : json.convertValue(node, type);
    }
}
