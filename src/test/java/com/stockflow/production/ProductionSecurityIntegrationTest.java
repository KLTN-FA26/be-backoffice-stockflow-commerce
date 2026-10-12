package com.stockflow.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-394/395 over HTTP with security ON: the real filter chain, the permission aspect, the grants
 * Tu seeded in V20261010000500 (resolved per request from the {@code roles} claim, ADR-0008) and the
 * warehouse data scope of SCRUM-457. Only token decoding is stubbed.
 *
 * <p>The CUSTOMER role has no {@code production-sample-requests} grant in the seed; one test pins that
 * a portal customer is refused today, and another grants it for its own duration to prove the
 * ownership rules the portal will rely on once the grant exists.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
class ProductionSecurityIntegrationTest {

    private static final String SAMPLES = "/api/v1/production/sample-requests";
    private static final String ORDERS = "/api/v1/production/orders";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private ProductionFixtures fixtures;
    private UUID hcm;

    @BeforeEach
    void setUp() {
        fixtures = new ProductionFixtures(storage, jdbc, tx, entityManager);
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
    }

    /** A bearer token for an existing user holding {@code role}; permissions come from the database. */
    private String token(UUID userId, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(userId.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private String staff(String role) {
        return token(fixtures.user(), role);
    }

    private void assign(UUID userId, UUID warehouseId) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                VALUES (?, ?, ?, 0, NOW())""", Identifiers.newId(), userId, warehouseId));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String authorization, Object body) throws Exception {
        request.header("Authorization", authorization);
        if (body != null) {
            request.contentType("application/json").content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private UUID createDraft(String sales, UUID customerId, ProductionFixtures.Design design) throws Exception {
        var created = send(post(SAMPLES), sales, java.util.Map.of("customerId", customerId,
                "designSnapshotId", design.snapshotId(), "blankSku", ProductionFixtures.BLANK, "quantity", 2))
                .andExpect(status().isCreated());
        return UUID.fromString(data(created).path("id").asText());
    }

    @Test
    @DisplayName("anonymous callers get 401; a role without the grant gets 403")
    void unauthenticatedAndUngranted() throws Exception {
        mvc.perform(get(SAMPLES)).andExpect(status().isUnauthorized());
        mvc.perform(get(ORDERS)).andExpect(status().isUnauthorized());
        send(get(SAMPLES), staff("WAREHOUSE_STAFF"), null).andExpect(status().isForbidden());
        send(get(SAMPLES), staff("CUSTOMER"), null) // no CUSTOMER grant in V20261010000500
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sales drafts, submits, records sending and the customer's approval; bad input is a 400")
    void salesLifecycleOverHttp() throws Exception {
        String sales = staff("SALES_STAFF");
        ProductionFixtures.Design design = fixtures.confirmedDesign();

        send(post(SAMPLES), sales, java.util.Map.of("customerId", DemoData.CUSTOMER_ID,
                "designSnapshotId", design.snapshotId(), "blankSku", ProductionFixtures.BLANK, "quantity", 0))
                .andExpect(status().isBadRequest());

        UUID id = createDraft(sales, DemoData.CUSTOMER_ID, design);
        send(post(SAMPLES + "/" + id + "/submission"), sales, java.util.Map.of("warehouseId", hcm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.productionOrder.status").value("PENDING_PREPRESS"));
        send(get(ORDERS).param("type", "SAMPLE").param("sampleRequestId", id.toString()), sales, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("SAMPLE"));

        fixtures.workshopFinished(id);
        send(post(SAMPLES + "/" + id + "/sending"), sales, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT_TO_CUSTOMER"));
        send(post(SAMPLES + "/" + id + "/approval"), sales, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        send(post(SAMPLES + "/" + id + "/approval"), sales, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SAMPLE_REQUEST_TRANSITION"));
    }

    @Test
    @DisplayName("the print shop reads only what its warehouses make and cannot draft samples")
    void workshopIsWarehouseScoped() throws Exception {
        String sales = staff("SALES_STAFF");
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        UUID submitted = createDraft(sales, DemoData.CUSTOMER_ID, design);
        String order = data(send(post(SAMPLES + "/" + submitted + "/submission"), sales,
                java.util.Map.of("warehouseId", hcm))).path("productionOrder").path("id").asText();
        UUID draft = createDraft(sales, DemoData.CUSTOMER_ID, design);

        UUID printer = fixtures.user();
        assign(printer, hcm);
        String inHcm = token(printer, "PRODUCTION_STAFF");
        send(post(SAMPLES), inHcm, java.util.Map.of("customerId", DemoData.CUSTOMER_ID,
                "designSnapshotId", design.snapshotId(), "blankSku", ProductionFixtures.BLANK, "quantity", 2))
                .andExpect(status().isForbidden());
        send(get(ORDERS + "/" + order), inHcm, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history[0].toStatus").value("PENDING_PREPRESS"));
        send(get(SAMPLES + "/" + submitted), inHcm, null).andExpect(status().isOk());
        send(get(SAMPLES + "/" + draft), inHcm, null) // a draft is made nowhere yet
                .andExpect(status().isForbidden());

        String unassigned = staff("PRODUCTION_STAFF");
        send(get(ORDERS + "/" + order), unassigned, null).andExpect(status().isForbidden());
        send(get(ORDERS), unassigned, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    @DisplayName("with the portal grant, a customer answers only their own samples and cannot use back-office verbs")
    void portalCustomerOwnership() throws Exception {
        portalGrant(true);
        try {
            UUID userA = fixtures.user();
            UUID userB = fixtures.user();
            UUID customerA = fixtures.customerOf(userA);
            fixtures.customerOf(userB);
            String sales = staff("SALES_STAFF");
            UUID sample = createDraft(sales, customerA, fixtures.confirmedDesign(customerA));
            send(post(SAMPLES + "/" + sample + "/submission"), sales, java.util.Map.of("warehouseId", hcm))
                    .andExpect(status().isOk());
            fixtures.workshopFinished(sample);
            send(post(SAMPLES + "/" + sample + "/sending"), sales, null).andExpect(status().isOk());

            String a = token(userA, "CUSTOMER");
            String b = token(userB, "CUSTOMER");
            send(get(SAMPLES + "/" + sample), b, null).andExpect(status().isNotFound());
            send(post(SAMPLES + "/" + sample + "/approval"), b, null).andExpect(status().isNotFound());
            send(get(SAMPLES).param("customerId", customerA.toString()), b, null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(0));
            send(post(SAMPLES + "/" + sample + "/sending"), a, null).andExpect(status().isForbidden());

            send(get(SAMPLES), a, null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1));
            send(post(SAMPLES + "/" + sample + "/change-request"), a, java.util.Map.of("feedback", "Darker blue"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CHANGES_REQUESTED"))
                    .andExpect(jsonPath("$.data.respondedBy").value(userA.toString()));
        } finally {
            portalGrant(false);
        }
        send(get(SAMPLES), token(fixtures.user(), "CUSTOMER"), null).andExpect(status().isForbidden());
    }

    /**
     * CUSTOMER is a system role whose grants only migrations change, so the test writes the rows the
     * missing migration would, on its own Testcontainers database — and advances {@code app_role.version}
     * in the same transaction, as ADR-0008 requires.
     */
    private void portalGrant(boolean on) {
        tx.executeWithoutResult(s -> {
            if (on) {
                jdbc.update("""
                        INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
                        SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'test'
                          FROM identity.app_role r JOIN identity.permission p
                            ON p.resource = 'production-sample-requests' AND p.action IN ('VIEW_PAGE', 'READ', 'UPDATE')
                         WHERE r.code = 'CUSTOMER'
                        ON CONFLICT (role_id, permission_id) DO NOTHING""");
            } else {
                jdbc.update("""
                        DELETE FROM identity.role_permission rp USING identity.app_role r, identity.permission p
                         WHERE rp.role_id = r.id AND rp.permission_id = p.id AND r.code = 'CUSTOMER'
                           AND p.resource = 'production-sample-requests'""");
            }
            jdbc.update("UPDATE identity.app_role SET version = version + 1 WHERE code = 'CUSTOMER'");
        });
        // After the commit, move the cached version pointer as RoleAuthorizationCache.publishVersion does
        // for a grant change made through the identity service. Moving it is the invalidation; nothing
        // is deleted.
        Long version = jdbc.queryForObject("SELECT version FROM identity.app_role WHERE code = 'CUSTOMER'", Long.class);
        redis.opsForHash().put("stockflow:v1:authz:role-versions", "CUSTOMER", String.valueOf(version));
    }
}
