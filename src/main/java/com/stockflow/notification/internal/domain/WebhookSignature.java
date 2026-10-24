package com.stockflow.notification.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checks a webhook signed the Svix way, which is how Resend signs its webhooks (headers {@code svix-id},
 * {@code svix-timestamp}, {@code svix-signature}).
 *
 * <p>The signed content is {@code id + "." + timestamp + "." + body}, the body exactly as received - parse it first
 * and re-serialise it, and the bytes no longer match. The key is the secret after its {@code whsec_} prefix,
 * base64-decoded. The signature header lists one or more {@code v1,<base64 HMAC-SHA256>}, space-separated (several
 * while a secret is being rotated); one match is enough. A timestamp more than {@code tolerance} away from now is
 * refused, so a signed request captured once cannot be replayed later.</p>
 *
 * <p>Comparison is constant-time ({@link MessageDigest#isEqual}): a comparison that stops at the first wrong byte
 * tells a patient attacker how many bytes they got right.</p>
 */
public final class WebhookSignature {

    private static final String SECRET_PREFIX = "whsec_";
    private static final String VERSION = "v1,";

    private final byte[] key;
    private final Duration tolerance;

    /**
     * @throws IllegalArgumentException when the secret is not a base64 key (with or without its {@code whsec_} prefix)
     */
    public WebhookSignature(String secret, Duration tolerance) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("A webhook secret is required");
        }
        String encoded = secret.strip().startsWith(SECRET_PREFIX) ? secret.strip().substring(SECRET_PREFIX.length())
                : secret.strip();
        this.key = Base64.getDecoder().decode(encoded);
        if (key.length == 0) {
            throw new IllegalArgumentException("The webhook secret is empty");
        }
        this.tolerance = tolerance;
    }

    /** Whether the request was signed with this secret, recently enough. Never throws on what a caller sent. */
    public boolean verify(String id, String timestamp, String signatures, byte[] body, Instant now) {
        if (blank(id) || blank(timestamp) || blank(signatures) || body == null) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.strip());
        } catch (NumberFormatException notSeconds) {
            return false;
        }
        Instant signedAt = Instant.ofEpochSecond(seconds);
        if (Duration.between(signedAt, now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = sign(id.strip(), timestamp.strip(), body);
        for (String candidate : signatures.strip().split("\\s+")) {
            if (!candidate.startsWith(VERSION)) {
                continue;
            }
            byte[] given;
            try {
                given = Base64.getDecoder().decode(candidate.substring(VERSION.length()));
            } catch (IllegalArgumentException notBase64) {
                continue;
            }
            if (MessageDigest.isEqual(expected, given)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code v1,<signature>} a sender with this secret puts in the header; tests sign their requests with it. */
    public String sign(String id, Instant timestamp, byte[] body) {
        return VERSION + Base64.getEncoder().encodeToString(sign(id, Long.toString(timestamp.getEpochSecond()), body));
    }

    private byte[] sign(String id, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException unavailable) {
            // HmacSHA256 is a required algorithm of every Java platform; reaching this is a broken JVM.
            throw new IllegalStateException("HmacSHA256 is not available", unavailable);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
