package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Ahamove's order callback (partner API v3, developers.ahamove.com "Webhook" and "Order status flow") into a
 * {@link CarrierReport}, by kltn-docs 09 §4.5's table for carriers booked through an API (✅ 2026-11-06).
 *
 * <p>Ahamove calls back with the whole order each time it changes. A callback about one drop-off carries
 * {@code _id = <order id>-<k>} (k = 1 for the first drop-off, path[k] in the body) with the order {@code IN PROCESS};
 * every other callback carries the plain order id:</p>
 * <table>
 *   <caption>The mapping</caption>
 *   <tr><th>Ahamove</th><th>Report</th><th>Carrier time</th></tr>
 *   <tr><td>{@code ACCEPTED} (sub-status BOARDED or ARRIVED too)</td><td>DRIVER_ASSIGNED: {@code supplier_name},
 *       {@code supplier_id} (the driver's phone)</td><td>{@code accept_time}, or {@code board_time} once
 *       boarded</td></tr>
 *   <tr><td>{@code ASSIGNING} (after a driver gave it back)</td><td>DRIVER_RELEASED</td><td>none</td></tr>
 *   <tr><td>{@code IN PROCESS}</td><td>PICKED_UP</td><td>{@code pickup_time}</td></tr>
 *   <tr><td>{@code IN PROCESS} / {@code COMPLETING}</td><td>IN_TRANSIT</td><td>none</td></tr>
 *   <tr><td>drop-off {@code COMPLETED}</td><td>DELIVERED (E25: the stop's completion is the delivery)</td>
 *       <td>the stop's {@code complete_time}</td></tr>
 *   <tr><td>drop-off {@code FAILED}</td><td>DELIVERY_FAILED: {@code fail_comment}</td><td>the stop's
 *       {@code fail_time}</td></tr>
 *   <tr><td>{@code COMPLETED} / {@code IN_RETURN}</td><td>RETURNING</td><td>none</td></tr>
 *   <tr><td>{@code COMPLETED} / {@code RETURNED}</td><td>RETURNED</td><td>the latest stop {@code return_time}, else
 *       {@code complete_time}</td></tr>
 *   <tr><td>{@code COMPLETED}</td><td>ORDER_COMPLETED</td><td>{@code complete_time}</td></tr>
 *   <tr><td>{@code CANCELLED}</td><td>ORDER_CANCELLED: {@code cancel_comment}</td><td>{@code cancel_time}</td></tr>
 *   <tr><td>{@code IDLE}, anything else</td><td>OTHER</td><td>none</td></tr>
 * </table>
 * <p>Times are epoch seconds, a fraction allowed; 0 means not set. "None" is a report whose time the carrier does not
 * give: it is never judged older than another. The driver's name is cut to what the load holds; a phone longer than
 * that is dropped rather than cut.</p>
 */
final class AhamoveWebhookReader {

    private static final Pattern STOP_ID = Pattern.compile("^(.+)-(\\d{1,3})$");
    private static final int DRIVER_NAME_MAX = 120;
    private static final int DRIVER_PHONE_MAX = 32;

    private AhamoveWebhookReader() {
    }

    static CarrierReport read(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new CarrierWebhookUnreadable("Not an Ahamove order callback: the body is not a JSON object");
        }
        String id = text(body, "_id");
        if (id == null) {
            throw new CarrierWebhookUnreadable("Not an Ahamove order callback: no _id");
        }
        String status = upper(text(body, "status"));
        String sub = upper(text(body, "sub_status"));
        Matcher stopId = STOP_ID.matcher(id);
        if (stopId.matches()) {
            return stop(body, stopId.group(1), Integer.parseInt(stopId.group(2)));
        }
        return switch (status == null ? "" : status) {
            case "ACCEPTED" -> new CarrierReport(CarrierEventKind.DRIVER_ASSIGNED, id, null, null,
                    "BOARDED".equals(sub) ? firstSet(body, "board_time", "accept_time") : time(body, "accept_time"),
                    null, null, cut(text(body, "supplier_name"), DRIVER_NAME_MAX), phone(text(body, "supplier_id")),
                    null);
            case "ASSIGNING" -> CarrierReport.of(CarrierEventKind.DRIVER_RELEASED, id, null);
            case "IN PROCESS", "IN_PROCESS" -> "COMPLETING".equals(sub)
                    ? CarrierReport.of(CarrierEventKind.IN_TRANSIT, id, null)
                    : CarrierReport.of(CarrierEventKind.PICKED_UP, id, time(body, "pickup_time"));
            case "COMPLETED" -> switch (sub == null ? "" : sub) {
                case "RETURNED" -> CarrierReport.of(CarrierEventKind.RETURNED, id,
                        latestReturn(body) != null ? latestReturn(body) : time(body, "complete_time"));
                case "IN_RETURN", "IN RETURN" -> CarrierReport.of(CarrierEventKind.RETURNING, id, null);
                default -> CarrierReport.of(CarrierEventKind.ORDER_COMPLETED, id, time(body, "complete_time"));
            };
            case "CANCELLED" -> new CarrierReport(CarrierEventKind.ORDER_CANCELLED, id, null, null,
                    time(body, "cancel_time"), null, null, null, null, text(body, "cancel_comment"));
            default -> CarrierReport.of(CarrierEventKind.OTHER, id, null);
        };
    }

    /** A callback about drop-off {@code k}: its own status, time and failure reason, read from path[k]. */
    private static CarrierReport stop(JsonNode body, String orderId, int k) {
        JsonNode path = body.get("path");
        if (k < 1 || path == null || !path.isArray() || path.size() <= k || !path.get(k).isObject()) {
            throw new CarrierWebhookUnreadable("Ahamove callback for drop-off " + k + " of order " + orderId
                    + " has no such drop-off in its path");
        }
        JsonNode point = path.get(k);
        String status = upper(text(point, "status"));
        String tracking = text(point, "tracking_number");
        if ("COMPLETED".equals(status)) {
            return new CarrierReport(CarrierEventKind.DELIVERED, orderId, k, tracking, time(point, "complete_time"),
                    null, null, null, null, null);
        }
        if ("FAILED".equals(status)) {
            return new CarrierReport(CarrierEventKind.DELIVERY_FAILED, orderId, k, tracking, time(point, "fail_time"),
                    null, null, null, null, text(point, "fail_comment"));
        }
        return new CarrierReport(CarrierEventKind.OTHER, orderId, k, tracking, null, null, null, null, null, null);
    }

    private static Instant latestReturn(JsonNode body) {
        Instant latest = null;
        JsonNode path = body.get("path");
        if (path != null && path.isArray()) {
            for (JsonNode point : path) {
                Instant at = time(point, "return_time");
                if (at != null && (latest == null || at.isAfter(latest))) {
                    latest = at;
                }
            }
        }
        return latest;
    }

    private static Instant firstSet(JsonNode node, String first, String second) {
        Instant at = time(node, first);
        return at != null ? at : time(node, second);
    }

    /** Epoch seconds, a fraction allowed, as a number or a numeric string; 0, negative or unreadable: not set. */
    static Instant time(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        BigDecimal seconds;
        try {
            seconds = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (seconds.signum() <= 0 || seconds.compareTo(BigDecimal.valueOf(32_503_680_000L)) >= 0) {
            return null;
        }
        long whole = seconds.longValue();
        long nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValue();
        return Instant.ofEpochSecond(whole, nanos);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String phone(String value) {
        return value == null || value.length() > DRIVER_PHONE_MAX ? null : value;
    }
}
