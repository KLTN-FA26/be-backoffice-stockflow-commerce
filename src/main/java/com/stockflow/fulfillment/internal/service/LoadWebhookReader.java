package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierReport;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the event of a carrier connected by webhook per load (WEBHOOK_LOAD; kltn-docs 09 §4.1, §4.5 step 1; QD-11) - the
 * system's own format, since no such carrier exists yet (E15). One JSON object per call, signed in
 * {@code X-Signature: sha256=<HMAC-SHA256 of the body, hex>} with the carrier's secret:
 * <pre>
 * {
 *   "loadCode":     "LD-20261109-0001",        required: the internal load code (BR-22)
 *   "type":         "DELIVERED",               required: WAYBILL, PICKED_UP, IN_TRANSIT, DELIVERED,
 *                                              DELIVERY_FAILED, RETURNED
 *   "reportedAt":   "2026-11-09T10:15:00+07:00", required: when it happened, ISO-8601 with an offset
 *   "shipmentCode": "SO-...-S1",               optional: the shipment an outcome is about; without it an outcome
 *                                              applies to every shipment on the load (BR-26)
 *   "waybillNo", "vehiclePlate", "driverName", "driverPhone": WAYBILL
 *   "failureReason": the carrier's own words, DELIVERY_FAILED (mapped by the carrier's table, E22)
 * }
 * </pre>
 * Field lengths are the load's own; anything longer is refused rather than cut, a carrier's number or plate being
 * meaningful only whole.
 */
final class LoadWebhookReader {

    private static final Map<String, CarrierEventKind> TYPES = Map.of(
            "WAYBILL", CarrierEventKind.WAYBILL,
            "PICKED_UP", CarrierEventKind.PICKED_UP,
            "IN_TRANSIT", CarrierEventKind.IN_TRANSIT,
            "DELIVERED", CarrierEventKind.DELIVERED,
            "DELIVERY_FAILED", CarrierEventKind.DELIVERY_FAILED,
            "RETURNED", CarrierEventKind.RETURNED);

    private LoadWebhookReader() {
    }

    static CarrierReport read(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new CarrierWebhookUnreadable("The event is not a JSON object");
        }
        String loadCode = text(body, "loadCode", 30, true);
        String type = text(body, "type", 32, true).toUpperCase(Locale.ROOT);
        CarrierEventKind kind = TYPES.get(type);
        if (kind == null) {
            throw new CarrierWebhookUnreadable("type: one of " + String.join(", ", TYPES.keySet().stream().sorted()
                    .toList()));
        }
        Instant reportedAt;
        try {
            reportedAt = OffsetDateTime.parse(text(body, "reportedAt", 64, true)).toInstant();
        } catch (DateTimeParseException notATime) {
            throw new CarrierWebhookUnreadable("reportedAt: an ISO-8601 time with an offset");
        }
        String waybill = text(body, "waybillNo", 64, false);
        if (kind == CarrierEventKind.WAYBILL && waybill == null) {
            throw new CarrierWebhookUnreadable("waybillNo: required with WAYBILL");
        }
        return new CarrierReport(kind, loadCode, null, text(body, "shipmentCode", 48, false), reportedAt, waybill,
                text(body, "vehiclePlate", 20, false), text(body, "driverName", 120, false),
                text(body, "driverPhone", 32, false), text(body, "failureReason", 500, false));
    }

    private static String text(JsonNode body, String field, int max, boolean required) {
        JsonNode value = body.get(field);
        String text = value == null || value.isNull() || value.isContainerNode() ? null : value.asText().strip();
        if (text == null || text.isEmpty()) {
            if (required) {
                throw new CarrierWebhookUnreadable(field + ": required");
            }
            return null;
        }
        if (text.length() > max) {
            throw new CarrierWebhookUnreadable(field + ": at most " + max + " characters");
        }
        return text;
    }
}
