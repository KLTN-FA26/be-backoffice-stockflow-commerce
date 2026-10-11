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

/** Invitation rules on the account. Row locking and races are proved against PostgreSQL in B2bOnboardingIntegrationTest. */
class PortalInvitationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final Duration LIFETIME = Duration.ofHours(72);
    private static final Duration COOLDOWN = Duration.ofMinutes(1);
    private static final String HASH = "{bcrypt}$2a$10$abcdefghijklmnopqrstuuJ0123456789012345678901234567890";

    private final UUID userId = UUID.randomUUID();

    private User invited(PortalInvitationToken token) {
        return User.inviteCustomer(userId, " Buyer@Example.TEST ", "Buyer",
                PortalInvitation.issue(token, NOW, LIFETIME));
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
    void anInvitedAccountStoresOnlyTheDigestAndCannotBeMatchedAsAPassword() {
        var token = PortalInvitationToken.generate(userId);
        User user = invited(token);
        assertThat(user.awaitingActivation()).isTrue();
        assertThat(user.username()).isEqualTo("buyer@example.test");
        assertThat(user.passwordHash()).startsWith("{portal-invitation}")
                .contains(token.digest()).doesNotContain(token.value());
        assertThat(user.pendingInvitation().orElseThrow().expiresAt()).isEqualTo(NOW.plus(LIFETIME));
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void activationWorksOnceWithinTheLifetimeAndClearsTheInvitation() {
        var token = PortalInvitationToken.generate(userId);
        User user = invited(token);
        user.activate(token, HASH, NOW.plusSeconds(10));
        assertThat(user.awaitingActivation()).isFalse();
        assertThat(user.pendingInvitation()).isEmpty();
        assertThat(user.passwordHash()).isEqualTo(HASH);
        assertThat(user.mustChangePassword()).isFalse();
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> user.activate(token, HASH, NOW.plusSeconds(11)));
    }

    @Test
    void expiryIsExclusiveAndAWrongOrForeignTokenFailsAlike() {
        var token = PortalInvitationToken.generate(userId);
        for (Instant at : new Instant[]{NOW.minusSeconds(1), NOW.plus(LIFETIME), NOW.plus(LIFETIME).plusSeconds(1)}) {
            User user = invited(token);
            expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> user.activate(token, HASH, at));
            assertThat(user.awaitingActivation()).isTrue();
        }
        User user = invited(token);
        expect(ErrorCode.PORTAL_INVITATION_INVALID,
                () -> user.activate(PortalInvitationToken.generate(userId), HASH, NOW.plusSeconds(1)));
        expect(ErrorCode.PORTAL_INVITATION_INVALID,
                () -> user.activate(PortalInvitationToken.generate(UUID.randomUUID()), HASH, NOW.plusSeconds(1)));
        user.activate(token, HASH, NOW.plus(LIFETIME).minusSeconds(1));
    }

    @Test
    void aLockedOrDisabledAccountCannotBeActivated() {
        var token = PortalInvitationToken.generate(userId);
        User locked = invited(token);
        locked.lock();
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> locked.activate(token, HASH, NOW.plusSeconds(1)));
        User disabled = invited(token);
        disabled.disable();
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> disabled.activate(token, HASH, NOW.plusSeconds(1)));
    }

    @Test
    void resendRespectsTheCooldownThenKillsThePreviousLink() {
        var first = PortalInvitationToken.generate(userId);
        User user = invited(first);
        var tooSoon = PortalInvitationToken.generate(userId);
        expect(ErrorCode.RATE_LIMITED, () -> user.reissueInvitation(
                PortalInvitation.issue(tooSoon, NOW.plusSeconds(30), LIFETIME), COOLDOWN));

        var second = PortalInvitationToken.generate(userId);
        Instant later = NOW.plus(COOLDOWN);
        user.reissueInvitation(PortalInvitation.issue(second, later, LIFETIME), COOLDOWN);
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> user.activate(first, HASH, later.plusSeconds(1)));
        expect(ErrorCode.PORTAL_INVITATION_INVALID, () -> user.activate(tooSoon, HASH, later.plusSeconds(1)));
        user.activate(second, HASH, later.plusSeconds(1));
    }

    @Test
    void anExpiredInvitationIsReplacedAtOnceButAnActivatedAccountNeverIs() {
        var first = PortalInvitationToken.generate(userId);
        User user = invited(first);
        var expiredLong = Duration.ofHours(72);
        Instant afterExpiry = NOW.plus(expiredLong);
        var second = PortalInvitationToken.generate(userId);
        user.reissueInvitation(PortalInvitation.issue(second, afterExpiry, LIFETIME), Duration.ofDays(10));
        user.activate(second, HASH, afterExpiry.plusSeconds(1));

        expect(ErrorCode.PORTAL_ACCOUNT_ALREADY_ACTIVE, () -> user.reissueInvitation(
                PortalInvitation.issue(PortalInvitationToken.generate(userId), afterExpiry.plus(COOLDOWN), LIFETIME),
                COOLDOWN));
        assertThat(user.passwordHash()).isEqualTo(HASH);
    }

    @Test
    void anOrdinaryPasswordHashIsNoInvitationAndACorruptOneFailsLoudly() {
        assertThat(PortalInvitation.fromCredential(HASH)).isEmpty();
        assertThat(PortalInvitation.fromCredential(null)).isEmpty();
        assertThatThrownBy(() -> PortalInvitation.fromCredential("{portal-invitation}garbage"))
                .isInstanceOf(IllegalStateException.class);
        var invitation = PortalInvitation.issue(PortalInvitationToken.generate(userId), NOW, LIFETIME);
        var decoded = PortalInvitation.fromCredential(invitation.toCredential()).orElseThrow();
        assertThat(decoded.issuedAt()).isEqualTo(invitation.issuedAt());
        assertThat(decoded.expiresAt()).isEqualTo(invitation.expiresAt());
        assertThat(invitation.toString()).doesNotContain(invitation.toCredential());
        assertThat(invitation.toCredential().length()).isLessThanOrEqualTo(200); // app_user.password_hash
    }

    private static void expect(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
