package com.stockflow.inventory;

import com.stockflow.inventory.api.InventoryService;

import com.stockflow.contracts.PaymentFailed;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.support.Await;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Event-driven behaviour across module boundaries.
 *
 * <p>What this demonstrates about the design: one {@code PaymentFailed} event causes two
 * independent reactions, in {@code inventory} and in {@code order}, neither of which knows the
 * other exists. Adding a third — notifying the customer — needs no change to either.</p>
 *
 * <p>The event is published <b>inside a transaction</b>, because
 * {@code @ApplicationModuleListener} composes {@code @TransactionalEventListener}, which by default
 * fires only after a transaction commits. Publishing outside one would drop the event silently,
 * and the test would fail with a timeout that pointed nowhere useful.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class InventoryEventIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired OrderService orders;
    @Autowired InventoryService inventory;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate transactions;

    private OrderSummary placeOrder(int quantity) {
        return orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new PlaceOrderCommand.Line(SOFA, quantity, Money.vnd(12_000_000), null))));
    }

    private void publishInTransaction(Object event) {
        transactions.executeWithoutResult(status -> publisher.publishEvent(event));
    }

    @Test
    @DisplayName("a failed payment releases the stock and cancels the order, via one event")
    void paymentFailureReleasesStockAndCancelsTheOrder() {
        int before = inventory.availableToPromise(SOFA);
        OrderSummary summary = placeOrder(3);
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before - 3);

        publishInTransaction(new PaymentFailed(summary.orderId(), UUID.randomUUID(),
                "CARD_DECLINED", "Issuer declined the transaction"));

        Await.until("inventory to release the failed order's stock", TIMEOUT,
                () -> inventory.availableToPromise(SOFA) == before);
        Await.until("order to be cancelled", TIMEOUT,
                () -> orders.findById(summary.orderId())
                        .map(OrderSummary::status)
                        .filter(OrderStatus.CANCELLED::equals)
                        .isPresent());
    }

    /**
     * Redelivery is not hypothetical here: {@code republish-outstanding-events-on-restart} means a
     * listener that was interrupted mid-handling genuinely sees the same event again.
     */
    @Test
    @DisplayName("redelivering the same event twice does not release the stock twice")
    void listenersAreIdempotent() {
        int before = inventory.availableToPromise(SOFA);
        OrderSummary summary = placeOrder(2);
        PaymentFailed event = new PaymentFailed(summary.orderId(), UUID.randomUUID(),
                "CARD_DECLINED", "Issuer declined the transaction");

        publishInTransaction(event);
        Await.until("the first release", TIMEOUT,
                () -> inventory.availableToPromise(SOFA) == before);

        publishInTransaction(event);
        // Nothing to observe changing, so give the listeners time to do the wrong thing if they
        // are going to, then assert nothing did.
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }
}
