package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.CarrierBookingCancelled;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierEventResult;
import com.stockflow.fulfillment.internal.domain.CarrierReport;
import com.stockflow.fulfillment.internal.domain.DeliveryFailureReason;
import com.stockflow.fulfillment.internal.domain.FailureReasonMap;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.CarrierBookingJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierEventJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierBookingJpaRepository;
import com.stockflow.fulfillment.internal.repository.CarrierEventJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.TrackingMilestoneJpaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns a stored webhook call into what it means for a load (kltn-docs 09 §4.5 steps 4-7), in two transactions the
 * caller opens:
 * <ol>
 *   <li>{@link #match}: read the body ({@link AhamoveWebhookReader} for an API_BOOKING carrier,
 *       {@link LoadWebhookReader} for a WEBHOOK_LOAD one), match the load - by the carrier's order id held by a booking
 *       (BR-22), or by the load code of that carrier - and the shipment (the drop-off's, or the one named), and keep
 *       what was matched. Nothing is locked: loads and bookings are read as projections, so the locks taken by the
 *       change that follows read their rows fresh.</li>
 *   <li>{@link #apply}: under the load's carrier-event lock, decide a repeat (BR-27: DUPLICATE) and an outdated report
 *       (older than the last applied to the same shipment, or to the load: STALE), then apply it through the load or the
 *       shipment, recorded by nobody at the carrier's time.</li>
 * </ol>
 * A change the load's or shipment's state refuses throws; the caller then decides the call NEEDS_REVIEW in a third
 * transaction ({@link #failed}), keeping what was matched. A call on the error list is never applied again.
 */
@Component
class CarrierEventApplier {

    /** What was matched, carried from the first transaction to the second. */
    record Matched(CarrierReport report, UUID loadId, String loadCode, UUID warehouseId, UUID bookingId,
                   String carrierOrderId, UUID shipmentId, String dedupeKey) { }

    private static final String E21_NOTE = "Returned by the carrier with no failed delivery reported first";

    private final CarrierEventJpaRepository events;
    private final DispatchLoadJpaRepository loads;
    private final CarrierBookingJpaRepository bookings;
    private final ShipmentJpaRepository shipments;
    private final TrackingMilestoneJpaRepository milestones;
    private final Dispatching dispatching;
    private final ShipmentService shipmentOutcomes;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    CarrierEventApplier(CarrierEventJpaRepository events, DispatchLoadJpaRepository loads,
                        CarrierBookingJpaRepository bookings, ShipmentJpaRepository shipments,
                        TrackingMilestoneJpaRepository milestones, Dispatching dispatching,
                        ShipmentService shipmentOutcomes, ApplicationEventPublisher publisher, ObjectMapper json,
                        Clock clock) {
        this.events = events;
        this.loads = loads;
        this.bookings = bookings;
        this.shipments = shipments;
        this.milestones = milestones;
        this.dispatching = dispatching;
        this.shipmentOutcomes = shipmentOutcomes;
        this.publisher = publisher;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 1. read and match

    /** @return what was matched, or null when the call is decided already (unreadable, unmatched, about a dead order) */
    Matched match(UUID eventId, CarrierJpaEntity carrier, byte[] body) {
        CarrierEventJpaEntity event = events.findById(eventId).orElseThrow();
        boolean api = CarrierConnectionType.API_BOOKING.name().equals(carrier.getConnectionType());
        CarrierReport report;
        try {
            JsonNode tree = json.readTree(body);
            report = api ? AhamoveWebhookReader.read(tree) : LoadWebhookReader.read(tree);
        } catch (IOException notJson) {
            return decided(event, CarrierEventResult.NEEDS_REVIEW, "The body is not JSON");
        } catch (CarrierWebhookUnreadable unreadable) {
            return decided(event, CarrierEventResult.NEEDS_REVIEW, unreadable.getMessage());
        }
        event.read(report.kind(), report.stop() == null ? report.reference() : report.reference() + "-" + report.stop(),
                report.reportedAt());

        DispatchLoadJpaRepository.LoadFacts load;
        UUID bookingId = null;
        String orderId = null;
        if (api) {
            CarrierBookingJpaRepository.BookingKey booking = bookings.findKeyByOrder(carrier.getId(), report.reference())
                    .orElse(null);
            if (booking == null) {
                return decided(event, CarrierEventResult.UNMATCHED, "No load holds order %s of carrier %s"
                        .formatted(report.reference(), carrier.getCode()));
            }
            load = loads.findFactsById(booking.getLoadId()).orElseThrow();
            switch (booking.getStatus()) {
                case CarrierBookingJpaEntity.BOOKED -> {
                    bookingId = booking.getId();
                    orderId = report.reference();
                }
                case CarrierBookingJpaEntity.CANCEL_FAILED -> {
                    event.matched(load.getWarehouseId(), load.getId(), null, null);
                    return decided(event, CarrierEventResult.UNMATCHED, ("Load %s no longer needs order %s, which the "
                            + "carrier refused to cancel: settle it with the carrier").formatted(load.getCode(),
                            report.reference()));
                }
                default -> {
                    event.matched(load.getWarehouseId(), load.getId(), null, null);
                    return decided(event, CarrierEventResult.STALE, "Order %s is no longer load %s's (its booking is %s)"
                            .formatted(report.reference(), load.getCode(), booking.getStatus()));
                }
            }
        } else {
            load = loads.findFactsByCode(report.reference()).orElse(null);
            if (load == null) {
                return decided(event, CarrierEventResult.UNMATCHED, "No load " + report.reference());
            }
            if (!carrier.getId().equals(load.getCarrierId())) {
                // Not this carrier's load: matched to nothing, so the other warehouse's load is not shown with it.
                return decided(event, CarrierEventResult.UNMATCHED, "Load %s is not booked with carrier %s"
                        .formatted(report.reference(), carrier.getCode()));
            }
        }
        if (load.getStatus() == LoadStatus.CANCELLED) {
            event.matched(load.getWarehouseId(), load.getId(), null, null);
            return decided(event, CarrierEventResult.UNMATCHED, "Load %s is cancelled (BR-22)".formatted(load.getCode()));
        }

        UUID shipmentId = null;
        if (report.kind().level() == CarrierEventKind.Level.SHIPMENT && report.namesShipment()) {
            if (report.stop() != null) {
                shipmentId = bookingId == null ? null : bookings.shipmentAtStop(bookingId, report.stop()).orElse(null);
                if (shipmentId == null) {
                    event.matched(load.getWarehouseId(), load.getId(), null, null);
                    return decided(event, CarrierEventResult.NEEDS_REVIEW, "Order %s has no drop-off %d"
                            .formatted(report.reference(), report.stop()));
                }
                String code = shipments.codeOf(shipmentId).orElse(null);
                if (report.shipmentCode() != null && !report.shipmentCode().equals(code)) {
                    event.matched(load.getWarehouseId(), load.getId(), null, null);
                    return decided(event, CarrierEventResult.NEEDS_REVIEW, ("Drop-off %d of order %s was made for "
                            + "shipment %s, but the carrier reports %s").formatted(report.stop(), report.reference(),
                            code, report.shipmentCode()));
                }
            } else {
                shipmentId = shipments.statesOnLoad(load.getId()).stream()
                        .filter(s -> s.getCode().equals(report.shipmentCode())).map(ShipmentJpaRepository.ShipmentState::getId)
                        .findFirst().orElse(null);
                if (shipmentId == null) {
                    event.matched(load.getWarehouseId(), load.getId(), null, null);
                    return decided(event, CarrierEventResult.NEEDS_REVIEW, "Shipment %s did not leave on load %s"
                            .formatted(report.shipmentCode(), load.getCode()));
                }
            }
        }
        String key = dedupeKey(carrier.getId(), report, body);
        event.matched(load.getWarehouseId(), load.getId(), shipmentId, key);
        return new Matched(report, load.getId(), load.getCode(), load.getWarehouseId(), bookingId, orderId, shipmentId,
                key);
    }

    // ------------------------------------------------------------------ 2. apply

    /** @return the result decided */
    CarrierEventResult apply(UUID eventId, CarrierJpaEntity carrier, Matched m) {
        events.lockReportsOf("carrier-event:" + m.loadId());
        CarrierEventJpaEntity event = events.findById(eventId).orElseThrow();
        if (event.getResult() != CarrierEventResult.RECEIVED) {
            return event.getResult();
        }
        if (events.handledBefore(m.dedupeKey(), eventId)) {
            return decide(event, CarrierEventResult.DUPLICATE, "The same report was handled already");
        }
        CarrierReport report = m.report();
        if (report.reportedAt() != null) {
            Instant last = latest(events.lastAppliedToLoad(m.loadId()),
                    m.shipmentId() == null ? null : events.lastAppliedToShipment(m.shipmentId()));
            if (last != null && report.reportedAt().isBefore(last)) {
                return decide(event, CarrierEventResult.STALE, "Older than the last report applied (" + last + ")");
            }
        }
        Instant at = report.reportedAt() != null ? report.reportedAt() : event.getReceivedAt();
        Outcome outcome = switch (report.kind()) {
            case WAYBILL -> changed(dispatching.carrierWaybill(m.loadId(), report.waybillNo(), report.vehiclePlate(),
                    report.driverName(), report.driverPhone()), "Waybill " + report.waybillNo() + " on the load");
            case DRIVER_ASSIGNED -> changed(dispatching.carrierDriverAssigned(m.loadId(), report.driverName(),
                    report.driverPhone()), "The driver on the load");
            case DRIVER_RELEASED -> changed(dispatching.carrierDriverReleased(m.loadId()), "The driver off the load");
            case PICKED_UP, IN_TRANSIT, RETURNING -> milestone(m, report.kind().name(), at, eventId);
            case DELIVERED, DELIVERY_FAILED -> outcome(carrier, m, at, eventId);
            case RETURNED -> returned(carrier, m, at, eventId);
            case ORDER_CANCELLED -> cancelled(carrier, m, at);
            case ORDER_COMPLETED -> new Outcome(CarrierEventResult.IGNORED,
                    "The order is completed; each drop-off reports its own outcome");
            case OTHER -> new Outcome(CarrierEventResult.IGNORED, "A status the system has no use for");
        };
        return decide(event, outcome.result(), outcome.note());
    }

    private record Outcome(CarrierEventResult result, String note) { }

    private static Outcome changed(boolean changed, String note) {
        return changed ? new Outcome(CarrierEventResult.APPLIED, note)
                : new Outcome(CarrierEventResult.IGNORED, "The load had it already");
    }

    /** Picked up, on its way, returning: a milestone of a vehicle that left (09 §4.5 step 7). */
    private Outcome milestone(Matched m, String milestone, Instant at, UUID eventId) {
        requireLeft(m);
        int recorded = milestones.recordFromCarrier(Identifiers.newId(), m.loadId(), milestone, at, eventId,
                clock.instant());
        return recorded == 1 ? new Outcome(CarrierEventResult.APPLIED, "Milestone " + milestone)
                : new Outcome(CarrierEventResult.IGNORED, "The load has milestone " + milestone + " already");
    }

    /**
     * Delivered or not, for the shipment named - or, a WEBHOOK_LOAD carrier naming none, every shipment that left on
     * the load (BR-26); an API carrier's outcome is never the load's.
     */
    private Outcome outcome(CarrierJpaEntity carrier, Matched m, Instant at, UUID eventId) {
        requireLeft(m);
        CarrierReport report = m.report();
        ShipmentStatus target = report.kind() == CarrierEventKind.DELIVERED ? ShipmentStatus.DELIVERED
                : ShipmentStatus.DELIVERY_FAILED;
        FailureReasonMap.Resolution failure = target == ShipmentStatus.DELIVERY_FAILED
                ? FailureReasonMap.of(reasons(carrier)).resolve(report.failureWording()) : null;
        DeliveryFailureReason reason = failure == null ? null : failure.reason();
        String note = failure == null ? null : failure.carrierWording() != null ? failure.carrierWording()
                : reason == DeliveryFailureReason.OTHER ? "The carrier gave no reason" : null;
        List<UUID> targets = m.shipmentId() != null ? List.of(m.shipmentId()) : leftOn(m);
        if (targets.isEmpty()) {
            return new Outcome(CarrierEventResult.NEEDS_REVIEW, "No shipment left on load " + m.loadCode());
        }
        int changed = 0;
        for (UUID shipment : targets) {
            changed += shipmentOutcomes.carrierOutcome(shipment, target, reason, note, at, eventId) ? 1 : 0;
        }
        return changed > 0 ? new Outcome(CarrierEventResult.APPLIED, "%s: %d shipment(s)%s".formatted(target, changed,
                        reason == null ? "" : " (" + reason + ")"))
                : new Outcome(CarrierEventResult.IGNORED, "The shipments were " + target + " already");
    }

    /**
     * The goods are back. A WEBHOOK_LOAD carrier: the shipment named, or every shipment of the load not delivered; one
     * still handed over is recorded failed (OTHER) first, in the same step (E21). An API carrier reports it for the
     * order: its failed drop-offs are returned; a drop-off it never reported an outcome for goes to the error list
     * rather than being guessed.
     */
    private Outcome returned(CarrierJpaEntity carrier, Matched m, Instant at, UUID eventId) {
        requireLeft(m);
        boolean api = CarrierConnectionType.API_BOOKING.name().equals(carrier.getConnectionType());
        List<ShipmentJpaRepository.ShipmentState> onLoad = shipments.statesOnLoad(m.loadId());
        List<ShipmentJpaRepository.ShipmentState> targets = m.shipmentId() != null
                ? onLoad.stream().filter(s -> s.getId().equals(m.shipmentId())).toList()
                : onLoad.stream().filter(s -> s.getStatus() != ShipmentStatus.DELIVERED).toList();
        if (api) {
            List<String> silent = targets.stream().filter(s -> s.getStatus() == ShipmentStatus.HANDED_OVER)
                    .map(ShipmentJpaRepository.ShipmentState::getCode).toList();
            if (!silent.isEmpty()) {
                return new Outcome(CarrierEventResult.NEEDS_REVIEW, ("The carrier reports the order returned, but "
                        + "reported no outcome for %s: record those by hand").formatted(String.join(", ", silent)));
            }
        }
        int changed = 0;
        for (ShipmentJpaRepository.ShipmentState shipment : targets) {
            if (shipment.getStatus() == ShipmentStatus.HANDED_OVER) {
                shipmentOutcomes.carrierOutcome(shipment.getId(), ShipmentStatus.DELIVERY_FAILED,
                        DeliveryFailureReason.OTHER, E21_NOTE, at, eventId);
            }
            changed += shipmentOutcomes.carrierOutcome(shipment.getId(), ShipmentStatus.RETURNED, null, null, at,
                    eventId) ? 1 : 0;
        }
        int recorded = milestones.recordFromCarrier(Identifiers.newId(), m.loadId(), "RETURNED", at, eventId,
                clock.instant());
        if (changed == 0 && recorded == 0) {
            return new Outcome(CarrierEventResult.IGNORED, "The goods were recorded back already");
        }
        return new Outcome(CarrierEventResult.APPLIED, "RETURNED: %d shipment(s)".formatted(changed));
    }

    /** The carrier cancelled the order before the pickup: the load is PLANNED again and its dispatchers told. */
    private Outcome cancelled(CarrierJpaEntity carrier, Matched m, Instant at) {
        if (!dispatching.carrierOrderCancelled(m.loadId(), m.bookingId(), at)) {
            return new Outcome(CarrierEventResult.STALE, "The booking of order " + m.carrierOrderId()
                    + " was no longer held");
        }
        publisher.publishEvent(new CarrierBookingCancelled(Identifiers.newId(), m.loadId(), m.loadCode(),
                m.warehouseId(), carrier.getCode(), m.carrierOrderId(), at));
        String wording = m.report().failureWording();
        return new Outcome(CarrierEventResult.APPLIED, "The carrier cancelled the order"
                + (wording == null ? "" : ": " + wording) + "; the load is PLANNED again");
    }

    /** Milestones and outcomes are a vehicle that left's (09 §4.5 step 7); before that, the error list's. */
    private void requireLeft(Matched m) {
        LoadStatus status = loads.findFactsById(m.loadId()).map(DispatchLoadJpaRepository.LoadFacts::getStatus)
                .orElseThrow();
        if (status != LoadStatus.DISPATCHED) {
            throw new BusinessException(com.stockflow.common.error.ErrorCode.INVALID_LOAD_TRANSITION,
                    "Load %s is %s: the carrier reports %s before the vehicle left".formatted(m.loadCode(), status,
                            m.report().kind()));
        }
    }

    private List<UUID> leftOn(Matched m) {
        return shipments.statesOnLoad(m.loadId()).stream().map(ShipmentJpaRepository.ShipmentState::getId).toList();
    }

    private static Map<String, DeliveryFailureReason> reasons(CarrierJpaEntity carrier) {
        return carrier.getFailureReasonMap().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> DeliveryFailureReason.valueOf(e.getValue())));
    }

    // ------------------------------------------------------------------ 3. a change that failed

    /**
     * The second transaction threw: a repeat that raced its twin to {@code uk_carrier_event_applied} is a DUPLICATE;
     * anything else is NEEDS_REVIEW with its message (a business refusal names the load's or shipment's state; anything
     * unexpected only its kind, the stack trace going to the log).
     */
    CarrierEventResult failed(UUID eventId, RuntimeException failure) {
        CarrierEventJpaEntity event = events.findById(eventId).orElseThrow();
        if (event.getResult() != CarrierEventResult.RECEIVED) {
            return event.getResult();
        }
        if (failure instanceof DataIntegrityViolationException taken
                && String.valueOf(taken.getMostSpecificCause().getMessage()).contains("uk_carrier_event_applied")) {
            return decide(event, CarrierEventResult.DUPLICATE, "The same report was applied at the same moment");
        }
        String why = failure instanceof BusinessException refused ? refused.getMessage()
                : "Could not be applied (" + failure.getClass().getSimpleName() + ")";
        return decide(event, CarrierEventResult.NEEDS_REVIEW, why);
    }

    // ------------------------------------------------------------------ helpers

    private Matched decided(CarrierEventJpaEntity event, CarrierEventResult result, String note) {
        decide(event, result, note);
        return null;
    }

    private CarrierEventResult decide(CarrierEventJpaEntity event, CarrierEventResult result, String note) {
        event.decide(result, note, clock.instant());
        events.saveAndFlush(event);
        return result;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    /**
     * What tells a repeat (BR-27: "cùng chuyến, shipment, loại sự kiện, thời điểm hãng báo"): the carrier, what was
     * reported, the order or load and the drop-off or shipment, and the carrier's time - or, for a report the carrier
     * gives no time for, the body itself, so that a later report of the same kind (a second driver giving the order
     * back) is not taken for a repeat.
     */
    static String dedupeKey(UUID carrierId, CarrierReport report, byte[] body) {
        List<String> parts = new ArrayList<>(List.of(String.valueOf(carrierId), report.kind().name(),
                report.reference(), String.valueOf(report.stop()), String.valueOf(report.shipmentCode())));
        parts.add(report.reportedAt() != null ? report.reportedAt().toString()
                : "body:" + HexFormat.of().formatHex(sha256(body)));
        return HexFormat.of().formatHex(sha256(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNullElse(bytes, new byte[0]));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", unavailable);
        }
    }
}
