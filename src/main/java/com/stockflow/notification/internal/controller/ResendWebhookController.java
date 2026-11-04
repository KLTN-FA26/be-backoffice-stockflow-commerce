package com.stockflow.notification.internal.controller;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.notification.internal.service.ResendWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * Resend's delivery reports (SCRUM-352): {@code POST /api/v1/notifications/webhooks/resend}, configured in the Resend
 * dashboard with the events {@code email.delivered}, {@code email.bounced}, {@code email.complained},
 * {@code email.delivery_delayed} and {@code email.failed}.
 *
 * <p><b>Anonymous, and safe to be.</b> Resend cannot hold a token, so the endpoint is open in the security chain and
 * the Svix signature is the authentication ({@link ResendWebhookService}). Before any of that: the body is read only up
 * to {@value #MAX_BODY_BYTES} bytes, the raw bytes are what is verified (a parsed and re-serialised body would never
 * match its signature), and each address gets a request budget. Every answer is 204 or an error envelope with a code
 * and no detail: nothing about a delivery, or whether one exists, leaves through here.</p>
 *
 * <p>The signature headers are read under both names in use: {@code svix-*} (what Resend sends) and
 * {@code webhook-*} (the Standard Webhooks names of the same values).</p>
 */
@RestController
@RequestMapping("/api/v1/notifications/webhooks")
@Tag(name = "Notifications", description = "Delivery reports from the e-mail provider")
class ResendWebhookController {

    /** A Resend report is a few KB; this is room for a large one and no more. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final ResendWebhookService webhooks;

    ResendWebhookController(ResendWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RateLimit(limit = 600, perSeconds = 60, key = RateLimit.Key.IP, bucket = "resend-webhook")
    @Operation(summary = "Resend delivery report; authenticated by its Svix signature, not by a token")
    public void resend(@RequestHeader(value = "svix-id", required = false) String svixId,
                       @RequestHeader(value = "svix-timestamp", required = false) String svixTimestamp,
                       @RequestHeader(value = "svix-signature", required = false) String svixSignature,
                       @RequestHeader(value = "webhook-id", required = false) String webhookId,
                       @RequestHeader(value = "webhook-timestamp", required = false) String webhookTimestamp,
                       @RequestHeader(value = "webhook-signature", required = false) String webhookSignature,
                       HttpServletRequest request) throws IOException {
        byte[] body = read(request);
        webhooks.receive(either(svixId, webhookId), either(svixTimestamp, webhookTimestamp),
                either(svixSignature, webhookSignature), body);
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

    private static String either(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
