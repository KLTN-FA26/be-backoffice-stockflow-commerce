package com.stockflow.order.internal.service.signing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * The one secret contract signing hangs its keys from ({@code CONTRACT_SIGNING_SECRET}), and the keys derived from it:
 * one for sealing the stored test certificate's private key, one for hashing one-time codes. Deriving by purpose
 * (HMAC-SHA256 of a label) means a leak of one use of the secret is not a leak of the other.
 *
 * <p>The {@code prod} profile refuses to start without it. Elsewhere a fixed development default is used, with a
 * warning: it is public in the source code, so anything sealed with it is sealed against nobody, which is fine on a
 * laptop and wrong on a server people reach.</p>
 */
@Component
class SigningSecrets {

    private static final Logger log = LoggerFactory.getLogger(SigningSecrets.class);
    private static final String DEVELOPMENT_DEFAULT = "stockflow-development-contract-signing-secret";

    private final byte[] secret;

    SigningSecrets(ContractSigningProperties properties, Environment environment) {
        boolean configured = properties.hasSecret();
        if (!configured && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("stockflow.order.contract-signing.secret (CONTRACT_SIGNING_SECRET) is "
                    + "required in the prod profile: it seals the contract signing key and hashes signing codes");
        }
        if (!configured) {
            log.warn("stockflow.order.contract-signing.secret is not set: a development default seals the contract "
                    + "signing key and hashes one-time codes. Set CONTRACT_SIGNING_SECRET on any server");
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
