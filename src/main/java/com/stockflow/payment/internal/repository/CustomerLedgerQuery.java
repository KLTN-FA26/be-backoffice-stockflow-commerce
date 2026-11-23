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
     * @param reduced   what reductions took off it, all of them; {@code amount - reduced} is what it charges now
     * @param cancelled it was cancelled - with its order, or by reductions taking off all it owed: nothing of it is
     *                  owed any more, whatever {@code outstanding} says, so it is never overdue
     * @param carried   of a fee item, the goods money a closure of the rest moved onto it (what a before-delivery
     *                  installment of a batch it closed whole still owed, {@code order_settlement.carried_to_fee_amount})
     *                  rather than a fee: the paper words that part as what it is; 0 for every other charge
     */
    public record Charge(LocalDate day, String orderNumber, String contractNumber, int seq, String name,
                         String milestone, BigDecimal amount, BigDecimal reduced, BigDecimal outstanding,
                         LocalDate dueDate, boolean cancelled, BigDecimal carried) {

        /** A charge carrying no goods money onto a fee item. */
        public Charge(LocalDate day, String orderNumber, String contractNumber, int seq, String name, String milestone,
                      BigDecimal amount, BigDecimal reduced, BigDecimal outstanding, LocalDate dueDate,
                      boolean cancelled) {
            this(day, orderNumber, contractNumber, seq, name, milestone, amount, reduced, outstanding, dueDate,
                    cancelled, BigDecimal.ZERO);
        }

        /** A cancellation's or a failed trip's fee rather than a row of the contract's plan. */
        public boolean feeItem() {
            return milestone.endsWith("_FEE");
        }
    }

    /**
     * A receipt line in force: the statement's day and reference, and the installment it paid. {@code carriesGoods}: a
     * fee item that carries goods money a closure of the rest moved onto it ({@link Charge#carried}).
     */
    public record Receipt(LocalDate day, String reference, String contractNumber, int seq, boolean feeItem,
                          BigDecimal amount, boolean carriesGoods) {

        public Receipt(LocalDate day, String reference, String contractNumber, int seq, boolean feeItem,
                       BigDecimal amount) {
            this(day, reference, contractNumber, seq, feeItem, amount, false);
        }
    }

    /**
     * The goods money closures moved onto fee item {@code i} (audit round 3, with group r3a's
     * {@code order_settlement.carried_to_fee_amount}). Read through {@code to_jsonb} so the statement runs whether or
     * not that column exists yet in the schema it meets: absent, nothing was carried.
     */
    private static final String CARRIED = """
            (SELECT COALESCE(SUM((to_jsonb(s) ->> 'carried_to_fee_amount')::numeric), 0)
               FROM payment.order_settlement s WHERE s.fee_installment_id = i.id)""";

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

    /**
     * Where a contract's payment plan stood at one moment (audit round 3, RAD-8): its signed value, what settlements had
     * taken off it by then, and what had been paid on it by then.
     *
     * @param contractValue the plan's installments at their signed amounts - what the contract is worth (14 BR-13:
     *                      they add up to the order's total with tax); fee items are not the contract's
     * @param reduced       what settlements made by then took off its installments (returns, carrier returns, closures)
     * @param paid          what receipts in force, recorded by then, paid on its installments
     */
    public record StandingAt(BigDecimal contractValue, BigDecimal reduced, BigDecimal paid) {
    }

    private final JdbcTemplate jdbc;

    CustomerLedgerQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Which installments the ledger charges: every one reached, whatever became of it since. A charge is a fact of the
     * day its milestone came; what ended it later - reductions, the cancellation of its order - is entered on its own
     * day ({@link #reductions}, and the cancellation's write-off in {@code PaymentPaperService}).
     *
     * <p>Audit round 3, RAD-7: an installment cancelled with its order used to be left out of every period, its
     * receipts kept: October's signed record closing at 20M owed (30M reached, 10M paid) re-ran at -10M once the order
     * was cancelled in November, and the cancellation's own write-off line, summed over charges that no longer held the
     * installment, was never printed. The same principle audit r2 money M-3 applied to reductions.</p>
     */
    private static final String CHARGED = "i.reached_at IS NOT NULL";

    /** The installments charged ({@link #CHARGED}), at their whole amount, oldest first. */
    public List<Charge> charges(UUID customerId) {
        return jdbc.query("""
                SELECT CAST(i.reached_at AT TIME ZONE 'Asia/Ho_Chi_Minh' AS date) AS day, i.order_number,
                       i.contract_number, i.seq, i.name, i.milestone, i.amount, i.reduced_amount,
                       i.amount - i.reduced_amount - i.paid_amount AS outstanding, i.due_date,
                       i.status = 'CANCELLED' AS cancelled, %s AS carried
                  FROM payment.installment i
                 WHERE i.customer_id = ? AND %s
                 ORDER BY i.reached_at, i.contract_number, i.seq""".formatted(CARRIED, CHARGED), (rs, n) -> new Charge(
                rs.getObject("day", LocalDate.class), rs.getString("order_number"), rs.getString("contract_number"),
                rs.getInt("seq"), rs.getString("name"), rs.getString("milestone"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("reduced_amount"), rs.getBigDecimal("outstanding"),
                rs.getObject("due_date", LocalDate.class), rs.getBoolean("cancelled"), rs.getBigDecimal("carried")),
                customerId);
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
                       i.milestone IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE') AS fee_item, l.amount,
                       %s > 0 AS carries_goods
                  FROM payment.payment_receipt_line l
                  JOIN payment.payment_receipt r ON r.id = l.receipt_id
                  JOIN payment.installment i ON i.id = l.installment_id
                 WHERE r.customer_id = ? AND r.status = 'RECORDED'
                 ORDER BY r.received_on, r.recorded_at, i.seq""".formatted(CARRIED), (rs, n) -> new Receipt(
                rs.getObject("received_on", LocalDate.class), rs.getString("reference"),
                rs.getString("contract_number"), rs.getInt("seq"), rs.getBoolean("fee_item"),
                rs.getBigDecimal("amount"), rs.getBoolean("carries_goods")), customerId);
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

    /**
     * Contract {@code contractId}'s plan as it stood at {@code at} (audit round 3, RAD-8): read from the installments'
     * signed amounts, the reductions of settlements made by then and the receipts recorded by then, so a confirmation
     * printed again later says what it said the day it was issued. A receipt voided since is not counted: a void says it
     * was recorded wrong, never paid.
     */
    public StandingAt standingAt(UUID contractId, java.time.Instant at) {
        java.sql.Timestamp moment = java.sql.Timestamp.from(at);
        return jdbc.queryForObject("""
                SELECT (SELECT COALESCE(SUM(i.amount), 0) FROM payment.installment i
                         WHERE i.contract_id = ? AND i.milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE'))
                           AS contract_value,
                       (SELECT COALESCE(SUM(r.amount), 0) FROM payment.installment_reduction r
                          JOIN payment.order_settlement s ON s.id = r.settlement_id
                          JOIN payment.installment i ON i.id = r.installment_id
                         WHERE i.contract_id = ? AND s.settled_at <= ?) AS reduced,
                       (SELECT COALESCE(SUM(l.amount), 0) FROM payment.payment_receipt_line l
                          JOIN payment.payment_receipt p ON p.id = l.receipt_id
                          JOIN payment.installment i ON i.id = l.installment_id
                         WHERE i.contract_id = ? AND i.milestone NOT IN ('CANCELLATION_FEE', 'FAILED_TRIP_FEE')
                           AND p.status = 'RECORDED' AND p.recorded_at <= ?) AS paid""",
                (rs, n) -> new StandingAt(rs.getBigDecimal("contract_value"), rs.getBigDecimal("reduced"),
                        rs.getBigDecimal("paid")), contractId, contractId, moment, contractId, moment);
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
