package com.stockflow.common.idempotency;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The {@code Idempotency-Key} header, and the fingerprint that decides whether two requests
 * carrying the same key really are the same request.
 *
 * <h2>The model</h2>
 *
 * <p>Stripe's, and it is the right one: <b>if the client sends an idempotency key, we honour it;
 * if it does not, we do not.</b> No annotation, no per-endpoint configuration, no list of paths
 * that somebody has to remember to update. A client that cares about not double-charging sends the
 * header; one that does not, does not pay for the machinery.</p>
 *
 * <p>The alternative — marking endpoints {@code @Idempotent} — puts the decision in the wrong
 * place. Whether a retry is safe is a property of the <i>caller's</i> situation (a flaky mobile
 * network, a queue redelivery), not of the endpoint.</p>
 *
 * <h2>Why the fingerprint exists</h2>
 *
 * <p>A key on its own is not enough. If a client reuses a key for a genuinely different request —
 * a bug in their retry loop, or a key derived from something not unique enough — replaying the
 * stored response would tell them their new order succeeded when nothing was created. That is
 * worse than any error. The fingerprint lets us detect the reuse and answer
 * {@link ErrorCode#IDEMPOTENCY_KEY_REUSED} (422) instead of lying.</p>
 */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";

    /**
     * Long enough for a UUID, a ULID, or a composite like {@code order:2026-09-07:00431}.
     * Bounded because the value is stored, indexed and logged.
     */
    public static final int MAX_KEY_LENGTH = 255;

    private static final int MIN_KEY_LENGTH = 8;

    private IdempotencyKeys() {
    }

    /**
     * @throws BusinessException (400) if the key is too short, too long, or contains characters
     *                           that have no business in a header that ends up in logs and a
     *                           database index
     */
    public static String validate(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    HEADER + " must not be blank when present");
        }
        String trimmed = key.trim();
        if (trimmed.length() < MIN_KEY_LENGTH || trimmed.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "%s must be between %d and %d characters, got %d"
                            .formatted(HEADER, MIN_KEY_LENGTH, MAX_KEY_LENGTH, trimmed.length()));
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == ':' || c == '.';
            if (!safe) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        HEADER + " may contain only letters, digits and - _ : .");
            }
        }
        return trimmed;
    }

    /**
     * A stable digest of what makes this request <i>this</i> request: the method, the path, and
     * the body.
     *
     * <p>The query string is included in the path argument by the caller. The body matters: two
     * POSTs to the same path with different payloads are different requests, and this is the only
     * thing that can tell them apart.</p>
     *
     * <p>SHA-256 rather than {@code hashCode()}: 32 bits collide by accident at a few tens of
     * thousands of requests, and an accidental collision here means one client's response returned
     * to another. Not a security boundary — the key is already scoped to one caller — but the
     * collision probability has to be negligible, and 256 bits makes it so.</p>
     */
    public static String fingerprint(String method, String pathWithQuery, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(pathWithQuery.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (body != null) {
                digest.update(body);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            // Every JVM ships SHA-256; if this ever throws the platform is broken, not the code.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * Who the key belongs to.
     *
     * <p>Keys are namespaced per caller. Two clients picking the same key — entirely possible when
     * a key is derived from a business reference such as an order number — must not see each
     * other's responses. Anonymous callers share a namespace, which is acceptable because an
     * anonymous endpoint has nothing caller-specific to replay.</p>
     */
    public static String callerNamespace(java.util.UUID userId) {
        return userId == null ? "anonymous" : userId.toString();
    }
}
