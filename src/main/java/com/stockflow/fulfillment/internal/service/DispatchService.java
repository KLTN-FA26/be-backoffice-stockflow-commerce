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
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageLineJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.LoadNumbers;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageLineJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * See {@link Dispatching}. Lock order, as everywhere in fulfillment's dispatch: the load row, then the shipments' rows
 * (id order), then the orders' rows (taken by {@code OrderService}). The order cancellation listener reaches a load only
 * after the pick and before the shipment, and an order with a completed package is never cancelled (decision
 * 2026-10-17), so the two never cross.
 */
@Service
@Transactional
class DispatchService implements Dispatching {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String DEFAULT_CURRENCY = "VND";

    private final DispatchLoadJpaRepository loads;
    private final DispatchLoadShipmentJpaRepository members;
    private final DispatchLoadPackageJpaRepository loaded;
    private final DispatchLoadStore store;
    private final LoadNumbers numbers;
    private final ShipmentJpaRepository shipments;
    private final com.stockflow.fulfillment.internal.repository.ShipmentLocks locks;
    private final PackageJpaRepository packages;
    private final PackageLineJpaRepository packageLines;
    private final PickJpaRepository picks;
    private final CarrierJpaRepository carriers;
    private final ShipmentTimeline timeline;
    private final ShipmentContext context;
    private final OrderService orders;
    private final WarehouseService warehouses;
    private final IdentityService identities;
    private final PlanVehicles planVehicles;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    DispatchService(DispatchLoadJpaRepository loads, DispatchLoadShipmentJpaRepository members,
                    DispatchLoadPackageJpaRepository loaded, DispatchLoadStore store, LoadNumbers numbers,
                    ShipmentJpaRepository shipments, com.stockflow.fulfillment.internal.repository.ShipmentLocks locks,
                    PackageJpaRepository packages,
                    PackageLineJpaRepository packageLines, PickJpaRepository picks, CarrierJpaRepository carriers,
                    ShipmentTimeline timeline, ShipmentContext context, OrderService orders,
                    WarehouseService warehouses,
                    IdentityService identities, PlanVehicles planVehicles, Clock clock) {
        this.loads = loads;
        this.members = members;
        this.loaded = loaded;
        this.store = store;
        this.numbers = numbers;
        this.shipments = shipments;
        this.locks = locks;
        this.packages = packages;
        this.packageLines = packageLines;
        this.picks = picks;
        this.carriers = carriers;
        this.timeline = timeline;
        this.context = context;
        this.orders = orders;
        this.warehouses = warehouses;
        this.identities = identities;
        this.planVehicles = planVehicles;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LoadRow> list(UUID warehouseId, String status, UUID carrierId, String search, int page,
                                      int size) {
        if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
        }
        List<Specification<DispatchLoadJpaEntity>> filters = new ArrayList<>();
        filters.add(WarehouseScope.warehousesIn("warehouseId"));
        if (warehouseId != null) {
            filters.add(Specs.eq("warehouseId", warehouseId));
        }
        if (status != null && !status.isBlank()) {
            filters.add(Specs.eq("status", loadStatus(status)));
        }
        if (carrierId != null) {
            filters.add(Specs.eq("carrierId", carrierId));
        }
        if (search != null && !search.isBlank()) {
            Specification<DispatchLoadJpaEntity> byCode = Specs.contains("code", search.trim());
            filters.add(byCode.or(Specs.contains("vehiclePlate", search.trim())));
        }
        var found = loads.findAll(Specification.allOf(filters),
                Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        Map<UUID, CarrierJpaEntity> carrierById = carriersOf(found.getContent().stream()
                .map(DispatchLoadJpaEntity::getCarrierId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, PlanVehicles.VehicleRef> vehicleOf = planVehicles.describe(found.getContent().stream()
                .map(DispatchLoadJpaEntity::getLoadPlanVehicleId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return Pages.toResponse(found, row -> {
            PlanVehicles.VehicleRef vehicle = row.getLoadPlanVehicleId() == null ? null
                    : vehicleOf.get(row.getLoadPlanVehicleId());
            return new LoadRow(row.getId(), row.getCode(), row.getWarehouseId(),
                    row.getMode().name(), row.getStatus().name(), row.getCarrierId(),
                    Optional.ofNullable(carrierById.get(row.getCarrierId())).map(CarrierJpaEntity::getName).orElse(null),
                    row.getVehiclePlate(), row.getDriverName(), row.getShipmentCount(), row.getPackageCount(),
                    row.getCodTotal(), row.getLoadingStartedAt(), row.getDispatchedAt(), row.getVersion(),
                    vehicle == null ? null : vehicle.loadPlanId(), vehicle == null ? null : vehicle.loadPlanCode(),
                    vehicle == null ? null : vehicle.seq());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public LoadDetail get(UUID loadId) {
        DispatchLoadJpaEntity header = loads.findById(loadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_NOT_FOUND));
        WarehouseScope.requireWarehouse(header.getWarehouseId());
        return detail(header, store.load(header));
    }

    // ------------------------------------------------------------------ the manifest's list

    @Override
    public LoadDetail open(OpenLoad request, CurrentUser actor) {
        Objects.requireNonNull(request.requestId(), "requestId");
        // Two opens with one request id at once (a double tap): the second waits here and answers as a replay instead
        // of failing on uk_dispatch_load_request.
        numbers.lockRequest(request.requestId());
        Optional<DispatchLoadJpaEntity> replay = loads.findByRequestId(request.requestId());
        if (replay.isPresent()) {
            DispatchLoadJpaEntity header = replay.get();
            WarehouseScope.requireWarehouse(header.getWarehouseId());
            if (!header.getWarehouseId().equals(request.warehouseId())
                    || !Objects.equals(header.getCarrierId(), request.carrierId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "That request id opened another load");
            }
            return detail(header, store.load(header));
        }
        WarehouseScope.requireWarehouse(request.warehouseId());
        warehouses.findWarehouse(request.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
        CarrierJpaEntity carrier = carriers.findById(request.carrierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND));
        if (!carrier.isActive()) {
            throw new BusinessException(ErrorCode.CARRIER_INACTIVE,
                    "Carrier %s is no longer in use".formatted(carrier.getCode()));
        }
        Instant now = clock.instant();
        DispatchLoad load = DispatchLoad.openParcel(Identifiers.newId(),
                numbers.next(LocalDate.ofInstant(now, BUSINESS_ZONE)), request.requestId(), request.warehouseId(),
                carrier.getId(), request.vehiclePlate(), request.driverName(), request.driverPhone(),
                actor.userId(), now);
        boolean chosen = request.shipmentIds() != null && !request.shipmentIds().isEmpty();
        List<UUID> wanted = chosen ? request.shipmentIds() : waitingFor(request.warehouseId(), carrier.getId());
        if (!chosen) {
            // PACK-PARTIAL (review of #150): by default, an order that may not ship in parts comes along whole or not
            // at all - its shipments that would leave part of it behind stay at dispatch.
            Set<UUID> splitting = splittingShipments(List.of(), wanted);
            wanted = wanted.stream().filter(id -> !splitting.contains(id)).toList();
        }
        add(load, wanted, actor.userId(), now);
        DispatchLoadJpaEntity header = store.insert(load);
        log.info("Load {} opened at warehouse {} for carrier {}: {} shipment(s)", load.code(), load.warehouseId(),
                carrier.getCode(), load.activeShipments().size());
        return detail(header, load);
    }

    /** By default the shipments chosen for this carrier beforehand (09 §4.1 step 2) and on no load yet. */
    private List<UUID> waitingFor(UUID warehouseId, UUID carrierId) {
        Specification<ShipmentJpaEntity> spec = Specification.allOf(List.of(Specs.eq("warehouseId", warehouseId),
                Specs.eq("status", ShipmentStatus.READY_TO_DISPATCH), Specs.eq("carrierId", carrierId),
                Specs.eq("deliveryMode", DeliveryMode.PARCEL)));
        List<UUID> ids = shipments.findAll(spec, Sort.by("readyAt", "id")).stream().map(ShipmentJpaEntity::getId)
                .toList();
        Set<UUID> elsewhere = members.findByShipmentIdInAndActiveTrue(ids).stream()
                .map(DispatchLoadShipmentJpaEntity::getShipmentId).collect(Collectors.toSet());
        return ids.stream().filter(id -> !elsewhere.contains(id)).toList();
    }

    @Override
    public LoadDetail addShipments(UUID loadId, List<UUID> shipmentIds, CurrentUser actor) {
        DispatchLoadJpaEntity header = lockLoad(loadId);
        DispatchLoad load = store.load(header);
        requireNotPlanManaged(load);
        if (add(load, shipmentIds, actor.userId(), clock.instant())) {
            header = store.save(load, header);
        }
        return detail(header, load);
    }

    /** Locks the shipments (id order) and puts each on the load; refused as a whole on the first refusal. */
    private boolean add(DispatchLoad load, List<UUID> shipmentIds, UUID by, Instant at) {
        if (shipmentIds == null || shipmentIds.isEmpty()) {
            return false;
        }
        Set<UUID> ids = new LinkedHashSet<>(shipmentIds);
        Map<UUID, ShipmentJpaEntity> rows = locks.lock(ids).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        // PACK-PARTIAL (review of #150): refused now, not at the signature - a manifest that would hand over part of an
        // order whose Sales did not allow partial shipments.
        Set<UUID> splitting = splittingShipments(load.activeShipments(), ids);
        if (!splitting.isEmpty()) {
            List<String> codes = rows.values().stream().filter(row -> splitting.contains(row.getId()))
                    .map(ShipmentJpaEntity::getCode).sorted().toList();
            throw new BusinessException(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED, ("Shipment(s) %s would hand over "
                    + "part of an order that does not allow partial shipments: put every shipment of the order on the "
                    + "load together, once all of it is packed, or ask Sales to allow partial shipment")
                    .formatted(String.join(", ", codes)),
                    codes.stream().map(code -> com.stockflow.common.api.FieldError.of("shipmentCode", code)).toList());
        }
        Map<UUID, String> elsewhere = otherLoads(ids, load.id());
        boolean changed = false;
        for (UUID id : ids) {
            ShipmentJpaEntity row = rows.get(id);
            if (row == null) {
                throw new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND, "No shipment " + id);
            }
            changed |= load.addShipment(facts(row, elsewhere.get(id)), row.getLabelCodAmount(), by, at);
        }
        return changed;
    }

    /**
     * PACK-PARTIAL: the shipments among {@code adding} whose order does not allow partial shipments and would not leave
     * whole with the shipments on the load ({@code onLoad}) and the others being added: units of it stay behind -
     * not packed yet, or packed in a shipment not on the load. Shipments packed before contents were recorded leave as
     * the whole order and are not checked.
     */
    private Set<UUID> splittingShipments(java.util.Collection<UUID> onLoad, java.util.Collection<UUID> adding) {
        if (adding.isEmpty()) {
            return Set.of();
        }
        Set<UUID> all = new LinkedHashSet<>(onLoad);
        all.addAll(adding);
        List<ShipmentJpaEntity> rows = shipments.findAllById(all);
        Map<UUID, List<ShipmentHandover.Line>> contents = linesOf(packages.findByShipmentIdIn(all).stream()
                .filter(p -> p.getStatus() == PackageStatus.SEALED).toList());
        Map<UUID, List<ShipmentJpaEntity>> byOrder = rows.stream()
                .collect(Collectors.groupingBy(ShipmentJpaEntity::getOrderId));
        Set<UUID> splitting = new HashSet<>();
        Set<UUID> added = new HashSet<>(adding);
        byOrder.forEach((orderId, ofOrder) -> {
            if (ofOrder.stream().noneMatch(row -> added.contains(row.getId()))) {
                return;
            }
            OrderSummary order = orders.findById(orderId).orElse(null);
            if (PartialShipments.mayShipInParts(order)) {
                return;
            }
            int outstanding = PartialShipments.outstandingUnits(order);
            boolean recorded = ofOrder.stream().allMatch(row -> contents.containsKey(row.getId()));
            int leaving = ofOrder.stream().flatMap(row -> contents.getOrDefault(row.getId(), List.of()).stream())
                    .mapToInt(ShipmentHandover.Line::quantity).sum();
            if (recorded && leaving < outstanding) {
                ofOrder.stream().map(ShipmentJpaEntity::getId).filter(added::contains).forEach(splitting::add);
            }
        });
        return splitting;
    }

    @Override
    public LoadDetail removeShipment(UUID loadId, UUID shipmentId, CurrentUser actor) {
        DispatchLoadJpaEntity header = lockLoad(loadId);
        DispatchLoad load = store.load(header);
        // SCRUM-392: a vehicle's manifest is what its plan placed on it; it changes through the plan.
        requireNotPlanManaged(load);
        if (load.removeShipment(shipmentId, actor.userId(), clock.instant())) {
            header = store.save(load, header);
        }
        return detail(header, load);
    }

    // ------------------------------------------------------------------ the vehicle

    @Override
    public LoadDetail scan(UUID loadId, UUID givenScanId, String packageCode, CurrentUser actor) {
        UUID scanId = givenScanId == null ? Identifiers.newId() : givenScanId;
        DispatchLoadJpaEntity header = lockLoad(loadId);
        DispatchLoad load = store.load(header);
        String code = packageCode == null ? "" : packageCode.trim();
        Optional<DispatchLoadPackageJpaEntity> recorded = loaded.findByScanId(scanId);
        if (recorded.isPresent()) {
            // A scan id is answered first: the same scan again gets the answer it got, whatever happened since.
            boolean same = recorded.get().getLoadId().equals(loadId) && packages.findById(
                    recorded.get().getPackageId()).map(p -> p.getLabelCode().equalsIgnoreCase(code)).orElse(false);
            if (!same) {
                throw new BusinessException(ErrorCode.LOAD_SCAN_ID_CONFLICT,
                        "Scan %s was recorded for another package or load; send a new scanId".formatted(scanId));
            }
            return detail(header, load);
        }
        PackageJpaEntity parcel = packages.findByLabelCode(code)
                .or(() -> packages.findByLabelCode(code.toUpperCase(Locale.ROOT)))
                .orElseThrow(() -> new BusinessException(ErrorCode.PACKAGE_NOT_FOUND,
                        "No package labelled " + code));
        DispatchLoad.ShipmentFacts shipment = null;
        if (parcel.getShipmentId() != null) {
            ShipmentJpaEntity row = shipments.findById(parcel.getShipmentId()).orElseThrow();
            shipment = facts(row, otherLoads(Set.of(row.getId()), loadId).get(row.getId()));
        }
        String parcelElsewhere = loaded.findByPackageIdAndActiveTrue(parcel.getId())
                .filter(l -> !l.getLoadId().equals(loadId))
                .map(l -> loads.findById(l.getLoadId()).map(DispatchLoadJpaEntity::getCode).orElse("?"))
                .orElse(null);
        // SCRUM-392: a vehicle takes only what its plan placed on it (read: placements change under the plan's lock,
        // and an order cancelled meanwhile finds the parcel on board and asks for it to be unloaded).
        DispatchLoad.Placement placement = load.mode() == DeliveryMode.FULL_TRUCKLOAD
                ? planVehicles.placement(planVehicles.read(header), parcel.getId()) : null;
        if (load.scan(scanId, new DispatchLoad.ParcelFacts(parcel.getId(), parcel.getLabelCode(), parcel.getStatus(),
                shipment, parcelElsewhere, placement), actor.userId(), clock.instant())) {
            header = store.save(load, header);
        }
        return detail(header, load);
    }

    @Override
    public LoadDetail unscan(UUID loadId, UUID packageId, CurrentUser actor) {
        DispatchLoadJpaEntity header = lockLoad(loadId);
        DispatchLoad load = store.load(header);
        if (load.unscan(packageId, actor.userId(), clock.instant())) {
            header = store.save(load, header);
        }
        return detail(header, load);
    }

    // ------------------------------------------------------------------ the signature

    @Override
    public LoadDetail handOver(UUID loadId, Handover request, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        if (locked.plan() != null) {
            return handOverVehicle(locked, request, actor);
        }
        DispatchLoadJpaEntity header = locked.header();
        DispatchLoad load = locked.load();
        if (load.status() == LoadStatus.DISPATCHED) {
            return detail(header, load);
        }
        if (request.version() == null || request.version() != header.getVersion()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Load %s changed since version %s (now %d): "
                    .formatted(load.code(), request.version(), header.getVersion())
                    + "check the manifest again before signing");
        }
        Instant now = clock.instant();
        UUID by = actor.userId();
        List<UUID> onManifest = load.activeShipments();
        // Every shipment of the orders on the manifest, locked in id order before anything is read: a COD depends on
        // the order's other shipments (which wait, which left), and another load signing one of them meanwhile must
        // be either wholly before or wholly after this one (review: concurrent signatures over-collected).
        Map<UUID, ShipmentJpaEntity> rows = locks.lockOfOrders(locks.ordersOf(onManifest)).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        for (UUID id : onManifest) {
            ShipmentJpaEntity row = rows.get(id);
            if (row.getStatus() != ShipmentStatus.READY_TO_DISPATCH) {
                throw new BusinessException(ErrorCode.PACKAGE_NOT_READY, "Shipment %s is %s; take it off the manifest"
                        .formatted(row.getCode(), row.getStatus()));
            }
            // The label as last printed, read under the shipment's lock: what the signed manifest compares the fixed
            // COD with (a load recorded before reprints refreshed it may still hold an older figure).
            load.relabel(id, row.getLabelCodAmount());
        }
        List<PackageJpaEntity> sealed = onManifest.isEmpty() ? List.of() : packages.findByShipmentIdIn(onManifest)
                .stream().filter(p -> p.getStatus() == PackageStatus.SEALED).toList();
        Map<UUID, Set<UUID>> parcelsByShipment = sealed.stream().collect(Collectors.groupingBy(
                PackageJpaEntity::getShipmentId, Collectors.mapping(PackageJpaEntity::getId, Collectors.toSet())));
        // A kit's part names its kit (SCRUM-287 LP2-6): a part not scanned holds back its kit's shipment.
        Map<UUID, String> labels = sealed.stream().collect(Collectors.toMap(PackageJpaEntity::getId,
                p -> p.getKit() == null ? p.getLabelCode() : "%s (kit part %d/%d)".formatted(p.getLabelCode(),
                        p.getKit().partNo(), p.getKit().partCount())));
        List<UUID> leaving = load.handOver(new DispatchLoad.Handover(request.carrierRepresentative(),
                request.vehiclePlate(), request.driverPhone(), request.signatureRef(),
                Boolean.TRUE.equals(request.signed()), request.note()), parcelsByShipment, labels, by, now);
        CarrierJpaEntity carrier = carriers.findById(load.carrierId()).orElseThrow();
        Map<UUID, List<ShipmentHandover.Line>> linesByShipment = linesOf(sealed);
        // Order by order (the orders' row locks are taken in one order by every signature: no deadlock between two
        // loads carrying shipments of the same orders), and within an order oldest shipment first: the money paid
        // ahead goes to shipments in the order they were made (09 BR-04), at the label as at the handover.
        List<ShipmentJpaEntity> ordered = leaving.stream().map(rows::get)
                .sorted(Comparator.comparing(ShipmentJpaEntity::getOrderId).thenComparing(ShipmentJpaEntity::getReadyAt)
                        .thenComparing(ShipmentJpaEntity::getId))
                .toList();
        for (int i = 0; i < ordered.size(); i++) {
            ShipmentJpaEntity row = ordered.get(i);
            // PACK-PARTIAL: the order's shipments signed over after this one leave with it - an order that does not
            // allow partial shipments lets this one go when, with them, nothing of it is left behind.
            List<ShipmentHandover.Line> leavingWith = new ArrayList<>();
            for (ShipmentJpaEntity later : ordered.subList(i + 1, ordered.size())) {
                if (later.getOrderId().equals(row.getOrderId())) {
                    leavingWith.addAll(linesByShipment.getOrDefault(later.getId(), List.of()));
                }
            }
            handOver(row, load.id(), load.code(), load, carrier, linesByShipment.getOrDefault(row.getId(), List.of()),
                    leavingWith, parcelsByShipment.get(row.getId()).size(), by, now);
        }
        header = store.save(load, header);
        log.info("Load {} dispatched with carrier {}: {} shipment(s), {} parcel(s), COD {}", load.code(),
                carrier.getCode(), leaving.size(), sealed.size(), load.codTotal());
        return detail(header, load);
    }

    /**
     * One shipment leaves: the order is told in this transaction (A1) - which units, the shipments of the order made
     * before it and still waiting ({@code earlierPending}, so its COD is the one its label printed), and what the
     * customer may read of it - and fixes its COD; the shipment takes that COD, its carrier and its load, and is
     * refused when that COD is not the one on its label (BR-04, {@code LABEL_OUTDATED}).
     *
     * <p>A shipment whose parcels record no contents (packed before #136; V20261021000300 made its shipment) leaves as
     * the whole order ({@code OrderService.markShipped}), as long as the order records no shipment: it is then marked
     * {@code wholeOrderHandover}, its COD is the one the order fixed for the whole order, and its outcomes are the
     * order's own.</p>
     */
    private void handOver(ShipmentJpaEntity row, UUID loadId, String loadCode, DispatchLoad manifest,
                          CarrierJpaEntity carrier, List<ShipmentHandover.Line> lines,
                          List<ShipmentHandover.Line> leavingWith, int parcels, UUID by, Instant at) {
        Shipment shipment = row.toDomain();
        boolean wholeOrder = lines.isEmpty();
        OrderSummary order;
        BigDecimal cod;
        try {
            if (wholeOrder) {
                order = orders.markShipped(row.getOrderId(), at, by);
                cod = order.lifecycle() == null || order.lifecycle().wholeOrderCodAmount() == null
                        ? row.getLabelCodAmount() : order.lifecycle().wholeOrderCodAmount();
            } else {
                order = orders.recordHandover(row.getOrderId(), new ShipmentHandover(row.getId(), lines, at, by,
                        earlierPending(row), new ShipmentHandover.Carriage(row.getCode(), parcels, carrier.getName(),
                        row.getDeliveryMode().name()), leavingWith));
                cod = order.lifecycle().shipments().stream().filter(s -> s.shipmentId().equals(row.getId()))
                        .findFirst().map(OrderSummary.Shipment::codAmount).orElseThrow();
            }
            shipment.handOver(loadId, carrier.getId(), cod, wholeOrder, by, at);
        } catch (BusinessException refused) {
            // The order's (or the label's) refusal, naming the shipment; its code, message and fields kept.
            List<com.stockflow.common.api.FieldError> fields = new ArrayList<>(refused.fieldErrors());
            fields.add(com.stockflow.common.api.FieldError.of("shipmentCode", row.getCode()));
            String why = refused.getMessage() != null && refused.getMessage().contains(row.getCode())
                    ? refused.getMessage() : "Shipment %s: %s".formatted(row.getCode(), refused.getMessage());
            BusinessException named = new BusinessException(refused.errorCode(),
                    why + " (take it off the manifest to hand the rest over)", fields);
            named.initCause(refused);
            throw named;
        }
        row.apply(shipment);
        shipments.saveAndFlush(row);
        timeline.record(shipment, ShipmentStatus.READY_TO_DISPATCH, order, null, carrier.getName(), loadCode);
        if (manifest != null && manifest.activeShipments().contains(row.getId())) {
            manifest.fixCod(row.getId(), cod);
        }
    }

    /**
     * The units of the order's other shipments that were made before this one and still wait for dispatch (G4-5's
     * {@code ShipmentHandover.earlierPending}): the shipments handed over just before in this load are no longer
     * waiting, so they count as handed over, as they will in the order.
     */
    private List<ShipmentHandover.Line> earlierPending(ShipmentJpaEntity shipment) {
        List<UUID> earlier = new ArrayList<>();
        for (ShipmentJpaEntity other : shipments.findByOrderIdOrderByReadyAtAscIdAsc(shipment.getOrderId())) {
            if (other.getId().equals(shipment.getId())) {
                break;
            }
            if (other.getStatus() == ShipmentStatus.READY_TO_DISPATCH) {
                earlier.add(other.getId());
            }
        }
        if (earlier.isEmpty()) {
            return List.of();
        }
        List<PackageJpaEntity> sealed = packages.findByShipmentIdIn(earlier).stream()
                .filter(p -> p.getStatus() == PackageStatus.SEALED).toList();
        Map<UUID, Integer> totals = new LinkedHashMap<>();
        linesOf(sealed).values().forEach(lines -> lines.forEach(line -> totals.merge(line.orderLineId(),
                line.quantity(), Integer::sum)));
        return totals.entrySet().stream().map(e -> new ShipmentHandover.Line(e.getKey(), e.getValue())).toList();
    }

    /** Per shipment, the units of each order line in its sealed parcels ({@code package_line}, 08 BR-01). */
    private Map<UUID, List<ShipmentHandover.Line>> linesOf(List<PackageJpaEntity> sealed) {
        if (sealed.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> shipmentOf = sealed.stream().collect(Collectors.toMap(PackageJpaEntity::getId,
                PackageJpaEntity::getShipmentId));
        Map<UUID, Map<UUID, Integer>> totals = new HashMap<>();
        for (PackageLineJpaEntity line : packageLines.findByPackageIdIn(shipmentOf.keySet())) {
            totals.computeIfAbsent(shipmentOf.get(line.getPackageId()), id -> new LinkedHashMap<>())
                    .merge(line.getOrderLineId(), line.getQuantity(), Integer::sum);
        }
        Map<UUID, List<ShipmentHandover.Line>> result = new HashMap<>();
        totals.forEach((shipment, lines) -> result.put(shipment, lines.entrySet().stream()
                .map(e -> new ShipmentHandover.Line(e.getKey(), e.getValue())).toList()));
        return result;
    }

    @Override
    public LoadDetail cancel(UUID loadId, String reason, CurrentUser actor) {
        DispatchLoadJpaEntity header = lockLoad(loadId);
        DispatchLoad load = store.load(header);
        requireNotPlanManaged(load);
        if (load.cancel(reason, actor.userId(), clock.instant())) {
            header = store.save(load, header);
            log.info("Load {} cancelled", load.code());
        }
        return detail(header, load);
    }

    /**
     * SCRUM-287 PR D: a load of a load plan's vehicle changes only through its plan (reopening and cancelling the plan
     * cancel it, LP-11), never on its own: 409 {@code LOAD_MANAGED_BY_PLAN}.
     */
    private static void requireNotPlanManaged(DispatchLoad load) {
        if (load.loadPlanVehicleId() != null) {
            throw new BusinessException(ErrorCode.LOAD_MANAGED_BY_PLAN, ("Load %s is a vehicle of a load plan; change "
                    + "it through the plan").formatted(load.code()));
        }
    }

    // ------------------------------------------------------------------ full truckload (SCRUM-392)

    /** A load locked for a change, with its plan's row locked first when it is a plan's vehicle. */
    private record Locked(DispatchLoadJpaEntity header, DispatchLoad load, PlanVehicles.PlanOfLoad plan) { }

    /**
     * The load's row lock - for a load plan's vehicle, after the plan's row lock (the order {@code LoadPlanService}
     * takes them in: reopening or cancelling the plan and an order cancelled out of it cannot interleave with a
     * booking, the start of loading or a signature - BR-14).
     */
    private Locked lockForChange(UUID loadId) {
        // A projection, not the entity: the row lock below must read the load fresh (two dispatchers signing at once).
        DispatchLoadJpaRepository.LoadKey key = loads.findKeyById(loadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_NOT_FOUND));
        WarehouseScope.requireWarehouse(key.getWarehouseId());
        PlanVehicles.PlanOfLoad plan = key.getLoadPlanVehicleId() == null ? null
                : planVehicles.lock(key.getLoadPlanVehicleId());
        DispatchLoadJpaEntity header = lockLoad(loadId);
        return new Locked(header, store.load(header), plan);
    }

    @Override
    public LoadDetail book(UUID loadId, Booking booking, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        if (locked.plan() == null) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s is a parcel load: only a load "
                    + "plan's vehicle is booked").formatted(load.code()));
        }
        if (load.status() == LoadStatus.PLANNED || load.status() == LoadStatus.VEHICLE_BOOKED) {
            PlanVehicles.requireConfirmed(locked.plan());
        }
        CarrierJpaEntity carrier = carriers.findById(booking.carrierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND));
        if (!carrier.isActive()) {
            throw new BusinessException(ErrorCode.CARRIER_INACTIVE,
                    "Carrier %s is no longer in use".formatted(carrier.getCode()));
        }
        // A shipment chosen for a carrier beforehand (09 §4.1 step 2) leaves with that carrier only.
        List<String> otherCarrier = shipments.findByIdIn(load.activeShipments()).stream()
                .filter(s -> s.getCarrierId() != null && !s.getCarrierId().equals(carrier.getId()))
                .map(ShipmentJpaEntity::getCode).sorted().toList();
        if (!otherCarrier.isEmpty()) {
            throw new BusinessException(ErrorCode.SHIPMENT_OTHER_CARRIER, ("Shipment(s) %s on load %s were chosen for "
                    + "another carrier").formatted(String.join(", ", otherCarrier), load.code()),
                    otherCarrier.stream().map(code -> FieldError.of("shipmentCode", code)).toList());
        }
        DispatchLoadJpaEntity header = locked.header();
        if (load.book(carrier.getId(), booking.vehiclePlate(), booking.driverName(), booking.driverPhone(),
                actor.userId(), clock.instant())) {
            header = store.save(load, header);
            log.info("Load {} booked with carrier {}", load.code(), carrier.getCode());
        }
        return detail(header, load);
    }

    @Override
    public LoadDetail cancelBooking(UUID loadId, String reason, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        DispatchLoadJpaEntity header = locked.header();
        if (load.cancelBooking(reason, actor.userId(), clock.instant())) {
            header = store.save(load, header);
            log.info("Load {}: booking cancelled ({})", load.code(), reason == null ? null : reason.trim());
        }
        return detail(header, load);
    }

    @Override
    public LoadDetail startLoading(UUID loadId, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        if (locked.plan() != null && load.status() != LoadStatus.LOADING) {
            PlanVehicles.requireConfirmed(locked.plan());
        }
        DispatchLoadJpaEntity header = locked.header();
        if (load.startLoading(actor.userId(), clock.instant())) {
            header = store.save(load, header);
            log.info("Load {}: loading started (plan {} locked, BR-14)", load.code(),
                    locked.plan() == null ? null : locked.plan().plan().code());
        }
        return detail(header, load);
    }

    @Override
    public LoadDetail unload(UUID loadId, String packageCode, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        String code = packageCode == null ? "" : packageCode.trim();
        PackageJpaEntity parcel = packages.findByLabelCode(code)
                .or(() -> packages.findByLabelCode(code.toUpperCase(Locale.ROOT)))
                .orElseThrow(() -> new BusinessException(ErrorCode.PACKAGE_NOT_FOUND, "No package labelled " + code));
        Instant now = clock.instant();
        DispatchLoadJpaEntity header = locked.header();
        if (load.confirmUnload(parcel.getId(), parcel.getLabelCode(), actor.userId(), now)) {
            boolean calledOff = PlanVehicles.callOffIfEmpty(load,
                    "Nothing left to carry: the order(s) on it were cancelled", now);
            header = store.save(load, header);
            log.info("Load {}: package {} unloaded{}", load.code(), parcel.getLabelCode(),
                    calledOff ? "; nothing left, the load is called off" : "");
            if (calledOff && locked.plan() != null) {
                planVehicles.completeIfDispatched(locked.plan().header(), locked.plan().plan(), now);
            }
        }
        return detail(header, load);
    }

    /**
     * One vehicle of a load plan signed for (09 §4.2 step 5, BR-15, LP-3): every parcel the plan placed on it is on
     * board (a kit whole), nothing of a cancelled order is left to unload, and every order on it may let goods leave
     * now (the balance gate, G3-Q4). A shipment is handed over at its LAST vehicle's signature - an order that does
     * not allow partial shipments with all its shipments in the plan at once - through the same per-shipment handover
     * as a parcel load (the order told in this transaction, {@code earlierPending}, PACK-PARTIAL). Once every vehicle
     * has left, the plan is DISPATCHED.
     */
    private LoadDetail handOverVehicle(Locked locked, Handover request, CurrentUser actor) {
        DispatchLoadJpaEntity header = locked.header();
        DispatchLoad load = locked.load();
        PlanVehicles.PlanOfLoad ctx = locked.plan();
        if (load.status() == LoadStatus.DISPATCHED) {
            return detail(header, load);
        }
        if (request.version() == null || request.version() != header.getVersion()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Load %s changed since version %s (now %d): "
                    .formatted(load.code(), request.version(), header.getVersion())
                    + "check the manifest again before signing");
        }
        if (load.status() == LoadStatus.LOADING) {
            PlanVehicles.requireConfirmed(ctx);
        }
        Instant now = clock.instant();
        UUID by = actor.userId();
        LoadPlan plan = ctx.plan();
        Map<UUID, UUID> vehicleOfParcel = new HashMap<>();
        plan.activeVehicles().forEach(v -> v.placements().forEach(p -> vehicleOfParcel.put(p.packageId(), v.id())));
        Map<UUID, Set<UUID>> vehiclesOfShipment = new HashMap<>();
        for (LoadPlan.Member m : plan.members()) {
            UUID vehicle = vehicleOfParcel.get(m.packageId());
            if (m.active() && vehicle != null) {
                vehiclesOfShipment.computeIfAbsent(m.shipmentId(), k -> new HashSet<>()).add(vehicle);
            }
        }
        List<UUID> onManifest = load.activeShipments();
        Set<UUID> involved = new LinkedHashSet<>(onManifest);
        involved.addAll(vehiclesOfShipment.keySet());
        // Every shipment of the orders in the plan, locked in id order before anything is read (as a parcel load).
        Map<UUID, ShipmentJpaEntity> rows = involved.isEmpty() ? Map.of() : locks.lockOfOrders(locks.ordersOf(involved))
                .stream().collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        for (UUID id : onManifest) {
            ShipmentJpaEntity row = rows.get(id);
            if (row.getStatus() != ShipmentStatus.READY_TO_DISPATCH) {
                throw new BusinessException(ErrorCode.PACKAGE_NOT_READY, "Shipment %s is %s; it cannot leave"
                        .formatted(row.getCode(), row.getStatus()));
            }
        }
        // The parcels the plan placed on this vehicle - only those are checked against the scans (LP-3).
        Set<UUID> placedHere = ctx.placedHere();
        List<PackageJpaEntity> here = placedHere.isEmpty() ? List.of() : packages.findAllById(placedHere).stream()
                .filter(p -> p.getStatus() == PackageStatus.SEALED && p.getShipmentId() != null).toList();
        Map<UUID, Set<UUID>> parcelsByShipment = here.stream().collect(Collectors.groupingBy(
                PackageJpaEntity::getShipmentId, Collectors.mapping(PackageJpaEntity::getId, Collectors.toSet())));
        Map<UUID, String> labels = here.stream().collect(Collectors.toMap(PackageJpaEntity::getId,
                p -> p.getKit() == null ? p.getLabelCode() : "%s (kit part %d/%d)".formatted(p.getLabelCode(),
                        p.getKit().partNo(), p.getKit().partCount())));
        load.handOver(new DispatchLoad.Handover(request.carrierRepresentative(), request.vehiclePlate(),
                request.driverPhone(), request.signatureRef(), Boolean.TRUE.equals(request.signed()), request.note()),
                parcelsByShipment, labels, by, now);

        // LP-3: which shipments have now left with every vehicle carrying them.
        Map<UUID, DispatchLoadJpaEntity> live = planVehicles.liveLoads(plan);
        UUID thisVehicle = ctx.vehicle().id();
        java.util.function.Predicate<UUID> gone = v -> v.equals(thisVehicle)
                || live.get(v) != null && live.get(v).getStatus() == LoadStatus.DISPATCHED;
        Map<UUID, List<ShipmentJpaEntity>> byOrder = new java.util.TreeMap<>();
        vehiclesOfShipment.keySet().stream().map(rows::get).filter(Objects::nonNull)
                .filter(row -> row.getStatus() == ShipmentStatus.READY_TO_DISPATCH)
                .forEach(row -> byOrder.computeIfAbsent(row.getOrderId(), k -> new ArrayList<>()).add(row));
        Set<UUID> ordersOnBoard = onManifest.stream().map(id -> rows.get(id).getOrderId()).collect(Collectors.toSet());
        List<UUID> orderIds = new ArrayList<>(new java.util.TreeSet<>(ordersOnBoard));
        byOrder.keySet().stream().filter(id -> !ordersOnBoard.contains(id)).forEach(orderIds::add);
        Collections.sort(orderIds);
        int handedOver = 0;
        for (UUID orderId : orderIds) {
            List<ShipmentJpaEntity> ofOrder = byOrder.getOrDefault(orderId, List.of());
            OrderSummary order = orders.findById(orderId).orElse(null);
            List<ShipmentJpaEntity> completing = new ArrayList<>();
            if (PartialShipments.mayShipInParts(order)) {
                ofOrder.stream().filter(row -> vehiclesOfShipment.get(row.getId()).stream().allMatch(gone))
                        .forEach(completing::add);
            } else if (ofOrder.stream().allMatch(row -> vehiclesOfShipment.get(row.getId()).stream().allMatch(gone))) {
                completing.addAll(ofOrder);
            }
            // Only what this signature completes: a group whose vehicles all left before was handed over then.
            completing.removeIf(row -> !vehiclesOfShipment.get(row.getId()).contains(thisVehicle));
            if (completing.isEmpty()) {
                if (ordersOnBoard.contains(orderId)) {
                    // Goods of the order leave on this vehicle though no shipment of it is complete yet (LP-3): the
                    // order must let them go now - on hold, cancelled or unpaid is refused here, not at the last one.
                    requireOrderLets(orderId, onManifest.stream().map(rows::get)
                            .filter(row -> row.getOrderId().equals(orderId)).findFirst().orElseThrow());
                }
                continue;
            }
            completing.sort(Comparator.comparing(ShipmentJpaEntity::getReadyAt).thenComparing(ShipmentJpaEntity::getId));
            List<PackageJpaEntity> sealed = packages.findByShipmentIdIn(completing.stream().map(ShipmentJpaEntity::getId)
                    .toList()).stream().filter(p -> p.getStatus() == PackageStatus.SEALED).toList();
            Map<UUID, List<ShipmentHandover.Line>> linesByShipment = linesOf(sealed);
            Map<UUID, Long> parcelCount = sealed.stream().collect(Collectors.groupingBy(PackageJpaEntity::getShipmentId,
                    Collectors.counting()));
            for (int i = 0; i < completing.size(); i++) {
                ShipmentJpaEntity row = completing.get(i);
                List<ShipmentHandover.Line> leavingWith = new ArrayList<>();
                completing.subList(i + 1, completing.size()).forEach(later -> leavingWith.addAll(
                        linesByShipment.getOrDefault(later.getId(), List.of())));
                // The vehicle it left on last: this one, or the last of its vehicles to leave before.
                DispatchLoadJpaEntity lastLoad = onManifest.contains(row.getId()) ? header
                        : vehiclesOfShipment.get(row.getId()).stream().map(live::get).filter(Objects::nonNull)
                        .max(Comparator.comparing(DispatchLoadJpaEntity::getDispatchedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))).orElse(header);
                boolean thisOne = lastLoad == header;
                CarrierJpaEntity carrier = carriers.findById(thisOne ? load.carrierId() : lastLoad.getCarrierId())
                        .orElseThrow();
                handOver(row, lastLoad.getId(), lastLoad.getCode(), thisOne ? load : null, carrier,
                        linesByShipment.getOrDefault(row.getId(), List.of()), leavingWith,
                        parcelCount.getOrDefault(row.getId(), 0L).intValue(), by, now);
                handedOver++;
            }
        }
        header = store.save(load, header);
        boolean planDone = planVehicles.completeIfDispatched(ctx.header(), plan, now);
        log.info("Load {} (vehicle {} of plan {}) dispatched: {} parcel(s), {} shipment(s) handed over{}", load.code(),
                ctx.vehicle().seq(), plan.code(), load.activeParcels().size(), handedOver,
                planDone ? "; plan dispatched" : "");
        return detail(header, load);
    }

    /** The order's refusal to let goods leave (G3-Q4 balance, hold, cancellation), naming a shipment of it on board. */
    private void requireOrderLets(UUID orderId, ShipmentJpaEntity onBoard) {
        try {
            orders.requireHandoverAllowed(orderId);
        } catch (BusinessException refused) {
            List<FieldError> fields = new ArrayList<>(refused.fieldErrors());
            fields.add(FieldError.of("shipmentCode", onBoard.getCode()));
            BusinessException named = new BusinessException(refused.errorCode(), "Shipment %s: %s".formatted(
                    onBoard.getCode(), refused.getMessage()), fields);
            named.initCause(refused);
            throw named;
        }
    }

    // ------------------------------------------------------------------ the printable manifest

    @Override
    @Transactional(readOnly = true)
    public Manifest manifest(UUID loadId) {
        DispatchLoadJpaEntity header = loads.findById(loadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_NOT_FOUND));
        WarehouseScope.requireWarehouse(header.getWarehouseId());
        DispatchLoad load = store.load(header);
        // SCRUM-392: a plan's vehicle carries - and its manifest lists - only the parcels placed on it.
        PlanVehicles.PlanOfLoad ctx = header.getLoadPlanVehicleId() == null ? null : planVehicles.read(header);
        Set<UUID> placedHere = ctx == null ? null : ctx.placedHere();
        Map<UUID, DispatchLoad.Member> onManifest = load.members().stream().filter(DispatchLoad.Member::active)
                .collect(Collectors.toMap(DispatchLoad.Member::shipmentId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        Set<UUID> onVehicle = load.activeParcels().stream().map(DispatchLoad.Loaded::packageId)
                .collect(Collectors.toSet());
        Map<UUID, ShipmentJpaEntity> rows = shipments.findByIdIn(onManifest.keySet()).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        Map<UUID, List<PackageJpaEntity>> parcels = onManifest.isEmpty() ? Map.of()
                : packages.findByShipmentIdIn(onManifest.keySet()).stream()
                .filter(p -> p.getStatus() == PackageStatus.SEALED)
                .filter(p -> placedHere == null || placedHere.contains(p.getId()))
                .sorted(Comparator.comparing(PackageJpaEntity::getLabelCode))
                .collect(Collectors.groupingBy(PackageJpaEntity::getShipmentId));
        String currency = DEFAULT_CURRENCY;
        List<ManifestShipment> lines = new ArrayList<>();
        BigDecimal weight = BigDecimal.ZERO;
        int parcelCount = 0;
        for (DispatchLoad.Member member : onManifest.values()) {
            ShipmentJpaEntity row = rows.get(member.shipmentId());
            OrderSummary order = orders.findById(row.getOrderId()).orElse(null);
            if (order != null && order.total() != null) {
                currency = order.total().currency().getCurrencyCode();
            }
            var address = order == null ? null : order.shippingAddress();
            List<PackageJpaEntity> own = parcels.getOrDefault(row.getId(), List.of());
            BigDecimal shipmentWeight = own.stream().map(PackageJpaEntity::getWeightKg).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            weight = weight.add(shipmentWeight);
            parcelCount += own.size();
            BigDecimal cod = ctx != null ? BigDecimal.ZERO      // a full truckload carries no COD (LP-2)
                    : member.codAmount() != null ? member.codAmount() : member.labelCodAmount();
            lines.add(new ManifestShipment(row.getCode(), order == null ? null : order.orderNumber(),
                    address == null || address.recipientName() == null ? order == null ? null : order.contactName()
                            : address.recipientName(),
                    address == null || address.phone() == null ? order == null ? null : order.contactPhone()
                            : address.phone(),
                    address == null ? null : addressLine(address), own.size(), shipmentWeight,
                    cod == null ? BigDecimal.ZERO : cod,
                    ctx == null && member.codAmount() != null && member.labelCodAmount() != null
                            && member.codAmount().compareTo(member.labelCodAmount()) != 0,
                    own.stream().map(p -> new ManifestParcel(p.getLabelCode(), p.getWeightKg(), p.getLengthCm(),
                            p.getWidthCm(), p.getHeightCm(), onVehicle.contains(p.getId()), p.getKit())).toList()));
        }
        CarrierJpaEntity carrier = header.getCarrierId() == null ? null
                : carriers.findById(header.getCarrierId()).orElse(null);
        var warehouse = warehouses.findWarehouse(header.getWarehouseId());
        String dispatcher = header.getDispatchedBy() == null ? null
                : identities.displayNames(List.of(header.getDispatchedBy())).get(header.getDispatchedBy());
        return new Manifest(header.getCode(), header.getStatus().name(), warehouse.map(w -> w.name()).orElse(null),
                warehouse.map(w -> w.address()).orElse(null), carrier == null ? null : carrier.getCode(),
                carrier == null ? null : carrier.getName(), carrier == null ? null : carrier.getContactName(),
                carrier == null ? null : carrier.getContactPhone(), header.getVehiclePlate(), header.getDriverName(),
                header.getDriverPhone(), header.getCarrierRepresentative(), header.getSignatureRef(),
                header.getHandoverNote(), header.getDispatchedAt(), dispatcher, lines, lines.size(), parcelCount,
                weight, load.codTotal(), currency, clock.instant(), header.getMode().name(),
                ctx == null ? null : ctx.plan().code(), ctx == null || ctx.vehicle() == null ? null : ctx.vehicle().seq(),
                ctx == null || ctx.vehicle() == null ? null : ctx.vehicle().spec().code());
    }

    private static String addressLine(OrderSummary.AddressSummary a) {
        return java.util.stream.Stream.of(a.line1(), a.line2(), a.wardName(), a.provinceName())
                .filter(part -> part != null && !part.isBlank()).collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ internals

    private DispatchLoadJpaEntity lockLoad(UUID loadId) {
        DispatchLoadJpaEntity header = loads.lockById(loadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_NOT_FOUND));
        WarehouseScope.requireWarehouse(header.getWarehouseId());
        return header;
    }

    /** The code of the open or dispatched load each shipment is on, when it is not {@code loadId}. */
    private Map<UUID, String> otherLoads(Set<UUID> shipmentIds, UUID loadId) {
        List<DispatchLoadShipmentJpaEntity> on = members.findByShipmentIdInAndActiveTrue(shipmentIds).stream()
                .filter(m -> !m.getLoadId().equals(loadId)).toList();
        if (on.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> codes = loads.findByIdIn(on.stream().map(DispatchLoadShipmentJpaEntity::getLoadId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(DispatchLoadJpaEntity::getId,
                DispatchLoadJpaEntity::getCode));
        return on.stream().collect(Collectors.toMap(DispatchLoadShipmentJpaEntity::getShipmentId,
                m -> codes.getOrDefault(m.getLoadId(), "?"), (a, b) -> a));
    }

    private static DispatchLoad.ShipmentFacts facts(ShipmentJpaEntity row, String otherLoadCode) {
        return new DispatchLoad.ShipmentFacts(row.getId(), row.getCode(), row.getWarehouseId(), row.getDeliveryMode(),
                row.getStatus(), row.getCarrierId(), otherLoadCode);
    }

    private Map<UUID, CarrierJpaEntity> carriersOf(Set<UUID> ids) {
        // A HashMap: a load without a carrier (an FTL load still PLANNED) looks up null.
        return ids.isEmpty() ? new HashMap<>() : carriers.findAllById(ids).stream()
                .collect(Collectors.toMap(CarrierJpaEntity::getId, Function.identity(), (a, b) -> a, HashMap::new));
    }

    private LoadDetail detail(DispatchLoadJpaEntity header, DispatchLoad load) {
        List<DispatchLoad.Member> active = load.members().stream().filter(DispatchLoad.Member::active).toList();
        Set<UUID> ids = active.stream().map(DispatchLoad.Member::shipmentId).collect(Collectors.toSet());
        Map<UUID, ShipmentJpaEntity> rows = ids.isEmpty() ? Map.of() : shipments.findByIdIn(ids).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        // SCRUM-392: a plan's vehicle shows only the parcels placed on it.
        PlanVehicles.PlanOfLoad ctx = header.getLoadPlanVehicleId() == null ? null : planVehicles.read(header);
        Set<UUID> placedHere = ctx == null ? null : ctx.placedHere();
        Map<UUID, List<PackageJpaEntity>> parcels = ids.isEmpty() ? Map.of() : packages.findByShipmentIdIn(ids)
                .stream().filter(p -> p.getStatus() == PackageStatus.SEALED)
                .filter(p -> placedHere == null || placedHere.contains(p.getId()))
                .sorted(Comparator.comparing(PackageJpaEntity::getLabelCode))
                .collect(Collectors.groupingBy(PackageJpaEntity::getShipmentId));
        Map<UUID, DispatchLoad.Loaded> onVehicle = load.activeParcels().stream()
                .collect(Collectors.toMap(DispatchLoad.Loaded::packageId, Function.identity()));
        Map<UUID, String> orderNumbers = ids.isEmpty() ? new HashMap<>() : picks.findByOrderIdIn(rows.values().stream()
                .map(ShipmentJpaEntity::getOrderId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PickJpaEntity::getOrderId, PickJpaEntity::getOrderNumber, (a, b) -> a));
        List<LoadShipment> lines = new ArrayList<>();
        Map<UUID, UUID> tasks = context.tasks(rows.values());
        Map<UUID, String> currencies = context.currencies(rows.values().stream().map(ShipmentJpaEntity::getOrderId)
                .toList());
        int expected = 0;
        for (DispatchLoad.Member member : active) {
            ShipmentJpaEntity row = rows.get(member.shipmentId());
            List<LoadParcel> own = parcels.getOrDefault(member.shipmentId(), List.of()).stream().map(p -> {
                DispatchLoad.Loaded l = onVehicle.get(p.getId());
                return new LoadParcel(p.getId(), p.getLabelCode(), p.getWeightKg(), l != null,
                        l == null ? null : l.loadedAt(), l == null ? null : l.loadedBy(), p.getKit());
            }).toList();
            expected += own.size();
            lines.add(new LoadShipment(row.getId(), row.getCode(), row.getOrderId(),
                    orderNumbers.get(row.getOrderId()), tasks.get(row.getId()), row.getStatus().name(), own.size(),
                    (int) own.stream().filter(LoadParcel::loaded).count(), member.labelCodAmount(),
                    member.codAmount(), currencies.get(row.getOrderId()), own));
        }
        CarrierJpaEntity carrier = header.getCarrierId() == null ? null
                : carriers.findById(header.getCarrierId()).orElse(null);
        Set<UUID> people = new HashSet<>();
        if (header.getDispatchedBy() != null) {
            people.add(header.getDispatchedBy());
        }
        Map<UUID, String> names = people.isEmpty() ? new HashMap<>() : new HashMap<>(identities.displayNames(people));
        return new LoadDetail(header.getId(), header.getCode(), header.getWarehouseId(), header.getMode().name(),
                header.getStatus().name(), header.getCarrierId(), carrier == null ? null : carrier.getCode(),
                carrier == null ? null : carrier.getName(), header.getVehiclePlate(), header.getDriverName(),
                header.getDriverPhone(), header.getLoadingStartedAt(), header.getLoadingStartedBy(),
                header.getDispatchedAt(), header.getDispatchedBy(), names.get(header.getDispatchedBy()),
                header.getCarrierRepresentative(), header.getSignatureRef(), header.getHandoverNote(),
                header.getCancelledAt(), header.getCancelReason(), lines.size(), expected, onVehicle.size(),
                load.codTotal(), currencies.values().stream().findFirst().orElse(DEFAULT_CURRENCY), header.getVersion(),
                lines, ctx == null ? null : ctx.header().getId(), ctx == null ? null : ctx.plan().code(),
                ctx == null || ctx.vehicle() == null ? null : ctx.vehicle().seq(),
                ctx == null || ctx.vehicle() == null ? null : ctx.vehicle().spec().code(), header.getBookedAt(),
                header.getBookedBy(), header.getBookingCancelledAt(), header.getBookingCancelReason(),
                pendingUnloads(load));
    }

    private List<PendingUnload> pendingUnloads(DispatchLoad load) {
        List<DispatchLoad.Unload> pending = load.pendingUnloads();
        if (pending.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> labels = packages.findAllById(pending.stream().map(DispatchLoad.Unload::packageId).toList())
                .stream().collect(Collectors.toMap(PackageJpaEntity::getId, PackageJpaEntity::getLabelCode));
        Map<UUID, String> codes = shipments.findByIdIn(pending.stream().map(DispatchLoad.Unload::shipmentId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(ShipmentJpaEntity::getId,
                ShipmentJpaEntity::getCode));
        return pending.stream().map(u -> new PendingUnload(u.packageId(), labels.get(u.packageId()), u.shipmentId(),
                codes.get(u.shipmentId()), u.reason(), u.requestedAt())).toList();
    }

    private static LoadStatus loadStatus(String raw) {
        try {
            return LoadStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown load status",
                    List.of(FieldError.of("status", "must be one of " + java.util.Arrays.toString(
                            LoadStatus.values()))));
        }
    }
}
