package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * FTL-CREDIT (owner decision, SCRUM-193): every order ships as a full truckload (kltn-docs 09 BR-08), and what a
 * customer pays after delivery does not hold the goods back - checked against the credit limit when the contract is
 * signed and held like any other over it - while what the contract wants paid before delivery still does (15 BR-08,
 * G3-Q4). Against a real database.
 *
 * <p>Since 4B-10 the terms are the contract's installment plan, not the order's payment term: "on CREDIT" is a plan
 * paying after delivery, "DEPOSIT paid before delivery" one paying on signing and before delivery
 * ({@link ConfirmedOrders}). The handover reads the installments under the order's lock; what is due before it is
 * {@link PaymentService#installmentStanding}'s {@code dueBeforeHandover}, no longer the order's own figure.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class FullTruckloadOnCreditIntegrationTest {

    /** A piece of the customer's SKU; a carton is 50 of them: 5,000,000. */
    private static final long PRICE = 100_000;

    @Autowired OrderService orders;
    @Autowired CreditHoldService creditHolds;
    @Autowired CustomerService customers;
    @Autowired PaymentService payments;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID staff;
    private CustomerSkuRows.Issued sku;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void aCustomerWithCredit() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 500);
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("20000000"), 30, "test terms",
                staff, null));
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

    private static SalesContracts.Installment share(String name, String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    /** {@code cartons} cartons whose contract pays as {@code plan}, signed. */
    private ConfirmedOrders.Signed signed(int cartons, SalesContracts.Installment... plan) {
        ConfirmedOrders.Signed signed = confirmedOrders.signed(customer, List.of(plan),
                ConfirmedOrders.Line.cartons(sku, cartons, PRICE));
        placed.add(signed.orderId());
        return signed;
    }

    private OrderSummary reload(UUID orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    /** Shipping from HCM, released there, picked and packed. */
    private void packed(UUID orderId) {
        confirmedOrders.release(orderId, DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(orderId, staff);
        orders.markPacked(orderId, staff);
    }

    private OrderSummary handOver(OrderSummary order) {
        UUID shipment = ShipmentRows.ready(jdbc, tx, order.orderId(), UUID.randomUUID());
        var line = order.lines().getFirst();
        return orders.recordHandover(order.orderId(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(line.lineId(), line.quantity())), Instant.now(), staff));
    }

    @Test
    @DisplayName("FTL-CREDIT: paid after delivery within the limit, the order is CONFIRMED at signing and its goods "
            + "leave with nothing paid")
    void creditFullTruckloadLeavesUnpaid() {
        ConfirmedOrders.Signed signed = signed(2, share("After delivery", "AFTER_DELIVERY", "100", 30));  // 10M of 20M
        assertThat(reload(signed.orderId()).status()).isEqualTo(OrderStatus.CONFIRMED);

        packed(signed.orderId());
        assertThat(payments.installmentStanding(signed.orderId()).dueBeforeHandover())
                .as("credit owes on its due date").isZero();
        OrderSummary shipped = handOver(reload(signed.orderId()));
        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("FTL-CREDIT: over the limit the order is held at signing like any other, and approval confirms it")
    void creditFullTruckloadOverTheLimitIsHeld() {
        ConfirmedOrders.Signed signed = signed(5, share("After delivery", "AFTER_DELIVERY", "100", 30));  // 25M > 20M
        assertThat(reload(signed.orderId()).status()).isEqualTo(OrderStatus.ON_HOLD);

        OrderSummary approved = creditHolds.approve(signed.orderId(), "approved over the limit", staff);
        assertThat(approved.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("FTL-CREDIT: a deposit on signing with the rest before delivery still does not leave with the rest "
            + "unpaid (409 ORDER_BALANCE_NOT_PAID); once it is in, it does")
    void depositFullTruckloadStillLeavesPaid() {
        ConfirmedOrders.Signed signed = signed(2, share("Deposit", "ON_SIGNING", "30", 0),
                share("Before delivery", "BEFORE_DELIVERY", "70", null));                 // 10M: 3M, then 7M
        confirmedOrders.pay(signed);
        packed(signed.orderId());

        assertThat(payments.installmentStanding(signed.orderId()).dueBeforeHandover())
                .isEqualByComparingTo("7000000");
        Throwable refused = catchThrowable(() -> handOver(reload(signed.orderId())));
        assertThat(refused).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) refused).errorCode()).isEqualTo(ErrorCode.ORDER_BALANCE_NOT_PAID);

        confirmedOrders.pay(customer, signed.seq(2), new BigDecimal("7000000"));
        assertThat(payments.installmentStanding(signed.orderId()).dueBeforeHandover()).isZero();
        assertThat(handOver(reload(signed.orderId())).status()).isEqualTo(OrderStatus.SHIPPED);
    }
}
