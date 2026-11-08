package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.fulfillment.internal.domain.CarrierApiEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ahamove's partner API v3 (3-4; developers.ahamove.com): {@code POST /v3/accounts/token},
 * {@code GET /v3/services?city_id=&delivery_type=TRUCK}, {@code POST /v3/orders}, {@code DELETE /v3/orders/{id}}.
 *
 * <p>A token is asked for with the carrier's account (the registered phone number) and key, and kept in memory for that
 * account and key: on production it lasts a year, and asking for a new one invalidates the old (Ahamove's FAQ), so it is
 * asked for only when there is none or a call answers 401 - then once more, and the call retried once. The deployment
 * runs one instance; with several, each would invalidate the others' token and recover on the next 401.</p>
 *
 * <p>The order is placed as Ahamove expects: the pickup first and each drop-off after it in the dispatcher's order, our
 * shipment code as each drop-off's {@code tracking_number} (echoed in Ahamove's events, by which 3-5 finds the
 * shipment), the internal load code in {@code remarks} (09 §4.1 step 3), {@code payment_method BALANCE} - the business
 * pays, nothing is collected - and no special requests. Coordinates are left out: Ahamove places a point by its address
 * when it has none.</p>
 */
@Component
class AhamoveGateway implements CarrierBookingGateway {

    static final String SERVICE = "ahamove";
    private static final Logger log = LoggerFactory.getLogger(AhamoveGateway.class);

    private final RestClient staging;
    private final RestClient production;
    private final AhamoveProperties properties;
    private final CarrierApiCalls calls;
    private final ObjectMapper json;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    AhamoveGateway(RestClientFactory clients, AhamoveProperties properties, CarrierApiCalls calls, ObjectMapper json) {
        this.staging = clients.forService(SERVICE, properties.stagingUrl()).build();
        this.production = clients.forService(SERVICE, properties.productionUrl()).build();
        this.properties = properties;
        this.calls = calls;
        this.json = json;
    }

    @Override
    public Placed place(Caller caller, Order order) {
        ObjectNode body = json.createObjectNode();
        body.put("order_time", order.pickupAt() == null ? 0 : order.pickupAt().getEpochSecond());
        ArrayNode path = body.putArray("path");
        for (Stop stop : order.route()) {
            ObjectNode point = path.addObject();
            point.put("address", stop.address());
            putIfPresent(point, "name", stop.name());
            putIfPresent(point, "mobile", stop.phone());
            putIfPresent(point, "tracking_number", stop.trackingNumber());
        }
        body.put("service_id", order.serviceCode());
        body.putArray("requests");
        body.put("payment_method", "BALANCE");
        putIfPresent(body, "remarks", order.remarks());
        Answer answer = authorised(caller, "BOOK", HttpMethod.POST, "/v3/orders", body.toString());
        JsonNode placed = answer.json();
        String orderId = text(placed, "order_id");
        if (orderId == null && placed != null && placed.has("order")) {
            orderId = text(placed.get("order"), "_id");
        }
        if (orderId == null) {
            throw new CarrierCallFailed(true, null, "Ahamove answered without an order id");
        }
        return new Placed(orderId, text(placed, "shared_link"));
    }

    @Override
    public void cancel(Caller caller, String orderId) {
        ObjectNode body = json.createObjectNode();
        body.put("comment", properties.cancelComment());
        authorised(caller, "CANCEL", HttpMethod.DELETE, "/v3/orders/" + UriUtils.encodePathSegment(orderId,
                StandardCharsets.UTF_8), body.toString());
    }

    @Override
    public List<ServiceOption> services(Caller caller, String cityId) {
        Answer answer = authorised(caller, "SERVICES", HttpMethod.GET, "/v3/services?city_id="
                + UriUtils.encodeQueryParam(cityId, StandardCharsets.UTF_8) + "&delivery_type=TRUCK", null);
        List<ServiceOption> options = new ArrayList<>();
        if (answer.json() != null && answer.json().isArray()) {
            for (JsonNode service : answer.json()) {
                String id = text(service, "_id");
                if (id != null) {
                    String name = text(service, "name_vi_vn");
                    options.add(new ServiceOption(id, name != null ? name : firstNonNull(text(service, "name"),
                            text(service, "description_vi_vn"), id), text(service, "city_id")));
                }
            }
        }
        return options;
    }

    // ------------------------------------------------------------------ the calls

    private record Answer(int status, String body, JsonNode json) { }

    /** A call with the carrier's token; on 401 a new token is asked for and the call made once more. */
    private Answer authorised(Caller caller, String operation, HttpMethod method, String path, String body) {
        String key = tokenKey(caller);
        String token = tokenFor(key, caller);
        Answer answer = call(caller, operation, method, path, body, token);
        if (answer.status() == 401) {
            tokens.remove(key, token);
            token = tokenFor(key, caller);
            answer = call(caller, operation, method, path, body, token);
        }
        if (answer.status() >= 400) {
            throw refusal(answer);
        }
        return answer;
    }

    /** The kept token, or a new one asked for (not inside the map's compute: the call can take seconds). */
    private String tokenFor(String key, Caller caller) {
        String kept = tokens.get(key);
        if (kept != null) {
            return kept;
        }
        String fresh = token(caller);
        tokens.put(key, fresh);
        return fresh;
    }

    private String token(Caller caller) {
        ObjectNode body = json.createObjectNode();
        body.put("mobile", caller.account());
        body.put("api_key", caller.apiKey());
        Answer answer = call(caller, "TOKEN", HttpMethod.POST, "/v3/accounts/token", body.toString(), null);
        if (answer.status() >= 400) {
            throw refusal(answer);
        }
        String token = text(answer.json(), "token");
        if (token == null) {
            throw new CarrierCallFailed(true, null, "Ahamove answered the token request without a token");
        }
        return token;
    }

    private Answer call(Caller caller, String operation, HttpMethod method, String path, String body, String token) {
        RestClient client = caller.environment() == CarrierApiEnvironment.PRODUCTION ? production : staging;
        long started = System.nanoTime();
        try {
            RestClient.RequestBodySpec request = client.method(method).uri(path)
                    .accept(MediaType.APPLICATION_JSON);
            if (token != null) {
                request = request.header("Authorization", "Bearer " + token);
            }
            if (body != null) {
                request = request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            Answer answer = request.exchange((req, res) -> {
                byte[] bytes = res.getBody().readAllBytes();
                String text = new String(bytes, StandardCharsets.UTF_8);
                return new Answer(res.getStatusCode().value(), text, parse(text));
            });
            long millis = (System.nanoTime() - started) / 1_000_000;
            boolean ok = answer.status() < 400;
            // A token call carries the key and answers with the token: neither goes to the log.
            boolean secret = "TOKEN".equals(operation);
            calls.record(caller.carrierId(), caller.bookingId(), operation,
                    ok ? CarrierApiCalls.Outcome.OK : CarrierApiCalls.Outcome.REFUSED, answer.status(),
                    secret ? null : body, secret ? null : answer.body(), ok ? null : describe(answer), millis);
            return answer;
        } catch (RestClientException noAnswer) {
            long millis = (System.nanoTime() - started) / 1_000_000;
            calls.record(caller.carrierId(), caller.bookingId(), operation, CarrierApiCalls.Outcome.NO_ANSWER, null,
                    "TOKEN".equals(operation) ? null : body, null, noAnswer.getClass().getSimpleName(), millis);
            log.warn("Ahamove {} for carrier {} got no answer: {}", operation, caller.carrierId(),
                    noAnswer.getClass().getSimpleName());
            throw new CarrierCallFailed(false, null, "Ahamove did not answer");
        }
    }

    private CarrierCallFailed refusal(Answer answer) {
        String code = answer.json() == null ? null : firstNonNull(text(answer.json(), "code"),
                text(answer.json(), "error"));
        return new CarrierCallFailed(true, code, describe(answer));
    }

    /** "406 SERVICE_NOT_VALID_AT_PICKUP: <Ahamove's words>", from whatever the error body carries. */
    private static String describe(Answer answer) {
        JsonNode body = answer.json();
        String code = body == null ? null : firstNonNull(text(body, "code"), text(body, "error"));
        String words = body == null ? null : firstNonNull(text(body, "title"), text(body, "message"),
                text(body, "description"), text(body, "detail"));
        if (code == null && words == null) {
            words = answer.body() == null || answer.body().isBlank() ? null
                    : answer.body().length() <= 200 ? answer.body() : answer.body().substring(0, 200) + "...";
        }
        StringBuilder out = new StringBuilder().append(answer.status());
        if (code != null) {
            out.append(' ').append(code);
        }
        if (words != null) {
            out.append(": ").append(words);
        }
        return out.toString();
    }

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (IOException notJson) {
            return null;
        }
    }

    /** The token's cache key: the carrier, its environment, account and key - a new key asks for a new token. */
    private static String tokenKey(Caller caller) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(caller.apiKey().getBytes(StandardCharsets.UTF_8));
            return caller.carrierId() + "|" + caller.environment() + "|" + caller.account() + "|"
                    + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
