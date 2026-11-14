package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.Specs;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.domain.UnloadReasonCode;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanVehicleJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.LoadNumbers;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.ParcelContents;
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

    private final DispatchLoadJpaRepository loads;
    private final DispatchLoadShipmentJpaRepository members;
    private final DispatchLoadPackageJpaRepository loaded;
    private final DispatchLoadStore store;
    private final LoadNumbers numbers;
    private final ShipmentJpaRepository shipments;
    private final com.stockflow.fulfillment.internal.repository.ShipmentLocks locks;
    private final PackageJpaRepository packages;
    private final ParcelContents contents;
    private final PickJpaRepository picks;
    private final CarrierJpaRepository carriers;
    private final ShipmentTimeline timeline;
    private final ShipmentContext context;
    private final OrderService orders;
    private final WarehouseService warehouses;
    private final IdentityService identities;
    private final PlanVehicles planVehicles;
    private final LeftBehindParcels leftBehind;
    private final Waybills waybills;
    private final com.stockflow.fulfillment.internal.repository.WaybillChangeJpaRepository waybillChanges;
    private final com.stockflow.fulfillment.internal.repository.CarrierBookingJpaRepository carrierBookings;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    DispatchService(DispatchLoadJpaRepository loads, DispatchLoadShipmentJpaRepository members,
                    DispatchLoadPackageJpaRepository loaded, DispatchLoadStore store, LoadNumbers numbers,
                    ShipmentJpaRepository shipments, com.stockflow.fulfillment.internal.repository.ShipmentLocks locks,
                    PackageJpaRepository packages,
                    ParcelContents contents, PickJpaRepository picks, CarrierJpaRepository carriers,
                    ShipmentTimeline timeline, ShipmentContext context, OrderService orders,
                    WarehouseService warehouses,
                    IdentityService identities, PlanVehicles planVehicles, LeftBehindParcels leftBehind,
                    Waybills waybills,
                    com.stockflow.fulfillment.internal.repository.WaybillChangeJpaRepository waybillChanges,
                    com.stockflow.fulfillment.internal.repository.CarrierBookingJpaRepository carrierBookings,
                    Clock clock) {
        this.loads = loads;
        this.members = members;
        this.loaded = loaded;
        this.store = store;
        this.numbers = numbers;
        this.shipments = shipments;
        this.locks = locks;
        this.packages = packages;
        this.contents = contents;
        this.picks = picks;
        this.carriers = carriers;
        this.timeline = timeline;
        this.context = context;
        this.orders = orders;
        this.warehouses = warehouses;
        this.identities = identities;
        this.planVehicles = planVehicles;
        this.leftBehind = leftBehind;
        this.waybills = waybills;
        this.waybillChanges = waybillChanges;
        this.carrierBookings = carrierBookings;
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
            filters.add(byCode.or(Specs.contains("vehiclePlate", search.trim()))
                    .or(Specs.contains("waybillNo", search.trim())).or(inPlanCode(search.trim())));
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
            return new LoadRow(row.getId(), row.getCode(), row.getWarehouseId(), row.getStatus().name(),
                    row.getCarrierId(),
                    Optional.ofNullable(carrierById.get(row.getCarrierId())).map(CarrierJpaEntity::getName).orElse(null),
                    row.getVehiclePlate(), row.getDriverName(), row.getShipmentCount(), row.getPackageCount(),
                    row.getLoadingStartedAt(), row.getDispatchedAt(), row.getVersion(),
                    vehicle == null ? null : vehicle.loadPlanId(), vehicle == null ? null : vehicle.loadPlanCode(),
                    vehicle == null ? null : vehicle.seq(), row.getWaybillNo());
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
                    recorded.get().getPackageId()).map(p -> p.getLabelCode().equalsIgnoreCase(code)
                            || code.equalsIgnoreCase(p.getLpn())).orElse(false);
            if (!same) {
                throw new BusinessException(ErrorCode.LOAD_SCAN_ID_CONFLICT,
                        "Scan %s was recorded for another package or load; send a new scanId".formatted(scanId));
            }
            return detail(header, load);
        }
        PackageJpaEntity parcel = parcelScanned(code);
        DispatchLoad.ShipmentFacts shipment = null;
        if (parcel.getShipmentId() != null) {
            ShipmentJpaEntity row = shipments.findById(parcel.getShipmentId()).orElseThrow();
            shipment = facts(row, otherLoads(Set.of(row.getId()), loadId).get(row.getId()));
        }
        String parcelElsewhere = loaded.findByPackageIdAndActiveTrue(parcel.getId())
                .filter(l -> !l.getLoadId().equals(loadId))
                .map(l -> loads.findById(l.getLoadId()).map(DispatchLoadJpaEntity::getCode).orElse("?"))
                .orElse(null);
        // SCRUM-474 UNLOAD-DESIGN point 4: a parcel of a vehicle called off stays locked until it is scanned off.
        Map<UUID, UUID> awaitingUnload = store.pendingUnloadLoads(List.of(parcel.getId()));
        if (!awaitingUnload.isEmpty()) {
            throw new BusinessException(ErrorCode.PACKAGE_UNLOAD_PENDING, ("Package %s is still on load %s, which was "
                    + "called off: scan it off there first").formatted(parcel.getLabelCode(),
                    loads.findById(awaitingUnload.get(parcel.getId())).map(DispatchLoadJpaEntity::getCode)
                            .orElse("?")));
        }
        // SCRUM-392: a vehicle takes only what its plan placed on it (read: placements change under the plan's lock,
        // and an order cancelled meanwhile finds the parcel on board and asks for it to be unloaded). A load of the
        // parcel shipping that was removed has no plan; it is no longer loading either, and refuses the scan.
        DispatchLoad.Placement placement = header.getLoadPlanVehicleId() == null ? null
                : planVehicles.placement(planVehicles.read(header), parcel.getId());
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
        if (locked.plan() == null) {
            // A load of the parcel shipping that was removed (V20261108000400): dispatched or cancelled, history only.
            if (locked.load().status() == LoadStatus.DISPATCHED) {
                return detail(locked.header(), locked.load());
            }
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, "Load %s is %s and cannot be handed over"
                    .formatted(locked.load().code(), locked.load().status()));
        }
        return handOverVehicle(locked, request, actor);
    }

    /**
     * One shipment leaves: the order is told in this transaction (A1) - which units, and what the customer may read of
     * it; the shipment takes its carrier and its load.
     *
     * <p>A shipment whose parcels record no contents (packed before #136; V20261021000300 made its shipment) leaves as
     * the whole order ({@code OrderService.markShipped}), as long as the order records no shipment: it is then marked
     * {@code wholeOrderHandover}, and its outcomes are the order's own.</p>
     */
    private void handOver(ShipmentJpaEntity row, UUID loadId, String loadCode, CarrierJpaEntity carrier,
                          List<ShipmentHandover.Line> lines, List<ShipmentHandover.Line> leavingWith, int parcels,
                          UUID by, Instant at) {
        Shipment shipment = row.toDomain();
        boolean wholeOrder = lines.isEmpty();
        OrderSummary order;
        try {
            if (wholeOrder) {
                order = orders.markShipped(row.getOrderId(), at, by);
            } else {
                // WF-05 fix plan, section 2: a shipment whose task picked a returned shipment's goods again tells the
                // order which delivery failure case it delivers.
                UUID redeliveryCase = row.getPackId() == null ? null
                        : picks.redeliveryCaseOfPack(row.getPackId()).orElse(null);
                order = orders.recordHandover(row.getOrderId(), new ShipmentHandover(row.getId(), lines, at, by,
                        new ShipmentHandover.Carriage(row.getCode(), parcels, carrier.getName()), leavingWith,
                        redeliveryCase));
            }
            shipment.handOver(loadId, carrier.getId(), wholeOrder, by, at);
        } catch (BusinessException refused) {
            // The order's refusal, naming the shipment; its code, message and fields kept.
            List<com.stockflow.common.api.FieldError> fields = new ArrayList<>(refused.fieldErrors());
            fields.add(com.stockflow.common.api.FieldError.of("shipmentCode", row.getCode()));
            String why = refused.getMessage() != null && refused.getMessage().contains(row.getCode())
                    ? refused.getMessage() : "Shipment %s: %s".formatted(row.getCode(), refused.getMessage());
            BusinessException named = new BusinessException(refused.errorCode(), why, fields);
            named.initCause(refused);
            throw named;
        }
        row.apply(shipment);
        shipments.saveAndFlush(row);
        timeline.record(shipment, ShipmentStatus.READY_TO_DISPATCH, order, null, carrier.getName(), loadCode);
    }

    /**
     * The parcel a loader scanned: a repack parcel by its label code, or a shipping carton by its LPN - the code its
     * shipping label prints (team plan 4C-6, kltn-docs 08 §4.3 step 6) - the newest labelled carton of that LPN.
     */
    private PackageJpaEntity parcelScanned(String code) {
        String upper = code.toUpperCase(Locale.ROOT);
        return packages.findByLabelCode(code)
                .or(() -> packages.findByLabelCode(upper))
                .or(() -> packages.findLabeledByLpn(upper).stream().findFirst())
                .orElseThrow(() -> new BusinessException(ErrorCode.PACKAGE_NOT_FOUND, "No package labelled " + code));
    }

    /**
     * Per shipment, the units of each order line in its shipping parcels (08 BR-01): a labelled carton's pick line, a
     * sealed repack parcel's {@code package_line} ({@link ParcelContents}).
     */
    private Map<UUID, List<ShipmentHandover.Line>> linesOf(List<PackageJpaEntity> sealed) {
        if (sealed.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> shipmentOf = sealed.stream().collect(Collectors.toMap(PackageJpaEntity::getId,
                PackageJpaEntity::getShipmentId));
        Map<UUID, Map<UUID, Integer>> totals = new HashMap<>();
        for (ParcelContents.Line line : contents.of(shipmentOf.keySet())) {
            totals.computeIfAbsent(shipmentOf.get(line.packageId()), id -> new LinkedHashMap<>())
                    .merge(line.orderLineId(), line.quantity(), Integer::sum);
        }
        Map<UUID, List<ShipmentHandover.Line>> result = new HashMap<>();
        totals.forEach((shipment, lines) -> result.put(shipment, lines.entrySet().stream()
                .map(e -> new ShipmentHandover.Line(e.getKey(), e.getValue())).toList()));
        return result;
    }

    // ------------------------------------------------------------------ the vehicle's life (SCRUM-392)

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
        if (locked.plan() != null && (load.status() == LoadStatus.PLANNED
                || load.status() == LoadStatus.VEHICLE_BOOKED)) {
            PlanVehicles.requireConfirmed(locked.plan());
        }
        CarrierJpaEntity carrier = carriers.findById(booking.carrierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND));
        if (!carrier.isActive()) {
            throw new BusinessException(ErrorCode.CARRIER_INACTIVE,
                    "Carrier %s is no longer in use".formatted(carrier.getCode()));
        }
        if (CarrierConnectionType.API_BOOKING.name().equals(carrier.getConnectionType())) {
            // 09 BR-29: the system creates the carrier's order itself; a booking typed in would leave the load without
            // the number only that order gives, and the signature would wait for it forever.
            throw new BusinessException(ErrorCode.CARRIER_BOOKS_THROUGH_API, ("Carrier %s is booked through its API, "
                    + "not by hand").formatted(carrier.getCode()));
        }
        if (carrierBookings.findLive(loadId).isPresent()) {
            // Rebooked by hand, the order at the API carrier would be held there with no load to cancel it for.
            throw new BusinessException(ErrorCode.CARRIER_BOOKING_IN_PROGRESS, ("Load %s is being booked or is booked "
                    + "through a carrier's API: cancel that booking first").formatted(load.code()));
        }
        String given = booking.waybillNo() == null || booking.waybillNo().isBlank() ? null : booking.waybillNo();
        DispatchLoadJpaEntity header = locked.header();
        String before = load.waybillNo();
        Instant now = clock.instant();
        if (load.book(carrier.getId(), booking.vehiclePlate(), booking.driverName(), booking.driverPhone(),
                given, actor.userId(), now)) {
            waybills.requireUnique(carrier.getId(), load.waybillNo(), load.id());
            DispatchLoadJpaEntity current = header;
            header = waybills.saving(() -> store.save(load, current));
            waybills.record(load.id(), before, load.waybillNo(), WaybillSource.MANUAL, actor.userId(), now,
                    load.waybillNo() == null ? "Booked with carrier " + carrier.getCode() : null);
            log.info("Load {} booked with carrier {}", load.code(), carrier.getCode());
        }
        return detail(header, load);
    }

    @Override
    public LoadDetail cancelBooking(UUID loadId, String reason, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        DispatchLoadJpaEntity header = locked.header();
        var live = carrierBookings.findLive(loadId).orElse(null);
        if (live != null && com.stockflow.fulfillment.internal.entity.CarrierBookingJpaEntity.UNCONFIRMED
                .equals(live.getStatus())) {
            if (reason == null || reason.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Cancelling a booking says why");
            }
            live.released(reason.strip());
            carrierBookings.saveAndFlush(live);
            log.info("Load {}: unconfirmed carrier booking released ({})", load.code(), reason.strip());
            return detail(header, load);
        }
        if (live != null) {
            // Booked or being booked through the carrier's API meanwhile: its order is cancelled there first.
            throw CarrierBookingService.inProgress(load.code(), live);
        }
        String before = load.waybillNo();
        Instant now = clock.instant();
        if (load.cancelBooking(reason, actor.userId(), now)) {
            header = store.save(load, header);
            waybills.record(load.id(), before, null, WaybillSource.MANUAL, actor.userId(), now,
                    "Booking cancelled: " + reason.strip());
            log.info("Load {}: booking cancelled ({})", load.code(), reason.trim());
        }
        return detail(header, load);
    }

    @Override
    public LoadDetail cancelCarrierBooking(UUID loadId, UUID bookingId, String reason, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        DispatchLoadJpaEntity header = locked.header();
        // First read of the booking in this transaction: the lock returns the row as it is now.
        var booking = carrierBookings.lockById(bookingId).orElse(null);
        if (booking == null || !com.stockflow.fulfillment.internal.entity.CarrierBookingJpaEntity.BOOKED
                .equals(booking.getStatus())) {
            return detail(header, load);
        }
        Instant now = clock.instant();
        booking.cancelled(reason, now);
        carrierBookings.saveAndFlush(booking);
        if (load.status() == LoadStatus.VEHICLE_BOOKED) {
            String before = load.waybillNo();
            load.cancelBooking(reason, actor.userId(), now);
            header = store.save(load, header);
            waybills.record(load.id(), before, null, WaybillSource.API, actor.userId(), now,
                    "Booking cancelled at the carrier: " + reason);
            log.info("Load {}: booking cancelled with its carrier order {} ({})", load.code(),
                    booking.getCarrierOrderId(), reason);
        } else {
            log.warn("Load {}: carrier order {} cancelled while the load is {}", load.code(),
                    booking.getCarrierOrderId(), load.status());
        }
        return detail(header, load);
    }

    // ------------------------------------------------------------------ what a carrier's webhook reported (3-5)

    @Override
    public boolean carrierWaybill(UUID loadId, String waybill, String plate, String driver, String phone) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        String before = load.waybillNo();
        if (load.status() == LoadStatus.DISPATCHED) {
            String given = waybill == null ? null : waybill.strip();
            if (Objects.equals(before, given)) {
                return false;
            }
            // 09 §4.5 step 7: a load that left with another number goes to the error list, never corrected silently.
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s left with waybill number %s; the "
                    + "carrier now reports %s").formatted(load.code(), before, given));
        }
        if (!load.recordWaybill(waybill, plate, driver, phone, null)) {
            return false;
        }
        waybills.requireUnique(load.carrierId(), load.waybillNo(), load.id());
        Instant now = clock.instant();
        DispatchLoadJpaEntity current = locked.header();
        waybills.saving(() -> store.save(load, current));
        waybills.record(load.id(), before, load.waybillNo(), WaybillSource.WEBHOOK, null, now, null);
        log.info("Load {}: waybill {} from the carrier's webhook", load.code(), load.waybillNo());
        return true;
    }

    @Override
    public boolean carrierDriverAssigned(UUID loadId, String driver, String phone) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        if (!load.carrierAssignedDriver(driver, phone)) {
            return false;
        }
        store.save(load, locked.header());
        log.info("Load {}: a driver took the carrier's order", load.code());
        return true;
    }

    @Override
    public boolean carrierDriverReleased(UUID loadId) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        if (!load.carrierReleasedDriver()) {
            return false;
        }
        store.save(load, locked.header());
        log.info("Load {}: the driver gave the carrier's order back", load.code());
        return true;
    }

    @Override
    public CarrierCancel carrierOrderCancelled(UUID loadId, UUID bookingId, Instant at) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        // First read of the booking in this transaction: the lock returns the row as it is now.
        var booking = carrierBookings.lockById(bookingId).orElse(null);
        if (booking == null || !com.stockflow.fulfillment.internal.entity.CarrierBookingJpaEntity.BOOKED
                .equals(booking.getStatus())) {
            return CarrierCancel.NOT_HELD;
        }
        if (load.status() != LoadStatus.VEHICLE_BOOKED && load.status() != LoadStatus.LOADING) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s is %s: its carrier reports order "
                    + "%s cancelled - settle it with the carrier").formatted(load.code(), load.status(),
                    booking.getCarrierOrderId()));
        }
        String reason = "The carrier cancelled order " + booking.getCarrierOrderId();
        booking.cancelled(reason, at);
        carrierBookings.saveAndFlush(booking);
        if (load.status() == LoadStatus.LOADING) {
            // The truck at the dock has no order any more: the load cannot be handed over on it (requireCarrierOrder).
            log.warn("Load {}: its carrier cancelled order {} while loading", load.code(), booking.getCarrierOrderId());
            return CarrierCancel.CANCELLED_WHILE_LOADING;
        }
        String before = load.waybillNo();
        load.cancelBooking(reason, null, at);
        store.save(load, locked.header());
        waybills.record(load.id(), before, null, WaybillSource.WEBHOOK, null, at, reason);
        log.info("Load {}: its carrier cancelled order {}; back to PLANNED", load.code(), booking.getCarrierOrderId());
        return CarrierCancel.UNBOOKED;
    }

    @Override
    public LoadDetail recordWaybill(UUID loadId, WaybillEntry entry, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        load.requireWaybillRecordable();
        waybills.requireTypeable(carriers.findById(load.carrierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND)));
        if (load.status() == LoadStatus.DISPATCHED) {
            // BR-24: after the vehicle left only the warehouse manager corrects it - a permission, never a role.
            actor.require(FulfillmentPermissions.WAYBILL_CORRECTIONS, com.stockflow.common.security.Action.UPDATE);
        }
        DispatchLoadJpaEntity header = locked.header();
        String before = load.waybillNo();
        if (load.recordWaybill(entry.waybillNo(), entry.vehiclePlate(), entry.driverName(), entry.driverPhone(),
                entry.reason())) {
            waybills.requireUnique(load.carrierId(), load.waybillNo(), load.id());
            Instant now = clock.instant();
            DispatchLoadJpaEntity current = header;
            header = waybills.saving(() -> store.save(load, current));
            waybills.record(load.id(), before, load.waybillNo(), WaybillSource.MANUAL, actor.userId(), now,
                    entry.reason());
            log.info("Load {}: waybill {} recorded{}", load.code(), load.waybillNo(),
                    load.status() == LoadStatus.DISPATCHED ? " (correction after dispatch)" : "");
        }
        return detail(header, load);
    }

    @Override
    public LoadDetail recordDriver(UUID loadId, DriverEntry entry, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        DispatchLoadJpaEntity header = locked.header();
        if (load.recordDriver(entry.driverName(), entry.driverPhone())) {
            header = store.save(load, header);
            log.info("Load {}: driver recorded by hand", load.code());
        }
        return detail(header, load);
    }

    @Override
    public LoadDetail applyCarrierBooking(UUID loadId, UUID carrierId, UUID bookingId, String orderId,
                                          String trackingLink, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        if (locked.plan() == null || load.status() != LoadStatus.PLANNED) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s is %s now: it was booked or "
                    + "called off while the carrier took the order").formatted(load.code(), load.status()));
        }
        PlanVehicles.requireConfirmed(locked.plan());
        // First read of the claim in this transaction: the lock returns the row as it is now. A claim made unconfirmed
        // by a call slower than ABANDONED_AFTER is still this order's.
        var booking = carrierBookings.lockById(bookingId)
                .filter(b -> com.stockflow.fulfillment.internal.entity.CarrierBookingJpaEntity.REQUESTED
                        .equals(b.getStatus()) || com.stockflow.fulfillment.internal.entity.CarrierBookingJpaEntity
                        .UNCONFIRMED.equals(b.getStatus()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "The booking of load %s was given up meanwhile".formatted(load.code())));
        Set<String> claimed = booking.getStops().stream().map(s -> String.valueOf(s.get("shipmentId")))
                .collect(Collectors.toSet());
        Set<String> manifest = load.activeShipments().stream().map(UUID::toString).collect(Collectors.toSet());
        if (!claimed.equals(manifest)) {
            // 09 BR-29: the order's drop-offs are the load's shipments; a load whose shipments changed meanwhile does
            // not take an order made for others.
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("The shipments of load %s changed while "
                    + "the carrier took the order").formatted(load.code()));
        }
        Instant now = clock.instant();
        load.book(carrierId, null, null, null, orderId, actor.userId(), now);
        waybills.requireUnique(carrierId, load.waybillNo(), load.id());
        DispatchLoadJpaEntity current = locked.header();
        DispatchLoadJpaEntity header = waybills.saving(() -> store.save(load, current));
        waybills.record(load.id(), null, load.waybillNo(), WaybillSource.API, actor.userId(), now, null);
        booking.booked(orderId, trackingLink, now);
        carrierBookings.saveAndFlush(booking);
        return detail(header, load);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WaybillChange> waybillHistory(UUID loadId) {
        DispatchLoadJpaEntity header = loads.findById(loadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_NOT_FOUND));
        WarehouseScope.requireWarehouse(header.getWarehouseId());
        var rows = waybillChanges.findByLoadIdOrderByChangedAtAscIdAsc(loadId);
        Set<UUID> people = rows.stream().map(r -> r.getChangedBy()).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> names = people.isEmpty() ? Map.of() : identities.displayNames(people);
        return rows.stream().map(r -> new WaybillChange(r.getOldWaybillNo(), r.getNewWaybillNo(), r.getSource(),
                r.getChangedBy(), r.getChangedBy() == null ? null : names.get(r.getChangedBy()), r.getChangedAt(),
                r.getReason())).toList();
    }

    @Override
    public LoadDetail startLoading(UUID loadId, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        if (locked.plan() != null && load.status() != LoadStatus.LOADING) {
            PlanVehicles.requireConfirmed(locked.plan());
        }
        DispatchLoadJpaEntity header = locked.header();
        Instant now = clock.instant();
        // 4B-8: the orders found unable to leave, answered with the load so the warehouse sees them now (08 BR-11).
        List<Dispatching.HandoverWarning> warnings = List.of();
        if (load.status() != LoadStatus.LOADING && locked.plan() != null) {
            // Review of #162: an order that may not ship in parts leaves whole with the plan - refused before anything
            // is on board, so no vehicle is stuck half way (BR-14 forbids reopening once loading starts).
            List<UUID> onBoard = load.activeShipments();
            Map<UUID, ShipmentJpaEntity> rows = shipments.findByIdIn(planShipments(locked.plan().plan(), onBoard))
                    .stream().collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
            Map<UUID, List<ShipmentHandover.Line>> inPlan = unitsInPlan(locked.plan().plan(), rows);
            onBoard.stream().map(rows::get).filter(Objects::nonNull)
                    .collect(Collectors.toMap(ShipmentJpaEntity::getOrderId, Function.identity(), (a, b) -> a,
                            java.util.TreeMap::new))
                    .forEach((orderId, row) -> named(row, () -> orders.requirePlanCarriesTheRest(orderId,
                            inPlan.getOrDefault(orderId, List.of()))));
            // 4B-8 (17 §4.4 step 17, 08 BR-11): loading goes ahead, but the orders that may not leave yet are named now -
            // the handover will refuse the vehicle until they may (09 BR-18).
            Set<UUID> orderIds = onBoard.stream().map(rows::get).filter(Objects::nonNull)
                    .map(ShipmentJpaEntity::getOrderId).collect(Collectors.toCollection(java.util.TreeSet::new));
            List<com.stockflow.order.api.HandoverBlock> blocks = orderIds.isEmpty() ? List.of()
                    : orders.handoverBlocks(orderIds, now);
            blocks.forEach(block -> log.warn("Load {}: order {} may not be handed over yet ({}, {} {} due)",
                    load.code(), block.orderNumber(), block.reasonCode(),
                    block.amountDue().stripTrailingZeros().toPlainString(), block.currency()));
            warnings = blocks.stream().map(block -> new Dispatching.HandoverWarning(block.orderId(),
                    block.orderNumber(), block.reasonCode(), block.amountDue(), block.currency(), block.batchSeq(),
                    block.windowStart())).toList();
        }
        if (load.startLoading(actor.userId(), now)) {
            header = store.save(load, header);
            if (locked.plan() != null) {
                // Decision 2 (review of #162): left-behind parcels split off at the point of no return (BR-14).
                leftBehind.splitOff(locked.plan().plan(), actor.userId(), now);
            }
            log.info("Load {}: loading started (plan {} locked, BR-14)", load.code(),
                    locked.plan() == null ? null : locked.plan().plan().code());
        }
        return detail(header, load).withHandoverWarnings(warnings);
    }

    /** The shipments of the plan's placed parcels, with {@code also}. */
    private static Set<UUID> planShipments(LoadPlan plan, List<UUID> also) {
        Set<UUID> placed = plan.placedPackageIds();
        Set<UUID> out = new HashSet<>(also);
        plan.members().stream().filter(m -> m.active() && placed.contains(m.packageId()))
                .forEach(m -> out.add(m.shipmentId()));
        return out;
    }

    @Override
    public LoadDetail unload(UUID loadId, String packageCode, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        String code = packageCode == null ? "" : packageCode.trim();
        PackageJpaEntity parcel = parcelScanned(code);
        Instant now = clock.instant();
        boolean changed = locked.load().confirmUnload(parcel.getId(), parcel.getLabelCode(), actor.userId(), now);
        return finishUnload(locked, parcel, changed, "scanned off", now);
    }

    @Override
    public LoadDetail overrideUnload(UUID loadId, UUID packageId, String note, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        PackageJpaEntity parcel = packages.findById(packageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PACKAGE_NOT_FOUND, "No package " + packageId));
        Instant now = clock.instant();
        boolean changed = locked.load().confirmUnloadWithoutScan(parcel.getId(), parcel.getLabelCode(), note,
                actor.userId(), now);
        return finishUnload(locked, parcel, changed, "confirmed off without a scan (label damaged)", now);
    }

    /** What follows a parcel coming off: save, call the vehicle off when empty, complete the plan when that was the last. */
    private LoadDetail finishUnload(Locked locked, PackageJpaEntity parcel, boolean changed, String how, Instant now) {
        DispatchLoad load = locked.load();
        DispatchLoadJpaEntity header = locked.header();
        if (changed) {
            boolean calledOff = PlanVehicles.callOffIfEmpty(load, now);
            header = store.save(load, header);
            log.info("Load {}: package {} unloaded ({}){}", load.code(), parcel.getLabelCode(), how,
                    calledOff ? "; nothing left, the load is called off" : "");
            if (calledOff && locked.plan() != null) {
                planVehicles.completeIfDispatched(locked.plan().header(), locked.plan().plan(), now);
            }
        }
        return detail(header, load);
    }

    @Override
    public ControlledCancelResult cancelControlled(UUID loadId, UnloadReasonCode code, String note, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        PlanVehicles.PlanOfLoad ctx = locked.plan();
        if (ctx == null) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s is %s and is no vehicle of a load "
                    + "plan: it cannot be cancelled under control").formatted(load.code(), load.status()));
        }
        DispatchLoadJpaEntity header = locked.header();
        if (load.status() == LoadStatus.CANCELLED && load.cancelReasonCode() == null) {
            // Called off by another path (dropped, auto-cancel): nothing to answer "as it is" - a replay only
            // makes sense after a controlled cancel.
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s is CANCELLED and was not called "
                    + "off under control").formatted(load.code()));
        }
        if (load.status() != LoadStatus.CANCELLED) {
            PlanVehicles.requireConfirmed(ctx);
        }
        Instant now = clock.instant();
        List<UUID> onManifest = load.activeShipments();
        if (!load.cancelControlled(code, note, actor.userId(), now)) {
            // Called off before: answered as it is, with the vehicle's live load when another took its place.
            DispatchLoadJpaEntity live = ctx.vehicle() == null ? null : planVehicles.liveLoads(ctx.plan())
                    .get(ctx.vehicle().id());
            return new ControlledCancelResult(detail(header, load), live == null ? null
                    : detail(live, store.load(live)));
        }
        header = store.save(load, header);
        log.info("Load {} (vehicle {} of plan {}) cancelled under control ({}: {}): {} parcel(s) to scan off",
                load.code(), ctx.vehicle() == null ? null : ctx.vehicle().seq(), ctx.plan().code(), code,
                LogSafe.text(note.trim()), load.pendingUnloads().size());
        if (onManifest.isEmpty()) {
            // Nothing left to carry (every order on it was cancelled; only parcels to scan off remain): no replacement,
            // as when the system calls an emptied vehicle off. It must not hold the plan back (BR-15): the plan is
            // DISPATCHED if every other vehicle left.
            boolean planDone = planVehicles.completeIfDispatched(ctx.header(), ctx.plan(), now);
            log.info("Load {}: no shipment left on its manifest, no replacement{}", load.code(),
                    planDone ? "; plan dispatched" : "");
            return new ControlledCancelResult(detail(header, load), null);
        }
        DispatchLoadJpaEntity replacementHeader = openReplacement(ctx, load, onManifest, actor.userId(), now);
        return new ControlledCancelResult(detail(header, load), detail(replacementHeader,
                store.load(replacementHeader)));
    }

    @Override
    public LoadDetail removeFromPlan(UUID loadId, UnloadReasonCode code, String note, CurrentUser actor) {
        Locked locked = lockForChange(loadId);
        DispatchLoad load = locked.load();
        PlanVehicles.PlanOfLoad ctx = locked.plan();
        if (ctx == null || ctx.vehicle() == null) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s is %s and is no vehicle of a load "
                    + "plan: it cannot be dropped from one").formatted(load.code(), load.status()));
        }
        DispatchLoadJpaEntity header = locked.header();
        if (!ctx.vehicle().active()) {
            // Dropped before: answered as it is. A retired vehicle of any other cause is not a drop.
            if (load.status() == LoadStatus.CANCELLED && load.cancelReasonCode() != null) {
                return detail(header, load);
            }
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s belongs to a vehicle that is no "
                    + "longer part of its plan").formatted(load.code()));
        }
        PlanVehicles.requireConfirmed(ctx);
        Instant now = clock.instant();
        // In memory only until saved below: a refusal of the load (not PLANNED, no code or note) or of the plan leaves
        // everything as it was.
        load.dropFromPlan(code, note, actor.userId(), now);
        planVehicles.requireOtherVehiclesLeft(ctx);
        requireNothingLeftSplit(ctx);
        header = store.save(load, header);
        boolean planDone = planVehicles.drop(ctx, now);
        log.info("Load {} (vehicle {} of plan {}) dropped from its plan ({}: {}){}", load.code(), ctx.vehicle().seq(),
                ctx.plan().code(), code, LogSafe.text(note), planDone ? "; plan dispatched" : "");
        return detail(header, load);
    }

    /**
     * UNLOAD-DROP: parcels on this vehicle go back to wait for a new plan only when nothing of theirs left on a vehicle
     * that departed: a shipment with parcels on both, or an order that may not ship in parts with
     * a shipment on a departed vehicle, would be left half gone. 409 {@code LOAD_PLAN_VEHICLE_SHARES_ORDER}.
     */
    private void requireNothingLeftSplit(PlanVehicles.PlanOfLoad ctx) {
        LoadPlan plan = ctx.plan();
        Set<UUID> here = ctx.placedHere();
        Set<UUID> hereShipments = new HashSet<>();
        Set<UUID> elsewhereShipments = new HashSet<>();
        Set<UUID> elsewhere = new HashSet<>(plan.placedPackageIds());
        elsewhere.removeAll(here);
        for (LoadPlan.Member m : plan.members()) {
            if (!m.active()) {
                continue;
            }
            if (here.contains(m.packageId())) {
                hereShipments.add(m.shipmentId());
            } else if (elsewhere.contains(m.packageId())) {
                elsewhereShipments.add(m.shipmentId());
            }
        }
        Set<UUID> shared = new HashSet<>(hereShipments);
        shared.retainAll(elsewhereShipments);
        if (!shared.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_PLAN_VEHICLE_SHARES_ORDER, ("Vehicle %d of load plan %s carries "
                    + "parcels of %d shipment(s) whose other parcels are on a vehicle that left")
                    .formatted(ctx.vehicle().seq(), plan.code(), shared.size()));
        }
        if (hereShipments.isEmpty() || elsewhereShipments.isEmpty()) {
            return;
        }
        Map<UUID, UUID> orderOf = shipments.findByIdIn(hereShipments).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, ShipmentJpaEntity::getOrderId));
        Set<UUID> elsewhereOrders = shipments.findByIdIn(elsewhereShipments).stream()
                .map(ShipmentJpaEntity::getOrderId).collect(Collectors.toSet());
        for (UUID orderId : new java.util.TreeSet<>(orderOf.values())) {
            if (elsewhereOrders.contains(orderId) && !PartialShipments.mayShipInParts(orders.findById(orderId)
                    .orElse(null))) {
                throw new BusinessException(ErrorCode.LOAD_PLAN_VEHICLE_SHARES_ORDER, ("Vehicle %d of load plan %s "
                        + "carries a shipment of an order that may not ship in parts, and another shipment of that "
                        + "order is on a vehicle that left").formatted(ctx.vehicle().seq(), plan.code()));
            }
        }
    }

    /**
     * UNLOAD-DESIGN point 2: the vehicle goes back to PLANNED with the same layout - a new load of the same plan vehicle,
     * with the manifest the called-off load had, to be booked with another truck. The plan's row is locked by the caller;
     * a shipment on the manifest is on no other plan's load (the plan's own vehicles may share it, LP-3).
     */
    private DispatchLoadJpaEntity openReplacement(PlanVehicles.PlanOfLoad ctx, DispatchLoad cancelled,
                                                  List<UUID> onManifest, UUID by, Instant at) {
        DispatchLoad replacement = DispatchLoad.planFullTruckload(Identifiers.newId(),
                numbers.next(LocalDate.ofInstant(at, BUSINESS_ZONE)), Identifiers.newId(), cancelled.warehouseId(),
                cancelled.loadPlanVehicleId());
        Map<UUID, ShipmentJpaEntity> rows = shipments.findByIdIn(onManifest).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        for (UUID shipmentId : onManifest) {
            ShipmentJpaEntity row = rows.get(shipmentId);
            replacement.addShipment(facts(row, null), by, at);
        }
        return store.insert(replacement);
    }

    /**
     * One vehicle of a load plan signed for (09 §4.2 step 5, BR-15, LP-3): every parcel the plan placed on it is on
     * board (a kit whole), nothing of a cancelled order is left to unload, and every order on it may let goods leave
     * now (the balance gate, G3-Q4). A shipment is handed over at its LAST vehicle's signature - an order that does
     * not allow partial shipments with all its shipments in the plan at once - one shipment at a time (the order told
     * in this transaction, PACK-PARTIAL). Once every vehicle has left, the plan is DISPATCHED.
     */
    /**
     * A load riding on a carrier's order (its waybill number is that order's id) leaves only while the carrier still
     * holds the order: one cancelled while loading started (a cancel racing the loading, the carrier calling it off)
     * would send a truck nobody booked (09 BR-29).
     */
    private void requireCarrierOrder(DispatchLoad load) {
        if (load.waybillNo() == null) {
            return;
        }
        carrierBookings.findByLoads(List.of(load.id())).stream()
                .filter(b -> load.waybillNo().equals(b.getCarrierOrderId())).findFirst()
                .filter(b -> !com.stockflow.fulfillment.internal.entity.CarrierBookingJpaEntity.BOOKED
                        .equals(b.getStatus()))
                .ifPresent(gone -> {
                    throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s rides on carrier order "
                            + "%s, which is %s: it cannot leave on it - unload it with a controlled cancel")
                            .formatted(load.code(), gone.getCarrierOrderId(), gone.getStatus()));
                });
    }

    /**
     * 4B-8 (09 BR-18): the vehicle is signed over whole or not at all - while any order on it may not let its goods
     * leave (an installment before delivery of its batch unpaid, its batch's window not open, a hold), the handover is
     * refused before anything is recorded, listing every such order with why and what it owes ({@code details.orders}).
     * Each order is checked again under its own lock as it is handed over.
     */
    private void requireEveryOrderMayLeave(Set<UUID> orderIds, Instant at) {
        if (orderIds.isEmpty()) {
            return;
        }
        List<com.stockflow.order.api.HandoverBlock> blocks = orders.handoverBlocks(orderIds, at);
        if (blocks.isEmpty()) {
            return;
        }
        List<Map<String, Object>> listed = blocks.stream().map(block -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("orderId", block.orderId().toString());
            row.put("orderNumber", block.orderNumber());
            row.put("reasonCode", block.reasonCode());
            row.put("amountDue", block.amountDue().stripTrailingZeros().toPlainString());
            row.put("currency", block.currency());
            if (block.batchSeq() != null) {
                row.put("deliveryBatchSeq", block.batchSeq());
            }
            if (block.windowStart() != null) {
                row.put("windowStart", block.windowStart().toString());
            }
            return row;
        }).toList();
        throw new BusinessException(ErrorCode.LOAD_HANDOVER_BLOCKED, "The vehicle cannot be handed over: "
                + blocks.stream().map(b -> "%s (%s, %s %s due)".formatted(b.orderNumber(), b.reasonCode(),
                        b.amountDue().stripTrailingZeros().toPlainString(), b.currency()))
                .collect(Collectors.joining(", ")), List.of(), Map.of("orders", listed));
    }

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
        requireCarrierOrder(load);
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
        // Every shipment of the orders in the plan, locked in id order before anything is read: another vehicle
        // signing one of them meanwhile is wholly before or wholly after this one.
        Map<UUID, ShipmentJpaEntity> rows = involved.isEmpty() ? Map.of() : locks.lockOfOrders(locks.ordersOf(involved))
                .stream().collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        for (UUID id : onManifest) {
            ShipmentJpaEntity row = rows.get(id);
            if (row.getStatus() != ShipmentStatus.READY_TO_DISPATCH) {
                throw new BusinessException(ErrorCode.PACKAGE_NOT_READY, "Shipment %s is %s; it cannot leave"
                        .formatted(row.getCode(), row.getStatus()));
            }
        }
        requireEveryOrderMayLeave(onManifest.stream().map(rows::get).filter(Objects::nonNull)
                .map(ShipmentJpaEntity::getOrderId).collect(Collectors.toCollection(java.util.TreeSet::new)), now);
        // The parcels the plan placed on this vehicle - only those are checked against the scans (LP-3).
        Set<UUID> placedHere = ctx.placedHere();
        List<PackageJpaEntity> here = placedHere.isEmpty() ? List.of() : packages.findAllById(placedHere).stream()
                .filter(p -> p.getStatus().ships() && p.getShipmentId() != null).toList();
        Map<UUID, Set<UUID>> parcelsByShipment = here.stream().collect(Collectors.groupingBy(
                PackageJpaEntity::getShipmentId, Collectors.mapping(PackageJpaEntity::getId, Collectors.toSet())));
        Map<UUID, String> labels = here.stream().collect(Collectors.toMap(PackageJpaEntity::getId,
                PackageJpaEntity::getLabelCode));
        load.handOver(new DispatchLoad.Handover(request.carrierRepresentative(), request.vehiclePlate(),
                request.driverPhone(), request.signatureRef(), Boolean.TRUE.equals(request.signed()), request.note()),
                parcelsByShipment, labels, by, now);

        Map<UUID, List<ShipmentHandover.Line>> unitsInPlan = unitsInPlan(plan, rows);
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
            // Only what this signature completes: a group whose vehicles all left before was handed over then. An
            // order that may not ship in parts is one group (review of #162): its shipments on vehicles that left
            // before are handed over now with the ones on this vehicle.
            if (PartialShipments.mayShipInParts(order)) {
                completing.removeIf(row -> !vehiclesOfShipment.get(row.getId()).contains(thisVehicle));
            } else if (completing.stream().noneMatch(row -> vehiclesOfShipment.get(row.getId()).contains(thisVehicle))) {
                completing.clear();
            }
            if (ordersOnBoard.contains(orderId)) {
                // Goods of the order leave on this vehicle (LP-3): the order lets them go now - on hold, cancelled,
                // unpaid or (no partial shipments) units outside the plan are refused here, at every vehicle - and
                // records that goods left: from now it is no longer cancelled (review of #162).
                recordGoodsLeft(orderId, unitsInPlan.getOrDefault(orderId, List.of()), now, onManifest.stream()
                        .map(rows::get).filter(row -> row.getOrderId().equals(orderId)).findFirst().orElseThrow());
                // 17 BR-27 / E23: the customer hears of each vehicle with goods of the order, in the portal's inbox.
                if (order != null) {
                    String carrierName = carriers.findById(load.carrierId()).map(CarrierJpaEntity::getName)
                            .orElse(null);
                    timeline.vehicleLeft(new com.stockflow.contracts.VehicleHandedOver(Identifiers.newId(), load.id(),
                            load.code(), load.warehouseId(), orderId, order.orderNumber(), order.customerId(),
                            carrierName, load.waybillNo(), load.snapshot().vehiclePlate(), now));
                }
            }
            if (completing.isEmpty()) {
                continue;
            }
            completing.sort(Comparator.comparing(ShipmentJpaEntity::getReadyAt).thenComparing(ShipmentJpaEntity::getId));
            List<PackageJpaEntity> sealed = packages.findByShipmentIdIn(completing.stream().map(ShipmentJpaEntity::getId)
                    .toList()).stream().filter(p -> p.getStatus().ships()).toList();
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
                handOver(row, lastLoad.getId(), lastLoad.getCode(), carrier,
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

    /**
     * Per order, the units of its parcels placed in the plan whose shipment still waits for dispatch (the partial check
     * of the order's gate). Orders whose parcels record no contents get none (not checked, as everywhere).
     */
    private Map<UUID, List<ShipmentHandover.Line>> unitsInPlan(LoadPlan plan, Map<UUID, ShipmentJpaEntity> rows) {
        Set<UUID> placed = plan.placedPackageIds();
        Map<UUID, UUID> shipmentOf = new HashMap<>();
        plan.members().stream().filter(m -> m.active() && placed.contains(m.packageId()))
                .forEach(m -> shipmentOf.put(m.packageId(), m.shipmentId()));
        Map<UUID, Map<UUID, Integer>> totals = new HashMap<>();
        if (!shipmentOf.isEmpty()) {
            for (ParcelContents.Line line : contents.of(shipmentOf.keySet())) {
                ShipmentJpaEntity row = rows.get(shipmentOf.get(line.packageId()));
                if (row != null && row.getStatus() == ShipmentStatus.READY_TO_DISPATCH) {
                    totals.computeIfAbsent(row.getOrderId(), k -> new LinkedHashMap<>())
                            .merge(line.orderLineId(), line.quantity(), Integer::sum);
                }
            }
        }
        Map<UUID, List<ShipmentHandover.Line>> out = new HashMap<>();
        totals.forEach((order, lines) -> out.put(order, lines.entrySet().stream()
                .map(e -> new ShipmentHandover.Line(e.getKey(), e.getValue())).toList()));
        return out;
    }

    /** The order's gate as goods leave (G3-Q4 balance, hold, cancellation, partial), naming a shipment of it on board. */
    private void recordGoodsLeft(UUID orderId, List<ShipmentHandover.Line> inPlan, Instant at,
                                 ShipmentJpaEntity onBoard) {
        named(onBoard, () -> orders.recordGoodsLeft(orderId, at, inPlan));
    }

    /** The order's refusal of {@code call}, naming a shipment of it with its code, message and fields kept. */
    private static void named(ShipmentJpaEntity onBoard, Runnable call) {
        try {
            call.run();
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
                .filter(p -> p.getStatus().ships())
                .filter(p -> placedHere == null || placedHere.contains(p.getId()))
                .sorted(Comparator.comparing(PackageJpaEntity::getLabelCode))
                .collect(Collectors.groupingBy(PackageJpaEntity::getShipmentId));
        List<ManifestShipment> lines = new ArrayList<>();
        BigDecimal weight = BigDecimal.ZERO;
        int parcelCount = 0;
        for (DispatchLoad.Member member : onManifest.values()) {
            ShipmentJpaEntity row = rows.get(member.shipmentId());
            OrderSummary order = orders.findById(row.getOrderId()).orElse(null);
            var address = order == null ? null : order.shippingAddress();
            List<PackageJpaEntity> own = parcels.getOrDefault(row.getId(), List.of());
            BigDecimal shipmentWeight = own.stream().map(PackageJpaEntity::getWeightKg).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            weight = weight.add(shipmentWeight);
            parcelCount += own.size();
            lines.add(new ManifestShipment(row.getCode(), order == null ? null : order.orderNumber(),
                    address == null || address.recipientName() == null ? order == null ? null : order.contactName()
                            : address.recipientName(),
                    address == null || address.phone() == null ? order == null ? null : order.contactPhone()
                            : address.phone(),
                    address == null ? null : addressLine(address), own.size(), shipmentWeight,
                    own.stream().map(p -> new ManifestParcel(p.getLabelCode(), p.getWeightKg(), p.getLengthCm(),
                            p.getWidthCm(), p.getHeightCm(), onVehicle.contains(p.getId()))).toList()));
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
                weight, clock.instant(), ctx == null ? null : ctx.plan().code(), ctx == null || ctx.vehicle() == null ? null : ctx.vehicle().seq(),
                ctx == null || ctx.vehicle() == null ? null : ctx.vehicle().spec().code(), header.getWaybillNo());
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
        return new DispatchLoad.ShipmentFacts(row.getId(), row.getCode(), row.getWarehouseId(), row.getStatus(),
                otherLoadCode);
    }

    private Map<UUID, CarrierJpaEntity> carriersOf(Set<UUID> ids) {
        // A HashMap: a load without a carrier (still PLANNED) looks up null.
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
                .stream().filter(p -> p.getStatus().ships())
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
        int expected = 0;
        for (DispatchLoad.Member member : active) {
            ShipmentJpaEntity row = rows.get(member.shipmentId());
            List<LoadParcel> own = parcels.getOrDefault(member.shipmentId(), List.of()).stream().map(p -> {
                DispatchLoad.Loaded l = onVehicle.get(p.getId());
                return new LoadParcel(p.getId(), p.getLabelCode(), p.getWeightKg(), l != null,
                        l == null ? null : l.loadedAt(), l == null ? null : l.loadedBy());
            }).toList();
            expected += own.size();
            lines.add(new LoadShipment(row.getId(), row.getCode(), row.getOrderId(),
                    orderNumbers.get(row.getOrderId()), tasks.get(row.getId()), row.getStatus().name(), own.size(),
                    (int) own.stream().filter(LoadParcel::loaded).count(), own));
        }
        CarrierJpaEntity carrier = header.getCarrierId() == null ? null
                : carriers.findById(header.getCarrierId()).orElse(null);
        Set<UUID> people = new HashSet<>();
        if (header.getDispatchedBy() != null) {
            people.add(header.getDispatchedBy());
        }
        Map<UUID, String> names = people.isEmpty() ? new HashMap<>() : new HashMap<>(identities.displayNames(people));
        DispatchLoadJpaEntity replacement = replacementOf(header, ctx);
        return new LoadDetail(header.getId(), header.getCode(), header.getWarehouseId(), header.getStatus().name(),
                header.getCarrierId(), carrier == null ? null : carrier.getCode(),
                carrier == null ? null : carrier.getName(), header.getVehiclePlate(), header.getDriverName(),
                header.getDriverPhone(), header.getLoadingStartedAt(), header.getLoadingStartedBy(),
                header.getDispatchedAt(), header.getDispatchedBy(), names.get(header.getDispatchedBy()),
                header.getCarrierRepresentative(), header.getSignatureRef(), header.getHandoverNote(),
                header.getCancelledAt(), header.getCancelReason(), lines.size(), expected, onVehicle.size(),
                header.getVersion(), lines, ctx == null ? null : ctx.header().getId(), ctx == null ? null : ctx.plan().code(),
                ctx == null || ctx.vehicle() == null ? null : ctx.vehicle().seq(),
                ctx == null || ctx.vehicle() == null ? null : ctx.vehicle().spec().code(), header.getBookedAt(),
                header.getBookedBy(), header.getBookingCancelledAt(), header.getBookingCancelReason(),
                pendingUnloads(load), load.status() == LoadStatus.CANCELLED && header.getBookedAt() != null,
                header.getCancelReasonCode() == null ? null : header.getCancelReasonCode().name(),
                replacement == null ? null : replacement.getId(), replacement == null ? null : replacement.getCode(),
                header.getWaybillNo(), carrier == null ? null : carrier.getConnectionType(),
                carrierBookings.findByLoads(List.of(header.getId())).stream().findFirst()
                        .map(b -> new CarrierBookingView(b.getStatus(), b.getCarrierOrderId(), b.getTrackingLink(),
                                b.getRequestedAt(), b.getBookedAt(), b.getLastError())).orElse(null));
    }

    /**
     * The live load that took the place of a vehicle called off under control (SCRUM-474), null when there is none: the
     * load is not such a cancelled vehicle, or nothing replaced it (nothing left to carry).
     */
    private DispatchLoadJpaEntity replacementOf(DispatchLoadJpaEntity header, PlanVehicles.PlanOfLoad ctx) {
        if (header.getStatus() != LoadStatus.CANCELLED || header.getCancelReasonCode() == null || ctx == null
                || ctx.vehicle() == null) {
            return null;
        }
        return planVehicles.liveLoads(ctx.plan()).get(ctx.vehicle().id());
    }

    /** The parcels still to take off (SCRUM-392, SCRUM-474). */
    private List<PendingUnload> pendingUnloads(DispatchLoad load) {
        List<DispatchLoad.Unload> pending = load.pendingUnloads();
        if (pending.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> labels = packages.findAllById(pending.stream().map(DispatchLoad.Unload::packageId).toList())
                .stream().collect(Collectors.toMap(PackageJpaEntity::getId, PackageJpaEntity::getLabelCode));
        Map<UUID, String> codes = shipments.findByIdIn(pending.stream()
                .map(DispatchLoad.Unload::shipmentId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, ShipmentJpaEntity::getCode));
        return pending.stream().map(u -> new PendingUnload(u.packageId(), labels.get(u.packageId()),
                u.shipmentId(), codes.get(u.shipmentId()), u.reason(), u.requestedAt(),
                u.reasonCode() == null ? null : u.reasonCode().name())).toList();
    }

    /** SCRUM-392: the load is a vehicle of a plan whose code contains the term - one subquery, no row per plan. */
    private static Specification<DispatchLoadJpaEntity> inPlanCode(String term) {
        return (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var vehicle = sub.from(LoadPlanVehicleJpaEntity.class);
            var plan = sub.from(LoadPlanJpaEntity.class);
            sub.select(vehicle.get("id")).where(cb.equal(vehicle.get("loadPlanId"), plan.get("id")),
                    Specs.<LoadPlanJpaEntity>contains("code", term).toPredicate(plan, query, cb));
            return root.get("loadPlanVehicleId").in(sub);
        };
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
