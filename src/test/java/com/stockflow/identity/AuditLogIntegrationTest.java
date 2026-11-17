package com.stockflow.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE3-B over HTTP with security ON (kltn-docs 00 §2.5): the audit trail is the System Admin's to read - every other
 * role is refused - and a change the documents say is audited can be read back with what changed: an internal price
 * set and replaced (13 §4.2 step 3) names its SKU and its new tiers, found by the request that made it (its correlation
 * id), by the record it changed, and opened on its own. Only token decoding is stubbed.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
class AuditLogIntegrationTest {

    private static final String LOGS = "/api/v1/audit-logs";
    private static final String PRICES = "/api/v1/catalog/customer-prices";
    private static final String CORRELATION = "X-Correlation-Id";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String token(UUID userId, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(userId.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private UUID user() {
        return tx.execute(s -> ReferenceRows.user(entityManager));
    }

    private ResultActions as(String authorization, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", authorization));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test
    @DisplayName("only the System Admin reads the trail: anonymous 401, every other role 403 on the list and an entry")
    void onlyTheSystemAdminReadsIt() throws Exception {
        mvc.perform(get(LOGS)).andExpect(status().isUnauthorized());
        for (Role role : Role.values()) {
            if (role == Role.SYSTEM_ADMIN) {
                continue;
            }
            String caller = token(user(), role.authority());
            as(caller, get(LOGS)).andExpect(status().isForbidden());
            as(caller, get(LOGS + "/" + UUID.randomUUID())).andExpect(status().isForbidden());
        }
        as(token(user(), Role.SYSTEM_ADMIN.authority()), get(LOGS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    @DisplayName("an internal price set and replaced: found by its request's correlation id and by its record, newest "
            + "first, naming the SKU and the new tiers; opened by its id; bad paging and periods 400, none 404")
    void aPriceChangeIsReadBackWithWhatChanged() throws Exception {
        UUID customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Audited customer', ?, 'ACTIVE', 0, NOW())""", customer, user(),
                "audit-" + customer + "@example.com"));
        String sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 10).sku();
        UUID managerId = user();
        String manager = token(managerId, "SALES_MANAGER");
        String admin = token(user(), Role.SYSTEM_ADMIN.authority());
        String created = "be3-price-set-" + UUID.randomUUID();
        String replaced = "be3-price-replaced-" + UUID.randomUUID();

        JsonNode price = data(as(manager, post(PRICES + "/" + customer + "/" + sku).header(CORRELATION, created)
                .contentType("application/json").content(json.writeValueAsString(Map.of("tiers", List.of(
                        Map.of("minQuantity", 1, "unitPrice", 11000), Map.of("minQuantity", 5, "unitPrice", 10500))))))
                .andExpect(status().isCreated()));
        as(manager, put(PRICES + "/" + customer + "/" + sku).header(CORRELATION, replaced)
                .contentType("application/json").content(json.writeValueAsString(Map.of("version",
                        price.path("version").asLong(), "tiers", List.of(Map.of("minQuantity", 1, "unitPrice", 10800))))))
                .andExpect(status().isOk());

        // The request that set the price: its one entry, with the SKU and both tiers.
        JsonNode byRequest = data(as(admin, get(LOGS).param("correlationId", created)).andExpect(status().isOk()));
        assertThat(byRequest.path("items")).hasSize(1);
        JsonNode set = byRequest.path("items").get(0);
        assertThat(set.path("action").asText()).isEqualTo("CREATE");
        assertThat(set.path("resourceType").asText()).isEqualTo("customer-price");
        assertThat(set.path("resourceId").asText()).isEqualTo(customer.toString());
        assertThat(set.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(set.path("actorId").asText()).isEqualTo(managerId.toString());
        assertThat(set.path("correlationId").asText()).isEqualTo(created);
        JsonNode details = json.readTree(set.path("details").asText());
        assertThat(details.path("sku").asText()).isEqualTo(sku);
        assertThat(details.path("tiers").asText()).isEqualTo("[from 1: 11000, from 5: 10500]");

        // Opened on its own.
        as(admin, get(LOGS + "/" + set.path("id").asText())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(set.path("id").asText()))
                .andExpect(jsonPath("$.data.details").value(set.path("details").asText()));

        // By the record it changed: the replacement first, with its new tier and the version it replaced.
        JsonNode byRecord = data(as(admin, get(LOGS).param("resourceType", "customer-price")
                .param("resourceId", customer.toString())).andExpect(status().isOk()));
        assertThat(byRecord.path("items")).hasSize(2);
        JsonNode update = byRecord.path("items").get(0);
        assertThat(update.path("action").asText()).isEqualTo("UPDATE");
        assertThat(update.path("correlationId").asText()).isEqualTo(replaced);
        assertThat(json.readTree(update.path("details").asText()).path("tiers").asText())
                .isEqualTo("[from 1: 10800]");
        assertThat(byRecord.path("items").get(1).path("action").asText()).isEqualTo("CREATE");
        assertThat(data(as(admin, get(LOGS).param("resourceType", "customer-price")
                .param("resourceId", customer.toString()).param("action", "UPDATE"))).path("items")).hasSize(1);
        assertThat(data(as(admin, get(LOGS).param("actorId", managerId.toString())
                .param("from", "2000-01-01T00:00:00Z"))).path("items")).hasSize(2);

        as(admin, get(LOGS + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
        as(admin, get(LOGS).param("size", "201")).andExpect(status().isBadRequest());
        as(admin, get(LOGS).param("from", "2026-11-15T00:00:00Z").param("to", "2026-11-14T00:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
}
