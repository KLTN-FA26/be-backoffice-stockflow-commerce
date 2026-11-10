package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.security.DataScope;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kltn-docs 14 BR-07 (SCRUM-465): a stock hold has a deadline only while its order waits for
 * payment. A contract's order, confirmed and holding at its shipping warehouse, holds with no deadline and survives the
 * expiry sweep (4B-7, 17 §4.2); a checkout order unpaid past the deadline is cancelled and its stock released.
 *
 * <p>Money arriving for an order already cancelled is no longer this test's: since 4B-10 nothing publishes a
 * {@code PaymentCaptured}, and a receipt names an installment, which the cancellation settled (4B-9).</p>
 *
 * <p>The sweep is run directly rather than waited for, and a deadline is moved into the past in the
 * database instead of sleeping through the configured TTL. Every test puts its stock back.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class PaidOrderHoldIntegrationTest {

    /** From the demo seed: 25 units at HCM-A01-2-B plus 8 at HCM-A02-1-A. */
    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration ASYNC = Duration.ofSeconds(10);

    @Autowired OrderService orders;
    @Autowired InventoryService inventory;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    private UUID place(int quantity) {
        return orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID, List.of(
                new PlaceOrderCommand.Line(SOFA, quantity, Money.vnd(12_000_000), null)))).orderId();
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
    @DisplayName("a contract's confirmed order holds at its shipping warehouse with no deadline and survives the sweep")
    void paidHoldsArePinned() {
        // The demo customer's own sofa (a contract's lines are only ever that, 14 BR-04), stocked for this test.
        Sku sofa = new Sku(CustomerSkuRows.like(jdbc, transactions, entityManager, DemoData.CUSTOMER_ID,
                "SOFA-3S-GREY", 50).sku());
        CustomerSkuRows.stock(jdbc, transactions, sofa.code(), 5);
        ConfirmedOrders confirmedOrders = new ConfirmedOrders(context);
        UUID orderId = confirmedOrders.confirmed(DemoData.CUSTOMER_ID,
                ConfirmedOrders.Line.units(sofa.code(), 2, 12_000_000)).orderId();
        assertThat(holdStates(orderId)).as("a contract's order holds nothing before its warehouse is chosen").isEmpty();

        confirmedOrders.ship(orderId, DemoData.WAREHOUSE_HCM);
        assertThat(holdStates(orderId)).isNotEmpty().allMatch(state -> state.equals("HELD:pinned"));
        deadlinePassed(orderId);   // nothing to move: pinned holds have no deadline
        sweep();
        assertThat(holdStates(orderId)).allMatch(state -> state.equals("HELD:pinned"));
        assertThat(inventory.availableToPromise(sofa)).isEqualTo(3);

        cleanUp(orderId);
        assertThat(inventory.availableToPromise(sofa)).isEqualTo(5);
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
}
