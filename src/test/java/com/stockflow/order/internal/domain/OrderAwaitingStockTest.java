package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderReleased;
import com.stockflow.order.api.CancellationFee;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-6 (kltn-docs 17 §4.2 step 5, §4.4, §4.8 step 30, §4.9, §5.1, BR-04, BR-11): an order of a contract waits for stock
 * once a production order for its short next batch is sent, is released from there with every carton held, may be
 * held and cancelled, and records once when printing started.
 */
class OrderAwaitingStockTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID HCM = UUID.randomUUID();

    /** A quote's order, its contract signed with nothing due on signing, shipping from HCM: CONFIRMED. */
    private static Order confirmed() {
        Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 46), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ-ACME"), 1_000, Money.vnd(2_000), null)), NOW);
        order.sendContract(SALES, NOW);
        order.contractSigned(false, false, SALES, NOW);
        order.chooseShippingWarehouse(HCM, false, SALES, NOW);
        order.pullTransitions();
        return order;
    }

    /** Confirmed, then waiting for stock: a production order for its short batch sent by the manager. */
    private static Order waiting() {
        Order order = confirmed();
        assertThat(order.awaitStock(MANAGER, NOW)).isTrue();
        order.pullTransitions();
        return order;
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("BR-04: a production order sent for its short batch - CONFIRMED → AWAITING_STOCK, with who, when, why")
    void entersFromConfirmed() {
        Order order = confirmed();

        assertThat(order.awaitStock(MANAGER, NOW)).isTrue();

        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(order.pullTransitions()).containsExactly(new OrderTransition(OrderStatus.CONFIRMED,
                OrderStatus.AWAITING_STOCK, MANAGER, "PRODUCTION_ORDER_SENT", NOW));
        assertThat(order.awaitStock(MANAGER, NOW)).as("a second production order sent keeps it").isFalse();
        assertThat(order.pullTransitions()).isEmpty();
        assertThat(order.status().holdsStock()).as("its held cartons are released if it is cancelled").isTrue();
        assertThat(order.status().countsTowardCreditExposure()).isTrue();
    }

    @Test
    @DisplayName("only an order of a contract waits, and only from CONFIRMED: a released or checkout order is left alone")
    void onlyFromConfirmed() {
        Order checkout = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 47), UUID.randomUUID(),
                UUID.randomUUID(), List.of(Order.line(new Sku("SOFA-3S-GREY"), 1, Money.vnd(1_000_000), null)), NOW);
        assertThat(checkout.awaitStock(MANAGER, NOW)).isFalse();
        assertThat(checkout.status()).isEqualTo(OrderStatus.DRAFT);

        Order released = confirmed();
        released.lines().forEach(line -> released.holdMore(line.id(), List.of(UUID.randomUUID())));
        released.release(HCM, "HCM", SALES, NOW);
        assertThat(released.awaitStock(MANAGER, NOW)).isFalse();
        assertThat(released.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);

        Order pending = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 9), 48), UUID.randomUUID(),
                UUID.randomUUID(), List.of(Order.line(new Sku("CUP-12OZ-ACME"), 1_000, Money.vnd(2_000), null)), NOW);
        pending.sendContract(SALES, NOW);
        pending.contractSigned(false, true, SALES, NOW);
        assertThat(pending.awaitStock(MANAGER, NOW)).as("PENDING_PAYMENT: not confirmed yet").isFalse();
        assertThat(pending.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("17 §5.1: released with every carton held - AWAITING_STOCK → READY_TO_FULFILL, announced")
    void releasedFromAwaitingStock() {
        Order order = waiting();
        order.pullDomainEvents();
        order.lines().forEach(line -> order.holdMore(line.id(), List.of(UUID.randomUUID())));

        order.release(HCM, "HCM", SALES, NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(order.warehouseId()).isEqualTo(HCM);
        assertThat(order.releasedAt()).isEqualTo(NOW);
        assertThat(order.pullTransitions()).extracting(OrderTransition::from, OrderTransition::to)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(OrderStatus.AWAITING_STOCK,
                        OrderStatus.READY_TO_FULFILL));
        assertThat(order.pullDomainEvents()).singleElement().isInstanceOfSatisfying(OrderEvent.Released.class,
                event -> assertThat(event.payload()).isEqualTo(new OrderReleased(order.id().value(), "HCM", NOW)));
    }

    @Test
    @DisplayName("17 §5.1: no edge to picking, packing, shipping or back to CONFIRMED; release needs its stock held")
    void refusedEdges() {
        Order order = waiting();
        assertThatThrownBy(() -> order.startPicking(SALES, NOW)).isInstanceOf(InvalidOrderTransitionException.class);
        assertThatThrownBy(() -> order.markPacked(NOW, SALES)).isInstanceOf(InvalidOrderTransitionException.class);
        assertThatThrownBy(() -> order.release(HCM, "HCM", SALES, NOW)).as("nothing held yet")
                .extracting(OrderAwaitingStockTest::code).isEqualTo(ErrorCode.ORDER_STOCK_NOT_HELD);
        for (OrderStatus target : List.of(OrderStatus.CONFIRMED, OrderStatus.PICKING, OrderStatus.PACKED,
                OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.IN_PRODUCTION)) {
            assertThat(OrderStatus.AWAITING_STOCK.canTransitionTo(target)).as("AWAITING_STOCK -> " + target).isFalse();
        }
        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_STOCK);
    }

    @Test
    @DisplayName("17 §4.9: held from AWAITING_STOCK and resolved back to it")
    void heldAndResolvedBack() {
        Order order = waiting();
        order.putOnHold(HoldReason.CUSTOMER, "Customer asked to wait", SALES, NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(order.statusBeforeHold()).isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(order.awaitStock(MANAGER, NOW)).as("already waiting, held").isFalse();

        order.resolveHold("Customer confirmed", SALES, NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(order.statusBeforeHold()).isNull();
    }

    @Test
    @DisplayName("a production order sent while the order is held from CONFIRMED: the hold goes back to AWAITING_STOCK")
    void sentWhileHeld() {
        Order order = confirmed();
        order.putOnHold(HoldReason.ADDRESS, null, SALES, NOW);
        order.pullTransitions();

        assertThat(order.awaitStock(MANAGER, NOW)).isTrue();

        assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(order.statusBeforeHold()).isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(order.pullTransitions()).as("no status changed").isEmpty();
        order.resolveHold("Address checked", SALES, NOW);
        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_STOCK);
    }

    @Test
    @DisplayName("BR-11: printing started is recorded once, by the first production order in production")
    void printingStartedOnce() {
        Order order = waiting();
        Instant first = NOW.plusSeconds(3_600);

        assertThat(order.recordPrintingStarted(first)).isTrue();
        assertThat(order.recordPrintingStarted(first.plusSeconds(60))).as("a second production order").isFalse();

        assertThat(order.printingStartedAt()).isEqualTo(first);
        assertThat(order.status()).as("no status of its own").isEqualTo(OrderStatus.AWAITING_STOCK);
    }

    @Test
    @DisplayName("BR-11: before printing a cancellation keeps nothing; once printing started, staff may keep a fee")
    void feeOnlyAfterPrinting() {
        Order before = waiting();
        CancellationFee fee = new CancellationFee(new BigDecimal("500000"), "Mass printing ran", "Cups printed");
        assertThatThrownBy(() -> before.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null, fee, SALES, NOW))
                .extracting(OrderAwaitingStockTest::code).isEqualTo(ErrorCode.CANCELLATION_FEE_BEFORE_PRINTING);
        before.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null, SALES, NOW);
        assertThat(before.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(before.pullDomainEvents()).filteredOn(OrderEvent.Cancelled.class::isInstance).singleElement()
                .satisfies(event -> {
                    OrderCancelled cancelled = ((OrderEvent.Cancelled) event).payload();
                    assertThat(cancelled.previousStatus()).isEqualTo("AWAITING_STOCK");
                    assertThat(cancelled.cancelledBy()).isEqualTo(SALES);
                });
        assertThat(before.recordPrintingStarted(NOW)).as("cancelled: settled without it").isFalse();
        assertThat(before.printingStartedAt()).isNull();

        Order printing = waiting();
        printing.recordPrintingStarted(NOW);
        assertThatThrownBy(() -> printing.cancel(CancellationReasonCode.OTHER, "no longer needed", null, null, NOW))
                .as("the system never cancels an order once printing started")
                .extracting(OrderAwaitingStockTest::code).isEqualTo(ErrorCode.CANCELLATION_NEEDS_STAFF);
        printing.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null, fee, SALES, NOW);
        assertThat(printing.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(printing.cancellationFeeAmount()).isEqualByComparingTo("500000");
    }
}
