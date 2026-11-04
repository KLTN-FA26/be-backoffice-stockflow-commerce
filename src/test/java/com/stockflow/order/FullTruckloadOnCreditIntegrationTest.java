package com.stockflow.order;

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
import com.stockflow.order.api.DeliveryMode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.order.internal.service.DeliveryModes;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.payment.internal.service.ReceivableService;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ShipmentRows;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * FTL-CREDIT (owner decision, SCRUM-193): a customer on credit terms is exempt from "fully paid before the handover"
 * as long as the order is within their limit - a full-truckload order on CREDIT is checked against the limit and held
 * like any other credit order, and its goods leave unpaid; a full-truckload order on prepaid or deposit terms still
 * does not leave with its balance unpaid (15 §4.1-4.2, G3-Q4). Against a real database.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class FullTruckloadOnCreditIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final long PRICE = 5_000_000;
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired DeliveryModes deliveryModes;
    @Autowired CreditHoldService creditHolds;
    @Autowired CustomerService customers;
    @Autowired ReceivableService receivables;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private UUID customer;
    private UUID staff;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void aCustomerWithCreditAndDeposits() {
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, true, true, CommercialTerm.CREDIT,
                new BigDecimal("30"), new BigDecimal("20000000"), 30, "test terms", staff, null,
                DepositBalancePolicy.BEFORE_DELIVERY));
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

    private OrderSummary place(PaymentTerm term, int units) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, units, Money.vnd(PRICE), null)), term, null));
        placed.add(order.orderId());
        return order;
    }

    private OrderSummary reload(UUID orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    /** Released to HCM, picked and packed. */
    private void packed(UUID orderId) {
        releases.release(orderId, DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(orderId, staff);
        orders.markPacked(orderId, staff);
    }

    private OrderSummary handOver(OrderSummary order, int units) {
        UUID shipment = ShipmentRows.ready(jdbc, tx, order.orderId(), UUID.randomUUID());
        return orders.recordHandover(order.orderId(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), units)), Instant.now(), staff));
    }

    private void payDeposit(OrderSummary order, long amount) {
        receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer, "FT" + UUID.randomUUID()
                .toString().replace("-", "").substring(0, 12), BigDecimal.valueOf(amount), "VND", TODAY, null, "payment",
                staff, List.of(new ReceivableService.OrderPayment(order.orderId(), BigDecimal.valueOf(amount)))));
    }

    @Test
    @DisplayName("FTL-CREDIT: a full-truckload order on CREDIT within the limit is CONFIRMED by the credit check, takes "
            + "the mode, and its goods leave with nothing paid")
    void creditFullTruckloadLeavesUnpaid() {
        OrderSummary order = place(PaymentTerm.CREDIT, 2);                  // 10M of the 20M limit
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);

        OrderSummary ftl = deliveryModes.change(order.orderId(), DeliveryMode.FULL_TRUCKLOAD, "whole truck", staff);
        assertThat(ftl.lifecycle().deliveryMode()).isEqualTo(DeliveryMode.FULL_TRUCKLOAD);
        assertThat(ftl.status()).isEqualTo(OrderStatus.CONFIRMED);

        packed(order.orderId());
        assertThat(reload(order.orderId()).lifecycle().amountDueBeforeHandover()).as("credit owes on its due date").isZero();
        OrderSummary shipped = handOver(order, 2);
        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(shipped.lifecycle().deliveryMode()).isEqualTo(DeliveryMode.FULL_TRUCKLOAD);
    }

    @Test
    @DisplayName("FTL-CREDIT: over the limit the full-truckload credit order is held like any other, and approval "
            + "confirms it with the mode kept")
    void creditFullTruckloadOverTheLimitIsHeld() {
        OrderSummary order = place(PaymentTerm.CREDIT, 5);                  // 25M > 20M
        assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
        deliveryModes.change(order.orderId(), DeliveryMode.FULL_TRUCKLOAD, "whole truck", staff);
        assertThat(reload(order.orderId()).status()).as("the mode does not lift the hold").isEqualTo(OrderStatus.ON_HOLD);

        OrderSummary approved = creditHolds.approve(order.orderId(), "approved over the limit", staff);
        assertThat(approved.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(approved.lifecycle().deliveryMode()).isEqualTo(DeliveryMode.FULL_TRUCKLOAD);
    }

    @Test
    @DisplayName("FTL-CREDIT: a full-truckload DEPOSIT order paid before delivery still does not leave with its balance "
            + "unpaid (409 ORDER_BALANCE_NOT_PAID); once the balance is in it does")
    void depositFullTruckloadStillLeavesPaid() {
        OrderSummary order = place(PaymentTerm.DEPOSIT, 2);                 // 10M, deposit 3M, balance 7M before delivery
        assertThat(order.payment().balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
        deliveryModes.change(order.orderId(), DeliveryMode.FULL_TRUCKLOAD, "whole truck", staff);
        payDeposit(order, 3_000_000);
        Await.until("deposit confirms it", ASYNC, () -> reload(order.orderId()).status() == OrderStatus.CONFIRMED);
        packed(order.orderId());

        assertThat(reload(order.orderId()).lifecycle().amountDueBeforeHandover()).isEqualByComparingTo("7000000");
        Throwable refused = catchThrowable(() -> handOver(reload(order.orderId()), 2));
        assertThat(refused).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) refused).errorCode()).isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);

        payDeposit(order, 7_000_000);
        Await.until("the balance is counted", ASYNC,
                () -> reload(order.orderId()).lifecycle().amountDueBeforeHandover().signum() == 0);
        assertThat(handOver(reload(order.orderId()), 2).status()).isEqualTo(OrderStatus.SHIPPED);
    }
}
