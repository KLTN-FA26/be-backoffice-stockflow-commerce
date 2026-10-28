package com.stockflow.notification.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Svix scheme Resend signs its webhooks with. */
class WebhookSignatureTest {

    private static final String SECRET = "whsec_" + Base64.getEncoder()
            .encodeToString("a-test-signing-key-of-32-bytes!!".getBytes(StandardCharsets.UTF_8));
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
    private static final byte[] BODY = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"e-1\"}}"
            .getBytes(StandardCharsets.UTF_8);

    private final WebhookSignature signature = new WebhookSignature(SECRET, Duration.ofMinutes(5));

    private String stamp(Instant at) {
        return Long.toString(at.getEpochSecond());
    }

    @Test
    @DisplayName("a request signed with the secret, now, over these exact bytes is accepted")
    void validSignature() {
        String signed = signature.sign("msg_1", NOW, BODY);
        assertThat(signed).startsWith("v1,");
        assertThat(signature.verify("msg_1", stamp(NOW), signed, BODY, NOW)).isTrue();
    }

    @Test
    @DisplayName("the known Svix test vector verifies: the scheme is the real one, not just self-consistent")
    void svixReferenceVector() {
        // From Svix's published verification example (secret, id, timestamp, payload, expected signature).
        var reference = new WebhookSignature("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw", Duration.ofMinutes(5));
        byte[] payload = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);
        Instant signedAt = Instant.ofEpochSecond(1614265330);
        assertThat(reference.verify("msg_p5jXN8AQM9LWM0D4loKWxJek", "1614265330",
                "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=", payload, signedAt)).isTrue();
    }

    @Test
    @DisplayName("one changed byte, another id, another secret or a re-serialised body fails")
    void tampering() {
        String signed = signature.sign("msg_1", NOW, BODY);
        byte[] changed = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"e-1\"}}".getBytes(StandardCharsets.UTF_8);
        assertThat(signature.verify("msg_1", stamp(NOW), signed, changed, NOW)).isFalse();
        assertThat(signature.verify("msg_2", stamp(NOW), signed, BODY, NOW)).isFalse();
        var other = new WebhookSignature("whsec_" + Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(5));
        assertThat(other.verify("msg_1", stamp(NOW), signed, BODY, NOW)).isFalse();
        byte[] spaced = "{ \"type\":\"email.delivered\",\"data\":{\"email_id\":\"e-1\"}}".getBytes(StandardCharsets.UTF_8);
        assertThat(signature.verify("msg_1", stamp(NOW), signed, spaced, NOW)).isFalse();
    }

    @Test
    @DisplayName("a signed request older or newer than the tolerance is a replay and is refused")
    void replayWindow() {
        Instant old = NOW.minus(Duration.ofMinutes(6));
        assertThat(signature.verify("msg_1", stamp(old), signature.sign("msg_1", old, BODY), BODY, NOW)).isFalse();
        Instant future = NOW.plus(Duration.ofMinutes(6));
        assertThat(signature.verify("msg_1", stamp(future), signature.sign("msg_1", future, BODY), BODY, NOW))
                .isFalse();
        Instant recent = NOW.minus(Duration.ofMinutes(4));
        assertThat(signature.verify("msg_1", stamp(recent), signature.sign("msg_1", recent, BODY), BODY, NOW))
                .isTrue();
    }

    @Test
    @DisplayName("a timestamp no Instant can hold is refused, not thrown: the caller gets 401, not 500")
    void hugeTimestamp() {
        String signed = signature.sign("msg_1", NOW, BODY);
        for (String timestamp : java.util.List.of(Long.toString(Long.MAX_VALUE), Long.toString(Long.MIN_VALUE),
                "31556889864403200", "-31557014167219201")) {
            assertThat(signature.verify("msg_1", timestamp, signed, BODY, NOW)).as(timestamp).isFalse();
        }
    }

    @Test
    @DisplayName("during a secret rotation the header lists several signatures; one match is enough")
    void severalSignatures() {
        String signed = signature.sign("msg_1", NOW, BODY);
        assertThat(signature.verify("msg_1", stamp(NOW), "v1,AAAA " + signed + " v2,whatever", BODY, NOW)).isTrue();
    }

    @Test
    @DisplayName("missing or malformed headers are refused, never thrown")
    void malformedHeaders() {
        String signed = signature.sign("msg_1", NOW, BODY);
        assertThat(signature.verify(null, stamp(NOW), signed, BODY, NOW)).isFalse();
        assertThat(signature.verify("msg_1", null, signed, BODY, NOW)).isFalse();
        assertThat(signature.verify("msg_1", stamp(NOW), null, BODY, NOW)).isFalse();
        assertThat(signature.verify("msg_1", "yesterday", signed, BODY, NOW)).isFalse();
        assertThat(signature.verify("msg_1", stamp(NOW), "v1,not-base64!!", BODY, NOW)).isFalse();
        assertThat(signature.verify("msg_1", stamp(NOW), signed.substring(3), BODY, NOW)).isFalse();
    }

    @Test
    @DisplayName("a secret that is not base64 fails at construction, i.e. at startup")
    void malformedSecret() {
        assertThatThrownBy(() -> new WebhookSignature("whsec_***not base64***", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookSignature(" ", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
