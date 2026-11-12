package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.order.api.CancellationOutcome;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.service.CancellationRequestService;
import com.stockflow.order.internal.service.CustomerOrders;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.OrderSettlementService;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
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
 * API fulfillment calls (A1) - with the transition trigger, the history rows, closing the remainder of a split order,
 * cancellation requests after a partial shipment, the customer's view of a request, and concurrent transitions under
 * the row lock. Every order is a contract's, confirmed by its installment on signing ({@link ConfirmedOrders}); what
 * the money does after a delivery is the payment module's tests (4B-10 removed the ledger and the billing that went
 * with it).
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class OrderLifecycleIntegrationTest {

    private static final long PRICE = 1_000_000;

    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired com.stockflow.customer.api.CustomerService customers;
    @Autowired CancellationRequestService cancellationRequests;
    @Autowired CustomerOrders customerOrders;
    @Autowired OrderSettlementService settlements;
    @Autowired com.stockflow.invoicing.internal.service.EInvoiceIssuance einvoices;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;

    private UUID customer;
    private String sku;
    private ConfirmedOrders confirmedOrders;
    private UUID staff;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void customerWithItsOwnSku() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        // A contract's lines are only ever the customer's own SKUs, in cartons (14 BR-04, BR-08).
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50).sku();
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

    /** A CONFIRMED order of {@code units} pieces, at least two: one carton of them (14 BR-08), its stock in HCM. */
    private OrderSummary place(int units) {
        CustomerSkuRows.stock(jdbc, tx, sku, units);
        UUID orderId = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.units(sku, units, PRICE)).orderId();
        // Split shipments throughout: Sales allowed them on the order (PACK-PARTIAL, lead's decision 2026-10-21).
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", orderId));
        placed.add(orderId);
        return orders.findById(orderId).orElseThrow();
    }

    private OrderStatus statusOf(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private OrderStatus statusOf(OrderSummary order) {
        return orders.findById(order.orderId()).orElseThrow().status();
    }

    /** Confirmed, released and picking. */
    private OrderSummary picking(int units) {
        OrderSummary order = place(units);
        confirmedOrders.release(order.orderId(), DemoData.WAREHOUSE_HCM, staff);
        return orders.startPicking(order.orderId(), staff);
    }

    private ShipmentHandover handover(OrderSummary order, UUID shipment, int units) {
        com.stockflow.support.ShipmentRows.ready(jdbc, tx, order.orderId(), shipment);
        return new ShipmentHandover(shipment, List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), units)),
                java.time.Instant.now(), staff);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    // ------------------------------------------------------------------------------------------------------ tests

    @Test
    @DisplayName("prepaid, end to end: hold and resolve, split handover, one failed then redelivered, completed, closed; "
            + "the history names who and why")
    void prepaidEndToEnd() {
        // The buyer an e-invoice is issued to: a tax code and the e-mail its invoices go to (21 BR-06).
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customer.customer SET tax_code = ? WHERE id = ?", "03" + (10_000_000
                    + java.util.concurrent.ThreadLocalRandom.current().nextInt(89_999_999)), customer);
            jdbc.update("INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, "
                    + "representative_name, representative_title, invoice_email, version, created_at) "
                    + "VALUES (gen_random_uuid(), ?, '1 Le Loi, TP HCM', 'Tran Van A', 'Director', ?, 0, NOW()) "
                    + "ON CONFLICT (customer_id) DO UPDATE SET invoice_email = EXCLUDED.invoice_email", customer,
                    "ke.toan@" + customer + ".example.com");
        });
        OrderSummary order = picking(4);
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
        assertThat(settlements.completionCandidates(0, 1000)).contains(order.orderId());
        // WF-05 plan 4, condition 5: both deliveries were drawn up as drafts (after commit); until the accountant
        // issues them the order stays DELIVERED.
        com.stockflow.support.Await.until("the drafts of both deliveries", Duration.ofSeconds(20),
                () -> jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice WHERE order_id = ? AND status = "
                        + "'DRAFT'", Long.class, order.orderId()) == 2);
        assertThat(settlements.complete(order.orderId())).as("its deliveries are not invoiced yet").isFalse();
        assertThat(statusOf(order)).isEqualTo(OrderStatus.DELIVERED);
        jdbc.queryForList("SELECT id FROM invoicing.einvoice WHERE order_id = ?", UUID.class, order.orderId())
                .forEach(invoice -> einvoices.issue(invoice, null, staff));
        assertThat(settlements.complete(order.orderId())).isTrue();
        assertThat(statusOf(order)).isEqualTo(OrderStatus.COMPLETED);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET completed_at = NOW() - interval '31 days' "
                + "WHERE id = ?", order.orderId()));
        assertThat(settlements.close(order.orderId())).isTrue();
        assertThat(statusOf(order)).isEqualTo(OrderStatus.CLOSED);

        List<OrderStatusChange> history = orders.history(order.orderId());
        assertThat(history).extracting(OrderStatusChange::to).containsExactly(OrderStatus.DRAFT,
                OrderStatus.PENDING_CONTRACT, OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED, OrderStatus.READY_TO_FULFILL, OrderStatus.PICKING, OrderStatus.ON_HOLD,
                OrderStatus.PICKING, OrderStatus.PARTIALLY_FULFILLED, OrderStatus.PACKED, OrderStatus.SHIPPED,
                OrderStatus.DELIVERY_FAILED, OrderStatus.DELIVERED, OrderStatus.COMPLETED, OrderStatus.CLOSED);
        // The first two entries are the contract's: the order drafted, then waiting for its contract.
        assertThat(history.get(6).reason()).isEqualTo("ADDRESS: sai phường");
        assertThat(history.get(6).actorId()).isEqualTo(staff);
        assertThat(history.get(7).reason()).isEqualTo("HOLD_RESOLVED_ADDRESS: khách đã xác nhận");
        assertThat(history.get(11).reason()).isEqualTo("CUSTOMER_ABSENT");
        assertThat(history.get(14).actorId()).as("closed by the system").isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_hold WHERE order_id = ? AND reason = 'ADDRESS' "
                + "AND note = 'sai phường' AND raised_by = ? AND resolved_at IS NOT NULL", Long.class, order.orderId(), staff))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("4B-10 (17 §4.6 step 21): a contract order completes only once its installments are paid - the settlement "
            + "leaves it DELIVERED while one owes, the receipt lets it complete (it never completed before: its paid "
            + "amount stayed 0)")
    void completesOnlyOncePaid() {
        // Everything after delivery, on the customer's credit: a limit above zero (15 BR-12).
        customers.saveCreditTerms(new com.stockflow.customer.api.SaveCreditTermsCommand(customer,
                new java.math.BigDecimal("50000000"), 30, "test terms", staff, null));
        CustomerSkuRows.stock(jdbc, tx, sku, 2);
        UUID orderId = confirmedOrders.signed(customer, List.of(new com.stockflow.order.internal.service.SalesContracts
                .Installment("After delivery", "AFTER_DELIVERY", null, new java.math.BigDecimal("100"), 30, null)),
                ConfirmedOrders.Line.units(sku, 2, PRICE)).orderId();
        placed.add(orderId);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        confirmedOrders.release(orderId, DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(orderId, staff);
        orders.markPacked(orderId, staff);
        orders.markShipped(orderId, null, staff);
        assertThat(orders.recordDelivery(orderId, staff).status()).isEqualTo(OrderStatus.DELIVERED);
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET delivered_at = NOW() - interval '8 days' WHERE id = ?", orderId));

        assertThat(settlements.completionCandidates(0, 1000)).as("the query cannot tell who owes (payment's to say)")
                .contains(orderId);
        assertThat(settlements.complete(orderId)).as("the installment after delivery is unpaid").isFalse();
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.DELIVERED);

        // Reached by the delivery (payment reads OrderDelivered after commit); the customer pays it.
        UUID installment = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ?", UUID.class,
                orderId);
        com.stockflow.support.Await.until("the installment reached", Duration.ofSeconds(15), () -> "DUE".equals(
                jdbc.queryForObject("SELECT status FROM payment.installment WHERE id = ?", String.class, installment)));
        confirmedOrders.pay(customer, installment, jdbc.queryForObject(
                "SELECT amount FROM payment.installment WHERE id = ?", java.math.BigDecimal.class, installment));

        assertThat(settlements.complete(orderId)).as("paid in full").isTrue();
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("split: one shipment delivered, the other failed and returned - partial; the rest closed - DELIVERED")
    void oneDeliveredOneReturned() {
        OrderSummary order = picking(3);
        orders.markPacked(order.orderId(), staff);
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, s1, 2));
        orders.recordHandover(order.orderId(), handover(order, s2, 1));
        orders.recordShipmentDelivered(order.orderId(), s1, staff);
        orders.recordShipmentDeliveryFailed(order.orderId(), s2, DeliveryFailureReason.DAMAGED, "vỡ thùng", staff);
        assertThat(orders.recordShipmentReturned(order.orderId(), s2, staff).status())
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        OrderSummary closed = orders.closeRemainder(order.orderId(), CancellationReasonCode.OTHER,
                "khách không lấy lại", staff);
        assertThat(closed.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(closed.lifecycle().effectiveTotal()).isEqualByComparingTo("2000000");
    }

    @Test
    @DisplayName("a cancellation request made while picking closes as EXPIRED once part is handed over (E2E QA "
            + "2026-10-21: it stayed pending for good); the customer sees the request and why it closed")
    void cancellationRequestAfterPartialShipment() {
        OrderSummary order = picking(2);
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
        assertThat(customerOrders.myOrder(customer, order.orderId()).cancellationRequest()).satisfies(r -> {
            assertThat(r.status()).isEqualTo("EXPIRED");
            assertThat(r.decisionNote()).isEqualTo("Closed: the order is PARTIALLY_FULFILLED and can no longer be "
                    + "cancelled; ask for a return instead.");
            assertThat(r.decidedAt()).isNotNull();
        });
        assertThat(code(catchThrowable(() -> cancellationRequests.approve(order.orderId(), asked.requestId(), null,
                "ok", staff)))).isEqualTo(ErrorCode.ORDER_CANCELLATION_REQUEST_NOT_PENDING);
        assertThat(code(catchThrowable(() -> cancellationRequests.reject(order.orderId(), asked.requestId(),
                "Một phần đã giao cho hãng", staff)))).isEqualTo(ErrorCode.ORDER_CANCELLATION_REQUEST_NOT_PENDING);
        assertThat(code(catchThrowable(() -> orders.cancelOwn(order.orderId(), customer,
                CancellationReasonCode.CUSTOMER_REQUEST, null, staff)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(customerOrders.myOrder(customer, order.orderId()).allowedActions())
                .isEqualTo(new CustomerOrders.AllowedActions(false, false));
    }

    @Test
    @DisplayName("hold while partly shipped: deliveries are still recorded; handover waits; resolved, partial")
    void holdWhilePartlyShipped() {
        OrderSummary order = picking(3);
        UUID s1 = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, s1, 1));
        assertThat(orders.putOnHold(order.orderId(), HoldReason.INVENTORY, "thiếu 2 bộ", staff).lifecycle()
                .statusBeforeHold()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(orders.recordShipmentDelivered(order.orderId(), s1, staff).status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(code(catchThrowable(() -> orders.recordHandover(order.orderId(), handover(order, UUID.randomUUID(), 2)))))
                .isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        assertThat(code(catchThrowable(() -> orders.closeRemainder(order.orderId(), CancellationReasonCode.OUT_OF_STOCK,
                null, staff)))).isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        assertThat(orders.resolveHold(order.orderId(), "đủ hàng", staff).status())
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
    }

    @Test
    @DisplayName("whole order without shipments: shipped, failed, delivered over the old endpoint path")
    void wholeOrderWithoutShipments() {
        OrderSummary order = picking(2);
        orders.markPacked(order.orderId(), staff);
        orders.markShipped(order.orderId(), null, staff);
        assertThat(code(catchThrowable(() -> orders.putOnHold(order.orderId(), HoldReason.ADDRESS, null, staff))))
                .as("after handover a hold is no help: the carrier has it").isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
        assertThat(orders.recordDeliveryFailure(order.orderId(), DeliveryFailureReason.WRONG_ADDRESS, null, staff)
                .lifecycle().deliveryFailureReason()).isEqualTo(DeliveryFailureReason.WRONG_ADDRESS);
        assertThat(orders.recordDelivery(order.orderId(), staff).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(orders.recordDelivery(order.orderId(), staff).status()).as("idempotent").isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("the database refuses a jump the state machine does not have, and holds the same edges")
    void triggerRefusesJumps() {
        OrderSummary order = place(2);
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
        OrderSummary holdRace = picking(2);
        List<Object> holds = race(() -> orders.putOnHold(holdRace.orderId(), HoldReason.FRAUD, null, staff),
                () -> orders.putOnHold(holdRace.orderId(), HoldReason.PAYMENT, null, staff));
        assertThat(holds).filteredOn(OrderSummary.class::isInstance).hasSize(1);
        assertThat(holds).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(code((Throwable) e)).isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION));

        OrderSummary packRace = picking(2);
        List<Object> packOrCancel = race(() -> orders.markPacked(packRace.orderId(), staff),
                () -> WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(packRace.orderId(),
                        new com.stockflow.order.api.CancelOrderCommand(CancellationReasonCode.OUT_OF_STOCK, null, null,
                                staff))));
        assertThat(packOrCancel).filteredOn(OrderSummary.class::isInstance).hasSize(1);
        assertThat(packOrCancel).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(code((Throwable) e)).isIn(ErrorCode.ORDER_CANCELLED,
                        ErrorCode.ORDER_NOT_CANCELLABLE));
        assertThat(statusOf(packRace)).isIn(OrderStatus.PACKED, OrderStatus.CANCELLED);

        OrderSummary handoverRace = picking(2);
        UUID shipment = UUID.randomUUID();
        List<Object> handovers = race(() -> orders.recordHandover(handoverRace.orderId(), handover(handoverRace, shipment, 1)),
                () -> orders.recordHandover(handoverRace.orderId(), handover(handoverRace, shipment, 1)));
        assertThat(handovers).allMatch(OrderSummary.class::isInstance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_shipment WHERE order_id = ?", Long.class,
                handoverRace.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("two closures of one order: the first leaves it SHIPPED, the second, after a shipment came back, DELIVERED")
    void secondClosureIsNotLost() {
        OrderSummary order = picking(4);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, a, 1));
        orders.recordHandover(order.orderId(), handover(order, b, 1));
        orders.recordShipmentDelivered(order.orderId(), a, staff);
        assertThat(orders.closeRemainder(order.orderId(), CancellationReasonCode.OUT_OF_STOCK, null, staff).status())
                .isEqualTo(OrderStatus.SHIPPED);
        orders.recordShipmentDeliveryFailed(order.orderId(), b, DeliveryFailureReason.LOST, null, staff);
        assertThat(orders.recordShipmentReturned(order.orderId(), b, staff).status())
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(orders.closeRemainder(order.orderId(), CancellationReasonCode.OTHER, "hàng thất lạc", staff)
                .status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(jdbc.queryForObject("SELECT cancelled_quantity FROM ordering.order_line WHERE order_id = ?",
                Integer.class, order.orderId())).as("two pieces closed by the first closure, the one that came back by the second").isEqualTo(3);
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
