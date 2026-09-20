package com.stockflow.identity.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link User#signIn} (SCRUM-378/WBS 3.19.7.1) — no Spring, no database, same style
 * as {@code ProductTest}: password verification itself stays out of the aggregate (see the class
 * javadoc), so all that is tested here is the account-status invariant.
 */
class UserTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private static User userWith(UserStatus status) {
        return new User(UserId.newId(), "jane.doe", "jane@example.com", "{bcrypt}$2b$...",
                "Jane Doe", status, null, 0L, Instant.parse("2026-01-01T00:00:00Z"), "seed");
    }

    @Test
    @DisplayName("an ACTIVE account signs in and records lastLoginAt")
    void activeAccountSignsIn() {
        User user = userWith(UserStatus.ACTIVE);

        user.signIn(NOW);

        assertThat(user.lastLoginAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a LOCKED account cannot sign in")
    void lockedAccountRejected() {
        User user = userWith(UserStatus.LOCKED);

        assertThatThrownBy(() -> user.signIn(NOW)).isInstanceOf(AccountNotActiveException.class);
        assertThat(user.lastLoginAt()).isNull();
    }

    @Test
    @DisplayName("a DISABLED account cannot sign in")
    void disabledAccountRejected() {
        User user = userWith(UserStatus.DISABLED);

        assertThatThrownBy(() -> user.signIn(NOW)).isInstanceOf(AccountNotActiveException.class);
        assertThat(user.lastLoginAt()).isNull();
    }

    @Test
    void customerRegistrationNormalisesEmailAsTheLoginName() {
        User user = User.register(UUID.randomUUID(), " Customer@Example.COM ", "hash", "Customer");

        assertThat(user.username()).isEqualTo("customer@example.com");
        assertThat(user.email()).isEqualTo("customer@example.com");
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("changing the password replaces the stored hash and refuses a blank one")
    void changePasswordHash() {
        User user = userWith(UserStatus.ACTIVE);

        user.changePasswordHash("{bcrypt}new");

        assertThat(user.passwordHash()).isEqualTo("{bcrypt}new");
        assertThatThrownBy(() -> user.changePasswordHash(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(user.passwordHash()).isEqualTo("{bcrypt}new");
    }
}
