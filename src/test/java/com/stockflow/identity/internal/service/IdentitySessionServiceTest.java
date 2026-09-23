package com.stockflow.identity.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.PermissionCatalog;
import com.stockflow.common.security.SessionValidator;
import com.stockflow.identity.api.ChangePasswordCommand;
import com.stockflow.identity.api.LoginCommand;
import com.stockflow.identity.internal.domain.SessionEndReason;
import com.stockflow.identity.internal.domain.User;
import com.stockflow.identity.internal.domain.UserId;
import com.stockflow.identity.internal.domain.UserRepository;
import com.stockflow.identity.internal.domain.UserStatus;
import com.stockflow.identity.internal.entity.RoleJpaEntity;
import com.stockflow.identity.internal.entity.UserRoleJpaEntity;
import com.stockflow.identity.internal.entity.UserSessionJpaEntity;
import com.stockflow.identity.internal.repository.PermissionJpaRepository;
import com.stockflow.identity.internal.repository.RoleJpaRepository;
import com.stockflow.identity.internal.repository.RolePermissionJpaRepository;
import com.stockflow.identity.internal.repository.UserRoleJpaRepository;
import com.stockflow.identity.internal.repository.UserSessionJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Session and password behaviour of {@link IdentityServiceImpl}, with the persistence mocked. */
class IdentitySessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");
    private static final Duration TTL = Duration.ofHours(8);

    private final RoleJpaRepository roles = mock(RoleJpaRepository.class);
    private final UserRoleJpaRepository userRoles = mock(UserRoleJpaRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final JwtEncoder jwtEncoder = mock(JwtEncoder.class);
    private final UserSessionJpaRepository sessions = mock(UserSessionJpaRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private IdentityServiceImpl service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(encoder.encode(any())).thenReturn("{bcrypt}dummy");
        service = new IdentityServiceImpl(roles, mock(PermissionJpaRepository.class),
                mock(RolePermissionJpaRepository.class), userRoles, users, mock(PermissionCatalog.class),
                encoder, jwtEncoder, clock, sessions, TTL);
    }

    private User user(UserStatus status) {
        return new User(new UserId(userId), "jane", "jane@example.com", "{bcrypt}hash", "Jane", status,
                null, 0, NOW, "seed");
    }

    @Test
    void loginStoresASessionAndPutsItsIdInTheToken() {
        when(users.findByUsername("jane")).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(encoder.matches("Passw0rd!x", "{bcrypt}hash")).thenReturn(true);
        when(users.save(any())).thenAnswer(call -> call.getArgument(0));
        when(sessions.save(any())).thenAnswer(call -> call.getArgument(0));
        when(jwtEncoder.encode(any())).thenReturn(
                Jwt.withTokenValue("signed").header("alg", "RS256").subject("x").build());

        var token = service.login(new LoginCommand("jane", "Passw0rd!x", "10.0.0.7", "Firefox"));

        var stored = ArgumentCaptor.forClass(UserSessionJpaEntity.class);
        verify(sessions).save(stored.capture());
        UserSessionJpaEntity session = stored.getValue();
        assertThat(session.getUserId()).isEqualTo(userId);
        assertThat(session.getIssuedAt()).isEqualTo(NOW);
        assertThat(session.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(session.getClientAddress()).isEqualTo("10.0.0.7");
        assertThat(session.getUserAgent()).isEqualTo("Firefox");

        var params = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(params.capture());
        var claims = params.getValue().getClaims();
        assertThat(claims.getClaimAsString(SessionValidator.SESSION_CLAIM)).isEqualTo(session.getId().toString());
        assertThat(claims.getId()).isEqualTo(session.getId().toString());
        assertThat(claims.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(token.expiresInSeconds()).isEqualTo(TTL.toSeconds());
    }

    @Test
    void aWrongPasswordCreatesNoSession() {
        when(users.findByUsername("jane")).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(encoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginCommand("jane", "nope")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(sessions);
    }

    @Test
    void logoutEndsTheCallersOwnSessionOnly() {
        UUID sid = UUID.randomUUID();
        var own = new UserSessionJpaEntity(sid, userId, NOW.minusSeconds(60), NOW.plus(TTL), null, null);
        when(sessions.findByIdAndUserId(sid, userId)).thenReturn(Optional.of(own));

        service.logout(userId, sid);

        assertThat(own.getRevokedAt()).isEqualTo(NOW);
        assertThat(own.getRevokedReason()).isEqualTo(SessionEndReason.LOGOUT);
        when(sessions.findByIdAndUserId(sid, UUID.randomUUID())).thenReturn(Optional.empty());
        service.logout(UUID.randomUUID(), sid);
    }

    @Test
    void logoutOfATokenWithoutASessionIsANoOp() {
        service.logout(userId, null);

        verifyNoInteractions(sessions);
    }

    @Test
    void logoutOthersKeepsTheCurrentSession() {
        UUID keep = UUID.randomUUID();
        when(sessions.revokeLive(userId, keep, NOW, SessionEndReason.LOGOUT_OTHERS)).thenReturn(3);

        assertThat(service.logoutOtherSessions(userId, keep)).isEqualTo(3);
    }

    @Test
    void aWrongCurrentPasswordIsItsOwnErrorAndChangesNothing() {
        when(users.findById(new UserId(userId))).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(encoder.matches("wrong", "{bcrypt}hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(
                new ChangePasswordCommand(userId, UUID.randomUUID(), "wrong", "Abcdefgh12", true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_CURRENT_PASSWORD));

        verify(users, never()).save(any());
        verifyNoInteractions(sessions);
    }

    @Test
    void aWeakNewPasswordIsAValidationFailureNotACurrentPasswordError() {
        when(users.findById(new UserId(userId))).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(encoder.matches("Current-pw1", "{bcrypt}hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(
                new ChangePasswordCommand(userId, null, "Current-pw1", "short", false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(users, never()).save(any());
    }

    @Test
    void theSamePasswordIsRefused() {
        when(users.findById(new UserId(userId))).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(encoder.matches("Abcdefgh12", "{bcrypt}hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(
                new ChangePasswordCommand(userId, null, "Abcdefgh12", "Abcdefgh12", false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.PASSWORD_UNCHANGED));
    }

    @Test
    void aLockedAccountCannotChangeItsPassword() {
        when(users.findById(new UserId(userId))).thenReturn(Optional.of(user(UserStatus.LOCKED)));

        assertThatThrownBy(() -> service.changePassword(
                new ChangePasswordCommand(userId, null, "Current-pw1", "Abcdefgh12", true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
    }

    @Test
    void changingThePasswordWithoutTheTickLeavesEverySessionAlone() {
        when(users.findById(new UserId(userId))).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(encoder.matches("Current-pw1", "{bcrypt}hash")).thenReturn(true);
        when(encoder.matches("Abcdefgh12", "{bcrypt}hash")).thenReturn(false);

        int ended = service.changePassword(
                new ChangePasswordCommand(userId, UUID.randomUUID(), "Current-pw1", "Abcdefgh12", false));

        assertThat(ended).isZero();
        var saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().passwordHash()).isEqualTo("{bcrypt}dummy");
        verify(sessions, never()).revokeLive(any(), any(), any(), any());
    }

    @Test
    void theTickEndsEveryOtherSessionAndKeepsTheCurrentOne() {
        UUID current = UUID.randomUUID();
        when(users.findById(new UserId(userId))).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
        when(encoder.matches("Current-pw1", "{bcrypt}hash")).thenReturn(true);
        when(sessions.revokeLive(userId, current, NOW, SessionEndReason.PASSWORD_CHANGED)).thenReturn(2);

        int ended = service.changePassword(
                new ChangePasswordCommand(userId, current, "Current-pw1", "Abcdefgh12", true));

        assertThat(ended).isEqualTo(2);
    }

    @Test
    void grantingARoleEndsTheUsersSessionsButRegrantingDoesNot() {
        var role = new RoleJpaEntity(UUID.randomUUID(), "SALES_STAFF", "Sales", null);
        when(users.existsById(new UserId(userId))).thenReturn(true);
        when(roles.findByCode("SALES_STAFF")).thenReturn(Optional.of(role));
        when(userRoles.findByUserIdAndRoleId(userId, role.getId())).thenReturn(Optional.empty());

        service.assignRole(userId, "SALES_STAFF");

        verify(sessions).revokeLive(eq(userId), eq(null), eq(NOW), eq(SessionEndReason.ROLE_CHANGED));

        when(userRoles.findByUserIdAndRoleId(userId, role.getId()))
                .thenReturn(Optional.of(new UserRoleJpaEntity(UUID.randomUUID(), userId, role.getId())));
        service.assignRole(userId, "SALES_STAFF");

        verify(sessions).revokeLive(any(), any(), any(), any());
    }

    @Test
    void revokingARoleNotHeldEndsNothing() {
        var role = new RoleJpaEntity(UUID.randomUUID(), "SALES_STAFF", "Sales", null);
        when(users.existsById(new UserId(userId))).thenReturn(true);
        when(roles.findByCode("SALES_STAFF")).thenReturn(Optional.of(role));
        when(userRoles.findByUserIdAndRoleId(userId, role.getId())).thenReturn(Optional.empty());

        service.revokeRole(userId, "SALES_STAFF");

        verifyNoInteractions(sessions);
    }

    @Test
    void theLifetimeMustBePositiveAndAtMostADay() {
        for (Duration bad : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofHours(25)}) {
            assertThatThrownBy(() -> new IdentityServiceImpl(roles, mock(PermissionJpaRepository.class),
                    mock(RolePermissionJpaRepository.class), userRoles, users, mock(PermissionCatalog.class),
                    encoder, jwtEncoder, clock, sessions, bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
