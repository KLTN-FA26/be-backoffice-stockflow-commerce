package com.stockflow.common.security;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stored secrets (kltn-docs 09 §4.1: a carrier's keys are never shown again): sealed values open only with the same key
 * and context, a changed or moved value does not open, and a deployment without a key starts but cannot seal.
 */
class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32});

    private final SecretCipher cipher = new SecretCipher(new SecretCipherProperties(KEY));

    @Test
    @DisplayName("a sealed secret opens to itself with the same context, and never carries it in clear")
    void roundTrip() {
        String sealed = cipher.seal("ahamove-key-123456", "carrier:1:api-key");

        assertThat(sealed).startsWith("v1:").doesNotContain("ahamove-key-123456").matches("^v1:[A-Za-z0-9+/]+={0,2}$");
        assertThat(cipher.open(sealed, "carrier:1:api-key")).isEqualTo("ahamove-key-123456");
    }

    @Test
    @DisplayName("sealing the same secret twice gives two different values: the nonce is fresh each time")
    void freshNonce() {
        assertThat(cipher.seal("same", "c")).isNotEqualTo(cipher.seal("same", "c"));
    }

    @Test
    @DisplayName("a value moved to another carrier or column does not open there")
    void contextIsBound() {
        String sealed = cipher.seal("secret-value-xyz", "carrier:1:api-key");

        assertThatThrownBy(() -> cipher.open(sealed, "carrier:2:api-key"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret-value-xyz");
        assertThatThrownBy(() -> cipher.open(sealed, "carrier:1:webhook-secret"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a changed value, a value sealed under another key, or garbage does not open")
    void tamperingIsDetected() {
        String sealed = cipher.seal("secret-value-xyz", "c");
        byte[] bytes = Base64.getDecoder().decode(sealed.substring(3));
        bytes[bytes.length - 1] ^= 1;
        String changed = "v1:" + Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> cipher.open(changed, "c")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(new SecretCipherProperties(OTHER_KEY)).open(sealed, "c"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open("plain text", "c")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open("v1:!!!", "c")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open("v1:AAAA", "c")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("without a key the application starts, and sealing or opening answers 503 SECRETS_UNAVAILABLE")
    void noKeyMeansUnavailable() {
        SecretCipher none = new SecretCipher(new SecretCipherProperties(" "));

        assertThat(none.available()).isFalse();
        assertThat(cipher.available()).isTrue();
        assertThatThrownBy(() -> none.seal("x", "c")).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.SECRETS_UNAVAILABLE);
        assertThatThrownBy(() -> none.open("v1:AAAA", "c")).isInstanceOf(BusinessException.class);
        assertThat(ErrorCode.SECRETS_UNAVAILABLE.httpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("a key that is set but not base64 of 32 bytes stops the application instead of being used")
    void malformedKeyFailsFast() {
        assertThatThrownBy(() -> new SecretCipher(new SecretCipherProperties("not base64 !")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(new SecretCipherProperties(
                Base64.getEncoder().encodeToString(new byte[16])))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
