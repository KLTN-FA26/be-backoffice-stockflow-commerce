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
 * If the load moved between the first and the third step (its plan reopened, someone else booked it, its shipments
 * changed), the order the carrier just created is cancelled there again, so no truck comes for a load that no longer
 * wants it.
 *
 * <p><b>A call that does not come back is not a refusal.</b> A timeout, a 5xx or a crash after the request left may
 * follow an order the carrier did create. Such a claim becomes {@code UNCONFIRMED} - at once, or after
 * {@link #ABANDONED_AFTER} for a claim whose process died - and keeps holding the load: a second order, a second truck,
 * is not made until a dispatcher has looked on the carrier's portal for an order bearing the load's code, cancelled it
 * there if it exists, and cancelled the booking here, which releases the claim.</p>
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
        // A claim whose process died mid-call is unconfirmed, like a call that got no answer: it keeps the load. Its
        // own transaction, so the refusal that follows does not roll it back.
        tx.executeWithoutResult(s -> bookings.findLive(loadId).filter(b ->
                CarrierBookingJpaEntity.REQUESTED.equals(b.getStatus())
                        && b.getRequestedAt().isBefore(clock.instant().minus(ABANDONED_AFTER)))
                .ifPresent(b -> b.unconfirmed(unconfirmed("the call never came back", "the load's code"))));
        Prepared prepared = tx.execute(s -> prepare(loadId, carrier, request, actor));
        CarrierBookingGateway.Placed placed;
        try {
            placed = gateway.place(prepared.caller(), prepared.order());
        } catch (CarrierBookingGateway.CarrierCallFailed failed) {
            if (failed.answered()) {
                settleClaim(prepared.bookingId(), b -> b.failed(failed.getMessage()));
                throw refusal(carrier, failed, "book");
            }
            settleClaim(prepared.bookingId(), b -> b.unconfirmed(unconfirmed(failed.getMessage(),
                    prepared.order().remarks())));
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, ("Carrier %s did not answer, and may have "
                    + "created the order: look on its portal for an order with remark %s, cancel it there if it "
                    + "exists, then cancel this booking to book again").formatted(carrier.getCode(),
                    prepared.order().remarks()));
        } catch (RuntimeException unknown) {
            settleClaim(prepared.bookingId(), b -> b.unconfirmed(unconfirmed(unknown.getClass().getSimpleName(),
                    prepared.order().remarks())));
            throw unknown;
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

    /**
     * A booked order is cancelled at the carrier first, then here under the load's locks
     * ({@link Dispatching#cancelCarrierBooking}). Anything else - no carrier order, a claim in flight or unconfirmed -
     * is {@link Dispatching#cancelBooking}'s, which decides under the same locks, so a booking that lands meanwhile is
     * refused rather than left held at the carrier.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Dispatching.LoadDetail cancelBooking(UUID loadId, String reason, CurrentUser actor) {
        CarrierBookingJpaEntity held = tx.execute(s -> {
            DispatchLoadJpaEntity header = loads.findById(loadId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_NOT_FOUND));
            WarehouseScope.requireWarehouse(header.getWarehouseId());
            return bookings.findLive(loadId).filter(b -> CarrierBookingJpaEntity.BOOKED.equals(b.getStatus()))
                    .orElse(null);
        });
        if (held == null) {
            return tx.execute(s -> dispatching.cancelBooking(loadId, reason, actor));
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Cancelling a booking says why");
        }
        CarrierJpaEntity carrier = tx.execute(s -> carriers.findById(held.getCarrierId()).orElseThrow());
        String note = cancelAtCarrier(carrier, held);
        Dispatching.LoadDetail after = tx.execute(s -> dispatching.cancelCarrierBooking(loadId, held.getId(),
                note == null ? reason.strip() : reason.strip() + " (" + note + ")", actor));
        if (!LoadStatus.PLANNED.name().equals(after.status())) {
            // Loading started while the carrier cancelled: the order is gone, the load cannot leave on it.
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION, ("Order %s was cancelled at carrier %s, but "
                    + "load %s started loading meanwhile: it cannot be handed over on that order - unload it with a "
                    + "controlled cancel").formatted(held.getCarrierOrderId(), carrier.getCode(), after.code()));
        }
        return after;
    }

    /**
     * Cancels the order at the carrier. A refusal is looked into: an order the carrier no longer has, or has cancelled
     * already (nobody took it, a cancel that timed out after it worked), is cancelled as far as this load is concerned.
     * A carrier whose API settings were removed since cannot be asked: the booking is released here and the dispatcher
     * settles the order with the carrier.
     *
     * @return a note for the booking's reason, or null
     * @throws BusinessException 409 {@code CARRIER_CANCEL_REFUSED} when the carrier keeps the order (the driver picked
     *         up); 502 when it did not answer
     */
    private String cancelAtCarrier(CarrierJpaEntity carrier, CarrierBookingJpaEntity held) {
        CarrierBookingGateway.Caller caller;
        try {
            caller = secrets.caller(carrier, held.getId());
        } catch (BusinessException noApi) {
            if (noApi.errorCode() != ErrorCode.CARRIER_NOT_API_BOOKING) {
                throw noApi;
            }
            log.warn("Load {}: carrier {} has no API settings any more; order {} released here, to be cancelled with "
                    + "the carrier directly", held.getLoadId(), carrier.getCode(), held.getCarrierOrderId());
            return "carrier no longer connected through its API: cancel order " + held.getCarrierOrderId()
                    + " with the carrier directly";
        }
        try {
            gateway.cancel(caller, held.getCarrierOrderId());
            return null;
        } catch (CarrierBookingGateway.CarrierCallFailed failed) {
            if (failed.answered() && goneAtCarrier(caller, held.getCarrierOrderId())) {
                return "already cancelled at the carrier";
            }
            throw new BusinessException(failed.answered() ? ErrorCode.CARRIER_CANCEL_REFUSED
                    : ErrorCode.EXTERNAL_SERVICE_ERROR, ("Carrier %s did not cancel order %s (%s): settle it with the "
                    + "carrier").formatted(carrier.getCode(), held.getCarrierOrderId(), failed.getMessage()),
                    List.of(FieldError.of("carrier", failed.getMessage())));
        }
    }

    /** Whether the carrier has no such order any more, or holds it cancelled. Unknown counts as no. */
    boolean goneAtCarrier(CarrierBookingGateway.Caller caller, String orderId) {
        try {
            return gateway.orderStatus(caller, orderId).map("CANCELLED"::equals).orElse(true);
        } catch (CarrierBookingGateway.CarrierCallFailed unknown) {
            return false;
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
        CarrierBookingJpaEntity live = bookings.findLive(loadId).orElse(null);
        if (live != null) {
            throw inProgress(header.getCode(), live);
        }
        UUID bookingId = Identifiers.newId();
        try {
            bookings.saveAndFlush(new CarrierBookingJpaEntity(bookingId, loadId, carrier.getId(),
                    service.getServiceCode(), stops, request.pickupAt(), actor.userId(), now));
        } catch (DataIntegrityViolationException taken) {
            if (!String.valueOf(taken.getMostSpecificCause().getMessage()).contains("uk_carrier_booking_live")) {
                throw taken;
            }
            throw new BusinessException(ErrorCode.CARRIER_BOOKING_IN_PROGRESS,
                    "Load %s is being booked or is booked with a carrier already".formatted(header.getCode()));
        }
        return new Prepared(bookingId, secrets.caller(carrier, bookingId), new CarrierBookingGateway.Order(
                service.getServiceCode(), request.pickupAt(), route, header.getCode()));
    }

    // ------------------------------------------------------------------ internals

    /**
     * Cancels at the carrier an order the load no longer takes. A refusal is CANCEL_FAILED, retried by the sweeper; no
     * answer leaves it BOOKED for a load that is not, which the sweeper cancels too.
     */
    private void compensate(Prepared prepared, CarrierBookingGateway.Placed placed, String why) {
        Instant now = clock.instant();
        String refused = null;
        boolean answered = true;
        try {
            gateway.cancel(prepared.caller(), placed.orderId());
        } catch (CarrierBookingGateway.CarrierCallFailed failed) {
            answered = failed.answered();
            refused = failed.answered() && goneAtCarrier(prepared.caller(), placed.orderId()) ? null
                    : failed.getMessage();
        }
        String outcome = refused;
        boolean heard = answered;
        settleClaim(prepared.bookingId(), b -> {
            b.booked(placed.orderId(), placed.trackingLink(), now);
            if (outcome == null) {
                b.cancelled("The load changed while the carrier booked it: " + why, now);
            } else if (heard) {
                b.cancelRefused(outcome);
            } else {
                b.cancelUnanswered(outcome);
            }
        });
        log.warn("Order {} created at the carrier for a load that moved meanwhile ({}); {}", placed.orderId(), why,
                refused == null ? "cancelled there again" : "the carrier refused to cancel it: " + refused);
    }

    /** Writes the outcome of a claim, under its lock, unless something settled it meanwhile. */
    private void settleClaim(UUID bookingId, java.util.function.Consumer<CarrierBookingJpaEntity> outcome) {
        tx.executeWithoutResult(s -> bookings.lockById(bookingId).filter(b ->
                CarrierBookingJpaEntity.REQUESTED.equals(b.getStatus())
                        || CarrierBookingJpaEntity.UNCONFIRMED.equals(b.getStatus())).ifPresent(outcome));
    }

    /** What an unconfirmed claim says on the load: why, and what to look for on the carrier's portal. */
    static String unconfirmed(String why, String loadCode) {
        return ("The carrier did not answer (%s) and may have created the order: look on its portal for an order with "
                + "remark %s, cancel it there if it exists, then cancel this booking to book again").formatted(why,
                loadCode);
    }

    static BusinessException inProgress(String loadCode, CarrierBookingJpaEntity live) {
        return new BusinessException(ErrorCode.CARRIER_BOOKING_IN_PROGRESS, switch (live.getStatus()) {
            case CarrierBookingJpaEntity.UNCONFIRMED -> ("Load %s has a booking the carrier never confirmed: check the "
                    + "carrier's portal for an order with remark %s, then cancel the booking").formatted(loadCode,
                    loadCode);
            // A PLANNED load still holding an order: a shipment was taken off, and the sweeper cancels it now.
            case CarrierBookingJpaEntity.BOOKED -> ("Load %s still holds an order at the carrier, being cancelled there "
                    + "now: book again in a minute").formatted(loadCode);
            default -> "Load %s is being booked with a carrier right now".formatted(loadCode);
        });
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
