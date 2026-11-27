package com.stockflow.payment.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.payment.internal.domain.BankTransfer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * SePay's webhook, as its developer documentation gives it (developer.sepay.vn, "Tích hợp webhook" and "Xác thực
 * webhook"), and nothing about what a transfer means.
 *
 * <pre>
 * POST, application/json
 * { "id": 92704, "gateway": "Vietcombank", "transactionDate": "2024-07-02 11:08:33", "accountNumber": "1017588888",
 *   "subAccount": "", "code": "SEVN63DC8E5C", "content": "SEVN63DC8E5C chuyen tien", "transferType": "in",
 *   "description": "...", "transferAmount": 5000000, "accumulated": 105000000, "referenceCode": "FT24012345678" }
 * success: HTTP 200 or 201, body {"success": true}, within 30 seconds - anything else is retried
 * </pre>
 *
 * <p>{@code id} is the same on every retry and every replay; {@code transactionDate} is Vietnam time; {@code code} is
 * what SePay's payment-code recognition read out of the content, null or empty when nothing; {@code transferAmount} is
 * whole dong, always positive, its direction in {@code transferType} ({@code in} / {@code out}).</p>
 *
 * <p>Authentication, one of two: HMAC-SHA256 over {@code <X-SePay-Timestamp>.<raw body>} in
 * {@code X-SePay-Signature: sha256=<hex>}, or {@code Authorization: Apikey <key>}. Both compared in constant time, on the
 * raw bytes received - never on JSON written again.</p>
 */
final class SepayWebhook {

    static final String SIGNATURE_HEADER = "X-SePay-Signature";
    static final String TIMESTAMP_HEADER = "X-SePay-Timestamp";
    static final String AUTHORIZATION = "Authorization";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String API_KEY_PREFIX = "Apikey ";
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** What a call reports: an incoming transfer, money going out (not kept), or nothing readable. */
    sealed interface Reading permits Incoming, Outgoing, Unreadable {
    }

    record Incoming(BankTransfer transfer) implements Reading {
    }

    record Outgoing(String providerTransactionId) implements Reading {
    }

    record Unreadable(String why) implements Reading {
    }

    private SepayWebhook() {
    }

    /**
     * Whether the call carries the credential configured: the HMAC signature when a secret is set, else the API key.
     * False when neither is configured - a webhook with no credential is anyone's.
     */
    static boolean authentic(BankFeedProperties.Webhook config, UnaryOperator<String> header, byte[] body,
                             Instant now) {
        if (config.hmac()) {
            return signed(config.hmacSecret(), config.maxSkew(), header.apply(SIGNATURE_HEADER),
                    header.apply(TIMESTAMP_HEADER), body, now);
        }
        if (config.key()) {
            String authorization = header.apply(AUTHORIZATION);
            return authorization != null && authorization.startsWith(API_KEY_PREFIX)
                    && same(config.apiKey().strip(), authorization.substring(API_KEY_PREFIX.length()).strip());
        }
        return false;
    }

    static boolean signed(String secret, Duration maxSkew, String signature, String timestamp, byte[] body,
                          Instant now) {
        if (signature == null || timestamp == null || !signature.startsWith(SIGNATURE_PREFIX)
                || !timestamp.strip().matches("\\d{1,12}")) {
            return false;
        }
        long seconds = Long.parseLong(timestamp.strip());
        if (Math.abs(now.getEpochSecond() - seconds) > maxSkew.toSeconds()) {
            return false;
        }
        return same(sign(secret, timestamp.strip(), body), signature.substring(SIGNATURE_PREFIX.length()).strip()
                .toLowerCase(java.util.Locale.ROOT));
    }

    /** The hex HMAC-SHA256 of {@code <timestamp>.<body>} under {@code secret}: what SePay puts after {@code sha256=}. */
    static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean same(String expected, String given) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    /** What the body reports; never throws on what a caller sent. */
    static Reading read(byte[] body, ObjectMapper json) {
        JsonNode root;
        try {
            root = body == null || body.length == 0 ? null : json.readTree(body);
        } catch (IOException notJson) {
            return new Unreadable("The body is not JSON");
        }
        if (root == null || !root.isObject()) {
            return new Unreadable("The body is not a JSON object");
        }
        JsonNode id = root.get("id");
        if (id == null || !(id.isIntegralNumber() || id.isTextual()) || id.asText().isBlank()) {
            return new Unreadable("No transaction id");
        }
        String providerId = id.asText().strip();
        String type = text(root, "transferType");
        if ("out".equalsIgnoreCase(type)) {
            return new Outgoing(providerId);
        }
        if (!"in".equalsIgnoreCase(type)) {
            return new Unreadable("transferType is neither in nor out");
        }
        BigDecimal amount = amount(root.get("transferAmount"));
        if (amount == null) {
            return new Unreadable("transferAmount is not a whole number of dong above zero");
        }
        Optional<Instant> at = vietnamTime(text(root, "transactionDate"));
        if (at.isEmpty()) {
            return new Unreadable("transactionDate is not yyyy-MM-dd HH:mm:ss");
        }
        try {
            return new Incoming(new BankTransfer(providerId, text(root, "accountNumber"), amount,
                    text(root, "content"), text(root, "referenceCode"), text(root, "code"), at.get(),
                    root.toString()));
        } catch (IllegalArgumentException invalid) {
            return new Unreadable(invalid.getMessage());
        }
    }

    /** {@code yyyy-MM-dd HH:mm:ss} in Vietnam time, as SePay writes its dates. */
    static Optional<Instant> vietnamTime(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(value.strip(), DATE_TIME).atZone(InstallmentService.BUSINESS_ZONE)
                    .toInstant());
        } catch (DateTimeParseException wrong) {
            return Optional.empty();
        }
    }

    /** A whole number of dong above zero, from a JSON number or a numeric string ({@code "18067000.00"}). */
    static BigDecimal amount(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            BigDecimal value = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText().strip());
            return value.signum() > 0 && value.stripTrailingZeros().scale() <= 0 ? value.stripTrailingZeros()
                    .setScale(0) : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
