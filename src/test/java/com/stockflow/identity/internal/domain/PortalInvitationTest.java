package com.stockflow.identity.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain rules only; database races require the pending persistence adapter and real PostgreSQL. */
class PortalInvitationTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final Instant EXPIRY = NOW.plus(Duration.ofHours(24));
    private final PortalInvitationToken token = PortalInvitationToken.generate();

    private PortalInvitation invitation() {
        return new PortalInvitation(UUID.randomUUID(), " Buyer@Example.test ", token.digest(),
                NOW, EXPIRY, null, 7);
    }

    @Test
    void tokenIsOpaqueCanonicalAndRedactedFromDiagnostics() {
        var secrets = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            var generated = PortalInvitationToken.generate();
            assertThat(generated.secret()).matches("[A-Za-z0-9_-]{43}");
            assertThat(generated.digest()).matches("[0-9a-f]{64}").isNotEqualTo(generated.secret());
            assertThat(PortalInvitationToken.parse(generated.secret()).digest()).isEqualTo(generated.digest());
            assertThat(generated.toString()).doesNotContain(generated.secret()).contains("REDACTED");
            assertThat(secrets.add(generated.secret())).isTrue();
        }
        assertThatThrownBy(() -> PortalInvitationToken.parse("A".repeat(42) + "B"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redemptionConsumesExactlyOnceAndKeepsTheBoundCustomerAndRecipient() {
        var invitation = invitation();
        UUID customerId = invitation.customerId();
        invitation.consume(token.secret(), NOW.plusSeconds(1), 7);
        assertThat(invitation.consumedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(invitation.customerId()).isEqualTo(customerId);
        assertThat(invitation.email()).isEqualTo("buyer@example.test");
        expect(ErrorCode.PORTAL_INVITATION_INVALID,
                () -> invitation.consume(token.secret(), NOW.plusSeconds(2), 7));
    }

    @Test
    void exactExpiryFutureIssueAndMalformedOrWrongSecretsCannotConsume() {
        for (Instant time : new Instant[]{NOW.minusSeconds(1), EXPIRY, EXPIRY.plusSeconds(1)}) {
            var invitation = invitation();
            expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> invitation.consume(token.secret(), time, 7));
            assertThat(invitation.consumedAt()).isNull();
        }
        for (String secret : new String[]{null, "", "invalid", PortalInvitationToken.generate().secret()}) {
            var invitation = invitation();
            expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> invitation.consume(secret, NOW, 7));
            assertThat(invitation.consumedAt()).isNull();
        }
    }

    @Test
    void resendThrottlesThenRotatesAndInvalidatesThePreviousLink() {
        var invitation = invitation();
        var replacement = PortalInvitationToken.generate();
        expect(ErrorCode.RATE_LIMITED, () -> invitation.resend(replacement.digest(), NOW.plusSeconds(59),
                EXPIRY, Duration.ofMinutes(1), 7));
        assertThat(invitation.tokenDigest()).isEqualTo(token.digest());
        invitation.resend(replacement.digest(), NOW.plusSeconds(60), EXPIRY, Duration.ofMinutes(1), 7);
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> invitation.consume(token.secret(), NOW.plusSeconds(61), 7));
        invitation.consume(replacement.secret(), NOW.plusSeconds(61), 7);
        assertThat(invitation.consumedAt()).isEqualTo(NOW.plusSeconds(61));
    }

    @Test
    void anExpiredInvitationCanBeReissuedButAnActivatedOneCannot() {
        var invitation = invitation();
        var replacement = PortalInvitationToken.generate();
        invitation.resend(replacement.digest(), EXPIRY, EXPIRY.plusSeconds(3600), Duration.ofMinutes(1), 7);
        invitation.consume(replacement.secret(), EXPIRY, 7);
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> invitation.resend(token.digest(),
                EXPIRY.plusSeconds(60), EXPIRY.plusSeconds(3600), Duration.ofMinutes(1), 7));
    }

    @Test
    void staleCommandsCannotConsumeOrReplaceTheCurrentInvitation() {
        var invitation = invitation();
        expect(ErrorCode.OPTIMISTIC_LOCK, () -> invitation.consume(token.secret(), NOW, 6));
        expect(ErrorCode.OPTIMISTIC_LOCK, () -> invitation.resend(PortalInvitationToken.generate().digest(),
                NOW.plusSeconds(60), EXPIRY, Duration.ofMinutes(1), 6));
        assertThat(invitation.consumedAt()).isNull();
        assertThat(invitation.tokenDigest()).isEqualTo(token.digest());
    }

    @Test
    void rejectsImpossibleLifetimesPlaintextPersistenceAndNonRotatingResend() {
        assertThatThrownBy(() -> new PortalInvitation(UUID.randomUUID(), "buyer@example.test", token.digest(),
                NOW, NOW, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortalInvitation(UUID.randomUUID(), "buyer@example.test", token.secret(),
                NOW, EXPIRY, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invitation().resend(token.digest(), NOW.plusSeconds(60), EXPIRY,
                Duration.ofMinutes(1), 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invitation().resend(PortalInvitationToken.generate().digest(), NOW,
                EXPIRY, Duration.ZERO, 7)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void expect(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
