package com.stockflow.order;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.internal.domain.PaymentReceipt;
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
import org.springframework.dao.DataAccessException;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-02 end to end against the real database, through the services the controllers call: the two demo scripts of the
 * WF-02 review (2026-11-13) and the transitions its test mapping found uncovered.
 *
 * <ul>
 *   <li><b>DS-02a</b> - a first production quoted with tier prices, a plate fee and VAT; sent; accepted above the MOQ
 *       (below it refused); converted into a DRAFT order; a contract of two delivery batches whose plan pays 30% on
 *       signing, batch 1 before delivery and batch 2 after it; sent, signed - the order waits for its money with no
 *       stock held - and the receipt of the installment on signing confirms it, by itself.</li>
 *   <li><b>DS-02b</b> - the credit exception: a customer with an instalment overdue draws a contract that pays after
 *       delivery; the DRAFT and the send both carry the warning (14 §4.3 step 13, 15 BR-11 - never a refusal); signed,
 *       the order goes ON_HOLD for credit with the check OVERDUE recorded. Accounting approves - PENDING_PAYMENT, the
 *       deposit still owed, CONFIRMED once paid - or refuses - CANCELLED, {@code CREDIT_REJECTED}, the deposit
 *       collected while it was held made refundable (15 §4.3 step 10, §4.5). The same on the demo seed:
 *       {@code Wf02CreditExceptionSeedIntegrationTest}.</li>
 *   <li><b>A sent contract changed</b> (14 §4.3 step 16) - the sent contract cannot be edited; cancelled with a reason,
 *       the order is a draft again; a new split of the cartons that does not add up is refused naming the SKU; the
 *       right split takes the installments tied to its batches suggested again, adding up to the total; sent and
 *       signed, the order waits for its money under its one live contract.</li>
 * </ul>
 *
 * <p>The committed test cases of the WF-02 review: TC-F03-01 (below the MOQ, the MOQ shown), TC-F04-01 (an order from
 * an accepted quote only, at its locked prices and rates), TC-F04-02 (a schedule or a plan that does not add up is
 * neither saved nor sent), TC-F04-03 (DS-02a: PENDING_PAYMENT, nothing held, CONFIRMED by the receipt). TC-F03-02 is
 * {@code QuoteVatIntegrationTest}, TC-F03-03 {@code QuoteIntegrationTest#expiry}.</p>
 *
 * <p>Every money figure follows from one line: 4 cartons of 10 at 2,800 (the 4-carton tier) = 112,000 + VAT, and a
 * plate fee of 500,000 + VAT, at the STANDARD rate the quote takes on its day (HĐ-7: 8% through 2026, 10% after). The
 * figures in the comments are at 10%: 673,200 with tax.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.provider=smtp", "stockflow.notification.email.customer.enabled=false"})
class Wf02SalesFlowIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(15);
    /** With tax: 673,200 at 10%. */
    private BigDecimal total;
    /** 30% of it on signing (201,960 at 10%), the rest in halves (235,620), quarters (117,810) and three quarters. */
    private long deposit;
    private long half;
    private long quarter;
    private long threeQuarters;
    /** 10 and 30 cups at 2,800 with tax (30,800 and 92,400 at 10%). */
    private long tenCups;
    private long thirtyCups;

    @Autowired Quotes quotes;
    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired PaymentService payments;
    @Autowired PaymentReceiptService receipts;
    @Autowired CustomerService customers;
    @Autowired com.stockflow.order.internal.service.CreditHoldService creditHolds;
    @Autowired com.stockflow.common.web.GlobalExceptionHandler errors;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private CurrentUser portal;
    private UUID accountant;
    private UUID customerId;
    private String sku;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        BigDecimal rate = com.stockflow.support.VatOfTheDay.standard(jdbc);
        total = com.stockflow.support.VatOfTheDay.tax(112_000, rate).add(com.stockflow.support.VatOfTheDay.tax(500_000,
                rate)).add(BigDecimal.valueOf(612_000));
        deposit = total.multiply(new BigDecimal("0.30")).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        half = (total.longValueExact() - deposit) / 2;
        quarter = (total.longValueExact() - deposit) / 4;
        threeQuarters = total.longValueExact() - deposit - quarter;
        tenCups = com.stockflow.support.VatOfTheDay.tax(28_000, rate).longValueExact() + 28_000;
        thirtyCups = com.stockflow.support.VatOfTheDay.tax(84_000, rate).longValueExact() + 84_000;
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        UUID portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        portal = new CurrentUser(portalUser, "portal-" + portalUser, Set.of(Role.CUSTOMER), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, user_id, full_name, email, phone, status, version, created_at)
                    VALUES (?, ?, 'Cong ty WF02', ?, '0901234567', 'ACTIVE', 0, NOW())""",
                    customerId, portalUser, "wf02-" + customerId + "@example.com");
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '12 Nguyen Hue, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
            // Credit approved by accounting: paying batch 2 after delivery is credit (15 BR-12), well within it.
            jdbc.update("""
                    INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                        approved_at)
                    VALUES (?, ?, 100000000, 30, ?, NOW())""", Identifiers.newId(), customerId, accountant);
        });
        customers.addAddress(customerId, salesUser, true, new SaveAddressCommand("SHIPPING", "Nguyen Van A",
                "0901234567", "1 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, true, null));
        // A first production (a plate fee) of a STANDARD product (10% VAT), 10 cups a carton - and stock of it, so that
        // "nothing is held" below is a fact about the order, not about an empty warehouse.
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 10, "STANDARD", false).sku();
        CustomerSkuRows.stock(jdbc, tx, sku, 200);
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    private static List<FieldError> fieldErrors(Throwable e) {
        return ((BusinessException) e).fieldErrors();
    }

    /** From 2 cartons at 3,000 a piece, from 4 at 2,800; 3 cartons proposed. */
    private Quotes.Draft draft(List<Quotes.Fee> fees) {
        return new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), null, List.of(new Quotes.Line(sku, 3, List.of(
                new Quotes.Tier(2, new BigDecimal("3000")), new Quotes.Tier(4, new BigDecimal("2800"))))), null, fees);
    }

    /**
     * F-03 and F-04.1: drafted (the plate fee added by the system), priced, sent, accepted at 4 cartons by the
     * customer - 1 carton is under the MOQ - and converted: the DRAFT order of the accepted quote.
     */
    private UUID quotedAndConverted() {
        Quotes.QuoteView drafted = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(List.of())));
        assertThat(drafted.fees()).extracting(Quotes.FeeView::kind).containsExactly(FeeKind.PLATE_FEE);
        Quotes.QuoteView priced = quotes.edit(sales, drafted.id(), drafted.version(), draft(List.of(
                new Quotes.Fee(FeeKind.PLATE_FEE, sku, new BigDecimal("500000")))));
        Quotes.QuoteView sent = quotes.send(sales, drafted.id(), priced.version());
        assertThat(sent.status()).isEqualTo(QuoteStatus.SENT);

        Quotes.QuoteView seen = quotes.get(portal, sent.id());
        UUID line = seen.lines().getFirst().id();
        assertThat(seen.lines().getFirst().moq()).as("the lowest tier is the MOQ, in cartons").isEqualTo(2);
        assertThatThrownBy(() -> quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(line, 1)))).extracting(Wf02SalesFlowIntegrationTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(line, 4)));
        assertThat(accepted.status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(accepted.lines().getFirst().acceptedUnitPrice()).as("the 4-carton tier").isEqualByComparingTo("2800");
        assertThat(accepted.acceptedTotal()).as("112,000 + 500,000 + 61,200 VAT").isEqualByComparingTo(total);

        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        assertThat(status(orderId)).isEqualTo(OrderStatus.DRAFT);
        assertThat(orders.findById(orderId).orElseThrow().total().amount()).isEqualByComparingTo(total);
        return orderId;
    }

    private UUID lineOf(UUID orderId) {
        return jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
    }

    /** Two batches, three weeks and five weeks out. */
    private List<SalesContracts.Batch> split(UUID line, int first, int second) {
        return List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                        List.of(new SalesContracts.BatchLine(line, first))),
                new SalesContracts.Batch(today.plusDays(35), today.plusDays(37),
                        List.of(new SalesContracts.BatchLine(line, second))));
    }

    /** 30% on signing (201,960), then batch 1 before delivery and batch 2 after it, at the amounts given. */
    private static List<SalesContracts.Installment> plan(long beforeBatch1, long afterBatch2) {
        return List.of(new SalesContracts.Installment("Dat coc", "ON_SIGNING", null, new BigDecimal("30"), 3, null),
                new SalesContracts.Installment("Truoc giao dot 1", "BEFORE_DELIVERY", BigDecimal.valueOf(beforeBatch1),
                        null, null, 1),
                new SalesContracts.Installment("Sau giao dot 2", "AFTER_DELIVERY", BigDecimal.valueOf(afterBatch2),
                        null, 30, 2));
    }

    private SalesContracts.ContractView signed(UUID orderId, SalesContracts.ContractView sent) {
        byte[] scan = TestPdfs.blank();
        return asSales(() -> contracts.sign(sales, orderId, sent.version(), IncomingFiles.sent(List.of(
                new FileUpload("signed.pdf", "application/pdf", scan.length, new ByteArrayInputStream(scan)))), today));
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private long reservations(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory.stock_reservation WHERE order_id = ?", Long.class,
                orderId);
    }

    private UUID batch(UUID orderId, int seq) {
        return jdbc.queryForObject("""
                SELECT b.id FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
                 WHERE c.order_id = ? AND c.status <> 'CANCELLED' AND b.seq = ?""", UUID.class, orderId, seq);
    }

    private static List<Long> amounts(List<SalesContracts.InstallmentView> plan) {
        return plan.stream().map(i -> i.amount().longValueExact()).toList();
    }

    private int ordersOf(UUID customer) {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE customer_id = ?", Integer.class,
                customer);
    }

    private String latestCreditOutcome(UUID orderId) {
        return jdbc.queryForObject("""
                SELECT outcome FROM ordering.order_credit_check WHERE order_id = ?
                ORDER BY checked_at DESC, created_at DESC LIMIT 1""", String.class, orderId);
    }

    /** The accountant records {@code amount} dong of {@code installment} under a statement line nobody used. */
    private void pay(UUID installment, long amount) {
        receipts.record(new PaymentReceiptService.RecordReceiptCommand(customerId,
                "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(), today, null,
                List.of(new PaymentReceipt.Payment(installment, BigDecimal.valueOf(amount))), accountant));
    }

    /**
     * DS-02b's precondition: the customer owes an instalment past its due day. An earlier order of theirs, paid all on
     * signing, signed and not paid; its due day moved ten days back with the triggers off - the one way to a day in the
     * past without a fake clock (as {@code InstallmentReceiptIntegrationTest#overdue} does with the sending).
     */
    private void owesSomethingOverdue() {
        UUID earlier = quotedAndConverted();
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, earlier, new SalesContracts.Draft(
                null, List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                List.of(new SalesContracts.BatchLine(lineOf(earlier), 4)))), List.of(new SalesContracts.Installment(
                "Thanh toan khi ky", "ON_SIGNING", null, new BigDecimal("100"), 0, null)))));
        assertThat(drafted.creditWarning()).as("nothing after delivery: no credit asked").isNull();
        signed(earlier, asSales(() -> contracts.send(sales, earlier, drafted.version())));
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE payment.installment SET due_date = ? WHERE order_id = ?", today.minusDays(10), earlier);
        });
        assertThat(payments.creditPosition(customerId).hasOverdue()).isTrue();
    }

    /**
     * DS-02b steps 1-2 on {@code orderId}: a contract paying batch 2 after delivery - warned as a DRAFT and when sent,
     * never refused (14 §4.3 step 13, 15 BR-11) - signed: ON_HOLD for credit, OVERDUE recorded, in the accountant's
     * queue, its instalments open (held or not, a signed contract is owed what it says), nothing held.
     */
    private SalesContracts.ContractView heldForCredit(UUID orderId) {
        UUID line = lineOf(orderId);
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, split(line, 2, 2), plan(half, half))));
        assertThat(drafted.creditWarning()).as("warned before sending").isNotNull();
        assertThat(drafted.creditWarning().outcome()).isEqualTo("OVERDUE");
        assertThat(asSales(() -> contracts.get(orderId)).creditWarning().outcome()).as("read again, still warned")
                .isEqualTo("OVERDUE");
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, drafted.version()));
        assertThat(sent.creditWarning().outcome()).as("sent anyway, warned").isEqualTo("OVERDUE");
        assertThat(asSales(() -> contracts.get(orderId)).creditWarning()).as("a sent contract is past warning")
                .isNull();

        signed(orderId, sent);
        assertThat(status(orderId)).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(orders.findById(orderId).orElseThrow().lifecycle().holdReason())
                .isEqualTo(com.stockflow.order.api.HoldReason.CREDIT);
        assertThat(latestCreditOutcome(orderId)).isEqualTo("OVERDUE");
        assertThat(creditHolds.heldOrders(0, 200).items()).contains(orderId);
        assertThat(jdbc.queryForList("SELECT status FROM payment.installment WHERE order_id = ? ORDER BY seq",
                String.class, orderId)).containsExactly("DUE", "NOT_DUE", "NOT_DUE");
        assertThat(reservations(orderId)).isZero();
        return sent;
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("TC-F04-03 / DS-02a: quote -> accepted >= MOQ -> order + contract of 2 batches -> signed -> paid on "
            + "signing -> CONFIRMED by itself, nothing held before; BEFORE_DELIVERY gates its own batch only")
    void ds02aQuoteToConfirmedOrder() {
        UUID orderId = quotedAndConverted();
        UUID line = lineOf(orderId);

        // 30% on signing = 201,960; the remaining 471,240 split as the two batches are worth (2 + 2 cartons).
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, split(line, 2, 2), plan(half, half))));
        assertThat(drafted.status()).isEqualTo("DRAFT");
        assertThat(drafted.deliverySchedule()).extracting(SalesContracts.BatchView::seq).containsExactly(1, 2);
        assertThat(amounts(drafted.paymentPlan())).containsExactly(deposit, half, half);
        assertThat(drafted.paymentPlan()).extracting(SalesContracts.InstallmentView::milestone)
                .containsExactly("ON_SIGNING", "BEFORE_DELIVERY", "AFTER_DELIVERY");

        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, drafted.version()));
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(status(orderId)).isEqualTo(OrderStatus.PENDING_CONTRACT);

        SalesContracts.ContractView signedContract = signed(orderId, sent);
        assertThat(signedContract.status()).isEqualTo("SIGNED");
        assertThat(status(orderId)).as("14 §4.3 step 15: it waits for the installment on signing")
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(reservations(orderId)).as("F-04.3: nothing is held while it waits for its money").isZero();

        // F-05.3 on the real rows: what must be paid before each batch is handed over. The deposit is owed before any;
        // batch 1's own installment only before batch 1.
        UUID batch1 = batch(orderId, 1);
        UUID batch2 = batch(orderId, 2);
        assertThat(payments.dueBeforeHandover(orderId, batch1)).isEqualByComparingTo(BigDecimal.valueOf(deposit + half));
        assertThat(payments.dueBeforeHandover(orderId, batch2)).isEqualByComparingTo(BigDecimal.valueOf(deposit));

        List<UUID> installments = jdbc.queryForList("SELECT id FROM payment.installment WHERE order_id = ? ORDER BY "
                + "seq", UUID.class, orderId);
        assertThat(installments).hasSize(3);
        PaymentReceipt receipt = receipts.record(new PaymentReceiptService.RecordReceiptCommand(customerId,
                "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(), today, null,
                List.of(new PaymentReceipt.Payment(installments.getFirst(), BigDecimal.valueOf(deposit))), accountant))
                .receipt();
        assertThat(receipt.amount()).isEqualByComparingTo(BigDecimal.valueOf(deposit));
        Await.until("the order confirmed by its receipt", ASYNC, () -> status(orderId) == OrderStatus.CONFIRMED);

        assertThat(payments.dueBeforeHandover(orderId, batch1)).as("batch 1 still waits for its own installment")
                .isEqualByComparingTo(BigDecimal.valueOf(half));
        assertThat(payments.dueBeforeHandover(orderId, batch2)).as("batch 2 is not held by batch 1's money")
                .isEqualByComparingTo("0");
        assertThat(reservations(orderId)).as("held once Sales chooses its shipping warehouse, not before").isZero();
        assertThat(jdbc.queryForList("SELECT to_status FROM ordering.order_status_history WHERE order_id = ? "
                + "ORDER BY occurred_at, created_at", String.class, orderId))
                .containsSubsequence("PENDING_CONTRACT", "PENDING_PAYMENT", "CONFIRMED");
    }

    @Test
    @DisplayName("14 §4.3 step 16, a sent contract changed: it is not edited; cancelled, the order is a draft again; a "
            + "wrong split is refused naming the SKU; the right one gets its batch installments suggested again, adding up")
    void aSentContractIsCancelledAndRedrawn() {
        UUID orderId = quotedAndConverted();
        UUID line = lineOf(orderId);
        SalesContracts.ContractView first = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, split(line, 2, 2), plan(half, half))));
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, first.version()));

        // A5: the state first - a plan that does not add up is not what is wrong with editing a sent contract.
        assertThatThrownBy(() -> asSales(() -> contracts.edit(sales, orderId, sent.version(), new SalesContracts.Draft(
                null, null, List.of(new SalesContracts.Installment("All", "ON_SIGNING", BigDecimal.ONE, null, 0,
                        null))))))
                .extracting(Wf02SalesFlowIntegrationTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        assertThatThrownBy(() -> asSales(() -> contracts.suggestPaymentPlan(orderId)))
                .extracting(Wf02SalesFlowIntegrationTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);

        // A6: read under the caller's data scope - out of it, there is no such order.
        UUID stranger = tx.execute(s -> ReferenceRows.user(entityManager));
        CurrentUser own = new CurrentUser(stranger, "own-" + stranger, Set.of(Role.SALES_STAFF), Set.of(),
                DataScope.OWN, Set.of(), Set.of());
        assertThatThrownBy(() -> WithCurrentUser.call(own, DataScope.OWN, () -> contracts.get(orderId)))
                .extracting(Wf02SalesFlowIntegrationTest::code).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> WithCurrentUser.call(own, DataScope.OWN, () -> contracts.history(orderId)))
                .extracting(Wf02SalesFlowIntegrationTest::code).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(asSales(() -> contracts.get(orderId)).id()).isEqualTo(sent.id());

        SalesContracts.ContractView cancelled = asSales(() -> contracts.cancel(sales, orderId, sent.version(),
                "The customer wants 1 carton first, 3 later"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancellationReason()).isEqualTo("The customer wants 1 carton first, 3 later");
        assertThat(status(orderId)).as("14 §4.3 step 16: drawn up again").isEqualTo(OrderStatus.DRAFT);

        // 1 + 2 cartons of an order of 4: refused, naming the SKU; nothing kept.
        Throwable wrongSplit = org.assertj.core.api.Assertions.catchThrowable(() -> asSales(() -> contracts.create(
                sales, orderId, new SalesContracts.Draft(null, split(line, 1, 2), plan(half, half)))));
        assertThat(code(wrongSplit)).isEqualTo(ErrorCode.DELIVERY_SCHEDULE_INVALID);
        assertThat(fieldErrors(wrongSplit)).extracting(FieldError::message)
                .anyMatch(message -> message.startsWith(sku + ": 3 carton(s) over the batches, the order has 4"));
        assertThat(asSales(() -> contracts.history(orderId))).as("nothing was kept").hasSize(1);

        // 1 + 3: the old amounts still add up to the total, so they are taken - but they are the old batches' worth.
        SalesContracts.ContractView redrawn = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, split(line, 1, 3), plan(half, half))));
        assertThat(redrawn.deliverySchedule()).extracting(b -> b.value().longValueExact())
                .as("10 cups and 30 cups with tax").containsExactly(tenCups, thirtyCups);

        // A4: suggested again from the batches as they are: the 471,240 after the deposit, shared 1 : 3.
        SalesContracts.PlanSuggestion suggestion = asSales(() -> contracts.suggestPaymentPlan(orderId));
        assertThat(suggestion.contractId()).isEqualTo(redrawn.id());
        assertThat(suggestion.version()).isEqualTo(redrawn.version());
        assertThat(suggestion.totalAmount()).isEqualByComparingTo(total);
        assertThat(amounts(suggestion.paymentPlan())).containsExactly(deposit, quarter, threeQuarters);
        assertThat(suggestion.paymentPlan().stream().map(SalesContracts.InstallmentView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
        assertThat(asSales(() -> contracts.get(orderId)).paymentPlan()).as("a suggestion saves nothing")
                .extracting(i -> i.amount().longValueExact()).containsExactly(deposit, half, half);

        // Sales takes it as it is.
        SalesContracts.ContractView edited = asSales(() -> contracts.edit(sales, orderId, suggestion.version(),
                new SalesContracts.Draft(null, null, suggestion.paymentPlan().stream()
                        .map(i -> new SalesContracts.Installment(i.name(), i.milestone(),
                                i.percent() == null ? i.amount() : null, i.percent(), i.dueDays(),
                                i.deliveryBatchSeq())).toList())));
        assertThat(amounts(edited.paymentPlan())).containsExactly(deposit, quarter, threeQuarters);
        assertThat(edited.paymentPlan().getFirst().percent()).as("the deposit is still a share")
                .isEqualByComparingTo("30");

        SalesContracts.ContractView resent = asSales(() -> contracts.send(sales, orderId, edited.version()));
        assertThat(signed(orderId, resent).status()).isEqualTo("SIGNED");
        assertThat(status(orderId)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.sales_contract WHERE order_id = ? "
                + "AND status <> 'CANCELLED'", Long.class, orderId)).as("one contract in force (14 BR-12)").isEqualTo(1);
        assertThat(asSales(() -> contracts.history(orderId))).extracting(SalesContracts.ContractView::status)
                .containsExactlyInAnyOrder("SIGNED", "CANCELLED");
        assertThat(jdbc.queryForList("SELECT amount FROM payment.installment WHERE order_id = ? ORDER BY seq",
                BigDecimal.class, orderId)).extracting(BigDecimal::longValueExact)
                .as("the installments opened are the suggested ones").containsExactly(deposit, quarter, threeQuarters);
        assertThat(reservations(orderId)).isZero();
    }

    @Test
    @DisplayName("TC-F03-01: accepting fewer cartons than the MOQ is refused, and the refusal shows the MOQ - in "
            + "Vietnamese to the customer, and as details for the form")
    void belowTheMoqTheMoqIsShown() {
        Quotes.QuoteView drafted = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(List.of(new Quotes.Fee(FeeKind.PLATE_FEE, sku, new BigDecimal("500000"))))));
        Quotes.QuoteView sent = quotes.send(sales, drafted.id(), drafted.version());
        Quotes.QuoteView seen = quotes.get(portal, sent.id());
        UUID line = seen.lines().getFirst().id();

        Throwable below = org.assertj.core.api.Assertions.catchThrowable(() -> quotes.accept(portal, sent.id(),
                new Quotes.Acceptance(seen.version(), Map.of(line, 1))));
        assertThat(code(below)).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);
        assertThat(((BusinessException) below).details()).containsEntry("moq", 2).containsEntry("cartons", 1)
                .containsEntry("sku", sku).containsEntry("lineNo", 1);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.of("vi"));
        try {
            var answer = errors.handleBusiness((BusinessException) below);
            assertThat(answer.getStatusCode().value()).isEqualTo(409);
            assertThat(answer.getBody().message()).isEqualTo("Dòng 1 (" + sku + "): 1 thùng thấp hơn số lượng đặt tối "
                    + "thiểu (MOQ) là 2 thùng.");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
        assertThat(quotes.get(portal, sent.id()).status()).as("nothing changed").isEqualTo(QuoteStatus.SENT);
        assertThat(quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(), Map.of(line, 2))).status())
                .as("at the MOQ it is taken").isEqualTo(QuoteStatus.ACCEPTED);
    }

    @Test
    @DisplayName("TC-F04-01: an order is made only from an accepted quote, and keeps its prices, fees and rates whatever "
            + "the customer's price list says afterwards")
    void anOrderOnlyFromAnAcceptedQuoteAtItsLockedPrices() {
        Quotes.QuoteView drafted = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                draft(List.of(new Quotes.Fee(FeeKind.PLATE_FEE, sku, new BigDecimal("500000"))))));
        assertThatThrownBy(() -> quotes.convert(sales, drafted.id(), new Quotes.Conversion(drafted.version(), null,
                null))).as("a draft").extracting(Wf02SalesFlowIntegrationTest::code)
                .isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
        Quotes.QuoteView sent = quotes.send(sales, drafted.id(), drafted.version());
        assertThatThrownBy(() -> quotes.convert(sales, sent.id(), new Quotes.Conversion(sent.version(), null, null)))
                .as("sent, not accepted").extracting(Wf02SalesFlowIntegrationTest::code)
                .isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
        assertThat(ordersOf(customerId)).isZero();

        Quotes.QuoteView seen = quotes.get(portal, sent.id());
        Quotes.QuoteView accepted = quotes.accept(portal, sent.id(), new Quotes.Acceptance(seen.version(),
                Map.of(seen.lines().getFirst().id(), 4)));
        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        // The customer's own price list is written afterwards, far cheaper: the order does not move (14 BR-06).
        tx.executeWithoutResult(s -> {
            UUID price = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO catalog.customer_price (id, customer_id, sku, currency, version, created_at)
                    VALUES (?, ?, ?, 'VND', 0, NOW())""", price, customerId, sku);
            jdbc.update("""
                    INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, version,
                        created_at)
                    VALUES (?, ?, 1, 100, 0, NOW())""", Identifiers.newId(), price);
        });
        Map<String, Object> line = jdbc.queryForMap("SELECT unit_price, cartons, units_per_carton, tax_rate FROM "
                + "ordering.order_line WHERE order_id = ?", orderId);
        assertThat((BigDecimal) line.get("unit_price")).as("the accepted tier's price").isEqualByComparingTo("2800");
        assertThat(line.get("cartons")).isEqualTo(4);
        assertThat((BigDecimal) line.get("tax_rate")).as("the quote's rate").isEqualByComparingTo(
                accepted.lines().getFirst().taxRate());
        assertThat(jdbc.queryForObject("SELECT amount FROM ordering.order_fee_line WHERE order_id = ? AND kind = "
                + "'PLATE_FEE'", BigDecimal.class, orderId)).isEqualByComparingTo("500000");
        assertThat(orders.findById(orderId).orElseThrow().total().amount()).as("the accepted total with tax")
                .isEqualByComparingTo(accepted.acceptedTotal());
        assertThat(quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId()).as("converted again: the same order").isEqualTo(orderId);
        assertThat(ordersOf(customerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-F04-02: cartons over the batches other than the order line's, or instalments not adding up to the "
            + "total with tax, are neither saved nor sent - each field told, in Vietnamese too")
    void schedulesAndPlansThatDoNotAddUpAreRefused() {
        UUID orderId = quotedAndConverted();
        UUID line = lineOf(orderId);

        Throwable split = org.assertj.core.api.Assertions.catchThrowable(() -> asSales(() -> contracts.create(sales,
                orderId, new SalesContracts.Draft(null, split(line, 2, 1), plan(half, half)))));
        assertThat(code(split)).isEqualTo(ErrorCode.DELIVERY_SCHEDULE_INVALID);
        Throwable plan = org.assertj.core.api.Assertions.catchThrowable(() -> asSales(() -> contracts.create(sales,
                orderId, new SalesContracts.Draft(null, split(line, 2, 2), plan(half, half - 1000)))));
        assertThat(code(plan)).isEqualTo(ErrorCode.PAYMENT_PLAN_INVALID);
        assertThat(fieldErrors(plan)).extracting(FieldError::field).contains("paymentPlan");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.sales_contract WHERE order_id = ?", Long.class,
                orderId)).as("nothing saved").isZero();

        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.of("vi"));
        try {
            assertThat(errors.handleBusiness((BusinessException) split).getBody().fieldErrors())
                    .extracting(FieldError::message).contains(sku + ": tổng số thùng qua các đợt giao là 3, khác số "
                            + "thùng của dòng đơn là 4.");
            assertThat(errors.handleBusiness((BusinessException) plan).getBody().fieldErrors())
                    .extracting(FieldError::message).anyMatch(m -> m.startsWith("Tổng các đợt thanh toán là ")
                            && m.contains("khác tổng tiền đơn (có thuế)"));
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }

        // Saved right, then edited wrong: the edit is refused and the saved one stays, sendable.
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, split(line, 2, 2), plan(half, half))));
        assertThatThrownBy(() -> asSales(() -> contracts.edit(sales, orderId, drafted.version(),
                new SalesContracts.Draft(null, null, plan(half, half + 1)))))
                .extracting(Wf02SalesFlowIntegrationTest::code).isEqualTo(ErrorCode.PAYMENT_PLAN_INVALID);
        assertThatThrownBy(() -> asSales(() -> contracts.edit(sales, orderId, drafted.version(),
                new SalesContracts.Draft(null, split(line, 4, 1), null))))
                .extracting(Wf02SalesFlowIntegrationTest::code).isEqualTo(ErrorCode.DELIVERY_SCHEDULE_INVALID);
        assertThat(asSales(() -> contracts.send(sales, orderId, drafted.version())).status()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("DS-02b: a customer owing something overdue - warned on the DRAFT and on sending, never refused; signed, "
            + "ON_HOLD for credit (OVERDUE); approved with a note: PENDING_PAYMENT, then CONFIRMED by the deposit")
    void ds02bCreditHoldApproved() {
        owesSomethingOverdue();
        UUID orderId = quotedAndConverted();
        SalesContracts.ContractView sent = heldForCredit(orderId);
        assertThat(sent.status()).isEqualTo("SENT");

        assertThatThrownBy(() -> creditHolds.approve(orderId, " ", accountant))
                .as("15 BR-11: a decision says why").extracting(Wf02SalesFlowIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(creditHolds.approve(orderId, "Khách cam kết trả khoản quá hạn trong tuần", accountant).status())
                .as("14 §5.3: approved, it still waits for its deposit").isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(latestCreditOutcome(orderId)).isEqualTo("APPROVED");
        assertThat(creditHolds.heldOrders(0, 200).items()).doesNotContain(orderId);
        assertThat(reservations(orderId)).as("still nothing held").isZero();

        UUID onSigning = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ? AND milestone = "
                + "'ON_SIGNING'", UUID.class, orderId);
        pay(onSigning, deposit);
        Await.until("confirmed by its deposit", ASYNC, () -> status(orderId) == OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForList("SELECT to_status FROM ordering.order_status_history WHERE order_id = ? "
                + "ORDER BY occurred_at, created_at", String.class, orderId))
                .containsSubsequence("PENDING_CONTRACT", "ON_HOLD", "PENDING_PAYMENT", "CONFIRMED");
    }

    @Test
    @DisplayName("DS-02b, refused: the deposit paid while the order was held for credit; refused with a note, the order "
            + "is CANCELLED (CREDIT_REJECTED), what is unpaid is cancelled and the deposit is to refund")
    void ds02bCreditRefusedRefundsTheDeposit() {
        owesSomethingOverdue();
        UUID orderId = quotedAndConverted();
        heldForCredit(orderId);
        UUID onSigning = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ? AND milestone = "
                + "'ON_SIGNING'", UUID.class, orderId);
        pay(onSigning, deposit);
        assertThat(jdbc.queryForObject("SELECT status FROM payment.installment WHERE id = ?", String.class, onSigning))
                .isEqualTo("PAID");
        assertThat(status(orderId)).as("a credit hold is accounting's to decide, paid or not")
                .isEqualTo(OrderStatus.ON_HOLD);

        assertThatThrownBy(() -> creditHolds.refuse(orderId, "", accountant))
                .extracting(Wf02SalesFlowIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        var refused = creditHolds.refuse(orderId, "Khách quá hạn 41 triệu, chưa có kế hoạch trả", accountant);
        assertThat(refused.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(refused.cancellation().reasonCode()).isEqualTo(CancellationReasonCode.CREDIT_REJECTED);
        assertThat(latestCreditOutcome(orderId)).isEqualTo("REJECTED");
        assertThat(payments.refundProgress(orderId).refundable()).as("15 §4.3 step 10: what was collected is to refund")
                .isEqualByComparingTo(BigDecimal.valueOf(deposit));
        assertThat(payments.refundProgress(orderId).outstanding()).isEqualByComparingTo(BigDecimal.valueOf(deposit));
        assertThat(jdbc.queryForList("SELECT status FROM payment.installment WHERE order_id = ? AND milestone <> "
                + "'ON_SIGNING'", String.class, orderId)).as("15 BR-15: the unpaid ones are cancelled")
                .containsOnly("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.sales_contract WHERE order_id = ? AND status <> "
                + "'CANCELLED'", String.class, orderId)).as("a signed contract stays SIGNED").isEqualTo("SIGNED");
        assertThat(reservations(orderId)).isZero();
    }

    @Test
    @DisplayName("O-13 / O-09: a draft order is never confirmed without its contract (the trigger refuses it), and is "
            + "cancelled through the service")
    void aDraftOrderIsCancelledNeverConfirmedDirectly() {
        UUID orderId = quotedAndConverted();

        for (String placed : List.of("CONFIRMED", "PENDING_PAYMENT")) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET "
                    + "status = ?, contract_signed_at = NOW() WHERE id = ?", placed, orderId)))
                    .as("DRAFT -> " + placed).isInstanceOf(DataAccessException.class);
        }
        assertThat(status(orderId)).isEqualTo(OrderStatus.DRAFT);

        WithCurrentUser.run(sales, DataScope.ALL, () -> orders.cancel(orderId, new CancelOrderCommand(
                CancellationReasonCode.CUSTOMER_REQUEST, "The customer no longer needs it", null, sales.userId())));
        assertThat(status(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT from_status || '>' || to_status FROM ordering.order_status_history "
                + "WHERE order_id = ? AND to_status = 'CANCELLED'", String.class, orderId)).isEqualTo("DRAFT>CANCELLED");
        assertThat(reservations(orderId)).isZero();
    }
}
