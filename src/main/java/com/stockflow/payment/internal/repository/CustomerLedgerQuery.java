package com.stockflow.payment.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything that ever moved one customer's debt, for the record that reconciles it: read whole, in five statements
 * over the payment module's own tables, because a reconciliation's opening balance is the sum of all that came before
 * its period and a page of a search cannot give that.
 *
 * <p>Days are business days (Asia/Ho_Chi_Minh): a milestone reached at 23:30 UTC belongs to the next day's ledger, as
 * its due day is counted.</p>
 */
@Repository
public class CustomerLedgerQuery {

    /**
     * An installment that fell due: the day its milestone was reached, and what it charged then - its whole amount, the
     * reductions made since being {@link Reduction}s of their own days (audit r2 money M-3).
     *
     * @param reduced what reductions took off it, all of them; {@code amount - reduced} is what it charges now
     */
    public record Charge(LocalDate day, String orderNumber, String contractNumber, int seq, String name,
                         String milestone, BigDecimal amount, BigDecimal reduced, BigDecimal outstanding,
                         LocalDate dueDate) {

        /** A cancellation's or a failed trip's fee rather than a row of the contract's plan. */
        public boolean feeItem() {
            return milestone.endsWith("_FEE");
        }
    }

    /** A receipt line in force: the statement's day and reference, and the installment it paid. */
    public record Receipt(LocalDate day, String reference, String contractNumber, int seq, boolean feeItem,
                          BigDecimal amount) {
    }

    /**
     * A settlement: what it kept of the money collected as a fee, and what it made refundable.
     *
     * @param shipmentId SHIPMENT_RETURNED: the shipment brought back or lost; null for every other kind
     */
    public record Settlement(LocalDate day, String kind, String orderNumber, String contractNumber,
                             BigDecimal feeCovered, BigDecimal refundable, UUID shipmentId) {
    }

    /**
     * What one settlement took off one installment ({@code payment.installment_reduction}), on the day it did: the
     * settlement's, or the installment's own when it was reached later - a reduction cannot lower a debt before it was
     * charged.
     *
     * @param shipmentId SHIPMENT_RETURNED: the shipment brought back or lost; null for every other kind
     */
    public record Reduction(LocalDate day, String kind, String orderNumber, String contractNumber, UUID shipmentId,
                            int seq, BigDecimal amount) {
    }

    /** A refund in force. */
    public record Refund(LocalDate day, String reference, String orderNumber, BigDecimal amount) {
    }

    private final JdbcTemplate jdbc;

    CustomerLedgerQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Which installments the ledger charges: reached, and not cancelled - unless reductions cancelled it, taking off
     * all it owed ({@code Installment.reduce}): it was charged, and the reductions are entered on their own days. One
     * cancelled with its order is left out with its reductions, as before: the cancellation's settlement writes off
     * what the order charged.
     */
    private static final String CHARGED = "i.reached_at IS NOT NULL AND (i.status <> 'CANCELLED' "
            + "OR i.reduced_amount = i.amount)";

    /** The installments charged ({@link #CHARGED}), at their whole amount, oldest first. */
    public List<Charge> charges(UUID customerId) {
        return jdbc.query("""
                SELECT CAST(i.reached_at AT TIME ZONE 'Asia/Ho_Chi_Minh' AS date) AS day, i.order_number,
                       i.contract_number, i.seq, i.name, i.milestone, i.amount, i.reduced_amount,
                       i.amount - i.reduced_amount - i.paid_amount AS outstanding, i.due_date
                  FROM payment.installment i
                 WHERE i.customer_id = ? AND %s
                 ORDER BY i.reached_at, i.contract_number, i.seq""".formatted(CHARGED), (rs, n) -> new Charge(
                rs.getObject("day", LocalDate.class), rs.getString("order_number"), rs.getString("contract_number"),
                rs.getInt("seq"), rs.getString("name"), rs.getString("milestone"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("reduced_amount"), rs.getBigDecimal("outstanding"),
                rs.getObject("due_date", LocalDate.class)), customerId);
    }

    /**
     * The reductions of the installments charged, each on its own day (audit r2 money M-3): the later of its
     * settlement's and its installment's. Before, a reduction was folded into its installment's charge, so a return
     * approved in November lowered what October's reconciliation - signed by both parties - closed at.
     */
    public List<Reduction> reductions(UUID customerId) {
        return jdbc.query("""
                SELECT CAST(GREATEST(s.settled_at, i.reached_at) AT TIME ZONE 'Asia/Ho_Chi_Minh' AS date) AS day,
                       s.kind, s.order_number, s.contract_number, s.shipment_id, i.seq, r.amount
                  FROM payment.installment_reduction r
                  JOIN payment.order_settlement s ON s.id = r.settlement_id
                  JOIN payment.installment i ON i.id = r.installment_id
                 WHERE i.customer_id = ? AND %s
                 ORDER BY GREATEST(s.settled_at, i.reached_at), s.settled_at, i.seq""".formatted(CHARGED),
                (rs, n) -> new Reduction(rs.getObject("day", LocalDate.class), rs.getString("kind"),
                        rs.getString("order_number"), rs.getString("contract_number"),
                        rs.getObject("shipment_id", UUID.class), rs.getInt("seq"), rs.getBigDecimal("amount")),
                customerId);
    }

    /** The lines of the receipts in force, oldest first. */
    public List<Receipt> receipts(UUID customerId) {
        return jdbc.query("""
                SELECT r.received_on, r.reference, i.contract_number, i.seq,
                       i.milestone IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE') AS fee_item, l.amount
                  FROM payment.payment_receipt_line l
                  JOIN payment.payment_receipt r ON r.id = l.receipt_id
                  JOIN payment.installment i ON i.id = l.installment_id
                 WHERE r.customer_id = ? AND r.status = 'RECORDED'
                 ORDER BY r.received_on, r.recorded_at, i.seq""", (rs, n) -> new Receipt(
                rs.getObject("received_on", LocalDate.class), rs.getString("reference"),
                rs.getString("contract_number"), rs.getInt("seq"), rs.getBoolean("fee_item"),
                rs.getBigDecimal("amount")), customerId);
    }

    /** The settlements of the customer's orders, oldest first. */
    public List<Settlement> settlements(UUID customerId) {
        return jdbc.query("""
                SELECT CAST(s.settled_at AT TIME ZONE 'Asia/Ho_Chi_Minh' AS date) AS day, s.kind, s.order_number,
                       s.contract_number, s.fee_covered_amount, s.refundable_amount, s.shipment_id
                  FROM payment.order_settlement s
                 WHERE s.customer_id = ?
                 ORDER BY s.settled_at""", (rs, n) -> new Settlement(rs.getObject("day", LocalDate.class),
                rs.getString("kind"), rs.getString("order_number"), rs.getString("contract_number"),
                rs.getBigDecimal("fee_covered_amount"), rs.getBigDecimal("refundable_amount"),
                rs.getObject("shipment_id", UUID.class)), customerId);
    }

    /** The refunds in force, oldest first. */
    public List<Refund> refunds(UUID customerId) {
        return jdbc.query("""
                SELECT r.refunded_on, r.reference, r.order_number, r.amount
                  FROM payment.refund_record r
                 WHERE r.customer_id = ? AND r.status = 'RECORDED'
                 ORDER BY r.refunded_on, r.recorded_at""", (rs, n) -> new Refund(
                rs.getObject("refunded_on", LocalDate.class), rs.getString("reference"),
                rs.getString("order_number"), rs.getBigDecimal("amount")), customerId);
    }
}
