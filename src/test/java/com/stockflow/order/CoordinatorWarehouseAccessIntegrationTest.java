package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.TestTokens;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V20261022001100 over HTTP with the real filter chain and the role grants of the database: the Order Coordinator
 * lists the warehouses an order can be released to and releases one, but edits no warehouse and reads no map; a role
 * without the grant is still refused, and listing warehouses gives nobody release.
 */
@SecuredIntegrationTest
class CoordinatorWarehouseAccessIntegrationTest {

    private static final String WAREHOUSES = "/api/v1/warehouses";
    private static final String CHECKSUM = "a".repeat(64);

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID hcm;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
    }

    private String as(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        return TestTokens.bearer(decoder, user, role);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String authorization, Object body)
            throws Exception {
        request.header("Authorization", authorization);
        if (body != null) {
            request.contentType("application/json").content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test
    @DisplayName("the coordinator lists and reads warehouses, and may not create, edit, (de)activate or map one")
    void coordinatorReadsTheDirectoryOnly() throws Exception {
        String coordinator = as("ORDER_COORDINATOR");

        JsonNode page = data(send(get(WAREHOUSES), coordinator, null).andExpect(status().isOk()));
        List<String> prefixes = new ArrayList<>();
        page.path("items").forEach(w -> prefixes.add(w.path("prefix").asText()));
        assertThat(prefixes).contains("HCM");
        send(get(WAREHOUSES + "/" + hcm), coordinator, null).andExpect(status().isOk());

        send(post(WAREHOUSES), coordinator, Map.of("prefix", "TST", "name", "Test", "address", "1 Test St",
                "mapUnit", "M", "mapWidth", 10, "mapHeight", 10)).andExpect(status().isForbidden());
        send(put(WAREHOUSES + "/" + hcm), coordinator, Map.of("name", "Renamed", "address", "1 Test St",
                "mapWidth", 60, "mapHeight", 40, "version", 0)).andExpect(status().isForbidden());
        send(post(WAREHOUSES + "/" + hcm + "/deactivation"), coordinator, null).andExpect(status().isForbidden());
        send(post(WAREHOUSES + "/" + hcm + "/activation"), coordinator, null).andExpect(status().isForbidden());
        send(get(WAREHOUSES + "/" + hcm + "/layout"), coordinator, null).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT name FROM warehouse.warehouse WHERE id = ?", String.class, hcm))
                .isNotEqualTo("Renamed");
    }

    @Test
    @DisplayName("a role without warehouse-warehouses:READ is still refused; the grant is the coordinator's only")
    void otherRolesAreUnchanged() throws Exception {
        send(get(WAREHOUSES), as("ACCOUNTANT"), null).andExpect(status().isForbidden());
        send(get(WAREHOUSES + "/" + hcm), as("ACCOUNTANT"), null).andExpect(status().isForbidden());
        send(get(WAREHOUSES), as("ECOMMERCE_ADMIN"), null).andExpect(status().isForbidden());
        send(get(WAREHOUSES), as("SALES_STAFF"), null).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the coordinator releases an eligible order to a warehouse taken from the list; listing gives no release")
    void coordinatorReleasesToAListedWarehouse() throws Exception {
        UUID coordinatorId = tx.execute(s -> ReferenceRows.user(entityManager));
        String coordinator = TestTokens.bearer(decoder, coordinatorId, "ORDER_COORDINATOR");
        UUID order = confirmedPrintOrder(coordinatorId);

        JsonNode page = data(send(get(WAREHOUSES).param("status", "ACTIVE"), coordinator, null)
                .andExpect(status().isOk()));
        String chosen = null;
        for (JsonNode w : page.path("items")) {
            if ("HCM".equals(w.path("prefix").asText())) {
                chosen = w.path("id").asText();
            }
        }
        assertThat(chosen).isEqualTo(DemoData.WAREHOUSE_HCM.toString());

        // Sales also lists warehouses (V20261017000800) but holds no sales-orders:APPROVE: still no release.
        send(post("/api/v1/orders/" + order + "/release"), as("SALES_STAFF"), Map.of("warehouseId", chosen))
                .andExpect(status().isForbidden());
        send(post("/api/v1/orders/" + order + "/release"), as("ACCOUNTANT"), Map.of("warehouseId", chosen))
                .andExpect(status().isForbidden());

        JsonNode released = data(send(post("/api/v1/orders/" + order + "/release"), coordinator,
                Map.of("warehouseId", chosen)).andExpect(status().isOk()));
        assertThat(released.path("status").asText()).isEqualTo("IN_PRODUCTION");
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.customer_order WHERE id = ?", String.class, order))
                .isEqualTo("IN_PRODUCTION");
        assertThat(jdbc.queryForObject("SELECT warehouse_id FROM ordering.customer_order WHERE id = ?", UUID.class,
                order)).isEqualTo(hcm);
    }

    /**
     * A CONFIRMED, paid order of the demo customer with one print line whose design has an approved sample (BR-PRD-08),
     * as in {@code OrderReleaseIntegrationTest}: a checkout with a design line needs the whole design approval flow.
     */
    private UUID confirmedPrintOrder(UUID respondedBy) {
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        UUID sample = Identifiers.newId();
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, DemoData.CUSTOMER_ID, DemoData.PRODUCT_SOFA);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                                                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""",
                    snapshot, draft, CHECKSUM);
            jdbc.update("""
                    INSERT INTO production.sample_request (id, request_number, customer_id, blank_sku, design_snapshot_id,
                        quantity, status, sent_at, responded_at, responded_by)
                    VALUES (?, ?, ?, 'SOFA-3S-GREY', ?, 3, 'APPROVED', NOW(), NOW(), ?)""",
                    sample, "SR-CW-" + sample.toString().substring(24), DemoData.CUSTOMER_ID, snapshot, respondedBy);
            jdbc.update("""
                    INSERT INTO ordering.approved_sample (sample_request_id, customer_id, design_snapshot_id, blank_sku,
                                                          approved_at)
                    VALUES (?, ?, ?, 'SOFA-3S-GREY', NOW())""", sample, DemoData.CUSTOMER_ID, snapshot);
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at, paid_amount,
                                                         paid_in_full_at)
                    VALUES (?, ?, ?, ?, 'CONFIRMED', 20000, 'VND', NOW(), 0, NOW(), 20000, NOW())""",
                    order, "SO-20991229-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(1_000_000)), DemoData.CUSTOMER_ID, UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id,
                                                     design_checksum) VALUES (?, ?, 'SOFA-3S-GREY', 10, 2000, 'VND', ?, ?)""",
                    Identifiers.newId(), order, snapshot, CHECKSUM);
        });
        return order;
    }
}
