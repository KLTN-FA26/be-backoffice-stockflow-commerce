package com.stockflow.order.internal.service.signing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How contracts are signed, bound from {@code stockflow.order.contract-signing.*}.
 *
 * <p>On a laptop and in tests (the {@code local} and {@code test} profiles) everything is optional: a test certificate is
 * generated and kept in the database, the secret falls back to a fixed default with a warning. Every other profile -
 * the VPS's {@code dev} included - refuses to start without a {@code secret} of at least 32 characters
 * ({@link SigningSecrets}), because a default secret would make every sealed key and every OTP hash guessable from the
 * source code.</p>
 *
 * @param keystore            base64 of a PKCS#12 holding the company's signing key and certificate chain; blank to use
 *                            the generated test certificate. Takes the place of the generated one entirely
 * @param keystorePassword    its password (also the key's); ignored without a keystore
 * @param secret              the secret the generated key is sealed with and the OTP codes are hashed with. Changing it
 *                            makes the stored test key unreadable (a new one is NOT made silently: startup of the
 *                            signer fails, so a deployment notices) and invalidates codes in flight
 * @param verificationBaseUrl the storefront's origin, {@code https://portal.example.vn}: the certificate page's QR code
 *                            points at {@code <base>/xac-minh-hop-dong/<code>}. Blank prints the code and no QR code
 * @param otpLifetime         how long a code works
 * @param otpResendAfter      how soon after one code another may be asked for
 * @param otpPerHour          most codes issued for one contract per hour
 */
@ConfigurationProperties(prefix = "stockflow.order.contract-signing")
public record ContractSigningProperties(String keystore, String keystorePassword, String secret,
                                        String verificationBaseUrl,
                                        @DefaultValue("PT10M") Duration otpLifetime,
                                        @DefaultValue("PT1M") Duration otpResendAfter,
                                        @DefaultValue("5") int otpPerHour) {

    @ConstructorBinding
    public ContractSigningProperties {
        if (otpLifetime == null || otpLifetime.isNegative() || otpLifetime.isZero()
                || otpLifetime.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("stockflow.order.contract-signing.otp-lifetime must be up to PT1H");
        }
        if (otpResendAfter == null || otpResendAfter.isNegative() || otpResendAfter.compareTo(otpLifetime) > 0) {
            throw new IllegalArgumentException(
                    "stockflow.order.contract-signing.otp-resend-after must not exceed the lifetime");
        }
        if (otpPerHour < 1 || otpPerHour > 20) {
            throw new IllegalArgumentException("stockflow.order.contract-signing.otp-per-hour must be 1 to 20");
        }
    }

    public boolean hasKeystore() {
        return keystore != null && !keystore.isBlank();
    }

    public boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }

    /** The QR target for a code, or null when no storefront address is configured. */
    public String verificationUrl(String code) {
        if (verificationBaseUrl == null || verificationBaseUrl.isBlank()) {
            return null;
        }
        String base = verificationBaseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/xac-minh-hop-dong/" + code;
    }
}
