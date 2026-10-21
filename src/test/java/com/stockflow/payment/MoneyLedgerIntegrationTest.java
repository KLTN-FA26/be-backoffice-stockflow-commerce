package com.stockflow.payment;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.DepositBalancePolicy;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.internal.service.MoneyLedgerService;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * G2-3 (SCRUM-218) against a real database: one ledger of incoming money (decision G3-Q3) pays prepaid and deposit
 * orders as well as receivables, the handover waits for the balance (G3-Q4), and the carrier's remittance pays what it
 * collected on delivery. kltn-docs 15 BR-02, BR-04..BR-09; 09 §4.3 step 4, BR-04.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class MoneyLedgerIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final long PRICE = 5_000_000;
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired CustomerService customers;
    @Autowired ReceivableService receivables;
    @Autowired MoneyLedgerService ledger;
    @Autowired PaymentService payments;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired com.stockflow.payment.internal.domain.CancelledOrderSettlement settlement;
    @Autowired org.springframework.context.ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private UUID customer;
    private UUID accountant;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void customerWithEveryTerm() {
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        terms(customer, DepositBalancePolicy.BEFORE_DELIVERY);
    }

    private void terms(UUID who, DepositBalancePolicy policy) {
        Long version = jdbc.queryForObject("SELECT COALESCE(MAX(version), -1) FROM customer.credit_profile "
                + "WHERE customer_id = ?", Long.class, who);
        customers.saveCreditTerms(new SaveCreditTermsCommand(who, true, true, true, CommercialTerm.PREPAID,
                new BigDecimal("30"), new BigDecimal("100000000"), 30, "test terms", accountant,
                version == null || version < 0 ? null : version, policy));
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderStatus status = orders.findById(id).orElseThrow().status();
            Long packed = jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE id = ? AND packed_at "
                    + "IS NOT NULL", Long.class, id);
            if (status.canTransitionTo(OrderStatus.CANCELLED) && packed == 0) {
                orders.cancel(id, new CancelOrderCommand(CancellationReasonCode.OTHER, "test cleanup", null, null));
            } else {
                jdbc.queryForList("SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'",
                        UUID.class, id).forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE"));
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private OrderSummary place(UUID who, PaymentTerm term, int units, BalancePolicy policy) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), who, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, units, Money.vnd(PRICE), null)), term, null, policy));
        placed.add(order.orderId());
        return order;
    }

    private OrderSummary place(PaymentTerm term, int units) {
        return place(customer, term, units, null);
    }

    private OrderSummary placeAt(PaymentTerm term, long price, BigDecimal depositPercent) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, 1, Money.vnd(price), null)), term, depositPercent, null));
        placed.add(order.orderId());
        return order;
    }

    private ReceivableService.TransferResult transfer(String reference, long amount, List<ReceivableService.OrderPayment>
            toOrders) {
        return receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer, reference,
                BigDecimal.valueOf(amount), "VND", TODAY, null, "statement line", accountant, toOrders));
    }

    private static ReceivableService.OrderPayment pay(OrderSummary order, long amount) {
        return new ReceivableService.OrderPayment(order.orderId(), BigDecimal.valueOf(amount));
    }

    private static String ref() {
        return "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private OrderSummary reload(OrderSummary order) {
        return orders.findById(order.orderId()).orElseThrow();
    }

    private void awaitStatus(OrderSummary order, OrderStatus status) {
        Await.until(order.orderNumber() + " " + status, ASYNC, () -> reload(order).status() == status);
    }

    private void awaitPaid(OrderSummary order, long amount) {
        Await.until(order.orderNumber() + " paid " + amount, ASYNC, () ->
                reload(order).payment().paidAmount().compareTo(BigDecimal.valueOf(amount)) == 0);
    }

    private OrderSummary packedAfterRelease(OrderSummary order) {
        releases.release(order.orderId(), DemoData.WAREHOUSE_HCM, accountant);
        orders.startPicking(order.orderId(), accountant);
        return orders.markPacked(order.orderId(), accountant);
    }

    private ShipmentHandover handover(OrderSummary order, UUID shipment, int units) {
        com.stockflow.support.ShipmentRows.ready(jdbc, tx, order.orderId(), shipment);
        return new ShipmentHandover(shipment, List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(),
                units)), Instant.now(), accountant);
    }

    private long unallocated(UUID transfer) {
        return jdbc.queryForObject("SELECT unallocated_amount::bigint FROM payment.customer_transfer WHERE id = ?",
                Long.class, transfer);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    // ------------------------------------------------------------------------------------------------------ tests

    @Test
    @DisplayName("prepaid paid in two transfers: part paid it waits, the second confirms it (BR-02); the same "
            + "statement line twice is refused (BR-07); more than it is worth is refused (BR-05)")
    void prepaidInTwoTransfers() {
        OrderSummary order = place(PaymentTerm.PREPAID, 2);            // 10M
        String first = ref();
        var part = transfer(first, 4_000_000, List.of(pay(order, 4_000_000)));
        assertThat(part.allocations()).singleElement().satisfies(a -> {
            assertThat(a.paysOrder()).isTrue();
            assertThat(a.orderId()).isEqualTo(order.orderId());
        });
        awaitPaid(order, 4_000_000);
        assertThat(reload(order).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(reload(order).payment().status()).isEqualTo(PaymentStatus.PARTIALLY_PAID);

        assertThat(code(catchThrowable(() -> transfer(first, 6_000_000, List.of(pay(order, 6_000_000))))))
                .isEqualTo(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED);
        assertThat(code(catchThrowable(() -> transfer(ref(), 7_000_000, List.of(pay(order, 6_000_001))))))
                .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_AMOUNT_DUE);
        assertThat(code(catchThrowable(() -> transfer(ref(), 5_000_000, List.of(pay(order, 6_000_000))))))
                .as("the orders ask for more than the transfer brings").isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_TRANSFER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.transfer_allocation WHERE order_id = ?",
                Long.class, order.orderId())).as("refusals leave nothing behind").isEqualTo(1L);

        var rest = transfer(ref(), 6_500_000, List.of(pay(order, 6_000_000)));
        assertThat(rest.transfer().unallocatedAmount()).as("the rest is credit").isEqualByComparingTo("500000");
        awaitStatus(order, OrderStatus.CONFIRMED);
        assertThat(reload(order).payment().status()).isEqualTo(PaymentStatus.PAID);

        var history = ledger.orderPayments(order.orderId());
        assertThat(history.collected()).isEqualByComparingTo("10000000");
        assertThat(history.mayStillTake()).isZero();
        assertThat(history.allocations()).hasSize(2);
        assertThat(history.transfers()).hasSize(2);
    }

    @Test
    @DisplayName("deposit then balance (BEFORE_DELIVERY): the deposit confirms and releases; packing warns, the "
            + "handover is refused until the balance arrives (G3-Q4)")
    void depositThenBalance() {
        OrderSummary order = place(PaymentTerm.DEPOSIT, 2);            // 10M, deposit 3M
        assertThat(order.payment().balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
        transfer(ref(), 3_000_000, List.of(pay(order, 3_000_000)));
        awaitStatus(order, OrderStatus.CONFIRMED);
        OrderSummary packed = packedAfterRelease(order);
        assertThat(packed.lifecycle().amountDueBeforeHandover()).isEqualByComparingTo("7000000");
        assertThat(code(catchThrowable(() -> orders.markShipped(order.orderId(), null, accountant))))
                .isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);

        transfer(ref(), 7_000_000, List.of(pay(order, 7_000_000)));
        awaitPaid(order, 10_000_000);
        assertThat(reload(order).lifecycle().amountDueBeforeHandover()).isZero();
        assertThat(orders.markShipped(order.orderId(), null, accountant).status()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("one transfer pays two orders: each confirmed by its share, the rest is credit; a credit order and "
            + "another customer's order take nothing")
    void oneTransferTwoOrders() {
        OrderSummary prepaid = place(PaymentTerm.PREPAID, 1);          // 5M
        OrderSummary deposit = place(PaymentTerm.DEPOSIT, 2);          // 10M, deposit 3M
        var result = transfer(ref(), 9_000_000, List.of(pay(prepaid, 5_000_000), pay(deposit, 3_000_000)));
        assertThat(result.allocations()).hasSize(2);
        assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("1000000");
        awaitStatus(prepaid, OrderStatus.CONFIRMED);
        awaitStatus(deposit, OrderStatus.CONFIRMED);

        OrderSummary credit = place(PaymentTerm.CREDIT, 1);
        assertThat(code(catchThrowable(() -> ledger.allocate(new MoneyLedgerService.AllocateCommand(
                result.transfer().id(), credit.orderId(), null, BigDecimal.ONE, accountant)))))
                .isEqualTo(ErrorCode.ALLOCATION_ORDER_NOT_PAYABLE);

        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));
        terms(other, DepositBalancePolicy.BEFORE_DELIVERY);
        OrderSummary theirs = place(other, PaymentTerm.PREPAID, 1, null);
        assertThat(code(catchThrowable(() -> ledger.allocate(new MoneyLedgerService.AllocateCommand(
                result.transfer().id(), theirs.orderId(), null, BigDecimal.ONE, accountant)))))
                .isEqualTo(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH);
    }

    @Test
    @DisplayName("a cancelled order takes no money: it stays credit, and the refund path gives it back (15 edge); "
            + "money paid before the cancellation is refunded from its allocation")
    void cancelledOrderRefundPath() {
        OrderSummary early = place(PaymentTerm.PREPAID, 1);
        var paid = transfer(ref(), 5_000_000, List.of(pay(early, 5_000_000)));
        awaitStatus(early, OrderStatus.CONFIRMED);
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(early.orderId(),
                new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, "changed plans", null, null)));
        UUID allocation = paid.allocations().getFirst().id();
        Await.until("refund placed on the allocation", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.refund WHERE allocation_id = ? AND source = 'ORDER_CANCELLED'",
                Long.class, allocation) == 1);
        assertThat(jdbc.queryForObject("SELECT amount::bigint || ' ' || status FROM payment.refund "
                + "WHERE allocation_id = ?", String.class, allocation)).isEqualTo("5000000 PENDING");
        assertThat(code(catchThrowable(() -> ledger.reverse(new MoneyLedgerService.ReverseCommand(
                paid.transfer().id(), allocation, "wrong", true, accountant)))))
                .as("refunded money is not reversed").isEqualTo(ErrorCode.PAYMENT_REVERSAL_NOT_ALLOWED);

        // Money arriving for the cancelled order: recorded, but not allocated to it.
        var late = transfer(ref(), 2_000_000, List.of());
        assertThat(code(catchThrowable(() -> ledger.allocate(new MoneyLedgerService.AllocateCommand(
                late.transfer().id(), early.orderId(), null, BigDecimal.valueOf(2_000_000), accountant)))))
                .isEqualTo(ErrorCode.ALLOCATION_ORDER_CANCELLED);
        assertThat(unallocated(late.transfer().id())).isEqualTo(2_000_000L);
        assertThat(code(catchThrowable(() -> ledger.refundCredit(new MoneyLedgerService.CreditRefundCommand(
                late.transfer().id(), BigDecimal.valueOf(2_000_001), "customer asks it back", accountant)))))
                .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_TRANSFER);
        var refund = ledger.refundCredit(new MoneyLedgerService.CreditRefundCommand(late.transfer().id(),
                BigDecimal.valueOf(2_000_000), "customer asks it back", accountant));
        assertThat(refund.transfer().unallocatedAmount()).isZero();
        assertThat(refund.transfer().refundedAmount()).isEqualByComparingTo("2000000");
        assertThat(jdbc.queryForObject("SELECT source || ' ' || status FROM payment.refund WHERE id = ?",
                String.class, refund.refundId())).isEqualTo("CUSTOMER_CREDIT PENDING");
    }

    @Test
    @DisplayName("reversal after confirmation: refused without holdOrder; with it the order goes ON_HOLD (PAYMENT), "
            + "the money is credit again, once; paid again and resolved, it is CONFIRMED")
    void reversalAfterConfirmation() {
        OrderSummary order = place(PaymentTerm.PREPAID, 1);
        var paid = transfer(ref(), 5_000_000, List.of(pay(order, 5_000_000)));
        UUID transferId = paid.transfer().id();
        UUID allocation = paid.allocations().getFirst().id();
        awaitStatus(order, OrderStatus.CONFIRMED);

        assertThat(code(catchThrowable(() -> ledger.reverse(new MoneyLedgerService.ReverseCommand(transferId,
                allocation, "allocated to the wrong order", false, accountant)))))
                .isEqualTo(ErrorCode.PAYMENT_REVERSAL_NEEDS_HOLD);
        assertThat(reload(order).status()).as("not un-confirmed silently").isEqualTo(OrderStatus.CONFIRMED);

        var reversed = ledger.reverse(new MoneyLedgerService.ReverseCommand(transferId, allocation,
                "allocated to the wrong order", true, accountant));
        assertThat(reversed.allocation().amount()).isEqualByComparingTo("-5000000");
        assertThat(reversed.allocation().reversesId()).isEqualTo(allocation);
        assertThat(unallocated(transferId)).isEqualTo(5_000_000L);
        awaitStatus(order, OrderStatus.ON_HOLD);
        OrderSummary held = reload(order);
        assertThat(held.lifecycle().holdReason()).isEqualTo(HoldReason.PAYMENT);
        assertThat(held.payment().paidAmount()).isZero();
        assertThat(held.payment().paidInFullAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.transfer_allocation WHERE id = ?", Long.class,
                allocation)).as("the original row stays (BR-06)").isEqualTo(1L);

        assertThat(code(catchThrowable(() -> ledger.reverse(new MoneyLedgerService.ReverseCommand(transferId,
                allocation, "again", true, accountant))))).isEqualTo(ErrorCode.ALLOCATION_ALREADY_REVERSED);
        assertThat(code(catchThrowable(() -> ledger.reverse(new MoneyLedgerService.ReverseCommand(transferId,
                reversed.allocation().id(), "undo", true, accountant))))).isEqualTo(ErrorCode.ALLOCATION_ALREADY_REVERSED);
        assertThat(code(catchThrowable(() -> orders.resolveHold(order.orderId(), "looks fine", accountant))))
                .isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);

        ledger.allocate(new MoneyLedgerService.AllocateCommand(transferId, order.orderId(), null,
                BigDecimal.valueOf(5_000_000), accountant));
        awaitPaid(order, 5_000_000);
        assertThat(orders.resolveHold(order.orderId(), "the right money arrived", accountant).status())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(ledger.orderPayments(order.orderId()).allocations()).hasSize(3);
    }

    @Test
    @DisplayName("before confirmation a reversal needs no hold: the order just waits for the money again")
    void reversalBeforeConfirmation() {
        OrderSummary order = place(PaymentTerm.PREPAID, 2);
        var part = transfer(ref(), 2_000_000, List.of(pay(order, 2_000_000)));
        awaitPaid(order, 2_000_000);
        ledger.reverse(new MoneyLedgerService.ReverseCommand(part.transfer().id(), part.allocations().getFirst().id(),
                "duplicate statement line", false, accountant));
        awaitPaid(order, 0);
        assertThat(reload(order).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("two allocations race for one order's last gap: one fits, the other is ALLOCATION_EXCEEDS_AMOUNT_DUE")
    void concurrentAllocations() throws Exception {
        OrderSummary order = place(PaymentTerm.PREPAID, 1);            // 5M
        UUID a = transfer(ref(), 5_000_000, List.of()).transfer().id();
        UUID b = transfer(ref(), 5_000_000, List.of()).transfer().id();
        List<Object> outcomes = race(
                () -> ledger.allocate(new MoneyLedgerService.AllocateCommand(a, order.orderId(), null,
                        BigDecimal.valueOf(5_000_000), accountant)),
                () -> ledger.allocate(new MoneyLedgerService.AllocateCommand(b, order.orderId(), null,
                        BigDecimal.valueOf(5_000_000), accountant)));
        assertThat(outcomes).filteredOn(MoneyLedgerService.AllocationResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_AMOUNT_DUE));
        assertThat(unallocated(a) + unallocated(b)).isEqualTo(5_000_000L);
        awaitStatus(order, OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("ON_DELIVERY, split: each shipment's COD fixed at the handover; delivery opens a carrier receivable "
            + "the customer's money never pays; the carrier's remittance does, once; the order completes only paid")
    void splitShipmentCarrierCollects() {
        terms(customer, DepositBalancePolicy.ON_DELIVERY);
        OrderSummary order = place(PaymentTerm.DEPOSIT, 2);            // 10M, deposit 3M
        assertThat(order.payment().balancePolicy()).isEqualTo(BalancePolicy.ON_DELIVERY);
        assertThat(order.lifecycle().amountDueOnDelivery()).isEqualByComparingTo("10000000");
        transfer(ref(), 3_000_000, List.of(pay(order, 3_000_000)));
        awaitStatus(order, OrderStatus.CONFIRMED);
        releases.release(order.orderId(), DemoData.WAREHOUSE_HCM, accountant);
        orders.startPicking(order.orderId(), accountant);
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, s1, 1));     // 5M: COD 2M
        orders.markPacked(order.orderId(), accountant);
        OrderSummary shipped = orders.recordHandover(order.orderId(), handover(order, s2, 1));   // 5M: COD 5M
        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(shipped.lifecycle().shipments()).extracting(OrderSummary.Shipment::codAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2000000"), new BigDecimal("5000000"));

        // While the carrier carries it, a transfer cannot pay the balance as well.
        var credit = transfer(ref(), 7_000_000, List.of());
        assertThat(code(catchThrowable(() -> ledger.allocate(new MoneyLedgerService.AllocateCommand(
                credit.transfer().id(), order.orderId(), null, BigDecimal.ONE, accountant)))))
                .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_AMOUNT_DUE);

        orders.recordShipmentDelivered(order.orderId(), s1, accountant);
        Await.until("carrier receivable", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.receivable WHERE order_id = ?", Long.class, order.orderId()) == 1);
        UUID receivable = jdbc.queryForObject("SELECT id FROM payment.receivable WHERE order_id = ?", UUID.class,
                order.orderId());
        assertThat(jdbc.queryForObject("SELECT kind || ' ' || collector || ' ' || amount::bigint || ' ' || due_date "
                + "FROM payment.receivable WHERE id = ?", String.class, receivable))
                .isEqualTo("DEPOSIT_BALANCE CARRIER 2000000 " + TODAY);
        assertThat(payments.creditPosition(customer).outstandingReceivables())
                .as("not the customer's debt").isZero();
        assertThat(code(catchThrowable(() -> receivables.recordTransfer(new ReceivableService.RecordTransferCommand(
                customer, ref(), BigDecimal.TEN, "VND", TODAY, List.of(receivable), null, accountant)))))
                .isEqualTo(ErrorCode.RECEIVABLE_CARRIER_COLLECTED);
        assertThat(code(catchThrowable(() -> ledger.allocate(new MoneyLedgerService.AllocateCommand(
                credit.transfer().id(), null, receivable, null, accountant)))))
                .isEqualTo(ErrorCode.RECEIVABLE_CARRIER_COLLECTED);

        // The carrier remits short, then the rest.
        var shortRemit = ledger.recordCarrierRemittance(new MoneyLedgerService.CarrierRemittanceCommand(receivable,
                "GHN", "GHN-DS-1", BigDecimal.valueOf(1_500_000), TODAY, null, accountant));
        assertThat(shortRemit.transfer().reference()).startsWith("GHN-DS-1:" + order.orderNumber() + ":");
        assertThat(jdbc.queryForObject("SELECT status FROM payment.receivable WHERE id = ?", String.class, receivable))
                .isEqualTo("PARTIALLY_PAID");
        assertThat(code(catchThrowable(() -> ledger.recordCarrierRemittance(new MoneyLedgerService
                .CarrierRemittanceCommand(receivable, "GHN", "GHN-DS-1", BigDecimal.ONE, TODAY, null, accountant)))))
                .isEqualTo(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED);
        ledger.recordCarrierRemittance(new MoneyLedgerService.CarrierRemittanceCommand(receivable, "GHN", "GHN-DS-2",
                BigDecimal.valueOf(500_000), TODAY, null, accountant));
        assertThat(jdbc.queryForObject("SELECT status FROM payment.receivable WHERE id = ?", String.class, receivable))
                .isEqualTo("PAID");
        awaitPaid(order, 5_000_000);

        orders.recordShipmentDelivered(order.orderId(), s2, accountant);
        awaitStatus(order, OrderStatus.DELIVERED);
        Await.until("second carrier receivable", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.receivable WHERE order_id = ?", Long.class, order.orderId()) == 2);
        var history = ledger.orderPayments(order.orderId());
        assertThat(history.collected()).isEqualByComparingTo("5000000");
        assertThat(history.outstandingReceivables()).isEqualByComparingTo("5000000");
        assertThat(history.mayStillTake()).as("billed to the carrier: no transfer pays it twice").isZero();
        assertThat(reload(order).lifecycle().amountDue()).isEqualByComparingTo("5000000");
    }

    @Test
    @DisplayName("a remittance on a receivable the customer pays is RECEIVABLE_NOT_CARRIER_COLLECTED; Sales cannot "
            + "let the carrier collect for a customer whose terms say before delivery")
    void carrierAndPolicyRefusals() {
        OrderSummary credit = place(PaymentTerm.CREDIT, 1);
        releases.release(credit.orderId(), DemoData.WAREHOUSE_HCM, accountant);
        orders.startPicking(credit.orderId(), accountant);
        orders.markPacked(credit.orderId(), accountant);
        orders.markShipped(credit.orderId(), null, accountant);
        orders.recordDelivery(credit.orderId());
        Await.until("credit receivable", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.receivable WHERE order_id = ?", Long.class, credit.orderId()) == 1);
        UUID receivable = jdbc.queryForObject("SELECT id FROM payment.receivable WHERE order_id = ?", UUID.class,
                credit.orderId());
        assertThat(code(catchThrowable(() -> ledger.recordCarrierRemittance(new MoneyLedgerService
                .CarrierRemittanceCommand(receivable, "GHN", "GHN-X", BigDecimal.ONE, TODAY, null, accountant)))))
                .isEqualTo(ErrorCode.RECEIVABLE_NOT_CARRIER_COLLECTED);

        assertThat(code(catchThrowable(() -> place(customer, PaymentTerm.DEPOSIT, 1, BalancePolicy.ON_DELIVERY))))
                .isEqualTo(ErrorCode.BALANCE_POLICY_NOT_ALLOWED);
        assertThat(code(catchThrowable(() -> place(customer, PaymentTerm.PREPAID, 1, BalancePolicy.BEFORE_DELIVERY))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        terms(customer, DepositBalancePolicy.ON_DELIVERY);
        assertThat(place(customer, PaymentTerm.DEPOSIT, 1, BalancePolicy.BEFORE_DELIVERY).payment().balancePolicy())
                .as("Sales may tighten it").isEqualTo(BalancePolicy.BEFORE_DELIVERY);
    }

    // ------------------------------------------------------------------------------------------- review of G2-3

    @Test
    @DisplayName("review 1: cancellation refunds asked once - A1=100, A2=50, 50 refundable: A2 refunded; a redelivered "
            + "OrderCancelled asks nothing of A1")
    void cancellationRefundsAreIdempotent() {
        OrderSummary order = placeAt(PaymentTerm.PREPAID, 150, null);
        transfer(ref(), 100, List.of(pay(order, 100)));
        var second = transfer(ref(), 50, List.of(pay(order, 50)));
        awaitStatus(order, OrderStatus.CONFIRMED);
        String reason = "Order cancelled (CUSTOMER_REQUEST)";
        assertThat((BigDecimal) tx.execute(st -> settlement.requestRefunds(order.orderId(), BigDecimal.valueOf(50), "VND", reason)))
                .isZero();
        assertThat((BigDecimal) tx.execute(st -> settlement.requestRefunds(order.orderId(), BigDecimal.valueOf(50), "VND", reason)))
                .as("redelivered").isZero();
        assertThat(jdbc.queryForList("SELECT amount::bigint || ' ' || (allocation_id = ?) FROM payment.refund "
                + "WHERE order_id = ?", String.class, second.allocations().getFirst().id(), order.orderId()))
                .containsExactly("50 true");
    }

    @Test
    @DisplayName("review 2 + 7: one GHN statement line remits two shipments of one order, each once; a paid "
            + "receivable takes no remittance")
    void oneStatementTwoShipments() {
        terms(customer, DepositBalancePolicy.ON_DELIVERY);
        OrderSummary order = place(PaymentTerm.DEPOSIT, 2);            // 10M, deposit 3M
        transfer(ref(), 3_000_000, List.of(pay(order, 3_000_000)));
        awaitStatus(order, OrderStatus.CONFIRMED);
        releases.release(order.orderId(), DemoData.WAREHOUSE_HCM, accountant);
        orders.startPicking(order.orderId(), accountant);
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        orders.recordHandover(order.orderId(), handover(order, s1, 1));     // COD 2M
        orders.markPacked(order.orderId(), accountant);
        orders.recordHandover(order.orderId(), handover(order, s2, 1));     // COD 5M
        orders.recordShipmentDelivered(order.orderId(), s1, accountant);
        orders.recordShipmentDelivered(order.orderId(), s2, accountant);
        Await.until("two carrier receivables", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.receivable WHERE order_id = ?", Long.class, order.orderId()) == 2);
        UUID r1 = jdbc.queryForObject("SELECT id FROM payment.receivable WHERE shipment_id = ?", UUID.class, s1);
        UUID r2 = jdbc.queryForObject("SELECT id FROM payment.receivable WHERE shipment_id = ?", UUID.class, s2);
        var first = ledger.recordCarrierRemittance(new MoneyLedgerService.CarrierRemittanceCommand(r1, "GHN",
                "GHN-DS-3", BigDecimal.valueOf(2_000_000), TODAY, null, accountant));
        var secondShipment = ledger.recordCarrierRemittance(new MoneyLedgerService.CarrierRemittanceCommand(r2, "GHN",
                "GHN-DS-3", BigDecimal.valueOf(5_000_000), TODAY, null, accountant));
        assertThat(first.transfer().reference()).startsWith("GHN-DS-3:" + order.orderNumber() + ":")
                .isNotEqualTo(secondShipment.transfer().reference());
        assertThat(code(catchThrowable(() -> ledger.recordCarrierRemittance(new MoneyLedgerService
                .CarrierRemittanceCommand(r2, "GHN", "GHN-DS-3", BigDecimal.ONE, TODAY, null, accountant)))))
                .as("the same line for the same shipment, refused before anything else")
                .isIn(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED, ErrorCode.RECEIVABLE_SETTLED);
        assertThat(code(catchThrowable(() -> ledger.recordCarrierRemittance(new MoneyLedgerService
                .CarrierRemittanceCommand(r2, "GHN", "GHN-DS-4", BigDecimal.ONE, TODAY, null, accountant)))))
                .isEqualTo(ErrorCode.RECEIVABLE_SETTLED);
        awaitPaid(order, 10_000_000);
    }

    @Test
    @DisplayName("review 3: a BEFORE_DELIVERY deposit order delivered with 907 000 of 1 295 000 unpaid opens the "
            + "customer's DEPOSIT_BALANCE receivable of 907 000, due on the delivery day")
    void depositDeliveredUnpaid() {
        OrderSummary order = placeAt(PaymentTerm.DEPOSIT, 1_295_000, new BigDecimal("29.96"));   // deposit 387 982
        transfer(ref(), 388_000, List.of(pay(order, 388_000)));
        awaitStatus(order, OrderStatus.CONFIRMED);
        var balance = transfer(ref(), 907_000, List.of(pay(order, 907_000)));
        awaitPaid(order, 1_295_000);
        packedAfterRelease(order);
        orders.markShipped(order.orderId(), null, accountant);
        // The balance turns out to belong to another order: taken back after the goods left.
        ledger.reverse(new MoneyLedgerService.ReverseCommand(balance.transfer().id(),
                balance.allocations().getFirst().id(), "belongs to another order", false, accountant));
        awaitPaid(order, 388_000);
        orders.recordDelivery(order.orderId());
        Await.until("balance receivable", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.receivable WHERE order_id = ?", Long.class, order.orderId()) == 1);
        assertThat(jdbc.queryForObject("SELECT kind || ' ' || collector || ' ' || amount::bigint || ' ' || due_date "
                + "FROM payment.receivable WHERE order_id = ?", String.class, order.orderId()))
                .isEqualTo("DEPOSIT_BALANCE CUSTOMER 907000 " + TODAY);
    }

    @Test
    @DisplayName("review 5: COD at the handover counts a 2M allocation the order has not heard of: deposit 3M + 2M, "
            + "a 5M shipment collects nothing")
    void codCountsTheLedger() {
        terms(customer, DepositBalancePolicy.ON_DELIVERY);
        OrderSummary order = place(PaymentTerm.DEPOSIT, 2);            // 10M
        transfer(ref(), 3_000_000, List.of(pay(order, 3_000_000)));
        awaitStatus(order, OrderStatus.CONFIRMED);
        releases.release(order.orderId(), DemoData.WAREHOUSE_HCM, accountant);
        orders.startPicking(order.orderId(), accountant);
        // Money in the ledger whose PaymentCaptured the order has not processed: written directly, no event.
        UUID t = UUID.randomUUID();
        tx.executeWithoutResult(st -> {
            jdbc.update("""
                    INSERT INTO payment.customer_transfer (id, customer_id, reference, amount, unallocated_amount,
                                                           currency, received_on, recorded_by, recorded_at)
                    VALUES (?, ?, ?, 2000000, 0, 'VND', CURRENT_DATE, ?, NOW())""", t, customer, ref(), accountant);
            jdbc.update("""
                    INSERT INTO payment.transfer_allocation (id, transfer_id, order_id, amount, allocated_at)
                    VALUES (?, ?, ?, 2000000, NOW())""", UUID.randomUUID(), t, order.orderId());
        });
        OrderSummary shipped = orders.recordHandover(order.orderId(), handover(order, UUID.randomUUID(), 1));
        assertThat(shipped.lifecycle().shipments().getFirst().codAmount()).isZero();
    }

    @Test
    @DisplayName("review 6: a PaymentReversed reaching a cancelled order is consumed: paid falls, nothing kept above "
            + "it, the order stays CANCELLED")
    void reversalAfterCancellation() {
        OrderSummary order = place(PaymentTerm.PREPAID, 1);
        var paid = transfer(ref(), 5_000_000, List.of(pay(order, 5_000_000)));
        awaitStatus(order, OrderStatus.CONFIRMED);
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(),
                new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, "changed plans", null, null)));
        UUID reversal = UUID.randomUUID();
        tx.executeWithoutResult(st -> publisher.publishEvent(new com.stockflow.contracts.PaymentReversed(
                order.orderId(), reversal, paid.allocations().getFirst().id(), BigDecimal.valueOf(5_000_000), "VND",
                "ORDER_ALLOCATION", accountant, Instant.now())));
        awaitPaid(order, 0);
        assertThat(reload(order).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_payment WHERE payment_id = ?", Long.class,
                reversal)).isEqualTo(1L);
    }

    @Test
    @DisplayName("review 8 + 9: a statement line recorded before the ledger is not recorded again; a failed credit "
            + "refund of 1 000 000 is credit again")
    void legacyReferenceAndFailedCreditRefund() {
        OrderSummary order = place(PaymentTerm.PREPAID, 1);
        String legacy = ref();
        tx.executeWithoutResult(st -> jdbc.update("""
                INSERT INTO payment.payment (id, order_id, amount, currency, method, status, provider_ref, captured_at,
                                             created_at)
                VALUES (?, ?, 1000, 'VND', 'BANK_TRANSFER', 'CAPTURED', ?, NOW(), NOW())""",
                UUID.randomUUID(), order.orderId(), legacy));
        assertThat(code(catchThrowable(() -> transfer(legacy, 1_000, List.of()))))
                .isEqualTo(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED);

        var credit = transfer(ref(), 1_000_000, List.of());
        var refund = ledger.refundCredit(new MoneyLedgerService.CreditRefundCommand(credit.transfer().id(),
                BigDecimal.valueOf(1_000_000), "customer asks it back", accountant));
        assertThat(unallocated(credit.transfer().id())).isZero();
        tx.executeWithoutResult(st -> jdbc.update("UPDATE payment.refund SET status = 'FAILED' WHERE id = ?",
                refund.refundId()));
        assertThat(unallocated(credit.transfer().id())).isEqualTo(1_000_000L);
        assertThat(jdbc.queryForObject("SELECT refunded_amount::bigint FROM payment.customer_transfer WHERE id = ?",
                Long.class, credit.transfer().id())).isZero();
    }

    @Test
    @DisplayName("review 11: terms saved without a balance policy keep the one they had")
    void termsKeepTheirPolicy() {
        terms(customer, DepositBalancePolicy.ON_DELIVERY);
        terms(customer, null);
        assertThat(customers.creditTerms(customer).depositBalancePolicy()).isEqualTo(DepositBalancePolicy.ON_DELIVERY);
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
                        return call.call();
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
