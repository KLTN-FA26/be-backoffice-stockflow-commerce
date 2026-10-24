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
import com.stockflow.inventory.api.PackagingRequest;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpecResolver;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
     * Where an estimate makes sense: CONFIRMED (the release dialog estimates before releasing, design v2 §2, with the
     * warehouse it is about to choose), released to production or the warehouse, and partly fulfilled (its remaining
     * units only). DRAFT, PENDING_PAYMENT, ON_HOLD and everything from SHIPPED on are refused.
     */
    private static final Set<OrderStatus> ESTIMABLE = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PRODUCTION,
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
    private final WarehouseService warehouses;
    private final PackagingSpecResolver packaging;
    private final ObjectMapper json;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;
    private final Duration budget;
    private final LoadPlanner planner = new LoadPlanner();
    private final com.stockflow.inventory.api.TransferFulfillment transfers;
    /** SCRUM-287 PR T: a transfer is estimated until it is dispatched. */
    private static final Set<String> TRANSFER_ESTIMABLE = Set.of("DRAFT", "PENDING_APPROVAL", "APPROVED", "PICKING");

    LoadEstimateService(LoadEstimateJpaRepository estimates, VehicleTypeJpaRepository vehicleTypes,
                        OrderService orders, WarehouseService warehouses, PackagingSpecResolver packaging,
                        ObjectMapper json, PlatformTransactionManager transactionManager,
                        @Value("${stockflow.fulfillment.load-planning.solver-budget:PT5S}") Duration budget,
                        com.stockflow.inventory.api.TransferFulfillment transfers) {
        this.transfers = transfers;
        this.estimates = estimates;
        this.vehicleTypes = vehicleTypes;
        this.orders = orders;
        this.warehouses = warehouses;
        this.packaging = packaging;
        this.json = json;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.writes = new TransactionTemplate(transactionManager);
        if (budget == null || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("stockflow.fulfillment.load-planning.solver-budget must be positive");
        }
        this.budget = budget;
    }

    /** What the read step hands the planner, and what the store step needs from it. */
    private record Prepared(UUID warehouseId, Map<UUID, Integer> remaining, List<UUID> vehicleTypeIds,
                            SelectionMode mode, LoadPlanRequest request, EstimateCartons.Built built,
                            List<EstimateLine> lines, List<MissingLine> missing, List<EstimateWarning> warnings) { }

    @Override
    public LoadEstimateView create(CreateEstimate command) {
        Source source = onlySource(command.sources());
        boolean transfer = source.type() == SourceType.TRANSFER;
        SelectionMode mode = command.selectionMode() == null ? SelectionMode.SINGLE_TYPE : command.selectionMode();
        Prepared prepared = reads.execute(s -> transfer
                ? prepareTransfer(source.id(), command.warehouseId(), command.vehicleTypeIds(), mode)
                : prepare(source.id(), command.warehouseId(), command.vehicleTypeIds(), mode));

        LoadPlanResult result = planner.plan(prepared.request());
        Layouts.Mapped mapped = Layouts.of(prepared.request(), result,
                carton -> prepared.built().labels().get(carton.ref()));

        ObjectNode layout = json.createObjectNode();
        layout.set("vehicles", json.valueToTree(mapped.vehicles()));
        layout.set("unplaced", json.valueToTree(mapped.unplaced()));
        layout.set("meta", json.valueToTree(meta(result)));

        UUID id = writes.execute(s -> {
            if (transfer) {
                recheckTransfer(source.id(), prepared);
            } else {
                recheck(source.id(), prepared);
            }
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

    private Prepared prepare(UUID orderId, UUID requestedWarehouse, List<UUID> requestedTypes, SelectionMode mode) {
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

        List<MissingLine> missing = new ArrayList<>();
        List<EstimateCartons.Line> resolved = new ArrayList<>();
        List<EstimateLine> estimateLines = new ArrayList<>();
        List<EstimateWarning> warnings = new ArrayList<>();
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
            resolved.add(new EstimateCartons.Line(line.lineId(), spec));
            estimateLines.add(line(line, spec));
        }
        if (resolved.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_PACKAGING_MISSING, "No line of order "
                    + order.orderNumber() + " has an item with a size and weight; complete the item(s) and estimate"
                    + " again", missing.stream().map(LoadEstimateService::missingField).toList());
        }
        EstimateCartons.Built built = EstimateCartons.build(orderId.toString(), resolved);
        LoadPlanRequest request = new LoadPlanRequest(built.cartons(), types, mode, built.kits(),
                KitDistribution.AUTO, false, null, SEED, budget);
        return new Prepared(warehouseId, remaining, types.stream().map(VehicleSpec::typeId).toList(), mode, request,
                built, estimateLines, List.copyOf(missing), warnings);
    }

    /**
     * SCRUM-287 PR T: a transfer order, from its creation until it is dispatched, at its source warehouse - every line
     * requested, each carton from the packaging-spec resolver as an order line's (LP2-2..LP2-5; a line without sizes is
     * blocked on its own, LP2-3). {@code orderLineId} of an estimate line is then the transfer line's id.
     */
    private Prepared prepareTransfer(UUID transferId, UUID requestedWarehouse, List<UUID> requestedTypes,
                                     SelectionMode mode) {
        var transfer = transfers.find(transferId).orElseThrow(() -> new NotFoundException("Transfer order",
                transferId));
        if (requestedWarehouse != null && !requestedWarehouse.equals(transfer.fromWarehouseId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Transfer " + transfer.number()
                    + " leaves from another warehouse", List.of(new FieldError("warehouseId",
                    "must be the transfer's source warehouse, or left out", "WarehouseMismatch")));
        }
        WarehouseScope.requireWarehouse(transfer.fromWarehouseId());
        if (!TRANSFER_ESTIMABLE.contains(transfer.status())) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE, "Transfer " + transfer.number()
                    + " is " + transfer.status() + "; a transfer is estimated until it is dispatched");
        }
        Map<UUID, Integer> remaining = new LinkedHashMap<>();
        transfer.lines().forEach(l -> remaining.put(l.lineId(), l.requested()));
        List<VehicleSpec> types = vehicleSpecs(requestedTypes);
        List<PackagingSpec> specs = packaging.resolveAll(transfer.lines().stream()
                .map(l -> new PackagingRequest(new Sku(l.sku()), l.requested())).toList());
        List<MissingLine> missing = new ArrayList<>();
        List<EstimateCartons.Line> resolved = new ArrayList<>();
        List<EstimateLine> estimateLines = new ArrayList<>();
        List<EstimateWarning> warnings = new ArrayList<>();
        for (int i = 0; i < transfer.lines().size(); i++) {
            var line = transfer.lines().get(i);
            PackagingSpec spec = specs.get(i);
            spec.warnings().forEach(w -> warnings.add(new EstimateWarning(line.lineId(), spec.sku(), w.code().name(),
                    w.unitOfMeasure(), w.fields(), w.message())));
            List<String> absent = missingFields(spec);
            if (!absent.isEmpty()) {
                missing.add(new MissingLine(line.lineId(), spec.sku(), spec.inventoryItemId(), absent));
                continue;
            }
            resolved.add(new EstimateCartons.Line(line.lineId(), spec));
            estimateLines.add(line(line.lineId(), spec));
        }
        if (resolved.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_PACKAGING_MISSING, "No line of transfer "
                    + transfer.number() + " has an item with a size and weight; complete the item(s) and estimate"
                    + " again", missing.stream().map(LoadEstimateService::missingField).toList());
        }
        EstimateCartons.Built built = EstimateCartons.build(transferId.toString(), resolved);
        LoadPlanRequest request = new LoadPlanRequest(built.cartons(), types, mode, built.kits(),
                KitDistribution.AUTO, false, null, SEED, budget);
        return new Prepared(transfer.fromWarehouseId(), remaining, types.stream().map(VehicleSpec::typeId).toList(),
                mode, request, built, estimateLines, List.copyOf(missing), warnings);
    }

    /** Before storing: the transfer may still be estimated, from the same warehouse, with the same lines. */
    private void recheckTransfer(UUID transferId, Prepared prepared) {
        var transfer = transfers.find(transferId).orElse(null);
        Map<UUID, Integer> lines = new LinkedHashMap<>();
        if (transfer != null) {
            transfer.lines().forEach(l -> lines.put(l.lineId(), l.requested()));
        }
        if (transfer == null || !TRANSFER_ESTIMABLE.contains(transfer.status())
                || !transfer.fromWarehouseId().equals(prepared.warehouseId()) || !lines.equals(prepared.remaining())) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE, "The transfer changed while it was "
                    + "estimated (dispatched, cancelled or edited); estimate again");
        }
    }

    /**
     * The warehouse the estimate belongs to: a released order's own; a CONFIRMED one has none yet, so the caller names
     * the warehouse the release dialog is about to choose - it must exist, be active and be within the caller's scope.
     */
    private UUID warehouseOf(OrderSummary order, UUID requested) {
        if (order.warehouseId() != null) {
            if (requested != null && !requested.equals(order.warehouseId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Order " + order.orderNumber()
                        + " was released to another warehouse", List.of(new FieldError("warehouseId",
                        "must be the order's warehouse, or left out", "WarehouseMismatch")));
            }
            return order.warehouseId();
        }
        if (requested == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Order " + order.orderNumber()
                    + " is not released yet: name the warehouse it will ship from", List.of(new FieldError(
                    "warehouseId", "is required for an order that is not released yet", "NotNull")));
        }
        WarehouseView warehouse = warehouses.findWarehouse(requested)
                .orElseThrow(() -> new NotFoundException("Warehouse", requested));
        if (!warehouse.active()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Warehouse " + warehouse.name()
                    + " is not active", List.of(new FieldError("warehouseId", "is not an active warehouse",
                    "WarehouseInactive")));
        }
        return requested;
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
     * not use is refused rather than silently left out: the coordinator chose it.
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
     * The item fields that keep a line from being measured (LP2-3), empty when it can be: the resolver's list when it
     * found no level, or the carton's own zero sizes or weight (nothing is defaulted, and the planner refuses a zero).
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
     * {@code inventoryItems[<itemId>]}, so the screen links to the item to complete; the message names the SKU, the
     * order line and the empty fields.
     */
    private static FieldError missingField(MissingLine line) {
        return new FieldError("inventoryItems[" + line.inventoryItemId() + "]", "SKU " + line.sku() + " (order line "
                + line.orderLineId() + ") has no " + String.join(", ", line.missingFields())
                + " on its inventory item", "PackagingMissing");
    }

    private static EstimateLine line(OrderSummary.LineSummary line, PackagingSpec spec) {
        return line(line.lineId(), spec);
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
