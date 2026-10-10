package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.security.DataScope;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kltn-docs 14 BR-07 (SCRUM-465): a stock hold has a deadline only while its order waits for
 * payment. Paid, its holds are pinned and survive the expiry sweep; unpaid past the deadline, the
 * order is cancelled and its stock released.
 *
 * <p>The sweep is run directly rather than waited for, and a deadline is moved into the past in the
 * database instead of sleeping through the configured TTL. Every test puts its stock back.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class PaidOrderHoldIntegrationTest {

    /** From the demo seed: 25 units at HCM-A01-2-B plus 8 at HCM-A02-1-A. */
    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration ASYNC = Duration.ofSeconds(10);

    @Autowired OrderService orders;
    @Autowired InventoryService inventory;
    @Autowired TransactionTemplate transactions;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    private UUID place(int quantity) {
        return orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID, List.of(
                new PlaceOrderCommand.Line(SOFA, quantity, Money.vnd(12_000_000), null)))).orderId();
    }

    /** As the payment module will: the whole total, published in a transaction, delivered after commit. */
    private void paymentCaptured(UUID orderId) {
        BigDecimal total = orders.findById(orderId).orElseThrow().total().amount();
        transactions.executeWithoutResult(status -> publisher.publishEvent(new PaymentCaptured(
                orderId, UUID.randomUUID(), total, "VND", PaymentMethod.BANK_TRANSFER)));
    }

    private OrderStatus statusOf(UUID orderId) {
        return orders.findById(orderId).map(OrderSummary::status).orElseThrow();
    }

    private List<String> holdStates(UUID orderId) {
        return jdbc.queryForList("""
                SELECT status || ':' || CASE WHEN expires_at IS NULL THEN 'pinned' ELSE 'deadline' END
                FROM inventory.stock_reservation WHERE order_id = ? ORDER BY id""", String.class, orderId);
    }

    /** Moves every live deadline of the order into the past, as if the TTL had run out. */
    private void deadlinePassed(UUID orderId) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE inventory.stock_reservation SET expires_at = NOW() - INTERVAL '1 minute'
                WHERE order_id = ? AND status = 'HELD' AND expires_at IS NOT NULL""", orderId));
    }

    /**
     * One sweep, now. Called on the target, not the proxy: ShedLock's {@code lockAtLeastFor} would
     * otherwise skip a second sweep within ten seconds of the first, so a later test would not sweep
     * at all. The target's own {@code @Transactional} is then bypassed too, hence the template.
     */
    private void sweep() {
        Object sweeper = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                context.getBean("reservationSweeper"));
        transactions.executeWithoutResult(status -> {
            try {
                var method = sweeper.getClass().getDeclaredMethod("releaseExpiredReservations");
                method.setAccessible(true);
                method.invoke(sweeper);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void cleanUp(UUID orderId) {
        if (statusOf(orderId) != OrderStatus.CANCELLED) {
            WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(orderId, "TEST_CLEANUP"));
        }
    }

    @Test
    @DisplayName("a paid order's holds are pinned and survive the expiry sweep")
    void paidHoldsArePinned() throws Exception {
        int before = inventory.availableToPromise(SOFA);
        UUID orderId = place(2);
        assertThat(holdStates(orderId)).allMatch(state -> state.equals("HELD:deadline"));

        paymentCaptured(orderId);
        Await.until("order confirmed", ASYNC, () -> statusOf(orderId) == OrderStatus.CONFIRMED);

        assertThat(holdStates(orderId)).isNotEmpty().allMatch(state -> state.equals("HELD:pinned"));
        deadlinePassed(orderId);   // nothing to move: pinned holds have no deadline
        sweep();
        assertThat(holdStates(orderId)).allMatch(state -> state.equals("HELD:pinned"));
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before - 2);

        cleanUp(orderId);
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }

    @Test
    @DisplayName("an unpaid order whose hold ran out is cancelled and its stock released")
    void unpaidOrderIsCancelledWhenItsHoldExpires() throws Exception {
        int before = inventory.availableToPromise(SOFA);
        UUID orderId = place(3);
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before - 3);

        deadlinePassed(orderId);
        sweep();

        Await.until("order cancelled", ASYNC, () -> statusOf(orderId) == OrderStatus.CANCELLED);
        assertThat(holdStates(orderId)).isNotEmpty().allMatch(state -> state.startsWith("EXPIRED")
                || state.startsWith("RELEASED"));
        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM ordering.customer_order WHERE id = ?",
                String.class, orderId)).startsWith("PAYMENT_NOT_RECEIVED");
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }

    @Test
    @DisplayName("money arriving after the order was cancelled leaves it cancelled, without failing")
    void paymentAfterCancellationIsLeftForRefund() throws Exception {
        int before = inventory.availableToPromise(SOFA);
        UUID orderId = place(1);
        deadlinePassed(orderId);
        sweep();
        Await.until("order cancelled", ASYNC, () -> statusOf(orderId) == OrderStatus.CANCELLED);

        paymentCaptured(orderId);
        // The listener runs asynchronously; give it the same window, then check nothing moved.
        Thread.sleep(1_500);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM event_publication
                WHERE event_type LIKE '%PaymentCaptured' AND completion_date IS NULL
                  AND serialized_event LIKE ?""", Integer.class, "%" + orderId + "%")).isZero();
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }
}
