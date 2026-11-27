package com.stockflow.payment.internal.controller;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.payment.internal.service.BankTransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * The bank feed's webhook (credit plan 2026-11-26 part C): {@code POST /api/v1/bank-transactions/webhooks/sepay}, the
 * URL given to SePay for incoming transfers to the company's account.
 *
 * <p><b>Anonymous, and safe to be.</b> SePay holds no token, so the route is open in the security chain and its
 * credential is what the webhook was configured with on my.sepay.vn: the HMAC-SHA256 signature of the raw body, or the
 * API key header, compared in constant time. The body is read up to {@value #MAX_BODY_BYTES} bytes; each address has a
 * request budget; the idempotency filter leaves the route alone, a repeat being told apart by SePay's id and the bank's
 * reference.</p>
 *
 * <p>Answered as SePay counts success, and nothing else: HTTP 200 with the body {@code {"success": true}} - not the API
 * envelope - for a transfer kept (or a repeat, or money going out, which is not kept). 401
 * {@code WEBHOOK_SIGNATURE_INVALID} for a credential that does not hold or a provider this system does not take; 400
 * for a body that is not a transfer; 503 {@code WEBHOOK_UNAVAILABLE} while the feed is off or a passing failure kept the
 * transfer unhandled, for SePay to send it again.</p>
 */
@RestController
@RequestMapping("/api/v1/bank-transactions/webhooks")
@Tag(name = "Bank transfers", description = "The bank feed's webhook and the Accountant's queue")
class BankTransactionWebhookController {

    /** A SePay call is well under 1 KB; nothing a bank sends is near this. */
    static final int MAX_BODY_BYTES = 16 * 1024;

    private final BankTransactionService transactions;

    BankTransactionWebhookController(BankTransactionService transactions) {
        this.transactions = transactions;
    }

    @PostMapping(value = "/{provider}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimit(limit = 600, perSeconds = 60, key = RateLimit.Key.IP, bucket = "bank-feed-webhook")
    @Operation(summary = "SePay's webhook for an incoming transfer; authenticated by its HMAC signature or API key, "
            + "not a token")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String provider, HttpServletRequest request)
            throws IOException {
        if (!"sepay".equalsIgnoreCase(provider)) {
            throw new BusinessException(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        byte[] body = read(request);
        return switch (transactions.receiveWebhook(request::getHeader, body)) {
            case TAKEN -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Map.of("success", true));
            case UNAUTHENTICATED -> throw new BusinessException(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
            case UNREADABLE -> throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The body is not an incoming or outgoing SePay transfer");
            case UNAVAILABLE, RETRY -> throw new BusinessException(ErrorCode.WEBHOOK_UNAVAILABLE);
        };
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
