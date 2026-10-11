package com.stockflow.identity.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>Aggregate root of the identity module: one staff/customer account.</b>
 *
 * <p>Two invariants live here. A {@code LOCKED}/{@code DISABLED} account never authenticates
 * ({@link #signIn}), and an account locked out by too many wrong passwords stays out until the lock
 * ends ({@link #recordFailedSignIn}). Password verification itself stays in
 * {@code IdentityServiceImpl}: {@code PasswordEncoder} is a framework type and
 * {@code ArchitectureTest.domainDoesNotDependOnFrameworks} keeps it out of this class, so this
 * aggregate only ever sees hashes, never a raw password.</p>
 *
 * <h2>Two kinds of lock</h2>
 *
 * <ul>
 *   <li>{@code status = LOCKED}: an administrator's decision. Only {@link #unlock} ends it.</li>
 *   <li>{@code lockedUntil}: the automatic lock after too many wrong passwords (SCRUM-456). It ends
 *       on its own; {@link #unlock} also ends it early.</li>
 * </ul>
 *
 * <p>{@code username} is immutable: it is the sign-in name, and the audit trail records actors by
 * it. Everything else changes only through the methods below.</p>
 */
public final class User extends AggregateRoot {

    private final UserId id;
    private final String username;
    private String email;
    private String passwordHash;
    private String fullName;
    private UserStatus status;
    private Instant lastLoginAt;
    private boolean mustChangePassword;
    private int failedLoginCount;
    private Instant lockedUntil;
    private final long version;
    private final Instant createdAt;
    private final String createdBy;

    public User(UserId id, String username, String email, String passwordHash, String fullName,
                UserStatus status, Instant lastLoginAt, boolean mustChangePassword, int failedLoginCount,
                Instant lockedUntil, long version, Instant createdAt, String createdBy) {
        this.id = Objects.requireNonNull(id, "id");
        this.username = requireNonBlank(username, "username");
        this.email = requireNonBlank(email, "email");
        this.passwordHash = requireNonBlank(passwordHash, "passwordHash");
        this.fullName = fullName;
        this.status = Objects.requireNonNull(status, "status");
        this.lastLoginAt = lastLoginAt;
        this.mustChangePassword = mustChangePassword;
        this.failedLoginCount = Math.max(0, failedLoginCount);
        this.lockedUntil = lockedUntil;
        this.version = version;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    /** A self-registered customer: signs in with the e-mail address, chose the password. */
    public static User register(UUID id, String email, String passwordHash, String fullName) {
        String normalisedEmail = normaliseEmail(email);
        return new User(new UserId(id), normalisedEmail, normalisedEmail, passwordHash, fullName,
                UserStatus.ACTIVE, null, false, 0, null, 0L, null, null);
    }

    /**
     * A staff account created by an administrator (SCRUM-454).
     *
     * @param temporaryPassword true when the administrator chose or generated the password, so the
     *                          user must replace it at the first opportunity
     */
    public static User createStaff(UUID id, String username, String email, String passwordHash,
                                   String fullName, boolean temporaryPassword) {
        return new User(new UserId(id), requireNonBlank(username, "username").trim(), normaliseEmail(email),
                passwordHash, fullName, UserStatus.ACTIVE, null, temporaryPassword, 0, null, 0L, null, null);
    }

    /**
     * A customer contact that Sales onboarded (SCRUM-450): signs in with the e-mail address, but has
     * no password until they redeem the invitation. Until then the account cannot authenticate -
     * nobody, including Sales, knows a password for it.
     */
    public static User inviteCustomer(UUID id, String email, String fullName, PortalInvitation invitation) {
        String normalisedEmail = normaliseEmail(email);
        return new User(new UserId(id), normalisedEmail, normalisedEmail,
                Objects.requireNonNull(invitation, "invitation").toCredential(), fullName,
                UserStatus.ACTIVE, null, false, 0, null, 0L, null, null);
    }

    /** The open invitation, if this account has never been activated. */
    public Optional<PortalInvitation> pendingInvitation() {
        return PortalInvitation.fromCredential(passwordHash);
    }

    public boolean awaitingActivation() {
        return passwordHash.startsWith(PortalInvitation.PREFIX);
    }

    /**
     * Replaces the invitation, which is what makes the previous link stop working. An expired
     * invitation can be replaced at once; an open one only after {@code cooldown}.
     *
     * @throws BusinessException {@code PORTAL_ACCOUNT_ALREADY_ACTIVE} once the account has a password;
     *         {@code RATE_LIMITED} within the cooldown
     */
    public void reissueInvitation(PortalInvitation next, Duration cooldown) {
        PortalInvitation current = pendingInvitation().orElseThrow(() ->
                new BusinessException(ErrorCode.PORTAL_ACCOUNT_ALREADY_ACTIVE,
                        "Account %s has already been activated".formatted(id)));
        if (!current.expiredAt(next.issuedAt())) current.requireReplaceableAt(next.issuedAt(), cooldown);
        this.passwordHash = next.toCredential();
    }

    /**
     * Redeems the invitation: the contact chose a password. Writing the hash is what consumes the
     * link, so a second redemption - concurrent or later - finds no invitation to match. Every
     * failure looks the same to the caller, so a link cannot be probed for why it failed.
     *
     * @throws BusinessException {@code PORTAL_INVITATION_INVALID}
     */
    public void activate(PortalInvitationToken token, String newPasswordHash, Instant now) {
        boolean redeemable = status == UserStatus.ACTIVE && token.userId().equals(id.value())
                && pendingInvitation().filter(open -> open.redeemableBy(token, now)).isPresent();
        if (!redeemable) throw new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID);
        if (requireNonBlank(newPasswordHash, "passwordHash").startsWith(PortalInvitation.PREFIX)) {
            throw new IllegalArgumentException("A password hash is required");
        }
        this.passwordHash = newPasswordHash;
        this.mustChangePassword = false;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    /**
     * Records a successful authentication. The caller has already verified the password against
     * {@link #passwordHash()} — this only enforces the account-status invariant, stamps
     * {@code lastLoginAt} and forgets earlier wrong passwords.
     *
     * @throws AccountNotActiveException if the account is {@code LOCKED} or {@code DISABLED}
     * @throws BusinessException {@code ACCOUNT_TEMPORARILY_LOCKED} while a lockout is running
     */
    public void signIn(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != UserStatus.ACTIVE) {
            throw new AccountNotActiveException(id, status);
        }
        if (isTemporarilyLocked(now)) {
            throw new BusinessException(ErrorCode.ACCOUNT_TEMPORARILY_LOCKED,
                    "Account %s is locked until %s after too many wrong passwords".formatted(id, lockedUntil));
        }
        this.lastLoginAt = now;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public boolean isTemporarilyLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Counts a wrong password. The {@code maxAttempts}-th in a row locks the account for
     * {@code lockFor} and starts the count again. Attempts made while a lock runs are not counted:
     * they cannot succeed anyway, and counting them would let anyone extend a lock forever.
     *
     * @return true when this attempt started a lock
     */
    public boolean recordFailedSignIn(Instant now, int maxAttempts, Duration lockFor) {
        if (isTemporarilyLocked(now)) {
            return false;
        }
        failedLoginCount++;
        if (failedLoginCount >= maxAttempts) {
            failedLoginCount = 0;
            lockedUntil = now.plus(lockFor);
            return true;
        }
        return false;
    }

    /** The user replaced their own password: it is no longer a temporary one. Takes a hash. */
    public void changePasswordHash(String newHash) {
        this.passwordHash = requireNonBlank(newHash, "passwordHash");
        this.mustChangePassword = false;
    }

    /**
     * An administrator set a new password. It is temporary by definition — the administrator knows
     * it — and resetting is also how someone locked out by wrong passwords gets back in.
     */
    public void resetPassword(String newHash) {
        this.passwordHash = requireNonBlank(newHash, "passwordHash");
        this.mustChangePassword = true;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public void updateProfile(String email, String fullName) {
        if (email != null) {
            this.email = normaliseEmail(email);
        }
        if (fullName != null) {
            this.fullName = fullName.isBlank() ? null : fullName.trim();
        }
    }

    /** ACTIVE → LOCKED. Locking a locked account is a no-op; a disabled one has to be enabled first. */
    public void lock() {
        switch (status) {
            case ACTIVE -> status = UserStatus.LOCKED;
            case LOCKED -> { }
            case DISABLED -> throw invalidTransition(UserStatus.LOCKED);
        }
    }

    /** LOCKED → ACTIVE, and ends a running wrong-password lock either way. */
    public void unlock() {
        switch (status) {
            case LOCKED, ACTIVE -> {
                status = UserStatus.ACTIVE;
                failedLoginCount = 0;
                lockedUntil = null;
            }
            case DISABLED -> throw invalidTransition(UserStatus.ACTIVE);
        }
    }

    /** Any status → DISABLED: the account is retired but kept, so its history keeps a name. */
    public void disable() {
        status = UserStatus.DISABLED;
    }

    /** DISABLED → ACTIVE. A locked account is unlocked, not enabled: two decisions, two actions. */
    public void enable() {
        switch (status) {
            case DISABLED -> {
                status = UserStatus.ACTIVE;
                failedLoginCount = 0;
                lockedUntil = null;
            }
            case ACTIVE -> { }
            case LOCKED -> throw invalidTransition(UserStatus.ACTIVE);
        }
    }

    private BusinessException invalidTransition(UserStatus target) {
        return new BusinessException(ErrorCode.INVALID_USER_STATUS_TRANSITION,
                "Account %s cannot go from %s to %s".formatted(id, status, target));
    }

    private static String normaliseEmail(String email) {
        return requireNonBlank(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public UserId id() { return id; }
    public String username() { return username; }
    public String email() { return email; }
    public String passwordHash() { return passwordHash; }
    public String fullName() { return fullName; }
    public UserStatus status() { return status; }
    public Instant lastLoginAt() { return lastLoginAt; }
    public boolean mustChangePassword() { return mustChangePassword; }
    public int failedLoginCount() { return failedLoginCount; }
    public Instant lockedUntil() { return lockedUntil; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public String createdBy() { return createdBy; }
}
