package com.stockflow.order.internal.service.signing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * The one secret contract signing hangs its keys from ({@code CONTRACT_SIGNING_SECRET}), and the keys derived from it:
 * one for sealing the stored test certificate's private key, one for hashing one-time codes. Deriving by purpose
 * (HMAC-SHA256 of a label) means a leak of one use of the secret is not a leak of the other.
 *
 * <p>Only the {@code local} and {@code test} profiles run without it, on a fixed development default with a warning: it
 * is public in the source code, so anything sealed with it is sealed against nobody, which is fine on a laptop and in a
 * test and wrong on a server people reach. Every other profile refuses to start without a secret of at least
 * {@value #MIN_LENGTH} characters - {@code prod}, and {@code dev}, which is what the VPS runs (a list of profiles that
 * must have it would miss the next one deployed; the two that need not are the ones known).</p>
 */
@Component
class SigningSecrets {

    private static final Logger log = LoggerFactory.getLogger(SigningSecrets.class);
    private static final String DEVELOPMENT_DEFAULT = "stockflow-development-contract-signing-secret";
    /** The shortest secret accepted on a server: {@code openssl rand -base64 48} gives 64 characters. */
    static final int MIN_LENGTH = 32;

    private final byte[] secret;

    SigningSecrets(ContractSigningProperties properties, Environment environment) {
        boolean configured = properties.hasSecret();
        if (!environment.acceptsProfiles(Profiles.of("local", "test"))) {
            String profiles = String.join(",", environment.getActiveProfiles()).toLowerCase(Locale.ROOT);
            if (!configured) {
                throw new IllegalStateException("stockflow.order.contract-signing.secret (CONTRACT_SIGNING_SECRET) is "
                        + "required outside the local and test profiles (active: " + (profiles.isEmpty() ? "none"
                        : profiles) + "): it seals the contract signing key and hashes signing codes. Generate one "
                        + "with: openssl rand -base64 48");
            }
            if (properties.secret().strip().length() < MIN_LENGTH) {
                throw new IllegalStateException("stockflow.order.contract-signing.secret (CONTRACT_SIGNING_SECRET) "
                        + "must be at least " + MIN_LENGTH + " characters: openssl rand -base64 48");
            }
        }
        if (!configured) {
            log.warn("stockflow.order.contract-signing.secret is not set: a development default seals the contract "
                    + "signing key and hashes one-time codes (local and test profiles only)");
        }
        this.secret = (configured ? properties.secret().strip() : DEVELOPMENT_DEFAULT).getBytes(StandardCharsets.UTF_8);
    }

    /** A 32-byte key for {@code purpose}. */
    byte[] derive(String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(("stockflow:contract-signing:" + purpose + ":v1").getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }
}
