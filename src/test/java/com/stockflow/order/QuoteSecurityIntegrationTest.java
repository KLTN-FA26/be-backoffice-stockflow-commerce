package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.support.ConfirmedDesigns;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-448 over HTTP with security ON: the real filter chain and permission aspect, the grants seeded by
 * V20261014000300 (resolved per request from the {@code roles} claim, ADR-0008). Only token decoding is
 * stubbed. The business flow end to end is {@code QuoteIntegrationTest}; this one is who may do what.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
class QuoteSecurityIntegrationTest {

    private static final String QUOTES = "/api/v1/quotes";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired OrderService orders;

    private String token(UUID userId, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(userId.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private UUID user() {
        return tx.execute(s -> ReferenceRows.user(entityManager));
    }

    private UUID customerOf(UUID portalUser) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Portal customer', ?, 'ACTIVE', 0, NOW())""", id, portalUser, "q-" + id + "@example.com"));
        return id;
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String authorization, Object body) throws Exception {
        request.header("Authorization", authorization);
        if (body != null) {
            request.contentType("application/json").content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private Map<String, Object> createBody(UUID customer, UUID design) {
        return Map.of("requestId", UUID.randomUUID(), "customerId", customer, "paymentTerm", "PREPAID",
                "validUntil", Instant.now().plus(Duration.ofDays(7)).toString(),
                "lines", List.of(Map.of("blankSku", ConfirmedDesigns.BLANK, "designSnapshotId", design, "quantity", 3,
                        "tiers", List.of(Map.of("minQuantity", 2, "unitPrice", 3000)))));
    }

    @Test
    @DisplayName("anonymous 401; roles without the grant 403; Sales drafts and sends; only the customer answers, only their own")
    void whoMayDoWhat() throws Exception {
        mvc.perform(get(QUOTES)).andExpect(status().isUnauthorized());
        send(get(QUOTES), token(user(), "WAREHOUSE_STAFF"), null).andExpect(status().isForbidden());

        UUID userA = user();
        UUID userB = user();
        UUID customerA = customerOf(userA);
        customerOf(userB);
        UUID design = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, customerA).snapshotId();
        String sales = token(user(), "SALES_STAFF");
        String a = token(userA, "CUSTOMER");
        String b = token(userB, "CUSTOMER");

        send(post(QUOTES), a, createBody(customerA, design)).andExpect(status().isForbidden());
        send(post(QUOTES), sales, Map.of("customerId", customerA)).andExpect(status().isBadRequest());
        JsonNode draft = data(send(post(QUOTES), sales, createBody(customerA, design)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].proposedUnitPrice").value(3000))
                .andExpect(jsonPath("$.data.lines[0].proposedLineTotal").value(9000))
                .andExpect(jsonPath("$.data.proposedTotal").value(9000)));
        String id = draft.path("id").asText();
        // A line without tiers is read from the price list over HTTP too (it was a 500).
        send(post(QUOTES), sales, Map.of("requestId", UUID.randomUUID(), "customerId", customerA,
                "paymentTerm", "PREPAID", "validUntil", Instant.now().plus(Duration.ofDays(7)).toString(),
                "lines", List.of(Map.of("blankSku", ConfirmedDesigns.BLANK, "designSnapshotId", design, "quantity", 3))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.lines[0].tiers.length()").value(1));

        send(get(QUOTES + "/" + id), a, null).andExpect(status().isNotFound());   // a draft is not shown
        JsonNode sent = data(send(post(QUOTES + "/" + id + "/sending"), sales,
                Map.of("version", draft.path("version").asLong())).andExpect(status().isOk()));
        long version = sent.path("version").asLong();

        send(get(QUOTES), a, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        send(get(QUOTES).param("customerId", customerA.toString()), b, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        send(get(QUOTES + "/" + id), b, null).andExpect(status().isNotFound());
        send(post(QUOTES + "/" + id + "/acceptance"), b, Map.of("version", version)).andExpect(status().isNotFound());

        // The answer is APPROVE, granted to CUSTOMER only: Sales and the coordinator cannot answer.
        send(post(QUOTES + "/" + id + "/acceptance"), sales, Map.of("version", version))
                .andExpect(status().isForbidden());
        send(post(QUOTES + "/" + id + "/acceptance"), token(user(), "ORDER_COORDINATOR"), Map.of("version", version))
                .andExpect(status().isForbidden());
        // The customer may answer, but not use Sales' verbs.
        send(post(QUOTES + "/" + id + "/conversion"), a, Map.of("version", version)).andExpect(status().isForbidden());
        send(post(QUOTES + "/" + id + "/acceptance"), a, Map.of("version", version))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DESIGN_SAMPLE_NOT_APPROVED"));
        send(post(QUOTES + "/" + id + "/change-request"), a, Map.of("version", version, "feedback", "Cheaper at 5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHANGES_REQUESTED"))
                .andExpect(jsonPath("$.data.respondedBy").value(userA.toString()));

        String coordinator = token(user(), "ORDER_COORDINATOR");
        send(get(QUOTES + "/" + id), coordinator, null).andExpect(status().isOk());
        send(post(QUOTES + "/" + id + "/conversion"), coordinator, Map.of("version", version + 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_QUOTE_TRANSITION"));
    }

    @Test
    @DisplayName("reorder: the order verb; anonymous 401, warehouse 403, another customer 404, a price-list order 409")
    void whoMayReorder() throws Exception {
        UUID userA = user();
        UUID userB = user();
        UUID customerA = customerOf(userA);
        customerOf(userB);
        UUID orderId = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customerA,
                List.of(new PlaceOrderCommand.Line(new Sku(ConfirmedDesigns.BLANK), 1, Money.vnd(3000), null))))
                .orderId();
        String reorder = "/api/v1/orders/" + orderId + "/reorder";
        Map<String, Object> body = Map.of("requestId", UUID.randomUUID());

        mvc.perform(post(reorder).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
        send(post(reorder), token(user(), "WAREHOUSE_STAFF"), body).andExpect(status().isForbidden());
        send(post(reorder), token(userB, "CUSTOMER"), body).andExpect(status().isNotFound());
        send(post(reorder), token(userA, "CUSTOMER"), Map.of()).andExpect(status().isBadRequest());
        send(post(reorder), token(userA, "CUSTOMER"), body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_QUOTED"));
        send(post(reorder), token(user(), "SALES_STAFF"), body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_QUOTED"));
    }
}
