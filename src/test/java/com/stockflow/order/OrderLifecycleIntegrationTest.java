package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.CancellationOutcome;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.service.CancellationRequestService;
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.order.internal.service.CustomerOrders;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.OrderSettlementService;
import com.stockflow.payment.internal.service.ReceivableService;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * G3-2 (SCRUM-239/246/247) against a real database: the whole lifecycle through {@code OrderService} - the in-process
 * API fulfillment calls (A1) - with the transition trigger, the history rows, receivables per shipment, the credit
 * exposure of split orders, the remainder refund, cancellation requests after a partial shipment, the customer's view
 * of a request, and concurrent transitions under the row lock.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class OrderLifecycleIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final long PRICE = 1_000_000;
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired CustomerService customers;
    @Autowired CreditHoldService credit;
    @Autowired CancellationRequestService cancellationRequests;
    @Autowired CustomerOrders customerOrders;
    @Autowired ReceivableService receivables;
    @Autowired OrderSettlementService settlements;
    @Autowired com.stockflow.payment.internal.domain.CancelledOrderSettlement remainderRefunds;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private UUID customer;
    private UUID staff;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void customerWithEveryTerm() {
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, true, true, CommercialTerm.CREDIT,
                new BigDecimal("30"), new BigDecimal("10000000"), 30, "test terms", staff, null));
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderStatus status = orders.findById(id).orElseThrow().status();
            Long packed = jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE id = ? AND packed_at "
                    + "IS NOT NULL", Long.class, id);
            if (status.canTransitionTo(OrderStatus.CANCELLED) && packed == 0) {
                orders.cancel(id, "TEST_CLEANUP");
            } else {
                jdbc.queryForList("SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'",
                        UUID.class, id).forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE"));
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private OrderSummary place(PaymentTerm term, int units) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, units, Money.vnd(PRICE), null)), term,
                term == PaymentTerm.DEPOSIT ? new BigDecimal("30") : null));
        placed.add(order.orderId());
        return order;
    }

    private void pay(OrderSummary order, long amount) {
        tx.executeWithoutResult(s -> publisher.publishEvent(new PaymentCaptured(order.orderId(), UUID.randomUUID(),
                BigDecimal.valueOf(amount), "VND", PaymentMethod.BANK_TRANSFER)));
    }

    private OrderStatus statusOf(OrderSummary order) {
        return orders.findById(order.orderId()).orElseThrow().status();
    }

    /** Placed, paid as its terms want, released and picking. */
    private OrderSummary picking(PaymentTerm term, int units) {
        OrderSummary order = place(term, units);
        if (term != PaymentTerm.CREDIT) {
            pay(order, term == PaymentTerm.PREPAID ? units * PRICE : units * PRICE * 30 / 100);
            Await.until("confirmed", ASYNC, () -> statusOf(order) == OrderStatus.CONFIRMED);
        }
        releases.release(order.orderId(), DemoData.WAREHOUSE_HCM, staff);
        return orders.startPicking(order.orderId(), staff);
    }

    private ShipmentHandover handover(OrderSummary order, UUID shipment, int units) {
        return new ShipmentHandover(shipment, List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), units)),
                java.time.Instant.now(), staff);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private List<String> receivablesOf(OrderSummary order) {
        return jdbc.queryForList("""
                SELECT amount::bigint || ' ' || (shipment_id IS NOT NULL) FROM payment.receivable
                 WHERE order_id = ? ORDER BY issued_at, amount""", String.class, order.orderId());
    }

    private BigDecimal exposure() {
        return credit.position(customer).exposure();
    }

    // ------------------------------------------------------------------------------------------------------ tests

    @Test
    @DisplayName("prepaid, end to end: hold and resolve, split handover, one failed then redelivered, completed, closed; "
            + "the history names who and why")
    void prepaidEndToEnd() {
        OrderSummary order = picking(PaymentTerm.PREPAID, 4);
        assertThat(order.status()).isEqualTo(OrderStatus.PICKING);

        assertThat(orders.putOnHold(order.orderId(), HoldReason.ADDRESS, "sai phường", staff).status())
                .isEqualTo(OrderStatus.ON_HOLD);
        assertThat(code(catchThrowable(() -> orders.putOnHold(order.orderId(), HoldReason.FRAUD, null, staff))))
                .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        assertThat(code(catchThrowable(() -> orders.markPacked(order.orderId(), staff))))
                .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        assertThat(orders.resolveHold(order.orderId(), "khách đã xác nhận", staff).status()).isEqualTo(OrderStatus.PICKING);
        assertThat(code(catchThrowable(() -> orders.resolveHold(order.orderId(), "again", staff))))
                .isEqualTo(ErrorCode.ORDER_NOT_ON_HOLD);

        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        assertThat(orders.recordHandover(order.orderId(), handover(order, s1, 1)).status())
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(orders.recordHandover(order.orderId(), handover(order, s1, 1)).status())
                .as("the same shipment again").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(orders.markPacked(order.orderId(), staff).status()).isEqualTo(OrderStatus.PACKED);
        OrderSummary shipped = orders.recordHandover(order.orderId(), handover(order, s2, 3));
        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(shipped.lifecycle().shipments()).hasSize(2);
        assertThat(code(catchThrowable(() -> orders.recordDelivery(order.orderId(), staff))))
                .as("G4-Q5: an order with shipments is delivered per shipment").isEqualTo(ErrorCode.ORDER_DELIVERY_VIA_SHIPMENT);

        orders.recordShipmentDelivered(order.orderId(), s1, staff);
        assertThat(orders.recordShipmentDeliveryFailed(order.orderId(), s2, DeliveryFailureReason.CUSTOMER_ABSENT, null,
                staff).status()).isEqualTo(OrderStatus.DELIVERY_FAILED);
        OrderSummary delivered = orders.recordShipmentDelivered(order.orderId(), s2, staff);
        assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(delivered.lifecycle().lines()).singleElement().satisfies(line -> {
            assertThat(line.shippedQuantity()).isEqualTo(4);
            assertThat(line.deliveredQuantity()).isEqualTo(4);
        });

        assertThat(settlements.complete(order.orderId())).as("inside the return window").isFalse();
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET delivered_at = NOW() - interval '8 days' "
                + "WHERE id = ?", order.orderId()));
        assertThat(settlements.completionCandidates(1000)).contains(order.orderId());
        assertThat(settlements.complete(order.orderId())).isTrue();
        assertThat(statusOf(order)).isEqualTo(OrderStatus.COMPLETED);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET completed_at = NOW() - interval '31 days' "
                + "WHERE id = ?", order.orderId()));
        assertThat(settlements.close(order.orderId())).isTrue();
        assertThat(statusOf(order)).isEqualTo(OrderStatus.CLOSED);

        List<OrderStatusChange> history = orders.history(order.orderId());
        assertThat(history).extracting(OrderStatusChange::to).containsExactly(OrderStatus.PENDING_PAYMENT,
                OrderStatus.CONFIRMED, OrderStatus.READY_TO_FULFILL, OrderStatus.PICKING, OrderStatus.ON_HOLD,
                OrderStatus.PICKING, OrderStatus.PARTIALLY_FULFILLED, OrderStatus.PACKED, OrderStatus.SHIPPED,
                OrderStatus.DELIVERY_FAILED, OrderStatus.DELIVERED, OrderStatus.COMPLETED, OrderStatus.CLOSED);
        assertThat(history.get(4).reason()).isEqualTo("ADDRESS: sai phường");
        assertThat(history.get(4).actorId()).isEqualTo(staff);
        assertThat(history.get(5).reason()).isEqualTo("HOLD_RESOLVED_ADDRESS: khách đã xác nhận");
        assertThat(history.get(9).reason()).isEqualTo("CUSTOMER_ABSENT");
        assertThat(history.get(12).actorId()).as("closed by the system").isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_hold WHERE order_id = ? AND reason = 'ADDRESS' "
                + "AND note = 'sai phường' AND raised_by = ? AND resolved_at IS NOT NULL", Long.class, order.orderId(), staff))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("credit, split: one receivable per delivered shipment; the exposure counts the order once and falls "
            + "with every payment; closing the rest frees the limit")
    void creditSplitReceivablesAndExposure() {
        BigDecimal before = exposure();
        OrderSummary order = picking(PaymentTerm.CREDIT, 4);
        assertThat(exposure().subtract(before)).isEqualByComparingTo("4000000");

        UUID s1 = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, s1, 2));
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(exposure().subtract(before)).isEqualByComparingTo("4000000");

        orders.recordShipmentDelivered(order.orderId(), s1, staff);
        Await.until("receivable of the first shipment", ASYNC, () -> receivablesOf(order).size() == 1);
        assertThat(receivablesOf(order)).containsExactly("2000000 true");
        assertThat(exposure().subtract(before)).as("2M billed + 2M still to bill, not 6M").isEqualByComparingTo("4000000");

        receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer,
                "FT-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("2000000"), "VND",
                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")), List.of(), null, staff));
        Await.until("the order learnt of the payment", ASYNC, () -> orders.findById(order.orderId()).orElseThrow()
                .payment().paidAmount().compareTo(new BigDecimal("2000000")) == 0);
        assertThat(exposure().subtract(before)).isEqualByComparingTo("2000000");

        OrderSummary closed = orders.closeRemainder(order.orderId(), CancellationReasonCode.OUT_OF_STOCK, null, staff);
        assertThat(closed.status()).as("every shipment delivered, the rest closed").isEqualTo(OrderStatus.DELIVERED);
        assertThat(closed.lifecycle().effectiveTotal()).isEqualByComparingTo("2000000");
        assertThat(closed.lifecycle().amountDue()).isZero();
        assertThat(exposure().subtract(before)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'",
                Long.class, order.orderId())).as("the closed units' holds were released").isZero();
    }

    @Test
    @DisplayName("credit, split: one shipment delivered, the other failed and returned - partial, only the delivered "
            + "part billed; the rest closed - DELIVERED")
    void creditOneDeliveredOneReturned() {
        OrderSummary order = picking(PaymentTerm.CREDIT, 3);
        orders.markPacked(order.orderId(), staff);
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, s1, 2));
        orders.recordHandover(order.orderId(), handover(order, s2, 1));
        orders.recordShipmentDelivered(order.orderId(), s1, staff);
        orders.recordShipmentDeliveryFailed(order.orderId(), s2, DeliveryFailureReason.DAMAGED, "vỡ thùng", staff);
        assertThat(orders.recordShipmentReturned(order.orderId(), s2, staff).status())
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        Await.until("receivable", ASYNC, () -> !receivablesOf(order).isEmpty());
        assertThat(receivablesOf(order)).containsExactly("2000000 true");
        assertThat(orders.closeRemainder(order.orderId(), CancellationReasonCode.OTHER, "khách không lấy lại", staff)
                .status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("deposit + partial shipment: nothing billed, the rest closed: what was received above the goods is "
            + "the refund asked")
    void depositPartialShipment() {
        OrderSummary order = picking(PaymentTerm.DEPOSIT, 4);   // 1.2M deposit of 4M
        UUID s1 = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, s1, 1));
        orders.recordShipmentDelivered(order.orderId(), s1, staff);
        OrderSummary closed = orders.closeRemainder(order.orderId(), CancellationReasonCode.CUSTOMER_REQUEST, null, staff);
        assertThat(closed.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(closed.lifecycle().effectiveTotal()).isEqualByComparingTo("1000000");
        assertThat(jdbc.queryForObject("SELECT remainder_refund_amount::bigint FROM ordering.customer_order WHERE id = ?",
                Long.class, order.orderId())).isEqualTo(200_000L);
        assertThat(receivablesOf(order)).isEmpty();
    }

    @Test
    @DisplayName("a cancellation request made while picking cannot be approved after part was handed over; "
            + "the customer sees the request and its rejection")
    void cancellationRequestAfterPartialShipment() {
        OrderSummary order = picking(PaymentTerm.PREPAID, 2);
        CancellationOutcome asked = orders.cancelOwn(order.orderId(), customer, CancellationReasonCode.CUSTOMER_REQUEST,
                "đổi ý", staff);
        assertThat(asked.result()).isEqualTo(CancellationOutcome.Result.REQUESTED);
        CustomerOrders.Detail pending = customerOrders.myOrder(customer, order.orderId());
        assertThat(pending.cancellationRequest()).satisfies(r -> {
            assertThat(r.status()).isEqualTo("PENDING");
            assertThat(r.reasonCode()).isEqualTo(CancellationReasonCode.CUSTOMER_REQUEST);
            assertThat(r.note()).isEqualTo("đổi ý");
            assertThat(r.decidedAt()).isNull();
        });
        assertThat(pending.allowedActions().requestCancellation()).as("one request at a time").isFalse();
        assertThat(customerOrders.myOrders(customer, new CustomerOrders.Query(0, 50, List.of(), null, null, null, null))
                .items()).filteredOn(card -> card.orderId().equals(order.orderId()))
                .singleElement().satisfies(card -> assertThat(card.cancellationRequestStatus()).isEqualTo("PENDING"));

        orders.recordHandover(order.orderId(), handover(order, UUID.randomUUID(), 1));
        assertThat(code(catchThrowable(() -> cancellationRequests.approve(order.orderId(), asked.requestId(), null,
                "ok", staff)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        cancellationRequests.reject(order.orderId(), asked.requestId(), "Một phần đã giao cho hãng", staff);
        assertThat(customerOrders.myOrder(customer, order.orderId()).cancellationRequest()).satisfies(r -> {
            assertThat(r.status()).isEqualTo("REJECTED");
            assertThat(r.decisionNote()).isEqualTo("Một phần đã giao cho hãng");
            assertThat(r.decidedAt()).isNotNull();
        });
        assertThat(code(catchThrowable(() -> orders.cancelOwn(order.orderId(), customer,
                CancellationReasonCode.CUSTOMER_REQUEST, null, staff)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(customerOrders.myOrder(customer, order.orderId()).allowedActions())
                .isEqualTo(new CustomerOrders.AllowedActions(false, false, false));
    }

    @Test
    @DisplayName("hold while partly shipped: deliveries are still recorded and billed; handover waits; resolved, partial")
    void holdWhilePartlyShipped() {
        OrderSummary order = picking(PaymentTerm.CREDIT, 3);
        UUID s1 = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, s1, 1));
        assertThat(orders.putOnHold(order.orderId(), HoldReason.INVENTORY, "thiếu 2 bộ", staff).lifecycle()
                .statusBeforeHold()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(orders.recordShipmentDelivered(order.orderId(), s1, staff).status()).isEqualTo(OrderStatus.ON_HOLD);
        Await.until("receivable while held", ASYNC, () -> !receivablesOf(order).isEmpty());
        assertThat(code(catchThrowable(() -> orders.recordHandover(order.orderId(), handover(order, UUID.randomUUID(), 2)))))
                .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        assertThat(code(catchThrowable(() -> orders.closeRemainder(order.orderId(), CancellationReasonCode.OUT_OF_STOCK,
                null, staff)))).isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        assertThat(orders.resolveHold(order.orderId(), "đủ hàng", staff).status())
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
    }

    @Test
    @DisplayName("whole order without shipments: shipped, failed, delivered over the old endpoint path; credit receivable")
    void wholeOrderWithoutShipments() {
        OrderSummary order = picking(PaymentTerm.CREDIT, 1);
        orders.markPacked(order.orderId(), staff);
        orders.markShipped(order.orderId(), null, staff);
        assertThat(code(catchThrowable(() -> orders.putOnHold(order.orderId(), HoldReason.ADDRESS, null, staff))))
                .as("after handover a hold is no help: the carrier has it").isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        assertThat(orders.recordDeliveryFailure(order.orderId(), DeliveryFailureReason.WRONG_ADDRESS, null, staff)
                .lifecycle().deliveryFailureReason()).isEqualTo(DeliveryFailureReason.WRONG_ADDRESS);
        assertThat(orders.recordDelivery(order.orderId(), staff).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(orders.recordDelivery(order.orderId(), staff).status()).as("idempotent").isEqualTo(OrderStatus.DELIVERED);
        Await.until("receivable", ASYNC, () -> !receivablesOf(order).isEmpty());
        assertThat(receivablesOf(order)).containsExactly("1000000 false");
    }

    @Test
    @DisplayName("the database refuses a jump the state machine does not have, and holds the same edges")
    void triggerRefusesJumps() {
        OrderSummary order = place(PaymentTerm.CREDIT, 1);
        Throwable jump = catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET status = 'SHIPPED', shipped_at = NOW() WHERE id = ?", order.orderId())));
        assertThat(jump).isInstanceOf(DataAccessException.class).hasMessageContaining("cannot move from CONFIRMED to SHIPPED");
        assertThat(jdbc.queryForList("SELECT from_status || '>' || to_status FROM ordering.order_status_transition",
                String.class)).containsExactlyInAnyOrderElementsOf(OrderStatus.edges());
    }

    @Test
    @DisplayName("concurrent transitions are serialised by the row lock: two holds, one wins; pack against cancel, "
            + "one wins; the same handover twice, one shipment")
    void concurrentTransitions() throws Exception {
        OrderSummary holdRace = picking(PaymentTerm.PREPAID, 1);
        List<Object> holds = race(() -> orders.putOnHold(holdRace.orderId(), HoldReason.FRAUD, null, staff),
                () -> orders.putOnHold(holdRace.orderId(), HoldReason.PAYMENT, null, staff));
        assertThat(holds).filteredOn(OrderSummary.class::isInstance).hasSize(1);
        assertThat(holds).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(code((Throwable) e)).isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION));

        OrderSummary packRace = picking(PaymentTerm.PREPAID, 1);
        List<Object> packOrCancel = race(() -> orders.markPacked(packRace.orderId(), staff),
                () -> WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(packRace.orderId(),
                        new com.stockflow.order.api.CancelOrderCommand(CancellationReasonCode.OUT_OF_STOCK, null, null,
                                staff))));
        assertThat(packOrCancel).filteredOn(OrderSummary.class::isInstance).hasSize(1);
        assertThat(packOrCancel).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(code((Throwable) e)).isIn(ErrorCode.ORDER_CANCELLED,
                        ErrorCode.ORDER_NOT_CANCELLABLE));
        assertThat(statusOf(packRace)).isIn(OrderStatus.PACKED, OrderStatus.CANCELLED);

        OrderSummary handoverRace = picking(PaymentTerm.PREPAID, 2);
        UUID shipment = UUID.randomUUID();
        List<Object> handovers = race(() -> orders.recordHandover(handoverRace.orderId(), handover(handoverRace, shipment, 1)),
                () -> orders.recordHandover(handoverRace.orderId(), handover(handoverRace, shipment, 1)));
        assertThat(handovers).allMatch(OrderSummary.class::isInstance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_shipment WHERE order_id = ?", Long.class,
                handoverRace.orderId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- review of G3-2 (money, holds)

    /** Money received outside any receivable, as a captured {@code payment.payment} the refund process can draw on. */
    private void capturedPayment(OrderSummary order, long amount) {
        UUID payment = UUID.randomUUID();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.payment (id, order_id, amount, currency, method, status, captured_at, created_at)
                VALUES (?, ?, ?, 'VND', 'BANK_TRANSFER', 'CAPTURED', NOW(), NOW())""", payment, order.orderId(), amount));
        tx.executeWithoutResult(s -> publisher.publishEvent(new PaymentCaptured(order.orderId(), payment,
                BigDecimal.valueOf(amount), "VND", PaymentMethod.BANK_TRANSFER)));
    }

    private List<String> refundsOf(OrderSummary order) {
        return jdbc.queryForList("""
                SELECT amount::bigint || ' ' || source FROM payment.refund WHERE order_id = ? ORDER BY created_at, amount""",
                String.class, order.orderId());
    }

    @Test
    @DisplayName("credit, paid ahead 3M: shipment A (2M) delivered bills nothing, B comes back, the rest is closed: "
            + "no receivable, 1M refunded - the customer pays 2M for 2M")
    void creditPaidAheadNeverOverBills() {
        OrderSummary order = picking(PaymentTerm.CREDIT, 4);
        capturedPayment(order, 3 * PRICE);
        Await.until("paid ahead counted", ASYNC, () -> orders.findById(order.orderId()).orElseThrow().payment()
                .paidAmount().compareTo(BigDecimal.valueOf(3 * PRICE)) == 0);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, a, 2));
        orders.markPacked(order.orderId(), staff);
        orders.recordHandover(order.orderId(), handover(order, b, 2));
        assertThat(orders.recordShipmentDelivered(order.orderId(), a, staff).lifecycle().billedAmount()).isZero();
        orders.recordShipmentDeliveryFailed(order.orderId(), b, DeliveryFailureReason.REFUSED, null, staff);
        orders.recordShipmentReturned(order.orderId(), b, staff);
        OrderSummary closed = orders.closeRemainder(order.orderId(), CancellationReasonCode.CUSTOMER_REQUEST, null, staff);
        assertThat(closed.status()).isEqualTo(OrderStatus.DELIVERED);
        Await.until("refund asked", ASYNC, () -> !refundsOf(order).isEmpty());
        assertThat(refundsOf(order)).containsExactly("1000000 ORDER_REMAINDER_CLOSED");
        assertThat(receivablesOf(order)).isEmpty();
    }

    @Test
    @DisplayName("deposit 30%: A delivered, B back, the rest closed: the balance of A is due, nothing refunded")
    void depositDeliveredAndReturned() {
        OrderSummary order = picking(PaymentTerm.DEPOSIT, 4);       // 1.2M received
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, a, 2));
        orders.markPacked(order.orderId(), staff);
        orders.recordHandover(order.orderId(), handover(order, b, 2));
        orders.recordShipmentDelivered(order.orderId(), a, staff);
        orders.recordShipmentDeliveryFailed(order.orderId(), b, DeliveryFailureReason.REFUSED, null, staff);
        orders.recordShipmentReturned(order.orderId(), b, staff);
        OrderSummary closed = orders.closeRemainder(order.orderId(), CancellationReasonCode.CUSTOMER_REQUEST, null, staff);
        assertThat(closed.lifecycle().amountDue()).isEqualByComparingTo("800000");
        assertThat(jdbc.queryForObject("SELECT remainder_refund_amount::bigint FROM ordering.customer_order WHERE id = ?",
                Long.class, order.orderId())).isZero();
        assertThat(receivablesOf(order)).isEmpty();
    }

    @Test
    @DisplayName("two closures of one order each ask their refund; a redelivered closure asks nothing again")
    void secondClosureIsNotLost() {
        OrderSummary order = place(PaymentTerm.PREPAID, 4);
        capturedPayment(order, 4 * PRICE);
        Await.until("confirmed", ASYNC, () -> statusOf(order) == OrderStatus.CONFIRMED);
        releases.release(order.orderId(), DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(order.orderId(), staff);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, a, 1));
        orders.recordHandover(order.orderId(), handover(order, b, 1));
        orders.recordShipmentDelivered(order.orderId(), a, staff);
        assertThat(orders.closeRemainder(order.orderId(), CancellationReasonCode.OUT_OF_STOCK, null, staff).status())
                .isEqualTo(OrderStatus.SHIPPED);
        Await.until("first refund", ASYNC, () -> refundsOf(order).size() == 1);
        orders.recordShipmentDeliveryFailed(order.orderId(), b, DeliveryFailureReason.LOST, null, staff);
        assertThat(orders.recordShipmentReturned(order.orderId(), b, staff).status())
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(orders.closeRemainder(order.orderId(), CancellationReasonCode.OTHER, "hàng thất lạc", staff)
                .status()).isEqualTo(OrderStatus.DELIVERED);
        Await.until("second refund", ASYNC, () -> refundsOf(order).size() == 2);
        assertThat(refundsOf(order)).containsExactlyInAnyOrder("2000000 ORDER_REMAINDER_CLOSED",
                "1000000 ORDER_REMAINDER_CLOSED");
        UUID firstClosure = jdbc.queryForObject("SELECT remainder_closure_id FROM payment.refund WHERE order_id = ? "
                + "AND amount = 2000000", UUID.class, order.orderId());
        BigDecimal unplaced = tx.execute(s -> remainderRefunds.requestRemainderRefunds(order.orderId(), firstClosure,
                new BigDecimal("2000000"), "VND", "redelivered"));
        assertThat(unplaced).isZero();
        assertThat(refundsOf(order)).hasSize(2);
    }

    @Test
    @DisplayName("a design hold is QC's: the coordinator cannot resolve it, and a mismatch on an order held for "
            + "another reason is refused (the packing completion rolls back)")
    void designHoldBelongsToQc() {
        OrderSummary order = picking(PaymentTerm.PREPAID, 1);
        orders.putOnDesignHold(order.orderId(), "DESIGN_MISMATCH");
        assertThat(code(catchThrowable(() -> orders.resolveHold(order.orderId(), "ok", staff))))
                .isEqualTo(ErrorCode.ORDER_DESIGN_HOLD_USE_REVERIFICATION);
        orders.resolveDesignHold(order.orderId(), staff, "QC: khớp checksum");
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PICKING);
        orders.resolveDesignHold(order.orderId(), staff, "QC again");   // not held: nothing to do, no error
        orders.putOnHold(order.orderId(), HoldReason.ADDRESS, "sai phường", staff);
        assertThat(code(catchThrowable(() -> orders.putOnDesignHold(order.orderId(), "DESIGN_MISMATCH"))))
                .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        assertThat(orders.findById(order.orderId()).orElseThrow().lifecycle().holdReason()).isEqualTo(HoldReason.ADDRESS);
    }

    /** Both calls released at once, each in its own transaction; what each returned or threw. */
    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> results = new ArrayList<>();
            for (Callable<Object> call : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return tx.execute(s -> {
                            try {
                                return call.call();
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
                    } catch (BusinessException refused) {
                        return refused;
                    }
                }));
            }
            start.countDown();
            List<Object> out = new ArrayList<>();
            for (Future<Object> result : results) {
                out.add(result.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
