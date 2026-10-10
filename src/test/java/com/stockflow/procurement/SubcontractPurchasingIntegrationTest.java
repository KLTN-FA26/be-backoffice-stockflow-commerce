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
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, DemoData.CUSTOMER_ID, UUID.randomUUID());
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
                        subcontract_method, subcontractor_id, subcontract_unit_price, subcontract_currency)
                    VALUES (?, ?, 'ORDER', 'SUBCONTRACTED', 'PENDING_PREPRESS', ?, ?, ?, ?, 400, ?, ?, ?,
                            'SUPPLIED_BLANKS', ?, 800, 'VND')""",
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
    @DisplayName("raise once per production order, DRAFT with one line; supplement while DRAFT; refused once approved")
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

        // Approved (submitted by one person, approved by another, against a revision).
        UUID revision = Identifiers.newId();
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header, changed_by)
                    VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?)""", revision, created.purchaseOrderId(), manager);
            jdbc.update("""
                    UPDATE procurement.purchase_orders SET status = 'APPROVED', active_revision_id = ?, submitted_at = NOW(),
                           submitted_by = ?, approved_at = NOW(), approved_by = ? WHERE id = ?""",
                    revision, manager, approver, created.purchaseOrderId());
        });
        assertThat(subcontracting.findById(created.purchaseOrderId())).get()
                .satisfies(o -> assertThat(o.approved()).isTrue());
        assertThatThrownBy(() -> subcontracting.supplement(created.purchaseOrderId(), 10, "more", manager))
                .extracting(SubcontractPurchasingIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
    }

    @Test
    @DisplayName("subcontractor terms: the print flag and the loss tolerance (BR-17)")
    void terms() {
        assertThat(subcontracting.subcontractorTerms(innhanh)).get().satisfies(t -> {
            assertThat(t.printSubcontractor()).isTrue();
            assertThat(t.lossTolerancePercent()).isEqualByComparingTo("3");
        });
        assertThat(subcontracting.subcontractorTerms(goviet)).get().satisfies(t -> {
            assertThat(t.printSubcontractor()).isFalse();
            assertThat(t.lossTolerancePercent()).isEqualByComparingTo("2");
        });
        assertThat(subcontracting.subcontractorTerms(UUID.randomUUID())).isEmpty();
    }
}
