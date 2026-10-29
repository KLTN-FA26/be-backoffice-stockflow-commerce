package com.stockflow.identity.internal.controller;

import com.stockflow.common.security.Role;
import com.stockflow.identity.api.CreateStaffUserCommand;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.TestTokens;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-474 LOCK-REASON through the real filter chain: lock, unlock, disable and enable refuse a missing or
 * out-of-range reason with 400, record it otherwise, and the history is readable with identity-users:READ.
 */
@SecuredIntegrationTest
class UserStatusReasonHttpIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired IdentityService identity;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private String admin;
    private UUID target;

    @BeforeEach
    void anAdministratorAndAnAccountToManage() {
        UUID adminId = tx.execute(s -> ReferenceRows.user(entityManager));
        admin = TestTokens.bearer(decoder, adminId, Role.SYSTEM_ADMIN);
        String name = "reason." + UUID.randomUUID().toString().substring(0, 8);
        target = identity.createStaffUser(new CreateStaffUserCommand(name, name + "@example.com", "Reason target",
                null, List.of("WAREHOUSE_STAFF"), null)).user().userId();
    }

    private String url(String action) {
        return "/api/v1/identity/users/" + target + "/" + action;
    }

    private static String body(String reason) {
        return "{\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}";
    }

    @Test
    void allFourActionsRefuseAMissingBlankShortOrLongReason() throws Exception {
        for (String action : List.of("lock", "unlock", "disable", "enable")) {
            mvc.perform(post(url(action)).header("Authorization", admin)).andExpect(status().isBadRequest());
            for (String bad : new String[]{null, "", "   ", "abcd", "x".repeat(501)}) {
                mvc.perform(post(url(action)).header("Authorization", admin)
                                .contentType("application/json").content(body(bad)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
            }
        }
        mvc.perform(get("/api/v1/identity/users/" + target).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.latestStatusChange").doesNotExist());
    }

    @Test
    void aValidReasonIsKeptAndShownOnTheDetailTheListAndTheHistory() throws Exception {
        mvc.perform(post(url("lock")).header("Authorization", admin).contentType("application/json")
                        .content(body("Nghi ngờ lộ mật khẩu")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOCKED"))
                .andExpect(jsonPath("$.data.latestStatusChange.action").value("LOCK"))
                .andExpect(jsonPath("$.data.latestStatusChange.reason").value("Nghi ngờ lộ mật khẩu"))
                .andExpect(jsonPath("$.data.latestStatusChange.fromStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.latestStatusChange.toStatus").value("LOCKED"));
        mvc.perform(post(url("unlock")).header("Authorization", admin).contentType("application/json")
                        .content(body("Đã xác minh với chủ tài khoản")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/identity/users/" + target).header("Authorization", admin))
                .andExpect(jsonPath("$.data.latestStatusChange.action").value("UNLOCK"))
                .andExpect(jsonPath("$.data.latestStatusChange.reason").value("Đã xác minh với chủ tài khoản"));
        mvc.perform(get("/api/v1/identity/users").param("q", "Reason target").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].latestStatusChange.reason").value("Đã xác minh với chủ tài khoản"));
        mvc.perform(get(url("status-changes")).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].action").value("UNLOCK"))
                .andExpect(jsonPath("$.data.items[1].action").value("LOCK"))
                .andExpect(jsonPath("$.data.items[1].reason").value("Nghi ngờ lộ mật khẩu"))
                .andExpect(jsonPath("$.data.items[1].actorUsername").isNotEmpty());
    }

    @Test
    void theHistoryNeedsTheUserReadPermissionAndTheActionsTheUpdateOne() throws Exception {
        String customer = TestTokens.bearer(decoder, UUID.randomUUID(), Role.CUSTOMER);

        mvc.perform(get(url("status-changes")).header("Authorization", customer)).andExpect(status().isForbidden());
        mvc.perform(post(url("lock")).header("Authorization", customer).contentType("application/json")
                        .content(body("Một lý do hợp lệ")))
                .andExpect(status().isForbidden());
        mvc.perform(get(url("status-changes"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/identity/users/" + UUID.randomUUID() + "/status-changes")
                        .header("Authorization", admin))
                .andExpect(status().isNotFound());
    }
}
