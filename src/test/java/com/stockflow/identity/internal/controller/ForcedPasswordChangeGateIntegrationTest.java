package com.stockflow.identity.internal.controller;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-474 FIRST-LOGIN, through the real filter chain and the real database: while an account carries the
 * "must change password" flag only {@code GET /me}, {@code GET /me/permissions}, {@code PUT /auth/password} and
 * {@code POST /auth/logout} work; every other call is 403 {@code PASSWORD_CHANGE_REQUIRED}, even for a role that
 * holds the permission. The same token works again the moment the password is changed.
 */
@SecuredIntegrationTest
class ForcedPasswordChangeGateIntegrationTest {

    private static final String CURRENT = "Temp0raryPass";
    private static final String CHANGED = "Brand-new-Pass1";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate tx;

    /** An account holding a role that reads users, forced or not; the token names SYSTEM_ADMIN. */
    private UUID account(boolean mustChange) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO identity.app_user (id, username, email, password_hash, status, must_change_password,
                                               temporary_password_set_at, version, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, NOW(), 0, NOW())""",
                id, "gate-" + id, "gate-" + id + "@example.com", encoder.encode(CURRENT), mustChange));
        return id;
    }

    private String token(UUID id) {
        return TestTokens.bearer(decoder, id, Role.SYSTEM_ADMIN);
    }

    @Test
    void aForcedUserCanOnlyReadWhoTheyAreChangeThePasswordAndSignOut() throws Exception {
        String bearer = token(account(true));

        mvc.perform(get("/api/v1/identity/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));
        mvc.perform(get("/api/v1/identity/me/permissions").header("Authorization", bearer))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/identity/auth/logout").header("Authorization", bearer))
                .andExpect(status().isOk());
        // reaches the controller: refused for the wrong current password, not by the gate
        mvc.perform(put("/api/v1/identity/auth/password").header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"nope-nope-1A\",\"newPassword\":\"" + CHANGED + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    void everyOtherCallIsRefusedEvenForARoleThatHoldsThePermission() throws Exception {
        String bearer = token(account(true));

        mvc.perform(get("/api/v1/identity/users").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/api/v1/identity/auth/sessions").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(post("/api/v1/identity/auth/logout-others").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void changingThePasswordLiftsTheGateForTheSameToken() throws Exception {
        UUID id = account(true);
        String bearer = token(id);

        mvc.perform(get("/api/v1/identity/users").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/identity/auth/password").header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + CURRENT + "\",\"newPassword\":\"" + CHANGED + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/identity/users").header("Authorization", bearer))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/identity/me").header("Authorization", bearer))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    }

    @Test
    void anAccountThatIsNotForcedIsNeverGated() throws Exception {
        mvc.perform(get("/api/v1/identity/users").header("Authorization", token(account(false))))
                .andExpect(status().isOk());
    }

    @Test
    void flaggingAnAccountAfterItsTokenWasIssuedGatesTheExistingToken() throws Exception {
        UUID id = account(false);
        String bearer = token(id);
        mvc.perform(get("/api/v1/identity/users").header("Authorization", bearer)).andExpect(status().isOk());

        tx.executeWithoutResult(status ->
                jdbc.update("UPDATE identity.app_user SET must_change_password = TRUE WHERE id = ?", id));

        mvc.perform(get("/api/v1/identity/users").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void anAnonymousCallIsStillTheOrdinary401() throws Exception {
        mvc.perform(get("/api/v1/identity/users")).andExpect(status().isUnauthorized());
    }
}
