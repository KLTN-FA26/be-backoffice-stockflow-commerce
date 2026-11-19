package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.common.id.Identifiers;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Ahamove without the network ({@code stockflow.fulfillment.ahamove.mode=fake}), behind the interface the booking calls
 * through: it keeps the orders it is given in memory and answers as the real API does where the system can tell -
 * an order id that is the load's waybill number, a tracking link, a refused cancel once the driver has picked up, the order as it is now for the check made before a final
 * outcome is applied, and a list of truck services.
 *
 * <p>It never calls out and never opens the carrier's sealed key ({@link CarrierSecrets}). What happens to an order
 * after it is placed - a driver, a pickup, a delivery - is driven by {@link FakeAhamoveDriver}, which reports it to the
 * system's own webhook pipeline. Every call is written to the carrier call log, as a real one is.</p>
 */
@Component
@FakeAhamoveOnly
class FakeAhamoveGateway implements CarrierBookingGateway {

    private static final String LINK = "https://fake-ahamove.invalid/track/";

    private final FakeAhamoveOrders orders;
    private final CarrierApiCalls calls;
    private final ObjectMapper json;
    private final Clock clock;

    FakeAhamoveGateway(FakeAhamoveOrders orders, CarrierApiCalls calls, ObjectMapper json, Clock clock) {
        this.orders = orders;
        this.calls = calls;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Placed place(Caller caller, Order order) {
        ObjectNode body = json.createObjectNode();
        body.put("order_time", order.pickupAt() == null ? 0 : order.pickupAt().getEpochSecond());
        ArrayNode path = body.putArray("path");
        for (Stop stop : order.route()) {
            ObjectNode point = path.addObject();
            point.put("address", stop.address());
            if (stop.trackingNumber() != null) {
                point.put("tracking_number", stop.trackingNumber());
            }
        }
        body.put("service_id", order.serviceCode());
        if (order.route().size() < 2) {
            throw refused(caller, "BOOK", body.toString(), 400, "INVALID_PATH",
                    "an order needs a pickup and at least one drop-off");
        }
        Instant now = clock.instant();
        // Letters and digits only, as Ahamove's ids are; the tail of a UUIDv7 is the random part.
        String random = Identifiers.newId().toString().replace("-", "");
        String id = "FK" + random.substring(random.length() - 12).toUpperCase(Locale.ROOT);
        FakeAhamoveOrders.Order created = new FakeAhamoveOrders.Order(id, caller.carrierId(), order.serviceCode(),
                order.remarks(), now, order.pickupAt());
        for (Stop stop : order.route()) {
            created.route.add(new FakeAhamoveOrders.Point(stop.address(), stop.name(), stop.phone(),
                    stop.trackingNumber()));
        }
        orders.put(created);
        ObjectNode answer = json.createObjectNode();
        answer.put("order_id", id);
        answer.put("shared_link", LINK + id);
        record(caller, "BOOK", 200, body.toString(), answer.toString(), null);
        return new Placed(id, LINK + id);
    }

    /**
     * An order can be cancelled until the driver has picked up; one under way, finished or cancelled already refuses. The
     * first half is Ahamove's documented flow (cancelling is described for ASSIGNING and ACCEPTED); what Ahamove does
     * to a cancel after pickup is not documented, so the refusal is the demo's rule - check it on staging.
     */
    @Override
    public void cancel(Caller caller, String orderId) {
        FakeAhamoveOrders.Order order = orders.get(orderId);
        String request = json.createObjectNode().put("order_id", String.valueOf(orderId)).toString();
        if (order == null) {
            throw refused(caller, "CANCEL", request, 404, "ORDER_NOT_FOUND", "no order " + orderId);
        }
        synchronized (order) {
            if (!("ASSIGNING".equals(order.status) || "ACCEPTED".equals(order.status) || "IDLE".equals(order.status))) {
                throw refused(caller, "CANCEL", request, 406, "INVALID_ORDER_STATUS",
                        "order %s is %s: it can be cancelled only before pickup".formatted(orderId, order.status));
            }
            Instant at = orders.next(order);
            order.status = "CANCELLED";
            order.subStatus = null;
            order.cancelByUser = true;
            order.cancelComment = "Cancelled by the partner";
            order.cancelTime = at;
        }
        record(caller, "CANCEL", 200, request, "{}", null);
    }

    @Override
    public Optional<OrderState> orderState(Caller caller, String orderId) {
        FakeAhamoveOrders.Order order = orders.get(orderId);
        String request = json.createObjectNode().put("order_id", String.valueOf(orderId)).toString();
        if (order == null) {
            record(caller, "STATUS", 404, request, null, "404 ORDER_NOT_FOUND");
            return Optional.empty();
        }
        OrderState state;
        synchronized (order) {
            List<String> stops = new ArrayList<>();
            for (int k = 1; k < order.route.size(); k++) {
                stops.add(order.route.get(k).status);
            }
            state = new OrderState(order.status, order.subStatus, stops);
        }
        record(caller, "STATUS", 200, request, json.createObjectNode().put("status", state.status()).toString(),
                null);
        return Optional.of(state);
    }

    @Override
    public List<ServiceOption> services(Caller caller, String cityId) {
        String city = cityId.toUpperCase(Locale.ROOT);
        List<ServiceOption> options = List.of(
                new ServiceOption(city + "-TRUCK-500", "Fake truck 500 kg (" + city + ")", city),
                new ServiceOption(city + "-TRUCK-1000", "Fake truck 1000 kg (" + city + ")", city),
                new ServiceOption(city + "-TRUCK-2000", "Fake truck 2000 kg (" + city + ")", city));
        record(caller, "SERVICES", 200, "city_id=" + city, null, null);
        return options;
    }

    private CarrierCallFailed refused(Caller caller, String operation, String request, int status, String code,
                                      String words) {
        String described = status + " " + code + ": " + words;
        record(caller, operation, status, request, null, described);
        return new CarrierCallFailed(true, code, described);
    }

    private void record(Caller caller, String operation, int status, String request, String response, String error) {
        calls.record(caller.carrierId(), caller.bookingId(), operation,
                status < 400 ? CarrierApiCalls.Outcome.OK : CarrierApiCalls.Outcome.REFUSED, status, request, response,
                error, 0);
    }
}
