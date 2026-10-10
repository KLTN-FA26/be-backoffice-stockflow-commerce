package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationOutcome;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.service.CancellationRequestService;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-460 against a real database: cancelling reaches the money and the warehouse, a customer asks
 * once an order has been released, and a payment delivered twice is counted once (kltn-docs 15, 17).
 */
@IntegrationTest
@Import(PostgresContainer.class)
class OrderCancellationIntegrationTest {

    /** From the demo seed: 33 units across two HCM bins. */
    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration ASYNC = Duration.ofSeconds(10);

    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired CancellationRequestService requests;
    @Autowired InventoryService inventory;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private OrderSummary place(int quantity) {
        return orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID, List.of(
                new PlaceOrderCommand.Line(SOFA, quantity, Money.vnd(12_000_000), null))));
    }

    private void captured(UUID orderId, UUID paymentId, BigDecimal amount) {
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new PaymentCaptured(orderId, paymentId, amount, "VND", PaymentMethod.BANK_TRANSFER)));
    }

    /** As SCRUM-217 will record it: the payment row behind a PaymentCaptured. */
    private UUID capturedPaymentRow(UUID orderId, BigDecimal amount) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.payment (id, order_id, amount, currency, method, status, captured_at, created_at)
                VALUES (?, ?, ?, 'VND', 'BANK_TRANSFER', 'CAPTURED', NOW(), NOW())""", id, orderId, amount));
        return id;
    }

    private OrderSummary order(UUID orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("a payment delivered twice is counted once; paid in full confirms the order")
    void paymentCountedOnce() throws Exception {
        OrderSummary placed = place(1);
        UUID orderId = placed.orderId();
        UUID paymentId = UUID.randomUUID();

        captured(orderId, paymentId, new BigDecimal("12000000"));
        captured(orderId, paymentId, new BigDecimal("12000000"));
        Await.until("order confirmed", ASYNC, () -> order(orderId).status() == OrderStatus.CONFIRMED);
        Thread.sleep(500);

        OrderSummary confirmed = order(orderId);
        assertThat(confirmed.payment().paidAmount()).isEqualByComparingTo("12000000");
        assertThat(confirmed.payment().status()).isEqualTo(PaymentStatus.PAID);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_payment WHERE order_id = ?",
                Integer.class, orderId)).isEqualTo(1);

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(orderId, "TEST_CLEANUP"));
    }

    @Test
    @DisplayName("a customer cancels a confirmed order at once: holds released, the paid money asked back")
    void customerCancelsDirectly() {
        int before = inventory.availableToPromise(SOFA);
        OrderSummary placed = place(2);
        UUID orderId = placed.orderId();
        UUID payment = capturedPaymentRow(orderId, new BigDecimal("24000000"));
        captured(orderId, payment, new BigDecimal("24000000"));
        Await.until("order confirmed", ASYNC, () -> order(orderId).status() == OrderStatus.CONFIRMED);

        CancellationOutcome outcome = orders.cancelOwn(orderId, DemoData.CUSTOMER_ID,
                CancellationReasonCode.CUSTOMER_REQUEST, "ordered twice", null);

        assertThat(outcome.result()).isEqualTo(CancellationOutcome.Result.CANCELLED);
        assertThat(outcome.order().cancellation().reasonCode()).isEqualTo(CancellationReasonCode.CUSTOMER_REQUEST);
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
        Await.until("refund asked", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM payment.refund
                WHERE order_id = ? AND source = 'ORDER_CANCELLED' AND status = 'PENDING' AND amount = 24000000""",
                Integer.class, orderId) == 1);
    }

    @Test
    @DisplayName("once released, the customer asks; approving cancels with a fee kept, stops the pick, refunds the rest")
    void releasedOrderGoesThroughARequest() throws Exception {
        UUID coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID customerUser = tx.execute(s -> ReferenceRows.user(entityManager));
        OrderSummary placed = place(1);
        UUID orderId = placed.orderId();
        UUID payment = capturedPaymentRow(orderId, new BigDecimal("12000000"));
        captured(orderId, payment, new BigDecimal("12000000"));
        Await.until("order confirmed", ASYNC, () -> order(orderId).status() == OrderStatus.CONFIRMED);
        releases.release(orderId, DemoData.WAREHOUSE_HCM, coordinator);
        // Fulfillment had started picking it.
        UUID pick = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO fulfillment.pick (id, order_id, status, created_at) VALUES (?, ?, 'PICKING', NOW())",
                    pick, orderId);
            jdbc.update("INSERT INTO fulfillment.pack (id, pick_id, status, created_at) VALUES (?, ?, 'PENDING', NOW())",
                    Identifiers.newId(), pick);
        });

        CancellationOutcome asked = orders.cancelOwn(orderId, DemoData.CUSTOMER_ID,
                CancellationReasonCode.CUSTOMER_REQUEST, "wrong colour", customerUser);
        assertThat(asked.result()).isEqualTo(CancellationOutcome.Result.REQUESTED);
        assertThat(order(orderId).status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(codeOf(catchThrowable(() -> orders.cancelOwn(orderId, DemoData.CUSTOMER_ID, null, null,
                customerUser)))).isEqualTo(ErrorCode.ORDER_CANCELLATION_REQUEST_PENDING);
        assertThat(codeOf(catchThrowable(() -> orders.cancelOwn(orderId, UUID.randomUUID(), null, null,
                customerUser)))).isEqualTo(ErrorCode.NOT_FOUND);

        OrderSummary cancelled = requests.approve(orderId, asked.requestId(), new BigDecimal("25"), "picked already",
                coordinator);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancellation().retainedAmount()).isEqualByComparingTo("3000000");
        assertThat(codeOf(catchThrowable(() -> requests.approve(orderId, asked.requestId(), null, null, coordinator))))
                .isEqualTo(ErrorCode.ORDER_CANCELLATION_REQUEST_NOT_PENDING);
        Await.until("pick cancelled", ASYNC, () -> "CANCELLED:true".equals(jdbc.queryForObject(
                "SELECT status || ':' || needs_put_back FROM fulfillment.pick WHERE id = ?", String.class, pick)));
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, pick))
                .isEqualTo("CANCELLED");
        Await.until("refund asked", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM payment.refund WHERE order_id = ? AND amount = 9000000""",
                Integer.class, orderId) == 1);
    }

    @Test
    @DisplayName("a request can be rejected with a reason, and the order goes on")
    void rejection() throws Exception {
        UUID coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        OrderSummary placed = place(1);
        UUID orderId = placed.orderId();
        captured(orderId, UUID.randomUUID(), new BigDecimal("12000000"));
        Await.until("order confirmed", ASYNC, () -> order(orderId).status() == OrderStatus.CONFIRMED);
        releases.release(orderId, DemoData.WAREHOUSE_HCM, coordinator);
        CancellationOutcome asked = orders.cancelOwn(orderId, DemoData.CUSTOMER_ID, null, null, null);

        assertThat(codeOf(catchThrowable(() -> requests.reject(orderId, asked.requestId(), " ", coordinator))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(requests.reject(orderId, asked.requestId(), "already packed for shipping", coordinator).status().name())
                .isEqualTo("REJECTED");
        assertThat(order(orderId).status()).isEqualTo(OrderStatus.READY_TO_FULFILL);

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(orderId,
                new CancelOrderCommand(CancellationReasonCode.OTHER, "test cleanup", null, null)));
    }
}
