package com.stockflow.procurement;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-478 (BR-RCP-002, owner decisions BLIND-RCPT, NOPO-SUPPLIER, NOPO-ATTACH, NOPO-REASON) through the real filter
 * chain, permission aspect, data scope and database: goods that arrived with no purchase order are received with a
 * required supplier and a free-text reason; confirming never posts - a warehouse manager (and nobody without
 * {@code procurement-goods-receipts:APPROVE}), who is neither the receiver nor whoever sent it, approves it (stock is
 * counted in only then) or rejects it (the whole receipt is cancelled). A PO attached afterwards is a reference only.
 */
@SecuredIntegrationTest
class GoodsReceiptWithoutPoIntegrationTest {

    private static final String SOFA = "SOFA-3S-GREY";   // 2 steps: no QC, no lot
    private static final String TABLE = "TABLE-OAK-160"; // 3 steps: QC, lot and expiry
    private static final String RECEIVING = "HCM-RCV01";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc mvc;
    private UUID clerk;
    private UUID manager;
    private UUID otherManager;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        clerk = account("WAREHOUSE_STAFF");
        manager = account("WAREHOUSE_MANAGER");
        otherManager = account("WAREHOUSE_MANAGER");
    }

    /** A real account with this role, assigned to the HCM warehouse. */
    private UUID account(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            jdbc.update("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM warehouse.warehouse WHERE prefix = 'HCM'""", user);
        });
        return user;
    }

    private static Role roleOf(UUID user, UUID clerk) {
        return user.equals(clerk) ? Role.WAREHOUSE_STAFF : Role.WAREHOUSE_MANAGER;
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.asRole(roleOf(user, clerk), user, request);
    }

    // ------------------------------------------------------------------ fixtures

    private UUID hcm() {
        return jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
    }

    private UUID supplier() {
        return jdbc.queryForObject("SELECT id FROM procurement.suppliers WHERE code = 'GOVIET'", UUID.class);
    }

    private UUID otherSupplier() {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO procurement.suppliers (id, code, name) VALUES (?, ?, ?)", id,
                "NP" + id.toString().substring(24, 32).toUpperCase(), "Other supplier"));
        return id;
    }

    /** A CONFIRMED order of the supplier into HCM, one SOFA line of 10; returns its id. */
    private UUID confirmedOrder(UUID supplier) {
        UUID order = Identifiers.newId();
        UUID revision = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', ?, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM warehouse.warehouse w WHERE w.prefix = 'HCM'""",
                    order, "PO-NP-" + order.toString().substring(24), supplier, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, order, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at)
                    SELECT ?, ?, ?, 1, id, 10, 1000, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                    Identifiers.newId(), order, revision, SOFA);
        });
        return order;
    }

    private String poSnapshot(UUID order) {
        return jdbc.queryForObject("""
                SELECT p.status || ':' || COALESCE((SELECT string_agg(l.status, ',') FROM procurement.purchase_order_lines l
                                                      WHERE l.po_id = p.id), '') || ':' ||
                       (SELECT count(*) FROM procurement.purchase_order_events e WHERE e.po_id = p.id)
                  FROM procurement.purchase_orders p WHERE p.id = ?""", String.class, order);
    }

    /** Units of a SKU, lot or not, counted INBOUND at the receiving location. */
    private int inbound(String sku, String lot) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand), 0)::int FROM inventory.stock_item
                 WHERE sku = ? AND location_code = ? AND status = 'INBOUND' AND COALESCE(lot_number, '') = COALESCE(?, '')""",
                Integer.class, sku, RECEIVING, lot);
    }

    private String createBody(UUID supplier, String reason) {
        return """
                {"supplierId":"%s","warehouseId":"%s","reason":"%s","deliveryNote":"DN-NP"}""".formatted(supplier, hcm(), reason);
    }

    private String draft(UUID by, UUID supplier, String reason) throws Exception {
        String body = as(by, post("/api/v1/goods-receipts/without-po").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(supplier, reason)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private String sofa(int quantity) {
        return """
                {"sku":"%s","quantity":%d,"locationCode":"%s"}""".formatted(SOFA, quantity, RECEIVING);
    }

    private ResultActions count(UUID by, String receipt, String... lines) throws Exception {
        return as(by, put("/api/v1/goods-receipts/" + receipt + "/lines").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[" + String.join(",", lines) + "]}"));
    }

    private ResultActions confirm(UUID by, String receipt) throws Exception {
        return as(by, post("/api/v1/goods-receipts/" + receipt + "/confirmation"));
    }

    private ResultActions decide(UUID by, String receipt, String path, String reason) throws Exception {
        return as(by, post("/api/v1/goods-receipts/" + receipt + "/" + path)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions reference(UUID by, String receipt, UUID order) throws Exception {
        return as(by, put("/api/v1/goods-receipts/" + receipt + "/referenced-purchase-order")
                .contentType(MediaType.APPLICATION_JSON).content("{\"purchaseOrderId\":\"" + order + "\"}"));
    }

    /** A receipt without a PO of 4 sofas, counted and sent for approval by the clerk. */
    private String waiting() throws Exception {
        String receipt = draft(clerk, supplier(), "gift from the supplier");
        count(clerk, receipt, sofa(4)).andExpect(status().isOk());
        confirm(clerk, receipt).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        return receipt;
    }

    // ------------------------------------------------------------------ create and count

    @Test
    @DisplayName("created for a supplier with a reason, without a PO; its lines name the item by SKU")
    void createsAndCounts() throws Exception {
        String receipt = draft(clerk, supplier(), "  sample sent with the last order  ");
        as(manager, get("/api/v1/goods-receipts/" + receipt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.withoutPurchaseOrder").value(true))
                .andExpect(jsonPath("$.data.purchaseOrderId").doesNotExist())
                .andExpect(jsonPath("$.data.supplierId").value(supplier().toString()))
                .andExpect(jsonPath("$.data.supplierName").value("Xưởng gỗ Việt"))
                .andExpect(jsonPath("$.data.blindReason").value("sample sent with the last order"))
                .andExpect(jsonPath("$.data.warehouseCode").value("HCM"));

        count(clerk, receipt, sofa(3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].sku").value(SOFA))
                .andExpect(jsonPath("$.data.lines[0].purchaseOrderLineId").doesNotExist())
                .andExpect(jsonPath("$.data.lines[0].quantity").value(3))
                .andExpect(jsonPath("$.data.lines[0].qcRequired").value(false))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
        // Recounting keeps the one line per item and lot.
        count(clerk, receipt, sofa(5)).andExpect(jsonPath("$.data.lines[0].quantity").value(5))
                .andExpect(jsonPath("$.data.lines.length()").value(1));
    }

    @Test
    @DisplayName("the reason and the supplier are required; an unknown supplier is 404")
    void createRules() throws Exception {
        as(clerk, post("/api/v1/goods-receipts/without-po").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(supplier(), "   "))).andExpect(status().isBadRequest());
        as(clerk, post("/api/v1/goods-receipts/without-po").contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseId\":\"" + hcm() + "\",\"reason\":\"gift\"}")).andExpect(status().isBadRequest());
        as(clerk, post("/api/v1/goods-receipts/without-po").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(UUID.randomUUID(), "gift"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SUPPLIER_NOT_FOUND"));
    }

    @Test
    @DisplayName("each count refusal is on its field: a PO line on a receipt without a PO, no SKU, an unknown SKU, a lot-tracked item without lot")
    void countRefusals() throws Exception {
        String receipt = draft(clerk, supplier(), "gift");
        count(clerk, receipt,
                "{\"purchaseOrderLineId\":\"" + UUID.randomUUID() + "\",\"quantity\":1,\"locationCode\":\"" + RECEIVING + "\"}",
                "{\"quantity\":1,\"locationCode\":\"" + RECEIVING + "\"}",
                "{\"sku\":\"NO-SUCH-SKU\",\"quantity\":1,\"locationCode\":\"" + RECEIVING + "\"}",
                "{\"sku\":\"" + TABLE + "\",\"quantity\":1,\"locationCode\":\"" + RECEIVING + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors[*].field", org.hamcrest.Matchers.contains(
                        "lines[0].purchaseOrderLineId", "lines[1].sku", "lines[2].sku", "lines[3].lotNumber",
                        "lines[3].expiryDate")));
        // The same item and lot twice.
        count(clerk, receipt, sofa(1), sofa(2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[1].lotNumber"));
    }

    @Test
    @DisplayName("a receipt against a PO still counts by PO line: a SKU alone is refused on its PO line field")
    void aPoReceiptStillNeedsItsPoLine() throws Exception {
        UUID order = confirmedOrder(supplier());
        String receipt = JsonPath.read(as(clerk, post("/api/v1/goods-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrderId\":\"" + order + "\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
        count(clerk, receipt, sofa(1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_LINE_NOT_ON_PO"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].purchaseOrderLineId"));
    }

    // ------------------------------------------------------------------ approval

    @Test
    @DisplayName("confirming never posts: the receipt waits, nothing is counted in; a clerk cannot approve (403)")
    void confirmWaits() throws Exception {
        int before = inbound(SOFA, null);
        String receipt = waiting();

        as(clerk, get("/api/v1/goods-receipts/" + receipt))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.confirmedAt").doesNotExist())
                .andExpect(jsonPath("$.data.approval.requestedBy").value(clerk.toString()));
        assertThat(inbound(SOFA, null)).isEqualTo(before);

        decide(clerk, receipt, "approval", "I say so").andExpect(status().isForbidden());
        decide(clerk, receipt, "rejection", "I say so").andExpect(status().isForbidden());
        assertThat(inbound(SOFA, null)).isEqualTo(before);
    }

    @Test
    @DisplayName("the manager approves: stock is counted in only now, the receipt goes to putaway, the reason is kept")
    void approvePosts() throws Exception {
        int before = inbound(SOFA, null);
        String receipt = waiting();

        decide(manager, receipt, "approval", "gift checked against the delivery note")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"))
                .andExpect(jsonPath("$.data.confirmedBy").value(manager.toString()))
                .andExpect(jsonPath("$.data.approval.decision").value("APPROVED"))
                .andExpect(jsonPath("$.data.approval.reason").value("gift checked against the delivery note"))
                .andExpect(jsonPath("$.data.withoutPurchaseOrder").value(true));

        assertThat(inbound(SOFA, null)).isEqualTo(before + 4);
        // The lot it opened knows its receipt and its supplier, and no order.
        assertThat(jdbc.queryForMap("""
                SELECT l.supplier_id, l.purchase_order_id FROM inventory.stock_lot l
                  JOIN procurement.goods_receipts r ON r.id = l.goods_receipt_id WHERE r.id = ?::uuid""", receipt))
                .containsEntry("supplier_id", supplier()).containsEntry("purchase_order_id", null);
        decide(otherManager, receipt, "approval", "again").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RECEIPT_TRANSITION"));
    }

    @Test
    @DisplayName("the manager rejects: the whole receipt is cancelled with the reason, nothing is counted in; rejecting needs APPROVE too")
    void rejectCancels() throws Exception {
        int before = inbound(SOFA, null);
        String receipt = waiting();

        decide(manager, receipt, "rejection", "not our goods")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.approval.decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.approval.reason").value("not our goods"))
                .andExpect(jsonPath("$.data.approval.decidedBy").value(manager.toString()))
                .andExpect(jsonPath("$.data.confirmedAt").doesNotExist());

        assertThat(inbound(SOFA, null)).isEqualTo(before);
        decide(otherManager, receipt, "approval", "too late").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("four eyes: whoever counted it or sent it for approval cannot decide it; a reason is required (400)")
    void fourEyesAndReason() throws Exception {
        String receipt = draft(manager, supplier(), "urgent");
        count(manager, receipt, sofa(2)).andExpect(status().isOk());
        confirm(otherManager, receipt).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

        decide(manager, receipt, "approval", "mine").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_SELF_APPROVAL"));
        decide(otherManager, receipt, "rejection", "mine").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_SELF_APPROVAL"));
        decide(account("WAREHOUSE_MANAGER"), receipt, "approval", "   ").andExpect(status().isBadRequest());
        decide(account("WAREHOUSE_MANAGER"), receipt, "approval", "seen").andExpect(status().isOk());
    }

    @Test
    @DisplayName("the lot, expiry and QC rules of a normal receipt apply: a QC item goes to QC after approval")
    void lotExpiryAndQcApply() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String receipt = draft(clerk, supplier(), "gift");
        String tableLine = """
                {"sku":"%s","quantity":3,"lotNumber":"NP-%s","expiryDate":"%s","locationCode":"%s"}"""
                .formatted(TABLE, suffix, LocalDate.now().plusYears(1), RECEIVING);
        count(clerk, receipt, tableLine).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].qcRequired").value(true));
        confirm(clerk, receipt).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        assertThat(inbound(TABLE, "NP-" + suffix)).isZero();

        decide(manager, receipt, "approval", "gift").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_QC"));
        assertThat(inbound(TABLE, "NP-" + suffix)).isEqualTo(3);
    }

    @Test
    @DisplayName("a receipt waiting for approval can be cancelled; the list filter tells receipts without a PO apart")
    void cancelAndList() throws Exception {
        String receipt = waiting();
        UUID order = confirmedOrder(supplier());
        String withPo = JsonPath.read(as(clerk, post("/api/v1/goods-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrderId\":\"" + order + "\"}")).andReturn().getResponse().getContentAsString(),
                "$.data.id");

        as(manager, get("/api/v1/goods-receipts?withoutPo=true&status=PENDING_APPROVAL&size=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id=='" + receipt + "')].withoutPurchaseOrder").value(true))
                .andExpect(jsonPath("$.data.items[?(@.id=='" + receipt + "')].supplierName").value("Xưởng gỗ Việt"))
                .andExpect(jsonPath("$.data.items[?(@.id=='" + withPo + "')]").isEmpty());
        as(manager, get("/api/v1/goods-receipts?withoutPo=false&purchaseOrderId=" + order))
                .andExpect(jsonPath("$.data.items[0].id").value(withPo))
                .andExpect(jsonPath("$.data.items[0].withoutPurchaseOrder").value(false));
        as(manager, get("/api/v1/goods-receipts?purchaseOrderId=" + order + "&withoutPo=true"))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        as(clerk, post("/api/v1/goods-receipts/" + receipt + "/cancellation"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    // ------------------------------------------------------------------ the PO attached afterwards

    @Test
    @DisplayName("NOPO-ATTACH: the PO is a reference only - after posting it is stored, and the order is not changed at all")
    void referenceChangesNothing() throws Exception {
        UUID order = confirmedOrder(supplier());
        String snapshot = poSnapshot(order);
        String receipt = waiting();
        decide(manager, receipt, "approval", "gift").andExpect(status().isOk());
        int received = inbound(SOFA, null);

        reference(clerk, receipt, order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referencedPurchaseOrderId").value(order.toString()))
                .andExpect(jsonPath("$.data.referencedPurchaseOrderNumber").value(
                        jdbc.queryForObject("SELECT po_number FROM procurement.purchase_orders WHERE id = ?", String.class, order)))
                .andExpect(jsonPath("$.data.purchaseOrderId").doesNotExist())
                .andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"));

        assertThat(poSnapshot(order)).as("no received quantity, no status, no event").isEqualTo(snapshot);
        assertThat(inbound(SOFA, null)).isEqualTo(received);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM procurement.goods_receipts WHERE po_id = ?""", Integer.class, order))
                .as("nothing is received against it").isZero();
        as(manager, get("/api/v1/goods-receipts/" + receipt))
                .andExpect(jsonPath("$.data.referencedPurchaseOrderId").value(order.toString()));
    }

    @Test
    @DisplayName("the PO must exist (404) and be the receipt's supplier's (409); not for a receipt against a PO or a cancelled one")
    void referenceRules() throws Exception {
        String receipt = draft(clerk, supplier(), "gift");
        reference(clerk, receipt, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_FOUND"));
        reference(clerk, receipt, confirmedOrder(otherSupplier())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_REFERENCE_SUPPLIER_MISMATCH"));
        UUID order = confirmedOrder(supplier());
        reference(clerk, receipt, order).andExpect(status().isOk());
        // Replaceable, a draft takes it too.
        UUID later = confirmedOrder(supplier());
        reference(clerk, receipt, later).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referencedPurchaseOrderId").value(later.toString()));

        String withPo = JsonPath.read(as(clerk, post("/api/v1/goods-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrderId\":\"" + order + "\"}")).andReturn().getResponse().getContentAsString(),
                "$.data.id");
        reference(clerk, withPo, later).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RECEIPT_TRANSITION"));

        as(clerk, post("/api/v1/goods-receipts/" + receipt + "/cancellation")).andExpect(status().isOk());
        reference(clerk, receipt, order).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ the database states the rules again

    @Test
    @DisplayName("database: a receipt without a PO needs its supplier and reason, and is not posted without the manager's approval")
    void databaseRules() throws Exception {
        String receipt = draft(clerk, supplier(), "gift");
        count(clerk, receipt, sofa(2)).andExpect(status().isOk());
        UUID id = UUID.fromString(receipt);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_by = ?, confirmed_at = NOW()
                 WHERE id = ?""", clerk, id)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_goods_receipts_blind_approved");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE procurement.goods_receipts SET blind_reason = ' ' WHERE id = ?", id)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_goods_receipts_blind");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE procurement.goods_receipts SET supplier_id = NULL WHERE id = ?", id)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_goods_receipts_blind");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE procurement.goods_receipts SET referenced_po_id = ? WHERE id = ?",
                confirmedOrder(otherSupplier()), id)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("another supplier");
    }

    @Test
    @DisplayName("database: a receipt against a PO keeps a PO line on every line, one without has none, and no PO receipt has a supplier or reason")
    void databaseLineRules() throws Exception {
        String blind = draft(clerk, supplier(), "gift");
        UUID order = confirmedOrder(supplier());
        UUID poLine = jdbc.queryForObject("SELECT id FROM procurement.purchase_order_lines WHERE po_id = ?", UUID.class, order);
        UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, SOFA);
        UUID location = jdbc.queryForObject("SELECT id FROM warehouse.storage_location WHERE location_code = ?", UUID.class,
                RECEIVING);
        String withPo = JsonPath.read(as(clerk, post("/api/v1/goods-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrderId\":\"" + order + "\"}")).andReturn().getResponse().getContentAsString(),
                "$.data.id");

        // A PO line on a receipt without a PO.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id,
                                                             received_qty) VALUES (?, ?::uuid, ?, ?, ?, 1)""",
                Identifiers.newId(), blind, poLine, item, location)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("exactly when");
        // No PO line on a receipt against a PO.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id,
                                                             received_qty) VALUES (?, ?::uuid, NULL, ?, ?, 1)""",
                Identifiers.newId(), withPo, item, location)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("exactly when");
        // A supplier and a reason belong to a receipt without a PO.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE procurement.goods_receipts SET supplier_id = ?, blind_reason = 'x' WHERE id = ?::uuid",
                supplier(), withPo)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_goods_receipts_blind");
        // Rejecting a receipt cancels it, and only one without a PO is rejected as a whole.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.goods_receipts SET status = 'CANCELLED', approval_requested_by = ?,
                       approval_requested_at = NOW(), approval_decision = 'REJECTED', approval_decided_by = ?,
                       approval_decided_at = NOW(), approval_reason = 'no' WHERE id = ?::uuid""", clerk, manager, withPo)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_goods_receipts_rejected");
        // One line per item and lot on a receipt without a PO.
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id,
                                                             received_qty) VALUES (?, ?::uuid, NULL, ?, ?, 1)""",
                Identifiers.newId(), blind, item, location));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipt_lines (id, receipt_id, po_line_id, inventory_item_id, location_id,
                                                             received_qty) VALUES (?, ?::uuid, NULL, ?, ?, 2)""",
                Identifiers.newId(), blind, item, location)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_goods_receipt_lines_blind_lot");
    }

    @Test
    @DisplayName("the permissions are the existing ones: a clerk creates, counts, sends and attaches; only APPROVE decides")
    void permissions() {
        assertThat(jdbc.queryForList("""
                SELECT r.code FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.code = 'procurement-goods-receipts:APPROVE' ORDER BY r.code""", String.class))
                .contains("WAREHOUSE_MANAGER").doesNotContain("WAREHOUSE_STAFF");
    }
}
