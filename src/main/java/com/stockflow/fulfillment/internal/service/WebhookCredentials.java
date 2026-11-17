package com.stockflow.fulfillment.internal.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Whether a call to a carrier's webhook carries what the carrier was configured with (kltn-docs 09 §4.5 step 3, QD-11).
 * Both checks compare in constant time, on digests of equal length, so neither the content nor the length of the
 * expected value leaks through how long a wrong guess takes.
 */
final class WebhookCredentials {

    /** The header a WEBHOOK_LOAD carrier signs its body in (QD-11). */
    static final String SIGNATURE_HEADER = "X-Signature";

    private WebhookCredentials() {
    }

    /**
     * API_BOOKING: the configured header carries exactly the configured value (Ahamove sends {@code apikey: <value>},
     * {@code Authorization: Bearer <value>} or {@code Authorization: Basic <value>} - the whole header value is what was
     * configured).
     */
    static boolean headerHolds(String expected, String given) {
        if (expected == null || expected.isEmpty() || given == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(expected.getBytes(StandardCharsets.UTF_8)),
                sha256(given.strip().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * API_BOOKING while its credential is being replaced (WF-05 fix plan item 6): the header carries the current
     * value or the next one. Both are compared whatever the first comes to, so the time taken does not say which
     * of them a guess was closer to.
     *
     * @param next the second value that holds during a rotation; null when none is under way
     */
    static boolean headerHoldsEither(String current, String next, String given) {
        boolean first = headerHolds(current, given);
        boolean second = headerHolds(next, given);
        return first | second;
    }

    /**
     * WEBHOOK_LOAD: {@code header} is the HMAC-SHA256 of the raw body under the carrier's secret, in hex, bare or as
     * {@code sha256=<hex>} (QD-11). The bytes as received are what is signed: a parsed and re-serialised body would
     * never match.
     */
    static boolean signatureHolds(String secret, byte[] body, String header) {
        if (secret == null || secret.isEmpty() || header == null || body == null) {
            return false;
        }
        String given = header.strip().toLowerCase(Locale.ROOT);
        if (given.startsWith("sha256=")) {
            given = given.substring("sha256=".length());
        }
        String expected = HexFormat.of().formatHex(hmac(secret, body));
        return MessageDigest.isEqual(sha256(expected.getBytes(StandardCharsets.US_ASCII)),
                sha256(given.getBytes(StandardCharsets.US_ASCII)));
    }

    /** The signature a carrier puts in {@link #SIGNATURE_HEADER} for {@code body}: {@code sha256=<hex>}. */
    static String sign(String secret, byte[] body) {
        return "sha256=" + HexFormat.of().formatHex(hmac(secret, body));
    }

    private static byte[] hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is part of every Java runtime", unavailable);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", unavailable);
        }
    }
}
