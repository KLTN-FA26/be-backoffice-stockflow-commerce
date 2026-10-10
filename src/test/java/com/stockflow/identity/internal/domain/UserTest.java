package com.stockflow.identity.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Duration;
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
                "Jane Doe", status, null, false, 0, null, 0L, Instant.parse("2026-01-01T00:00:00Z"), "seed");
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

    // ---- SCRUM-454 / 456 -------------------------------------------------------------------

    private static final Duration LOCK = Duration.ofMinutes(15);

    @Test
    @DisplayName("the fifth wrong password in a row locks the account for a while, then it signs in again")
    void wrongPasswordsLockTemporarily() {
        User user = userWith(UserStatus.ACTIVE);
        for (int i = 0; i < 4; i++) {
            assertThat(user.recordFailedSignIn(NOW, 5, LOCK)).isFalse();
        }
        assertThat(user.recordFailedSignIn(NOW, 5, LOCK)).isTrue();

        assertThat(user.lockedUntil()).isEqualTo(NOW.plus(LOCK));
        assertThatThrownBy(() -> user.signIn(NOW.plusSeconds(60)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ACCOUNT_TEMPORARILY_LOCKED);

        user.signIn(NOW.plus(LOCK).plusSeconds(1));
        assertThat(user.lockedUntil()).isNull();
        assertThat(user.failedLoginCount()).isZero();
    }

    @Test
    @DisplayName("wrong passwords during a lock do not extend it")
    void attemptsDuringALockAreNotCounted() {
        User user = userWith(UserStatus.ACTIVE);
        for (int i = 0; i < 5; i++) {
            user.recordFailedSignIn(NOW, 5, LOCK);
        }
        for (int i = 0; i < 20; i++) {
            assertThat(user.recordFailedSignIn(NOW.plusSeconds(30), 5, LOCK)).isFalse();
        }
        assertThat(user.lockedUntil()).isEqualTo(NOW.plus(LOCK));
        assertThat(user.failedLoginCount()).isZero();
    }

    @Test
    @DisplayName("a good sign-in forgets earlier wrong passwords")
    void goodSignInResetsTheCount() {
        User user = userWith(UserStatus.ACTIVE);
        user.recordFailedSignIn(NOW, 5, LOCK);
        user.recordFailedSignIn(NOW, 5, LOCK);

        user.signIn(NOW);

        assertThat(user.failedLoginCount()).isZero();
    }

    @Test
    @DisplayName("lock, unlock, disable and enable follow the allowed transitions")
    void statusTransitions() {
        User user = userWith(UserStatus.ACTIVE);
        user.lock();
        assertThat(user.status()).isEqualTo(UserStatus.LOCKED);
        user.lock();
        assertThat(user.status()).isEqualTo(UserStatus.LOCKED);
        assertThatThrownBy(user::enable).isInstanceOf(BusinessException.class);
        user.unlock();
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);

        user.disable();
        assertThat(user.status()).isEqualTo(UserStatus.DISABLED);
        assertThatThrownBy(user::lock).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_USER_STATUS_TRANSITION);
        assertThatThrownBy(user::unlock).isInstanceOf(BusinessException.class);
        user.enable();
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("unlock also ends a lock after wrong passwords")
    void unlockEndsATemporaryLock() {
        User user = userWith(UserStatus.ACTIVE);
        for (int i = 0; i < 5; i++) {
            user.recordFailedSignIn(NOW, 5, LOCK);
        }
        user.unlock();

        user.signIn(NOW.plusSeconds(1));
        assertThat(user.lastLoginAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("an administrator's reset makes the password temporary; the user's own change makes it final")
    void temporaryPasswordFlag() {
        User user = User.createStaff(UUID.randomUUID(), " lan.nguyen ", " Lan@Example.com ", "{bcrypt}a", "Lan", true);
        assertThat(user.username()).isEqualTo("lan.nguyen");
        assertThat(user.email()).isEqualTo("lan@example.com");
        assertThat(user.mustChangePassword()).isTrue();

        user.changePasswordHash("{bcrypt}b");
        assertThat(user.mustChangePassword()).isFalse();

        user.resetPassword("{bcrypt}c");
        assertThat(user.mustChangePassword()).isTrue();
        assertThat(user.passwordHash()).isEqualTo("{bcrypt}c");
    }

    @Test
    @DisplayName("editing the profile normalises the e-mail and never touches the username")
    void updateProfile() {
        User user = userWith(UserStatus.ACTIVE);

        user.updateProfile(" New@Example.COM ", "  Jane D.  ");

        assertThat(user.email()).isEqualTo("new@example.com");
        assertThat(user.fullName()).isEqualTo("Jane D.");
        assertThat(user.username()).isEqualTo("jane.doe");
    }
}
