package com.stockflow.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.support.ApprovalLimitFixtures;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SupplierItemFixtures;
import com.stockflow.support.TestTokens;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-117 (SCRUM-131) through the HTTP API with the real security chain, against Postgres and its triggers: an
 * APPROVED or CONFIRMED standard order is amended and the amendment is decided like the order was - automatically
 * within the requester's limit, otherwise by someone else whose limit covers the new total - or rejected, withdrawn,
 * refused while a goods receipt is in progress, and withdrawn by the first receipt posted.
 *
 * <p>Orders are created, submitted and approved through the API. CONFIRMED is then set in SQL, as the receiving tests
 * do: confirming through the API sends the order to the supplier, which needs a configured buyer identity and a
 * notification transport this class has nothing to say about.</p>
 *
 * <p>That a SUBCONTRACT supplement survives the first receipt is {@code SubcontractPurchasingIntegrationTest}'s, next
 * to the fixtures a subcontract order needs.</p>
 *
 * <p>The idempotency filter is switched on here (the test profile has it off) to replay a request with the same
 * {@code Idempotency-Key}; that costs this class an application context of its own.</p>
 */
@SecuredIntegrationTest
@TestPropertySource(properties = "stockflow.idempotency.enabled=true")
class PurchaseOrderAmendmentIntegrationTest {

    private static final String SOFA = "SOFA-3S-GREY";   // no QC, no lot: a receipt of it is posted at once
    private static final String RECEIVING = "HCM-RCV01";
    private static final String ORDERS = "/api/v1/purchase-orders/";
    private static final LocalDate EXPECTED = LocalDate.now().plusDays(10);

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired SupplierItemService catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private UUID supplier;
    private String other;

    @BeforeEach
    void setUp() {
        supplier = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO procurement.suppliers (id, code, name, status, version, created_at)
                    VALUES (?, ?, 'Amendment supplier', 'ACTIVE', 0, NOW())""",
                    supplier, "AMD-" + supplier.toString().substring(24).toUpperCase());
            other = ReferenceRows.approvedProduct(entityManager).sku();
        });
        SupplierItemFixtures.list(catalog, supplier, SOFA);
        SupplierItemFixtures.list(catalog, supplier, other);
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

    /** Creates and edits purchase orders, so may ask for and withdraw an amendment (UPDATE); never APPROVE. */
    private String buyer(UUID user) {
        return TestTokens.bearer(decoder, user, "PROCUREMENT_STAFF");
    }

    /** Every permission: the approval limit is then the only thing that decides. */
    private String approver(UUID user) {
        return TestTokens.bearer(decoder, user, "SYSTEM_ADMIN");
    }

    /** A warehouse clerk of HCM, who counts and posts goods receipts. */
    private record Clerk(UUID id, String token) {
    }

    private Clerk clerk() {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = 'WAREHOUSE_STAFF'""",
                    user);
            jdbc.update("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM warehouse.warehouse WHERE prefix = 'HCM'""", user);
        });
        return new Clerk(user, TestTokens.bearer(decoder, user, "WAREHOUSE_STAFF"));
    }

    // ------------------------------------------------------------------ calls

    private ResultActions call(String path, String auth, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).header("Authorization", auth);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions amend(UUID po, String auth, String body) throws Exception {
        return call(ORDERS + po + "/amendments", auth, body);
    }

    private ResultActions approve(UUID po, String auth) throws Exception {
        return call(ORDERS + po + "/amendment/approval", auth, null);
    }

    private ResultActions reject(UUID po, String auth, String reason) throws Exception {
        return call(ORDERS + po + "/amendment/rejection", auth, "{\"reason\":\"" + reason + "\"}");
    }

    private ResultActions withdraw(UUID po, String auth, String reason) throws Exception {
        return call(ORDERS + po + "/amendment/withdrawal", auth, "{\"reason\":\"" + reason + "\"}");
    }

    // ------------------------------------------------------------------ orders

    /**
     * Line 1: 10 sofas at 50,000; line 2: 5 of the other SKU at 100,000 - 1,000,000 in all, no tax. Created and
     * submitted by a buyer with no limit, approved by someone else: APPROVED. Its id.
     */
    private UUID approvedOrder() throws Exception {
        UUID buyer = personWithLimits();
        String body = """
                {"supplierId":"%s","warehouseId":"%s","currency":"VND","expectedAt":"%s",
                 "lines":[{"sku":"%s","quantityOrdered":10,"unitPrice":50000},
                          {"sku":"%s","quantityOrdered":5,"unitPrice":100000}]}""".formatted(supplier,
                DemoData.WAREHOUSE_HCM, EXPECTED, SOFA, other);
        UUID po = UUID.fromString(data(call("/api/v1/purchase-orders", buyer(buyer), body)
                .andExpect(status().isCreated())).path("purchaseOrderId").asText());
        call(ORDERS + po + "/submission", buyer(buyer), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        call(ORDERS + po + "/approval", approver(personWithLimits()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        return po;
    }

    /** {@link #approvedOrder}, sent and confirmed by the supplier (reference SUP-1): CONFIRMED. */
    private UUID confirmedOrder() throws Exception {
        UUID po = approvedOrder();
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.purchase_orders
                   SET status = 'CONFIRMED', confirmed_at = NOW(), confirmed_by = approved_by,
                       supplier_confirmation_status = 'CONFIRMED', supplier_responded_at = NOW(),
                       supplier_reference = 'SUP-1', version = version + 1
                 WHERE id = ?""", po));
        return po;
    }

    private UUID lineOf(UUID po, String sku) {
        return jdbc.queryForObject("""
                SELECT l.id FROM procurement.purchase_order_lines l
                  JOIN inventory.inventory_items i ON i.id = l.inventory_item_id
                 WHERE l.po_id = ? AND i.sku = ?""", UUID.class, po, sku);
    }

    private long version(UUID po) {
        return jdbc.queryForObject("SELECT version FROM procurement.purchase_orders WHERE id = ?", Long.class, po);
    }

    /**
     * An amendment body: the version as it is now, the expected date and note given, and every line kept as
     * (line id, quantity) pairs.
     */
    private String amendment(UUID po, LocalDate expectedDate, String reason, Object... lineAndQuantity) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i + 1 < lineAndQuantity.length; i += 2) {
            lines.add("{\"lineId\":\"%s\",\"quantityOrdered\":%d}".formatted(lineAndQuantity[i], lineAndQuantity[i + 1]));
        }
        return """
                {"version":%d,"reason":"%s","expectedDate":"%s","lines":[%s]}""".formatted(version(po), reason,
                expectedDate, String.join(",", lines));
    }

    /** Sofas 10 to 6, the other line 5 to 3: 600,000 in all, delivered three days later. */
    private String lessOfBoth(UUID po) {
        return amendment(po, EXPECTED.plusDays(3), "Supplier can deliver less", lineOf(po, SOFA), 6,
                lineOf(po, other), 3);
    }

    private Map<String, Object> row(UUID po) {
        return jdbc.queryForMap("""
                SELECT status, revision_no, total_amount, active_revision_id, pending_revision_id,
                       supplier_confirmation_status, supplier_reference, supplier_responded_at
                  FROM procurement.purchase_orders WHERE id = ?""", po);
    }

    private BigDecimal ordered(UUID line) {
        return jdbc.queryForObject("SELECT ordered_qty FROM procurement.purchase_order_lines WHERE id = ?",
                BigDecimal.class, line);
    }

    private List<String> eventsOf(UUID revision) {
        return jdbc.queryForList("""
                SELECT action FROM procurement.purchase_order_events WHERE po_revision_id = ?
                 ORDER BY created_at, id""", String.class, revision);
    }

    private List<Map<String, Object>> decisionsOf(UUID revision) {
        return jdbc.queryForList("""
                SELECT decision, approver_id, reason, limit_amount, limit_holder_id
                  FROM procurement.purchase_order_approvals WHERE po_revision_id = ? ORDER BY step_no""", revision);
    }

    private int amendmentRevisions(UUID po) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM procurement.purchase_order_revisions WHERE po_id = ? AND kind = 'AMENDMENT'""",
                Integer.class, po);
    }

    // ------------------------------------------------------------------ receipts

    private String draftReceipt(Clerk clerk, UUID po) throws Exception {
        return data(call("/api/v1/goods-receipts", clerk.token(), "{\"purchaseOrderId\":\"" + po + "\"}")
                .andExpect(status().isCreated())).path("id").asText();
    }

    private ResultActions count(Clerk clerk, String receipt, UUID poLine, int quantity) throws Exception {
        return mvc.perform(put("/api/v1/goods-receipts/" + receipt + "/lines").header("Authorization", clerk.token())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"lines":[{"purchaseOrderLineId":"%s","quantity":%d,"locationCode":"%s"}]}"""
                        .formatted(poLine, quantity, RECEIVING)));
    }

    private ResultActions postReceipt(Clerk clerk, String receipt) throws Exception {
        return call("/api/v1/goods-receipts/" + receipt + "/confirmation", clerk.token(), null);
    }

    // ------------------------------------------------------------------ deciding

    @Test
    @DisplayName("within the requester's limit the amendment is approved at once: AUTO_APPROVED with the limit, "
            + "REVISED then AUTO_APPROVED on the timeline, the lines and the total rewritten, the revision active")
    void withinLimitIsAutoApproved() throws Exception {
        UUID po = approvedOrder();
        UUID before = (UUID) row(po).get("active_revision_id");
        UUID requester = personWithLimits(1_000_000);

        amend(po, buyer(requester), lessOfBoth(po)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.expectedAt").value(EXPECTED.plusDays(3).toString()));

        Map<String, Object> row = row(po);
        UUID revision = (UUID) row.get("active_revision_id");
        assertThat(revision).isNotEqualTo(before);
        assertThat(row.get("pending_revision_id")).isNull();
        assertThat(((Number) row.get("revision_no")).longValue()).isEqualTo(1);
        assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("600000");
        assertThat(jdbc.queryForObject("SELECT kind FROM procurement.purchase_order_revisions WHERE id = ?",
                String.class, revision)).isEqualTo("AMENDMENT");
        assertThat(ordered(lineOf(po, SOFA))).isEqualByComparingTo("6");
        assertThat(ordered(lineOf(po, other))).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT po_revision_id FROM procurement.purchase_order_lines WHERE id = ?",
                UUID.class, lineOf(po, SOFA))).isEqualTo(revision);

        assertThat(decisionsOf(revision)).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("decision", "AUTO_APPROVED").containsEntry("approver_id", requester)
                    .containsEntry("limit_holder_id", requester);
            assertThat((BigDecimal) d.get("limit_amount")).isEqualByComparingTo("1000000");
        });
        assertThat(eventsOf(revision)).containsExactly("REVISED", "AUTO_APPROVED");
        JsonNode revised = json.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM procurement.purchase_order_events WHERE po_revision_id = ? AND action = 'REVISED'""",
                String.class, revision));
        assertThat(revised.path("line1.quantityBefore").asText()).isEqualTo("10");
        assertThat(revised.path("line1.quantityAfter").asText()).isEqualTo("6");
    }

    @Test
    @DisplayName("above the requester's limit it waits: the requester cannot approve it (four eyes), an approver whose "
            + "limit is below the new total cannot either, one with no limit configured does")
    void aboveLimitWaitsForSomeoneElse() throws Exception {
        UUID po = approvedOrder();
        UUID requester = personWithLimits(100_000);

        amend(po, buyer(requester), lessOfBoth(po)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        UUID revision = (UUID) row(po).get("pending_revision_id");
        assertThat(revision).as("waiting").isNotNull();
        assertThat(ordered(lineOf(po, SOFA))).as("nothing applied yet").isEqualByComparingTo("10");

        approve(po, approver(requester)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SELF_APPROVAL_NOT_ALLOWED"));
        approve(po, approver(personWithLimits(599_999))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PO_APPROVAL_LIMIT_EXCEEDED"));
        assertThat(row(po).get("pending_revision_id")).isEqualTo(revision);

        UUID noLimit = personWithLimits();
        approve(po, approver(noLimit)).andExpect(status().isOk());
        assertThat(row(po)).containsEntry("active_revision_id", revision).containsEntry("pending_revision_id", null);
        assertThat(ordered(lineOf(po, SOFA))).isEqualByComparingTo("6");
        assertThat(decisionsOf(revision)).singleElement()
                .satisfies(d -> assertThat(d).containsEntry("decision", "APPROVED").containsEntry("approver_id", noLimit));
        assertThat(eventsOf(revision)).containsExactly("REVISED", "APPROVED");
    }

    @Test
    @DisplayName("D3: approving an amendment of a CONFIRMED order sends the supplier's answer back to PENDING - they "
            + "agreed to the previous revision - and the earlier answer stays on the timeline; the order stays CONFIRMED")
    void confirmedOrderSupplierResponseBackToPending() throws Exception {
        UUID po = confirmedOrder();
        UUID requester = personWithLimits(1_000_000);

        amend(po, buyer(requester), lessOfBoth(po)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.supplierConfirmationStatus").value("PENDING"));

        Map<String, Object> row = row(po);
        assertThat(row).containsEntry("status", "CONFIRMED").containsEntry("supplier_confirmation_status", "PENDING")
                .containsEntry("supplier_reference", null).containsEntry("supplier_responded_at", null);
        JsonNode approved = json.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM procurement.purchase_order_events
                 WHERE po_revision_id = ? AND action = 'AUTO_APPROVED'""", String.class, row.get("active_revision_id")));
        assertThat(approved.path("supplierConfirmationBefore").asText()).isEqualTo("CONFIRMED");
        assertThat(approved.path("supplierReferenceBefore").asText()).isEqualTo("SUP-1");
    }

    @Test
    @DisplayName("a rejected amendment leaves the active revision as it was, and a later one is decided by a person "
            + "even within the requester's limit (SCRUM-124 rule 3)")
    void rejectKeepsActiveRevisionAndBlocksLaterAutoApproval() throws Exception {
        UUID po = approvedOrder();
        UUID active = (UUID) row(po).get("active_revision_id");
        amend(po, buyer(personWithLimits(100_000)), lessOfBoth(po)).andExpect(status().isOk());
        UUID revision = (UUID) row(po).get("pending_revision_id");
        UUID rejecter = personWithLimits();

        call(ORDERS + po + "/amendment/rejection", approver(rejecter), "{}").andExpect(status().isBadRequest());
        reject(po, approver(rejecter), "Price was agreed for the full quantity").andExpect(status().isOk());

        assertThat(row(po)).containsEntry("active_revision_id", active).containsEntry("pending_revision_id", null);
        assertThat(ordered(lineOf(po, SOFA))).isEqualByComparingTo("10");
        assertThat(decisionsOf(revision)).singleElement().satisfies(d -> assertThat(d)
                .containsEntry("decision", "REJECTED").containsEntry("approver_id", rejecter)
                .containsEntry("reason", "Price was agreed for the full quantity"));
        assertThat(eventsOf(revision)).containsExactly("REVISED", "REJECTED");

        amend(po, buyer(personWithLimits(1_000_000)), lessOfBoth(po)).andExpect(status().isOk());
        assertThat(row(po).get("pending_revision_id")).as("rejected once: a person decides").isNotNull();
    }

    @Test
    @DisplayName("the requester withdraws the amendment with a reason: WITHDRAWN recorded, nothing waits any more")
    void withdrawByRequester() throws Exception {
        UUID po = approvedOrder();
        UUID requester = personWithLimits(100_000);
        amend(po, buyer(requester), lessOfBoth(po)).andExpect(status().isOk());
        UUID revision = (UUID) row(po).get("pending_revision_id");

        withdraw(po, buyer(requester), "Supplier found the stock").andExpect(status().isOk());

        assertThat(row(po).get("pending_revision_id")).isNull();
        assertThat(decisionsOf(revision)).singleElement().satisfies(d -> assertThat(d)
                .containsEntry("decision", "WITHDRAWN").containsEntry("approver_id", requester));
        assertThat(eventsOf(revision)).containsExactly("REVISED", "WITHDRAWN");
        approve(po, approver(personWithLimits())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PO_AMENDMENT_NOT_PENDING"));
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("D5: no amendment is asked for, and none approved, while a goods receipt of the order is being counted")
    void refusedWhileReceiptInProgress() throws Exception {
        Clerk clerk = clerk();
        UUID counting = confirmedOrder();
        draftReceipt(clerk, counting);
        amend(counting, buyer(personWithLimits(100_000)), lessOfBoth(counting)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PO_AMENDMENT_NOT_ALLOWED"));
        assertThat(amendmentRevisions(counting)).as("a refusal leaves no revision").isZero();

        UUID asked = confirmedOrder();
        amend(asked, buyer(personWithLimits(100_000)), lessOfBoth(asked)).andExpect(status().isOk());
        draftReceipt(clerk, asked);
        approve(asked, approver(personWithLimits())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PO_AMENDMENT_NOT_ALLOWED"));
        assertThat(ordered(lineOf(asked, SOFA))).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("an amendment against a version read before a change is refused with OPTIMISTIC_LOCK")
    void staleVersion() throws Exception {
        UUID po = approvedOrder();
        String stale = lessOfBoth(po).replace("\"version\":" + version(po), "\"version\":" + (version(po) - 1));

        amend(po, buyer(personWithLimits()), stale).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        assertThat(amendmentRevisions(po)).isZero();
    }

    @Test
    @DisplayName("an amendment that keeps every line, the date and the note as they are is refused: 400 PO_AMENDMENT_NO_CHANGE")
    void nothingChanged() throws Exception {
        UUID po = approvedOrder();

        amend(po, buyer(personWithLimits()), amendment(po, EXPECTED, "Same again", lineOf(po, SOFA), 10,
                lineOf(po, other), 5)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PO_AMENDMENT_NO_CHANGE"));
        assertThat(amendmentRevisions(po)).isZero();
    }

    @Test
    @DisplayName("the same request sent twice with one Idempotency-Key makes one revision and answers the same body; "
            + "a second request without the key is refused while the first waits")
    void sameRequestTwice() throws Exception {
        UUID po = approvedOrder();
        String requester = buyer(personWithLimits(100_000));
        String body = lessOfBoth(po);
        String key = UUID.randomUUID().toString();

        List<String> answers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            answers.add(mvc.perform(post(ORDERS + po + "/amendments").header("Authorization", requester)
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        }

        assertThat(answers.get(1)).isEqualTo(answers.get(0));
        assertThat(amendmentRevisions(po)).isEqualTo(1);
        amend(po, requester, lessOfBoth(po)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PO_AMENDMENT_ALREADY_PENDING"));
        assertThat(amendmentRevisions(po)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ receiving after an amendment

    @Test
    @DisplayName("a line the amendment removed is CANCELLED: it takes no receipt, and the order is RECEIVED once the "
            + "line it kept is received in full")
    void removedLineTakesNoReceiptAndOrderStillReceives() throws Exception {
        UUID po = confirmedOrder();
        amend(po, buyer(personWithLimits(1_000_000)), amendment(po, EXPECTED, "Only the sofas", lineOf(po, SOFA), 10))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM procurement.purchase_order_lines WHERE id = ?",
                String.class, lineOf(po, other))).isEqualTo("CANCELLED");
        assertThat((BigDecimal) row(po).get("total_amount")).isEqualByComparingTo("500000");

        Clerk clerk = clerk();
        String receipt = draftReceipt(clerk, po);
        count(clerk, receipt, lineOf(po, other), 5).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PO_LINE_NOT_OPEN"));
        count(clerk, receipt, lineOf(po, SOFA), 10).andExpect(status().isOk());
        postReceipt(clerk, receipt).andExpect(status().isOk());

        assertThat(row(po).get("status")).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("D5: the first receipt posted withdraws the amendment still waiting - WITHDRAWN by whoever posted it, "
            + "naming the receipt - and approving it afterwards finds nothing waiting")
    void firstPostedReceiptWithdrawsPendingAmendment() throws Exception {
        UUID po = confirmedOrder();
        amend(po, buyer(personWithLimits(100_000)), lessOfBoth(po)).andExpect(status().isOk());
        UUID revision = (UUID) row(po).get("pending_revision_id");

        Clerk clerk = clerk();
        String receipt = draftReceipt(clerk, po);
        count(clerk, receipt, lineOf(po, SOFA), 4).andExpect(status().isOk());
        postReceipt(clerk, receipt).andExpect(status().isOk());

        assertThat(row(po)).containsEntry("status", "PARTIALLY_RECEIVED").containsEntry("pending_revision_id", null);
        assertThat(ordered(lineOf(po, SOFA))).as("received against the revision in force").isEqualByComparingTo("10");
        String number = jdbc.queryForObject("SELECT receipt_number FROM procurement.goods_receipts WHERE id = ?",
                String.class, UUID.fromString(receipt));
        String reason = "Goods received (receipt " + number + ")";
        assertThat(decisionsOf(revision)).singleElement().satisfies(d -> assertThat(d)
                .containsEntry("decision", "WITHDRAWN").containsEntry("approver_id", clerk.id())
                .containsEntry("reason", reason));
        assertThat(eventsOf(revision)).containsExactly("REVISED", "WITHDRAWN");
        assertThat(jdbc.queryForObject("""
                SELECT reason FROM procurement.purchase_order_events WHERE po_revision_id = ? AND action = 'WITHDRAWN'""",
                String.class, revision)).isEqualTo(reason);

        approve(po, approver(personWithLimits())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PO_AMENDMENT_NOT_PENDING"));
    }

    // ------------------------------------------------------------------ ending the order

    @Test
    @DisplayName("cancelling an amended order keeps the total it had: the line the amendment removed does not count "
            + "again once the cancellation settles every line")
    void cancellingAnAmendedOrderKeepsItsTotal() throws Exception {
        UUID po = approvedOrder();
        amend(po, buyer(personWithLimits(1_000_000)), amendment(po, EXPECTED, "Only six sofas", lineOf(po, SOFA), 6))
                .andExpect(status().isOk());
        assertThat((BigDecimal) row(po).get("total_amount")).isEqualByComparingTo("300000");

        call(ORDERS + po + "/cancellation", buyer(personWithLimits()), "{\"reason\":\"Customer order dropped\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(row(po)).containsEntry("status", "CANCELLED");
        assertThat((BigDecimal) row(po).get("total_amount")).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("cancelling an order withdraws the amendment waiting on it, by whoever cancels, before the status "
            + "changes")
    void cancellingWithdrawsPendingAmendment() throws Exception {
        UUID po = approvedOrder();
        amend(po, buyer(personWithLimits(100_000)), lessOfBoth(po)).andExpect(status().isOk());
        UUID revision = (UUID) row(po).get("pending_revision_id");
        UUID canceller = personWithLimits();

        call(ORDERS + po + "/cancellation", buyer(canceller), "{\"reason\":\"Customer order dropped\"}")
                .andExpect(status().isOk());

        assertThat(row(po)).containsEntry("status", "CANCELLED").containsEntry("pending_revision_id", null);
        assertThat(decisionsOf(revision)).singleElement().satisfies(d -> assertThat(d)
                .containsEntry("decision", "WITHDRAWN").containsEntry("approver_id", canceller)
                .containsEntry("reason", "Order cancelled"));
        assertThat(eventsOf(revision)).containsExactly("REVISED", "WITHDRAWN");
        assertThat(jdbc.queryForList("""
                SELECT action FROM procurement.purchase_order_events WHERE po_id = ? AND action IN ('WITHDRAWN', 'CANCELLED')
                 ORDER BY created_at, id""", String.class, po)).containsExactly("WITHDRAWN", "CANCELLED");
    }

    // ------------------------------------------------------------------ permissions

    @Test
    @DisplayName("a buyer (UPDATE, no APPROVE) may ask for and withdraw an amendment, never approve or reject one")
    void buyerWithoutApproveCannotDecide() throws Exception {
        UUID po = approvedOrder();
        amend(po, buyer(personWithLimits(100_000)), lessOfBoth(po)).andExpect(status().isOk());
        String colleague = buyer(personWithLimits());

        approve(po, colleague).andExpect(status().isForbidden());
        reject(po, colleague, "Not mine to decide").andExpect(status().isForbidden());
        assertThat(row(po).get("pending_revision_id")).as("still waiting").isNotNull();

        withdraw(po, colleague, "Asked twice by mistake").andExpect(status().isOk());
        assertThat(row(po).get("pending_revision_id")).isNull();
    }
}
