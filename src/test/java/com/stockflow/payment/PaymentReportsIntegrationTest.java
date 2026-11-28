package com.stockflow.payment;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.export.ExportFile;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationFee;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.CustomerReceivable;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.Receivables;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.domain.ReceiptFilter;
import com.stockflow.payment.internal.service.InstallmentService;
import com.stockflow.payment.internal.service.PaymentReceiptExportService;
import com.stockflow.payment.internal.service.PaymentReceiptService;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The payment module's side of the management reports (2026-11-14 plan, BE2-A and BE2-B) against the real database:
 * receivables aged by due date (kltn-docs 15 §4.3 step 8), and the receipts filtered by the day received, totalled and
 * exported (15 BR-06).
 *
 * <p>Every installment comes from a contract drawn up, sent and signed through {@link SalesContracts} - never inserted:
 * {@code tg_installment_matches_plan} holds each to its signed plan row. Delivery is the payment module's own reaction
 * to it ({@link InstallmentService#delivered}, what the {@code OrderDelivered} listener calls) with the day the test
 * needs, so the due days fall where the buckets' edges are. Same context as {@code InstallmentReceiptIntegrationTest}.</p>
 *
 * <p>The order total is always 2,200,000 dong (1,000 pieces at 2,000 with 10% VAT).</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class PaymentReportsIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentService payments;
    @Autowired Receivables receivables;
    @Autowired InstallmentBook installmentBook;
    @Autowired InstallmentService installments;
    @Autowired PaymentReceiptService receipts;
    @Autowired PaymentReceiptExportService receiptExports;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID accountant;
    private UUID customerId;
    private String taxCode;
    private CustomerSkuRows.Issued sku;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = customer("Cong ty Cong No");
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                    approved_at)
                VALUES (?, ?, 100000000, 30, ?, NOW())""", Identifiers.newId(), customerId, accountant));
        taxCode = jdbc.queryForObject("SELECT tax_code FROM customer.customer WHERE id = ?", String.class, customerId);
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private UUID customer(String name) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW())""", id, name, "reports-" + id + "@example.com",
                    "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), id);
        });
        return id;
    }

    /** A DRAFT order of 20 cartons of 50 at 2,000 a piece with 10% VAT: 2,200,000 with tax - as a converted quote. */
    private UUID draftOrder() {
        return tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), 1000, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), 20, 50).withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, 700_000 + ThreadLocalRandom.current().nextInt(99_999)),
                    customerId, Identifiers.newId(), List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
    }

    private static SalesContracts.Installment share(String name, String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    /** A new order with {@code plan}, drawn up, sent and signed today; its installments' ids in the plan's order. */
    private Signed signed(SalesContracts.Installment... plan) {
        UUID orderId = draftOrder();
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(
                null, List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                List.of(new SalesContracts.BatchLine(line, 20)))), List.of(plan))));
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        asSales(() -> contracts.sign(sales, orderId, sent.version(), IncomingFiles.sent(List.of(new FileUpload(
                "signed.pdf", "application/pdf", scan.length, new ByteArrayInputStream(scan)))), today));
        return new Signed(orderId, jdbc.queryForList("SELECT id FROM payment.installment WHERE order_id = ? ORDER BY seq",
                UUID.class, orderId));
    }

    private record Signed(UUID orderId, List<UUID> installmentIds) {

        UUID seq(int seq) {
            return installmentIds.get(seq - 1);
        }
    }

    /** Delivered in full {@code daysAgo} days ago, at noon Vietnam time: what it pays after delivery falls due. */
    private void deliveredDaysAgo(UUID orderId, int daysAgo) {
        installments.delivered(orderId, today.minusDays(daysAgo).atTime(LocalTime.NOON).atZone(VIETNAM).toInstant(),
                true);
    }

    private static String reference() {
        return "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private PaymentReceipt pay(UUID installmentId, long amount, LocalDate receivedOn) {
        return receipts.record(new PaymentReceiptService.RecordReceiptCommand(customerId, reference(), receivedOn,
                "statement " + receivedOn, List.of(new PaymentReceipt.Payment(installmentId, BigDecimal.valueOf(amount))),
                accountant)).receipt();
    }

    private InstallmentStatus status(UUID installmentId) {
        return InstallmentStatus.valueOf(jdbc.queryForObject("SELECT status FROM payment.installment WHERE id = ?",
                String.class, installmentId));
    }

    private static BigDecimal dong(long amount) {
        return BigDecimal.valueOf(amount);
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    // ---- receivables ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("15 §4.3: receivables age by due day at every bucket edge; paid, cancelled and before delivery are out")
    void receivablesAgeByDueDate() {
        // Every contract is signed first, while nothing is overdue: a signature over overdue debt would put the order
        // on hold for credit (15 BR-11), which is not this test's subject.
        // Eight installments after delivery of 275,000, delivered 91 days ago (below), due 91, 90, 61, 60, 31, 30, 1
        // and 0 days after: due today (not yet due) and overdue by 1, 30, 31, 60, 61, 90 and 91 days.
        int[] dueDays = {91, 90, 61, 60, 31, 30, 1, 0};
        SalesContracts.Installment[] plan = new SalesContracts.Installment[dueDays.length];
        for (int i = 0; i < dueDays.length; i++) {
            plan[i] = share("After delivery " + (i + 1), "AFTER_DELIVERY", "12.5", dueDays[i]);
        }
        Signed aged = signed(plan);
        // Signed, not delivered: the deposit (due today) and the part before delivery are no receivable; the part after
        // delivery is committed - 880,000, less 80,000 paid early (below).
        Signed waiting = signed(share("Deposit", "ON_SIGNING", "30", 0), share("Before", "BEFORE_DELIVERY", "30", null),
                share("After", "AFTER_DELIVERY", "40", 30));
        // Delivered and paid in full (below): no receivable.
        Signed paid = signed(share("All after delivery", "AFTER_DELIVERY", "100", 0));
        // Cancelled with a fee once printing started: its installment after delivery CANCELLED, owed no more; the fee
        // is a fee item due the day of the cancellation (15 §5.1), receivable and not yet overdue.
        Signed cancelled = signed(share("After delivery", "AFTER_DELIVERY", "100", 15));

        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET printing_started_at = NOW() WHERE id = ?", cancelled.orderId()));
        WithCurrentUser.run(sales, DataScope.ALL, () -> orders.cancel(cancelled.orderId(), new CancelOrderCommand(
                CancellationReasonCode.CUSTOMER_REQUEST, "agreed with the customer", null, sales.userId(),
                new CancellationFee(dong(340_000), "Cups printed to the customer's design", "1,000 cups printed",
                        com.stockflow.order.api.CancellationFeeBearer.CUSTOMER))));
        assertThat(orders.findById(cancelled.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        Await.until("installment cancelled", ASYNC, () -> status(cancelled.seq(1)) == InstallmentStatus.CANCELLED);
        UUID fee = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ? "
                + "AND milestone = 'CANCELLATION_FEE'", UUID.class, cancelled.orderId());

        deliveredDaysAgo(paid.orderId(), 10);
        pay(paid.seq(1), 2_200_000, today);
        assertThat(status(paid.seq(1))).isEqualTo(InstallmentStatus.PAID);
        deliveredDaysAgo(aged.orderId(), 91);
        pay(waiting.seq(3), 80_000, today);

        List<CustomerReceivable> rows = receivables.receivables(today, customerId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.customerId()).isEqualTo(customerId);
            assertThat(row.currency()).isEqualTo("VND");
            assertThat(row.notYetDue()).as("due today, and the fee due today")
                    .isEqualByComparingTo(dong(275_000 + 340_000));
            assertThat(row.overdue1To30()).as("1 and 30 days").isEqualByComparingTo(dong(550_000));
            assertThat(row.overdue31To60()).as("31 and 60 days").isEqualByComparingTo(dong(550_000));
            assertThat(row.overdue61To90()).as("61 and 90 days").isEqualByComparingTo(dong(550_000));
            assertThat(row.overdueOver90()).as("91 days").isEqualByComparingTo(dong(275_000));
            assertThat(row.receivable()).isEqualByComparingTo(dong(2_200_000 + 340_000));
            assertThat(row.receivable()).as("the buckets add up").isEqualByComparingTo(row.notYetDue()
                    .add(row.overdue()));
            assertThat(row.committedNotReached()).isEqualByComparingTo(dong(880_000 - 80_000));
            assertThat(row.signingOverdue()).as("the deposit is due today: not overdue yet")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row.oldestDueDate()).isEqualTo(today.minusDays(91));
            assertThat(row.openItems()).as("eight installments and the fee").isEqualTo(9);
        });
        assertThat(status(fee)).isEqualTo(InstallmentStatus.DUE);

        // The plan's "done when": receivable + committed is the credit check's exposure, to the dong.
        CustomerReceivable row = rows.getFirst();
        assertThat(row.receivable().add(row.committedNotReached()))
                .isEqualByComparingTo(payments.creditPosition(customerId).outstandingReceivables());

        // Every customer: this one is among them, with the same figures; one that owes nothing is not.
        UUID owesNothing = customer("Cong ty Khong No");
        List<CustomerReceivable> all = receivables.receivables(today, null);
        assertThat(all).filteredOn(r -> r.customerId().equals(customerId)).singleElement().isEqualTo(row);
        assertThat(all).noneMatch(r -> r.customerId().equals(owesNothing));
        assertThat(receivables.receivables(today, owesNothing)).isEmpty();
    }

    @Test
    @DisplayName("an installment due on signing past its due day is its own figure, outside 15 §4.3's; with it the "
            + "report shows all the credit check calls overdue, and a customer owing only that has a row")
    void signingOverdueIsShownApart() {
        // A deposit of 660,000 due five days after signing, 100,000 of it paid; the rest after delivery, not reached.
        Signed deposit = signed(share("Deposit", "ON_SIGNING", "30", 5), share("After", "AFTER_DELIVERY", "70", 10));
        pay(deposit.seq(1), 100_000, today);
        assertThat(status(deposit.seq(1))).isEqualTo(InstallmentStatus.PARTIALLY_PAID);

        // Aged against the deposit's due day itself: nothing overdue yet. Today's balances against a later day - the
        // credit check's own test (InstallmentBook.overdue) is asked for the same day below.
        LocalDate dueDay = today.plusDays(5);
        assertThat(receivables.receivables(dueDay, customerId)).singleElement()
                .satisfies(row -> assertThat(row.signingOverdue()).isEqualByComparingTo(BigDecimal.ZERO));

        LocalDate dayAfter = today.plusDays(6);
        CustomerReceivable row = receivables.receivables(dayAfter, customerId).getFirst();
        assertThat(row.signingOverdue()).as("660,000 less the 100,000 paid").isEqualByComparingTo(dong(560_000));
        assertThat(row.receivable()).as("15 §4.3: a deposit is no receivable").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.overdue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.committedNotReached()).isEqualByComparingTo(dong(1_540_000));
        assertThat(row.openItems()).isZero();
        assertThat(row.oldestDueDate()).as("of the receivable items only").isNull();
        assertThat(row.overdue().add(row.signingOverdue())).as("everything the credit check calls overdue")
                .isEqualByComparingTo(installmentBook.overdue(customerId, dayAfter).amount());
        assertThat(row.receivable().add(row.committedNotReached())).as("the exposure as the credit check counts it")
                .isEqualByComparingTo(payments.creditPosition(customerId).outstandingReceivables());

        // A customer whose only debt is the deposit, overdue: a row of its own, so it never looks clean.
        customerId = customer("Cong ty Chi Coc");
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true);
        Signed onlyDeposit = signed(share("All on signing", "ON_SIGNING", "100", 0));
        assertThat(receivables.receivables(today, customerId)).as("due today: owes nothing overdue, no row").isEmpty();
        assertThat(receivables.receivables(today.plusDays(1), customerId)).singleElement().satisfies(only -> {
            assertThat(only.signingOverdue()).isEqualByComparingTo(dong(2_200_000));
            assertThat(only.receivable()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(only.committedNotReached()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        assertThat(onlyDeposit.installmentIds()).hasSize(1);
    }

    // ---- receipts: the day received, the total, the export ---------------------------------------------------------

    @Test
    @DisplayName("BE2-A: the list filters by the day received, both ends inclusive; the total counts RECORDED only")
    void receiptsByDayReceivedAndTheirTotal() {
        Signed signed = signed(share("Deposit", "ON_SIGNING", "100", 0));
        PaymentReceipt twoDaysAgo = pay(signed.seq(1), 500_000, today.minusDays(2));
        PaymentReceipt yesterday = pay(signed.seq(1), 600_000, today.minusDays(1));
        PaymentReceipt ofToday = pay(signed.seq(1), 700_000, today);
        receipts.voidReceipt(new PaymentReceiptService.VoidReceiptCommand(yesterday.id(), "Wrong statement line",
                false, accountant));

        ReceiptFilter mine = new ReceiptFilter(customerId, null, null, null, null);
        assertThat(receipts.receipts(new ReceiptFilter(customerId, null, null, today.minusDays(1), today.minusDays(1)),
                0, 20, null).items()).extracting(PaymentReceipt::id).containsExactly(yesterday.id());
        assertThat(receipts.receipts(new ReceiptFilter(customerId, null, null, today.minusDays(1), today), 0, 20,
                "receivedOn,asc").items()).extracting(PaymentReceipt::id).containsExactly(yesterday.id(), ofToday.id());
        assertThat(receipts.receipts(new ReceiptFilter(customerId, null, null, null, today.minusDays(2)), 0, 20, null)
                .items()).extracting(PaymentReceipt::id).containsExactly(twoDaysAgo.id());
        assertThat(receipts.receipts(new ReceiptFilter(customerId, PaymentReceiptStatus.RECORDED, null,
                today.minusDays(2), today), 0, 20, null).totalElements()).isEqualTo(2);

        var summary = receiptExports.summary(mine);
        assertThat(summary.count()).as("the voided receipt counts no money").isEqualTo(2);
        assertThat(summary.totalAmount()).isEqualByComparingTo(dong(1_200_000));
        assertThat(summary.currency()).isEqualTo("VND");
        assertThat(summary.byCurrency()).singleElement().satisfies(total -> assertThat(total.count()).isEqualTo(2));
        var ofYesterday = receiptExports.summary(new ReceiptFilter(customerId, null, null, today.minusDays(1),
                today.minusDays(1)));
        assertThat(ofYesterday.count()).isZero();
        assertThat(ofYesterday.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(receiptExports.summary(new ReceiptFilter(customerId, PaymentReceiptStatus.VOIDED, null, null, null))
                .count()).as("status VOIDED: nothing counted").isZero();
        assertThat(receiptExports.summary(new ReceiptFilter(customerId, null, twoDaysAgo.reference().toLowerCase(),
                null, null)).totalAmount()).as("the reference compared as BR-03 compares it")
                .isEqualByComparingTo(dong(500_000));

        assertThat(code(catchThrowable(() -> new ReceiptFilter(null, null, null, today, today.minusDays(1)))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("BE2-A: the export has the receipts and their lines, voided ones with why, and is audited as EXPORT")
    void receiptsExport() throws IOException {
        Signed signed = signed(share("Deposit", "ON_SIGNING", "40", 0), share("Rest", "BEFORE_DELIVERY", "60", null));
        String contractNumber = jdbc.queryForObject("SELECT contract_number FROM payment.installment WHERE id = ?",
                String.class, signed.seq(1));
        String orderNumber = jdbc.queryForObject("SELECT order_number FROM payment.installment WHERE id = ?",
                String.class, signed.seq(1));
        PaymentReceipt first = receipts.record(new PaymentReceiptService.RecordReceiptCommand(customerId, reference(),
                today.minusDays(1), "=HYPERLINK(\"http://x\")", List.of(
                new PaymentReceipt.Payment(signed.seq(1), dong(300_000)),
                new PaymentReceipt.Payment(signed.seq(2), dong(200_000))), accountant)).receipt();
        PaymentReceipt voided = pay(signed.seq(1), 100_000, today);
        receipts.voidReceipt(new PaymentReceiptService.VoidReceiptCommand(voided.id(), "Recorded twice", false,
                accountant));
        String accountantName = jdbc.queryForObject("SELECT username FROM identity.app_user WHERE id = ?",
                String.class, accountant);

        ExportFile file = receiptExports.export(new ReceiptFilter(customerId, null, null, null, null));

        assertThat(file.filename()).startsWith("payment-receipts-").endsWith(".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            XSSFSheet sheet = workbook.getSheetAt(0);
            int header = 4;
            assertThat(sheet.getRow(header).getCell(1).getStringCellValue()).isNotBlank();
            Row one = sheet.getRow(header + 1);
            assertThat(one.getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(today.minusDays(1));
            assertThat(one.getCell(1).getStringCellValue()).isEqualTo(first.reference());
            assertThat(one.getCell(2).getStringCellValue()).isEqualTo(taxCode);
            assertThat(one.getCell(3).getStringCellValue()).isEqualTo("Cong ty Cong No");
            assertThat(one.getCell(4).getNumericCellValue()).isEqualTo(500_000d);
            assertThat(one.getCell(6).getStringCellValue()).isEqualTo("RECORDED");
            assertThat(one.getCell(7).getStringCellValue()).isEqualTo(accountantName);
            assertThat(one.getCell(9).getStringCellValue()).as("a note is text, never a formula")
                    .isEqualTo("=HYPERLINK(\"http://x\")");
            Row two = sheet.getRow(header + 2);
            assertThat(two.getCell(6).getStringCellValue()).isEqualTo("VOIDED");
            assertThat(two.getCell(10).getStringCellValue()).isEqualTo(accountantName);
            assertThat(two.getCell(12).getStringCellValue()).isEqualTo("Recorded twice");
            assertThat(sheet.getLastRowNum()).isEqualTo(header + 2);

            XSSFSheet lines = workbook.getSheetAt(1);
            assertThat(lines.getLastRowNum()).as("two lines of the first receipt, one of the voided")
                    .isEqualTo(header + 3);
            // The first receipt's two lines come first (in the order recorded); which of the two is first is not
            // asserted, they share their receipt's instant.
            Row line = lines.getRow(header + 1).getCell(6).getStringCellValue().equals("ON_SIGNING")
                    ? lines.getRow(header + 1) : lines.getRow(header + 2);
            assertThat(line.getCell(0).getStringCellValue()).isEqualTo(first.reference());
            assertThat(line.getCell(3).getStringCellValue()).isEqualTo(contractNumber);
            assertThat(line.getCell(4).getStringCellValue()).isEqualTo(orderNumber);
            assertThat(line.getCell(5).getStringCellValue()).isEqualTo("1 - Deposit");
            assertThat(line.getCell(6).getStringCellValue()).isEqualTo("ON_SIGNING");
            assertThat(line.getCell(7).getNumericCellValue()).isEqualTo(300_000d);
            assertThat(List.of(lines.getRow(header + 1).getCell(0).getStringCellValue(),
                    lines.getRow(header + 2).getCell(0).getStringCellValue(),
                    lines.getRow(header + 3).getCell(0).getStringCellValue()))
                    .containsExactly(first.reference(), first.reference(), voided.reference());
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log
                 WHERE action = 'EXPORT' AND resource_type = 'payment-receipt' AND details LIKE ?""", Long.class,
                "%" + customerId + "%")).as("the export is audited with its filter").isEqualTo(1);
    }
}
