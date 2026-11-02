package com.stockflow.procurement.internal.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-117 (SCRUM-131) amendment revisions against Postgres: what {@code recordAmendment} freezes, that
 * {@code applyAmendment} gets past {@code guard_po_line_content_change} and {@code ck_purchase_orders_total}, and the
 * pending amendment read back and dropped.
 *
 * <p>The orders are put in with SQL, as the receiving tests do, so each test pins the persistence alone; the amendment
 * is proposed by the aggregate and recorded through the port, as the service does. Every step runs in its own
 * {@link TransactionTemplate} transaction, so the triggers and the deferred checks really run at commit.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class PurchaseOrderAmendmentRepositoryIntegrationTest {

    private static final LocalDate EXPECTED = LocalDate.now().plusDays(10);

    @Autowired PurchaseOrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private UUID actor;
    private UUID supplier;

    /** What {@link #request} recorded. */
    private record Recorded(UUID revisionId, long revisionNo) {
    }

    @BeforeEach
    void setUp() {
        actor = tx.execute(s -> ReferenceRows.user(entityManager));
        supplier = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.suppliers (id, code, name, status, version, created_at)
                VALUES (?, ?, 'Amendment supplier', 'ACTIVE', 0, NOW())""",
                supplier, "AMD-" + supplier.toString().substring(24).toUpperCase()));
    }

    // ------------------------------------------------------------------ fixtures

    private String newSku() {
        return tx.execute(s -> ReferenceRows.approvedProduct(entityManager).sku());
    }

    private UUID itemOf(String sku) {
        return jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, sku);
    }

    /** One line of {@link #order}: so many base units at a price, optionally in a packing unit of {@code count} units. */
    private record Line(String sku, int quantity, long price, UUID unit, Integer count) {
        static Line of(String sku, int quantity, long price) {
            return new Line(sku, quantity, price, null, null);
        }
    }

    /** An order of {@code status} (APPROVED or CONFIRMED) with these lines, no tax, at revision 0. */
    private UUID order(String status, Line... lines) {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        long total = 0;
        for (Line line : lines) {
            total += line.quantity() * line.price();
        }
        long amount = total;
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            boolean confirmed = "CONFIRMED".equals(status);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, expected_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at,
                        confirmed_by, supplier_confirmation_status, subtotal, total_amount, active_revision_id, version,
                        created_at)
                    VALUES (?, ?, ?, ?, ?, 'VND', CURRENT_DATE, ?, NOW(), ?, NOW(), ?, ?, ?, ?, ?, ?, ?, 0, NOW())""",
                    po, "PO-AMD-" + po.toString().substring(24), status, supplier, DemoData.WAREHOUSE_HCM, EXPECTED,
                    submitter, approver, confirmed ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                    confirmed ? approver : null, confirmed ? "PENDING" : "NOT_SENT", amount, amount, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            int no = 1;
            for (Line line : lines) {
                long lineTotal = line.quantity() * line.price();
                jdbc.update("""
                        INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                            ordered_qty, unit_price, line_subtotal, line_net, line_total, uom_conversion_id, unit_qty,
                            version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW())""",
                        Identifiers.newId(), po, revision, no++, itemOf(line.sku()), line.quantity(), line.price(),
                        lineTotal, lineTotal, lineTotal, line.unit(), line.count());
            }
        });
        return po;
    }

    private UUID lineOf(UUID po, String sku) {
        return jdbc.queryForObject("""
                SELECT id FROM procurement.purchase_order_lines WHERE po_id = ? AND inventory_item_id = ?""",
                UUID.class, po, itemOf(sku));
    }

    private static PurchaseOrder.AmendmentLine keep(UUID line, int quantity) {
        return new PurchaseOrder.AmendmentLine(line, quantity, null, null, null);
    }

    /** Proposes, records and points the order at the amendment, as {@code PurchaseOrderAmendments.request} does. */
    private Recorded request(UUID po, PurchaseOrder.AmendmentLine... lines) {
        return tx.execute(s -> {
            PurchaseOrderId id = new PurchaseOrderId(po);
            PurchaseOrder order = orders.findByIdForUpdate(id).orElseThrow();
            var proposal = order.proposeAmendment(new PurchaseOrder.AmendmentRequest(EXPECTED.plusDays(3),
                    "Delivered in two trucks", "Supplier can deliver less", List.of(lines)));
            long revisionNo = orders.nextRevisionNo(id);
            UUID revisionId = orders.recordAmendment(order, revisionNo, actor, proposal);
            order.requestAmendment(revisionId);
            orders.save(order);
            return new Recorded(revisionId, revisionNo);
        });
    }

    private void apply(UUID po, Recorded amendment) {
        tx.executeWithoutResult(s -> orders.applyAmendment(new PurchaseOrderId(po), amendment.revisionId(),
                amendment.revisionNo()));
    }

    private Map<String, Object> line(UUID lineId) {
        return jdbc.queryForMap("""
                SELECT ordered_qty, unit_price, status, po_revision_id, uom_conversion_id, unit_qty, line_total
                  FROM procurement.purchase_order_lines WHERE id = ?""", lineId);
    }

    private Map<String, Object> header(UUID po) {
        return jdbc.queryForMap("""
                SELECT subtotal, tax_total, total_amount, expected_date, note, pending_revision_id, active_revision_id
                  FROM procurement.purchase_orders WHERE id = ?""", po);
    }

    // ------------------------------------------------------------------ recording

    @Test
    @DisplayName("recordAmendment freezes the proposed lines and header in an AMENDMENT revision; the live lines keep "
            + "what was ordered")
    void recordedAmendmentFreezesProposedLinesNotLiveOnes() throws Exception {
        String a = newSku();
        String b = newSku();
        UUID po = order("APPROVED", Line.of(a, 10, 1000), Line.of(b, 5, 2000));

        Recorded amendment = request(po, keep(lineOf(po, a), 8), keep(lineOf(po, b), 5));

        assertThat((BigDecimal) jdbc.queryForObject("""
                SELECT ordered_qty FROM procurement.purchase_order_line_revisions
                 WHERE po_revision_id = ? AND po_line_id = ?""", BigDecimal.class, amendment.revisionId(), lineOf(po, a)))
                .isEqualByComparingTo("8");
        assertThat((BigDecimal) line(lineOf(po, a)).get("ordered_qty")).as("live line untouched")
                .isEqualByComparingTo("10");
        Map<String, Object> revision = jdbc.queryForMap("""
                SELECT kind, revision_no, change_summary, changed_by, snapshot_header::text AS header
                  FROM procurement.purchase_order_revisions WHERE id = ?""", amendment.revisionId());
        assertThat(revision).containsEntry("kind", "AMENDMENT").containsEntry("revision_no", 1L)
                .containsEntry("change_summary", "Supplier can deliver less").containsEntry("changed_by", actor);
        JsonNode snapshot = json.readTree((String) revision.get("header"));
        assertThat(snapshot.path("totalAmount").decimalValue()).isEqualByComparingTo("18000");
        assertThat(snapshot.path("previousTotalAmount").decimalValue()).isEqualByComparingTo("20000");
        assertThat(snapshot.path("expectedDate").asText()).isEqualTo(EXPECTED.plusDays(3).toString());
        assertThat(snapshot.path("note").asText()).isEqualTo("Delivered in two trucks");
        assertThat(header(po)).containsEntry("pending_revision_id", amendment.revisionId());
    }

    // ------------------------------------------------------------------ applying

    @Test
    @DisplayName("applyAmendment on a CONFIRMED order passes guard_po_line_content_change: kept lines take the "
            + "revision's content and point at it, a removed line is CANCELLED, and the totals follow the kept lines")
    void appliedAmendmentPassesTheLineGuard() {
        String a = newSku();
        String b = newSku();
        UUID po = order("CONFIRMED", Line.of(a, 10, 1000), Line.of(b, 5, 2000));
        UUID kept = lineOf(po, a);
        UUID removed = lineOf(po, b);
        Recorded amendment = request(po, new PurchaseOrder.AmendmentLine(kept, 8, new BigDecimal("1100"), null,
                "Short delivery"));

        apply(po, amendment);

        Map<String, Object> keptRow = line(kept);
        assertThat((BigDecimal) keptRow.get("ordered_qty")).isEqualByComparingTo("8");
        assertThat((BigDecimal) keptRow.get("unit_price")).isEqualByComparingTo("1100");
        assertThat(keptRow).containsEntry("po_revision_id", amendment.revisionId()).containsEntry("status", "OPEN");
        assertThat(jdbc.queryForObject("SELECT note FROM procurement.purchase_order_lines WHERE id = ?", String.class,
                kept)).isEqualTo("Short delivery");
        assertThat(line(removed)).containsEntry("status", "CANCELLED");
        Map<String, Object> header = header(po);
        assertThat((BigDecimal) header.get("total_amount")).as("ck_purchase_orders_total holds over the kept line")
                .isEqualByComparingTo("8800");
        assertThat((BigDecimal) header.get("subtotal")).isEqualByComparingTo("8800");
        assertThat(header.get("expected_date").toString()).isEqualTo(EXPECTED.plusDays(3).toString());
        assertThat(header).containsEntry("note", "Delivered in two trucks");
    }

    @Test
    @DisplayName("a line ordered in a carton keeps its unit through an amendment; only the count of cartons follows")
    void appliedAmendmentKeepsPackingUnit() {
        String sku = newSku();
        UUID carton = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
                SELECT ?, id, 'CARTON', 10 FROM inventory.inventory_items WHERE sku = ?""", carton, sku));
        UUID po = order("CONFIRMED", new Line(sku, 50, 1000, carton, 5));

        apply(po, request(po, keep(lineOf(po, sku), 30)));

        Map<String, Object> row = line(lineOf(po, sku));
        assertThat(row).containsEntry("uom_conversion_id", carton).containsEntry("unit_qty", 3);
        assertThat((BigDecimal) row.get("ordered_qty")).isEqualByComparingTo("30");
    }

    // ------------------------------------------------------------------ reading and dropping

    @Test
    @DisplayName("pendingAmendment reads back who asked, when, why, and the total the order would have")
    void pendingAmendmentReadsBackTheRequest() {
        String a = newSku();
        UUID po = order("APPROVED", Line.of(a, 10, 1000));

        Recorded amendment = request(po, keep(lineOf(po, a), 7));

        var pending = tx.execute(s -> orders.pendingAmendment(new PurchaseOrderId(po))).orElseThrow();
        assertThat(pending.revisionId()).isEqualTo(amendment.revisionId());
        assertThat(pending.revisionNo()).isEqualTo(1);
        assertThat(pending.requestedBy()).isEqualTo(actor);
        assertThat(pending.requestedAt()).isNotNull();
        assertThat(pending.reason()).isEqualTo("Supplier can deliver less");
        assertThat(pending.totalAmount()).isEqualByComparingTo("7000");
    }

    @Test
    @DisplayName("dropAmendment clears the pending pointer and nothing else")
    void droppedAmendmentClearsThePointer() {
        String a = newSku();
        UUID po = order("APPROVED", Line.of(a, 10, 1000));
        UUID active = (UUID) header(po).get("active_revision_id");
        request(po, keep(lineOf(po, a), 7));

        tx.executeWithoutResult(s -> orders.dropAmendment(new PurchaseOrderId(po)));

        assertThat(header(po)).containsEntry("pending_revision_id", null).containsEntry("active_revision_id", active);
        Optional<PurchaseOrderRepository.PendingAmendment> pending =
                tx.execute(s -> orders.pendingAmendment(new PurchaseOrderId(po)));
        assertThat(pending).isEmpty();
        assertThat((BigDecimal) line(lineOf(po, a)).get("ordered_qty")).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("revisionLines reads a revision's lines in line order, whatever order the request named them in")
    void revisionLinesReadBackInLineOrder() {
        String a = newSku();
        String b = newSku();
        UUID po = order("APPROVED", Line.of(a, 10, 1000), Line.of(b, 5, 2000));

        Recorded amendment = request(po, keep(lineOf(po, b), 4), keep(lineOf(po, a), 8));

        List<PurchaseOrderRepository.RevisionLine> lines = tx.execute(s -> orders.revisionLines(amendment.revisionId()));
        assertThat(lines).extracting(PurchaseOrderRepository.RevisionLine::lineNo).containsExactly(1, 2);
        assertThat(lines).extracting(PurchaseOrderRepository.RevisionLine::sku).containsExactly(a, b);
        assertThat(lines).extracting(PurchaseOrderRepository.RevisionLine::quantityOrdered).containsExactly(8, 4);
        assertThat(lines.get(1).unitPrice()).isEqualByComparingTo("2000");
        assertThat(lines.get(0).lineId()).isEqualTo(lineOf(po, a));
    }
}
