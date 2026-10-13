package com.stockflow.design;

import com.stockflow.common.id.Identifiers;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An assisted design names a customer and an owner account separately. The owner confirms the design and an
 * order of the customer later verifies it against {@code customer_id}, so an owner of company B on a draft of
 * company A would let A order what B confirmed. {@code POST /api/v1/designs} must refuse that, through the real
 * filter chain and permission aspect.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
// The same properties and mock as the other security integration tests, so the application context is shared.
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class DesignOwnerCustomerIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID adminUser;
    private UUID reviewer;
    private UUID ownerA;
    private UUID customerA;
    private UUID ownerB;
    private UUID customerB;

    @BeforeEach
    void setUp() {
        adminUser = account("ECOMMERCE_ADMIN");
        reviewer = account("QC_STAFF");
        ownerA = account("CUSTOMER");
        ownerB = account("CUSTOMER");
        customerA = profileOf(ownerA);
        customerB = profileOf(ownerB);
    }

    private UUID account(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT ?, ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", Identifiers.newId(), user, role));
        return user;
    }

    private UUID profileOf(UUID user) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Design customer', ?, 'ACTIVE', 0, NOW())""", id, user, "design-" + id + "@example.com"));
        return id;
    }

    private String adminToken() {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(adminUser.toString()).claim("roles", List.of("ECOMMERCE_ADMIN")).build());
        return "Bearer " + value;
    }

    private String body(UUID customer, UUID owner) {
        return """
                {"customerId":"%s","productId":"%s","ownerUserId":"%s","reviewerUserId":"%s",
                 "name":"Cabinet","spec":"Width 1800 mm"}""".formatted(customer, DemoData.PRODUCT_SOFA, owner, reviewer);
    }

    @Test
    void ownerOfAnotherCompanyIsRefusedWithAFieldError() throws Exception {
        long before = drafts();
        mvc.perform(post("/api/v1/designs").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customerA, ownerB)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_OWNER_CUSTOMER_MISMATCH"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("ownerUserId"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("DESIGN_OWNER_CUSTOMER_MISMATCH"));
        assertThat(drafts()).isEqualTo(before);
    }

    @Test
    void ownerWithoutACustomerProfileIsRefused() throws Exception {
        UUID orphan = account("CUSTOMER");
        mvc.perform(post("/api/v1/designs").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customerA, orphan)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_OWNER_CUSTOMER_MISMATCH"));
    }

    @Test
    void ownerOfThatCustomerCreatesTheDraft() throws Exception {
        mvc.perform(post("/api/v1/designs").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customerA, ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(customerA.toString()))
                .andExpect(jsonPath("$.data.ownerUserId").value(ownerA.toString()));
        mvc.perform(post("/api/v1/designs").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body(customerB, ownerB)))
                .andExpect(status().isOk());
    }

    private long drafts() {
        return jdbc.queryForObject("SELECT count(*) FROM design.design_draft WHERE customer_id = ?", Long.class, customerA);
    }
}
