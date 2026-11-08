package com.stockflow.common.security;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Seals a secret the application must store and read back - a carrier's API key, the secret a carrier signs its
 * webhooks with (kltn-docs 09 §4.1: "không hiển thị lại sau khi lưu") - so that a dump of the database, a backup or a
 * row copied into {@code platform.legacy_archive} does not carry it in clear.
 *
 * <p>AES-256-GCM with a random 96-bit nonce per seal: authenticated, so a sealed value that was changed fails to open
 * rather than opening to garbage. The {@code context} is bound in as associated data - say
 * {@code "carrier:<id>:api-key"} - so a sealed value copied into another row or another column does not open there
 * either: nobody can move one carrier's key onto another by editing the table.</p>
 *
 * <p>The stored form is {@code v1:<base64(nonce || ciphertext || tag)>}; the version prefix leaves room for another
 * scheme or key without guessing what an old row holds. Hashing would not do: the key is sent to the carrier, so it
 * has to come back.</p>
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String VERSION = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @throws IllegalStateException when a key is configured but is not base64 of exactly 32 bytes: refusing to start
     *         beats sealing secrets with a key nobody meant to use
     */
    public SecretCipher(SecretCipherProperties properties) {
        String configured = properties == null ? null : properties.key();
        if (configured == null || configured.isBlank()) {
            this.key = null;
            log.warn("stockflow.security.secrets.key is not set: carrier API keys and webhook secrets cannot be "
                    + "saved until it is (STOCKFLOW_SECRETS_KEY, openssl rand -base64 32)");
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configured.strip());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("stockflow.security.secrets.key is not base64", notBase64);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("stockflow.security.secrets.key must be base64 of exactly 32 bytes, not "
                    + bytes.length);
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    /** Whether a key is configured, so secrets can be sealed and opened. */
    public boolean available() {
        return key != null;
    }

    /**
     * @param secret  the clear value; never logged
     * @param context what the value belongs to, bound in: the same context must be given to {@link #open}
     * @throws BusinessException 503 {@code SECRETS_UNAVAILABLE} when no key is configured
     */
    public String seal(String secret, String context) {
        if (secret == null || context == null) {
            throw new IllegalArgumentException("A secret and its context are required");
        }
        SecretKey current = requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, current, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + sealed.length)
                    .put(nonce).put(sealed).array());
        } catch (GeneralSecurityException impossible) {
            // AES-GCM is mandatory in every JDK; only a broken runtime gets here.
            throw new IllegalStateException("AES-GCM is unavailable", impossible);
        }
    }

    /**
     * @param sealed  a value {@link #seal} returned
     * @param context the context it was sealed with
     * @throws BusinessException 503 {@code SECRETS_UNAVAILABLE} when no key is configured
     * @throws IllegalStateException when the value does not open with this key and context - changed, moved to
     *         another row, or sealed under another key. The message names nothing of the value
     */
    public String open(String sealed, String context) {
        if (sealed == null || context == null) {
            throw new IllegalArgumentException("A sealed value and its context are required");
        }
        SecretKey current = requireKey();
        if (!sealed.startsWith(VERSION)) {
            throw new IllegalStateException("Not a sealed secret of a known version");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(sealed.substring(VERSION.length()));
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("A sealed secret is not base64");
        }
        if (bytes.length <= NONCE_BYTES) {
            throw new IllegalStateException("A sealed secret is too short");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, current, new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException doesNotOpen) {
            throw new IllegalStateException("A sealed secret does not open with this key and context");
        }
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw new BusinessException(ErrorCode.SECRETS_UNAVAILABLE,
                    "No secrets key is configured (stockflow.security.secrets.key)");
        }
        return key;
    }
}
