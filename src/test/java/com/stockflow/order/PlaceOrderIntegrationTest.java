package com.stockflow.order;

import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;

import com.stockflow.contracts.OrderPlaced;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>The test that proves the architecture's central claim.</b>
 *
 * <p>The whole argument for collapsing fourteen services into one application was that placing an
 * order and reserving its stock become a single atomic operation. That is a claim about behaviour
 * under failure, and a claim like that is worth exactly as much as the test that demonstrates it.
 * {@link #stockIsRolledBackWhenTheOrderFails()} is that test.</p>
 *
 * <p>Run against a real Postgres, because the property being asserted is a database transaction
 * property. An in-memory stub would pass whatever the code did.</p>
 *
 * <p><b>Every test puts the stock back.</b> These are not wrapped in a rolled-back transaction —
 * they cannot be, since the whole point is to observe real commits — so a test that places an
 * order without cancelling it permanently consumes seeded stock and quietly changes the starting
 * conditions for every test after it. Each one therefore asserts against a {@code before} it read
 * itself, and cancels what it placed.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
class PlaceOrderIntegrationTest {

    /** From the demo seed: 25 units at HCM-A-01-02-B plus 8 at HCM-A-02-01-A. */
    private static final Sku SOFA = new Sku("SOFA-3S-GREY");

    @Autowired OrderService orders;
    @Autowired InventoryService inventory;
    @Autowired TransactionTemplate transactions;
    @Autowired RecordedEvents.Recorder events;

    private static PlaceOrderCommand order(int quantity) {
        return new PlaceOrderCommand(UUID.randomUUID(), UUID.randomUUID(), List.of(
                new PlaceOrderCommand.Line(SOFA, quantity, Money.vnd(12_000_000), null)));
    }

    @Test
    @DisplayName("placing an order reserves its stock in the same transaction")
    void placingAnOrderReservesStock() {
        int before = inventory.availableToPromise(SOFA);

        OrderSummary summary = orders.placeOrder(order(3));

        assertThat(summary.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(summary.lines()).singleElement()
                .extracting(OrderSummary.LineSummary::reservationIds)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isNotEmpty();
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before - 3);

        orders.cancel(summary.orderId(), "TEST_CLEANUP");
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }

    /**
     * The one that matters.
     *
     * <p>An order is placed and its stock reserved, then the surrounding transaction is rolled
     * back — standing in for any late failure: a constraint violation, a validation error, the
     * process being killed. Because both writes were in one transaction, the reservation
     * disappears with the order.</p>
     *
     * <p>In the microservices build this scenario produced an orphaned reservation. Inventory had
     * already committed and published {@code StockReserved}; the order service then failed. The
     * stock stayed held for a customer whose order did not exist, until a compensating
     * {@code ReleaseStock} command arrived — and if <i>that</i> message was lost, until the
     * reservation timeout fired, which is why the timeout existed. Here there is nothing to
     * compensate, because there is nothing to clean up.</p>
     */
    @Test
    @DisplayName("stock is rolled back when the order fails - no saga, no compensation")
    void stockIsRolledBackWhenTheOrderFails() {
        int before = inventory.availableToPromise(SOFA);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            orders.placeOrder(order(5));
            // The order and its five reserved units are both written at this point.
            assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before - 5);
            throw new IllegalStateException("simulated failure after the order was written");
        })).isInstanceOf(IllegalStateException.class);

        // Both are gone. No compensating command was sent, no saga state was consulted, no
        // reservation timeout had to expire.
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }

    @Test
    @DisplayName("a checkout that cannot be fully stocked reserves nothing at all")
    void insufficientStockLeavesNothingBehind() {
        int before = inventory.availableToPromise(SOFA);

        assertThatThrownBy(() -> orders.placeOrder(order(before + 1)))
                .hasMessageContaining("Insufficient stock");

        // Not "some lines reserved, order abandoned" - the failure is all-or-nothing.
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }

    /**
     * A line big enough to exhaust the first lot must draw from the second.
     *
     * <p>The seed puts 25 units at one location and 8 at another, so 30 units cannot come from one
     * stock item. This is the case that a single shared idempotency key would break — the second
     * lot's reservation would violate {@code uk_stock_reservation_request} — and the case where
     * keeping only the first reservation id would strand the second hold on cancellation.</p>
     */
    @Test
    @DisplayName("an order line spanning two lots gets a hold on each, and releases both")
    void aLineSpanningTwoLotsHoldsAndReleasesBoth() {
        int before = inventory.availableToPromise(SOFA);
        int quantity = before - 2;   // more than any single lot holds

        OrderSummary summary = orders.placeOrder(order(quantity));

        assertThat(summary.lines().getFirst().reservationIds()).hasSizeGreaterThan(1);
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before - quantity);

        orders.cancel(summary.orderId(), "CUSTOMER_REQUEST");

        // Every hold released, not just the first one.
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }

    @Test
    @DisplayName("resubmitting the same checkout returns the original order, not a second one")
    void checkoutIsIdempotent() {
        PlaceOrderCommand command = order(2);
        int before = inventory.availableToPromise(SOFA);

        OrderSummary first = orders.placeOrder(command);
        OrderSummary retry = orders.placeOrder(command);

        assertThat(retry.orderId()).isEqualTo(first.orderId());
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before - 2);

        orders.cancel(first.orderId(), "TEST_CLEANUP");
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }

    @Test
    @DisplayName("cancelling puts the stock back")
    void cancellationReleasesStock() {
        int before = inventory.availableToPromise(SOFA);
        OrderSummary summary = orders.placeOrder(order(4));

        orders.cancel(summary.orderId(), "CUSTOMER_REQUEST");

        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
        assertThat(orders.findById(summary.orderId()))
                .get()
                .extracting(OrderSummary::status)
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("placing an order publishes OrderPlaced for whoever is listening")
    void placingAnOrderPublishesOrderPlaced() {
        OrderSummary summary = orders.placeOrder(order(1));

        assertThat(events.matching(OrderPlaced.class,
                event -> event.orderId().equals(summary.orderId()))).hasSize(1);

        orders.cancel(summary.orderId(), "TEST_CLEANUP");
    }
}
