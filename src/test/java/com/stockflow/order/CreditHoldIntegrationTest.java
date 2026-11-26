package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Credit decisions against a real database (kltn-docs 15 §4.3 steps 8-10, BR-03, BR-10, BR-11): a contract paying
 * after delivery is checked against the customer's limit when it is signed - within it the order is confirmed, over it
 * the order waits ON_HOLD for whoever approves credit, who approves with a note or refuses (the order is cancelled,
 * {@code CREDIT_REJECTED}) - and two contracts of one customer signed at once are checked one after the other.
 *
 * <p>Before 4B-10 this was {@code CreditCheckoutIntegrationTest}: the same rules on credit orders placed at checkout,
 * with the payment term chosen there (15 BR-01, {@code PAYMENT_TERM_NOT_ALLOWED}), a deposit share and a refusal that
 * switched the order to prepaid. Those went with the per-order terms: an order's terms are its contract's plan, so a
 * refusal can only cancel it ({@code OrderThroughContractIntegrationTest} covers the hold, approval and refusal once
 * each). What is checked here is what a credit decision still is: the limit, the exposure it leaves, the note, the
 * list of held orders, and the serialisation of two checks.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class CreditHoldIntegrationTest {

    /** 10 cartons of 50 at 40,000 a piece, no VAT: 20,000,000. */
    private static final long PRICE = 40_000;

    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired CreditHoldService credit;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID accountant;
    private CustomerSkuRows.Issued sku;

    @BeforeEach
    void setUp() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        confirmedOrders.legalProfile(customer);
    }

    /** Credit approved by accounting: {@code limit}, 30 days to pay (18 BR-02). */
    private void creditLimit(String limit) {
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal(limit), 30, "test terms",
                accountant, null));
    }

    /** Everything paid 30 days after delivery: what the customer's credit has to carry (15 BR-09). */
    private static List<SalesContracts.Installment> afterDelivery() {
        return List.of(new SalesContracts.Installment("After delivery", "AFTER_DELIVERY", null, new BigDecimal("100"),
                30, null));
    }

    /** A draft order of 20,000,000 whose contract is not drawn up yet. */
    private UUID draftOnCredit() {
        return confirmedOrders.draft(customer, List.of(ConfirmedOrders.Line.cartons(sku, 10, PRICE)));
    }

    /**
     * An order of 20,000,000 paid after delivery, held for credit when signed: sent while the customer's limit carried
     * it - so nothing waited for accounting before sending (15 BR-11, 2026-11-26) - and signed after accounting lowered
     * the limit to {@code limit}.
     */
    private UUID heldOnCredit(String limit) {
        creditLimit(limit);
        return confirmedOrders.signHeldForCredit(draftOnCredit(), afterDelivery()).orderId();
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private String latestOutcome(UUID orderId) {
        return jdbc.queryForObject("""
                SELECT outcome FROM ordering.order_credit_check WHERE order_id = ?
                ORDER BY checked_at DESC, created_at DESC LIMIT 1""", String.class, orderId);
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("15 BR-03: within the limit CONFIRMED; over it ON_HOLD until approved; a note is required; once only")
    void creditLimit() {
        creditLimit("30000000");
        // Both sent before either is signed: each fits the limit alone, so neither waits for accounting before it is
        // sent (15 BR-11); signed one after the other, the second no longer fits.
        UUID first = draftOnCredit();
        UUID second = draftOnCredit();
        long firstVersion = confirmedOrders.send(first, afterDelivery(), 1).version();
        long secondVersion = confirmedOrders.send(second, afterDelivery(), 1).version();

        confirmedOrders.signSent(first, firstVersion);
        assertThat(status(first)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(latestOutcome(first)).isEqualTo("WITHIN_LIMIT");

        confirmedOrders.signSent(second, secondVersion);
        assertThat(status(second)).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(orders.findById(second).orElseThrow().lifecycle().holdReason()).isEqualTo(HoldReason.CREDIT);
        assertThat(latestOutcome(second)).isEqualTo("OVER_LIMIT");
        CreditHoldService.CreditPosition position = credit.position(customer);
        assertThat(position.exposure()).as("15 BR-10: every signed contract's installments after delivery, the held "
                + "one's too").isEqualByComparingTo("40000000");
        assertThat(position.available()).isEqualByComparingTo("0");
        assertThat(credit.heldOrders(0, 200).items()).contains(second).doesNotContain(first);

        assertThat(codeOf(catchThrowable(() -> credit.approve(second, " ", accountant))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(credit.approve(second, "long-standing customer", accountant).status())
                .as("nothing due on signing: confirmed").isEqualTo(OrderStatus.CONFIRMED);
        assertThat(latestOutcome(second)).isEqualTo("APPROVED");
        assertThat(codeOf(catchThrowable(() -> credit.approve(second, "again", accountant))))
                .isEqualTo(ErrorCode.ORDER_NOT_ON_CREDIT_HOLD);
        assertThat(credit.heldOrders(0, 200).items()).doesNotContain(second);
    }

    @Test
    @DisplayName("15 §4.3 step 10: refused, a held order is cancelled CREDIT_REJECTED and no longer counts")
    void refusal() {
        // A limit above zero: without one a contract may not pay after delivery at all (15 BR-12).
        UUID held = heldOnCredit("1000000");
        assertThat(status(held)).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(codeOf(catchThrowable(() -> credit.refuse(held, "", accountant))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        var refused = credit.refuse(held, "overdue elsewhere", accountant);
        assertThat(refused.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(refused.cancellation().reasonCode()).isEqualTo(CancellationReasonCode.CREDIT_REJECTED);
        assertThat(latestOutcome(held)).isEqualTo("REJECTED");
        assertThat(credit.position(customer).exposure()).as("its installments were cancelled with it (4B-9)")
                .isEqualByComparingTo("0");
        assertThat(codeOf(catchThrowable(() -> credit.refuse(held, "again", accountant))))
                .isEqualTo(ErrorCode.ORDER_NOT_ON_CREDIT_HOLD);
    }

    @Test
    @DisplayName("Sales cancel an order held for credit: the hold is closed on record, by them, with the reason")
    void cancelledWhileHeldClosesTheHold() {
        UUID held = heldOnCredit("1000000");
        assertThat(status(held)).isEqualTo(OrderStatus.ON_HOLD);
        UUID sales = tx.execute(s -> ReferenceRows.user(entityManager));

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(held,
                new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, "khách rút đơn", null, sales)));

        assertThat(status(held)).isEqualTo(OrderStatus.CANCELLED);
        var rows = jdbc.queryForList("""
                SELECT reason, resolved_by, resolution_note FROM ordering.order_hold WHERE order_id = ?""", held);
        assertThat(rows).isNotEmpty().allSatisfy(row -> {
            assertThat(row.get("resolved_by")).as("no hold left waiting for a decision").isEqualTo(sales);
            assertThat((String) row.get("resolution_note")).isEqualTo("Huỷ cùng đơn hàng: Khách yêu cầu - khách rút đơn");
        });
        assertThat(credit.heldOrders(0, 200).items()).doesNotContain(held);
    }

    @Test
    @DisplayName("two contracts of one customer signed at once are checked one after the other: one confirmed, one held")
    void concurrentSignaturesAreSerialised() throws Exception {
        creditLimit("30000000");
        confirmedOrders.sales();
        UUID a = confirmedOrders.draft(customer, List.of(ConfirmedOrders.Line.cartons(sku, 10, PRICE)));
        UUID b = confirmedOrders.draft(customer, List.of(ConfirmedOrders.Line.cartons(sku, 10, PRICE)));
        // Both sent before either is signed, so each fits the limit alone when sent (15 BR-11): a send after the other
        // was signed would wait for accounting, be approved and then pass at signing, and the race would test nothing.
        long versionA = confirmedOrders.send(a, afterDelivery(), 1).version();
        long versionB = confirmedOrders.send(b, afterDelivery(), 1).version();
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<UUID> signA = () -> confirmedOrders.signSent(a, versionA).orderId();
            Callable<UUID> signB = () -> confirmedOrders.signSent(b, versionB).orderId();
            Future<UUID> first = pool.submit(signA);
            Future<UUID> second = pool.submit(signB);
            first.get();
            second.get();
        } finally {
            pool.shutdown();
        }
        assertThat(List.of(status(a), status(b))).containsExactlyInAnyOrder(OrderStatus.CONFIRMED, OrderStatus.ON_HOLD);
    }
}
