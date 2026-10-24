package com.stockflow.procurement;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.api.CreateSubcontractOrderCommand;
import com.stockflow.procurement.api.SubcontractOrder;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-434 against a real database: a SUBCONTRACT PO for a SUBCONTRACTED production order, on the new
 * purchase-order tables, to the demo print subcontractor INNHANH. Each test builds its own sales order,
 * root production order and subcontracted child.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class SubcontractPurchasingIntegrationTest {

    private static final Sku CUP = new Sku("SOFA-3S-GREY");

    @Autowired SubcontractPurchasing subcontracting;
    @Autowired com.stockflow.procurement.internal.service.SubcontractAmendments amendments;
    @Autowired com.stockflow.product.api.ProductService products;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private UUID hcm;
    private UUID innhanh;
    private UUID goviet;
    private UUID manager;
    private UUID child;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        innhanh = jdbc.queryForObject("SELECT id FROM procurement.suppliers WHERE code = 'INNHANH'", UUID.class);
        goviet = jdbc.queryForObject("SELECT id FROM procurement.suppliers WHERE code = 'GOVIET'", UUID.class);
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        child = splitProductionOrder();
    }

    /** A sales order line printed 600 in house and 400 at INNHANH; returns the subcontracted child. */
    private UUID splitProductionOrder() {
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
                    order, "SO-20991230-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)),
                    DemoData.CUSTOMER_ID, UUID.randomUUID(), hcm, manager);
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id,
                                                     design_checksum) VALUES (?, ?, ?, 1000, 2000, 'VND', ?, ?)""",
                    line, order, CUP.code(), snapshot, "b".repeat(64));
            jdbc.update("""
                    INSERT INTO production.production_order (id, order_number, type, status, warehouse_id, blank_sku,
                        design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id)
                    VALUES (?, ?, 'ORDER', 'PENDING_PREPRESS', ?, ?, ?, ?, 600, ?, ?)""",
                    root, "LSX-IT-" + root.toString().substring(24), hcm, CUP.code(), snapshot, "b".repeat(64), order, line);
            jdbc.update("""
                    INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id, blank_sku,
                        design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id, parent_id,
                        origin, subcontract_method, subcontractor_id, subcontract_unit_price, subcontract_currency)
                    VALUES (?, ?, 'ORDER', 'SUBCONTRACTED', 'PENDING_PREPRESS', ?, ?, ?, ?, 400, ?, ?, ?,
                            'SPLIT', 'SUPPLIED_BLANKS', ?, 800, 'VND')""",
                    sub, "LSX-IT-" + sub.toString().substring(24), hcm, CUP.code(), snapshot, "b".repeat(64), order, line,
                    root, innhanh);
        });
        return sub;
    }

    private CreateSubcontractOrderCommand command(UUID supplier, Sku sku, int quantity) {
        return new CreateSubcontractOrderCommand(child, supplier, hcm, sku, quantity, new BigDecimal("800"), "VND",
                LocalDate.now().plusDays(7), manager);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("raise once per production order, DRAFT with one line; supplement while DRAFT; refused once submitted")
    void lifecycle() {
        assertThatThrownBy(() -> subcontracting.create(command(goviet, CUP, 400)))
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.SUPPLIER_NOT_SUBCONTRACTOR);
        assertThatThrownBy(() -> subcontracting.create(command(innhanh, new Sku("NO-SUCH-SKU"), 400)))
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
        assertThatThrownBy(() -> subcontracting.create(command(UUID.randomUUID(), CUP, 400)))
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);

        SubcontractOrder created = subcontracting.create(command(innhanh, CUP, 400));
        assertThat(created.poNumber()).matches("SPO-\\d{8}-\\d{4}");
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.approved()).isFalse();
        assertThat(created.finishedSku()).isEqualTo(CUP.code());
        assertThat(created.quantity()).isEqualTo(400);
        assertThat(created.totalAmount()).isEqualByComparingTo("320000");
        assertThat(jdbc.queryForObject("SELECT po_type FROM procurement.purchase_orders WHERE id = ?", String.class,
                created.purchaseOrderId())).isEqualTo("SUBCONTRACT");
        // Like every purchase order: the supplier's terms are captured, the line is in the item's own unit.
        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT p.payment_term_days, p.lead_time_days, l.uom
                  FROM procurement.purchase_orders p JOIN procurement.purchase_order_lines l ON l.po_id = p.id
                 WHERE p.id = ?""", created.purchaseOrderId());
        Map<String, Object> terms = jdbc.queryForMap(
                // a NULL lead time is the system default, as SupplierJpaEntity reads it
                "SELECT payment_term_days, COALESCE(lead_time_days, "
                        + com.stockflow.common.domain.CommercialTerms.LEAD_DAYS
                        + ") AS lead_time_days FROM procurement.suppliers WHERE id = ?",
                innhanh);
        assertThat(stored).containsEntry("payment_term_days", terms.get("payment_term_days"))
                .containsEntry("lead_time_days", terms.get("lead_time_days"))
                .containsEntry("uom", jdbc.queryForObject(
                        "SELECT unit_of_measure FROM inventory.inventory_items WHERE sku = ?", String.class, CUP.code()));

        // Idempotent per production order.
        assertThat(subcontracting.create(command(innhanh, CUP, 999)).purchaseOrderId()).isEqualTo(created.purchaseOrderId());
        assertThat(subcontracting.findByProductionOrder(child)).get()
                .extracting(SubcontractOrder::purchaseOrderId).isEqualTo(created.purchaseOrderId());

        SubcontractOrder supplemented = subcontracting.supplement(created.purchaseOrderId(), 50, "reprint the shortfall", manager);
        assertThat(supplemented.quantity()).isEqualTo(450);
        assertThat(supplemented.totalAmount()).isEqualByComparingTo("360000");
        assertThat(jdbc.queryForList("SELECT action FROM procurement.purchase_order_events WHERE po_id = ? ORDER BY created_at",
                String.class, created.purchaseOrderId())).containsExactly("CREATED", "REVISED");
        assertThatThrownBy(() -> subcontracting.supplement(created.purchaseOrderId(), 0, "x", manager))
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);

        // Submitted: the approver decides on the revision frozen at submission, so the line is not
        // resized under it — the order goes back to DRAFT (rejected) first.
        UUID revision = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header, changed_by)
                    VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?)""", revision, created.purchaseOrderId(), manager);
            jdbc.update("""
                    UPDATE procurement.purchase_orders SET status = 'PENDING_APPROVAL', pending_revision_id = ?,
                           submitted_at = NOW(), submitted_by = ? WHERE id = ?""",
                    revision, manager, created.purchaseOrderId());
        });
        assertThatThrownBy(() -> subcontracting.supplement(created.purchaseOrderId(), 10, "more", manager))
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
        assertThat(subcontracting.findById(created.purchaseOrderId())).get()
                .extracting(SubcontractOrder::quantity).isEqualTo(450);

        // Approved (submitted by one person, approved by another, against that revision).
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.purchase_orders SET status = 'APPROVED', active_revision_id = ?, pending_revision_id = NULL,
                       approved_at = NOW(), approved_by = ? WHERE id = ?""",
                revision, approver, created.purchaseOrderId()));
        assertThat(subcontracting.findById(created.purchaseOrderId())).get()
                .satisfies(o -> assertThat(o.approved()).isTrue());

        // Approved: more quantity is an AMENDMENT revision approved again (BR-02, 03 §16 "PO supplement"); until then
        // the order keeps its approved 450.
        SubcontractOrder asked = subcontracting.supplement(created.purchaseOrderId(), 10, "QC shortfall resend", manager);
        assertThat(asked.quantity()).isEqualTo(450);
        assertThat(asked.pendingQuantity()).isEqualTo(460);
        assertThat(asked.status()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> subcontracting.supplement(created.purchaseOrderId(), 5, "again", manager))
                .as("one amendment waits at a time")
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> amendments.approveAmendment(created.purchaseOrderId(), manager)).as("four eyes")
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.SELF_APPROVAL_NOT_ALLOWED);
        SubcontractOrder amended = amendments.approveAmendment(created.purchaseOrderId(), approver);
        assertThat(amended.quantity()).isEqualTo(460);
        assertThat(amended.pendingQuantity()).isNull();
        assertThat(amended.totalAmount()).isEqualByComparingTo("368000");
        assertThat(amended.status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForMap("""
                SELECT r.kind, r.revision_no, lr.ordered_qty, p.revision_no AS order_revision
                  FROM procurement.purchase_orders p JOIN procurement.purchase_order_revisions r ON r.id = p.active_revision_id
                  JOIN procurement.purchase_order_line_revisions lr ON lr.po_revision_id = r.id
                 WHERE p.id = ?""", created.purchaseOrderId()))
                .containsEntry("kind", "AMENDMENT").satisfies(row -> {
                    assertThat((java.math.BigDecimal) row.get("ordered_qty")).isEqualByComparingTo("460");
                    assertThat(row.get("revision_no")).isEqualTo(row.get("order_revision"));
                });
        assertThat(jdbc.queryForList("""
                SELECT decision FROM procurement.purchase_order_approvals a
                  JOIN procurement.purchase_orders p ON p.active_revision_id = a.po_revision_id WHERE p.id = ?""",
                String.class, created.purchaseOrderId())).containsExactly("APPROVED");

        // A rejected amendment leaves the approved quantity as it was.
        subcontracting.supplement(created.purchaseOrderId(), 40, "more", manager);
        assertThatThrownBy(() -> amendments.rejectAmendment(created.purchaseOrderId(), approver, " "))
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(amendments.rejectAmendment(created.purchaseOrderId(), approver, "not needed"))
                .satisfies(o -> {
                    assertThat(o.quantity()).isEqualTo(460);
                    assertThat(o.pendingQuantity()).isNull();
                });
    }

    @Test
    @DisplayName("SCRUM-473: a draft line in a packing unit is resized in whole units, and its unit quantity follows")
    void draftResizeKeepsTheLineUnit() {
        SubcontractOrder created = subcontracting.create(command(innhanh, CUP, 400));
        UUID line = jdbc.queryForObject("SELECT id FROM procurement.purchase_order_lines WHERE po_id = ?", UUID.class,
                created.purchaseOrderId());
        UUID carton = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            // Subcontract lines are written in the base unit; a line in a carton of 10 is put there as a draft.
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
                    SELECT ?, id, ?, 10 FROM inventory.inventory_items WHERE sku = ?""",
                    carton, "C" + carton.toString().substring(24).toUpperCase(), CUP.code());
            jdbc.update("UPDATE procurement.purchase_order_lines SET uom_conversion_id = ?, unit_qty = 40 WHERE id = ?",
                    carton, line);
        });

        assertThatThrownBy(() -> subcontracting.supplement(created.purchaseOrderId(), 15, "resend", manager))
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.PO_LINE_UNIT_NOT_MULTIPLE);

        assertThat(subcontracting.supplement(created.purchaseOrderId(), 20, "resend", manager).quantity()).isEqualTo(420);
        assertThat(jdbc.queryForMap(
                "SELECT uom_conversion_id, unit_qty, ordered_qty FROM procurement.purchase_order_lines WHERE id = ?", line))
                .containsEntry("uom_conversion_id", carton).containsEntry("unit_qty", 42);
    }

    @Test
    @DisplayName("SCRUM-473: a line ordered in a packing unit keeps it through an amendment, in whole units only")
    void amendmentKeepsTheLineUnit() {
        SubcontractOrder created = subcontracting.create(command(innhanh, CUP, 400));
        UUID line = jdbc.queryForObject("SELECT id FROM procurement.purchase_order_lines WHERE po_id = ?", UUID.class,
                created.purchaseOrderId());
        UUID carton = Identifiers.newId();
        UUID revision = Identifiers.newId();
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            // Subcontract lines are written in the base unit; a line in a carton of 10 is put there as a draft.
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
                    SELECT ?, id, ?, 10 FROM inventory.inventory_items WHERE sku = ?""",
                    carton, "C" + carton.toString().substring(24).toUpperCase(), CUP.code());
            jdbc.update("UPDATE procurement.purchase_order_lines SET uom_conversion_id = ?, unit_qty = 40 WHERE id = ?",
                    carton, line);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header, changed_by)
                    VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?)""", revision, created.purchaseOrderId(), manager);
            jdbc.update("""
                    UPDATE procurement.purchase_orders SET status = 'APPROVED', active_revision_id = ?, submitted_at = NOW(),
                           submitted_by = ?, approved_at = NOW(), approved_by = ? WHERE id = ?""",
                    revision, manager, approver, created.purchaseOrderId());
        });

        assertThatThrownBy(() -> subcontracting.supplement(created.purchaseOrderId(), 15, "resend", manager))
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.PO_LINE_UNIT_NOT_MULTIPLE);

        subcontracting.supplement(created.purchaseOrderId(), 20, "resend", manager);
        assertThat(jdbc.queryForMap("""
                SELECT lr.uom_conversion_id, lr.unit_qty FROM procurement.purchase_orders p
                  JOIN procurement.purchase_order_line_revisions lr ON lr.po_revision_id = p.pending_revision_id
                 WHERE p.id = ?""", created.purchaseOrderId()))
                .containsEntry("uom_conversion_id", carton).containsEntry("unit_qty", 42);

        SubcontractOrder amended = amendments.approveAmendment(created.purchaseOrderId(), approver);
        assertThat(amended.quantity()).isEqualTo(420);
        assertThat(jdbc.queryForMap("SELECT uom_conversion_id, unit_qty FROM procurement.purchase_order_lines WHERE id = ?",
                line)).containsEntry("uom_conversion_id", carton).containsEntry("unit_qty", 42);
    }

    @Test
    @DisplayName("BR-PRD-17: settled once; QC-failed units not paid; a loss above the 3% tolerance waits for the approver's deduction; the PO is cancelled when nothing was received")
    void settlement() {
        SubcontractOrder po = subcontracting.create(command(innhanh, CUP, 400));
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));
        // 400 blanks sent, 380 received (370 passed, 10 failed), 5 returned, 15 lost: tolerance 3% of 400 = 12, 3 above.
        var settled = subcontracting.settle(new com.stockflow.procurement.api.SettleSubcontractCommand(
                po.purchaseOrderId(), child, 380, 370, 10, 400, 380, 5, 15, null, null, manager));
        assertThat(settled.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(settled.toleranceBlanks()).isEqualTo(12);
        assertThat(settled.excessLoss()).isEqualTo(3);
        assertThat(settled.payableAmount()).as("370 x 800, the 10 failed not paid").isEqualByComparingTo("296000");
        assertThat(settled.netPayable()).isEqualByComparingTo("296000");
        assertThat(subcontracting.settle(new com.stockflow.procurement.api.SettleSubcontractCommand(
                po.purchaseOrderId(), child, 1, 1, 0, 0, 0, 0, 0, null, null, manager)))
                .as("settled once").isEqualTo(settled);

        assertThatThrownBy(() -> subcontracting.approveLoss(po.purchaseOrderId(), new java.math.BigDecimal("-1"), "x",
                approver)).extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> subcontracting.approveLoss(po.purchaseOrderId(), new java.math.BigDecimal("1500"), " ",
                approver)).as("QA1: the approver says why").extracting(SubcontractPurchasingIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        var approved = subcontracting.approveLoss(po.purchaseOrderId(), new java.math.BigDecimal("1500"), "agreed",
                approver);
        assertThat(approved.status()).isEqualTo("FINAL");
        assertThat(approved.deductionAmount()).isEqualByComparingTo("4500");
        assertThat(approved.netPayable()).isEqualByComparingTo("291500");
        assertThat(approved.approvedBy()).isEqualTo(approver);
        assertThat(subcontracting.approveLoss(po.purchaseOrderId(), new java.math.BigDecimal("1500.00"), "agreed",
                approver)).as("a replay").isEqualTo(approved);
        assertThatThrownBy(() -> subcontracting.approveLoss(po.purchaseOrderId(), new java.math.BigDecimal("2000"),
                "renegotiated", approver)).extracting(SubcontractPurchasingIntegrationTest::code)
                .isEqualTo(ErrorCode.CONFLICT);

        // Nothing was received on the DRAFT order: production closing it cancels it.
        assertThat(subcontracting.closeForProduction(po.purchaseOrderId(), "Production order replaced in house",
                manager).status()).isEqualTo("CANCELLED");
        assertThat(subcontracting.closeForProduction(po.purchaseOrderId(), "again", manager).status())
                .as("idempotent").isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("SCRUM-461: a SUBCONTRACT order is not asked whether the SKU is purchasable, and it is an open reference of the product")
    void subcontractOrdersIgnoreTheProductStatus() {
        // The finished SKU of a running production order belongs to a product that has been suspended since.
        var suspended = tx.execute(s -> ReferenceRows.approvedProduct(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE product.products SET status = 'INACTIVE', suspended_at = NOW(), suspend_reason = 'recall' WHERE id = ?""",
                suspended.productId()));
        assertThat(products.purchasability(java.util.List.of(suspended.sku())).get(suspended.sku()))
                .isEqualTo(com.stockflow.product.api.Purchasability.PRODUCT_INACTIVE);

        SubcontractOrder created = subcontracting.create(command(innhanh, new Sku(suspended.sku()), 400));

        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.finishedSku()).isEqualTo(suspended.sku());
        assertThat(products.openReferences(suspended.productId()).references()).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo(com.stockflow.product.api.SkuReference.Kind.PURCHASE_ORDER_LINE);
            assertThat(r.documentId()).isEqualTo(created.purchaseOrderId());
            assertThat(r.documentNumber()).isEqualTo(created.poNumber());
        });
    }

    @Test
    @DisplayName("subcontractor terms: the print flag and the loss tolerance (BR-17)")
    void terms() {
        assertThat(subcontracting.subcontractorTerms(innhanh)).get().satisfies(t -> {
            assertThat(t.printSubcontractor()).isTrue();
            assertThat(t.active()).isTrue();
            assertThat(t.lossTolerancePercent()).isEqualByComparingTo("3");
        });
        assertThat(subcontracting.subcontractorTerms(goviet)).get().satisfies(t -> {
            assertThat(t.printSubcontractor()).isFalse();
            assertThat(t.lossTolerancePercent()).isEqualByComparingTo("2");
        });
        assertThat(subcontracting.subcontractorTerms(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("an inactive print subcontractor gets no subcontract order (03 §16, BR-PRD-13)")
    void inactiveSubcontractor() {
        tx.executeWithoutResult(s ->
                jdbc.update("UPDATE procurement.suppliers SET status = 'INACTIVE' WHERE id = ?", innhanh));
        try {
            assertThat(subcontracting.subcontractorTerms(innhanh)).get()
                    .satisfies(t -> assertThat(t.active()).isFalse());
            assertThatThrownBy(() -> subcontracting.create(command(innhanh, CUP, 400)))
                    .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
        } finally {
            tx.executeWithoutResult(s ->
                    jdbc.update("UPDATE procurement.suppliers SET status = 'ACTIVE' WHERE id = ?", innhanh));
        }
    }
}
