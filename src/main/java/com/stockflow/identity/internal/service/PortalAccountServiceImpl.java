package com.stockflow.identity.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Roles;
import com.stockflow.identity.api.InviteCustomerAccountCommand;
import com.stockflow.identity.api.PortalAccountService;
import com.stockflow.identity.api.PortalInvitationLink;
import com.stockflow.identity.api.PortalInvitationStatus;
import com.stockflow.identity.internal.domain.PasswordPolicy;
import com.stockflow.identity.internal.domain.PortalInvitation;
import com.stockflow.identity.internal.domain.PortalInvitationRepository;
import com.stockflow.identity.internal.domain.PortalInvitationToken;
import com.stockflow.identity.internal.domain.User;
import com.stockflow.identity.internal.domain.UserId;
import com.stockflow.identity.internal.domain.UserRepository;
import com.stockflow.identity.internal.entity.UserRoleJpaEntity;
import com.stockflow.identity.internal.repository.RoleJpaRepository;
import com.stockflow.identity.internal.repository.UserRoleJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Invited customer accounts: issue, replace, redeem (SCRUM-450).
 *
 * <p>Every change to one account's invitations first takes that account's row lock, so a resend and
 * an activation of the same account are serialised and one of them sees the other's result. The
 * account has no password ({@code password_hash} null) until the contact redeems an invitation.</p>
 */
@Service
@Transactional
class PortalAccountServiceImpl implements PortalAccountService {

    private final UserRepository users;
    private final PortalInvitationRepository invitations;
    private final UserRoleJpaRepository userRoles;
    private final RoleJpaRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration lifetime;
    private final Duration resendCooldown;

    PortalAccountServiceImpl(UserRepository users, PortalInvitationRepository invitations,
                             UserRoleJpaRepository userRoles, RoleJpaRepository roles,
                             PasswordEncoder passwordEncoder, Clock clock,
                             @Value("${stockflow.identity.portal-invitation.lifetime:PT72H}") Duration lifetime,
                             @Value("${stockflow.identity.portal-invitation.resend-cooldown:PT1M}") Duration resendCooldown) {
        if (lifetime.compareTo(Duration.ofMinutes(1)) < 0 || lifetime.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException(
                    "stockflow.identity.portal-invitation.lifetime must be between 1 minute and 30 days");
        }
        if (resendCooldown.isNegative() || resendCooldown.compareTo(lifetime) >= 0) {
            throw new IllegalArgumentException(
                    "stockflow.identity.portal-invitation.resend-cooldown must be shorter than the lifetime");
        }
        this.users = users;
        this.invitations = invitations;
        this.userRoles = userRoles;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.lifetime = lifetime;
        this.resendCooldown = resendCooldown;
    }

    @Override
    public PortalInvitationLink invite(InviteCustomerAccountCommand command) {
        String email = command.email().trim().toLowerCase(Locale.ROOT);
        // Username and e-mail are both the address here, and both are unique case-insensitively.
        // The database indexes are the backstop for two invitations racing past this check.
        if (users.findByEmail(email).isPresent() || users.findByUsername(email).isPresent()) {
            throw new BusinessException(ErrorCode.USER_EMAIL_ALREADY_EXISTS,
                    "An account already signs in with this e-mail");
        }
        UUID userId = Identifiers.newId();
        User invited = users.save(User.inviteCustomer(userId, email, command.fullName()));
        var customerRole = roles.findByCode(Roles.CUSTOMER).orElseThrow(() ->
                new IllegalStateException("The CUSTOMER role is seeded by migration and must exist"));
        userRoles.save(new UserRoleJpaEntity(Identifiers.newId(), userId, customerRole.getId()));
        return issue(invited, command.issuedBy());
    }

    @Override
    public PortalInvitationLink reissue(UUID userId, UUID issuedBy) {
        User user = users.findByIdForUpdate(new UserId(userId)).orElseThrow(() ->
                new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.awaitingActivation()) {
            throw new BusinessException(ErrorCode.PORTAL_ACCOUNT_ALREADY_ACTIVE,
                    "Account %s has already been activated".formatted(userId));
        }
        Instant now = clock.instant();
        invitations.findOpenByUserId(userId).ifPresent(open -> {
            open.revokeForResend(now, resendCooldown);
            invitations.save(open);
        });
        return issue(user, issuedBy);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> accountNamedBy(String token) {
        return PortalInvitationToken.parse(token).map(PortalInvitationToken::userId);
    }

    @Override
    public UUID activate(String token, String password) {
        PortalInvitationToken parsed = PortalInvitationToken.parse(token).orElseThrow(() ->
                new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID));
        User user = users.findByIdForUpdate(new UserId(parsed.userId())).orElseThrow(() ->
                new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID));
        Instant now = clock.instant();
        // Judge the link before the password, so a weak password never tells anyone a link is live;
        // and refuse a weak password without consuming the link, so the contact can try again.
        PortalInvitation open = invitations.findOpenByUserId(parsed.userId())
                .filter(found -> found.redeemableBy(parsed, now))
                .orElseThrow(() -> new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID));
        PasswordPolicy.violation(password).ifPresent(reason -> {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason);
        });
        open.consume(parsed, now);
        user.activate(passwordEncoder.encode(password));
        invitations.save(open);
        users.save(user);
        return user.id().value();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PortalInvitationStatus> invitation(UUID userId) {
        return users.findById(new UserId(userId)).filter(User::awaitingActivation)
                .flatMap(user -> invitations.findOpenByUserId(userId))
                .map(open -> new PortalInvitationStatus(open.issuedAt(), open.expiresAt(),
                        open.delivery() == null ? null : open.delivery().name()));
    }

    @Override
    public void recordDelivery(UUID invitationId, boolean delivered) {
        invitations.findById(invitationId).ifPresent(invitation -> {
            invitation.recordDelivery(delivered ? PortalInvitation.Delivery.SENT : PortalInvitation.Delivery.FAILED,
                    clock.instant());
            invitations.save(invitation);
        });
    }

    private PortalInvitationLink issue(User user, UUID issuedBy) {
        PortalInvitationToken token = PortalInvitationToken.generate(user.id().value());
        PortalInvitation invitation = invitations.save(
                PortalInvitation.issue(Identifiers.newId(), token, issuedBy, clock.instant(), lifetime));
        return new PortalInvitationLink(invitation.id(), user.id().value(), user.email(), user.fullName(),
                token.value(), invitation.issuedAt(), invitation.expiresAt());
    }
}
