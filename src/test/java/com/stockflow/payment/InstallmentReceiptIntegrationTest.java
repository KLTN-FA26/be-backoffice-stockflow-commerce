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
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.CompanyBankAccount;
import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.service.InstallmentService;
import com.stockflow.payment.internal.service.PaymentReceiptService;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4B-6 (kltn-docs 15 §4.1-§4.4, §5.1-§5.3; BR-02..BR-13) against the real database: a signed contract's plan becomes
 * installments, the accountant records receipts against them and voids wrong ones, and the order follows - confirmed
 * once its installments on signing are paid, held when a void takes that money back.
 *
 * <p>Every contract is drawn up, sent and signed through {@link SalesContracts}, never inserted: the installments are
 * opened inside the signing transaction ({@code placeSigned}), and {@code tg_installment_matches_plan} holds each one
 * to its signed plan row, so a hand-made installment would test a path production never takes. The order's reactions
 * are asynchronous ({@code @ApplicationModuleListener} after commit), so they are awaited, never slept for.</p>
 *
 * <p>The order total is always 2,200,000 dong (1,000 pieces at 2,000 with 10% VAT): the plan's shares below are worked
 * out from it, rounded to the dong with the rest on the last row (14 §4.3 step 12).</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class InstallmentReceiptIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(15);
    /** A generous approved credit line: paying after delivery fits it, so signing holds nothing for credit. */
    private static final long AMPLE_CREDIT = 100_000_000;

    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired CreditHoldService creditHolds;
    @Autowired PaymentService payments;
    @Autowired InstallmentService installments;
    @Autowired PaymentReceiptService receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID accountant;
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
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Cong ty Tra Gop', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "installments-" + customerId + "@example.com", "03" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    /** Credit approved by accounting: allowed, with {@code limit}, 30 days - what a plan paying after delivery needs. */
    private void credit(long limit) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, allow_credit,
                    default_payment_term, credit_limit, credit_term_days, approved_by, approved_at)
                VALUES (?, ?, true, false, true, 'PREPAID', ?, 30, ?, NOW())""", Identifiers.newId(), customerId,
                BigDecimal.valueOf(limit), accountant));
    }

    /** A DRAFT order of 20 cartons of 50 at 2,000 a piece with 10% VAT: 2,200,000 with tax - as a converted quote. */
    private UUID draftOrder() {
        return tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), 1000, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), 20, 50).withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, 600_000 + ThreadLocalRandom.current().nextInt(99_999)),
                    customerId, Identifiers.newId(), List.of(line), Instant.now());
            order.applyTerms(PaymentTerm.PREPAID, null, null, null);
            return orderRepository.save(order).id().value();
        });
    }

    private static SalesContracts.Installment share(String name, String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    /** A contract of one batch three weeks out and {@code plan}, drawn up and sent. */
    private SalesContracts.ContractView sent(UUID orderId, List<SalesContracts.Installment> plan) {
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(
                null, List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                List.of(new SalesContracts.BatchLine(line, 20)))), plan)));
        return asSales(() -> contracts.send(sales, orderId, drafted.version()));
    }

    /** The sent contract recorded signed on {@code signedOn} with a real one-page PDF scan. */
    private Signed sign(UUID orderId, SalesContracts.ContractView sent, LocalDate signedOn) {
        byte[] scan = TestPdfs.blank();
        SalesContracts.ContractView signed = asSales(() -> contracts.sign(sales, orderId, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), signedOn));
        List<UUID> ids = jdbc.queryForList("SELECT id FROM payment.installment WHERE order_id = ? ORDER BY seq",
                UUID.class, orderId);
        return new Signed(orderId, signed.contractNumber(), ids);
    }

    /** A new order with {@code plan}, signed today. */
    private Signed signed(SalesContracts.Installment... plan) {
        UUID orderId = draftOrder();
        return sign(orderId, sent(orderId, List.of(plan)), today);
    }

    private Installment installment(UUID id) {
        return installments.detail(id, null).installment();
    }

    private List<Installment> installmentsOf(UUID orderId) {
        return installments.ofOrder(orderId, null).installments();
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private void awaitStatus(UUID orderId, OrderStatus wanted) {
        Await.until("order " + orderId + " " + wanted, ASYNC, () -> status(orderId) == wanted);
    }

    /**
     * Every listener of the events naming {@code id} has finished: Spring Modulith completes a publication once its
     * listener returns ({@code completion-mode: UPDATE} keeps the row). What lets a test say an asynchronous reaction
     * did <i>nothing</i>. The publication is written in the publishing transaction, so it must already be there - else
     * the wait below would pass on nothing at all.
     */
    private void awaitListeners(UUID id) {
        String naming = "%" + id + "%";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                Long.class, naming)).as("the events naming %s are in the publication registry", id).isPositive();
        Await.until("listeners of " + id + " done", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE serialized_event LIKE ? AND completion_date IS NULL""",
                Long.class, naming) == 0);
    }

    /** A statement reference nobody else used: BR-03 is global, across customers and the money ledger. */
    private static String reference() {
        return "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private static PaymentReceipt.Payment line(UUID installmentId, long amount) {
        return new PaymentReceipt.Payment(installmentId, BigDecimal.valueOf(amount));
    }

    private PaymentReceipt record(UUID customer, String reference, PaymentReceipt.Payment... lines) {
        return receipts.record(new PaymentReceiptService.RecordReceiptCommand(customer, reference, today, null,
                List.of(lines), accountant)).receipt();
    }

    private PaymentReceipt pay(PaymentReceipt.Payment... lines) {
        return record(customerId, reference(), lines);
    }

    private PaymentReceipt voidReceipt(UUID receiptId, String reason, boolean holdOrders) {
        return receipts.voidReceipt(new PaymentReceiptService.VoidReceiptCommand(receiptId, reason, holdOrders,
                accountant)).receipt();
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    private long receiptRows(UUID customer) {
        return jdbc.queryForObject("SELECT count(*) FROM payment.payment_receipt WHERE customer_id = ?", Long.class,
                customer);
    }

    private long lineRows(UUID installmentId) {
        return jdbc.queryForObject("SELECT count(*) FROM payment.payment_receipt_line WHERE installment_id = ?",
                Long.class, installmentId);
    }

    private static BigDecimal dong(long amount) {
        return BigDecimal.valueOf(amount);
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("15 §4.1 step 1: signing opens one installment per plan row; only the one on signing is due")
    void signingOpensTheInstallments() {
        credit(AMPLE_CREDIT);
        Signed signed = signed(share("Deposit", "ON_SIGNING", "30", 3), share("Before delivery", "BEFORE_DELIVERY",
                "40", null), share("After delivery", "AFTER_DELIVERY", "30", 15));

        List<Installment> all = installmentsOf(signed.orderId());
        assertThat(all).as("one per plan row, in the plan's order").hasSize(3)
                .extracting(Installment::seq).containsExactly(1, 2, 3);
        assertThat(all).extracting(Installment::contractNumber).containsOnly(signed.contractNumber());
        assertThat(all).extracting(Installment::customerId).containsOnly(customerId);
        assertThat(all).extracting(Installment::milestone).containsExactly(InstallmentMilestone.ON_SIGNING,
                InstallmentMilestone.BEFORE_DELIVERY, InstallmentMilestone.AFTER_DELIVERY);
        assertThat(all).extracting(Installment::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(dong(660_000), dong(880_000), dong(660_000));

        Installment onSigning = all.get(0);
        Instant signedAt = jdbc.queryForObject("SELECT signed_at FROM ordering.sales_contract WHERE order_id = ? "
                + "AND status = 'SIGNED'", java.sql.Timestamp.class, signed.orderId()).toInstant();
        assertThat(onSigning.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(onSigning.reachedAt()).as("due from the signature").isEqualTo(signedAt);
        assertThat(onSigning.dueDate()).as("the day signed plus its days").isEqualTo(today.plusDays(3));
        assertThat(onSigning.paidAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        for (Installment waiting : all.subList(1, 3)) {
            assertThat(waiting.status()).as("installment %d waits for its milestone", waiting.seq())
                    .isEqualTo(InstallmentStatus.NOT_DUE);
            assertThat(waiting.reachedAt()).isNull();
            assertThat(waiting.dueDate()).isNull();
        }
        assertThat(all.get(1).dueDays()).as("paid before delivery: no days to pay").isNull();
        assertThat(all.get(2).dueDays()).isEqualTo(15);

        assertThat(status(signed.orderId())).as("14 §4.3 step 15: waits for the installment on signing")
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        InstallmentStanding standing = payments.installmentStanding(signed.orderId());
        assertThat(standing.opened()).isTrue();
        assertThat(standing.onSigningPaid()).isFalse();
        assertThat(standing.beforeDeliveryUnpaid()).isEqualByComparingTo(dong(880_000));
        assertThat(standing.dueBeforeHandover()).as("the goods wait for the deposit too (BR-07, BR-08)")
                .isEqualByComparingTo(dong(1_540_000));
        assertThat(standing.total()).isEqualByComparingTo(dong(2_200_000));
        assertThat(standing.paid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(standing.paymentStatus()).isEqualTo(InstallmentStanding.ContractPaymentStatus.UNPAID);
    }

    @Test
    @DisplayName("15 BR-07: a receipt paying the installment on signing in full confirms the order")
    void receiptPayingOnSigningConfirms() {
        Signed signed = signed(share("Deposit", "ON_SIGNING", "30", 0), share("Rest", "BEFORE_DELIVERY", "70", null));
        assertThat(status(signed.orderId())).isEqualTo(OrderStatus.PENDING_PAYMENT);

        PaymentReceipt receipt = pay(line(signed.seq(1), 660_000));
        assertThat(receipt.amount()).isEqualByComparingTo(dong(660_000));
        assertThat(receipt.lines()).hasSize(1);
        Installment paid = installment(signed.seq(1));
        assertThat(paid.status()).isEqualTo(InstallmentStatus.PAID);
        assertThat(paid.paidAt()).isNotNull();
        assertThat(installment(signed.seq(2)).status()).isEqualTo(InstallmentStatus.NOT_DUE);

        awaitStatus(signed.orderId(), OrderStatus.CONFIRMED);
        assertThat(payments.installmentStanding(signed.orderId()).onSigningPaid()).isTrue();
        assertThat(installments.detail(signed.seq(1), null).receipts()).singleElement()
                .satisfies(r -> assertThat(r.receiptId()).isEqualTo(receipt.id()));
    }

    @Test
    @DisplayName("15 §4.1 step 3: one receipt over two installments, an early payment of one not due included")
    void multiLineReceiptWithEarlyPayment() {
        credit(AMPLE_CREDIT);
        Signed signed = signed(share("Deposit", "ON_SIGNING", "30", 0), share("After delivery", "AFTER_DELIVERY",
                "70", 30));

        PaymentReceipt receipt = pay(line(signed.seq(1), 660_000), line(signed.seq(2), 500_000));
        assertThat(receipt.amount()).as("the receipt is the sum of its lines").isEqualByComparingTo(dong(1_160_000));
        assertThat(jdbc.queryForObject("SELECT amount FROM payment.payment_receipt WHERE id = ?", BigDecimal.class,
                receipt.id())).isEqualByComparingTo(dong(1_160_000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_receipt_line WHERE receipt_id = ?",
                Long.class, receipt.id())).isEqualTo(2);

        assertThat(installment(signed.seq(1)).status()).isEqualTo(InstallmentStatus.PAID);
        Installment early = installment(signed.seq(2));
        assertThat(early.status()).as("paid early, before its milestone").isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(early.reached()).isFalse();
        assertThat(early.dueDate()).isNull();
        assertThat(early.paidAmount()).isEqualByComparingTo(dong(500_000));
        assertThat(early.outstanding()).isEqualByComparingTo(dong(1_040_000));

        awaitStatus(signed.orderId(), OrderStatus.CONFIRMED);
        InstallmentStanding standing = payments.installmentStanding(signed.orderId());
        assertThat(standing.paid()).isEqualByComparingTo(dong(1_160_000));
        assertThat(standing.paymentStatus()).isEqualTo(InstallmentStanding.ContractPaymentStatus.PARTIALLY_PAID);
    }

    @Test
    @DisplayName("15 BR-07: part of the installment on signing leaves the order waiting; the rest confirms it")
    void partialThenFull() {
        Signed signed = signed(share("Deposit", "ON_SIGNING", "30", 0), share("Rest", "BEFORE_DELIVERY", "70", null));

        PaymentReceipt first = pay(line(signed.seq(1), 200_000));
        Installment partly = installment(signed.seq(1));
        assertThat(partly.status()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(partly.paidAmount()).isEqualByComparingTo(dong(200_000));
        awaitListeners(first.id());
        assertThat(status(signed.orderId())).as("short of the installment on signing")
                .isEqualTo(OrderStatus.PENDING_PAYMENT);

        pay(line(signed.seq(1), 460_000));
        Installment paid = installment(signed.seq(1));
        assertThat(paid.status()).isEqualTo(InstallmentStatus.PAID);
        assertThat(paid.paidAmount()).isEqualByComparingTo(dong(660_000));
        awaitStatus(signed.orderId(), OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("15 BR-03, BR-05: a receipt that does not fit is refused whole, and writes nothing")
    void refusals() {
        Signed signed = signed(share("Deposit", "ON_SIGNING", "30", 0), share("Rest", "BEFORE_DELIVERY", "70", null));
        UUID onSigning = signed.seq(1);
        String statementLine = reference();
        PaymentReceipt first = record(customerId, statementLine, line(onSigning, 100_000));

        // BR-03: one statement line, one live receipt.
        long receiptsBefore = receiptRows(customerId);
        long linesBefore = lineRows(onSigning);
        assertThatThrownBy(() -> record(customerId, statementLine, line(onSigning, 100_000)))
                .extracting(InstallmentReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_REFERENCE_IN_USE);
        assertThat(receiptRows(customerId)).isEqualTo(receiptsBefore);
        assertThat(lineRows(onSigning)).isEqualTo(linesBefore);
        assertThat(installment(onSigning).paidAmount()).isEqualByComparingTo(dong(100_000));

        // A voided receipt's reference is free for the corrected one.
        voidReceipt(first.id(), "Typed the wrong amount", false);
        PaymentReceipt corrected = record(customerId, statementLine, line(onSigning, 120_000));
        assertThat(corrected.reference()).isEqualTo(statementLine);
        assertThat(installment(onSigning).paidAmount()).isEqualByComparingTo(dong(120_000));

        // BR-05: never more than the installment still owes (540,000 now).
        receiptsBefore = receiptRows(customerId);
        linesBefore = lineRows(onSigning);
        assertThatThrownBy(() -> pay(line(onSigning, 540_001)))
                .extracting(InstallmentReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_EXCEEDS_OUTSTANDING);
        // A good line next to a bad one does not slip through: the receipt is refused whole.
        assertThatThrownBy(() -> pay(line(signed.seq(2), 100_000), line(onSigning, 540_001)))
                .extracting(InstallmentReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_EXCEEDS_OUTSTANDING);
        assertThat(receiptRows(customerId)).isEqualTo(receiptsBefore);
        assertThat(lineRows(onSigning)).isEqualTo(linesBefore);
        assertThat(lineRows(signed.seq(2))).isZero();
        assertThat(installment(signed.seq(2)).status()).isEqualTo(InstallmentStatus.NOT_DUE);
        assertThat(installment(onSigning).paidAmount()).isEqualByComparingTo(dong(120_000));

        // BR-05: only the receipt's own customer's installments.
        UUID stranger = tx.execute(s -> ReferenceRows.customer(entityManager));
        assertThatThrownBy(() -> record(stranger, reference(), line(onSigning, 1_000)))
                .extracting(InstallmentReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.INSTALLMENT_CUSTOMER_MISMATCH);
        assertThat(receiptRows(stranger)).isZero();
        assertThat(lineRows(onSigning)).isEqualTo(linesBefore);

        // BR-05: a paid installment takes no more money.
        pay(line(onSigning, 540_000));
        assertThat(installment(onSigning).status()).isEqualTo(InstallmentStatus.PAID);
        receiptsBefore = receiptRows(customerId);
        linesBefore = lineRows(onSigning);
        assertThatThrownBy(() -> pay(line(onSigning, 1)))
                .extracting(InstallmentReceiptIntegrationTest::code).isEqualTo(ErrorCode.INSTALLMENT_NOT_PAYABLE);
        assertThat(receiptRows(customerId)).isEqualTo(receiptsBefore);
        assertThat(lineRows(onSigning)).isEqualTo(linesBefore);
        assertThat(installment(onSigning).paidAmount()).isEqualByComparingTo(dong(660_000));
    }

    @Test
    @DisplayName("15 §4.4 step 12, BR-04: a void is kept with who, when and why; the installments say again what is left")
    void voidRecomputes() {
        credit(AMPLE_CREDIT);
        Signed signed = signed(share("Deposit", "ON_SIGNING", "30", 0), share("After delivery", "AFTER_DELIVERY",
                "70", 30));
        PaymentReceipt receipt = pay(line(signed.seq(1), 200_000), line(signed.seq(2), 300_000));
        assertThat(installment(signed.seq(1)).status()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(installment(signed.seq(2)).status()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        awaitListeners(receipt.id());
        assertThat(status(signed.orderId())).isEqualTo(OrderStatus.PENDING_PAYMENT);

        PaymentReceipt voided = voidReceipt(receipt.id(), "  Wrong customer on the statement  ", false);
        assertThat(voided.status().name()).isEqualTo("VOIDED");

        Installment onSigning = installment(signed.seq(1));
        assertThat(onSigning.status()).as("reached, nothing paid: due again").isEqualTo(InstallmentStatus.DUE);
        assertThat(onSigning.paidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(onSigning.paidAt()).isNull();
        Installment afterDelivery = installment(signed.seq(2));
        assertThat(afterDelivery.status()).as("not reached, nothing paid: not due again")
                .isEqualTo(InstallmentStatus.NOT_DUE);
        assertThat(afterDelivery.paidAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, void_reason, voided_by, voided_at IS NOT NULL AS voided, amount
                  FROM payment.payment_receipt WHERE id = ?""", receipt.id());
        assertThat(row).containsEntry("status", "VOIDED").containsEntry("void_reason",
                "Wrong customer on the statement").containsEntry("voided_by", accountant).containsEntry("voided", true);
        assertThat((BigDecimal) row.get("amount")).as("kept as it was").isEqualByComparingTo(dong(500_000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_receipt_line WHERE receipt_id = ?",
                Long.class, receipt.id())).as("its lines are kept").isEqualTo(2);
        assertThat(installments.detail(signed.seq(1), null).receipts()).singleElement()
                .satisfies(r -> assertThat(r.status().name()).isEqualTo("VOIDED"));

        awaitListeners(receipt.id());
        assertThat(status(signed.orderId())).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThatThrownBy(() -> voidReceipt(receipt.id(), "again", false))
                .extracting(InstallmentReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_ALREADY_VOIDED);
    }

    @Test
    @DisplayName("15 BR-13: voiding what confirmed an order holds it for payment; resolved once paid again")
    void voidOfConfirmingReceiptHoldsTheOrder() {
        Signed signed = signed(share("Deposit", "ON_SIGNING", "30", 0), share("Rest", "BEFORE_DELIVERY", "70", null));
        String statementLine = reference();
        PaymentReceipt confirming = record(customerId, statementLine, line(signed.seq(1), 660_000));
        awaitStatus(signed.orderId(), OrderStatus.CONFIRMED);

        // Without the accountant's agreement to hold the order: refused, nothing changed.
        assertThatThrownBy(() -> voidReceipt(confirming.id(), "Duplicate of another statement line", false))
                .extracting(InstallmentReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_VOID_NEEDS_HOLD);
        assertThat(jdbc.queryForObject("SELECT status FROM payment.payment_receipt WHERE id = ?", String.class,
                confirming.id())).isEqualTo("RECORDED");
        assertThat(installment(signed.seq(1)).status()).isEqualTo(InstallmentStatus.PAID);
        assertThat(status(signed.orderId())).isEqualTo(OrderStatus.CONFIRMED);

        // With it: voided, due again, and the order held for payment.
        PaymentReceipt voided = voidReceipt(confirming.id(), "Duplicate of another statement line", true);
        assertThat(voided.status().name()).isEqualTo("VOIDED");
        assertThat(installment(signed.seq(1)).status()).isEqualTo(InstallmentStatus.DUE);
        awaitStatus(signed.orderId(), OrderStatus.ON_HOLD);
        assertThat(jdbc.queryForObject("SELECT hold_reason FROM ordering.customer_order WHERE id = ?", String.class,
                signed.orderId())).isEqualTo("PAYMENT");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ordering.order_hold
                 WHERE order_id = ? AND reason = 'PAYMENT' AND resolved_at IS NULL""", Long.class, signed.orderId()))
                .isEqualTo(1);

        // Short of the installment on signing, the hold is not resolved by a note.
        assertThatThrownBy(() -> orders.resolveHold(signed.orderId(), "Customer promised to pay", sales.userId()))
                .extracting(InstallmentReceiptIntegrationTest::code).isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);
        assertThat(status(signed.orderId())).isEqualTo(OrderStatus.ON_HOLD);

        // Paid again - under the same statement line, free since its receipt was voided - the hold resolves.
        PaymentReceipt again = record(customerId, statementLine, line(signed.seq(1), 660_000));
        assertThat(installment(signed.seq(1)).status()).isEqualTo(InstallmentStatus.PAID);
        awaitListeners(again.id());
        assertThat(status(signed.orderId())).as("a held order is resolved by Sales, not confirmed by the receipt")
                .isEqualTo(OrderStatus.ON_HOLD);
        orders.resolveHold(signed.orderId(), "Paid again", sales.userId());
        assertThat(status(signed.orderId())).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ordering.order_hold
                 WHERE order_id = ? AND reason = 'PAYMENT' AND resolved_at IS NOT NULL""", Long.class,
                signed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("15 §4.1 step 2: released, the installment before delivery is due; delivered, the one after it")
    void milestones() {
        credit(AMPLE_CREDIT);
        Signed signed = signed(share("Deposit", "ON_SIGNING", "20", 0), share("Before delivery", "BEFORE_DELIVERY",
                "30", null), share("After delivery", "AFTER_DELIVERY", "50", 10));

        Instant releasedAt = Instant.now();
        installments.released(signed.orderId(), releasedAt);
        Installment beforeDelivery = installment(signed.seq(2));
        assertThat(beforeDelivery.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(beforeDelivery.reachedAt()).isNotNull();
        assertThat(beforeDelivery.dueDate()).as("BR-08: no due day, it blocks the handover").isNull();
        assertThat(payments.installmentStanding(signed.orderId()).beforeDeliveryUnpaid())
                .isEqualByComparingTo(beforeDelivery.amount()).isEqualByComparingTo(dong(660_000));
        assertThat(installment(signed.seq(3)).status()).as("released is not delivered")
                .isEqualTo(InstallmentStatus.NOT_DUE);

        // A milestone comes once.
        Instant reached = beforeDelivery.reachedAt();
        installments.released(signed.orderId(), releasedAt.plusSeconds(3_600));
        assertThat(installment(signed.seq(2)).reachedAt()).isEqualTo(reached);

        // A delivery of part of an order not delivered in full waits for the last one.
        installments.delivered(signed.orderId(), Instant.now(), false);
        assertThat(installment(signed.seq(3)).status()).isEqualTo(InstallmentStatus.NOT_DUE);

        // 01:00 in Vietnam is still the day before in UTC: the delivery day is the business day.
        Instant deliveredAt = today.atTime(1, 0).atZone(VIETNAM).toInstant();
        installments.delivered(signed.orderId(), deliveredAt, true);
        Installment afterDelivery = installment(signed.seq(3));
        assertThat(afterDelivery.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(afterDelivery.reachedAt()).isEqualTo(deliveredAt);
        assertThat(afterDelivery.dueDate()).as("BR-09: the delivery day plus its days").isEqualTo(today.plusDays(10));

        // Paid in part, the installment before delivery still blocks what is left of it.
        pay(line(signed.seq(2), 160_000));
        assertThat(installment(signed.seq(2)).status()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(payments.installmentStanding(signed.orderId()).beforeDeliveryUnpaid())
                .isEqualByComparingTo(dong(500_000));
    }

    @Test
    @DisplayName("15 §4.1 step 4: past its due day the installment is OVERDUE until paid in full")
    void overdue() {
        UUID orderId = draftOrder();
        SalesContracts.ContractView sent = sent(orderId, List.of(share("Deposit", "ON_SIGNING", "30", 2),
                share("Rest", "BEFORE_DELIVERY", "70", null)));
        // The contract was sent six days ago and signed five days ago. A contract's sending is fixed once sent
        // (tg_sales_contract_transition), and signing may not be dated before it, so the fixture moves the sending back
        // with triggers off - the one way to an installment on signing whose due day is past without a fake clock.
        // Its due day is then the signed day plus two: three days ago, set by the real signing path.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.sales_contract SET sent_at = sent_at - INTERVAL '6 days' WHERE id = ?",
                    sent.id());
        });
        Signed signed = sign(orderId, sent, today.minusDays(5));
        UUID onSigning = signed.seq(1);
        assertThat(installment(onSigning).dueDate()).isEqualTo(today.minusDays(3));
        assertThat(installment(onSigning).status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(payments.creditPosition(customerId).hasOverdue()).isFalse();

        assertThat(installments.markOverdue()).isPositive();
        assertThat(installment(onSigning).status()).isEqualTo(InstallmentStatus.OVERDUE);
        assertThat(installment(signed.seq(2)).status()).as("no due day, never overdue")
                .isEqualTo(InstallmentStatus.NOT_DUE);
        CreditPosition position = payments.creditPosition(customerId);
        assertThat(position.hasOverdue()).as("BR-11: the next credit check sees it").isTrue();
        assertThat(position.overdueAmount()).isEqualByComparingTo(dong(660_000));
        assertThat(position.oldestOverdueDueDate()).isEqualTo(today.minusDays(3));

        // Part of it paid: still OVERDUE, for what is left.
        PaymentReceipt partial = pay(line(onSigning, 100_000));
        assertThat(installment(onSigning).status()).isEqualTo(InstallmentStatus.OVERDUE);
        assertThat(installment(onSigning).paidAmount()).isEqualByComparingTo(dong(100_000));
        assertThat(payments.creditPosition(customerId).overdueAmount()).isEqualByComparingTo(dong(560_000));
        awaitListeners(partial.id());
        assertThat(status(orderId)).isEqualTo(OrderStatus.PENDING_PAYMENT);

        // That receipt voided: past its due day, it is OVERDUE again, not DUE.
        voidReceipt(partial.id(), "Recorded against the wrong contract", false);
        assertThat(installment(onSigning).status()).isEqualTo(InstallmentStatus.OVERDUE);
        assertThat(installment(onSigning).paidAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // Paid in full: PAID, nothing overdue, and the order confirmed.
        pay(line(onSigning, 660_000));
        assertThat(installment(onSigning).status()).isEqualTo(InstallmentStatus.PAID);
        assertThat(payments.creditPosition(customerId).hasOverdue()).isFalse();
        awaitStatus(orderId, OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("15 BR-10: what the customer pays after delivery counts against credit from the signature")
    void creditExposure() {
        credit(AMPLE_CREDIT);
        BigDecimal before = payments.creditPosition(customerId).outstandingReceivables();
        Signed signed = signed(share("Deposit", "ON_SIGNING", "40", 0), share("After delivery", "AFTER_DELIVERY",
                "60", 30));
        assertThat(payments.creditPosition(customerId).outstandingReceivables())
                .as("NOT_DUE counts").isEqualByComparingTo(before.add(dong(1_320_000)));

        pay(line(signed.seq(1), 880_000));
        assertThat(payments.creditPosition(customerId).outstandingReceivables())
                .as("the installment on signing is no receivable").isEqualByComparingTo(before.add(dong(1_320_000)));

        pay(line(signed.seq(2), 300_000));
        assertThat(payments.creditPosition(customerId).outstandingReceivables())
                .as("paid early, it owes less").isEqualByComparingTo(before.add(dong(1_020_000)));
    }

    @Test
    @DisplayName("15 §4.6 step 18, BR-15: a cancelled order's unpaid installments are cancelled; its receipts stay")
    void orderCancelled() {
        credit(AMPLE_CREDIT);
        Signed signed = signed(share("Deposit", "ON_SIGNING", "30", 0), share("Before delivery", "BEFORE_DELIVERY",
                "40", null), share("After delivery", "AFTER_DELIVERY", "30", 30));
        PaymentReceipt deposit = pay(line(signed.seq(1), 660_000));
        PaymentReceipt early = pay(line(signed.seq(3), 100_000));
        awaitStatus(signed.orderId(), OrderStatus.CONFIRMED);
        awaitListeners(early.id());

        WithCurrentUser.run(sales, DataScope.ALL, () -> orders.cancel(signed.orderId(), new CancelOrderCommand(
                CancellationReasonCode.CUSTOMER_REQUEST, "agreed with the customer", null, sales.userId())));
        assertThat(status(signed.orderId())).isEqualTo(OrderStatus.CANCELLED);
        Await.until("installments cancelled", ASYNC,
                () -> installment(signed.seq(2)).status() == InstallmentStatus.CANCELLED);

        assertThat(installment(signed.seq(1)).status()).as("paid in full, it stays PAID")
                .isEqualTo(InstallmentStatus.PAID);
        Installment unpaid = installment(signed.seq(2));
        assertThat(unpaid.cancelledAt()).isNotNull();
        Installment partlyPaid = installment(signed.seq(3));
        assertThat(partlyPaid.status()).isEqualTo(InstallmentStatus.CANCELLED);
        assertThat(partlyPaid.paidAmount()).as("what it was paid stays, for the refund (4B-9)")
                .isEqualByComparingTo(dong(100_000));
        assertThat(payments.installmentStanding(signed.orderId()).beforeDeliveryUnpaid())
                .isEqualByComparingTo(BigDecimal.ZERO);

        long receiptsBefore = receiptRows(customerId);
        assertThatThrownBy(() -> pay(line(signed.seq(2), 100_000)))
                .as("money after the cancellation is not taken on a cancelled installment")
                .extracting(InstallmentReceiptIntegrationTest::code).isEqualTo(ErrorCode.INSTALLMENT_NOT_PAYABLE);
        assertThat(receiptRows(customerId)).isEqualTo(receiptsBefore);

        assertThatThrownBy(() -> voidReceipt(early.id(), "Wrong amount", false))
                .as("§4.4 step 13: its money is settled with the order")
                .extracting(InstallmentReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED);
        assertThatThrownBy(() -> voidReceipt(deposit.id(), "Wrong amount", true))
                .extracting(InstallmentReceiptIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_receipt WHERE id IN (?, ?) AND status = "
                + "'RECORDED'", Long.class, deposit.id(), early.id())).isEqualTo(2);
        assertThat(installment(signed.seq(3)).paidAmount()).isEqualByComparingTo(dong(100_000));
    }

    @Test
    @DisplayName("15 §4.2 step 5: how to pay an installment - the company's account, what is left, its reference")
    void transferInstructions() {
        Signed signed = signed(share("Deposit", "ON_SIGNING", "30", 0), share("Rest", "BEFORE_DELIVERY", "70", null));
        CompanyBankAccount company = payments.companyBankAccount().orElseThrow();
        assertThat(company.bankName()).isNotBlank();

        InstallmentService.TransferInstructions first = installments.transferInstructions(signed.seq(1), customerId);
        assertThat(first.account()).isEqualTo(company);
        assertThat(first.amountDue()).isEqualByComparingTo(dong(660_000));
        assertThat(first.reference()).isEqualTo(signed.contractNumber() + " DOT1");

        InstallmentService.TransferInstructions second = installments.transferInstructions(signed.seq(2), customerId);
        assertThat(second.reference()).as("not due yet, it may be paid early").isEqualTo(signed.contractNumber()
                + " DOT2");
        assertThat(second.amountDue()).isEqualByComparingTo(dong(1_540_000));

        pay(line(signed.seq(1), 100_000));
        assertThat(installments.transferInstructions(signed.seq(1), customerId).amountDue())
                .as("what is left to pay").isEqualByComparingTo(dong(560_000));
        assertThat(installments.transferInstructions(signed.seq(1), null).amountDue())
                .as("staff see any customer's").isEqualByComparingTo(dong(560_000));

        UUID stranger = tx.execute(s -> ReferenceRows.customer(entityManager));
        assertThatThrownBy(() -> installments.transferInstructions(signed.seq(1), stranger))
                .as("another customer's is not found, as if it did not exist")
                .extracting(InstallmentReceiptIntegrationTest::code).isEqualTo(ErrorCode.INSTALLMENT_NOT_FOUND);

        pay(line(signed.seq(1), 560_000));
        assertThatThrownBy(() -> installments.transferInstructions(signed.seq(1), customerId))
                .extracting(InstallmentReceiptIntegrationTest::code).isEqualTo(ErrorCode.INSTALLMENT_NOT_PAYABLE);
    }

    @Test
    @DisplayName("15 BR-11: paid on signing while held for credit, the approved order is confirmed, not left waiting")
    void paidWhileHeldForCredit() {
        credit(1_000_000);
        Signed signed = signed(share("Deposit", "ON_SIGNING", "20", 0), share("After delivery", "AFTER_DELIVERY",
                "80", 30));
        assertThat(status(signed.orderId())).as("1,760,000 after delivery does not fit 1,000,000")
                .isEqualTo(OrderStatus.ON_HOLD);
        assertThat(installment(signed.seq(1)).status()).as("held or not, a signed contract is owed what it says")
                .isEqualTo(InstallmentStatus.DUE);

        PaymentReceipt deposit = pay(line(signed.seq(1), 440_000));
        assertThat(installment(signed.seq(1)).status()).isEqualTo(InstallmentStatus.PAID);
        awaitListeners(deposit.id());
        assertThat(status(signed.orderId())).as("a credit hold is accounting's to decide")
                .isEqualTo(OrderStatus.ON_HOLD);

        creditHolds.approve(signed.orderId(), "Known customer", accountant);
        assertThat(status(signed.orderId())).as("its installment on signing is paid already")
                .isEqualTo(OrderStatus.CONFIRMED);
    }
}
