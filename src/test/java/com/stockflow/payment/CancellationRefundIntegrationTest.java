package com.stockflow.payment;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationFee;
import com.stockflow.order.api.CancellationOutcome;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.CancellationRequestService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.OrderSettlementResult;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.SettleRemainderClosureCommand;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.RefundRecord;
import com.stockflow.payment.internal.domain.RefundStanding;
import com.stockflow.payment.internal.service.InstallmentService;
import com.stockflow.payment.internal.service.PaymentReceiptService;
import com.stockflow.payment.internal.service.RefundService;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4B-9 (kltn-docs 15 §4.5, §4.6, BR-14..BR-16; 17 §4.8, BR-11..BR-14) against the real database: cancelling a contract
 * order before and after mass printing, the fee paid from the money collected first, the cancellation fee item, the
 * accountant's refund records up to what is refundable, and the customer's cancellation request.
 *
 * <p>Every contract is drawn up, sent and signed through {@link SalesContracts}, and every payment is a real receipt.
 * The cancellation settles the installments in its own transaction, so nothing here is awaited. Mass printing has no
 * domain path in this branch yet (4A-4 records it): {@code printing_started_at} is written by SQL, as
 * {@code AwaitingStockIntegrationTest} does - the one fact BR-11 reads.</p>
 *
 * <p>The order total is always 2,200,000 dong (1,000 pieces at 2,000 with 10% VAT); the plan is 30% on signing
 * (660,000), 40% before delivery (880,000) and 30% after delivery (660,000).</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class CancellationRefundIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(710_000);
    private static final long AMPLE_CREDIT = 100_000_000;

    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired CancellationRequestService cancellationRequests;
    @Autowired PaymentService payments;
    @Autowired InstallmentService installments;
    @Autowired PaymentReceiptService receipts;
    @Autowired RefundService refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID accountant;
    private UUID customerUser;
    private UUID customerId;
    private CustomerSkuRows.Issued sku;
    private LocalDate today;

    /** A signed contract's order and its installments' ids in the plan's order. */
    private record Signed(UUID orderId, String contractNumber, List<UUID> installmentIds) {

        UUID seq(int seq) {
            return installmentIds.get(seq - 1);
        }
    }

    @BeforeEach
    void setUp() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customerUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Cong ty Huy Don', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "refunds-" + customerId + "@example.com", "04" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '34 Le Loi, TP HCM', 'Tran Thi E', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
            jdbc.update("""
                    INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                        approved_at)
                    VALUES (?, ?, ?, 30, ?, NOW())""", Identifiers.newId(), customerId,
                    BigDecimal.valueOf(AMPLE_CREDIT), accountant);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private UUID draftOrder() {
        return tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), 1000, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), 20, 50).withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()), customerId,
                    Identifiers.newId(), List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
    }

    private static SalesContracts.Installment share(String name, String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    /** A new order with the 30 / 40 / 30 plan, signed today. */
    private Signed signed() {
        UUID orderId = draftOrder();
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(
                null, List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                List.of(new SalesContracts.BatchLine(line, 20)))), List.of(share("Deposit", "ON_SIGNING", "30", 0),
                share("Before delivery", "BEFORE_DELIVERY", "40", null),
                share("After delivery", "AFTER_DELIVERY", "30", 30)))));
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        SalesContracts.ContractView signed = asSales(() -> contracts.sign(sales, orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today));
        List<UUID> ids = jdbc.queryForList("SELECT id FROM payment.installment WHERE order_id = ? ORDER BY seq",
                UUID.class, orderId);
        return new Signed(orderId, signed.contractNumber(), ids);
    }

    /** 17 BR-11: the first production order of the order entered In Production (4A-6 writes the same column). */
    private void massPrintingStarted(UUID orderId) {
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET printing_started_at = NOW() WHERE id = ?", orderId));
    }

    /**
     * Paid on signing, the order confirms itself after commit (PaymentReceiptRecorded): waited for, so that neither
     * that listener's save nor this test's writes overwrite the other.
     */
    private void awaitConfirmed(UUID orderId) {
        Await.until("order " + orderId + " confirmed", Duration.ofSeconds(15),
                () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.CONFIRMED);
    }

    private static String reference() {
        return "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private PaymentReceipt pay(UUID installmentId, long amount) {
        return receipts.record(new PaymentReceiptService.RecordReceiptCommand(customerId, reference(), today, null,
                List.of(new PaymentReceipt.Payment(installmentId, BigDecimal.valueOf(amount))), accountant))
                .receipt();
    }

    private void cancel(UUID orderId, BigDecimal percent, CancellationFee fee) {
        WithCurrentUser.run(sales, DataScope.ALL, () -> orders.cancel(orderId, new CancelOrderCommand(
                CancellationReasonCode.CUSTOMER_REQUEST, "agreed with the customer", percent, sales.userId(), fee)));
    }

    private Installment installment(UUID id) {
        return installments.detail(id, null).installment();
    }

    private RefundService.OrderRefundDetail refundOf(UUID orderId) {
        return refunds.detail(orderId, null);
    }

    private RefundService.OrderRefundDetail recordRefund(UUID orderId, long amount, String reference) {
        return refunds.record(new RefundService.RecordRefundCommand(orderId, BigDecimal.valueOf(amount), today,
                reference, null, accountant));
    }

    private Optional<UUID> feeItemOf(UUID orderId) {
        return jdbc.queryForList("SELECT id FROM payment.installment WHERE order_id = ? AND milestone = "
                + "'CANCELLATION_FEE'", UUID.class, orderId).stream().findFirst();
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    private static BigDecimal dong(long amount) {
        return BigDecimal.valueOf(amount);
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("17 BR-11: cancelled before mass printing - no fee, everything collected is to refund, in the same "
            + "transaction")
    void cancelBeforePrinting() {
        Signed signed = signed();
        pay(signed.seq(1), 660_000);
        pay(signed.seq(3), 100_000);
        awaitConfirmed(signed.orderId());

        assertThatThrownBy(() -> cancel(signed.orderId(), new BigDecimal("12.345"), null))
                .as("a share to two decimals, refused before anything moves")
                .extracting(CancellationRefundIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> cancel(signed.orderId(), BigDecimal.TEN, null))
                .as("no fee before mass printing").extracting(CancellationRefundIntegrationTest::code)
                .isEqualTo(ErrorCode.CANCELLATION_FEE_BEFORE_PRINTING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE order_id = ?", Long.class,
                signed.orderId())).as("a refused cancellation settles nothing").isZero();

        cancel(signed.orderId(), null, null);
        assertThat(orders.findById(signed.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        // Synchronous: no await - the installments were cancelled with the order.
        assertThat(installment(signed.seq(1)).status()).isEqualTo(InstallmentStatus.PAID);
        assertThat(installment(signed.seq(2)).status()).isEqualTo(InstallmentStatus.CANCELLED);
        assertThat(installment(signed.seq(3)).status()).isEqualTo(InstallmentStatus.CANCELLED);

        RefundService.OrderRefundDetail refund = refundOf(signed.orderId());
        assertThat(refund.settlements()).singleElement().satisfies(s -> {
            assertThat(s.collected()).isEqualByComparingTo(dong(760_000));
            assertThat(s.fee()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.refundable()).isEqualByComparingTo(dong(760_000));
            assertThat(s.feeItemId()).isNull();
        });
        assertThat(refund.standing().status()).isEqualTo(RefundStanding.Status.PENDING);
        assertThat(refund.standing().remaining()).isEqualByComparingTo(dong(760_000));
        assertThat(feeItemOf(signed.orderId())).isEmpty();
    }

    @Test
    @DisplayName("15 BR-15: after mass printing, a fee above what was collected - all of it pays the fee, the "
            + "shortfall is a fee item the customer pays by receipt")
    void feeAboveCollected() {
        Signed signed = signed();
        PaymentReceipt deposit = pay(signed.seq(1), 660_000);
        awaitConfirmed(signed.orderId());
        massPrintingStarted(signed.orderId());
        BigDecimal exposureBefore = payments.creditPosition(customerId).outstandingReceivables();

        cancel(signed.orderId(), null, new CancellationFee(dong(1_000_000), "Cups printed to the customer's design",
                "1,000 cups printed before the cancellation; blanks and ink used"));

        RefundService.OrderRefundDetail refund = refundOf(signed.orderId());
        OrderSettlementResultView s = new OrderSettlementResultView(refund);
        assertThat(s.collected).isEqualByComparingTo(dong(660_000));
        assertThat(s.feeCovered).isEqualByComparingTo(dong(660_000));
        assertThat(s.refundable).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(s.feeOwed).isEqualByComparingTo(dong(340_000));
        assertThat(refund.standing().status()).isEqualTo(RefundStanding.Status.NONE);

        UUID feeItemId = feeItemOf(signed.orderId()).orElseThrow();
        Installment fee = installment(feeItemId);
        assertThat(fee.amount()).isEqualByComparingTo(dong(340_000));
        assertThat(fee.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(fee.dueDate()).isEqualTo(today);
        assertThat(fee.seq()).isEqualTo(Installment.FIRST_FEE_SEQ);
        assertThat(payments.creditPosition(customerId).outstandingReceivables())
                .as("BR-10: the unpaid fee counts; the cancelled after-delivery installment no longer does")
                .isEqualByComparingTo(exposureBefore.subtract(dong(660_000)).add(dong(340_000)));

        // The customer pays the fee item like an installment, though the order is cancelled.
        PaymentReceipt feePaid = pay(feeItemId, 340_000);
        assertThat(installment(feeItemId).status()).isEqualTo(InstallmentStatus.PAID);
        assertThatThrownBy(() -> pay(signed.seq(2), 100_000)).as("not a cancelled installment")
                .extracting(CancellationRefundIntegrationTest::code).isEqualTo(ErrorCode.INSTALLMENT_NOT_PAYABLE);

        // A receipt against the fee item stays voidable; one the settlement counted does not.
        receipts.voidReceipt(new PaymentReceiptService.VoidReceiptCommand(feePaid.id(), "Wrong statement line", false,
                accountant));
        assertThat(installment(feeItemId).status()).isEqualTo(InstallmentStatus.DUE);
        assertThatThrownBy(() -> receipts.voidReceipt(new PaymentReceiptService.VoidReceiptCommand(deposit.id(),
                "Wrong amount", true, accountant))).extracting(CancellationRefundIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED);
    }

    @Test
    @DisplayName("17 §4.8 step 30: after mass printing, a share of what was collected; the rest is to refund")
    void shareOfCollected() {
        Signed signed = signed();
        pay(signed.seq(1), 660_000);
        pay(signed.seq(2), 440_000);
        awaitConfirmed(signed.orderId());
        massPrintingStarted(signed.orderId());

        cancel(signed.orderId(), new BigDecimal("12.5"), null);

        OrderSettlementResultView s = new OrderSettlementResultView(refundOf(signed.orderId()));
        assertThat(s.collected).isEqualByComparingTo(dong(1_100_000));
        assertThat(s.fee).as("12.5% of 1,100,000").isEqualByComparingTo(dong(137_500));
        assertThat(s.refundable).isEqualByComparingTo(dong(962_500));
        assertThat(s.feeOwed).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(feeItemOf(signed.orderId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT fee_percent FROM payment.order_settlement WHERE order_id = ?",
                BigDecimal.class, signed.orderId())).isEqualByComparingTo("12.5");
    }

    @Test
    @DisplayName("15 §4.5 step 16, BR-14: refunds recorded up to what is refundable; over it refused; the refund "
            + "goes PENDING -> PARTIALLY_REFUNDED -> REFUNDED, a void takes it back; the order stays CANCELLED")
    void refundRecords() {
        Signed signed = signed();
        pay(signed.seq(1), 660_000);
        awaitConfirmed(signed.orderId());
        cancel(signed.orderId(), null, null);
        assertThat(refundOf(signed.orderId()).standing().refundable()).isEqualByComparingTo(dong(660_000));

        String first = reference();
        RefundService.OrderRefundDetail partly = recordRefund(signed.orderId(), 400_000, first);
        assertThat(partly.standing().status()).isEqualTo(RefundStanding.Status.PARTIALLY_REFUNDED);
        assertThat(partly.standing().remaining()).isEqualByComparingTo(dong(260_000));

        assertThatThrownBy(() -> recordRefund(signed.orderId(), 260_001, reference()))
                .as("BR-14: never more than what is left").extracting(CancellationRefundIntegrationTest::code)
                .isEqualTo(ErrorCode.REFUND_EXCEEDS_REFUNDABLE);
        assertThatThrownBy(() -> recordRefund(signed.orderId(), 100, first))
                .as("one statement line, one live record").extracting(CancellationRefundIntegrationTest::code)
                .isEqualTo(ErrorCode.REFUND_REFERENCE_IN_USE);

        RefundService.OrderRefundDetail full = recordRefund(signed.orderId(), 260_000, reference());
        assertThat(full.standing().status()).isEqualTo(RefundStanding.Status.REFUNDED);
        assertThat(full.standing().remaining()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> recordRefund(signed.orderId(), 1, reference()))
                .extracting(CancellationRefundIntegrationTest::code).isEqualTo(ErrorCode.REFUND_EXCEEDS_REFUNDABLE);

        UUID wrong = full.records().getLast().id();
        RefundService.OrderRefundDetail voided = refunds.voidRecord(wrong, "Sent to the wrong account", accountant);
        assertThat(voided.standing().status()).isEqualTo(RefundStanding.Status.PARTIALLY_REFUNDED);
        assertThat(voided.standing().remaining()).isEqualByComparingTo(dong(260_000));
        assertThat(voided.records()).filteredOn(r -> r.status() == RefundRecord.Status.VOIDED).singleElement()
                .satisfies(r -> assertThat(r.voidReason()).isEqualTo("Sent to the wrong account"));
        assertThatThrownBy(() -> refunds.voidRecord(wrong, "again", accountant))
                .extracting(CancellationRefundIntegrationTest::code)
                .isEqualTo(ErrorCode.REFUND_RECORD_ALREADY_VOIDED);
        assertThat(orders.findById(signed.orderId()).orElseThrow().status())
                .as("17 §5.1: CANCELLED is final; the refund's standing is computed").isEqualTo(OrderStatus.CANCELLED);

        // The accountant's work list.
        assertThat(refunds.orderRefunds(customerId, true, 0, 20).items()).singleElement()
                .satisfies(o -> assertThat(o.refunded()).isEqualByComparingTo(dong(400_000)));
        assertThat(refunds.orderRefunds(customerId, false, 0, 20).items()).isEmpty();

        // The database says BR-14 again: a record above what is refundable does not commit.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.refund_record (id, order_id, customer_id, order_number, amount, currency,
                    refunded_on, reference, status, recorded_by, recorded_at)
                SELECT ?, o.id, o.customer_id, o.order_number, 260001, o.currency, CURRENT_DATE, ?, 'RECORDED', ?,
                       NOW()
                  FROM ordering.customer_order o WHERE o.id = ?""", Identifiers.newId(), reference(), accountant,
                signed.orderId()))).hasStackTraceContaining("BR-14");
    }

    @Test
    @DisplayName("17 BR-14: the customer's request waits alone; approved after mass printing with a share, it "
            + "settles the money in the approving transaction")
    void customerCancellationRequest() {
        Signed signed = signed();
        pay(signed.seq(1), 660_000);
        awaitConfirmed(signed.orderId());
        massPrintingStarted(signed.orderId());

        CancellationOutcome requested = orders.cancelOwn(signed.orderId(), customerId,
                CancellationReasonCode.CUSTOMER_REQUEST, "changed our plans", customerUser);
        assertThat(requested.result()).isEqualTo(CancellationOutcome.Result.REQUESTED);
        assertThatThrownBy(() -> orders.cancelOwn(signed.orderId(), customerId, CancellationReasonCode.OTHER,
                "again", customerUser)).as("one pending request per order")
                .extracting(CancellationRefundIntegrationTest::code)
                .isEqualTo(ErrorCode.ORDER_CANCELLATION_REQUEST_PENDING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE order_id = ?", Long.class,
                signed.orderId())).as("a request settles nothing").isZero();

        asSales(() -> cancellationRequests.approve(signed.orderId(), requested.requestId(), BigDecimal.TEN,
                "fee agreed by phone", sales.userId()));
        assertThat(orders.findById(signed.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        OrderSettlementResultView s = new OrderSettlementResultView(refundOf(signed.orderId()));
        assertThat(s.fee).isEqualByComparingTo(dong(66_000));
        assertThat(s.refundable).isEqualByComparingTo(dong(594_000));
        assertThat(refunds.detail(signed.orderId(), customerId).standing().status())
                .as("the customer sees their refund").isEqualTo(RefundStanding.Status.PENDING);
        assertThatThrownBy(() -> refunds.detail(signed.orderId(), Identifiers.newId()))
                .as("another customer does not").extracting(CancellationRefundIntegrationTest::code)
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("15 §4.6 step 19, BR-16: a closure lowers what is owed; money paid above the new worth pays the fee "
            + "first and the rest is to refund; once per closure")
    void remainderClosure() {
        Signed signed = signed();
        pay(signed.seq(1), 660_000);
        pay(signed.seq(2), 880_000);
        awaitConfirmed(signed.orderId());
        massPrintingStarted(signed.orderId());
        UUID closureId = Identifiers.newId();
        // Half the goods closed: 1,000,000 + 10% VAT.
        SettleRemainderClosureCommand command = new SettleRemainderClosureCommand(signed.orderId(), closureId,
                dong(1_100_000), dong(100_000), "Printed cartons never shipped", "500 cups printed, kept in stock",
                sales.userId(), Instant.now());

        OrderSettlementResult result = tx.execute(s -> payments.settleRemainderClosure(command)).orElseThrow();
        assertThat(result.reduced()).as("the installment after delivery, all of it").isEqualByComparingTo(
                dong(660_000));
        assertThat(result.surplus()).isEqualByComparingTo(dong(440_000));
        assertThat(result.feeCovered()).isEqualByComparingTo(dong(100_000));
        assertThat(result.refundable()).isEqualByComparingTo(dong(340_000));
        assertThat(installment(signed.seq(3)).status()).as("taken off whole, nothing paid: no longer owed")
                .isEqualTo(InstallmentStatus.CANCELLED);
        assertThat(installment(signed.seq(3)).reducedAmount()).isEqualByComparingTo(dong(660_000));

        OrderSettlementResult replay = tx.execute(s -> payments.settleRemainderClosure(command)).orElseThrow();
        assertThat(replay.settlementId()).as("once per closure").isEqualTo(result.settlementId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.installment_reduction r JOIN "
                + "payment.order_settlement s ON s.id = r.settlement_id WHERE s.order_id = ?", Long.class,
                signed.orderId())).isEqualTo(1);
        assertThat(installment(signed.seq(3)).milestone()).isEqualTo(InstallmentMilestone.AFTER_DELIVERY);

        // §4.4 step 13: a receipt the closure's refund was worked out from is not voided any more.
        UUID depositReceipt = jdbc.queryForObject("SELECT receipt_id FROM payment.payment_receipt_line WHERE "
                + "installment_id = ?", UUID.class, signed.seq(1));
        assertThatThrownBy(() -> receipts.voidReceipt(new PaymentReceiptService.VoidReceiptCommand(depositReceipt,
                "Wrong amount", true, accountant))).extracting(CancellationRefundIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED);
    }

    @Test
    @DisplayName("a settlement is made once: a cancellation settled again returns the first")
    void cancellationSettledOnce() {
        Signed signed = signed();
        pay(signed.seq(1), 660_000);
        awaitConfirmed(signed.orderId());
        cancel(signed.orderId(), null, null);
        OrderSettlementResult again = tx.execute(s -> payments.settleCancellation(
                new com.stockflow.payment.api.SettleCancellationCommand(signed.orderId(), null, null, null, null,
                        sales.userId(), Instant.now()))).orElseThrow();
        assertThat(again.refundable()).isEqualByComparingTo(dong(660_000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE order_id = ?", Long.class,
                signed.orderId())).isEqualTo(1);
    }

    /** The single settlement of an order's refund, unpacked for the assertions. */
    private static final class OrderSettlementResultView {
        final BigDecimal collected;
        final BigDecimal fee;
        final BigDecimal feeCovered;
        final BigDecimal refundable;
        final BigDecimal feeOwed;

        OrderSettlementResultView(RefundService.OrderRefundDetail detail) {
            assertThat(detail.settlements()).hasSize(1);
            var s = detail.settlements().getFirst();
            this.collected = s.collected();
            this.fee = s.fee();
            this.feeCovered = s.feeCovered();
            this.refundable = s.refundable();
            this.feeOwed = s.feeOwed();
        }
    }
}
