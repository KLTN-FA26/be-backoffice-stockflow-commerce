package com.stockflow.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Links to an application route that work without signing in, for a limited time: what a partner outside the system -
 * a producer printing a production order (4A-3) - is sent by e-mail. The link is the route plus {@code expires} (epoch
 * seconds) and {@code signature}, an HMAC-SHA256 over both; the route answers only when {@link #verify} says
 * {@link Verdict#VALID}, and decides for itself what else must still hold (the production order still open, the file
 * still the one approved).
 *
 * <p>Not an object store's presigned URL: one of those reaches the bytes directly and cannot be checked again, which is
 * why they live minutes. This one reaches the application, which checks everything again on every use and only then
 * hands out a presigned URL of minutes - so it can live as long as the partner needs it. It is still a bearer
 * credential: nothing here logs one, the request log keeps no {@code signature} parameter, and an event never carries
 * one (an event is stored); it is made when the message is written.</p>
 *
 * <p>In {@code common} because the module that writes the message (notification) is not the one whose route it is: the
 * route's owner hands over the path, the writer signs it, the owner verifies it.</p>
 */
@Component
public class SignedLinks {

    /** Whether a link checks out. */
    public enum Verdict { VALID, INVALID, EXPIRED }

    private static final Logger log = LoggerFactory.getLogger(SignedLinks.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final String baseUrl;

    /**
     * @throws IllegalStateException when a key is configured but is not base64 of at least 32 bytes, when the base URL
     *         is not an http(s) address, or when it is set with no key and no permission for a process key: refusing to
     *         start beats handing out links nobody can open
     */
    public SignedLinks(SignedLinkProperties properties) {
        String configured = properties == null ? null : properties.key();
        boolean addressed = properties != null && properties.baseUrl() != null && !properties.baseUrl().isBlank();
        byte[] bytes;
        if (configured == null || configured.isBlank()) {
            if (addressed && !properties.processKeyAllowed()) {
                // Links would be sent that die at the next deploy and fail on every other instance.
                throw new IllegalStateException("stockflow.security.links.base-url is set but the key is not "
                        + "(STOCKFLOW_LINKS_KEY, openssl rand -base64 32)");
            }
            bytes = new byte[MIN_KEY_BYTES];
            new SecureRandom().nextBytes(bytes);
            log.warn("stockflow.security.links.key is not set: links sent to partners stop working at the next restart "
                    + "(STOCKFLOW_LINKS_KEY, openssl rand -base64 32)");
        } else {
            try {
                bytes = Base64.getDecoder().decode(configured.strip());
            } catch (IllegalArgumentException notBase64) {
                throw new IllegalStateException("stockflow.security.links.key is not base64", notBase64);
            }
            if (bytes.length < MIN_KEY_BYTES) {
                throw new IllegalStateException("stockflow.security.links.key must be base64 of at least "
                        + MIN_KEY_BYTES + " bytes, not " + bytes.length);
            }
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
        String base = properties == null || properties.baseUrl() == null ? "" : properties.baseUrl().strip();
        if (!base.isEmpty() && !base.startsWith("https://") && !base.startsWith("http://")) {
            throw new IllegalStateException("stockflow.security.links.base-url must be an http(s) address");
        }
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (this.baseUrl.isEmpty()) {
            log.warn("stockflow.security.links.base-url is not set: no link can be sent to a partner "
                    + "(STOCKFLOW_PUBLIC_BASE_URL)");
        }
    }

    /** Whether links can be made: the application's outside address is configured. */
    public boolean available() {
        return !baseUrl.isEmpty();
    }

    /**
     * The link to {@code path} that works until {@code expiresAt}.
     *
     * @param path a route of this application, starting with a single {@code /}, with no query
     * @throws IllegalStateException when no base URL is configured ({@link #available()})
     */
    public String url(String path, Instant expiresAt) {
        requirePath(path);
        if (!available()) {
            throw new IllegalStateException("No link can be made: stockflow.security.links.base-url is not set");
        }
        long expires = expiresAt.getEpochSecond();
        return baseUrl + path + "?expires=" + expires + "&signature=" + sign(path, expires);
    }

    /**
     * Whether {@code signature} was made here for {@code path} and {@code expires}, and whether it still holds at
     * {@code now}. A forged or altered link is {@link Verdict#INVALID} whatever its time; only a genuine one is ever
     * told it expired.
     */
    public Verdict verify(String path, long expires, String signature, Instant now) {
        if (path == null || signature == null || signature.isBlank()) {
            return Verdict.INVALID;
        }
        byte[] expected = sign(path, expires).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signature.strip().getBytes(StandardCharsets.US_ASCII))) {
            return Verdict.INVALID;
        }
        return now.getEpochSecond() < expires ? Verdict.VALID : Verdict.EXPIRED;
    }

    private String sign(String path, long expires) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(("v1\n" + path + "\n" + expires).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is not available", impossible);
        }
    }

    private static void requirePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("?")
                || path.contains("#")) {
            throw new IllegalArgumentException("A signed link is to a route of this application, without a query");
        }
    }
}
