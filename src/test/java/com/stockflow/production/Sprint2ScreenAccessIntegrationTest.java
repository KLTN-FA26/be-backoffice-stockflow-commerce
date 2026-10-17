package com.stockflow.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.production.internal.service.SampleProductionProgress;
import com.stockflow.support.ConfirmedDesigns;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reads the Sprint 2 screens were missing (FE live integration, 14/10/2026), over HTTP with the real filter chain,
 * the role grants of the database (V20261015000800) and the warehouse scope:
 * <ul>
 *   <li>Sales lists warehouses to submit a sample to, and nothing more of the warehouse module;</li>
 *   <li>Sales lists one customer's confirmed designs and their preview - never another customer's, and a customer
 *       or a role without {@code design-snapshots:READ} gets none;</li>
 *   <li>the warehouse manager lists print subcontractors to split work to, read-only;</li>
 *   <li>QC reads the PACKING areas of its own production order without the warehouse map;</li>
 *   <li>the warehouse sees what is left to scan and a wrong source scan is refused.</li>
 * </ul>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false"})
class Sprint2ScreenAccessIntegrationTest {

    private static final String SAMPLES = "/api/v1/production/sample-requests";
    private static final String ORDERS = "/api/v1/production/orders";
    private static final String SNAPSHOTS = "/api/v1/design-snapshots";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    /** Local storage in tests hands out no browser link; the URL itself is the storage module's, not this test's. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean FileStorage storage;
    @Autowired SampleProductionProgress progress;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private ProductionFixtures fixtures;
    private UUID hcm;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.doAnswer(call -> "http://objects.test/" + call.getArgument(0)).when(storage)
                .presignedDownloadUrl(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        fixtures = new ProductionFixtures(storage, jdbc, tx, entityManager, progress);
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
    }

    private String token(UUID userId, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(userId.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private String staff(String role) {
        return token(fixtures.user(), role);
    }

    /** A warehouse-bound role assigned to HCM. */
    private String inHcm(String role) {
        UUID id = fixtures.user();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                VALUES (?, ?, ?, 0, NOW())""", Identifiers.newId(), id, hcm));
        return token(id, role);
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

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    /** A sample round of the demo customer submitted to HCM; its SAMPLE production order. */
    private UUID submittedSample(String sales) throws Exception {
        ConfirmedDesigns.stockBlanks(jdbc, tx, 10);
        var created = send(post(SAMPLES), sales, Map.of("customerId", DemoData.CUSTOMER_ID,
                "designSnapshotId", fixtures.confirmedDesign().snapshotId(), "blankSku", ProductionFixtures.BLANK,
                "quantity", 2)).andExpect(status().isCreated());
        UUID sample = UUID.fromString(data(created).path("id").asText());
        return UUID.fromString(data(send(post(SAMPLES + "/" + sample + "/submission"), sales,
                Map.of("warehouseId", hcm)).andExpect(status().isOk())).path("productionOrder").path("id").asText());
    }

    @Test
    @DisplayName("G3: Sales lists the warehouses a sample goes to, and may not create or edit one")
    void salesReadsWarehouses() throws Exception {
        String sales = staff("SALES_STAFF");
        JsonNode page = data(send(get("/api/v1/warehouses"), sales, null).andExpect(status().isOk()));
        assertThat(texts(page.path("items"), "id")).contains(hcm.toString());
        send(get("/api/v1/warehouses/" + hcm), sales, null).andExpect(status().isOk());
        send(post("/api/v1/warehouses/" + hcm + "/deactivation"), sales, null).andExpect(status().isForbidden());
        send(get("/api/v1/warehouses/" + hcm + "/layout"), sales, null).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("G2: Sales lists one customer's confirmed designs and their preview, never another customer's")
    void salesPicksTheCustomersConfirmedDesign() throws Exception {
        UUID userA = fixtures.user();
        UUID customerA = fixtures.customerOf(userA);
        UUID customerB = fixtures.customerOf(fixtures.user());
        UUID ofA = ConfirmedDesigns.confirmedWithPreview(storage, jdbc, tx, entityManager, customerA).snapshotId();
        UUID ofB = ConfirmedDesigns.confirmedWithPreview(storage, jdbc, tx, entityManager, customerB).snapshotId();
        // A draft of A that was never confirmed is not a choice.
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", Identifiers.newId(), customerA, DemoData.PRODUCT_SOFA));
        String sales = staff("SALES_STAFF");

        JsonNode mine = data(send(get(SNAPSHOTS).param("customerId", customerA.toString()), sales, null)
                .andExpect(status().isOk()));
        assertThat(texts(mine.path("items"), "snapshotId")).containsExactly(ofA.toString());
        assertThat(texts(mine.path("items"), "customerId")).containsOnly(customerA.toString());
        assertThat(mine.path("items").get(0).path("productId").asText()).isEqualTo(DemoData.PRODUCT_SOFA.toString());
        assertThat(texts(mine.path("items").get(0).path("artifacts"), "role"))
                .containsExactlyInAnyOrder("CUSTOMER_PREVIEW", "PRINT_READY");
        assertThat(mine.toString()).as("no storage key or link in the list").doesNotContain("storageKey", "url");

        // Narrowed by the blank a quote line names: a SKU of the design's product finds it, another product's does not.
        assertThat(texts(data(send(get(SNAPSHOTS).param("customerId", customerA.toString())
                .param("sku", ProductionFixtures.BLANK), sales, null)).path("items"), "snapshotId"))
                .containsExactly(ofA.toString());
        assertThat(data(send(get(SNAPSHOTS).param("customerId", customerA.toString()).param("sku", "TABLE-OAK-160"),
                sales, null)).path("items").size()).isZero();

        send(get(SNAPSHOTS + "/" + ofA + "/preview").param("customerId", customerA.toString()), sales, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.snapshotId").value(ofA.toString()))
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.url").isNotEmpty());
        // B's design asked for as A's: the same 404 as a snapshot that does not exist.
        send(get(SNAPSHOTS + "/" + ofB + "/preview").param("customerId", customerA.toString()), sales, null)
                .andExpect(status().isNotFound());
        send(get(SNAPSHOTS + "/" + UUID.randomUUID() + "/preview").param("customerId", customerA.toString()), sales,
                null).andExpect(status().isNotFound());
        send(get(SNAPSHOTS).param("customerId", UUID.randomUUID().toString()), sales, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
        send(get(SNAPSHOTS), sales, null).andExpect(status().isBadRequest());

        // The customer holds design-snapshots:READ since V20260903000100; this is a back-office list all the same.
        String portalA = token(userA, "CUSTOMER");
        send(get(SNAPSHOTS).param("customerId", customerA.toString()), portalA, null).andExpect(status().isForbidden());
        send(get(SNAPSHOTS + "/" + ofA + "/preview").param("customerId", customerA.toString()), portalA, null)
                .andExpect(status().isForbidden());
        for (String role : List.of("WAREHOUSE_STAFF", "QC_STAFF", "PRODUCTION_STAFF")) {
            send(get(SNAPSHOTS).param("customerId", customerA.toString()), staff(role), null)
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("G4: the warehouse manager lists the print subcontractors to split to, and edits no supplier")
    void managerListsPrintSubcontractors() throws Exception {
        String manager = staff("WAREHOUSE_MANAGER");
        JsonNode printers = data(send(get("/api/v1/suppliers").param("printSubcontractor", "true")
                .param("status", "ACTIVE"), manager, null).andExpect(status().isOk()));
        assertThat(texts(printers.path("items"), "code")).contains("INNHANH");
        assertThat(texts(printers.path("items"), "printSubcontractor")).containsOnly("true");
        JsonNode others = data(send(get("/api/v1/suppliers").param("printSubcontractor", "false"), manager, null)
                .andExpect(status().isOk()));
        assertThat(texts(others.path("items"), "printSubcontractor")).containsOnly("false");
        String supplier = printers.path("items").get(0).path("supplierId").asText();
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/suppliers/" + supplier),
                manager, null).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("G5: QC reads the PACKING areas of its own order, scoped to its warehouse, without the warehouse map")
    void qcReadsPackingAreasOfItsOrder() throws Exception {
        UUID order = submittedSample(staff("SALES_STAFF"));
        String qc = inHcm("QC_STAFF");
        send(get(ORDERS + "/" + order + "/packing-locations"), qc, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("HCM-PACK01"))
                .andExpect(jsonPath("$.data.length()").value(1));
        // QC assigned to no warehouse.
        send(get(ORDERS + "/" + order + "/packing-locations"), staff("QC_STAFF"), null)
                .andExpect(status().isForbidden());
        send(get(ORDERS + "/" + order + "/packing-locations"), inHcm("WAREHOUSE_STAFF"), null)
                .andExpect(status().isForbidden());
        // QC holds no warehouse-locations:READ: the area list above is all it needs.
        send(get("/api/v1/warehouses/" + hcm + "/layout"), qc, null).andExpect(status().isForbidden());
        // A SAMPLE is checked against its own print file: there is no approved sample to show.
        send(get(ORDERS + "/" + order + "/approved-sample"), qc, null).andExpect(status().isNotFound());
        send(get(ORDERS + "/" + UUID.randomUUID() + "/approved-sample"), qc, null).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("SCRUM-409/410: what is left to scan, and a scan of the wrong place or item moves nothing")
    void scanConfirmationOverHttp() throws Exception {
        UUID order = submittedSample(staff("SALES_STAFF"));
        send(post(ORDERS + "/" + order + "/prepress"), inHcm("PRODUCTION_STAFF"), Map.of("decision", "PASS"))
                .andExpect(status().isOk());
        String storekeeper = inHcm("WAREHOUSE_STAFF");
        String issues = ORDERS + "/" + order + "/material-issues";

        JsonNode pending = data(send(get(issues + "/pending"), storekeeper, null).andExpect(status().isOk()));
        assertThat(pending.path("required").asInt()).isEqualTo(2);
        assertThat(pending.path("outstanding").asInt()).isEqualTo(2);
        JsonNode hold = pending.path("holds").get(0);
        assertThat(hold.path("sku").asText()).isEqualTo(ProductionFixtures.BLANK);
        String reservation = hold.path("reservationId").asText();
        String location = hold.path("locationCode").asText();

        send(post(issues), storekeeper, Map.of("requestId", UUID.randomUUID(), "toLocation", "HCM-PRD01",
                "reservationId", reservation, "scannedLocation", "HCM-PACK01", "scannedCode", ProductionFixtures.BLANK))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ISSUE_LOCATION_MISMATCH"));
        send(post(issues), storekeeper, Map.of("requestId", UUID.randomUUID(), "toLocation", "HCM-PRD01",
                "reservationId", reservation, "scannedLocation", location, "scannedCode", "TABLE-OAK-160"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ISSUE_ITEM_MISMATCH"));
        send(get(issues + "/pending"), storekeeper, null).andExpect(jsonPath("$.data.outstanding").value(2));

        send(post(issues), storekeeper, Map.of("requestId", UUID.randomUUID(), "toLocation", "HCM-PRD01",
                "reservationId", reservation, "scannedLocation", location, "scannedCode", ProductionFixtures.BLANK))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.issue.reservationId").value(reservation));
        // A clerk not assigned to HCM.
        send(get(issues + "/pending"), staff("WAREHOUSE_STAFF"), null).andExpect(status().isForbidden());
    }
}
