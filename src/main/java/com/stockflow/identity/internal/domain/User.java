package com.stockflow.identity.internal.domain;

import com.stockflow.common.domain.AggregateRoot;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>Aggregate root of the identity module: one staff/customer account.</b>
 *
 * <p>SCRUM-378 (WBS 3.19.7) gives this account real behaviour: {@link #signIn} is the one
 * invariant worth protecting here — a {@code LOCKED}/{@code DISABLED} account must never be
 * allowed to authenticate, no matter what else changes about the login flow later. Password
 * verification itself stays in {@code IdentityServiceImpl}: {@code PasswordEncoder} is a framework
 * type and {@code ArchitectureTest.domainDoesNotDependOnFrameworks} keeps it out of this class, so
 * this aggregate only ever sees a hash to compare against, never performs the comparison.</p>
 *
 * <p>No Spring, no JPA, no annotations. {@code username}/{@code email} are immutable once created
 * (this story does not add a "change username" use case); every other field is set at
 * reconstruction and mutated only through {@link #signIn}.</p>
 */
public final class User extends AggregateRoot {

    private final UserId id;
    private final String username;
    private final String email;
    private String passwordHash;
    private final String fullName;
    private UserStatus status;
    private Instant lastLoginAt;
    private final long version;
    private final Instant createdAt;
    private final String createdBy;

    public User(UserId id, String username, String email, String passwordHash, String fullName,
               UserStatus status, Instant lastLoginAt, long version, Instant createdAt,
               String createdBy) {
        this.id = Objects.requireNonNull(id, "id");
        this.username = requireNonBlank(username, "username");
        this.email = requireNonBlank(email, "email");
        this.passwordHash = requireNonBlank(passwordHash, "passwordHash");
        this.fullName = fullName;
        this.status = Objects.requireNonNull(status, "status");
        this.lastLoginAt = lastLoginAt;
        this.version = version;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public static User register(UUID id, String email, String passwordHash, String fullName) {
        String normalisedEmail = requireNonBlank(email, "email").trim().toLowerCase(java.util.Locale.ROOT);
        return new User(new UserId(id), normalisedEmail, normalisedEmail, passwordHash, fullName,
                UserStatus.ACTIVE, null, 0L, null, null);
    }

    /**
     * Records a successful authentication. The caller has already verified the password against
     * {@link #passwordHash()} — this only enforces the account-status invariant and stamps
     * {@code lastLoginAt}.
     *
     * @throws AccountNotActiveException if the account is {@code LOCKED} or {@code DISABLED}
     */
    public void signIn(Instant now) {
        if (status != UserStatus.ACTIVE) {
            throw new AccountNotActiveException(id, status);
        }
        this.lastLoginAt = Objects.requireNonNull(now, "now");
    }

    /** Replaces the credential. Takes an already-hashed value: this class never sees a raw password. */
    public void changePasswordHash(String newHash) {
        this.passwordHash = requireNonBlank(newHash, "passwordHash");
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
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public String createdBy() { return createdBy; }
}
