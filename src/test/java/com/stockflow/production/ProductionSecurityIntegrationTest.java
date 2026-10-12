package com.stockflow.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.production.internal.service.SampleProductionProgress;
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
 * seeded by V20261010000500 and V20261014000200 (resolved per request from the {@code roles} claim,
 * ADR-0008) and the warehouse data scope of SCRUM-457. Only token decoding is stubbed.
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
    @Autowired SampleProductionProgress progress;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private ProductionFixtures fixtures;
    private UUID hcm;

    @BeforeEach
    void setUp() {
        fixtures = new ProductionFixtures(storage, jdbc, tx, entityManager, progress);
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
        send(get(ORDERS), staff("CUSTOMER"), null).andExpect(status().isForbidden());
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
    @DisplayName("a portal customer reads and answers only their own samples and cannot use back-office verbs")
    void portalCustomerOwnership() throws Exception {
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
        // No UPDATE or CREATE for CUSTOMER: the permission refuses before the service is reached.
        send(post(SAMPLES + "/" + sample + "/sending"), a, null).andExpect(status().isForbidden());
        send(post(SAMPLES + "/" + sample + "/next-round"), a, null).andExpect(status().isForbidden());

        send(get(SAMPLES), a, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        send(post(SAMPLES + "/" + sample + "/change-request"), a, java.util.Map.of("feedback", "Darker blue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHANGES_REQUESTED"))
                .andExpect(jsonPath("$.data.respondedBy").value(userA.toString()));
    }

    @Test
    @DisplayName("QC and the print shop read the samples their warehouse makes but cannot answer for the customer")
    void onlyTheCustomerOrSalesAnswer() throws Exception {
        String sales = staff("SALES_STAFF");
        UUID sample = createDraft(sales, DemoData.CUSTOMER_ID, fixtures.confirmedDesign());
        send(post(SAMPLES + "/" + sample + "/submission"), sales, java.util.Map.of("warehouseId", hcm))
                .andExpect(status().isOk());
        fixtures.workshopFinished(sample);
        send(post(SAMPLES + "/" + sample + "/sending"), sales, null).andExpect(status().isOk());

        for (String role : List.of("QC_STAFF", "PRODUCTION_STAFF", "WAREHOUSE_MANAGER")) {
            UUID user = fixtures.user();
            assign(user, hcm);
            String inHcm = token(user, role);
            send(get(SAMPLES + "/" + sample), inHcm, null).andExpect(status().isOk());
            send(post(SAMPLES + "/" + sample + "/approval"), inHcm, null).andExpect(status().isForbidden());
            send(post(SAMPLES + "/" + sample + "/change-request"), inHcm, java.util.Map.of("feedback", "x"))
                    .andExpect(status().isForbidden());
            send(post(SAMPLES + "/" + sample + "/cancellation"), inHcm, java.util.Map.of("reason", "x"))
                    .andExpect(status().isForbidden());
        }
        send(get(SAMPLES + "/" + sample), sales, null).andExpect(jsonPath("$.data.status").value("SENT_TO_CUSTOMER"));
        send(post(SAMPLES + "/" + sample + "/approval"), sales, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("SCRUM-397: the warehouse issues blanks (production-material-issues:CREATE); the print shop may only read them")
    void materialIssueOverHttp() throws Exception {
        com.stockflow.support.ConfirmedDesigns.stockBlanks(jdbc, tx, 10);
        String sales = staff("SALES_STAFF");
        UUID id = createDraft(sales, DemoData.CUSTOMER_ID, fixtures.confirmedDesign());
        String order = data(send(post(SAMPLES + "/" + id + "/submission"), sales,
                java.util.Map.of("warehouseId", hcm))).path("productionOrder").path("id").asText();
        UUID printerId = fixtures.user();
        assign(printerId, hcm);
        String printer = token(printerId, "PRODUCTION_STAFF");
        send(post(ORDERS + "/" + order + "/prepress"), printer, java.util.Map.of("decision", "PASS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.status").value("READY"));
        String issues = ORDERS + "/" + order + "/material-issues";

        send(post(issues), printer, java.util.Map.of("requestId", UUID.randomUUID(), "toLocation", "HCM-PRD01"))
                .andExpect(status().isForbidden());
        send(post(issues), staff("WAREHOUSE_STAFF"), java.util.Map.of("requestId", UUID.randomUUID(),
                "toLocation", "HCM-PRD01")).andExpect(status().isForbidden());   // not assigned to HCM
        UUID storekeeperId = fixtures.user();
        assign(storekeeperId, hcm);
        String storekeeper = token(storekeeperId, "WAREHOUSE_STAFF");
        send(post(issues), storekeeper, java.util.Map.of("toLocation", "HCM-PRD01"))
                .andExpect(status().isBadRequest());

        String state = "READY";
        for (int scans = 0; scans < 5 && "READY".equals(state); scans++) {
            state = data(send(post(issues), storekeeper, java.util.Map.of("requestId", UUID.randomUUID(),
                    "toLocation", "HCM-PRD01")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.issue.toLocation").value("HCM-PRD01")))
                    .path("order").path("summary").path("status").asText();
        }
        org.assertj.core.api.Assertions.assertThat(state).isEqualTo("MATERIAL_ISSUED");
        send(get(issues), printer, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sku").value(ProductionFixtures.BLANK));
        send(post(issues), storekeeper, java.util.Map.of("requestId", UUID.randomUUID(), "toLocation", "HCM-PRD01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PRODUCTION_TRANSITION"));
    }

    @Test
    @DisplayName("SCRUM-398..401: the print shop prints and records output, QC alone records QC (no longer production-orders:UPDATE), the manager alone cancels")
    void printShopStepsOverHttp() throws Exception {
        com.stockflow.support.ConfirmedDesigns.stockBlanks(jdbc, tx, 10);
        String sales = staff("SALES_STAFF");
        UUID id = createDraft(sales, DemoData.CUSTOMER_ID, fixtures.confirmedDesign());
        String order = data(send(post(SAMPLES + "/" + id + "/submission"), sales,
                java.util.Map.of("warehouseId", hcm))).path("productionOrder").path("id").asText();
        String printer = assigned("PRODUCTION_STAFF");
        String storekeeper = assigned("WAREHOUSE_STAFF");
        String inspector = assigned("QC_STAFF");
        String manager = assigned("WAREHOUSE_MANAGER");
        send(post(ORDERS + "/" + order + "/prepress"), inspector, java.util.Map.of("decision", "PASS"))
                .andExpect(status().isForbidden());
        send(post(ORDERS + "/" + order + "/prepress"), printer, java.util.Map.of("decision", "PASS"))
                .andExpect(status().isOk());
        String state = "READY";
        for (int scans = 0; scans < 5 && "READY".equals(state); scans++) {
            state = data(send(post(ORDERS + "/" + order + "/material-issues"), storekeeper,
                    java.util.Map.of("requestId", UUID.randomUUID(), "toLocation", "HCM-PRD01")))
                    .path("order").path("summary").path("status").asText();
        }

        send(post(ORDERS + "/" + order + "/printing"), inspector, null).andExpect(status().isForbidden());
        send(post(ORDERS + "/" + order + "/printing"), printer, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.status").value("PRINTING"));
        send(post(ORDERS + "/" + order + "/output"), printer, java.util.Map.of("printed", 0))
                .andExpect(status().isBadRequest());
        send(post(ORDERS + "/" + order + "/output"), printer, java.util.Map.of("printed", 2,
                "scrap", List.of(java.util.Map.of("reason", "OTHER", "quantity", 1))))
                .andExpect(status().isBadRequest());   // OTHER says what
        send(post(ORDERS + "/" + order + "/output"), printer, java.util.Map.of("printed", 2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.status").value("QC"));

        String qc = ORDERS + "/" + order + "/qc-results";
        send(post(qc), printer, java.util.Map.of("passed", 2, "failed", 0)).andExpect(status().isForbidden());
        send(post(ORDERS + "/" + order + "/cancellation"), printer, java.util.Map.of("reason", "no"))
                .andExpect(status().isForbidden());
        send(post(qc), inspector, java.util.Map.of("passed", 2, "failed", 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.status").value("COMPLETED"));
        send(get(qc), sales, null).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].passed").value(2));
        send(get(ORDERS + "/" + order + "/scrap"), printer, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        send(post(ORDERS + "/" + order + "/cancellation"), manager, java.util.Map.of("reason", "too late"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PRODUCTION_TRANSITION"));
    }

    private String assigned(String role) {
        UUID userId = fixtures.user();
        assign(userId, hcm);
        return token(userId, role);
    }

    @Test
    @DisplayName("Q2 over HTTP: Sales keeps an agreed amount on cancellation (sales-order-cancellations:UPDATE), validated and audited; the print shop cannot")
    void agreedFeeOverHttp() throws Exception {
        UUID orderId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, version, created_at, paid_amount, paid_in_full_at, warehouse_id,
                        released_at, released_by)
                    VALUES (?, ?, ?, ?, 'IN_PRODUCTION', 20000, 'VND', NOW(), 0, NOW(), 20000, NOW(), ?, NOW(), ?)""",
                    orderId, "SO-20991227-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(1_000_000)), DemoData.CUSTOMER_ID, UUID.randomUUID(), hcm, fixtures.user());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                    VALUES (?, ?, ?, 1, 20000, 'VND')""", Identifiers.newId(), orderId, ProductionFixtures.BLANK);
        });
        String path = "/api/v1/orders/" + orderId + "/admin-cancellation";
        java.util.Map<String, Object> fee = java.util.Map.of("amount", 12000, "reason", "Printing had started",
                "detail", "Agreed with the buyer: setup and 6 printed cups");

        send(post(path), assigned("PRODUCTION_STAFF"), java.util.Map.of("reasonCode", "CUSTOMER_REQUEST",
                "agreedFee", fee)).andExpect(status().isForbidden());
        String sales = staff("SALES_STAFF");
        send(post(path), sales, java.util.Map.of("reasonCode", "CUSTOMER_REQUEST", "agreedFee",
                java.util.Map.of("amount", 12000, "reason", "Printing had started")))
                .andExpect(status().isBadRequest());
        send(post(path), sales, java.util.Map.of("reasonCode", "CUSTOMER_REQUEST", "agreedFee",
                java.util.Map.of("amount", 25000, "reason", "r", "detail", "d")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        send(post(path), sales, java.util.Map.of("reasonCode", "CUSTOMER_REQUEST", "agreedFee", fee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancellation.retainedAmount").value(12000))
                .andExpect(jsonPath("$.data.cancellation.feeReason").value("Printing had started"))
                .andExpect(jsonPath("$.data.cancellation.feeDetail").value("Agreed with the buyer: setup and 6 printed cups"));
        send(post(path), sales, java.util.Map.of("reasonCode", "CUSTOMER_REQUEST", "agreedFee", fee))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_CANCELLABLE"));
        com.stockflow.support.Await.until("audited", java.time.Duration.ofSeconds(10), () -> jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'order' AND resource_id = ?
                   AND outcome = 'SUCCESS'""", Integer.class, orderId.toString()) >= 1);
    }

    @Test
    @DisplayName("SCRUM-415: the dashboard needs production-orders:READ and counts only the caller's warehouses")
    void dashboardOverHttp() throws Exception {
        send(get("/api/v1/production/dashboard"), staff("CUSTOMER"), null).andExpect(status().isForbidden());
        send(get("/api/v1/production/dashboard"), staff("PRODUCTION_STAFF"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overdue").value(0))
                .andExpect(jsonPath("$.data.samplesAwaitingApproval").value(0));
        send(get("/api/v1/production/dashboard").param("from", "2026-10-12").param("to", "2026-10-01"),
                assigned("WAREHOUSE_MANAGER"), null).andExpect(status().isBadRequest());
        send(get("/api/v1/production/dashboard/overdue"), assigned("PRODUCTION_STAFF"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
        send(get("/api/v1/production/dashboard/samples-awaiting-approval"), assigned("SALES_STAFF"), null)
                .andExpect(status().isOk());
    }
}
