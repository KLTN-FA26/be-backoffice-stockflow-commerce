package com.stockflow.warehouse;

import com.stockflow.common.security.RoleMatrixView;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.UpdateRolePermissionsCommand;
import com.stockflow.support.DemoData;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Printing location labels through the real filter chain, JWT authority converter and permission
 * aspect (SCRUM-97); only token decoding is stubbed. Printing is reading the map, so it needs
 * {@code warehouse-locations:READ} and nothing else.
 */
@SecuredIntegrationTest
class LocationLabelSecurityIntegrationTest {

    private static final String TEST_ROLE = "INVENTORY_PLANNER";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired IdentityService identity;

    private List<String> originalPermissions;

    @BeforeEach
    void rememberPermissions() {
        originalPermissions = identity.roleMatrix(TEST_ROLE).groups().stream()
                .flatMap(group -> group.resources().stream())
                .flatMap(resource -> resource.actions().stream()
                        .filter(RoleMatrixView.ActionChip::granted)
                        .map(action -> resource.code() + ":" + action.action().name()))
                .toList();
    }

    @AfterEach
    void restorePermissions() {
        identity.updateRolePermissions(new UpdateRolePermissionsCommand(
                TEST_ROLE, identity.roleMatrix(TEST_ROLE).version(), originalPermissions));
    }

    private String token(String permission) {
        identity.updateRolePermissions(new UpdateRolePermissionsCommand(
                TEST_ROLE, identity.roleMatrix(TEST_ROLE).version(), List.of(permission)));
        return TestTokens.bearer(decoder, UUID.randomUUID(), Map.of("scope_level", "ALL"), TEST_ROLE);
    }

    private static MockHttpServletRequestBuilder printShelfA01() {
        return post("/api/v1/warehouses/{id}/location-labels", DemoData.WAREHOUSE_HCM)
                .accept(MediaType.APPLICATION_PDF, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"A4_SHEET\",\"shelfId\":\"" + DemoData.shelf("A01") + "\"}");
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(printShelfA01()).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutLocationReadIs403() throws Exception {
        mvc.perform(printShelfA01().header("Authorization", token("warehouse-warehouses:READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void withLocationReadPrintsThePdf() throws Exception {
        byte[] pdf = mvc.perform(printShelfA01().header("Authorization", token("warehouse-locations:READ")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }
}
