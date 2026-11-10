package com.stockflow.order;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationOutcome;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.service.CancellationRequestService;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
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
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-460 against a real database: cancelling reaches the money and the warehouse, and a customer asks once an order
 * has been released (kltn-docs 15, 17 §4.8).
 *
 * <p>Since 4B-10 every order here is a contract's, confirmed by a receipt paying its installment on signing
 * ({@link ConfirmedOrders}); what a cancellation does with that money is the order's settlement (4B-9,
 * {@code payment.order_settlement}), written in the cancelling transaction. The old money ledger's "a payment delivered
 * twice is counted once" went with {@code PaymentCaptured}: a receipt is recorded once per statement reference
 * (15 BR-03), which {@code InstallmentReceiptIntegrationTest} covers.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class OrderCancellationIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(10);
    private static final long PRICE = 12_000_000;

    @Autowired OrderService orders;
    @Autowired CancellationRequestService requests;
    @Autowired InventoryService inventory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;

    private ConfirmedOrders confirmedOrders;
    /** The demo customer's own sofa (14 BR-04), stocked at HCM for the test alone. */
    private Sku sofa;
    /** Its carton unit of two: what orders and stock both count (whole cartons, 4C-2). */
    private CustomerSkuRows.Issued sofaCarton;

    @BeforeEach
    void setUp() {
        confirmedOrders = new ConfirmedOrders(context);
        sofaCarton = CustomerSkuRows.like(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, "SOFA-3S-GREY", 2);
        sofa = new Sku(sofaCarton.sku());
        CustomerSkuRows.stock(jdbc, tx, sofa.code(), 10);
    }

    private UUID confirmed(int units) {
        return confirmedOrders.confirmed(DemoData.CUSTOMER_ID, ConfirmedOrders.Line.cartons(sofaCarton, units / 2,
                PRICE))
                .orderId();
    }

    private OrderSummary order(UUID orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    private Map<String, Object> settlement(UUID orderId) {
        return jdbc.queryForMap("""
                SELECT kind, collected_amount, fee_amount, refundable_amount FROM payment.order_settlement
                 WHERE order_id = ?""", orderId);
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("a customer asks to cancel a confirmed order; approved, holds are released, the paid money is to refund")
    void customerAsksBeforeRelease() {
        int before = inventory.availableToPromise(sofa);
        UUID sales = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID orderId = confirmed(2);
        confirmedOrders.ship(orderId, DemoData.WAREHOUSE_HCM);
        assertThat(inventory.availableToPromise(sofa)).as("held at HCM").isEqualTo(before - 2);

        // 4B-5 (17 §4.8): never at once - the customer asks, before release too, and Sales decides.
        CancellationOutcome outcome = orders.cancelOwn(orderId, DemoData.CUSTOMER_ID,
                CancellationReasonCode.CUSTOMER_REQUEST, "ordered twice", null);
        assertThat(outcome.result()).isEqualTo(CancellationOutcome.Result.REQUESTED);
        assertThat(order(orderId).status()).isEqualTo(OrderStatus.CONFIRMED);

        OrderSummary cancelled = requests.approve(orderId, outcome.requestId(), null, "agreed", sales);
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancellation().reasonCode()).isEqualTo(CancellationReasonCode.CUSTOMER_REQUEST);
        assertThat(inventory.availableToPromise(sofa)).isEqualTo(before);
        // 4B-9 (17 BR-11): nothing mass printed, no fee - everything collected is to refund.
        Map<String, Object> settled = settlement(orderId);
        assertThat(settled).containsEntry("kind", "ORDER_CANCELLED");
        assertThat((BigDecimal) settled.get("collected_amount")).isEqualByComparingTo("24000000");
        assertThat((BigDecimal) settled.get("fee_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) settled.get("refundable_amount")).isEqualByComparingTo("24000000");
    }

    @Test
    @DisplayName("once released, the customer asks; approving cancels with no fee, stops the pick, the paid money is to "
            + "refund")
    void releasedOrderGoesThroughARequest() {
        UUID coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID customerUser = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID orderId = confirmed(2);
        OrderSummary released = confirmedOrders.release(orderId, DemoData.WAREHOUSE_HCM, coordinator);
        // Fulfillment had started picking it.
        UUID pick = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
                    VALUES (?, ?, ?, ?, 'PICKING', NOW())""", pick, orderId, released.orderNumber(),
                    DemoData.WAREHOUSE_HCM);
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

        // 17 BR-11: nothing was mass printed, so nothing may be kept.
        assertThat(codeOf(catchThrowable(() -> requests.approve(orderId, asked.requestId(), new BigDecimal("25"),
                "picked already", coordinator)))).isEqualTo(ErrorCode.CANCELLATION_FEE_BEFORE_PRINTING);
        OrderSummary cancelled = requests.approve(orderId, asked.requestId(), null, "picked already", coordinator);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancellation().retainedAmount()).isEqualByComparingTo("0");
        assertThat(codeOf(catchThrowable(() -> requests.approve(orderId, asked.requestId(), null, null, coordinator))))
                .isEqualTo(ErrorCode.ORDER_CANCELLATION_REQUEST_NOT_PENDING);
        Await.until("pick cancelled", ASYNC, () -> "CANCELLED:true".equals(jdbc.queryForObject(
                "SELECT status || ':' || needs_put_back FROM fulfillment.pick WHERE id = ?", String.class, pick)));
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, pick))
                .isEqualTo("CANCELLED");
        assertThat((BigDecimal) settlement(orderId).get("refundable_amount")).isEqualByComparingTo("24000000");
    }

    @Test
    @DisplayName("a request can be rejected with a reason, and the order goes on")
    void rejection() {
        UUID coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID orderId = confirmed(2);
        confirmedOrders.release(orderId, DemoData.WAREHOUSE_HCM, coordinator);
        CancellationOutcome asked = orders.cancelOwn(orderId, DemoData.CUSTOMER_ID, null, null, null);

        assertThat(codeOf(catchThrowable(() -> requests.reject(orderId, asked.requestId(), " ", coordinator))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(requests.reject(orderId, asked.requestId(), "already packed for shipping", coordinator).status()
                .name()).isEqualTo("REJECTED");
        assertThat(order(orderId).status()).isEqualTo(OrderStatus.READY_TO_FULFILL);

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(orderId,
                new CancelOrderCommand(CancellationReasonCode.OTHER, "test cleanup", null, null)));
    }
}
