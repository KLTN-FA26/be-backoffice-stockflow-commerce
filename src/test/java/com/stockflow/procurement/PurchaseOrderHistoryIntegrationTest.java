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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-117 (SCRUM-132), design section 9, through the HTTP API with the real security chain: the revisions of an
 * order with their purpose and state, one revision's diff against the last approved one, the paged timeline with names
 * and payloads, the pending amendment on the order itself, and the warehouse scope of all of it.
 */
@SecuredIntegrationTest
class PurchaseOrderHistoryIntegrationTest {

    private static final String SOFA = "SOFA-3S-GREY";
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
                    VALUES (?, ?, 'History supplier', 'ACTIVE', 0, NOW())""",
                    supplier, "HST-" + supplier.toString().substring(24).toUpperCase());
            // The delivery contact lives in supplier_contacts: an EMAIL supplier is sent its orders there.
            jdbc.update("""
                    INSERT INTO procurement.supplier_contacts (id, supplier_id, full_name, email, is_primary, version, created_at)
                    VALUES (?, ?, 'History supplier', 'supplier@example.com', TRUE, 0, NOW())""",
                    Identifiers.newId(), supplier);
            other = ReferenceRows.approvedProduct(entityManager).sku();
        });
        SupplierItemFixtures.list(catalog, supplier, SOFA);
        SupplierItemFixtures.list(catalog, supplier, other);
    }

    // ------------------------------------------------------------------ people and calls

    private UUID personWithLimits(long... limits) {
        return tx.execute(s -> {
            UUID user = ReferenceRows.user(entityManager);
            for (long limit : limits) {
                var role = ApprovalLimitFixtures.newRole(entityManager);
                ApprovalLimitFixtures.giveRole(entityManager, user, role);
                ApprovalLimitFixtures.setLimit(entityManager, role, limit);
            }
            return user;
        });
    }

    /** Creates and edits orders and may ask for an amendment; never APPROVE. */
    private String buyer(UUID user) {
        return TestTokens.bearer(decoder, user, "PROCUREMENT_STAFF");
    }

    private String approver(UUID user) {
        return TestTokens.bearer(decoder, user, "SYSTEM_ADMIN");
    }

    private ResultActions call(String path, String auth, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).header("Authorization", auth);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private ResultActions read(String path, String auth) throws Exception {
        return mvc.perform(get(path).header("Authorization", auth));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    // ------------------------------------------------------------------ orders

    private UUID draft(String buyer) throws Exception {
        String body = """
                {"supplierId":"%s","warehouseId":"%s","currency":"VND","expectedAt":"%s",
                 "lines":[{"sku":"%s","quantityOrdered":10,"unitPrice":50000},
                          {"sku":"%s","quantityOrdered":5,"unitPrice":100000}]}""".formatted(supplier,
                DemoData.WAREHOUSE_HCM, EXPECTED, SOFA, other);
        return UUID.fromString(data(call("/api/v1/purchase-orders", buyer, body)
                .andExpect(status().isCreated())).path("purchaseOrderId").asText());
    }

    /** Submitted by a buyer with no limit, approved by someone else: APPROVED, revision 0 active. */
    private UUID approvedOrder(String buyer) throws Exception {
        UUID po = draft(buyer);
        call(ORDERS + po + "/submission", buyer, null).andExpect(status().isOk());
        call(ORDERS + po + "/approval", approver(personWithLimits()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
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

    /** Sofas 10 to 8, the other line removed, delivered three days later: an amendment of an approved order. */
    private ResultActions amendToEight(UUID po, String auth) throws Exception {
        String body = """
                {"version":%d,"reason":"Supplier can deliver less","expectedDate":"%s",
                 "lines":[{"lineId":"%s","quantityOrdered":8}]}""".formatted(version(po), EXPECTED.plusDays(3),
                lineOf(po, SOFA));
        return call(ORDERS + po + "/amendments", auth, body);
    }

    /**
     * Submitted (revision 0) and rejected, resubmitted (revision 1) and approved, then amended (revision 2, waiting
     * because the requester has no limit).
     */
    private UUID orderWithThreeRevisions(String buyer) throws Exception {
        UUID po = draft(buyer);
        call(ORDERS + po + "/submission", buyer, null).andExpect(status().isOk());
        call(ORDERS + po + "/rejection", approver(personWithLimits()), "{\"reason\":\"Price too high\"}")
                .andExpect(status().isOk());
        call(ORDERS + po + "/submission", buyer, null).andExpect(status().isOk());
        call(ORDERS + po + "/approval", approver(personWithLimits()), null).andExpect(status().isOk());
        amendToEight(po, buyer).andExpect(status().isOk());
        return po;
    }

    private JsonNode revisionNo(JsonNode list, int no) {
        for (JsonNode node : list) {
            if (node.path("revisionNo").asInt() == no) {
                return node;
            }
        }
        throw new AssertionError("No revision " + no + " in " + list);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("revisions list: purpose SUBMISSION or AMENDMENT and state REJECTED, ACTIVE, PENDING, then SUPERSEDED")
    void revisionsListPurposeAndState() throws Exception {
        String buyer = buyer(personWithLimits());
        UUID po = orderWithThreeRevisions(buyer);

        JsonNode list = data(read(ORDERS + po + "/revisions", buyer).andExpect(status().isOk()));
        assertThat(list).hasSize(3);
        assertThat(list.get(0).path("revisionNo").asInt()).as("newest first").isEqualTo(2);
        assertThat(revisionNo(list, 0).path("state").asText()).isEqualTo("REJECTED");
        assertThat(revisionNo(list, 1).path("state").asText()).isEqualTo("ACTIVE");
        assertThat(revisionNo(list, 2).path("state").asText()).isEqualTo("PENDING");
        assertThat(revisionNo(list, 0).path("purpose").asText()).isEqualTo("SUBMISSION");
        assertThat(revisionNo(list, 1).path("purpose").asText()).isEqualTo("SUBMISSION");
        assertThat(revisionNo(list, 2).path("purpose").asText()).isEqualTo("AMENDMENT");
        assertThat(revisionNo(list, 0).path("decision").asText()).isEqualTo("REJECTED");
        assertThat(revisionNo(list, 0).path("decisionReason").asText()).isEqualTo("Price too high");
        assertThat(revisionNo(list, 0).path("decidedByName").asText()).isNotBlank();
        assertThat(revisionNo(list, 2).path("reason").asText()).isEqualTo("Supplier can deliver less");
        assertThat(revisionNo(list, 2).path("changedByName").asText()).isNotBlank();
        // Undecided: the field is left out (default-property-inclusion non_null), not written as null.
        assertThat(revisionNo(list, 2).path("decision").isMissingNode()).isTrue();

        call(ORDERS + po + "/amendment/approval", approver(personWithLimits()), null).andExpect(status().isOk());
        JsonNode after = data(read(ORDERS + po + "/revisions", buyer));
        assertThat(revisionNo(after, 1).path("state").asText()).isEqualTo("SUPERSEDED");
        assertThat(revisionNo(after, 2).path("state").asText()).isEqualTo("ACTIVE");
        assertThat(revisionNo(after, 2).path("decision").asText()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("revision detail: changes against the last approved revision - quantity 10 to 8, a removed line, "
            + "the expected date")
    void revisionDetailDiffsAgainstLastApproved() throws Exception {
        String buyer = buyer(personWithLimits());
        UUID po = orderWithThreeRevisions(buyer);

        JsonNode detail = data(read(ORDERS + po + "/revisions/2", buyer).andExpect(status().isOk()));
        assertThat(detail.path("comparedWithRevisionNo").asInt()).isEqualTo(1);
        assertThat(detail.path("revision").path("revisionNo").asInt()).isEqualTo(2);
        assertThat(detail.path("expectedDate").asText()).isEqualTo(EXPECTED.plusDays(3).toString());
        assertThat(detail.path("lines")).hasSize(1);
        assertThat(detail.path("lines").get(0).path("sku").asText()).isEqualTo(SOFA);
        assertThat(detail.path("lines").get(0).path("quantity").decimalValue()).isEqualByComparingTo("8");

        List<JsonNode> changes = new ArrayList<>();
        detail.path("changes").forEach(changes::add);
        assertThat(changes).anySatisfy(c -> {
            assertThat(c.path("scope").asText()).isEqualTo("LINE");
            assertThat(c.path("lineNo").asInt()).isEqualTo(1);
            assertThat(c.path("type").asText()).isEqualTo("CHANGED");
            assertThat(c.path("field").asText()).isEqualTo("quantity");
            assertThat(c.path("before").asText()).isEqualTo("10");
            assertThat(c.path("after").asText()).isEqualTo("8");
        });
        assertThat(changes).anySatisfy(c -> {
            assertThat(c.path("scope").asText()).isEqualTo("LINE");
            assertThat(c.path("lineNo").asInt()).isEqualTo(2);
            assertThat(c.path("type").asText()).isEqualTo("REMOVED");
        });
        assertThat(changes).anySatisfy(c -> {
            assertThat(c.path("scope").asText()).isEqualTo("HEADER");
            assertThat(c.path("field").asText()).isEqualTo("expectedDate");
            assertThat(c.path("before").asText()).isEqualTo(EXPECTED.toString());
            assertThat(c.path("after").asText()).isEqualTo(EXPECTED.plusDays(3).toString());
        });

        // Revision 1 is compared with revision 0 although revision 0 was rejected: nothing earlier was approved.
        JsonNode resubmission = data(read(ORDERS + po + "/revisions/1", buyer));
        assertThat(resubmission.path("comparedWithRevisionNo").asInt()).isEqualTo(0);
        assertThat(resubmission.path("changes")).isEmpty();
        read(ORDERS + po + "/revisions/0", buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comparedWithRevisionNo").doesNotExist());
        read(ORDERS + po + "/revisions/9", buyer).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("timeline: newest first, paged by the database, names filled, the payload as stored, size 500 refused")
    void timelineNewestFirstWithPayloadAndNames() throws Exception {
        UUID actor = personWithLimits();
        String buyer = buyer(actor);
        UUID po = approvedOrder(buyer);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.purchase_order_events
                       (id, po_id, action, actor_id, reason, payload, created_at, created_by)
                VALUES (?, ?, 'REVISED', ?, 'Dates moved', CAST(? AS jsonb), NOW() + interval '1 minute', 'test')""",
                Identifiers.newId(), po, actor, "{\"expectedDate\":\"2030-01-01\"}"));
        int total = jdbc.queryForObject(
                "SELECT count(*) FROM procurement.purchase_order_events WHERE po_id = ?", Integer.class, po);
        assertThat(total).isGreaterThanOrEqualTo(3);

        JsonNode page = data(read(ORDERS + po + "/timeline?page=0&size=2", buyer).andExpect(status().isOk()));
        assertThat(page.path("items")).hasSize(2);
        assertThat(page.path("totalElements").asInt()).isEqualTo(total);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        JsonNode newest = page.path("items").get(0);
        assertThat(newest.path("action").asText()).isEqualTo("REVISED");
        assertThat(newest.path("reason").asText()).isEqualTo("Dates moved");
        assertThat(newest.path("actorName").asText()).isNotBlank();
        assertThat(newest.path("payload").path("expectedDate").asText()).isEqualTo("2030-01-01");

        JsonNode second = data(read(ORDERS + po + "/timeline?page=1&size=2", buyer));
        assertThat(second.path("items").get(0).path("id").asText())
                .isNotEqualTo(page.path("items").get(1).path("id").asText());

        read(ORDERS + po + "/timeline?page=0&size=500", buyer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("the order response shows the pending amendment and, once told, the notice status")
    void orderResponseShowsPendingAmendmentAndNoticeStatus() throws Exception {
        UUID requester = personWithLimits();
        String buyer = buyer(requester);
        UUID po = approvedOrder(buyer);
        read(ORDERS + po, buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingAmendment").doesNotExist())
                .andExpect(jsonPath("$.data.amendmentDeliveryStatus").doesNotExist());

        amendToEight(po, buyer).andExpect(status().isOk());

        read(ORDERS + po, buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingAmendment.revisionNo").value(1))
                .andExpect(jsonPath("$.data.pendingAmendment.requestedBy").value(requester.toString()))
                .andExpect(jsonPath("$.data.pendingAmendment.reason").value("Supplier can deliver less"))
                .andExpect(jsonPath("$.data.pendingAmendment.newTotalAmount").value(400000))
                .andExpect(jsonPath("$.data.amendmentDeliveryStatus").doesNotExist());
        read(ORDERS + "?size=50&q=" + supplier, buyer).andExpect(status().isOk());

        call(ORDERS + po + "/amendment/approval", approver(personWithLimits()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingAmendment").doesNotExist());
    }

    @Test
    @DisplayName("a warehouse-bound user of another warehouse is refused all three endpoints exactly as the order is")
    void historyOutsideWarehouseScopeIsRefusedAsTheOrderIs() throws Exception {
        String buyer = buyer(personWithLimits());
        UUID po = orderWithThreeRevisions(buyer);

        UUID clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = 'WAREHOUSE_STAFF'""",
                    clerk);
            jdbc.update("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM warehouse.warehouse WHERE id <> ? LIMIT 1""",
                    clerk, DemoData.WAREHOUSE_HCM);
        });
        String elsewhere = TestTokens.bearer(decoder, clerk, "WAREHOUSE_STAFF");

        int expected = read(ORDERS + po, elsewhere).andReturn().getResponse().getStatus();
        assertThat(expected).as("what the order itself answers").isIn(403, 404);
        read(ORDERS + po + "/revisions", elsewhere).andExpect(status().is(expected));
        read(ORDERS + po + "/revisions/1", elsewhere).andExpect(status().is(expected));
        read(ORDERS + po + "/timeline", elsewhere).andExpect(status().is(expected));
    }

    @Test
    @DisplayName("amounts of 10 million and more are plain numbers as stored, not scientific notation")
    void largeAmountsArePlain() throws Exception {
        String buyer = buyer(personWithLimits());
        String body = """
                {"supplierId":"%s","warehouseId":"%s","currency":"VND","expectedAt":"%s",
                 "lines":[{"sku":"%s","quantityOrdered":220,"unitPrice":50000}]}""".formatted(supplier,
                DemoData.WAREHOUSE_HCM, EXPECTED, SOFA);
        UUID po = UUID.fromString(data(call("/api/v1/purchase-orders", buyer, body)
                .andExpect(status().isCreated())).path("purchaseOrderId").asText());
        call(ORDERS + po + "/submission", buyer, null).andExpect(status().isOk());
        call(ORDERS + po + "/approval", approver(personWithLimits()), null).andExpect(status().isOk());

        // The raw bodies: a tree read by the mapper would turn the number into a double, hiding what is asserted here.
        // VND has no minor unit, so Money stores scale 0 and the snapshot holds 11000000, not 11000000.00.
        String list = read(ORDERS + po + "/revisions", buyer).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).containsPattern(plain("totalAmount", "11000000")).doesNotContain("E+");
        String detail = read(ORDERS + po + "/revisions/0", buyer).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).containsPattern(plain("subtotal", "11000000")).containsPattern(plain("taxTotal", "0"))
                .containsPattern(plain("totalAmount", "11000000")).doesNotContain("E+");
    }

    /** {@code "field":value} and the end of the value: 11000000 must not match 110000000 or 11000000.50. */
    private static String plain(String field, String value) {
        return Pattern.quote("\"" + field + "\":" + value) + "[,}]";
    }

    @Test
    @DisplayName("a first submission or a resubmission waiting in PENDING_APPROVAL is not a pending amendment")
    void submissionWaitingIsNotAPendingAmendment() throws Exception {
        String buyer = buyer(personWithLimits());
        UUID po = draft(buyer);
        call(ORDERS + po + "/submission", buyer, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.pendingAmendment").doesNotExist());
        read(ORDERS + po, buyer).andExpect(jsonPath("$.data.pendingAmendment").doesNotExist());

        call(ORDERS + po + "/rejection", approver(personWithLimits()), "{\"reason\":\"Again\"}")
                .andExpect(status().isOk());
        // Revision 1 is kind AMENDMENT (a resubmission); the order is PENDING_APPROVAL, so it is not an amendment.
        call(ORDERS + po + "/submission", buyer, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingAmendment").doesNotExist());
        read(ORDERS + "?size=50&q=" + supplier, buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].pendingAmendment").doesNotExist());
    }

    @Test
    @DisplayName("a resubmission cancelled while it waited is no pending amendment: not on the order, not PENDING among "
            + "the revisions, and nothing to withdraw")
    void cancelledResubmissionIsNoPendingAmendment() throws Exception {
        String buyer = buyer(personWithLimits());
        UUID po = draft(buyer);
        call(ORDERS + po + "/submission", buyer, null).andExpect(status().isOk());
        call(ORDERS + po + "/rejection", approver(personWithLimits()), "{\"reason\":\"Again\"}")
                .andExpect(status().isOk());
        call(ORDERS + po + "/submission", buyer, null).andExpect(status().isOk());
        call(ORDERS + po + "/cancellation", buyer, "{\"reason\":\"Not needed any more\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.pendingAmendment").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT pending_revision_id FROM procurement.purchase_orders WHERE id = ?",
                UUID.class, po)).as("the pointer the order keeps, which must not read as an amendment").isNotNull();

        read(ORDERS + po, buyer).andExpect(jsonPath("$.data.pendingAmendment").doesNotExist());
        read(ORDERS + "?size=50&q=" + supplier, buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].pendingAmendment").doesNotExist());
        JsonNode list = data(read(ORDERS + po + "/revisions", buyer).andExpect(status().isOk()));
        assertThat(list).noneSatisfy(r -> assertThat(r.path("state").asText()).isEqualTo("PENDING"));
        call(ORDERS + po + "/amendment/withdrawal", buyer, "{\"reason\":\"Tidy up\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PO_AMENDMENT_NOT_PENDING"));
    }

    @Test
    @DisplayName("revision 0 is a submission even when no SUBMITTED event names it: a draft frozen as it is cancelled")
    void initialRevisionIsASubmission() throws Exception {
        String buyer = buyer(personWithLimits());
        UUID po = draft(buyer);
        call(ORDERS + po + "/cancellation", buyer, "{\"reason\":\"Ordered elsewhere\"}").andExpect(status().isOk());

        JsonNode list = data(read(ORDERS + po + "/revisions", buyer).andExpect(status().isOk()));
        assertThat(list).hasSize(1);
        assertThat(revisionNo(list, 0).path("purpose").asText()).isEqualTo("SUBMISSION");
        assertThat(revisionNo(list, 0).path("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the amendment delivery status is that of the active revision's notice: a later amendment whose notice "
            + "was not attempted yet shows none, not the previous one's")
    void amendmentDeliveryStatusBelongsToTheActiveRevision() throws Exception {
        String buyer = buyer(personWithLimits());
        UUID po = approvedOrder(buyer);
        amendToEight(po, buyer).andExpect(status().isOk());
        call(ORDERS + po + "/amendment/approval", approver(personWithLimits()), null).andExpect(status().isOk());
        noticeDelivered(po, 1);
        read(ORDERS + po, buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amendmentDeliveryStatus").value("DELIVERED"));

        String body = """
                {"version":%d,"reason":"Seven after all","expectedDate":"%s",
                 "lines":[{"lineId":"%s","quantityOrdered":7}]}""".formatted(version(po), EXPECTED.plusDays(3),
                lineOf(po, SOFA));
        call(ORDERS + po + "/amendments", buyer, body).andExpect(status().isOk());
        call(ORDERS + po + "/amendment/approval", approver(personWithLimits()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amendmentDeliveryStatus").doesNotExist());
        read(ORDERS + po, buyer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amendmentDeliveryStatus").doesNotExist());
    }

    /** A delivered notice of amendment revision {@code revisionNo}, as the notification listener logs one. */
    private void noticeDelivered(UUID po, int revisionNo) {
        String reference = "purchase-order:" + po + ":revision:" + revisionNo;
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO notification.delivery_log (id, template_code, channel, recipient, status, sent_at,
                       external_reference, operation_reference, terminal, delivery_generation, attempt_number,
                       version, created_at)
                VALUES (?, 'purchase-order.amended', 'EMAIL', 'supplier@example.com', 'SENT', NOW(), ?, ?, FALSE, 0,
                        1, 0, NOW())""", Identifiers.newId(), reference, reference));
    }
}
