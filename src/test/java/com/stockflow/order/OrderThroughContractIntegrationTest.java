package com.stockflow.order;

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
import com.stockflow.order.api.CancellationOutcome;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.order.internal.service.CustomerOrders;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4B-5 (kltn-docs 14 §4.2, §4.3 steps 13-16, §5.3, BR-15; 15 BR-07, BR-11; 17 §5.1, §5.2, §4.8) against the real
 * database: a DRAFT order is taken on by its contract - sent, it waits for the signature; cancelled, it goes back to
 * DRAFT; signed, it waits for the installment on signing, is confirmed, or is held for accounting when what it pays
 * after delivery does not fit the customer's credit. The customer sees it from the signature's wait on, and asks to
 * cancel; Sales cancels it with its unsigned contract.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class OrderThroughContractIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired CustomerOrders customerOrders;
    @Autowired CreditHoldService creditHolds;
    @Autowired com.stockflow.order.internal.service.OrderReleases releases;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID accountant;
    private UUID customerId;
    private CustomerSkuRows.Issued sku;
    private LocalDate today;

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
                    VALUES (?, 'Cong ty Vong Doi', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "lifecycle-" + customerId + "@example.com", "04" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '5 Hai Ba Trung, Ha Noi', 'Nguyen Thi C', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
    }

    /** Credit approved by accounting: {@code limit} and 30 days to pay (4B-10: credit is these two alone). */
    private void credit(long limit) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                    approved_at)
                VALUES (?, ?, ?, 30, ?, NOW())""", Identifiers.newId(), customerId, BigDecimal.valueOf(limit),
                accountant));
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

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    /** A contract of one batch three weeks out and {@code plan}, sent. */
    private SalesContracts.ContractView sent(UUID orderId, List<SalesContracts.Installment> plan) {
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(
                null, List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                List.of(new SalesContracts.BatchLine(line, 20)))), plan)));
        return asSales(() -> contracts.send(sales, orderId, drafted.version()));
    }

    private SalesContracts.ContractView signed(UUID orderId, SalesContracts.ContractView sent) throws Exception {
        byte[] scan;
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var bytes = new java.io.ByteArrayOutputStream();
            document.save(bytes);
            scan = bytes.toByteArray();
        }
        return asSales(() -> contracts.sign(sales, orderId, sent.version(), IncomingFiles.sent(List.of(
                new FileUpload("signed.pdf", "application/pdf", scan.length, new ByteArrayInputStream(scan)))), today));
    }

    private static SalesContracts.Installment share(String name, String milestone, String percent) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), null, null);
    }

    @Test
    @DisplayName("sent: waits for the signature, the customer sees it and may ask to cancel; cancelled: back to DRAFT")
    void sentThenCancelledBackToDraft() {
        UUID orderId = draftOrder();
        assertThat(customerOrders.myOrders(customerId, new CustomerOrders.Query(0, 20, List.of(), null, null, null,
                null)).items()).as("17 §5.2: a DRAFT order is not the customer's to see")
                .extracting(CustomerOrders.Card::orderId).doesNotContain(orderId);
        assertThatThrownBy(() -> customerOrders.myOrder(customerId, orderId))
                .extracting(OrderThroughContractIntegrationTest::code).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> orders.cancelOwn(orderId, customerId, CancellationReasonCode.CUSTOMER_REQUEST, null,
                null)).as("nothing of a DRAFT order to ask about").extracting(OrderThroughContractIntegrationTest::code)
                .isEqualTo(ErrorCode.NOT_FOUND);

        SalesContracts.ContractView sent = sent(orderId, List.of(share("All", "ON_SIGNING", "100")));
        assertThat(status(orderId)).isEqualTo(OrderStatus.PENDING_CONTRACT);
        CustomerOrders.Detail mine = customerOrders.myOrder(customerId, orderId);
        assertThat(mine.statusGroup()).isEqualTo(CustomerOrders.StatusGroup.AWAITING_CONTRACT);
        assertThat(mine.allowedActions().cancel()).as("17 §4.8: never at once").isFalse();
        assertThat(mine.allowedActions().requestCancellation()).isTrue();

        CancellationOutcome asked = orders.cancelOwn(orderId, customerId, CancellationReasonCode.CUSTOMER_REQUEST,
                "not sure yet", null);
        asSales(() -> contracts.cancel(sales, orderId, sent.version(), "The customer wants another window"));
        assertThat(status(orderId)).as("14 §4.3 step 16: drawn up again").isEqualTo(OrderStatus.DRAFT);
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.order_cancellation_request WHERE id = ?",
                String.class, asked.requestId())).as("out of the customer's sight: the request closes")
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT string_agg(to_status, '>' ORDER BY occurred_at, id) FROM "
                + "ordering.order_status_history WHERE order_id = ?", String.class, orderId))
                .contains("PENDING_CONTRACT").endsWith("DRAFT");
    }

    @Test
    @DisplayName("signed with an installment on signing: waits for it; signed without: confirmed; placed either way")
    void signedThenPlaced() throws Exception {
        UUID pays = draftOrder();
        signed(pays, sent(pays, List.of(share("Deposit", "ON_SIGNING", "30"), share("Rest", "BEFORE_DELIVERY", "70"))));
        assertThat(status(pays)).isEqualTo(OrderStatus.PENDING_PAYMENT);

        UUID confirmed = draftOrder();
        signed(confirmed, sent(confirmed, List.of(share("All before delivery", "BEFORE_DELIVERY", "100"))));
        assertThat(status(confirmed)).as("14 BR-15: signed, nothing due on signing").isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("SELECT contract_signed_at IS NOT NULL AND placed_at = contract_signed_at FROM "
                + "ordering.customer_order WHERE id = ?", Boolean.class, confirmed))
                .as("placed when signed: the day the dashboard counts it from").isTrue();
        assertThatThrownBy(() -> releases.release(confirmed, com.stockflow.support.DemoData.WAREHOUSE_HCM,
                sales.userId())).as("no stock held until the shipping warehouse is chosen (4B-7)")
                .extracting(OrderThroughContractIntegrationTest::code).isEqualTo(ErrorCode.ORDER_STOCK_NOT_HELD);
        assertThat(status(confirmed)).isEqualTo(OrderStatus.CONFIRMED);
        // OrderPlaced: the dashboard counts it from the signature on.
        Await.until("the order rolled up", ASYNC, () -> jdbc.queryForObject("SELECT count(*) FROM "
                + "reporting.processed_order_events WHERE order_id = ? AND event_type = 'PLACED'", Long.class,
                confirmed) == 1);
    }

    @Test
    @DisplayName("15 BR-11: paying after delivery past the limit holds the order at signing; accounting decides")
    void heldForCreditAndDecided() throws Exception {
        credit(1_000_000);
        UUID approved = draftOrder();
        SalesContracts.ContractView sent = sent(approved, List.of(share("Deposit", "ON_SIGNING", "20"),
                share("After delivery", "AFTER_DELIVERY", "80")));
        assertThat(sent.creditWarning()).as("warned when sent, never refused").isNotNull();
        assertThat(sent.creditWarning().outcome()).isEqualTo("OVER_LIMIT");
        signed(approved, sent);
        OrderStatus held = status(approved);
        assertThat(held).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(orders.findById(approved).orElseThrow().lifecycle().holdReason()).isEqualTo(HoldReason.CREDIT);
        assertThat(jdbc.queryForObject("SELECT outcome FROM ordering.order_credit_check WHERE order_id = ? ORDER BY "
                + "checked_at DESC LIMIT 1", String.class, approved)).isEqualTo("OVER_LIMIT");
        creditHolds.approve(approved, "Known customer", accountant);
        assertThat(status(approved)).as("approved, it still waits for the deposit on signing")
                .isEqualTo(OrderStatus.PENDING_PAYMENT);

        UUID refused = draftOrder();
        signed(refused, sent(refused, List.of(share("After delivery", "AFTER_DELIVERY", "100"))));
        assertThat(status(refused)).isEqualTo(OrderStatus.ON_HOLD);
        // Its terms are its contract's: refusing credit cancels it - there is no other way to re-term it (4B-10).
        creditHolds.refuse(refused, "Too much owed", accountant);
        assertThat(status(refused)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.findById(refused).orElseThrow().cancellation().reasonCode())
                .isEqualTo(CancellationReasonCode.CREDIT_REJECTED);
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.sales_contract WHERE order_id = ?", String.class,
                refused)).as("a signed contract stays SIGNED; it is settled outside the system").isEqualTo("SIGNED");
    }

    @Test
    @DisplayName("17 §4.8: Sales cancels an order waiting for its signature, and its contract with it")
    void cancelledWithItsContract() {
        UUID orderId = draftOrder();
        sent(orderId, List.of(share("All", "ON_SIGNING", "100")));
        CancellationOutcome asked = orders.cancelOwn(orderId, customerId, CancellationReasonCode.CUSTOMER_REQUEST,
                "changed our plans", null);
        assertThat(asked.result()).isEqualTo(CancellationOutcome.Result.REQUESTED);

        WithCurrentUser.run(sales, DataScope.ALL, () -> orders.cancel(orderId, new CancelOrderCommand(
                CancellationReasonCode.CUSTOMER_REQUEST, "agreed with the customer", null, sales.userId())));
        assertThat(status(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT status || ':' || cancellation_reason FROM ordering.sales_contract WHERE "
                + "order_id = ?", String.class, orderId)).isEqualTo("CANCELLED:Order cancelled: CUSTOMER_REQUEST");
        assertThatThrownBy(() -> asSales(() -> contracts.get(orderId)))
                .extracting(OrderThroughContractIntegrationTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_NOT_FOUND);
    }
}
