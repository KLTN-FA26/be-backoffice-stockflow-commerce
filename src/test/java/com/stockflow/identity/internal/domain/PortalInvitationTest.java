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

/** Invitation and invited-account rules. Locking and races are proved against PostgreSQL in B2bOnboardingIntegrationTest. */
class PortalInvitationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final Duration LIFETIME = Duration.ofHours(72);
    private static final Duration COOLDOWN = Duration.ofMinutes(1);
    private static final String HASH = "{bcrypt}$2a$10$abcdefghijklmnopqrstuuJ0123456789012345678901234567890";

    private final UUID userId = UUID.randomUUID();

    private PortalInvitation issued(PortalInvitationToken token, Instant at) {
        return PortalInvitation.issue(UUID.randomUUID(), token, null, at, LIFETIME);
    }

    @Test
    void tokenIsCanonicalNamesItsAccountAndIsRedacted() {
        var values = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            var token = PortalInvitationToken.generate(userId);
            assertThat(token.value()).matches("[A-Za-z0-9_-]{64}");
            assertThat(token.digest()).matches("[0-9a-f]{64}");
            var parsed = PortalInvitationToken.parse(token.value()).orElseThrow();
            assertThat(parsed.userId()).isEqualTo(userId);
            assertThat(parsed.digest()).isEqualTo(token.digest());
            assertThat(token.toString()).doesNotContain(token.value()).contains("REDACTED");
            assertThat(values.add(token.value())).isTrue();
        }
        for (String bad : new String[]{null, "", "short", "A".repeat(63) + "=", "A".repeat(65), "A".repeat(63) + "+"}) {
            assertThat(PortalInvitationToken.parse(bad)).isEmpty();
        }
    }

    @Test
    void anInvitationKeepsOnlyTheDigestAndWorksOnceWithinItsLifetime() {
        var token = PortalInvitationToken.generate(userId);
        PortalInvitation invitation = issued(token, NOW);
        assertThat(invitation.tokenDigest()).isEqualTo(token.digest()).isNotEqualTo(token.value());
        assertThat(invitation.toString()).doesNotContain(token.value()).doesNotContain(token.digest());
        assertThat(invitation.expiresAt()).isEqualTo(NOW.plus(LIFETIME));

        invitation.consume(token, NOW.plusSeconds(10));
        assertThat(invitation.isOpen()).isFalse();
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> invitation.consume(token, NOW.plusSeconds(11)));
    }

    @Test
    void expiryIsExclusiveAndAWrongOrForeignTokenFailsAlike() {
        var token = PortalInvitationToken.generate(userId);
        for (Instant at : new Instant[]{NOW.minusSeconds(1), NOW.plus(LIFETIME), NOW.plus(LIFETIME).plusSeconds(1)}) {
            PortalInvitation invitation = issued(token, NOW);
            expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> invitation.consume(token, at));
            assertThat(invitation.isOpen()).isTrue();
        }
        PortalInvitation invitation = issued(token, NOW);
        expect(ErrorCode.PORTAL_INVITATION_INVALID,
                () -> invitation.consume(PortalInvitationToken.generate(userId), NOW.plusSeconds(1)));
        expect(ErrorCode.PORTAL_INVITATION_INVALID,
                () -> invitation.consume(PortalInvitationToken.generate(UUID.randomUUID()), NOW.plusSeconds(1)));
        invitation.consume(token, NOW.plus(LIFETIME).minusSeconds(1));
    }

    @Test
    void resendWaitsForTheCooldownUnlessTheInvitationExpiredAndARevokedOneNeverRedeems() {
        var token = PortalInvitationToken.generate(userId);
        PortalInvitation open = issued(token, NOW);
        expect(ErrorCode.RATE_LIMITED, () -> open.revokeForResend(NOW.plusSeconds(30), COOLDOWN));
        assertThat(open.isOpen()).isTrue();
        open.revokeForResend(NOW.plus(COOLDOWN), COOLDOWN);
        assertThat(open.revokedAt()).isEqualTo(NOW.plus(COOLDOWN));
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> open.consume(token, NOW.plus(COOLDOWN).plusSeconds(1)));
        assertThatThrownBy(() -> open.revokeForResend(NOW.plus(LIFETIME), COOLDOWN))
                .isInstanceOf(IllegalStateException.class);

        PortalInvitation expired = issued(PortalInvitationToken.generate(userId), NOW);
        expired.revokeForResend(NOW.plus(LIFETIME), Duration.ofDays(10));
        assertThat(expired.isOpen()).isFalse();
    }

    @Test
    void reconstructionEnforcesTheSameRulesAsTheTable() {
        UUID id = UUID.randomUUID();
        String digest = "a".repeat(64);
        assertThatThrownBy(() -> new PortalInvitation(id, userId, "nothex", NOW, NOW.plusSeconds(60), null,
                null, null, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortalInvitation(id, userId, digest, NOW, NOW, null,
                null, null, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortalInvitation(id, userId, digest, NOW, NOW.plusSeconds(60), null,
                NOW, NOW, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortalInvitation(id, userId, digest, NOW, NOW.plusSeconds(60), null,
                null, null, PortalInvitation.Delivery.SENT, null, 0)).isInstanceOf(IllegalArgumentException.class);
        var recorded = new PortalInvitation(id, userId, digest, NOW, NOW.plusSeconds(60), null, null, null, null, null, 0);
        recorded.recordDelivery(PortalInvitation.Delivery.FAILED, NOW.plusSeconds(1));
        assertThat(recorded.delivery()).isEqualTo(PortalInvitation.Delivery.FAILED);
    }

    @Test
    void anInvitedAccountHasNoPasswordAndActivatesOnceUnlessAnAdministratorLockedIt() {
        User user = User.inviteCustomer(userId, " Buyer@Example.TEST ", "Buyer");
        assertThat(user.awaitingActivation()).isTrue();
        assertThat(user.passwordHash()).isNull();
        assertThat(user.username()).isEqualTo("buyer@example.test");
        user.activate(HASH);
        assertThat(user.awaitingActivation()).isFalse();
        assertThat(user.passwordHash()).isEqualTo(HASH);
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> user.activate(HASH));

        User locked = User.inviteCustomer(UUID.randomUUID(), "locked@example.test", "L");
        locked.lock();
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> locked.activate(HASH));
        User disabled = User.inviteCustomer(UUID.randomUUID(), "disabled@example.test", "D");
        disabled.disable();
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> disabled.activate(HASH));
    }

    private static void expect(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
