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
 * Invited customer accounts: issue, replace, redeem. The invitation itself is a credential on the
 * account ({@link PortalInvitation} explains why), so every transition is a write to one locked row.
 */
@Service
@Transactional
class PortalAccountServiceImpl implements PortalAccountService {

    private final UserRepository users;
    private final UserRoleJpaRepository userRoles;
    private final RoleJpaRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration lifetime;
    private final Duration resendCooldown;

    PortalAccountServiceImpl(UserRepository users, UserRoleJpaRepository userRoles, RoleJpaRepository roles,
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
        PortalInvitationToken token = PortalInvitationToken.generate(userId);
        PortalInvitation invitation = PortalInvitation.issue(token, clock.instant(), lifetime);
        User invited = users.save(User.inviteCustomer(userId, email, command.fullName(), invitation));
        var customerRole = roles.findByCode(Roles.CUSTOMER).orElseThrow(() ->
                new IllegalStateException("The CUSTOMER role is seeded by migration and must exist"));
        userRoles.save(new UserRoleJpaEntity(Identifiers.newId(), userId, customerRole.getId()));
        return link(invited, token, invitation);
    }

    @Override
    public PortalInvitationLink reissue(UUID userId) {
        User user = users.findByIdForUpdate(new UserId(userId)).orElseThrow(() ->
                new BusinessException(ErrorCode.USER_NOT_FOUND));
        PortalInvitationToken token = PortalInvitationToken.generate(userId);
        PortalInvitation invitation = PortalInvitation.issue(token, clock.instant(), lifetime);
        user.reissueInvitation(invitation, resendCooldown);
        return link(users.save(user), token, invitation);
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
        if (user.pendingInvitation().filter(open -> open.redeemableBy(parsed, now)).isEmpty()) {
            throw new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID);
        }
        PasswordPolicy.violation(password).ifPresent(reason -> {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason);
        });
        user.activate(parsed, passwordEncoder.encode(password), now);
        users.save(user);
        return user.id().value();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PortalInvitationStatus> invitation(UUID userId) {
        return users.findById(new UserId(userId)).flatMap(User::pendingInvitation)
                .map(open -> new PortalInvitationStatus(open.issuedAt(), open.expiresAt()));
    }

    private static PortalInvitationLink link(User user, PortalInvitationToken token, PortalInvitation invitation) {
        return new PortalInvitationLink(user.id().value(), user.email(), user.fullName(), token.value(),
                invitation.issuedAt(), invitation.expiresAt());
    }
}
