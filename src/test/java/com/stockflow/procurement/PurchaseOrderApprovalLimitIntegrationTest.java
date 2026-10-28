package com.stockflow.procurement;

import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.support.ApprovalLimitFixtures;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SupplierItemFixtures;
import com.stockflow.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-114: the approval limits of kltn-docs warehouse/02 §4 step 7 (owner decision POLIMIT2) and the force close of a
 * CONFIRMED order (§5), through the HTTP API with the real security chain, against Postgres and its constraints.
 *
 * <p>Limits are keyed by role in {@code procurement.approval_limits}; each test gives its people roles of their own
 * (nothing here depends on a role name, only on the limit a role has), so the permission a token carries and the limit
 * the data gives are independent, as they are in the product.</p>
 */
@SecuredIntegrationTest
class PurchaseOrderApprovalLimitIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired SupplierItemService catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private UUID supplier;
    private String sku;

    @BeforeEach
    void setUp() {
        supplier = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO procurement.suppliers (id, code, name, status, version, created_at)
                    VALUES (?, ?, 'Approval limit supplier', 'ACTIVE', 0, NOW())""",
                    supplier, "LIM-" + supplier.toString().substring(24).toUpperCase());
            sku = ReferenceRows.approvedProduct(entityManager).sku();
        });
        SupplierItemFixtures.list(catalog, supplier, sku);
    }

    // ------------------------------------------------------------------ people

    /** A user whose roles have exactly these VND limits (one role each); no limit at all for an empty list. */
    private UUID personWithLimits(long... limits) {
        return tx.execute(s -> {
            UUID user = ReferenceRows.user(entityManager);
            for (long limit : limits) {
                UUID role = ApprovalLimitFixtures.newRole(entityManager);
                ApprovalLimitFixtures.giveRole(entityManager, user, role);
                ApprovalLimitFixtures.setLimit(entityManager, role, limit);
            }
            return user;
        });
    }

    /** What a buyer carries: create and edit purchase orders (UPDATE), never APPROVE. */
    private String buyer(UUID user) {
        return TestTokens.bearer(decoder, user, "PROCUREMENT_STAFF");
    }

    /** SYSTEM_ADMIN holds every permission: the approval limit is then the only thing that decides. */
    private String approver(UUID user) {
        return TestTokens.bearer(decoder, user, "SYSTEM_ADMIN");
    }

    // ------------------------------------------------------------------ calls

    private ResultActions call(String path, String auth, String body) throws Exception {
        var request = post(path).header("Authorization", auth);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    /** A draft totalling {@code total} VND (one line, quantity 1), created by {@code buyer}; its id. */
    private UUID draft(String buyer, int total) throws Exception {
        String body = """
                {"supplierId":"%s","warehouseId":"%s","currency":"VND",
                 "lines":[{"sku":"%s","quantityOrdered":1,"unitPrice":%d}]}""".formatted(supplier,
                DemoData.WAREHOUSE_HCM, sku, total);
        return UUID.fromString(data(call("/api/v1/purchase-orders", buyer, body).andExpect(status().isCreated()))
                .path("purchaseOrderId").asText());
    }

    private ResultActions submit(UUID po, String auth) throws Exception {
        return call("/api/v1/purchase-orders/" + po + "/submission", auth, null);
    }

    private ResultActions approve(UUID po, String auth) throws Exception {
        return call("/api/v1/purchase-orders/" + po + "/approval", auth, null);
    }

    private Map<String, Object> row(UUID po) {
        return jdbc.queryForMap("""
                SELECT status, auto_approved, submitted_by, approved_by, active_revision_id, pending_revision_id
                  FROM procurement.purchase_orders WHERE id = ?""", po);
    }

    // ------------------------------------------------------------------ under the submitter's limit

    @Test
    @DisplayName("02 §4 step 7: within the submitter's own limit the order is approved at submit, with the reason and "
            + "the limit used on its approval record and timeline; it then confirms like any approved order")
    void underLimitSubmitIsApprovedAtOnce() throws Exception {
        UUID submitter = personWithLimits(100_000);
        String buyer = buyer(submitter);
        UUID po = draft(buyer, 60_000);

        submit(po, buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.autoApproved").value(true))
                .andExpect(jsonPath("$.data.approvedBy").value(submitter.toString()))
                .andExpect(jsonPath("$.data.submittedBy").value(submitter.toString()));

        Map<String, Object> row = row(po);
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("auto_approved")).isEqualTo(true);
        assertThat(row.get("approved_by")).isEqualTo(row.get("submitted_by")).isEqualTo(submitter);
        assertThat(row.get("active_revision_id")).as("approved means frozen as the active revision").isNotNull();
        assertThat(row.get("pending_revision_id")).isNull();

        String reason = "Auto-approved within limit: total 60000 VND, limit 100000 VND";
        assertThat(jdbc.queryForList("""
                SELECT a.decision || '|' || a.approver_id || '|' || a.reason FROM procurement.purchase_order_approvals a
                  JOIN procurement.purchase_order_revisions r ON r.id = a.po_revision_id WHERE r.po_id = ?""",
                String.class, po)).containsExactly("APPROVED|" + submitter + "|" + reason);
        assertThat(jdbc.queryForList("""
                SELECT action || ':' || from_status || '>' || to_status FROM procurement.purchase_order_events
                 WHERE po_id = ? ORDER BY created_at, action DESC""", String.class, po))
                .contains("SUBMITTED:DRAFT>PENDING_APPROVAL", "APPROVED:PENDING_APPROVAL>APPROVED");
        JsonNode payload = json.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM procurement.purchase_order_events WHERE po_id = ? AND action = 'APPROVED'""",
                String.class, po));
        assertThat(payload.path("autoApproved").asText()).isEqualTo("true");
        assertThat(payload.path("limit").asText()).isEqualTo("100000");
        assertThat(payload.path("total").asText()).isEqualTo("60000");
        assertThat(jdbc.queryForObject("""
                SELECT reason FROM procurement.purchase_order_events WHERE po_id = ? AND action = 'APPROVED'""",
                String.class, po)).isEqualTo(reason);

        // Nobody approves it again: it is not waiting.
        call("/api/v1/purchase-orders/" + po + "/approval", approver(personWithLimits(1_000_000)), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("INVALID_PURCHASE_ORDER_TRANSITION"));
    }

    @Test
    @DisplayName("the limit is inclusive, and the most generous of the submitter's roles counts")
    void boundaryAndHighestOfSeveralRoles() throws Exception {
        UUID twoRoles = personWithLimits(1_000, 40_000);
        UUID exact = personWithLimits(40_000);
        UUID oneDongShort = personWithLimits(39_999);

        UUID a = draft(buyer(twoRoles), 40_000);
        submit(a, buyer(twoRoles)).andExpect(jsonPath("$.data.status").value("APPROVED"));
        UUID b = draft(buyer(exact), 40_000);
        submit(b, buyer(exact)).andExpect(jsonPath("$.data.status").value("APPROVED"));
        UUID c = draft(buyer(oneDongShort), 40_000);
        submit(c, buyer(oneDongShort)).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.autoApproved").value(false));
    }

    // ------------------------------------------------------------------ above it

    @Test
    @DisplayName("above the submitter's limit, or with no limit at all, the order waits in PENDING_APPROVAL")
    void overLimitAndNoLimitWait() throws Exception {
        UUID small = personWithLimits(10_000);
        UUID none = personWithLimits();      // no role holds a limit: zero, however small the order
        UUID noRole = tx.execute(s -> ReferenceRows.user(entityManager));

        for (UUID person : List.of(small, none, noRole)) {
            UUID po = draft(buyer(person), person.equals(small) ? 10_001 : 1);
            submit(po, buyer(person)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                    .andExpect(jsonPath("$.data.autoApproved").value(false));
            assertThat(row(po).get("pending_revision_id")).isNotNull();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM procurement.purchase_order_approvals a
                      JOIN procurement.purchase_order_revisions r ON r.id = a.po_revision_id WHERE r.po_id = ?""",
                    Integer.class, po)).isZero();
        }
    }

    @Test
    @DisplayName("an expired, future-dated or other-currency limit is no limit")
    void limitMustBeInForceInTheOrdersCurrency() throws Exception {
        UUID person = tx.execute(s -> {
            UUID user = ReferenceRows.user(entityManager);
            for (String[] row : new String[][] {{"VND", "2000-01-01", "2020-01-01"}, {"VND", "2999-01-01", null},
                    {"USD", "2000-01-01", null}}) {
                UUID role = ApprovalLimitFixtures.newRole(entityManager);
                ApprovalLimitFixtures.giveRole(entityManager, user, role);
                jdbc.update("""
                        INSERT INTO procurement.approval_limits (id, role_id, currency, max_po_amount, effective_from,
                                                                 effective_to, version, created_at)
                        VALUES (?, ?, ?, 1000000000, ?::date, ?::date, 0, NOW())""", Identifiers.newId(), role, row[0],
                        row[1], row[2]);
            }
            return user;
        });
        UUID po = draft(buyer(person), 1_000);
        submit(po, buyer(person)).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
    }

    @Test
    @DisplayName("approve: refused above the approver's own limit (403 PO_APPROVAL_LIMIT_EXCEEDED) and by the submitter "
            + "(four-eyes); a bigger limit approves; the order stays pending meanwhile")
    void approveNeedsALimitThatCoversTheTotal() throws Exception {
        UUID submitter = personWithLimits(1_000);
        UUID po = draft(buyer(submitter), 50_000);
        submit(po, buyer(submitter)).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

        UUID tooSmall = personWithLimits(49_999);
        approve(po, approver(tooSmall)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PO_APPROVAL_LIMIT_EXCEEDED"));
        approve(po, approver(submitter)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SELF_APPROVAL_NOT_ALLOWED"));
        assertThat(row(po).get("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(row(po).get("approved_by")).isNull();

        UUID exact = personWithLimits(50_000);
        approve(po, approver(exact)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.autoApproved").value(false))
                .andExpect(jsonPath("$.data.approvedBy").value(exact.toString()));
        assertThat(row(po).get("auto_approved")).isEqualTo(false);
    }

    @Test
    @DisplayName("PO-NOLIMIT: an approver none of whose roles has a limit configured is bounded by the approve "
            + "permission alone and approves any total; a submitter without a limit still waits")
    void approverWithoutConfiguredLimitApproves() throws Exception {
        UUID submitter = personWithLimits();
        UUID po = draft(buyer(submitter), 2_000_000_000);   // above every seeded limit
        submit(po, buyer(submitter)).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

        UUID noLimit = personWithLimits();
        approve(po, approver(noLimit)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.autoApproved").value(false))
                .andExpect(jsonPath("$.data.approvedBy").value(noLimit.toString()));
    }

    @Test
    @DisplayName("a draft is never approved by hand, whatever the limit: DRAFT -> APPROVED exists only as the "
            + "under-limit submission")
    void aDraftIsNotApprovedByHand() throws Exception {
        UUID po = draft(buyer(personWithLimits()), 1_000);
        approve(po, approver(personWithLimits(1_000_000))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PURCHASE_ORDER_TRANSITION"));
        assertThat(row(po).get("status")).isEqualTo("DRAFT");
    }

    // ------------------------------------------------------------------ the database states it too

    @Test
    @DisplayName("ck_purchase_orders_four_eyes still refuses an approver who is the submitter - unless the order is "
            + "flagged auto_approved, and the flag is only for approver = submitter")
    void databaseConstraints() throws Exception {
        UUID submitter = personWithLimits(1_000);
        UUID po = draft(buyer(submitter), 5_000);
        submit(po, buyer(submitter)).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE procurement.purchase_orders SET status = 'APPROVED', approved_by = submitted_by,
                       approved_at = NOW(), active_revision_id = pending_revision_id WHERE id = ?""", po))
                .hasMessageContaining("ck_purchase_orders_four_eyes");
        UUID other = personWithLimits();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE procurement.purchase_orders SET status = 'APPROVED', approved_by = ?, auto_approved = TRUE,
                       approved_at = NOW(), active_revision_id = pending_revision_id WHERE id = ?""", other, po))
                .hasMessageContaining("ck_purchase_orders_auto_approved");
    }

    // ------------------------------------------------------------------ force close (02 §5)

    /** A CONFIRMED order with its one line, put there the way a receipt test does; its id. */
    private UUID confirmedOrder(String status) {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID confirmer = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    VALUES (?, ?, ?, ?, ?, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW())""",
                    po, "PO-FC-" + po.toString().substring(24), status, supplier, DemoData.WAREHOUSE_HCM, submitter,
                    confirmer, confirmer, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at)
                    SELECT ?, ?, ?, 1, id, 10, 1000, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                    Identifiers.newId(), po, revision, sku);
        });
        return po;
    }

    private String buyerToken() {
        return buyer(tx.execute(s -> ReferenceRows.user(entityManager)));
    }

    @Test
    @DisplayName("02 §5: a CONFIRMED order that will not be received is force-closed with a reason: CLOSED, "
            + "FORCE_CLOSE, FORCE_CLOSED on the timeline, its lines closed, and no goods receipt can start on it")
    void forceCloseOfAConfirmedOrder() throws Exception {
        UUID po = confirmedOrder("CONFIRMED");
        String buyer = buyerToken();

        call("/api/v1/purchase-orders/" + po + "/closure-short", buyer, "{\"reason\":\"   \"}")
                .andExpect(status().isBadRequest());
        call("/api/v1/purchase-orders/" + po + "/closure-short", buyer, "{}").andExpect(status().isBadRequest());
        assertThat(row(po).get("status")).as("nothing changed without a reason").isEqualTo("CONFIRMED");

        UUID closer = tx.execute(s -> ReferenceRows.user(entityManager));
        call("/api/v1/purchase-orders/" + po + "/closure-short", buyer(closer),
                "{\"reason\":\"supplier stopped production\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closeKind").value("FORCE_CLOSE"))
                .andExpect(jsonPath("$.data.closeReason").value("supplier stopped production"));

        Map<String, Object> closed = jdbc.queryForMap("""
                SELECT status, close_kind, close_reason, closed_by FROM procurement.purchase_orders WHERE id = ?""", po);
        assertThat(closed).containsEntry("status", "CLOSED").containsEntry("close_kind", "FORCE_CLOSE")
                .containsEntry("close_reason", "supplier stopped production").containsEntry("closed_by", closer);
        assertThat(jdbc.queryForList("SELECT status FROM procurement.purchase_order_lines WHERE po_id = ?",
                String.class, po)).containsExactly("CLOSED");
        assertThat(jdbc.queryForList("""
                SELECT action || ':' || from_status || '>' || to_status || ':' || reason
                  FROM procurement.purchase_order_events WHERE po_id = ? AND action = 'FORCE_CLOSED'""", String.class, po))
                .containsExactly("FORCE_CLOSED:CONFIRMED>CLOSED:supplier stopped production");

        // Closed is closed: no receipt starts on it, and it cannot be closed or cancelled again.
        call("/api/v1/goods-receipts", buyer, "{\"purchaseOrderId\":\"" + po + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_RECEIVABLE"));
        call("/api/v1/purchase-orders/" + po + "/closure-short", buyer, "{\"reason\":\"again\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PURCHASE_ORDER_TRANSITION"));
        call("/api/v1/purchase-orders/" + po + "/cancellation", buyer, "{\"reason\":\"too late\"}")
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("short-close of a PARTIALLY_RECEIVED order is unchanged (SHORT_CLOSE); an order still waiting for "
            + "approval or only approved is not closed short")
    void shortCloseUnchangedAndOtherStatesRefused() throws Exception {
        String buyer = buyerToken();
        UUID partial = confirmedOrder("PARTIALLY_RECEIVED");
        call("/api/v1/purchase-orders/" + partial + "/closure-short", buyer, "{\"reason\":\"short delivery\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closeKind").value("SHORT_CLOSE"));
        assertThat(jdbc.queryForObject("""
                SELECT action FROM procurement.purchase_order_events WHERE po_id = ? AND action LIKE '%CLOSED'""",
                String.class, partial)).isEqualTo("SHORT_CLOSED");

        UUID approved = confirmedOrder("APPROVED");
        jdbc.update("UPDATE procurement.purchase_orders SET confirmed_at = NULL, confirmed_by = NULL WHERE id = ?", approved);
        call("/api/v1/purchase-orders/" + approved + "/closure-short", buyer, "{\"reason\":\"no\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PURCHASE_ORDER_TRANSITION"));

        UUID submitter = personWithLimits();
        UUID waiting = draft(buyer(submitter), 1_000);
        submit(waiting, buyer(submitter)).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        call("/api/v1/purchase-orders/" + waiting + "/closure-short", buyer, "{\"reason\":\"no\"}")
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("force close needs the purchase-orders UPDATE permission: a warehouse clerk (read only) is 403, and "
            + "so is no token at all")
    void forceCloseNeedsPermission() throws Exception {
        UUID po = confirmedOrder("CONFIRMED");
        String clerk = TestTokens.bearer(decoder, tx.execute(s -> ReferenceRows.user(entityManager)), "WAREHOUSE_STAFF");

        call("/api/v1/purchase-orders/" + po + "/closure-short", clerk, "{\"reason\":\"not mine to close\"}")
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/purchase-orders/" + po + "/closure-short").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"anonymous\"}")).andExpect(status().isUnauthorized());
        assertThat(row(po).get("status")).isEqualTo("CONFIRMED");
    }
}
