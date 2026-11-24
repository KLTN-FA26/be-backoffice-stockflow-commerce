package com.stockflow.payment.internal.service;

import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.DocumentSamples;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.SettleCancellationCommand;
import com.stockflow.payment.api.SettleRemainderClosureCommand;
import com.stockflow.payment.api.SettleShipmentReturnCommand;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * WF-05 audit round 3 on the money papers, each against the books a real run leaves: a paper says what was true when
 * the event it records happened, and says the same each time it is printed again.
 * <ul>
 *   <li>RAD-6: the refund confirmation prints the account the refund was paid to, as recorded with it - not the
 *       customer's profile today; a refund recorded before the account was kept names the transaction reference;</li>
 *   <li>RAD-7: the debt reconciliation charges an installment its order's cancellation ended later on the day it fell
 *       due, so a period signed before the cancellation closes as it did, and the cancellation's own write-off line is
 *       printed;</li>
 *   <li>RAD-8: the payment confirmation prints the contract's signed value, what settlements had taken off it and what
 *       had been paid by the time the receipt was recorded - never today's figures.</li>
 * </ul>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class PaymentPapersRound3IntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ResourceBundle T = DocumentParts.bundle();
    private static final List<SalesContracts.Installment> HALF_AND_HALF = List.of(
            new SalesContracts.Installment("Đặt cọc khi ký", "ON_SIGNING", null, new BigDecimal("50"), 0, null),
            new SalesContracts.Installment("Sau giao hàng", "AFTER_DELIVERY", null, new BigDecimal("50"), 30, null));

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired PaymentService payments;
    @Autowired RefundService refunds;
    @Autowired CustomerService customers;
    @Autowired PaymentPaperService papers;
    @MockitoSpyBean DebtReconciliationDocument debtPaper;

    private ConfirmedOrders orders;
    private UUID accountant;
    private UUID customer;
    private ConfirmedOrders.Signed signed;
    private BigDecimal deposit;
    private LocalDate today;

    @BeforeEach
    void signed() {
        orders = new ConfirmedOrders(context);
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID owner = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, tax_code, status, version, created_at)
                VALUES (?, ?, 'Công ty Cổ phần Cà phê Mộc Lam', ?, ?, 'ACTIVE', 0, NOW())""", customer, owner,
                "r3-" + customer + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                        .nextInt(89_999_999))));
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("1000000000"), 30,
                "ample credit", accountant, null));
        var sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        signed = orders.signed(customer, HALF_AND_HALF, ConfirmedOrders.Line.cartons(sku, 10, 40_000));
        deposit = jdbc.queryForObject("SELECT amount FROM payment.installment WHERE id = ?", BigDecimal.class,
                signed.seq(1));
        today = LocalDate.now(VIETNAM);
    }

    private static String money(BigDecimal amount) {
        return DocumentFormats.money(amount);
    }

    private static BigDecimal half(BigDecimal amount) {
        return amount.divide(BigDecimal.TWO, 0, RoundingMode.DOWN);
    }

    private String text(String sample, PaymentPaperService.Pdf pdf) {
        try {
            return DocumentSamples.text(DocumentSamples.keep(sample, pdf.content())).replaceAll("\\s+", " ");
        } catch (java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private void closeRemainder(BigDecimal worth) {
        tx.execute(s -> payments.settleRemainderClosure(new SettleRemainderClosureCommand(signed.orderId(),
                Identifiers.newId(), worth, null, null, null, accountant, Instant.now(), null,
                SettleShipmentReturnCommand.ShippingFee.NONE)));
    }

    // ---- RAD-8 ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RAD-8: a payment confirmation prints the signed contract value and what was paid when the receipt "
            + "was recorded; a reduction and a later receipt change nothing on it, and a later confirmation shows the "
            + "reduction made before it on a column of its own")
    void paymentConfirmationIsAsOfItsReceipt() {
        BigDecimal first = half(deposit);
        BigDecimal second = deposit.subtract(first);
        PaymentReceipt early = orders.pay(customer, signed.seq(1), first);
        BigDecimal afterDelivery = jdbc.queryForObject("SELECT amount FROM payment.installment WHERE id = ?",
                BigDecimal.class, signed.seq(2));
        BigDecimal closed = afterDelivery.divide(BigDecimal.valueOf(4), 0, RoundingMode.DOWN);
        closeRemainder(closed);
        PaymentReceipt late = orders.pay(customer, signed.seq(1), second);

        String earlyPaper = text("r3-payment-confirmation-first-receipt",
                papers.paymentConfirmation(signed.seq(1), early.id(), null));
        assertThat(earlyPaper).as("the contract as signed, the money paid by then, what was left then")
                .contains(money(signed.total()), money(first), money(signed.total().subtract(first)))
                .doesNotContain(T.getString("payment-confirmation.pdf.col.reduced"))
                .doesNotContain(money(signed.total().subtract(closed)));

        String latePaper = text("r3-payment-confirmation-after-reduction",
                papers.paymentConfirmation(signed.seq(1), late.id(), null));
        assertThat(latePaper).contains(money(signed.total()), T.getString("payment-confirmation.pdf.col.reduced"),
                money(closed), money(deposit), money(signed.total().subtract(closed).subtract(deposit)));

        assertThat(text("r3-payment-confirmation-first-receipt-again",
                papers.paymentConfirmation(signed.seq(1), early.id(), null)))
                .as("printed again, the same paper").isEqualTo(earlyPaper);
    }

    // ---- RAD-6 ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RAD-6: the refund confirmation names the account the money went to as recorded with the refund - a "
            + "profile changed later does not change it - and a refund kept before accounts were recorded names the "
            + "transaction reference instead of today's account; the schema keeps a number with its bank")
    void refundConfirmationKeepsItsAccount() {
        // The profile signing the contract needed, with the account the customer gives for refunds.
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE customer.customer_legal_profile SET bank_account_number = '0071000111222',
                       bank_name = 'Vietcombank', bank_account_holder = 'CONG TY CP CA PHE MOC LAM'
                 WHERE customer_id = ?""", customer));
        orders.pay(customer, signed.seq(1), deposit);
        closeRemainder(signed.total());
        String reference = "FTR3" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 40, 1L << 41), 36)
                .toUpperCase();
        refunds.record(new RefundService.RecordRefundCommand(signed.orderId(), deposit, today, reference, null,
                accountant));
        UUID record = jdbc.queryForObject("SELECT id FROM payment.refund_record WHERE order_id = ?", UUID.class,
                signed.orderId());
        // The transfer bounced elsewhere, say: the customer gives another account afterwards.
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE customer.customer_legal_profile SET bank_account_number = '9990000333444', bank_name = 'ACB',
                       bank_account_holder = 'CONG TY CP CA PHE MOC LAM' WHERE customer_id = ?""", customer));

        assertThat(text("r3-refund-confirmation-recorded-account", papers.refundConfirmation(record, null)))
                .contains("0071000111222", "Vietcombank").doesNotContain("9990000333444");

        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE payment.refund_record SET payee_account_number = NULL, payee_bank_name = NULL,
                       payee_account_holder = NULL WHERE id = ?""", record));
        assertThat(text("r3-refund-confirmation-before-accounts-were-kept", papers.refundConfirmation(record, null)))
                .as("a record of before: no account guessed from today's profile")
                .contains(T.getString("payment-papers.accountByReference")).doesNotContain("9990000333444")
                .doesNotContain("0071000111222");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE payment.refund_record SET payee_account_number = '123456' WHERE id = ?", record))))
                .as("ck_refund_record_payee_account").hasMessageContaining("ck_refund_record_payee_account");
    }

    // ---- RAD-7 ------------------------------------------------------------------------------------------------------

    private DebtReconciliationDocument.Facts reconcile(LocalDate from, LocalDate to, String sample) {
        DocumentSamples.keep(sample, papers.debtReconciliation(customer, from, to).content());
        ArgumentCaptor<DebtReconciliationDocument.Facts> facts =
                ArgumentCaptor.forClass(DebtReconciliationDocument.Facts.class);
        verify(debtPaper, atLeastOnce()).render(facts.capture());
        return facts.getValue();
    }

    private static BigDecimal closing(DebtReconciliationDocument.Facts facts) {
        return facts.entries().stream().map(entry -> entry.debit().subtract(entry.credit()))
                .reduce(facts.opening(), BigDecimal::add);
    }

    @Test
    @DisplayName("RAD-7: the deposit fell due and was partly paid in an earlier period; the order is cancelled later - "
            + "the earlier period still closes at what was owed then, the later one opens there and shows the "
            + "cancellation writing the deposit off, and the refund of what was paid brings it to nothing")
    void aCancelledInstallmentStaysInThePeriodItFellDue() {
        BigDecimal paid = deposit.divide(BigDecimal.valueOf(3), 0, RoundingMode.DOWN);
        PaymentReceipt receipt = orders.pay(customer, signed.seq(1), paid);
        Instant reached = today.minusDays(40).atTime(10, 0).atZone(VIETNAM).toInstant();
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE payment.installment SET reached_at = ?, due_date = ? WHERE id = ?",
                    Timestamp.from(reached), Date.valueOf(today.minusDays(40)), signed.seq(1));
            jdbc.update("UPDATE payment.payment_receipt SET received_on = ? WHERE id = ?",
                    Date.valueOf(today.minusDays(35)), receipt.id());
        });
        // The order module cancels the order first, in the transaction that asks payment to settle it.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST',
                           cancellation_reason = 'Khách huỷ đơn' WHERE id = ?""", signed.orderId());
        });
        Instant cancelled = today.minusDays(5).atTime(10, 0).atZone(VIETNAM).toInstant().truncatedTo(ChronoUnit.MICROS);
        tx.execute(s -> payments.settleCancellation(new SettleCancellationCommand(signed.orderId(), null, null, null,
                null, accountant, cancelled)));
        assertThat(jdbc.queryForObject("SELECT status FROM payment.installment WHERE id = ?", String.class,
                signed.seq(1))).isEqualTo("CANCELLED");

        LocalDate earlierFrom = today.minusDays(50);
        LocalDate earlierTo = today.minusDays(20);
        DebtReconciliationDocument.Facts earlier = reconcile(earlierFrom, earlierTo, "r3-debt-reconciliation-before");
        assertThat(closing(earlier)).as("what both parties signed for the earlier period")
                .isEqualByComparingTo(deposit.subtract(paid));

        DebtReconciliationDocument.Facts later = reconcile(today.minusDays(19), today,
                "r3-debt-reconciliation-cancelled");
        assertThat(later.opening()).as("carried forward").isEqualByComparingTo(deposit.subtract(paid));
        String writeOff = T.getString("payment-papers.debt.cancelled").formatted(signed.orderNumber());
        assertThat(later.entries()).anySatisfy(entry -> {
            assertThat(entry.description()).isEqualTo(writeOff);
            assertThat(entry.date()).isEqualTo(today.minusDays(5));
            assertThat(entry.credit()).isEqualByComparingTo(deposit);
        });
        assertThat(closing(later)).as("what was paid is the customer's to have back")
                .isEqualByComparingTo(paid.negate());
        assertThat(later.overdue()).as("nothing cancelled is overdue").isEqualByComparingTo("0");

        String reference = "FTR3C" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 40, 1L << 41), 36)
                .toUpperCase();
        refunds.record(new RefundService.RecordRefundCommand(signed.orderId(), paid, today, reference, null,
                accountant));
        assertThat(closing(reconcile(today.minusDays(19), today, "r3-debt-reconciliation-refunded")))
                .isEqualByComparingTo("0");
        assertThat(closing(reconcile(earlierFrom, earlierTo, "r3-debt-reconciliation-before-again")))
                .as("printed again after it all").isEqualByComparingTo(deposit.subtract(paid));
    }
}
