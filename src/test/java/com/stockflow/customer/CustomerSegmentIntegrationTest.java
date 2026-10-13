package com.stockflow.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.catalog.internal.service.SegmentPriceService;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.internal.service.CustomerSegmentService;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Customer groups from the back office (user decision P7) over HTTP with security ON: the real filter chain and
 * permission aspect, the grants seeded by V20261014000600 (resolved per request from the {@code roles} claim,
 * ADR-0008). Only token decoding is stubbed. Then the group a customer is put in prices it: own → group → base.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
class CustomerSegmentIntegrationTest {

    private static final String SEGMENTS = "/api/v1/customer-segments";
    private static final String SKU = ConfirmedDesigns.BLANK;

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired CustomerSegmentService segments;
    @Autowired SegmentPriceService groupPrices;
    @Autowired CatalogService catalog;

    private String token(String role) {
        UUID userId = tx.execute(s -> ReferenceRows.user(entityManager));
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(userId.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private UUID customer() {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
                VALUES (?, 'Cong ty Gom Nhom', ?, 'ACTIVE', 0, NOW())""", id, "seg-" + id + "@example.com"));
        return id;
    }

    private long version(UUID customerId) {
        return jdbc.queryForObject("SELECT version FROM customer.customer WHERE id = ?", Long.class, customerId);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String authorization, Object body) throws Exception {
        if (authorization != null) request.header("Authorization", authorization);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static Map<String, Object> assignment(UUID segmentId, long version) {
        Map<String, Object> body = new HashMap<>();
        body.put("segmentId", segmentId);
        body.put("version", version);
        return body;
    }

    @Test
    void theAdminManagesGroupsSalesReadsAndAGroupPricesItsCustomers() throws Exception {
        String admin = token("ECOMMERCE_ADMIN");
        String sales = token("SALES_STAFF");
        String suffix = UUID.randomUUID().toString().substring(24).toUpperCase();
        Map<String, Object> group = Map.of("code", " dai-ly-" + suffix.toLowerCase() + " ", "name", "Đại lý cấp 1");

        // Who may do what.
        send(get(SEGMENTS), null, null).andExpect(status().isUnauthorized());
        send(get(SEGMENTS), token("WAREHOUSE_STAFF"), null).andExpect(status().isForbidden());
        send(get(SEGMENTS), token("CUSTOMER"), null).andExpect(status().isForbidden());
        send(post(SEGMENTS), sales, group).andExpect(status().isForbidden());
        send(post(SEGMENTS), token("ORDER_COORDINATOR"), group).andExpect(status().isForbidden());

        // Input is checked; the code is canonical and unique.
        send(post(SEGMENTS), admin, Map.of("code", "Đại lý", "name", "X")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        send(post(SEGMENTS), admin, Map.of("code", "OK-" + suffix)).andExpect(status().isBadRequest());
        JsonNode created = data(send(post(SEGMENTS), admin, group).andExpect(status().isCreated()));
        UUID segmentId = UUID.fromString(created.path("id").asText());
        assertThat(created.path("code").asText()).isEqualTo("DAI-LY-" + suffix);
        assertThat(created.path("version").asLong()).isZero();
        send(post(SEGMENTS), token("SYSTEM_ADMIN"), Map.of("code", "dai-ly-" + suffix, "name", "Again"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        // Sales and the coordinator read.
        send(get(SEGMENTS + "/" + segmentId), sales, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Đại lý cấp 1"));
        send(get(SEGMENTS), token("ORDER_COORDINATOR"), null).andExpect(status().isOk());
        send(get(SEGMENTS + "/" + UUID.randomUUID()), sales, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SEGMENT_NOT_FOUND"));

        // Rename with the version read; a stale one is refused; Sales cannot.
        send(put(SEGMENTS + "/" + segmentId), sales, Map.of("name", "X", "version", 0)).andExpect(status().isForbidden());
        send(put(SEGMENTS + "/" + segmentId), admin, Map.of("name", "Đại lý", "description", "Cấp 1", "version", 0))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.code").value("DAI-LY-" + suffix));
        send(put(SEGMENTS + "/" + segmentId), admin, Map.of("name", "Late", "version", 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        send(put(SEGMENTS + "/" + segmentId), admin, Map.of("name", "No version")).andExpect(status().isBadRequest());

        // Put a customer in the group: admin only, with the customer's version.
        UUID customerId = customer();
        String assign = "/api/v1/customers/" + customerId + "/segment";
        send(put(assign), sales, assignment(segmentId, version(customerId))).andExpect(status().isForbidden());
        send(put(assign), admin, assignment(UUID.randomUUID(), version(customerId))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SEGMENT_NOT_FOUND"));
        send(put("/api/v1/customers/" + UUID.randomUUID() + "/segment"), admin, assignment(segmentId, 0))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
        long before = version(customerId);
        send(put(assign), admin, assignment(segmentId, before)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.segmentId").value(segmentId.toString()));
        send(put(assign), admin, assignment(null, before)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        send(get(SEGMENTS + "/" + segmentId), sales, null).andExpect(jsonPath("$.data.customerCount").value(1));
        send(get("/api/v1/customers").param("segmentId", segmentId.toString()), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        // The group now prices the customer (own → group → base); out of the group, the base price again.
        groupPrices.create(segmentId, SKU, new BigDecimal("11000000"));
        PriceSchedule grouped = catalog.priceSchedule(customerId, SKU);
        assertThat(grouped.source()).isEqualTo(PriceSchedule.PriceSource.SEGMENT);
        assertThat(grouped.tiers().getFirst().unitPrice().amount()).isEqualByComparingTo("11000000");
        send(put(assign), token("SYSTEM_ADMIN"), assignment(null, version(customerId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.segmentId").doesNotExist());
        assertThat(catalog.priceSchedule(customerId, SKU).source()).isEqualTo(PriceSchedule.PriceSource.BASE);

        // Audited.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'customer-segment' AND resource_id = ?
                   AND outcome = 'SUCCESS'""", Long.class, segmentId.toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'customer-segment-assignment'
                   AND resource_id = ? AND outcome = 'SUCCESS'""", Long.class, customerId.toString())).isEqualTo(2);
    }

    @Test
    void twoAdminsAtOnceOneWinsTheOtherIsTold() throws Exception {
        String code = "RACE-" + UUID.randomUUID().toString().substring(24).toUpperCase();
        List<Object> creates = race(List.of(() -> segments.create(code, "A", null), () -> segments.create(code, "B", null)));
        assertThat(creates.stream().filter(BusinessException.class::isInstance)
                .map(e -> ((BusinessException) e).errorCode())).containsExactly(ErrorCode.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer.segment WHERE code = ?", Long.class, code))
                .isEqualTo(1);

        UUID segmentId = jdbc.queryForObject("SELECT id FROM customer.segment WHERE code = ?", UUID.class, code);
        UUID customerId = customer();
        long version = version(customerId);
        List<Object> moves = race(List.of(() -> segments.assign(customerId, segmentId, version),
                () -> segments.assign(customerId, null, version)));
        assertThat(moves.stream().filter(BusinessException.class::isInstance)
                .map(e -> ((BusinessException) e).errorCode())).containsExactly(ErrorCode.OPTIMISTIC_LOCK);
    }

    private static List<Object> race(List<Callable<Object>> attempts) throws Exception {
        var pool = Executors.newFixedThreadPool(attempts.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> attempt : attempts) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return attempt.call();
                    } catch (BusinessException refused) {
                        return refused;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
