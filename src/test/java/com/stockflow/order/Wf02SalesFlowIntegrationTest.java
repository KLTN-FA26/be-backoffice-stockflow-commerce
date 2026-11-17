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
 *   <li><b>DS-02b</b> - the sent contract cannot be edited; cancelled with a reason, the order is a draft again; a new
 *       split of the cartons that does not add up is refused naming the SKU; the right split takes the installments
 *       tied to its batches suggested again, adding up to the total; sent and signed, the order waits for its money
 *       under its one live contract.</li>
 * </ul>
 *
 * <p>Every money figure follows from one line: 4 cartons of 10 at 2,800 (the 4-carton tier) = 112,000 + 10% VAT, and a
 * plate fee of 500,000 + 10% VAT - 673,200 with tax.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.provider=smtp", "stockflow.notification.email.customer.enabled=false"})
class Wf02SalesFlowIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final BigDecimal TOTAL = new BigDecimal("673200");

    @Autowired Quotes quotes;
    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired PaymentService payments;
    @Autowired PaymentReceiptService receipts;
    @Autowired CustomerService customers;
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
        assertThat(accepted.acceptedTotal()).as("112,000 + 500,000 + 61,200 VAT").isEqualByComparingTo(TOTAL);

        UUID orderId = quotes.convert(sales, accepted.id(), new Quotes.Conversion(accepted.version(), null, null))
                .convertedOrder().orderId();
        assertThat(status(orderId)).isEqualTo(OrderStatus.DRAFT);
        assertThat(orders.findById(orderId).orElseThrow().total().amount()).isEqualByComparingTo(TOTAL);
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

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("DS-02a: quote -> accepted >= MOQ -> order + contract of 2 batches -> signed -> paid on signing -> "
            + "CONFIRMED by itself, nothing held before; BEFORE_DELIVERY gates its own batch only")
    void ds02aQuoteToConfirmedOrder() {
        UUID orderId = quotedAndConverted();
        UUID line = lineOf(orderId);

        // 30% on signing = 201,960; the remaining 471,240 split as the two batches are worth (2 + 2 cartons).
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, split(line, 2, 2), plan(235_620, 235_620))));
        assertThat(drafted.status()).isEqualTo("DRAFT");
        assertThat(drafted.deliverySchedule()).extracting(SalesContracts.BatchView::seq).containsExactly(1, 2);
        assertThat(amounts(drafted.paymentPlan())).containsExactly(201_960L, 235_620L, 235_620L);
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
        assertThat(payments.dueBeforeHandover(orderId, batch1)).isEqualByComparingTo("437580");
        assertThat(payments.dueBeforeHandover(orderId, batch2)).isEqualByComparingTo("201960");

        List<UUID> installments = jdbc.queryForList("SELECT id FROM payment.installment WHERE order_id = ? ORDER BY "
                + "seq", UUID.class, orderId);
        assertThat(installments).hasSize(3);
        PaymentReceipt receipt = receipts.record(new PaymentReceiptService.RecordReceiptCommand(customerId,
                "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(), today, null,
                List.of(new PaymentReceipt.Payment(installments.getFirst(), new BigDecimal("201960"))), accountant))
                .receipt();
        assertThat(receipt.amount()).isEqualByComparingTo("201960");
        Await.until("the order confirmed by its receipt", ASYNC, () -> status(orderId) == OrderStatus.CONFIRMED);

        assertThat(payments.dueBeforeHandover(orderId, batch1)).as("batch 1 still waits for its own installment")
                .isEqualByComparingTo("235620");
        assertThat(payments.dueBeforeHandover(orderId, batch2)).as("batch 2 is not held by batch 1's money")
                .isEqualByComparingTo("0");
        assertThat(reservations(orderId)).as("held once Sales chooses its shipping warehouse, not before").isZero();
        assertThat(jdbc.queryForList("SELECT to_status FROM ordering.order_status_history WHERE order_id = ? "
                + "ORDER BY occurred_at, created_at", String.class, orderId))
                .containsSubsequence("PENDING_CONTRACT", "PENDING_PAYMENT", "CONFIRMED");
    }

    @Test
    @DisplayName("DS-02b: a sent contract is not edited; cancelled, the order is a draft again; a wrong split is "
            + "refused naming the SKU; the right one gets its batch installments suggested again, adding up")
    void ds02bContractRedrawn() {
        UUID orderId = quotedAndConverted();
        UUID line = lineOf(orderId);
        SalesContracts.ContractView first = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, split(line, 2, 2), plan(235_620, 235_620))));
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
                sales, orderId, new SalesContracts.Draft(null, split(line, 1, 2), plan(235_620, 235_620)))));
        assertThat(code(wrongSplit)).isEqualTo(ErrorCode.DELIVERY_SCHEDULE_INVALID);
        assertThat(fieldErrors(wrongSplit)).extracting(FieldError::message)
                .anyMatch(message -> message.startsWith(sku + ": 3 carton(s) over the batches, the order has 4"));
        assertThat(asSales(() -> contracts.history(orderId))).as("nothing was kept").hasSize(1);

        // 1 + 3: the old amounts still add up to the total, so they are taken - but they are the old batches' worth.
        SalesContracts.ContractView redrawn = asSales(() -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, split(line, 1, 3), plan(235_620, 235_620))));
        assertThat(redrawn.deliverySchedule()).extracting(b -> b.value().longValueExact())
                .as("10 cups and 30 cups with tax").containsExactly(30_800L, 92_400L);

        // A4: suggested again from the batches as they are: the 471,240 after the deposit, shared 1 : 3.
        SalesContracts.PlanSuggestion suggestion = asSales(() -> contracts.suggestPaymentPlan(orderId));
        assertThat(suggestion.contractId()).isEqualTo(redrawn.id());
        assertThat(suggestion.version()).isEqualTo(redrawn.version());
        assertThat(suggestion.totalAmount()).isEqualByComparingTo(TOTAL);
        assertThat(amounts(suggestion.paymentPlan())).containsExactly(201_960L, 117_810L, 353_430L);
        assertThat(suggestion.paymentPlan().stream().map(SalesContracts.InstallmentView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(TOTAL);
        assertThat(asSales(() -> contracts.get(orderId)).paymentPlan()).as("a suggestion saves nothing")
                .extracting(i -> i.amount().longValueExact()).containsExactly(201_960L, 235_620L, 235_620L);

        // Sales takes it as it is.
        SalesContracts.ContractView edited = asSales(() -> contracts.edit(sales, orderId, suggestion.version(),
                new SalesContracts.Draft(null, null, suggestion.paymentPlan().stream()
                        .map(i -> new SalesContracts.Installment(i.name(), i.milestone(),
                                i.percent() == null ? i.amount() : null, i.percent(), i.dueDays(),
                                i.deliveryBatchSeq())).toList())));
        assertThat(amounts(edited.paymentPlan())).containsExactly(201_960L, 117_810L, 353_430L);
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
                .as("the installments opened are the suggested ones").containsExactly(201_960L, 117_810L, 353_430L);
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
