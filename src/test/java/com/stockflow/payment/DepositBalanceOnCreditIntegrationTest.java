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
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.payment.api.PaymentService;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * BALCREDIT (lead's decision 2026-10-21; kltn-docs 15 §4.2 step 3) against a real database: a deposit order's balance
 * on credit is checked against the customer's limit when the order is placed - over it, or overdue, the order is
 * refused (409) and nothing is held - and on delivery becomes the customer's receivable, due after their days to pay,
 * counted in their credit exposure exactly once.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class DepositBalanceOnCreditIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final long PRICE = 5_000_000;
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired OrderService orders;
    @Autowired com.stockflow.order.internal.service.QuotedOrders quotedOrders;
    @Autowired OrderReleases releases;
    @Autowired CustomerService customers;
    @Autowired ReceivableService receivables;
    @Autowired PaymentService payments;
    @Autowired CreditChecks creditChecks;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private UUID customer;
    private UUID accountant;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void customerOnCredit() {
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        terms(customer, true, new BigDecimal("20000000"), DepositBalancePolicy.ON_CREDIT);
    }

    private void terms(UUID who, boolean credit, BigDecimal limit, DepositBalancePolicy policy) {
        Long version = jdbc.queryForObject("SELECT COALESCE(MAX(version), -1) FROM customer.credit_profile "
                + "WHERE customer_id = ?", Long.class, who);
        customers.saveCreditTerms(new SaveCreditTermsCommand(who, true, true, credit, CommercialTerm.DEPOSIT,
                new BigDecimal("30"), credit ? limit : null, credit ? 30 : null, "test terms", accountant,
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

    private OrderSummary place(PaymentTerm term, int units, BalancePolicy policy) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, units, Money.vnd(PRICE), null)), term, null, policy));
        placed.add(order.orderId());
        return order;
    }

    /** What the customer owes on credit: undelivered credit (orders, balances) plus open receivables. */
    private BigDecimal exposure() {
        return creditChecks.undeliveredCreditExposure(customer, null)
                .add(payments.creditPosition(customer).outstandingReceivables());
    }

    private OrderSummary reload(OrderSummary order) {
        return orders.findById(order.orderId()).orElseThrow();
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private int heldFor(UUID requestId) {
        return jdbc.queryForObject("SELECT count(*)::int FROM ordering.customer_order WHERE request_id = ?",
                Integer.class, requestId);
    }

    @Test
    @DisplayName("within the limit: placed on the deposit, the 7M balance counted at once; deposit paid, released, "
            + "packed, handed over with no COD and delivered → a 7M DEPOSIT_BALANCE receivable due in 30 days; "
            + "exposure still 7M - counted once")
    void withinTheLimitBecomesAReceivable() {
        OrderSummary order = place(PaymentTerm.DEPOSIT, 2, null);         // 10M, deposit 3M, balance 7M
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.payment().balancePolicy()).isEqualTo(BalancePolicy.ON_CREDIT);
        assertThat(order.payment().creditTermDays()).isEqualTo(30);
        assertThat(exposure()).as("the balance, from placement").isEqualByComparingTo("7000000");
        assertThat(jdbc.queryForObject("SELECT order_amount::bigint || '/' || outcome FROM ordering.order_credit_check "
                + "WHERE order_id = ?", String.class, order.orderId())).isEqualTo("7000000/WITHIN_LIMIT");

        receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer, "FT" + UUID.randomUUID()
                .toString().replace("-", "").substring(0, 12), BigDecimal.valueOf(3_000_000), "VND", TODAY, null,
                "deposit", accountant, List.of(new ReceivableService.OrderPayment(order.orderId(),
                BigDecimal.valueOf(3_000_000)))));
        Await.until("deposit confirms it", ASYNC, () -> reload(order).status() == OrderStatus.CONFIRMED);
        assertThat(exposure()).isEqualByComparingTo("7000000");

        releases.release(order.orderId(), DemoData.WAREHOUSE_HCM, accountant);
        orders.startPicking(order.orderId(), accountant);
        OrderSummary packed = orders.markPacked(order.orderId(), accountant);
        assertThat(packed.lifecycle().amountDueBeforeHandover()).as("only the deposit, paid").isZero();
        assertThat(packed.lifecycle().amountDueOnDelivery()).as("nothing for the carrier").isZero();

        UUID shipment = com.stockflow.support.ShipmentRows.ready(jdbc, tx, order.orderId(), UUID.randomUUID());
        OrderSummary shipped = orders.recordHandover(order.orderId(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), 2)), Instant.now(), accountant));
        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(shipped.lifecycle().shipments().getFirst().codAmount()).isZero();
        assertThat(exposure()).isEqualByComparingTo("7000000");

        orders.recordShipmentDelivered(order.orderId(), shipment, accountant);
        Await.until("the receivable opens", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.receivable WHERE order_id = ?", Long.class, order.orderId()) == 1);
        Map<String, Object> receivable = jdbc.queryForMap("SELECT amount::bigint AS amount, kind, collector, due_date "
                + "FROM payment.receivable WHERE order_id = ?", order.orderId());
        assertThat(receivable).containsEntry("amount", 7_000_000L).containsEntry("kind", "DEPOSIT_BALANCE")
                .containsEntry("collector", "CUSTOMER");
        assertThat(receivable.get("due_date").toString()).isEqualTo(TODAY.plusDays(30).toString());
        assertThat(reload(order).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(creditChecks.undeliveredCreditExposure(customer, null)).as("nothing left on the order's side")
                .isZero();
        assertThat(exposure()).as("the receivable only - counted once").isEqualByComparingTo("7000000");
    }

    @Test
    @DisplayName("over the limit, ON_CREDIT asked for: 409 BALANCE_CREDIT_LIMIT_EXCEEDED, no order, no hold; with other "
            + "credit already used, a balance that alone fits is refused too; BEFORE_DELIVERY is placed instead")
    void overTheLimitIsRefused() {
        UUID request = UUID.randomUUID();
        int heldBefore = jdbc.queryForObject("SELECT count(*)::int FROM inventory.stock_reservation WHERE status = "
                + "'HELD'", Integer.class);
        Throwable refused = catchThrowable(() -> orders.placeOrder(new PlaceOrderCommand(request, customer, null, null,
                false, List.of(new PlaceOrderCommand.Line(SOFA, 6, Money.vnd(PRICE), null)), PaymentTerm.DEPOSIT,
                null, BalancePolicy.ON_CREDIT)));                        // 30M, balance 21M > 20M
        assertThat(code(refused)).isEqualTo(ErrorCode.BALANCE_CREDIT_LIMIT_EXCEEDED);
        assertThat(refused.getMessage()).contains("21000000").contains("20000000");
        assertThat(heldFor(request)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM inventory.stock_reservation WHERE status = 'HELD'",
                Integer.class)).as("nothing held").isEqualTo(heldBefore);

        OrderSummary credit = place(PaymentTerm.CREDIT, 3, null);         // 15M on credit, confirmed
        assertThat(credit.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(code(catchThrowable(() -> place(PaymentTerm.DEPOSIT, 2, BalancePolicy.ON_CREDIT)))).as("15M + 7M > 20M")
                .isEqualTo(ErrorCode.BALANCE_CREDIT_LIMIT_EXCEEDED);
        OrderSummary paidFirst = place(PaymentTerm.DEPOSIT, 2, BalancePolicy.BEFORE_DELIVERY);
        assertThat(paidFirst.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(exposure()).isEqualByComparingTo("15000000");
    }

    @Test
    @DisplayName("an overdue customer gets no balance on credit (409 CUSTOMER_CREDIT_OVERDUE); a customer without "
            + "credit cannot ask for it (409 BALANCE_POLICY_NOT_ALLOWED), nor be given it as their terms")
    void overdueOrNoCredit() {
        OrderSummary earlier = place(PaymentTerm.PREPAID, 1, null);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.receivable (id, order_id, customer_id, amount, paid_amount, currency, issued_at,
                    due_date, status)
                VALUES (gen_random_uuid(), ?, ?, 1000000, 0, 'VND', NOW() - INTERVAL '40 days', CURRENT_DATE - 10,
                       'OVERDUE')""", earlier.orderId(), customer));
        assertThat(payments.creditPosition(customer).hasOverdue()).isTrue();
        assertThat(code(catchThrowable(() -> place(PaymentTerm.DEPOSIT, 1, BalancePolicy.ON_CREDIT))))
                .isEqualTo(ErrorCode.CUSTOMER_CREDIT_OVERDUE);
        // SCRUM-193 (CREDIT-PORTAL): ON_CREDIT from the customer's terms is refused too - no quiet fallback (it was #150
        // item 3); choosing BEFORE_DELIVERY explicitly is the way out.
        assertThat(code(catchThrowable(() -> place(PaymentTerm.DEPOSIT, 1, null))))
                .isEqualTo(ErrorCode.CUSTOMER_CREDIT_OVERDUE);
        OrderSummary chosen = place(PaymentTerm.DEPOSIT, 1, BalancePolicy.BEFORE_DELIVERY);
        assertThat(chosen.payment().balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
        assertThat(chosen.payment().creditTermDays()).isNull();

        UUID cash = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID other = customer;
        customer = cash;
        terms(cash, false, null, DepositBalancePolicy.BEFORE_DELIVERY);
        assertThat(code(catchThrowable(() -> place(PaymentTerm.DEPOSIT, 1, BalancePolicy.ON_CREDIT))))
                .isEqualTo(ErrorCode.BALANCE_POLICY_NOT_ALLOWED);
        assertThat(code(catchThrowable(() -> terms(cash, false, null, DepositBalancePolicy.ON_CREDIT))))
                .isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        customer = other;
    }

    @Test
    @DisplayName("SCRUM-193: the customer's default ON_CREDIT over the limit is refused like Sales' explicit one - "
            + "409 BALANCE_CREDIT_LIMIT_EXCEEDED, nothing placed; it no longer falls back to BEFORE_DELIVERY")
    void defaultOnCreditIsRefused() {
        UUID request = UUID.randomUUID();
        Throwable refused = catchThrowable(() -> orders.placeOrder(new PlaceOrderCommand(request, customer, null, null,
                false, List.of(new PlaceOrderCommand.Line(SOFA, 6, Money.vnd(PRICE), null)), PaymentTerm.DEPOSIT,
                null, null)));                                           // 30M, balance 21M > 20M, policy from the terms
        assertThat(code(refused)).isEqualTo(ErrorCode.BALANCE_CREDIT_LIMIT_EXCEEDED);
        assertThat(heldFor(request)).isZero();
        assertThat(exposure()).as("nothing on credit").isZero();
        assertThat(code(catchThrowable(() -> place(PaymentTerm.DEPOSIT, 6, BalancePolicy.ON_CREDIT))))
                .isEqualTo(ErrorCode.BALANCE_CREDIT_LIMIT_EXCEEDED);
        // the way out: pay the balance before delivery
        OrderSummary order = place(PaymentTerm.DEPOSIT, 6, BalancePolicy.BEFORE_DELIVERY);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.payment().balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
        assertThat(order.payment().creditTermDays()).isNull();
    }

    @Test
    @DisplayName("SCRUM-193: converting an accepted quote has no balance policy to choose, so the customer's default "
            + "ON_CREDIT over the limit still gives way to BEFORE_DELIVERY there (and only there)")
    void convertedQuoteStillFallsBack() {
        OrderSummary order = quotedOrders.placeQuotedOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null,
                false, List.of(new PlaceOrderCommand.Line(SOFA, 6, Money.vnd(PRICE), null)), PaymentTerm.DEPOSIT, null,
                null));
        placed.add(order.orderId());
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.payment().balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
        assertThat(order.payment().creditTermDays()).isNull();
    }

    @Test
    @DisplayName("SCRUM-193: the refusal's details carry the credit left (limit - owed, never below 0), the limit, what "
            + "is owed, the balance asked for and the currency")
    void refusalCarriesTheCreditLeft() {
        place(PaymentTerm.CREDIT, 3, null);                                // 15M on credit: 5M of the 20M left
        BusinessException refused = (BusinessException) catchThrowable(() -> place(PaymentTerm.DEPOSIT, 2, null));
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.BALANCE_CREDIT_LIMIT_EXCEEDED);   // balance 7M > 5M
        assertThat(refused.details()).containsEntry("currency", "VND");
        assertThat((BigDecimal) refused.details().get("availableCredit")).isEqualByComparingTo("5000000");
        assertThat((BigDecimal) refused.details().get("creditLimit")).isEqualByComparingTo("20000000");
        assertThat((BigDecimal) refused.details().get("exposure")).isEqualByComparingTo("15000000");
        assertThat((BigDecimal) refused.details().get("requiredAmount")).isEqualByComparingTo("7000000");
    }

    @Test
    @DisplayName("review of #150, item 6: an ON_CREDIT deposit order is not released while its customer is overdue "
            + "(409 CUSTOMER_CREDIT_OVERDUE)")
    void releaseRefusedWhenOverdue() {
        OrderSummary order = place(PaymentTerm.DEPOSIT, 2, BalancePolicy.ON_CREDIT);
        receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer, "FT" + UUID.randomUUID()
                .toString().replace("-", "").substring(0, 12), BigDecimal.valueOf(3_000_000), "VND", TODAY, null,
                "deposit", accountant, List.of(new ReceivableService.OrderPayment(order.orderId(),
                BigDecimal.valueOf(3_000_000)))));
        Await.until("deposit confirms it", ASYNC, () -> reload(order).status() == OrderStatus.CONFIRMED);
        OrderSummary earlier = place(PaymentTerm.PREPAID, 1, null);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.receivable (id, order_id, customer_id, amount, paid_amount, currency, issued_at,
                    due_date, status)
                VALUES (gen_random_uuid(), ?, ?, 1000000, 0, 'VND', NOW() - INTERVAL '40 days', CURRENT_DATE - 10,
                       'OVERDUE')""", earlier.orderId(), customer));
        assertThat(code(catchThrowable(() -> releases.release(order.orderId(), DemoData.WAREHOUSE_HCM, accountant))))
                .isEqualTo(ErrorCode.CUSTOMER_CREDIT_OVERDUE);
        assertThat(reload(order).status()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
