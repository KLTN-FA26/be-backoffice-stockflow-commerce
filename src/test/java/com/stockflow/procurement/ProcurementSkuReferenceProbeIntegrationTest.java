package com.stockflow.procurement;

import com.stockflow.common.id.Identifiers;
import com.stockflow.product.api.SkuReference;
import com.stockflow.product.api.SkuReferenceProbe;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which purchase order lines still refer to a SKU (SCRUM-461, BR-PRD-005), for every status a purchase order and a
 * line can be in, against Postgres: a line still to be received on an order that is not over counts, wherever the
 * order is in its life up to partly received; everything else does not.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ProcurementSkuReferenceProbeIntegrationTest {

    @Autowired List<SkuReferenceProbe> probes;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SkuReferenceProbe probe;
    private String sku;
    private UUID supplier;
    private UUID submitter;
    private UUID approver;

    @BeforeEach
    void setUp() {
        probe = probes.stream().filter(p -> AopUtils.getTargetClass(p).getSimpleName().equals("ProcurementSkuReferenceProbe"))
                .findFirst().orElseThrow();
        supplier = jdbc.queryForObject("SELECT id FROM procurement.suppliers WHERE code = 'GOVIET'", UUID.class);
        submitter = tx.execute(s -> ReferenceRows.user(entityManager));
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
        sku = tx.execute(s -> ReferenceRows.approvedProduct(entityManager).sku());
    }

    /** A purchase order in {@code status} with one line of {@code sku} in {@code lineStatus}; returns its number. */
    private String order(String status, String lineStatus, String forSku) {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        String number = "PO-PR-" + po.toString().substring(24);
        boolean submitted = !status.equals("DRAFT") && !status.equals("CANCELLED");
        boolean approved = List.of("APPROVED", "CONFIRMED", "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED").contains(status);
        boolean confirmed = List.of("CONFIRMED", "PARTIALLY_RECEIVED", "RECEIVED").contains(status);
        boolean closed = status.equals("CLOSED");
        boolean pending = status.equals("PENDING_APPROVAL");
        boolean draft = status.equals("DRAFT");
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_by, submitted_at, approved_by, approved_at, confirmed_by, confirmed_at,
                        close_kind, closed_by, closed_at, cancel_reason, active_revision_id, pending_revision_id,
                        version, created_at)
                    VALUES (?, ?, ?, ?, ?, 'VND', CURRENT_DATE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW())""",
                    po, number, status, supplier, DemoData.WAREHOUSE_HCM,
                    submitted ? submitter : null, submitted ? now() : null,
                    approved ? approver : null, approved ? now() : null,
                    confirmed ? approver : null, confirmed ? now() : null,
                    closed ? "NORMAL" : null, closed ? approver : null, closed ? now() : null,
                    status.equals("CANCELLED") ? "x" : null,
                    draft || pending ? null : revision, pending ? revision : null);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, status, version, created_at)
                    SELECT ?, ?, ?, 1, id, 5, 1000, ?, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                    Identifiers.newId(), po, revision, lineStatus, forSku);
        });
        return number;
    }

    private static java.sql.Timestamp now() {
        return java.sql.Timestamp.from(java.time.Instant.now());
    }

    private List<String> openDocuments(String... skus) {
        return probe.openReferences(List.of(skus), 100).stream().map(SkuReference::documentNumber).toList();
    }

    @Test
    @DisplayName("an open line of an order that is not over is a reference: DRAFT to PARTIALLY_RECEIVED")
    void openLinesCount() {
        Map<String, String> counted = new LinkedHashMap<>();
        counted.put(order("DRAFT", "OPEN", sku), "DRAFT");
        counted.put(order("PENDING_APPROVAL", "OPEN", sku), "PENDING_APPROVAL");
        counted.put(order("APPROVED", "OPEN", sku), "APPROVED");
        counted.put(order("CONFIRMED", "OPEN", sku), "CONFIRMED");
        counted.put(order("CONFIRMED", "PARTIALLY_RECEIVED", sku), "CONFIRMED, line partly received");
        counted.put(order("PARTIALLY_RECEIVED", "PARTIALLY_RECEIVED", sku), "PARTIALLY_RECEIVED");

        assertThat(openDocuments(sku)).containsExactlyInAnyOrderElementsOf(counted.keySet());
        assertThat(probe.openReferences(List.of(sku), 100)).allSatisfy(r -> {
            assertThat(r.kind()).isEqualTo(SkuReference.Kind.PURCHASE_ORDER_LINE);
            assertThat(r.sku()).isEqualTo(sku);
            assertThat(r.documentId()).isNotNull();
        });
    }

    @Test
    @DisplayName("an order that is over, or a line that is done, is not: RECEIVED, CLOSED, CANCELLED, WITHDRAWN")
    void finishedDocumentsDoNotCount() {
        order("RECEIVED", "RECEIVED", sku);
        order("CLOSED", "CLOSED", sku);
        order("CLOSED", "OPEN", sku);          // short-closed with a line never received: the order is over
        order("CANCELLED", "CANCELLED", sku);
        order("CONFIRMED", "RECEIVED", sku);   // the order is open, this line is not
        order("CONFIRMED", "CLOSED", sku);
        order("CONFIRMED", "CANCELLED", sku);
        order("DRAFT", "WITHDRAWN", sku);

        assertThat(openDocuments(sku)).isEmpty();
    }

    @Test
    @DisplayName("only the SKUs asked about, newest order first, at most the limit; no SKU and an unknown SKU give nothing")
    void scopeAndLimit() {
        String other = tx.execute(s -> ReferenceRows.approvedProduct(entityManager).sku());
        String first = order("CONFIRMED", "OPEN", sku);
        String second = order("CONFIRMED", "OPEN", sku);
        String third = order("CONFIRMED", "OPEN", other);

        assertThat(openDocuments(sku)).containsExactly(second, first);
        assertThat(openDocuments(other)).containsExactly(third);
        assertThat(openDocuments(sku, other)).containsExactlyInAnyOrder(first, second, third);
        assertThat(probe.openReferences(List.of(sku), 1)).hasSize(1);
        assertThat(probe.openReferences(List.of(), 10)).isEmpty();
        assertThat(openDocuments("NO-SUCH-SKU")).isEmpty();
        // A SKU the shared format does not accept cannot be on a line, and is left out rather than failing.
        assertThat(openDocuments("bad sku!", sku)).containsExactly(second, first);
    }
}
