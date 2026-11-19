package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.CarrierEventResult;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * See {@link FakeAhamove}. A step changes the fake order, then the order is reported to
 * {@link FakeAhamoveCallbacks#receiveFromFake} as Ahamove's callback would be - the whole order each time, as Ahamove
 * sends it: the plain order id for a change of the order, {@code <order id>-<k>} with the order IN PROCESS for
 * drop-off k; {@code supplier_name} and {@code supplier_id} (the driver's phone) once a driver accepted; epoch-second times
 * strictly increasing; {@code cancel_by_user} on a cancel. No call leaves the machine.
 *
 * <p>When the system's pipeline does not take a step - the load has not left the warehouse (NEEDS_REVIEW), the report
 * could not be verified, a copy of it was already seen - the step is undone on the fake order, so that it can be sent
 * again once the system is ready (the handover signed, say). The event stays on the error list, as a real one would.</p>
 */
@Service
@FakeAhamoveOnly
class FakeAhamoveDriver implements FakeAhamove {

    static final String DEFAULT_DRIVER = "Nguyen Van Tai (fake driver)";
    static final String DEFAULT_PHONE = "0912345678";
    static final String DEFAULT_PLATE = "29C-123.45";
    static final String DEFAULT_FAILURE = "CUSTOMER_NOT_AVAILABLE";
    private static final String IN_PROCESS = "IN PROCESS";
    /** Results that say the system took the report (an ASSIGNING with no driver to release is IGNORED, rightly). */
    private static final Set<CarrierEventResult> TAKEN = EnumSet.of(CarrierEventResult.APPLIED,
            CarrierEventResult.IGNORED);

    private final FakeAhamoveOrders orders;
    private final FakeAhamoveCallbacks callbacks;
    private final CarrierEvents events;
    private final CarrierJpaRepository carriers;
    private final ObjectMapper json;

    FakeAhamoveDriver(FakeAhamoveOrders orders, FakeAhamoveCallbacks callbacks, CarrierEvents events,
                      CarrierJpaRepository carriers, ObjectMapper json) {
        this.orders = orders;
        this.callbacks = callbacks;
        this.events = events;
        this.carriers = carriers;
        this.json = json;
    }

    @Override
    public List<OrderView> orders() {
        return orders.all().stream().map(this::view).toList();
    }

    @Override
    public OrderView order(String orderId) {
        return view(require(orderId));
    }

    @Override
    public Played send(String orderId, Step step, Details details) {
        FakeAhamoveOrders.Order order = require(orderId);
        Callback callback = apply(order, step, details == null ? Details.none() : details);
        return new Played(view(order), List.of(callback));
    }

    @Override
    public Played play(String orderId, boolean deliver, Details details) {
        FakeAhamoveOrders.Order order = require(orderId);
        Details given = details == null ? Details.none() : details;
        List<Callback> sent = new ArrayList<>();
        for (Step step : remaining(order, deliver)) {
            Callback callback = apply(order, step, step == Step.FAIL && given.reason() == null
                    ? new Details(null, DEFAULT_FAILURE, given.driverName(), given.driverPhone(),
                    given.vehiclePlate()) : given);
            sent.add(callback);
            if (!TAKEN.contains(CarrierEventResult.valueOf(callback.result()))) {
                break;
            }
        }
        return new Played(view(order), sent);
    }

    /** The steps an order still has to take for the outcome asked for. */
    private static List<Step> remaining(FakeAhamoveOrders.Order order, boolean deliver) {
        List<Step> steps = new ArrayList<>();
        synchronized (order) {
            switch (order.status) {
                case "ASSIGNING" -> {
                    steps.add(Step.ACCEPT);
                    steps.add(Step.PICKUP);
                }
                case "ACCEPTED" -> steps.add(Step.PICKUP);
                case IN_PROCESS -> { }
                case "COMPLETED" -> {
                    if (!"IN_RETURN".equals(order.subStatus)) {
                        throw refused("Order %s is completed: nothing more happens to it".formatted(order.id));
                    }
                    return List.of(Step.RETURNED);
                }
                default -> throw refused("Order %s is %s: nothing more happens to it".formatted(order.id,
                        order.status));
            }
            for (int k = 1; k < order.route.size(); k++) {
                if (order.route.get(k).status.isEmpty()) {
                    steps.add(deliver ? Step.DELIVER : Step.FAIL);
                }
            }
            if (deliver) {
                steps.add(Step.COMPLETE);
            } else {
                steps.add(Step.RETURNING);
                steps.add(Step.RETURNED);
            }
        }
        return steps;
    }

    // ------------------------------------------------------------------ one step

    private Callback apply(FakeAhamoveOrders.Order order, Step step, Details details) {
        String carrierCode = carriers.findById(order.carrierId).orElseThrow().getCode();
        byte[] body;
        String callbackId;
        Memento before;
        synchronized (order) {
            before = Memento.of(order);
            Integer stop = mutate(order, step, details);
            callbackId = stop == null ? order.id : order.id + "-" + stop;
            body = body(order, callbackId).getBytes(StandardCharsets.UTF_8);
        }
        CarrierEvents.Receipt receipt = callbacks.receiveFromFake(carrierCode, body);
        String result = receipt.result();
        String note = null;
        if (receipt.eventId() != null) {
            try {
                note = events.get(receipt.eventId()).event().resultNote();
            } catch (BusinessException outOfScope) {
                // The note is a courtesy: a caller outside the event's warehouse still gets the result.
                note = null;
            }
        }
        if (!TAKEN.contains(CarrierEventResult.valueOf(result))) {
            synchronized (order) {
                before.restore(order);
            }
        }
        return new Callback(step.name(), callbackId, receipt.eventId() == null ? null : receipt.eventId().toString(),
                result, note);
    }

    /** Changes the order as {@code step} does, or refuses; @return the drop-off the callback is about, or null. */
    private Integer mutate(FakeAhamoveOrders.Order order, Step step, Details details) {
        Instant at;
        switch (step) {
            case ASSIGNING -> {
                requireStatus(order, step, "ASSIGNING", "ACCEPTED");
                at = orders.next(order);
                if ("ACCEPTED".equals(order.status)) {
                    // The driver gives it back: the acceptance it undoes stays in the callback (accept_time).
                    order.status = "ASSIGNING";
                    order.driverName = null;
                    order.driverPhone = null;
                    order.vehiclePlate = null;
                }
            }
            case ACCEPT -> {
                requireStatus(order, step, "ASSIGNING");
                at = orders.next(order);
                order.status = "ACCEPTED";
                order.acceptTime = at;
                order.driverName = text(details.driverName(), DEFAULT_DRIVER);
                order.driverPhone = text(details.driverPhone(), DEFAULT_PHONE);
                order.vehiclePlate = text(details.vehiclePlate(), DEFAULT_PLATE);
            }
            case PICKUP -> {
                requireStatus(order, step, "ACCEPTED");
                at = orders.next(order);
                order.status = IN_PROCESS;
                order.pickupTime = at;
                order.route.get(0).status = "COMPLETED";
                order.route.get(0).completeTime = at;
            }
            case EN_ROUTE -> {
                requireStatus(order, step, IN_PROCESS);
                orders.next(order);
                order.subStatus = "COMPLETING";
            }
            case DELIVER, FAIL -> {
                requireStatus(order, step, IN_PROCESS);
                int k = dropOff(order, details.stop());
                at = orders.next(order);
                FakeAhamoveOrders.Point point = order.route.get(k);
                if (step == Step.DELIVER) {
                    point.status = "COMPLETED";
                    point.completeTime = at;
                } else {
                    point.status = "FAILED";
                    point.failTime = at;
                    point.failComment = text(details.reason(), DEFAULT_FAILURE);
                }
                return k;
            }
            case COMPLETE -> {
                requireStatus(order, step, IN_PROCESS);
                requireSettled(order, step);
                at = orders.next(order);
                order.status = "COMPLETED";
                order.subStatus = null;
                order.completeTime = at;
            }
            case RETURNING -> {
                requireStatus(order, step, IN_PROCESS);
                requireSettled(order, step);
                if (order.route.stream().skip(1).noneMatch(p -> "FAILED".equals(p.status))) {
                    throw refused("Nothing is on its way back: no drop-off of order %s failed".formatted(order.id));
                }
                orders.next(order);
                order.status = "COMPLETED";
                order.subStatus = "IN_RETURN";
            }
            case RETURNED -> {
                requireStatus(order, step, "COMPLETED");
                if (!"IN_RETURN".equals(order.subStatus)) {
                    throw refused("Order %s is not on its way back: send RETURNING first".formatted(order.id));
                }
                at = orders.next(order);
                order.subStatus = "RETURNED";
                order.completeTime = at;
                order.route.stream().skip(1).filter(p -> "FAILED".equals(p.status))
                        .forEach(p -> p.returnTime = at);
            }
            case CARRIER_CANCEL -> {
                requireStatus(order, step, "ASSIGNING", "ACCEPTED");
                at = orders.next(order);
                order.status = "CANCELLED";
                order.subStatus = null;
                order.cancelByUser = false;
                order.cancelComment = text(details.reason(), "No driver available (fake Ahamove)");
                order.cancelTime = at;
            }
            default -> throw new IllegalStateException(step.name());
        }
        return null;
    }

    private static int dropOff(FakeAhamoveOrders.Order order, Integer asked) {
        if (asked == null) {
            for (int k = 1; k < order.route.size(); k++) {
                if (order.route.get(k).status.isEmpty()) {
                    return k;
                }
            }
            throw refused("Every drop-off of order %s is settled already".formatted(order.id));
        }
        if (asked < 1 || asked >= order.route.size()) {
            throw refused("Order %s has drop-offs 1 to %d, not %d".formatted(order.id, order.route.size() - 1,
                    asked));
        }
        if (!order.route.get(asked).status.isEmpty()) {
            throw refused("Drop-off %d of order %s is %s already".formatted(asked, order.id,
                    order.route.get(asked).status));
        }
        return asked;
    }

    private static void requireSettled(FakeAhamoveOrders.Order order, Step step) {
        for (int k = 1; k < order.route.size(); k++) {
            if (order.route.get(k).status.isEmpty()) {
                throw refused("%s: drop-off %d of order %s is not settled yet (DELIVER or FAIL it)".formatted(step, k,
                        order.id));
            }
        }
    }

    private static void requireStatus(FakeAhamoveOrders.Order order, Step step, String... allowed) {
        for (String status : allowed) {
            if (status.equals(order.status)) {
                return;
            }
        }
        throw refused("%s: order %s is %s%s, which cannot take that step (needs %s)".formatted(step, order.id,
                order.status, order.subStatus == null ? "" : "/" + order.subStatus, String.join(" or ", allowed)));
    }

    private static BusinessException refused(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static String text(String given, String otherwise) {
        return given == null || given.isBlank() ? otherwise : given.strip();
    }

    // ------------------------------------------------------------------ the callback

    /** Ahamove's order callback: the whole order, its points under {@code path}. */
    private String body(FakeAhamoveOrders.Order o, String id) {
        ObjectNode body = json.createObjectNode();
        body.put("_id", id);
        body.put("status", o.status);
        if (o.subStatus != null) {
            body.put("sub_status", o.subStatus);
        }
        body.put("service_id", o.serviceCode);
        if (o.remarks != null) {
            body.put("remarks", o.remarks);
        }
        if (o.driverName != null) {
            body.put("supplier_name", o.driverName);
            body.put("supplier_id", o.driverPhone);
        }
        time(body, "accept_time", o.acceptTime);
        time(body, "pickup_time", o.pickupTime);
        time(body, "complete_time", o.completeTime);
        time(body, "cancel_time", o.cancelTime);
        if ("CANCELLED".equals(o.status)) {
            body.put("cancel_by_user", o.cancelByUser);
            body.put("cancel_comment", o.cancelComment);
        }
        ArrayNode path = body.putArray("path");
        for (FakeAhamoveOrders.Point p : o.route) {
            ObjectNode point = path.addObject();
            point.put("address", p.address);
            if (p.name != null) {
                point.put("name", p.name);
            }
            if (p.phone != null) {
                point.put("mobile", p.phone);
            }
            if (p.trackingNumber != null) {
                point.put("tracking_number", p.trackingNumber);
            }
            if (!p.status.isEmpty()) {
                point.put("status", p.status);
            }
            time(point, "complete_time", p.completeTime);
            time(point, "fail_time", p.failTime);
            time(point, "return_time", p.returnTime);
            if (p.failComment != null) {
                point.put("fail_comment", p.failComment);
            }
        }
        return body.toString();
    }

    /** Epoch seconds with a fraction, as Ahamove's callbacks carry them. */
    private static void time(ObjectNode node, String field, Instant at) {
        if (at != null) {
            node.put(field, BigDecimal.valueOf(at.getEpochSecond()).add(BigDecimal.valueOf(at.getNano(), 9)));
        }
    }

    // ------------------------------------------------------------------ views

    private FakeAhamoveOrders.Order require(String orderId) {
        FakeAhamoveOrders.Order order = orders.get(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "The fake Ahamove holds no order " + orderId
                    + " (it keeps orders in memory: a restart forgets them)");
        }
        return order;
    }

    private OrderView view(FakeAhamoveOrders.Order o) {
        String code = carriers.findById(o.carrierId).map(c -> c.getCode()).orElse(null);
        synchronized (o) {
            List<StopView> stops = new ArrayList<>();
            for (int k = 0; k < o.route.size(); k++) {
                FakeAhamoveOrders.Point p = o.route.get(k);
                stops.add(new StopView(k, p.trackingNumber, p.address, p.status.isEmpty() ? null : p.status,
                        p.failComment));
            }
            return new OrderView(o.id, code, o.status, o.subStatus, o.serviceCode, o.remarks,
                    "https://fake-ahamove.invalid/track/" + o.id, o.driverName, o.driverPhone, o.vehiclePlate,
                    o.createdAt, stops);
        }
    }

    /** What a step may have changed on an order, to put back when the system did not take the step. */
    private record Memento(String status, String subStatus, boolean cancelByUser, String cancelComment,
                           String driverName, String driverPhone, String vehiclePlate, Instant acceptTime,
                           Instant pickupTime, Instant completeTime, Instant cancelTime,
                           List<Object[]> points) {

        static Memento of(FakeAhamoveOrders.Order o) {
            List<Object[]> points = new ArrayList<>();
            for (FakeAhamoveOrders.Point p : o.route) {
                points.add(new Object[] {p.status, p.completeTime, p.failTime, p.failComment, p.returnTime});
            }
            return new Memento(o.status, o.subStatus, o.cancelByUser, o.cancelComment, o.driverName, o.driverPhone,
                    o.vehiclePlate, o.acceptTime, o.pickupTime, o.completeTime, o.cancelTime, points);
        }

        void restore(FakeAhamoveOrders.Order o) {
            o.status = status;
            o.subStatus = subStatus;
            o.cancelByUser = cancelByUser;
            o.cancelComment = cancelComment;
            o.driverName = driverName;
            o.driverPhone = driverPhone;
            o.vehiclePlate = vehiclePlate;
            o.acceptTime = acceptTime;
            o.pickupTime = pickupTime;
            o.completeTime = completeTime;
            o.cancelTime = cancelTime;
            // `last` is not put back: a report already sent has its time, and the next must be later than it.
            for (int k = 0; k < points.size(); k++) {
                FakeAhamoveOrders.Point p = o.route.get(k);
                Object[] saved = points.get(k);
                p.status = (String) saved[0];
                p.completeTime = (Instant) saved[1];
                p.failTime = (Instant) saved[2];
                p.failComment = (String) saved[3];
                p.returnTime = (Instant) saved[4];
            }
        }
    }
}
