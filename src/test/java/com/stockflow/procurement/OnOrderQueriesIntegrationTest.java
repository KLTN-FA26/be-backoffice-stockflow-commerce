package com.stockflow.procurement;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.IncomingStockQueries;
import com.stockflow.procurement.api.OnOrderQueries;
import com.stockflow.procurement.api.SkuOnOrder;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a reorder suggestion subtracts as already coming (ADR-0010, design §8), against real purchase orders and a
 * real goods receipt: the open part of an approved order, a draft reported apart, a cancelled order not at all, and
 * received goods moving from "on order" to "inbound" rather than vanishing from both. The database is shared with
 * other tests that order the same demo SKU, so every figure is a difference from before; the supplier's refusal and
 * lateness are pinned on a SKU of their own, where every figure is exact.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class OnOrderQueriesIntegrationTest {

    private static final String SOFA = "SOFA-3S-GREY"; // no QC: a confirmed receipt leaves it INBOUND

    @Autowired OnOrderQueries onOrder;
    @Autowired IncomingStockQueries incoming;
    @Autowired GoodsReceipts receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    @Test
    @DisplayName("an approved order counts its unreceived part, a draft is reported apart, a cancelled one not at all")
    void openPurchasing() {
        LocalDate today = LocalDate.now();
        SkuOnOrder before = onOrder.onOrder(List.of(SOFA)).getFirst();
        IncomingStockQueries.IncomingStock arrivedBefore = incoming.incoming(List.of(SOFA)).getFirst();

        UUID confirmed = order("CONFIRMED", today.plusDays(10), 500);
        order("PENDING_APPROVAL", today.plusDays(3), 100);
        order("CANCELLED", today.plusDays(1), 999);
        UUID clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID line = jdbc.queryForObject("SELECT id FROM procurement.purchase_order_lines WHERE po_id = ?",
                UUID.class, confirmed);
        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(confirmed, "DN-ONORDER", null),
                clerk);
        receipts.replaceLines(draft.id(), List.of(new GoodsReceipts.NewLine(line, 200, null, null, "HCM-RCV01",
                null)));
        receipts.confirm(draft.id(), clerk);

        SkuOnOrder after = onOrder.onOrder(List.of(SOFA)).getFirst();
        assertThat(after.onOrder() - before.onOrder()).isEqualTo(300);
        assertThat(after.awaitingApproval() - before.awaitingApproval()).isEqualTo(100);
        assertThat(after.nextExpectedDate()).isNotNull().isBeforeOrEqualTo(today.plusDays(10));
        // The 200 received are not lost between the two figures: they wait in receiving.
        assertThat(incoming.incoming(List.of(SOFA)).getFirst().inbound() - arrivedBefore.inbound()).isEqualTo(200);
    }

    @Test
    @DisplayName("an order the supplier refused is not on order: its units are reported apart, and its date is "
            + "not the next one")
    void supplierRefusal() {
        String sku = new AlertFixtures(jdbc, tx, entityManager).sku(null, null);
        LocalDate today = BusinessCalendar.date(Instant.now());

        order(sku, "CONFIRMED", "CONFIRMED", today.plusDays(10), 500);
        order(sku, "CONFIRMED", "REJECTED", today.plusDays(2), 70);

        assertThat(onOrder.onOrder(List.of(sku)).getFirst())
                .as("the refused 70 neither count as coming nor set the next delivery date")
                .isEqualTo(new SkuOnOrder(sku, 500, 0, today.plusDays(10), 0, 70));
    }

    @Test
    @DisplayName("a late order is still on order, its units flagged as overdue once the supplier has it; an order "
            + "approved but not sent is never overdue; a line withdrawn from a draft is no longer awaiting approval")
    void latenessAndWithdrawnLines() {
        String sku = new AlertFixtures(jdbc, tx, entityManager).sku(null, null);
        LocalDate today = BusinessCalendar.date(Instant.now());

        order(sku, "CONFIRMED", "PENDING", today.plusDays(10), 500);
        order(sku, "APPROVED", "NOT_SENT", today.minusDays(5), 40); // past its date, but never sent: not late
        order(sku, "CONFIRMED", "CONFIRMED", today.minusDays(2), 15);
        order(sku, "CONFIRMED", "CONFIRMED", today, 25); // due today: not late yet
        UUID pending = order(sku, "PENDING_APPROVAL", "NOT_SENT", today.plusDays(3), 30);
        withdrawnLine(pending, sku, 900);

        assertThat(onOrder.onOrder(List.of(sku)).getFirst())
                .isEqualTo(new SkuOnOrder(sku, 580, 30, today.minusDays(5), 15, 0));
    }

    @Test
    @DisplayName("every SKU asked is answered once, in order, an unknown one with zeros")
    void everySkuOnce() {
        assertThat(onOrder.onOrder(List.of("NO-SUCH-SKU", SOFA, "NO-SUCH-SKU")))
                .extracting(SkuOnOrder::sku).containsExactly("NO-SUCH-SKU", SOFA);
        assertThat(onOrder.onOrder(List.of("NO-SUCH-SKU")).getFirst())
                .isEqualTo(new SkuOnOrder("NO-SUCH-SKU", 0, 0, null, 0, 0));
        assertThat(incoming.incoming(List.of("NO-SUCH-SKU")))
                .containsExactly(new IncomingStockQueries.IncomingStock("NO-SUCH-SKU", 0, 0, 0));
    }

    /** A standard order on the demo supplier and HCM, one line of {@code quantity} sofas; returns its id. */
    private UUID order(String status, LocalDate expected, int quantity) {
        return order(SOFA, status, "NOT_SENT", expected, quantity);
    }

    /**
     * A standard order on the demo supplier and HCM, one line of {@code quantity} of {@code sku}, with the supplier's
     * answer {@code supplierAnswer} (a refusal carries the reason the database requires); returns its id. An order
     * expected in the past was placed a week before that date, as {@code ck_purchase_orders_dates} wants.
     */
    private UUID order(String sku, String status, String supplierAnswer, LocalDate expected, int quantity) {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        boolean approved = !status.equals("PENDING_APPROVAL") && !status.equals("CANCELLED");
        boolean sent = status.equals("CONFIRMED");
        boolean answered = supplierAnswer.equals("CONFIRMED") || supplierAnswer.equals("REJECTED");
        LocalDate today = BusinessCalendar.date(Instant.now());
        LocalDate ordered = expected.isBefore(today) ? expected.minusDays(7) : today;
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, expected_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at,
                        confirmed_by, cancel_reason, active_revision_id, supplier_confirmation_status,
                        supplier_responded_at, supplier_response_note, version, created_at)
                    SELECT ?, ?, ?, s.id, w.id, 'VND', ?, ?, NOW(), ?,
                        CASE WHEN ? THEN NOW() END, ?, CASE WHEN ? THEN NOW() END, ?, ?, ?, ?,
                        CASE WHEN ? THEN NOW() END, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, "PO-ONORD-" + po.toString().substring(24), status, Date.valueOf(ordered),
                    Date.valueOf(expected), submitter, approved, approved ? approver : null, sent,
                    sent ? approver : null, status.equals("CANCELLED") ? "test" : null, revision, supplierAnswer,
                    answered, supplierAnswer.equals("REJECTED") ? "out of raw material" : null);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, status, version, created_at)
                    SELECT ?, ?, ?, 1, id, ?, 1000, ?, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                    Identifiers.newId(), po, revision, quantity, status.equals("CANCELLED") ? "CANCELLED" : "OPEN",
                    sku);
        });
        return po;
    }

    /** A second line on {@code po}, taken off it again (SCRUM-462): it stays only for the revision that froze it. */
    private void withdrawnLine(UUID po, String sku, int quantity) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                    ordered_qty, unit_price, status, version, created_at)
                SELECT ?, o.id, o.active_revision_id, 2, i.id, ?, 1000, 'WITHDRAWN', 0, NOW()
                  FROM procurement.purchase_orders o, inventory.inventory_items i WHERE o.id = ? AND i.sku = ?""",
                Identifiers.newId(), quantity, po, sku));
    }
}
