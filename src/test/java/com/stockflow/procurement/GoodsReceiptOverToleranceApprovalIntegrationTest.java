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
 * SCRUM-477 (BR-RCP-001, owner decision OVER-RCPT) through the real filter chain, permission aspect, data scope and
 * database: a count beyond the supplier's over-receipt tolerance is no longer refused. Confirming it makes the receipt
 * wait (PENDING_APPROVAL) with nothing counted in; a warehouse manager - and nobody who lacks
 * {@code procurement-goods-receipts:APPROVE} - approves the whole count, or rejects the excess so that only the
 * tolerance is posted. Four eyes, the reason, and the database's own checks of the same rules.
 *
 * <p>The demo supplier GOVIET has a 5% tolerance: an order of 100 takes up to 105.</p>
 */
@SecuredIntegrationTest
class GoodsReceiptOverToleranceApprovalIntegrationTest {

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

    /** A CONFIRMED order of GOVIET into HCM; lines (sku, ordered). */
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
                    order, "PO-OT-" + order.toString().substring(24), submitter, approver, approver, revision);
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

    private String poStatus(UUID order) {
        return jdbc.queryForObject("SELECT status FROM procurement.purchase_orders WHERE id = ?", String.class, order);
    }

    /** Units of a SKU, lot or not, counted INBOUND at the receiving location. */
    private int inbound(String sku, String lot) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand), 0)::int FROM inventory.stock_item
                 WHERE sku = ? AND location_code = ? AND status = 'INBOUND' AND COALESCE(lot_number, '') = COALESCE(?, '')""",
                Integer.class, sku, RECEIVING, lot);
    }

    private String draft(UUID by, UUID order) throws Exception {
        String body = as(by, post("/api/v1/goods-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrderId\":\"" + order + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private String plain(UUID poLine, int quantity) {
        return """
                {"purchaseOrderLineId":"%s","quantity":%d,"locationCode":"%s"}""".formatted(poLine, quantity, RECEIVING);
    }

    private String lot(UUID poLine, int quantity, String lotNumber) {
        return """
                {"purchaseOrderLineId":"%s","quantity":%d,"lotNumber":"%s","expiryDate":"%s","locationCode":"%s"}"""
                .formatted(poLine, quantity, lotNumber, LocalDate.now().plusYears(1), RECEIVING);
    }

    private ResultActions count(UUID by, String receipt, String... lines) throws Exception {
        return as(by, put("/api/v1/goods-receipts/" + receipt + "/lines").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[" + String.join(",", lines) + "]}"));
    }

    private ResultActions confirm(UUID by, String receipt) throws Exception {
        return as(by, post("/api/v1/goods-receipts/" + receipt + "/confirmation"));
    }

    private ResultActions approve(UUID by, String receipt, String reason) throws Exception {
        return as(by, post("/api/v1/goods-receipts/" + receipt + "/approval")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions rejectExcess(UUID by, String receipt, String reason) throws Exception {
        return as(by, post("/api/v1/goods-receipts/" + receipt + "/excess-rejection")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
    }

    /** A receipt of 110 sofas against an order of 100 (limit 105), counted and confirmed by the clerk: it waits. */
    private String waitingReceipt(UUID order) throws Exception {
        String receipt = draft(clerk, order);
        count(clerk, receipt, plain(poLine(order, SOFA), 110)).andExpect(status().isOk());
        confirm(clerk, receipt).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        return receipt;
    }

    // ------------------------------------------------------------------ the wait

    @Test
    @DisplayName("a count past the tolerance is accepted and flagged; confirming it counts nothing in and waits for the manager")
    void overToleranceWaits() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        int before = inbound(SOFA, null);
        String receipt = draft(clerk, order);

        count(clerk, receipt, plain(poLine(order, SOFA), 110))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.lines[0].quantity").value(110))
                .andExpect(jsonPath("$.data.lines[0].warnings[0]").value("OVER_TOLERANCE"))
                .andExpect(jsonPath("$.data.warnings[0]").value("OVER_TOLERANCE"));

        confirm(clerk, receipt)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.confirmedAt").doesNotExist())
                .andExpect(jsonPath("$.data.approval.requestedBy").value(clerk.toString()))
                .andExpect(jsonPath("$.data.approval.decision").doesNotExist())
                .andExpect(jsonPath("$.data.warnings[0]").value("OVER_TOLERANCE"));

        assertThat(inbound(SOFA, null)).as("nothing is counted in while it waits").isEqualTo(before);
        assertThat(poStatus(order)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement.purchase_order_events WHERE po_id = ?",
                Integer.class, order)).as("the order saw no receipt").isZero();
        // It shows in the list for the manager to find.
        as(manager, get("/api/v1/goods-receipts?status=PENDING_APPROVAL&purchaseOrderId=" + order))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(receipt))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING_APPROVAL"));
    }

    @Test
    @DisplayName("within the tolerance (105 of 100) the count is posted at once, as before")
    void withinToleranceIsPostedAtOnce() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        int before = inbound(SOFA, null);
        String receipt = draft(clerk, order);
        count(clerk, receipt, plain(poLine(order, SOFA), 105)).andExpect(jsonPath("$.data.warnings.length()").value(0));

        confirm(clerk, receipt).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"))
                .andExpect(jsonPath("$.data.approval").doesNotExist());

        assertThat(inbound(SOFA, null)).isEqualTo(before + 105);
        assertThat(poStatus(order)).isEqualTo("RECEIVED");
    }

    // ------------------------------------------------------------------ the decision: permission, four eyes, reason

    @Test
    @DisplayName("permission: only a holder of procurement-goods-receipts:APPROVE decides - a clerk gets 403 on both decisions")
    void clerkCannotDecide() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String receipt = waitingReceipt(order);
        int before = inbound(SOFA, null);

        approve(clerk, receipt, "I say so").andExpect(status().isForbidden());
        rejectExcess(clerk, receipt, "I say so").andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT status FROM procurement.goods_receipts WHERE id = ?::uuid",
                String.class, receipt)).isEqualTo("PENDING_APPROVAL");
        assertThat(inbound(SOFA, null)).isEqualTo(before);
    }

    @Test
    @DisplayName("four eyes: a manager who counted and confirmed the receipt cannot decide it; another manager can")
    void fourEyes() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String receipt = draft(manager, order);
        count(manager, receipt, plain(poLine(order, SOFA), 110)).andExpect(status().isOk());
        confirm(manager, receipt).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

        approve(manager, receipt, "my own count").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_SELF_APPROVAL"));
        rejectExcess(manager, receipt, "my own count").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_SELF_APPROVAL"));

        approve(otherManager, receipt, "seen the delivery note").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"));
    }

    @Test
    @DisplayName("four eyes: whoever sent the count for approval is not its approver either, though another user started the draft")
    void theOneWhoAskedCannotDecide() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String receipt = draft(clerk, order);
        count(clerk, receipt, plain(poLine(order, SOFA), 110)).andExpect(status().isOk());
        confirm(manager, receipt).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

        approve(manager, receipt, "ok").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECEIPT_SELF_APPROVAL"));
        approve(otherManager, receipt, "ok").andExpect(status().isOk());
    }

    @Test
    @DisplayName("a decision needs a reason (400); a decided receipt is not decided again (409)")
    void reasonAndOnce() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String receipt = waitingReceipt(order);

        approve(manager, receipt, "   ").andExpect(status().isBadRequest());
        as(manager, post("/api/v1/goods-receipts/" + receipt + "/approval").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        rejectExcess(manager, receipt, "").andExpect(status().isBadRequest());

        approve(manager, receipt, "bonus carton").andExpect(status().isOk());
        approve(otherManager, receipt, "again").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RECEIPT_TRANSITION"));
        rejectExcess(otherManager, receipt, "again").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RECEIPT_TRANSITION"));
    }

    // ------------------------------------------------------------------ approve

    @Test
    @DisplayName("approve: the whole 110 is counted in - and only now - the order is RECEIVED, the reason and approver are kept")
    void approvePostsTheWholeCount() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String receipt = waitingReceipt(order);
        int before = inbound(SOFA, null);

        approve(manager, receipt, "supplier sent a bonus carton")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"))
                .andExpect(jsonPath("$.data.lines[0].quantity").value(110))
                .andExpect(jsonPath("$.data.confirmedBy").value(manager.toString()))
                .andExpect(jsonPath("$.data.approval.decision").value("APPROVED"))
                .andExpect(jsonPath("$.data.approval.decidedBy").value(manager.toString()))
                .andExpect(jsonPath("$.data.approval.reason").value("supplier sent a bonus carton"))
                .andExpect(jsonPath("$.data.approval.requestedBy").value(clerk.toString()))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));

        assertThat(inbound(SOFA, null)).isEqualTo(before + 110);
        assertThat(poStatus(order)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("""
                SELECT status || ':' || approval_decision || ':' || (approval_decided_by = confirmed_by)
                  FROM procurement.goods_receipts WHERE id = ?::uuid""", String.class, receipt))
                .isEqualTo("IN_PUTAWAY:APPROVED:true");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM procurement.purchase_order_events WHERE po_id = ? AND action = 'RECEIVED'""",
                Integer.class, order)).isEqualTo(1);
    }

    @Test
    @DisplayName("an approved excess counts against the line afterwards: the next receipt of it waits too")
    void approvedExcessCountsForLaterReceipts() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        approve(manager, waitingReceipt(order), "bonus").andExpect(status().isOk());

        // The order is RECEIVED now: a further receipt is refused outright (BR-01)...
        as(clerk, post("/api/v1/goods-receipts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrderId\":\"" + order + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_RECEIVABLE"));

        // ... and on an order that stays open, what was approved is held against the limit.
        UUID open = confirmedOrder(SOFA, 200);
        String first = draft(clerk, open);
        count(clerk, first, plain(poLine(open, SOFA), 120)).andExpect(status().isOk());
        confirm(clerk, first).andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"));   // 120 of 200: within
        String second = draft(clerk, open);
        count(clerk, second, plain(poLine(open, SOFA), 90)).andExpect(status().isOk())   // 120 + 90 = 210 = limit
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
        count(clerk, second, plain(poLine(open, SOFA), 91)).andExpect(status().isOk())   // 211 > 210
                .andExpect(jsonPath("$.data.warnings[0]").value("OVER_TOLERANCE"));
        confirm(clerk, second).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
    }

    @Test
    @DisplayName("what only drafts or waiting receipts hold is not received: two drafts of 60 on an order of 100 both post")
    void draftsAndWaitingReceiptsHoldNothing() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String a = draft(clerk, order);
        String b = draft(clerk, order);
        count(clerk, a, plain(poLine(order, SOFA), 60)).andExpect(jsonPath("$.data.warnings.length()").value(0));
        count(clerk, b, plain(poLine(order, SOFA), 60)).andExpect(jsonPath("$.data.warnings.length()").value(0));

        confirm(clerk, a).andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"));
        confirm(clerk, b).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));   // 60 + 60 > 105 now
    }

    // ------------------------------------------------------------------ reject the excess

    @Test
    @DisplayName("reject the excess: 110 counted, 105 posted; the order is RECEIVED; the reason and the cut are kept")
    void rejectCutsToTheTolerance() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String receipt = waitingReceipt(order);
        int before = inbound(SOFA, null);

        rejectExcess(manager, receipt, "5 cartons went back on the truck")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"))
                .andExpect(jsonPath("$.data.lines[0].quantity").value(105))
                .andExpect(jsonPath("$.data.approval.decision").value("EXCESS_REJECTED"))
                .andExpect(jsonPath("$.data.approval.reason").value("5 cartons went back on the truck"))
                .andExpect(jsonPath("$.data.confirmedBy").value(manager.toString()));

        assertThat(inbound(SOFA, null)).as("only up to the tolerance").isEqualTo(before + 105);
        assertThat(poStatus(order)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT received_qty::int FROM procurement.goods_receipt_lines WHERE receipt_id = ?::uuid",
                Integer.class, receipt)).isEqualTo(105);
    }

    @Test
    @DisplayName("reject the excess of a lot-tracked, QC line: the last lot is cut (70 + 50 -> 70 + 35) and the receipt goes to QC")
    void rejectCutsTheLastLot() throws Exception {
        UUID order = confirmedOrder(TABLE, 100);
        UUID line = poLine(order, TABLE);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String receipt = draft(clerk, order);
        count(clerk, receipt, lot(line, 70, "A-" + suffix), lot(line, 50, "B-" + suffix))
                .andExpect(jsonPath("$.data.lines[0].warnings[0]").value("OVER_TOLERANCE"))
                .andExpect(jsonPath("$.data.lines[1].warnings[0]").value("OVER_TOLERANCE"));
        confirm(clerk, receipt).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        assertThat(inbound(TABLE, "A-" + suffix) + inbound(TABLE, "B-" + suffix)).isZero();

        rejectExcess(manager, receipt, "over the order")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_QC"));

        assertThat(inbound(TABLE, "A-" + suffix)).isEqualTo(70);
        assertThat(inbound(TABLE, "B-" + suffix)).isEqualTo(35);
    }

    @Test
    @DisplayName("what posted receipts already hold narrows the allowance: 99 posted, 10 counted, 6 stay (99 + 6 = 105)")
    void rejectCountsReceiptsAlreadyPosted() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String first = draft(clerk, order);
        String second = draft(clerk, order);
        count(clerk, first, plain(poLine(order, SOFA), 99)).andExpect(status().isOk());
        count(clerk, second, plain(poLine(order, SOFA), 10)).andExpect(status().isOk());
        confirm(clerk, first).andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"));
        confirm(clerk, second).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));   // 99 + 10 = 109 > 105
        int before = inbound(SOFA, null);

        rejectExcess(manager, second, "the order is nearly full")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"))
                .andExpect(jsonPath("$.data.lines[0].quantity").value(6));

        assertThat(inbound(SOFA, null)).isEqualTo(before + 6);
        assertThat(poStatus(order)).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("nothing within the tolerance left when the manager decides: rejecting the excess cancels the receipt; "
            + "approving it is refused, the order takes no more goods (BR-01)")
    void rejectWithNothingLeftCancels() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String first = draft(clerk, order);
        String waiting = draft(clerk, order);
        String third = draft(clerk, order);
        count(clerk, first, plain(poLine(order, SOFA), 99)).andExpect(status().isOk());
        count(clerk, waiting, plain(poLine(order, SOFA), 10)).andExpect(status().isOk());
        count(clerk, third, plain(poLine(order, SOFA), 6)).andExpect(status().isOk());
        confirm(clerk, first).andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"));
        confirm(clerk, waiting).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));   // 109 > 105
        confirm(clerk, third).andExpect(jsonPath("$.data.status").value("IN_PUTAWAY"));           // 99 + 6 = 105: full
        assertThat(poStatus(order)).isEqualTo("RECEIVED");
        int before = inbound(SOFA, null);

        approve(manager, waiting, "bonus").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_RECEIVABLE"));
        rejectExcess(manager, waiting, "no room in the order")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.lines.length()").value(0))
                .andExpect(jsonPath("$.data.approval.decision").value("EXCESS_REJECTED"))
                .andExpect(jsonPath("$.data.approval.reason").value("no room in the order"));

        assertThat(inbound(SOFA, null)).isEqualTo(before);
    }

    @Test
    @DisplayName("a receipt waiting for approval can be cancelled; nothing was counted in, and it is then decided by nobody")
    void cancelWhileWaiting() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String receipt = waitingReceipt(order);
        int before = inbound(SOFA, null);

        as(clerk, post("/api/v1/goods-receipts/" + receipt + "/cancellation"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(inbound(SOFA, null)).isEqualTo(before);
        approve(manager, receipt, "too late").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a receipt waiting for approval is not edited, and it holds the order: the order is not cancelled under it")
    void waitingReceiptIsFrozenAndHoldsTheOrder() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String receipt = waitingReceipt(order);

        count(clerk, receipt, plain(poLine(order, SOFA), 100)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RECEIPT_TRANSITION"));
        confirm(clerk, receipt).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM procurement.goods_receipts WHERE po_id = ? AND status IN ('DRAFT', 'PENDING_APPROVAL'))""",
                Boolean.class, order)).isTrue();
    }

    // ------------------------------------------------------------------ the database states the rules again

    @Test
    @DisplayName("database: a receipt past the tolerance is not posted without the manager's decision (BR-04), with it it is")
    void databaseRefusesAnUnapprovedExcess() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String receipt = draft(clerk, order);
        count(clerk, receipt, plain(poLine(order, SOFA), 110)).andExpect(status().isOk());
        UUID id = UUID.fromString(receipt);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_by = ?, confirmed_at = NOW()
                 WHERE id = ?""", clerk, id)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("(BR-04)");

        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE procurement.goods_receipts SET status = 'PENDING_APPROVAL', approval_requested_by = ?,
                           approval_requested_at = NOW() WHERE id = ?""", clerk, id);
            jdbc.update("""
                    UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_by = ?, confirmed_at = NOW(),
                           approval_decision = 'APPROVED', approval_decided_by = ?, approval_decided_at = NOW(),
                           approval_reason = 'ok' WHERE id = ?""", manager, manager, id);
        });
        assertThat(jdbc.queryForObject("SELECT status FROM procurement.goods_receipts WHERE id = ?", String.class, id))
                .isEqualTo("IN_PUTAWAY");
    }

    @Test
    @DisplayName("database: four eyes and a reason on a decision are constraints, a waiting receipt's lines may be cut, a posted one's may not")
    void databaseConstraints() throws Exception {
        UUID order = confirmedOrder(SOFA, 100);
        String receipt = waitingReceipt(order);
        UUID id = UUID.fromString(receipt);

        // The decider is neither the receiver nor the one who asked.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_by = ?, confirmed_at = NOW(),
                       approval_decision = 'APPROVED', approval_decided_by = approval_requested_by,
                       approval_decided_at = NOW(), approval_reason = 'ok' WHERE id = ?""", clerk, id)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_goods_receipts_four_eyes");
        // A decision has a reason.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_by = ?, confirmed_at = NOW(),
                       approval_decision = 'APPROVED', approval_decided_by = ?, approval_decided_at = NOW(),
                       approval_reason = ' ' WHERE id = ?""", manager, manager, id)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_goods_receipts_approval_decided");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_by = ?, confirmed_at = NOW(),
                       approval_decision = 'APPROVED', approval_decided_by = ?, approval_decided_at = NOW(),
                       approval_reason = NULL WHERE id = ?""", manager, manager, id)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A receipt cannot wait without having asked.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.goods_receipts SET approval_requested_at = NULL, approval_requested_by = NULL
                 WHERE id = ?""", id)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_goods_receipts_approval_pending");

        // The lines of a waiting receipt may be cut (the rejection of the excess), a posted one's may not (BR-05).
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE procurement.goods_receipt_lines SET received_qty = 105 WHERE receipt_id = ?", id));
        approve(manager, receipt, "ok").andExpect(status().isOk());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE procurement.goods_receipt_lines SET received_qty = 1 WHERE receipt_id = ?", id)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("(BR-05)");
    }

    @Test
    @DisplayName("the permission is held by the warehouse manager alone among the warehouse roles; a staff role's grants are not touched")
    void grants() {
        assertThat(jdbc.queryForList("""
                SELECT r.code FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.code = 'procurement-goods-receipts:APPROVE' ORDER BY r.code""", String.class))
                .contains("WAREHOUSE_MANAGER").doesNotContain("WAREHOUSE_STAFF", "QC_STAFF", "PROCUREMENT_STAFF");
    }
}
