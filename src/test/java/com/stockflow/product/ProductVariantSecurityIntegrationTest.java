package com.stockflow.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stockflow.common.security.RoleMatrixView;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.UpdateRolePermissionsCommand;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

/**
 * A product's variants through the real filter chain and permission aspects; only token decoding is
 * stubbed. Every request targets a random product id, so one that gets past security answers 404 and
 * nothing is committed. Permissions are resolved per request from the role (ADR-0008), so each token
 * first rewrites the test role's grants, and the original grants are restored afterwards.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = "stockflow.security.enabled=true")
class ProductVariantSecurityIntegrationTest {
    private static final String TEST_ROLE = "INVENTORY_PLANNER";

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
        mvc.perform(get("/api/v1/products/{id}/variants", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void variantsReadListsButCannotGenerate() throws Exception {
        String reader = token("product-variants:READ");
        MvcResult listed = mvc.perform(get("/api/v1/products/{id}/variants", UUID.randomUUID())
                .header("Authorization", reader)).andReturn();
        assertThat(listed.getResponse().getStatus()).isNotIn(401, 403);
        mvc.perform(post("/api/v1/products/{id}/variants/generation", UUID.randomUUID())
                        .header("Authorization", reader).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void variantsCreateOpensGeneration() throws Exception {
        String creator = token("product-variants:CREATE");
        MvcResult result = mvc.perform(post("/api/v1/products/{id}/variants/generation", UUID.randomUUID())
                        .header("Authorization", creator).contentType("application/json").content("{}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void productsUpdateAloneCannotAddAVariant() throws Exception {
        String editor = token("product-products:UPDATE");
        mvc.perform(post("/api/v1/products/{id}/variants", UUID.randomUUID())
                        .header("Authorization", editor).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void variantsUpdateWithoutApproveCannotActivate() throws Exception {
        String editor = token("product-variants:UPDATE");
        mvc.perform(post("/api/v1/products/{id}/variants/{vid}/activation", UUID.randomUUID(), UUID.randomUUID())
                        .header("Authorization", editor))
                .andExpect(status().isForbidden());
    }
}
