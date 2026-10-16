package com.stockflow.procurement;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.api.CreateSubcontractOrderCommand;
import com.stockflow.procurement.api.SubcontractOrder;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review of PR #109, items 2 and 3 (BR-SEC-002): a warehouse manager of another warehouse may neither decide the
 * amendment of an HCM subcontract order nor read its settlement - refused before anything is written, so the
 * amendment is still waiting and the order unchanged; a manager of HCM, not the one who asked, decides it.
 */
@SecuredIntegrationTest
class SubcontractWarehouseScopeIntegrationTest {

    private static final Sku CUP = new Sku("SOFA-3S-GREY");
    private static final String ORDERS = "/api/v1/purchase-orders/";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired SubcontractPurchasing subcontracting;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private AlertFixtures fixtures;
    private UUID hcm;
    private UUID requester;

    @BeforeEach
    void setUp() {
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
        hcm = fixtures.hcm();
        requester = tx.execute(s -> ReferenceRows.user(entityManager));
    }

    @Test
    @DisplayName("another warehouse's manager cannot approve, reject or read an HCM subcontract amendment; nothing changes")
    void amendmentAndSettlementAreWarehouseScoped() throws Exception {
        UUID po = approvedWithPendingAmendment();
        String elsewhere = TestTokens.bearer(decoder, fixtures.staff("WAREHOUSE_MANAGER", fixtures.otherWarehouse().id()),
                "WAREHOUSE_MANAGER");

        mvc.perform(post(ORDERS + po + "/amendment/approval").header("Authorization", elsewhere))
                .andExpect(status().isForbidden());
        mvc.perform(post(ORDERS + po + "/amendment/rejection").header("Authorization", elsewhere)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not yours\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(ORDERS + po + "/subcontract-settlement").header("Authorization", elsewhere))
                .andExpect(status().isForbidden());

        SubcontractOrder untouched = subcontracting.findById(po).orElseThrow();
        assertThat(untouched.quantity()).as("the approved quantity").isEqualTo(400);
        assertThat(untouched.pendingQuantity()).as("the amendment still waits").isEqualTo(410);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement.purchase_order_approvals a"
                + " JOIN procurement.purchase_order_revisions r ON r.id = a.po_revision_id WHERE r.po_id = ?"
                + " AND r.kind = 'AMENDMENT'", Integer.class, po)).as("no decision recorded").isZero();

        // A manager of HCM - not the one who asked for the amendment - decides it, and reads the settlement (none yet).
        String inHcm = TestTokens.bearer(decoder, fixtures.staff("WAREHOUSE_MANAGER", hcm), "WAREHOUSE_MANAGER");
        mvc.perform(get(ORDERS + po + "/subcontract-settlement").header("Authorization", inHcm))
                .andExpect(status().isNotFound());
        mvc.perform(post(ORDERS + po + "/amendment/approval").header("Authorization", inHcm))
                .andExpect(status().isOk());
        assertThat(subcontracting.findById(po).orElseThrow()).satisfies(o -> {
            assertThat(o.quantity()).isEqualTo(410);
            assertThat(o.pendingQuantity()).isNull();
        });
    }

    /** An HCM SUBCONTRACT order for a split child, approved at 400, with an amendment to 410 waiting. */
    private UUID approvedWithPendingAmendment() {
        UUID innhanh = jdbc.queryForObject("SELECT id FROM procurement.suppliers WHERE code = 'INNHANH'", UUID.class);
        UUID child = splitChild(innhanh);
        SubcontractOrder created = subcontracting.create(new CreateSubcontractOrderCommand(child, innhanh, hcm, CUP, 400,
                new BigDecimal("800"), "VND", LocalDate.now().plusDays(7), requester));
        UUID revision = Identifiers.newId();
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header, changed_by)
                    VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?)""", revision, created.purchaseOrderId(), requester);
            jdbc.update("""
                    UPDATE procurement.purchase_orders SET status = 'APPROVED', active_revision_id = ?, submitted_at = NOW(),
                           submitted_by = ?, approved_at = NOW(), approved_by = ? WHERE id = ?""",
                    revision, requester, approver, created.purchaseOrderId());
        });
        subcontracting.supplement(created.purchaseOrderId(), 10, "QC shortfall resend", requester);
        return created.purchaseOrderId();
    }

    private UUID splitChild(UUID subcontractor) {
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        UUID order = Identifiers.newId();
        UUID line = Identifiers.newId();
        UUID root = Identifiers.newId();
        UUID sub = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, DemoData.CUSTOMER_ID, DemoData.PRODUCT_SOFA);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                                                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/p.pdf', NOW(), NOW(), '[]'::jsonb)""", snapshot, draft, "b".repeat(64));
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, warehouse_id, released_at, released_by,
                                                         version, created_at)
                    VALUES (?, ?, ?, ?, 'IN_PRODUCTION', 2000000, 'VND', NOW(), ?, NOW(), ?, 0, NOW())""",
                    order, "SO-20991229-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)),
                    DemoData.CUSTOMER_ID, UUID.randomUUID(), hcm, requester);
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id,
                                                     design_checksum) VALUES (?, ?, ?, 1000, 2000, 'VND', ?, ?)""",
                    line, order, CUP.code(), snapshot, "b".repeat(64));
            jdbc.update("""
                    INSERT INTO production.production_order (id, order_number, type, status, warehouse_id, blank_sku,
                        design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id)
                    VALUES (?, ?, 'ORDER', 'PENDING_PREPRESS', ?, ?, ?, ?, 600, ?, ?)""",
                    root, "LSX-SC-" + root.toString().substring(24), hcm, CUP.code(), snapshot, "b".repeat(64), order, line);
            jdbc.update("""
                    INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id, blank_sku,
                        design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id, parent_id,
                        origin, subcontract_method, subcontractor_id, subcontract_unit_price, subcontract_currency)
                    VALUES (?, ?, 'ORDER', 'SUBCONTRACTED', 'PENDING_PREPRESS', ?, ?, ?, ?, 400, ?, ?, ?,
                            'SPLIT', 'SUPPLIED_BLANKS', ?, 800, 'VND')""",
                    sub, "LSX-SC-" + sub.toString().substring(24), hcm, CUP.code(), snapshot, "b".repeat(64), order, line,
                    root, subcontractor);
        });
        return sub;
    }
}
