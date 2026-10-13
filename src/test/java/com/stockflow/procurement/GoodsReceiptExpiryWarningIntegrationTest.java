package com.stockflow.procurement;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryControlService;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Docs 03 BR-06, the soft half (SCRUM-147, G5a-3 S7): a receipt line whose expiry date is within the
 * configured window (30 days) of the receiving date carries {@code EXPIRY_NEAR}, on the line and on the
 * receipt, without refusing anything. Through the real filter chain, as a warehouse clerk of HCM.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
// The same properties and mock as the other security integration tests, so the application context is shared.
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class GoodsReceiptExpiryWarningIntegrationTest {

    private static final String SOFA = "SOFA-3S-GREY";   // no lot, no expiry
    private static final String TABLE = "TABLE-OAK-160"; // lot and expiry tracked, QC
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired InventoryControlService inventoryControl;

    private String clerk;
    private UUID po;
    private UUID sofaLine;
    private UUID tableLine;
    private LocalDate today;
    private String suffix;

    @BeforeEach
    void setUp() {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT ?, ?, id, 0, NOW() FROM identity.app_role WHERE code = 'WAREHOUSE_STAFF'""",
                    Identifiers.newId(), user);
            jdbc.update("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    SELECT ?, ?, id, 0, NOW() FROM warehouse.warehouse WHERE prefix = 'HCM'""",
                    Identifiers.newId(), user);
        });
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of("WAREHOUSE_STAFF")).build());
        clerk = "Bearer " + value;
        today = LocalDate.now(BUSINESS_ZONE);
        suffix = UUID.randomUUID().toString().substring(0, 6);
        po = confirmedOrder(SOFA, 5, TABLE, 20);
        sofaLine = poLine(po, SOFA);
        tableLine = poLine(po, TABLE);
    }

    private UUID confirmedOrder(Object... skuAndQty) {
        UUID order = Identifiers.newId();
        UUID revision = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    order, "PO-EW-" + order.toString().substring(24), submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, order, submitter);
            for (int i = 0; i + 1 < skuAndQty.length; i += 2) {
                jdbc.update("""
                        INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                            ordered_qty, unit_price, version, created_at)
                        SELECT ?, ?, ?, ?, id, ?, 1000, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                        Identifiers.newId(), order, revision, i / 2 + 1, skuAndQty[i + 1], skuAndQty[i]);
            }
        });
        return order;
    }

    private UUID poLine(UUID order, String sku) {
        return jdbc.queryForObject("""
                SELECT l.id FROM procurement.purchase_order_lines l JOIN inventory.inventory_items i
                  ON i.id = l.inventory_item_id WHERE l.po_id = ? AND i.sku = ?""", UUID.class, order, sku);
    }

    private String draft() throws Exception {
        String body = mvc.perform(post("/api/v1/goods-receipts").header("Authorization", clerk)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purchaseOrderId\":\"" + po + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private String lot(String name, int quantity, LocalDate expiry) {
        return """
                {"purchaseOrderLineId":"%s","quantity":%d,"lotNumber":"%s-%s","expiryDate":"%s","locationCode":"HCM-RCV01"}"""
                .formatted(tableLine, quantity, name, suffix, expiry);
    }

    private String sofa(int quantity) {
        return """
                {"purchaseOrderLineId":"%s","quantity":%d,"locationCode":"HCM-RCV01"}""".formatted(sofaLine, quantity);
    }

    private String count(String receipt, String... lines) throws Exception {
        return mvc.perform(put("/api/v1/goods-receipts/" + receipt + "/lines").header("Authorization", clerk)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[" + String.join(",", lines) + "]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String fetch(String receipt) throws Exception {
        return mvc.perform(get("/api/v1/goods-receipts/" + receipt).header("Authorization", clerk))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<String> lineWarnings(String json, String lotName) {
        List<List<String>> found = JsonPath.read(json,
                "$.data.lines[?(@.lotNumber=='" + lotName + "-" + suffix + "')].warnings");
        assertThat(found).as("line of lot " + lotName).hasSize(1);
        return found.get(0);
    }

    private List<String> receiptWarnings(String json) {
        return JsonPath.read(json, "$.data.warnings");
    }

    @Test
    void theWindowIsThirtyDaysByDefault() {
        assertThat(inventoryControl.expiryWarningDays()).isEqualTo(30);
    }

    @Test
    void warnsOnLotsWithinThirtyDaysOfReceivingAndOnlyThose() throws Exception {
        String receipt = draft();

        String json = count(receipt, sofa(5), lot("A", 1, today.plusDays(10)), lot("B", 1, today.plusDays(30)),
                lot("C", 1, today.plusDays(31)), lot("D", 1, today.plusYears(1)), lot("E", 1, today.plusDays(1)));

        assertThat(lineWarnings(json, "A")).containsExactly("EXPIRY_NEAR");
        assertThat(lineWarnings(json, "B")).as("day 30 is inside the window").containsExactly("EXPIRY_NEAR");
        assertThat(lineWarnings(json, "C")).as("day 31 is outside it").isEmpty();
        assertThat(lineWarnings(json, "D")).isEmpty();
        assertThat(lineWarnings(json, "E")).containsExactly("EXPIRY_NEAR");
        assertThat(receiptWarnings(json)).containsExactly("EXPIRY_NEAR");
        // The line without an expiry date (the SKU is not expiry-tracked) never warns.
        List<List<String>> sofaWarnings = JsonPath.read(json, "$.data.lines[?(@.sku=='" + SOFA + "')].warnings");
        assertThat(sofaWarnings).containsExactly(List.of());
    }

    @Test
    void aReceiptWithoutNearExpiryHasNoWarning() throws Exception {
        String receipt = draft();

        String json = count(receipt, sofa(5), lot("A", 2, today.plusDays(31)), lot("B", 2, today.plusYears(2)));

        assertThat(receiptWarnings(json)).isEmpty();
        assertThat(lineWarnings(json, "A")).isEmpty();
        assertThat(lineWarnings(json, "B")).isEmpty();
    }

    @Test
    void aReceiptWithNoExpiryTrackedLineHasNoWarning() throws Exception {
        String json = count(draft(), sofa(5));

        assertThat(receiptWarnings(json)).isEmpty();
    }

    @Test
    void aFreshDraftWithNoCountHasNoWarning() throws Exception {
        String receipt = draft();

        assertThat(receiptWarnings(fetch(receipt))).isEmpty();
    }

    @Test
    void theWarningIsOnTheReceiptWhenReadAgainAndAfterConfirming() throws Exception {
        String receipt = draft();
        count(receipt, sofa(5), lot("A", 3, today.plusDays(10)));

        String read = fetch(receipt);
        assertThat(lineWarnings(read, "A")).containsExactly("EXPIRY_NEAR");
        assertThat(receiptWarnings(read)).containsExactly("EXPIRY_NEAR");

        // Non-blocking: the receipt is confirmed all the same, and keeps saying so.
        mvc.perform(post("/api/v1/goods-receipts/" + receipt + "/confirmation").header("Authorization", clerk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_QC"))
                .andExpect(jsonPath("$.data.warnings[0]").value("EXPIRY_NEAR"));
        assertThat(receiptWarnings(fetch(receipt))).containsExactly("EXPIRY_NEAR");
    }

    @Test
    void aCancelledReceiptCarriesNoWarning() throws Exception {
        String receipt = draft();
        count(receipt, lot("A", 3, today.plusDays(10)));

        mvc.perform(post("/api/v1/goods-receipts/" + receipt + "/cancellation").header("Authorization", clerk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    @Test
    void theListRowsAreUnchanged() throws Exception {
        String receipt = draft();
        count(receipt, lot("A", 3, today.plusDays(10)));

        mvc.perform(get("/api/v1/goods-receipts?purchaseOrderId=" + po).header("Authorization", clerk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(receipt))
                .andExpect(jsonPath("$.data.items[0].warnings").doesNotExist());
    }

    @Test
    void aWarningNeverBlocksTheCount() throws Exception {
        // The hard rule of BR-06 still holds: an expiry on or before the receiving date is refused...
        String receipt = draft();
        mvc.perform(put("/api/v1/goods-receipts/" + receipt + "/lines").header("Authorization", clerk)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[" + lot("A", 1, today) + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_LOT_DATA_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("EXPIRY_NOT_AFTER_RECEIPT"));
        // ...and one day later is accepted, with the warning.
        assertThat(receiptWarnings(count(receipt, lot("A", 1, today.plusDays(1))))).containsExactly("EXPIRY_NEAR");
    }
}
