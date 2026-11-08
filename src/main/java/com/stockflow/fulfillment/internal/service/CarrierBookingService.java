package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.entity.CarrierBookingJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierPickupPointJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierServiceCodeJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierBookingJpaRepository;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.CarrierServiceCodeJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * See {@link Bookings}. Booking through a carrier's API takes three steps, so that no transaction - no row lock, no
 * connection - is held while the carrier answers:
 * <ol>
 *   <li>a short transaction checks the load, finds the pickup point and the service code, builds the route and claims
 *       the booking ({@code REQUESTED}; {@code uk_carrier_booking_live} lets one claim per load through);</li>
 *   <li>the carrier is called, with nothing open;</li>
 *   <li>a short transaction books the load under its locks ({@link Dispatching#applyCarrierBooking}) - or records the
 *       refusal, leaving the load PLANNED.</li>
 * </ol>
 * If the load moved between the first and the third step (its plan reopened, someone else booked it), the order the
 * carrier just created is cancelled there again, so no truck comes for a load that no longer wants it. A claim whose
 * call never came back is given up after {@link #ABANDONED_AFTER} (the sweeper's and the next booking's job).
 */
@Service
class CarrierBookingService implements Bookings {

    static final Duration ABANDONED_AFTER = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(CarrierBookingService.class);

    private final CarrierJpaRepository carriers;
    private final DispatchLoadJpaRepository loads;
    private final DispatchLoadStore store;
    private final PlanVehicles planVehicles;
    private final ShipmentJpaRepository shipments;
    private final OrderService orders;
    private final CarrierServiceCodeJpaRepository serviceCodes;
    private final CarrierBookingJpaRepository bookings;
    private final CarrierSecrets secrets;
    private final CarrierBookingGateway gateway;
    private final Dispatching dispatching;
    private final TransactionOperations tx;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    CarrierBookingService(CarrierJpaRepository carriers, DispatchLoadJpaRepository loads, DispatchLoadStore store,
                          PlanVehicles planVehicles, ShipmentJpaRepository shipments, OrderService orders,
                          CarrierServiceCodeJpaRepository serviceCodes, CarrierBookingJpaRepository bookings,
                          CarrierSecrets secrets, CarrierBookingGateway gateway, Dispatching dispatching,
                          TransactionOperations tx, Clock clock) {
        this.carriers = carriers;
        this.loads = loads;
        this.store = store;
        this.planVehicles = planVehicles;
        this.shipments = shipments;
        this.orders = orders;
        this.serviceCodes = serviceCodes;
        this.bookings = bookings;
        this.secrets = secrets;
        this.gateway = gateway;
        this.dispatching = dispatching;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Dispatching.LoadDetail book(UUID loadId, Request request, CurrentUser actor) {
        CarrierJpaEntity carrier = tx.execute(s -> carriers.findById(request.carrierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND)));
        if (!CarrierConnectionType.API_BOOKING.name().equals(carrier.getConnectionType())) {
            return tx.execute(s -> dispatching.book(loadId, new Dispatching.Booking(request.carrierId(),
                    request.vehiclePlate(), request.driverName(), request.driverPhone(), request.waybillNo()), actor));
        }
        if (present(request.vehiclePlate()) || present(request.driverName()) || present(request.driverPhone())
                || present(request.waybillNo())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("Carrier %s gives the waybill number, the "
                    + "vehicle and the driver itself once a driver takes the order").formatted(carrier.getCode()));
        }
        Prepared prepared = tx.execute(s -> prepare(loadId, carrier, request, actor));
        CarrierBookingGateway.Placed placed;
        try {
            placed = gateway.place(prepared.caller(), prepared.order());
        } catch (CarrierBookingGateway.CarrierCallFailed failed) {
            tx.executeWithoutResult(s -> bookings.lockById(prepared.bookingId())
                    .ifPresent(b -> b.failed(failed.getMessage())));
            throw refusal(carrier, failed, "book");
        }
        try {
            Dispatching.LoadDetail booked = tx.execute(s -> dispatching.applyCarrierBooking(loadId, carrier.getId(),
                    prepared.bookingId(), placed.orderId(), placed.trackingLink(), actor));
            log.info("Load {} booked with carrier {} through its API: order {}", booked.code(), carrier.getCode(),
                    placed.orderId());
            return booked;
        } catch (RuntimeException moved) {
            // The carrier created an order for a load that no longer takes it: cancel it there again.
            compensate(prepared, placed, moved.getMessage());
            throw moved;
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Dispatching.LoadDetail cancelBooking(UUID loadId, String reason, CurrentUser actor) {
        CarrierBookingJpaEntity held = tx.execute(s -> {
            DispatchLoadJpaEntity header = loads.findById(loadId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_NOT_FOUND));
            WarehouseScope.requireWarehouse(header.getWarehouseId());
            return bookings.findLive(loadId).filter(b -> CarrierBookingJpaEntity.BOOKED.equals(b.getStatus())
                    && header.getStatus() == LoadStatus.VEHICLE_BOOKED).orElse(null);
        });
        if (held == null) {
            return tx.execute(s -> dispatching.cancelBooking(loadId, reason, actor));
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Cancelling a booking says why");
        }
        CarrierJpaEntity carrier = tx.execute(s -> carriers.findById(held.getCarrierId()).orElseThrow());
        try {
            gateway.cancel(secrets.caller(carrier, held.getId()), held.getCarrierOrderId());
        } catch (CarrierBookingGateway.CarrierCallFailed failed) {
            throw new BusinessException(failed.answered() ? ErrorCode.CARRIER_CANCEL_REFUSED
                    : ErrorCode.EXTERNAL_SERVICE_ERROR, ("Carrier %s did not cancel order %s (%s): settle it with the "
                    + "carrier").formatted(carrier.getCode(), held.getCarrierOrderId(), failed.getMessage()),
                    List.of(FieldError.of("carrier", failed.getMessage())));
        }
        try {
            return tx.execute(s -> {
                // Plan, load, then booking: the order applyCarrierBooking locks them in. The detail is read after the
                // booking is cancelled, or it would still show the order as booked.
                dispatching.cancelBooking(loadId, reason, actor);
                bookings.lockById(held.getId()).ifPresent(b -> {
                    b.cancelled(reason.strip(), clock.instant());
                    bookings.saveAndFlush(b);
                });
                return dispatching.get(loadId);
            });
        } catch (RuntimeException moved) {
            // The order is gone at the carrier whatever the load did meanwhile: say so on the booking.
            tx.executeWithoutResult(s -> bookings.lockById(held.getId()).ifPresent(b -> {
                b.cancelled(reason.strip(), clock.instant());
            }));
            log.warn("Load {}: order {} cancelled at carrier {}, but the booking could not be cancelled here: {}",
                    loadId, held.getCarrierOrderId(), carrier.getCode(), moved.getMessage());
            throw moved;
        }
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<Service> services(UUID carrierId, String cityId) {
        if (cityId == null || !cityId.strip().matches("^[!-~]{1,32}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "cityId: the carrier's city id, e.g. SGN",
                    List.of(FieldError.of("cityId", "1-32 characters, no spaces")));
        }
        CarrierJpaEntity carrier = tx.execute(s -> carriers.findById(carrierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND)));
        if (!CarrierConnectionType.API_BOOKING.name().equals(carrier.getConnectionType())) {
            throw new BusinessException(ErrorCode.CARRIER_NOT_API_BOOKING,
                    "Carrier %s does not book through an API".formatted(carrier.getCode()));
        }
        try {
            return gateway.services(secrets.caller(carrier, null), cityId.strip()).stream()
                    .map(o -> new Service(o.id(), o.name(), o.cityId())).toList();
        } catch (CarrierBookingGateway.CarrierCallFailed failed) {
            throw refusal(carrier, failed, "list the services of");
        }
    }

    // ------------------------------------------------------------------ step 1

    private record Prepared(UUID bookingId, CarrierBookingGateway.Caller caller, CarrierBookingGateway.Order order) { }

    private Prepared prepare(UUID loadId, CarrierJpaEntity carrier, Request request, CurrentUser actor) {
        DispatchLoadJpaEntity header = loads.findById(loadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_NOT_FOUND));
        WarehouseScope.requireWarehouse(header.getWarehouseId());
        if (!carrier.isActive()) {
            throw new BusinessException(ErrorCode.CARRIER_INACTIVE,
                    "Carrier %s is no longer in use".formatted(carrier.getCode()));
        }
        if (header.getStatus() != LoadStatus.PLANNED || header.getLoadPlanVehicleId() == null) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Load %s is %s: a vehicle is booked "
                    + "through the carrier's API from PLANNED - cancel the booking first").formatted(header.getCode(),
                    header.getStatus()));
        }
        PlanVehicles.PlanOfLoad ctx = planVehicles.read(header);
        PlanVehicles.requireConfirmed(ctx);
        CarrierPickupPointJpaEntity pickup = bookings.pickupPoint(carrier.getId(), header.getWarehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_PICKUP_NOT_CONFIGURED, ("Carrier %s has no "
                        + "pickup point at this warehouse").formatted(carrier.getCode())));
        UUID vehicleType = ctx.vehicle().spec().typeId();
        CarrierServiceCodeJpaEntity service = serviceCodes.findByCarrierIdAndVehicleTypeIdAndProvinceCode(
                carrier.getId(), vehicleType, pickup.getProvinceCode()).orElseThrow(() -> new BusinessException(
                ErrorCode.CARRIER_SERVICE_NOT_CONFIGURED, ("Carrier %s has no service code for vehicle type %s in "
                + "province %s").formatted(carrier.getCode(), ctx.vehicle().spec().code(), pickup.getProvinceCode())));

        DispatchLoad load = store.load(header);
        List<UUID> manifest = load.activeShipments();
        if (manifest.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_EMPTY,
                    "Load %s has no shipment on its manifest".formatted(header.getCode()));
        }
        List<UUID> order = request.stopShipmentIds() == null ? manifest : request.stopShipmentIds();
        if (order.size() != manifest.size() || !new HashSet<>(order).equals(new HashSet<>(manifest))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The stops are the load's shipments, each once",
                    List.of(FieldError.of("stopShipmentIds", "exactly the shipments on the manifest")));
        }
        Map<UUID, ShipmentJpaEntity> rows = shipments.findByIdIn(new HashSet<>(order)).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        List<CarrierBookingGateway.Stop> route = new ArrayList<>();
        route.add(new CarrierBookingGateway.Stop(pickup.getAddress(), pickup.getContactName(),
                pickup.getContactPhone(), null));
        List<Map<String, Object>> stops = new ArrayList<>();
        int seq = 1;
        for (UUID shipmentId : order) {
            ShipmentJpaEntity shipment = rows.get(shipmentId);
            OrderSummary sold = orders.findById(shipment.getOrderId()).orElseThrow(() -> new BusinessException(
                    ErrorCode.NOT_FOUND, "No order for shipment " + shipment.getCode()));
            OrderSummary.AddressSummary address = sold.shippingAddress();
            if (address == null || !pickup.getProvinceCode().equals(address.provinceCode())) {
                throw new BusinessException(ErrorCode.CARRIER_STOP_OUT_OF_AREA, ("Shipment %s goes outside province "
                        + "%s, where carrier %s picks up: choose another carrier for this vehicle (E24)")
                        .formatted(shipment.getCode(), pickup.getProvinceCode(), carrier.getCode()),
                        List.of(FieldError.of("shipmentCode", shipment.getCode())));
            }
            String line = Stream.of(address.line1(), address.line2(), address.wardName(), address.provinceName())
                    .filter(part -> part != null && !part.isBlank()).collect(Collectors.joining(", "));
            String recipient = address.recipientName() != null ? address.recipientName() : sold.contactName();
            String phone = address.phone() != null ? address.phone() : sold.contactPhone();
            route.add(new CarrierBookingGateway.Stop(line, recipient, phone, shipment.getCode()));
            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("seq", seq++);
            stop.put("shipmentId", shipment.getId().toString());
            stop.put("shipmentCode", shipment.getCode());
            stop.put("address", line);
            stops.add(stop);
        }

        Instant now = clock.instant();
        // A claim whose call never came back holds the load for ABANDONED_AFTER only.
        bookings.findLive(loadId).filter(b -> CarrierBookingJpaEntity.REQUESTED.equals(b.getStatus())
                        && b.getRequestedAt().isBefore(now.minus(ABANDONED_AFTER)))
                .ifPresent(b -> b.failed("The call to the carrier never came back; check the carrier's portal"));
        bookings.flush();
        UUID bookingId = Identifiers.newId();
        try {
            bookings.saveAndFlush(new CarrierBookingJpaEntity(bookingId, loadId, carrier.getId(),
                    service.getServiceCode(), stops, request.pickupAt(), actor.userId(), now));
        } catch (DataIntegrityViolationException taken) {
            throw new BusinessException(ErrorCode.CARRIER_BOOKING_IN_PROGRESS,
                    "Load %s is being booked or is booked with a carrier already".formatted(header.getCode()));
        }
        return new Prepared(bookingId, secrets.caller(carrier, bookingId), new CarrierBookingGateway.Order(
                service.getServiceCode(), request.pickupAt(), route, header.getCode()));
    }

    // ------------------------------------------------------------------ internals

    /** Cancels at the carrier an order the load no longer takes; a refusal is left for the sweeper to retry. */
    private void compensate(Prepared prepared, CarrierBookingGateway.Placed placed, String why) {
        Instant now = clock.instant();
        String outcome;
        try {
            gateway.cancel(prepared.caller(), placed.orderId());
            outcome = null;
        } catch (CarrierBookingGateway.CarrierCallFailed failed) {
            outcome = failed.getMessage();
        }
        String refused = outcome;
        tx.executeWithoutResult(s -> bookings.lockById(prepared.bookingId()).ifPresent(b -> {
            b.booked(placed.orderId(), placed.trackingLink(), now);
            if (refused == null) {
                b.cancelled("The load changed while the carrier booked it: " + why, now);
            } else {
                b.cancelRefused(refused);
            }
        }));
        log.warn("Order {} created at the carrier for a load that moved meanwhile ({}); {}", placed.orderId(), why,
                refused == null ? "cancelled there again" : "the carrier refused to cancel it: " + refused);
    }

    private static BusinessException refusal(CarrierJpaEntity carrier,
                                             CarrierBookingGateway.CarrierCallFailed failed, String what) {
        if (!failed.answered()) {
            return new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Carrier %s did not answer; try again".formatted(carrier.getCode()));
        }
        return new BusinessException(ErrorCode.CARRIER_BOOKING_FAILED, "Carrier %s refused to %s it: %s"
                .formatted(carrier.getCode(), what, failed.getMessage()),
                List.of(FieldError.of("carrier", Objects.requireNonNullElse(failed.getMessage(), "refused"))));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
