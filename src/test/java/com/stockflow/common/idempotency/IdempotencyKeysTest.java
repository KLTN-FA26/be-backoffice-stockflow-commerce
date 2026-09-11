package com.stockflow.common.idempotency;

import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeysTest {

    @Test
    @DisplayName("a UUID-shaped key is accepted and trimmed")
    void acceptsReasonableKeys() {
        assertThat(IdempotencyKeys.validate("  " + UUID.randomUUID() + "  "))
                .doesNotContain(" ");
        assertThat(IdempotencyKeys.validate("order:2026-09-07:000431"))
                .isEqualTo("order:2026-09-07:000431");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has space", "new\nline", "semi;colon"})
    @DisplayName("keys that would end up in a log line or an index are refused")
    void rejectsUnsafeKeys(String key) {
        assertThatThrownBy(() -> IdempotencyKeys.validate(key))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("an over-long key is refused rather than truncated")
    void rejectsOverlongKey() {
        // Truncating would map two different keys onto one stored response.
        assertThatThrownBy(() -> IdempotencyKeys.validate("a".repeat(300)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("the fingerprint changes when the body changes")
    void fingerprintCoversTheBody() {
        byte[] first = "{\"quantity\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"quantity\":2}".getBytes(StandardCharsets.UTF_8);

        // Without this, reusing a key for a different order would replay the first response and
        // report success for work that never happened.
        assertThat(IdempotencyKeys.fingerprint("POST", "/api/v1/orders", first))
                .isNotEqualTo(IdempotencyKeys.fingerprint("POST", "/api/v1/orders", second));
    }

    @Test
    @DisplayName("the fingerprint changes when the method or path changes")
    void fingerprintCoversMethodAndPath() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(IdempotencyKeys.fingerprint("POST", "/api/v1/orders", body))
                .isNotEqualTo(IdempotencyKeys.fingerprint("PUT", "/api/v1/orders", body))
                .isNotEqualTo(IdempotencyKeys.fingerprint("POST", "/api/v1/orders/1", body));
    }

    @Test
    @DisplayName("the same request always fingerprints the same, as 64 hex characters")
    void fingerprintIsStable() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String once = IdempotencyKeys.fingerprint("POST", "/api/v1/orders", body);

        assertThat(once).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(IdempotencyKeys.fingerprint("POST", "/api/v1/orders", body)).isEqualTo(once);
    }

    @Test
    @DisplayName("an empty body is handled, not treated as absent")
    void handlesEmptyBody() {
        assertThat(IdempotencyKeys.fingerprint("DELETE", "/api/v1/orders/1", new byte[0]))
                .isNotEqualTo(IdempotencyKeys.fingerprint("DELETE", "/api/v1/orders/2", new byte[0]));
    }

    @Test
    @DisplayName("keys are namespaced per caller so two clients cannot collide")
    void callerNamespacing() {
        UUID user = UUID.randomUUID();
        assertThat(IdempotencyKeys.callerNamespace(user)).isEqualTo(user.toString());
        assertThat(IdempotencyKeys.callerNamespace(null)).isEqualTo("anonymous");
    }
}
