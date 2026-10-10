package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-427 against a real database (kltn-docs 15 §4.3, BR-01, BR-03): a customer is sold only on the
 * terms allowed, a credit order within the limit is confirmed and over it waits for whoever approves
 * credit, and two credit orders placed at once are checked one after the other.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class CreditCheckoutIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");

    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired CreditHoldService credit;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private UUID customer;
    private UUID accountant;
    private final List<UUID> placed = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void customerWithCredit() {
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            if (orders.findById(id).orElseThrow().status() != OrderStatus.CANCELLED) {
                orders.cancel(id, new CancelOrderCommand(CancellationReasonCode.OTHER, "test cleanup", null, null));
            }
        }));
    }

    private void terms(boolean allowDeposit, boolean allowCredit, String limit) {
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, allowDeposit, allowCredit,
                CommercialTerm.PREPAID, allowDeposit ? new BigDecimal("30") : null,
                allowCredit ? new BigDecimal(limit) : null, allowCredit ? 30 : null, "test terms", accountant, null));
    }

    /** One sofa at {@code price}: the order amount is the price. */
    private OrderSummary place(PaymentTerm term, long price) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, 1, Money.vnd(price), null)), term, null));
        placed.add(order.orderId());
        return order;
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
    @DisplayName("15 BR-01: without terms a customer prepays; credit or deposit is PAYMENT_TERM_NOT_ALLOWED")
    void onlyAllowedTerms() {
        assertThat(codeOf(catchThrowable(() -> place(PaymentTerm.CREDIT, 1_000_000))))
                .isEqualTo(ErrorCode.PAYMENT_TERM_NOT_ALLOWED);
        assertThat(codeOf(catchThrowable(() -> place(PaymentTerm.DEPOSIT, 1_000_000))))
                .isEqualTo(ErrorCode.PAYMENT_TERM_NOT_ALLOWED);
        assertThat(place(null, 1_000_000).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        terms(true, false, null);
        OrderSummary deposit = place(PaymentTerm.DEPOSIT, 1_000_000);
        assertThat(deposit.payment().depositRequired()).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("15 BR-03: within the limit CONFIRMED and pinned; over it ON_HOLD until approved or refused")
    void creditLimit() {
        terms(false, true, "30000000");

        OrderSummary first = place(PaymentTerm.CREDIT, 24_000_000);
        assertThat(first.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(first.payment().creditTermDays()).isEqualTo(30);
        assertThat(latestOutcome(first.orderId())).isEqualTo("WITHIN_LIMIT");
        assertThat(jdbc.queryForObject("""
                SELECT bool_and(expires_at IS NULL) FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'""",
                Boolean.class, first.orderId())).isTrue();

        OrderSummary second = place(PaymentTerm.CREDIT, 12_000_000);
        assertThat(second.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(latestOutcome(second.orderId())).isEqualTo("OVER_LIMIT");
        var position = credit.position(customer);
        assertThat(position.exposure()).isEqualByComparingTo("24000000");
        assertThat(position.available()).isEqualByComparingTo("6000000");
        assertThat(credit.heldOrders(0, 50).items()).contains(second.orderId());

        assertThat(codeOf(catchThrowable(() -> credit.approve(second.orderId(), " ", accountant))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(credit.approve(second.orderId(), "long-standing customer", accountant).status())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(latestOutcome(second.orderId())).isEqualTo("APPROVED");
        assertThat(codeOf(catchThrowable(() -> credit.approve(second.orderId(), "again", accountant))))
                .isEqualTo(ErrorCode.ORDER_NOT_ON_CREDIT_HOLD);
        assertThat(credit.position(customer).exposure()).isEqualByComparingTo("36000000");
    }

    @Test
    @DisplayName("refused credit: switched to prepaid it waits for payment; cancelled it is CREDIT_REJECTED")
    void refusal() {
        terms(false, true, "0");

        OrderSummary switched = place(PaymentTerm.CREDIT, 5_000_000);
        assertThat(switched.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(codeOf(catchThrowable(() -> credit.refuse(switched.orderId(),
                CreditHoldService.RefusalAction.SWITCH_TO_DEPOSIT, null, "no deposit terms", accountant))))
                .isEqualTo(ErrorCode.PAYMENT_TERM_NOT_ALLOWED);
        OrderSummary prepaid = credit.refuse(switched.orderId(), CreditHoldService.RefusalAction.SWITCH_TO_PREPAID,
                null, "pay first this time", accountant);
        assertThat(prepaid.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(prepaid.paymentTerm()).isEqualTo(PaymentTerm.PREPAID);
        assertThat(latestOutcome(switched.orderId())).isEqualTo("REJECTED");
        // Waiting for payment again: the holds have a deadline again (kltn-docs 14 BR-07).
        assertThat(jdbc.queryForObject("""
                SELECT bool_and(expires_at IS NOT NULL) FROM inventory.stock_reservation
                WHERE order_id = ? AND status = 'HELD'""", Boolean.class, switched.orderId())).isTrue();

        OrderSummary cancelled = place(PaymentTerm.CREDIT, 5_000_000);
        OrderSummary refused = credit.refuse(cancelled.orderId(), CreditHoldService.RefusalAction.CANCEL, null,
                "overdue elsewhere", accountant);
        assertThat(refused.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(refused.cancellation().reasonCode()).isEqualTo(CancellationReasonCode.CREDIT_REJECTED);
    }

    @Test
    @DisplayName("two credit orders placed at once are checked one after the other: one confirmed, one held")
    void concurrentCheckoutsAreSerialised() throws Exception {
        terms(false, true, "30000000");
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<OrderSummary> checkout = () -> place(PaymentTerm.CREDIT, 20_000_000);
            Future<OrderSummary> a = pool.submit(checkout);
            Future<OrderSummary> b = pool.submit(checkout);
            List<OrderStatus> statuses = List.of(a.get().status(), b.get().status());
            assertThat(statuses).containsExactlyInAnyOrder(OrderStatus.CONFIRMED, OrderStatus.ON_HOLD);
        } finally {
            pool.shutdown();
        }
    }
}
