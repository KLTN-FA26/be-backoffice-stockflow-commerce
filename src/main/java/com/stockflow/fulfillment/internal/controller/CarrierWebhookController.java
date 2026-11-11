package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.fulfillment.internal.service.CarrierEvents;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Carriers' webhooks (3-5; kltn-docs 09 §4.5): {@code POST /api/v1/fulfillment/carrier-webhooks/{carrierCode}}, the URL
 * given to each carrier - Ahamove's partner callback, or a WEBHOOK_LOAD carrier's events.
 *
 * <p><b>Anonymous, and safe to be.</b> A carrier holds no token, so the route is open in the security chain and its
 * credential is what the carrier was configured with (3-1): an API_BOOKING carrier's header, compared in constant time,
 * or a WEBHOOK_LOAD carrier's HMAC signature of the raw body. The body is read only up to {@value #MAX_BODY_BYTES} bytes
 * and the raw bytes are what is checked; each address gets a request budget; the idempotency filter leaves the route
 * alone, a repeat being told apart by the event log (BR-27). Every call naming a known carrier is kept - a refused
 * one as a summary - whatever it says.</p>
 *
 * <p>An authenticated call is answered 200 with the event's id and result - nothing about the load - or 503
 * {@code WEBHOOK_UNAVAILABLE} when it could not be handled now (a passing failure, an order whose booking is still being
 * saved), for the carrier to send it again. A call whose credential does not hold, or that names no carrier the system
 * knows, is 401 {@code WEBHOOK_SIGNATURE_INVALID}, the same for both, so the route tells no one which carrier codes
 * exist.</p>
 */
@RestController
@RequestMapping("/api/v1/fulfillment/carrier-webhooks")
@Tag(name = "Fulfillment - carrier events", description = "Carriers' webhooks and the error list")
class CarrierWebhookController {

    /** The column's limit too ({@code ck_carrier_event_payload}): an Ahamove order with its stops is a few KB. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final CarrierEvents events;

    CarrierWebhookController(CarrierEvents events) {
        this.events = events;
    }

    @PostMapping("/{carrierCode}")
    @RateLimit(limit = 600, perSeconds = 60, key = RateLimit.Key.IP, bucket = "carrier-webhook")
    @Operation(summary = "A carrier's webhook; authenticated by the carrier's configured header or signature, not a token")
    public ApiResponse<Map<String, Object>> receive(@PathVariable String carrierCode, HttpServletRequest request)
            throws IOException {
        byte[] body = read(request);
        CarrierEvents.Receipt receipt = events.receive(carrierCode, request::getHeader, body);
        if (!receipt.authenticated()) {
            throw new BusinessException(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        if (receipt.retry()) {
            // Kept, not handled yet: a carrier that retries on a 5xx sends it again.
            throw new BusinessException(ErrorCode.WEBHOOK_UNAVAILABLE);
        }
        return ApiResponse.ok(Map.of("eventId", receipt.eventId(), "result", receipt.result()));
    }

    private static byte[] read(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new BusinessException(ErrorCode.WEBHOOK_PAYLOAD_TOO_LARGE);
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new BusinessException(ErrorCode.WEBHOOK_PAYLOAD_TOO_LARGE);
            }
            return body;
        }
    }
}
