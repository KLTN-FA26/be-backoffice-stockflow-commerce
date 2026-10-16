package com.stockflow.product;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockflow.common.security.RoleMatrixView;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.UpdateRolePermissionsCommand;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

/**
 * The attribute dictionary endpoints through the real filter chain and permission aspects; only
 * token decoding is stubbed. Permissions are resolved per request from the role (ADR-0008), so each
 * token first rewrites the test role's grants, and the original grants are restored afterwards.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = "stockflow.security.enabled=true")
class AttributeSecurityIntegrationTest {
    private static final String TEST_ROLE = "INVENTORY_PLANNER";
    private static final UUID SOFA = DemoData.demoId("demo:cat:SOFA");

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired IdentityService identity;
    private List<String> originalPermissions;

    @BeforeEach
    void rememberPermissions() {
        originalPermissions =
                identity.roleMatrix(TEST_ROLE).groups().stream()
                        .flatMap(group -> group.resources().stream())
                        .flatMap(
                                resource ->
                                        resource.actions().stream()
                                                .filter(RoleMatrixView.ActionChip::granted)
                                                .map(a -> resource.code() + ":" + a.action().name()))
                        .toList();
    }

    @AfterEach
    void restorePermissions() {
        identity.updateRolePermissions(
                new UpdateRolePermissionsCommand(
                        TEST_ROLE, identity.roleMatrix(TEST_ROLE).version(), originalPermissions));
    }

    private String token(String permission) {
        identity.updateRolePermissions(
                new UpdateRolePermissionsCommand(
                        TEST_ROLE, identity.roleMatrix(TEST_ROLE).version(), List.of(permission)));
        String token = UUID.randomUUID().toString();
        when(decoder.decode(token))
                .thenReturn(
                        Jwt.withTokenValue(token)
                                .header("alg", "RS256")
                                .subject(UUID.randomUUID().toString())
                                .claim("scope_level", "ALL")
                                .claim("roles", List.of(TEST_ROLE))
                                .build());
        return "Bearer " + token;
    }

    @Test
    void anonymousIsUnauthorised() throws Exception {
        mvc.perform(get("/api/v1/attributes")).andExpect(status().isUnauthorized());
    }

    @Test
    void readIsEnoughToListButNotToCreate() throws Exception {
        String reader = token("product-attributes:READ");
        mvc.perform(get("/api/v1/attributes").header("Authorization", reader))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/attributes")
                                .header("Authorization", reader)
                                .contentType("application/json")
                                .content("{\"code\":\"SEC_X\",\"dataType\":\"SELECT\",\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void productProductsAloneDoesNotOpenTheDictionary() throws Exception {
        mvc.perform(get("/api/v1/attributes").header("Authorization", token("product-products:READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void categoryAttributesNeedUpdateToReplace() throws Exception {
        String reader = token("product-attributes:READ");
        mvc.perform(get("/api/v1/categories/{id}/attributes", SOFA).header("Authorization", reader))
                .andExpect(status().isOk());
        mvc.perform(
                        put("/api/v1/categories/{id}/attributes", SOFA)
                                .header("Authorization", reader)
                                .contentType("application/json")
                                .content("{\"attributes\":[]}"))
                .andExpect(status().isForbidden());
    }
}
