package com.stockflow.payment;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.internal.domain.ReceivableStatus;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-431 against a real database (kltn-docs 15 §4.3, §5.3, BR-07, BR-09): a delivered credit order
 * becomes a receivable due after its days to pay, transfers pay the earliest due first and leave the
 * rest as credit, every allocation reaches the order as money received, and an overdue customer gets
 * no new credit and no release without the approver.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ReceivableIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration ASYNC = Duration.ofSeconds(10);

    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired CustomerService customers;
    @Autowired ReceivableService receivables;
    @Autowired PaymentService payments;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private UUID customer;
    private UUID accountant;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void creditCustomer() {
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, false, true, CommercialTerm.CREDIT, null,
                new BigDecimal("100000000"), 30, "test terms", accountant, null));
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderStatus status = orders.findById(id).orElseThrow().status();
            if (status.canTransitionTo(OrderStatus.CANCELLED)) {
                orders.cancel(id, new CancelOrderCommand(CancellationReasonCode.OTHER, "test cleanup", null, null));
            } else {
                // Delivered without a pick in this test: put its held stock back for the tests after it.
                jdbc.queryForList("SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'",
                        UUID.class, id).forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE"));
            }
        }));
    }

    private OrderSummary creditOrder(long price) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, 1, Money.vnd(price), null)), PaymentTerm.CREDIT, null));
        placed.add(order.orderId());
        return order;
    }

    /** Handed over to the carrier: shipping records it (SCRUM-289/294); here the row is set directly. */
    private OrderSummary delivered(long price) {
        OrderSummary order = creditOrder(price);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET status = 'SHIPPED' WHERE id = ?",
                order.orderId()));
        return orders.recordDelivery(order.orderId());
    }

    private String receivableOf(UUID orderId) {
        List<String> rows = jdbc.queryForList("""
                SELECT status || ' ' || amount::bigint || ' ' || paid_amount::bigint || ' ' || due_date
                FROM payment.receivable WHERE order_id = ?""", String.class, orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ReceivableService.TransferResult transfer(String reference, long amount, List<UUID> allocateTo) {
        return receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer, reference,
                BigDecimal.valueOf(amount), "VND", LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")), allocateTo, null,
                accountant));
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("delivered → receivable due in 30 days; transfers pay it first-due-first, the rest is credit")
    void deliveryTransfersAndCredit() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        OrderSummary first = delivered(12_000_000);
        assertThat(first.status()).isEqualTo(OrderStatus.DELIVERED);
        Await.until("receivable opened", ASYNC, () -> receivableOf(first.orderId()) != null);
        assertThat(receivableOf(first.orderId())).isEqualTo("OPEN 12000000 0 " + today.plusDays(30));
        assertThat(orders.recordDelivery(first.orderId()).status()).isEqualTo(OrderStatus.DELIVERED);   // idempotent
        assertThat(payments.creditPosition(customer).outstandingReceivables()).isEqualByComparingTo("12000000");

        String ref = "FT-" + UUID.randomUUID().toString().substring(0, 8);
        var part = transfer(ref, 5_000_000, null);
        assertThat(part.allocations()).hasSize(1);
        assertThat(receivableOf(first.orderId())).startsWith("PARTIALLY_PAID 12000000 5000000");
        Await.until("order paid 5M", ASYNC, () ->
                orders.findById(first.orderId()).orElseThrow().payment().paidAmount().compareTo(new BigDecimal("5000000")) == 0);
        assertThat(codeOf(catchThrowable(() -> transfer(ref, 1_000, null))))
                .isEqualTo(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED);

        var more = transfer(ref + "-2", 10_000_000, null);
        assertThat(more.transfer().unallocatedAmount()).isEqualByComparingTo("3000000");
        assertThat(receivableOf(first.orderId())).startsWith("PAID 12000000 12000000");
        Await.until("order paid in full", ASYNC, () ->
                orders.findById(first.orderId()).orElseThrow().payment().status() == PaymentStatus.PAID);

        // The customer's credit pays the next receivable as soon as it opens.
        OrderSummary second = delivered(2_000_000);
        Await.until("second receivable paid from credit", ASYNC, () -> {
            String r = receivableOf(second.orderId());
            return r != null && r.startsWith("PAID 2000000 2000000");
        });
        assertThat(jdbc.queryForObject("SELECT unallocated_amount::bigint FROM payment.customer_transfer WHERE reference = ?",
                Long.class, ref + "-2")).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("a named receivable of another customer is ALLOCATION_CUSTOMER_MISMATCH")
    void namedReceivableOfAnotherCustomer() {
        OrderSummary order = delivered(3_000_000);
        Await.until("receivable opened", ASYNC, () -> receivableOf(order.orderId()) != null);
        UUID theirs = jdbc.queryForObject("SELECT id FROM payment.receivable WHERE order_id = ?", UUID.class,
                order.orderId());
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));

        Throwable thrown = catchThrowable(() -> receivables.recordTransfer(new ReceivableService.RecordTransferCommand(
                other, "FT-X-" + UUID.randomUUID(), BigDecimal.TEN, "VND", LocalDate.now(), List.of(theirs), null,
                accountant)));
        assertThat(codeOf(thrown)).isEqualTo(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH);
    }

    @Test
    @DisplayName("overdue: the nightly run marks it; new credit waits for the approver; release is refused")
    void overdueBlocksCredit() {
        OrderSummary late = delivered(4_000_000);
        Await.until("receivable opened", ASYNC, () -> receivableOf(late.orderId()) != null);
        OrderSummary confirmed = creditOrder(1_000_000);
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);

        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE payment.receivable SET due_date = CURRENT_DATE - 2 WHERE order_id = ?", late.orderId()));
        assertThat(receivables.markOverdue()).isGreaterThanOrEqualTo(1);
        assertThat(receivables.markOverdue()).isZero();   // a second run changes nothing
        assertThat(receivableOf(late.orderId())).startsWith(ReceivableStatus.OVERDUE.name());
        assertThat(payments.creditPosition(customer).hasOverdue()).isTrue();

        OrderSummary held = creditOrder(1_000_000);
        assertThat(held.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM ordering.order_credit_check WHERE order_id = ?""", String.class, held.orderId()))
                .isEqualTo("OVERDUE");
        UUID coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        assertThat(codeOf(catchThrowable(() -> releases.release(confirmed.orderId(), DemoData.WAREHOUSE_HCM, coordinator))))
                .isEqualTo(ErrorCode.CUSTOMER_CREDIT_OVERDUE);
    }
}
