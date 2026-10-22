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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * See {@link LoadEstimates}. Three steps, and only the first and last touch the database:
 *
 * <ol>
 *   <li><b>Read</b> (one read-only transaction): the order, its eligibility and warehouse scope, the vehicle types,
 *       every line's packaging through the resolver. A line with nothing to measure stops here with 422.</li>
 *   <li><b>Plan</b>, outside any transaction: the solver may take its whole budget (LP2-10, synchronous), and a
 *       database connection held for those seconds would be a connection the warehouse floor does not have.</li>
 *   <li><b>Store</b> (one short transaction): under an advisory lock on the source, the current estimate is
 *       superseded and the new one inserted, so the one-current index never sees two. The answer is then read back
 *       as stored.</li>
 * </ol>
 *
 * <p>The seed is fixed: the same order, vehicle types and mode give the same estimate, unless the budget ran out
 * ({@code meta.budgetExhausted}), when the plan is the best found so far.</p>
 */
@Service
class LoadEstimateService implements LoadEstimates {

    /** The planner's seed for every estimate: 0 draws exactly what the lead's Python solver drew. */
    static final long SEED = 0L;

    /** Released to the warehouse or production, nothing handed over yet (design v2 §2: estimated at release). */
    private static final Set<OrderStatus> ESTIMABLE = EnumSet.of(OrderStatus.IN_PRODUCTION,
            OrderStatus.READY_TO_FULFILL, OrderStatus.PICKING, OrderStatus.PACKED);

    private static final TypeReference<List<EstimateLine>> LINES = new TypeReference<>() { };
    private static final TypeReference<List<EstimateWarning>> WARNINGS = new TypeReference<>() { };
    private static final TypeReference<List<MissingLine>> MISSING = new TypeReference<>() { };
    private static final TypeReference<List<Layout>> VEHICLES = new TypeReference<>() { };
    private static final TypeReference<List<Layout.Unplaced>> UNPLACED = new TypeReference<>() { };
    private static final TypeReference<List<UUID>> IDS = new TypeReference<>() { };

    private final LoadEstimateJpaRepository estimates;
    private final VehicleTypeJpaRepository vehicleTypes;
    private final OrderService orders;
    private final PackagingSpecResolver packaging;
    private final ObjectMapper json;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;
    private final Duration budget;
    private final LoadPlanner planner = new LoadPlanner();

    LoadEstimateService(LoadEstimateJpaRepository estimates, VehicleTypeJpaRepository vehicleTypes,
                        OrderService orders, PackagingSpecResolver packaging, ObjectMapper json,
                        PlatformTransactionManager transactionManager,
                        @Value("${stockflow.fulfillment.load-planning.solver-budget:PT5S}") Duration budget) {
        this.estimates = estimates;
        this.vehicleTypes = vehicleTypes;
        this.orders = orders;
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
    private record Prepared(UUID warehouseId, List<UUID> vehicleTypeIds, SelectionMode mode, LoadPlanRequest request,
                            EstimateCartons.Built built, List<EstimateLine> lines, List<EstimateWarning> warnings) { }

    @Override
    public LoadEstimateView create(CreateEstimate command) {
        Source source = onlySource(command.sources());
        if (source.type() != SourceType.ORDER) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_SOURCE_UNSUPPORTED,
                    "Only sales orders can be estimated for now; transfer orders come with transfer packing");
        }
        SelectionMode mode = command.selectionMode() == null ? SelectionMode.SINGLE_TYPE : command.selectionMode();
        Prepared prepared = reads.execute(s -> prepare(source.id(), command.vehicleTypeIds(), mode));

        LoadPlanResult result = planner.plan(prepared.request());
        Layouts.Mapped mapped = Layouts.of(prepared.request(), result,
                carton -> prepared.built().labels().get(carton.ref()));

        ObjectNode layout = json.createObjectNode();
        layout.set("vehicles", json.valueToTree(mapped.vehicles()));
        layout.set("unplaced", json.valueToTree(mapped.unplaced()));
        layout.set("missing", json.valueToTree(List.<MissingLine>of()));
        layout.set("meta", json.valueToTree(meta(result)));

        UUID id = writes.execute(s -> {
            estimates.lockSource(SourceType.ORDER + ":" + source.id());
            List<LoadEstimateJpaEntity> current = estimates.findAllBySourceTypeAndSourceIdAndStatus(
                    SourceType.ORDER.name(), source.id(), LoadEstimateJpaEntity.CURRENT);
            current.forEach(LoadEstimateJpaEntity::supersede);
            // Hibernate flushes inserts before updates: the old row must leave CURRENT first.
            estimates.saveAllAndFlush(current);
            LoadEstimateJpaEntity row = estimates.saveAndFlush(new LoadEstimateJpaEntity(Identifiers.newId(),
                    SourceType.ORDER.name(), source.id(), prepared.warehouseId(),
                    json.valueToTree(prepared.vehicleTypeIds()), prepared.mode().name(),
                    json.valueToTree(prepared.lines()), layout, json.valueToTree(prepared.warnings())));
            return row.getId();
        });
        // Read back as stored, so the answer is exactly what GET answers later (numbers as the JSON column keeps them).
        return reads.execute(s -> view(estimates.findById(id).orElseThrow()));
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

    private Prepared prepare(UUID orderId, List<UUID> requestedTypes, SelectionMode mode) {
        OrderSummary order = orders.findById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
        WarehouseScope.requireWarehouse(order.warehouseId());
        if (!ESTIMABLE.contains(order.status())) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE, "Order " + order.orderNumber()
                    + " is " + order.status() + "; only a released order that has not shipped can be estimated");
        }
        List<VehicleSpec> types = vehicleSpecs(requestedTypes);

        List<OrderSummary.LineSummary> lines = order.lines().stream().filter(l -> l.quantity() > 0).toList();
        List<PackagingSpec> specs = lines.isEmpty() ? List.of() : packaging.resolveAll(lines.stream()
                .map(l -> new PackagingRequest(new Sku(l.sku()), l.quantity())).toList());

        List<FieldError> missing = new ArrayList<>();
        List<EstimateCartons.Line> resolved = new ArrayList<>();
        List<EstimateLine> estimateLines = new ArrayList<>();
        List<EstimateWarning> warnings = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            OrderSummary.LineSummary line = lines.get(i);
            PackagingSpec spec = specs.get(i);
            spec.warnings().forEach(w -> warnings.add(new EstimateWarning(line.lineId(), spec.sku(), w.code().name(),
                    w.unitOfMeasure(), w.fields(), w.message())));
            if (!measurable(spec)) {
                missing.add(missingField(line, spec));
                continue;
            }
            resolved.add(new EstimateCartons.Line(line.lineId(), spec));
            estimateLines.add(line(line, spec));
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_ESTIMATE_PACKAGING_MISSING, missing.size()
                    + " line(s) of order " + order.orderNumber() + " have an item without size or weight; complete"
                    + " the item(s) and estimate again", missing);
        }
        EstimateCartons.Built built = EstimateCartons.build(orderId.toString(), resolved);
        LoadPlanRequest request = new LoadPlanRequest(built.cartons(), types, mode, built.kits(),
                KitDistribution.AUTO, false, null, SEED, budget);
        return new Prepared(order.warehouseId(), types.stream().map(VehicleSpec::typeId).toList(), mode, request,
                built, estimateLines, warnings);
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

    /** Resolved with a real size and weight: nothing is defaulted (LP2-3), and the planner refuses a zero. */
    private static boolean measurable(PackagingSpec spec) {
        if (!spec.resolved() || spec.carton() == null) {
            return false;
        }
        PackagingSpec.CartonSpec c = spec.carton();
        return c.lengthMm() > 0 && c.widthMm() > 0 && c.heightMm() > 0 && c.weightKg() != null
                && c.weightKg().signum() > 0;
    }

    /**
     * One blocked line, pointing at its inventory item (LP2-3): {@code field} is {@code inventoryItems[<itemId>]}, so
     * the screen links to the item to complete; the message names the SKU, the order line and the empty fields.
     */
    private static FieldError missingField(OrderSummary.LineSummary line, PackagingSpec spec) {
        List<String> fields = spec.missing() != null && spec.missing().fields() != null ? spec.missing().fields()
                : List.of("lengthCm", "widthCm", "heightCm", "weightKg");
        return new FieldError("inventoryItems[" + spec.inventoryItemId() + "]", "SKU " + spec.sku() + " (order line "
                + line.lineId() + ") has no " + String.join(", ", fields) + " on its inventory item",
                "PackagingMissing");
    }

    private static EstimateLine line(OrderSummary.LineSummary line, PackagingSpec spec) {
        PackagingSpec.CartonSpec c = spec.carton();
        Partial partial = spec.partialCarton() == null ? null
                : new Partial(spec.partialCarton().units(), spec.partialCarton().weightKg());
        return new EstimateLine(line.lineId(), spec.sku(), spec.inventoryItemId(), spec.quantity(),
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
                read(row.getVehicleTypeIds(), IDS), read(row.getLines(), LINES), read(layout.get("missing"), MISSING),
                read(row.getWarnings(), WARNINGS), read(layout.get("vehicles"), VEHICLES),
                read(layout.get("unplaced"), UNPLACED),
                layout.hasNonNull("meta") ? json.convertValue(layout.get("meta"), EstimateMeta.class) : null,
                row.getCreatedAt(), row.getCreatedBy());
    }

    private <T> List<T> read(JsonNode node, TypeReference<List<T>> type) {
        return node == null || node.isNull() ? List.of() : json.convertValue(node, type);
    }
}
