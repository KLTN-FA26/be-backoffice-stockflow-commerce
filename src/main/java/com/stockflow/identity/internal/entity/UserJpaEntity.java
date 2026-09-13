package com.stockflow.identity.internal.entity;

import com.stockflow.identity.internal.domain.UserStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a user (table {@code identity.app_user} — USER is a reserved word). STARTER ENTITY.
 *
 * <p>{@code passwordHash} only ever holds a hash; a raw password is never persisted or logged.</p>
 */
@Entity
@Table(name = "app_user", schema = "identity",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_app_user_username", columnNames = "username"),
                @UniqueConstraint(name = "uk_app_user_email", columnNames = "email")})
public class UserJpaEntity extends BaseEntity {

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(name = "full_name", length = 200)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected UserJpaEntity() {
    }

    public UserJpaEntity(UUID id, String username, String email, String passwordHash,
                         String fullName, UserStatus status, Instant lastLoginAt) {
        super(id);
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.status = status;
        this.lastLoginAt = lastLoginAt;
    }

    /** The only mutation this login flow needs; other fields have no write use case yet. */
    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public UserStatus getStatus() { return status; }
    public Instant getLastLoginAt() { return lastLoginAt; }
}
