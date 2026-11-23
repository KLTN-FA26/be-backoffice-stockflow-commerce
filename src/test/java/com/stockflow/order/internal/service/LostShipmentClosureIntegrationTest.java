package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.WindowChangeReason;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.support.ChatRows;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-05 F-5 against the real database (Q-12, Q-2, Q-19, Q-3; BE-17): a shipment the carrier lost, closed by Sales
 * through {@link LostShipmentClosures} under the order's lock - settled like a carrier return (kind
 * {@code SHIPMENT_RETURNED}), its share of the order's shipping fee waived by payment (owner 23/11), the closure on
 * record with the carrier's confirmation, and the installment after delivery reached from the closure once nothing
 * more is coming.
 *
 * <p>The goods: 4 cartons of 50 at 2,000, no VAT (400,000), two cartons a batch; a shipping fee line of 100,000 + 10%
 * (110,000). The money: 50% on signing (paid), 50% ten days after the whole order. Batch 1 is delivered; batch 2 fails,
 * LOST. Its goods are half the order's goods, so half the shipping fee - 55,000 - is waived with them.</p>
 *
 * <p>The evidence file's storage is not what this tests: the closure is given a stored key, as
 * {@code LostShipmentClosures.close} hands it on once the upload is kept.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false",
        // BE-45 (audit r2 contract M-6): no closing delay, so the order ended by the carrier is a closing candidate at
        // once and the candidate query is run against the real schema.
        "stockflow.order.close-after-days=0"})
class LostShipmentClosureIntegrationTest {

    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired InstallmentOrders installmentOrders;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;

    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID staff;
    private CustomerSkuRows.Issued sku;
    private UUID orderId;

    @BeforeEach
    void aCustomerWithCredit() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        confirmedOrders.legalProfile(customer);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                                                     approved_at)
                VALUES (?, ?, 10000000000, 30, ?, NOW())""", Identifiers.newId(), customer, accountant));
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50, "STANDARD", true);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 4 * 50);
    }

    @AfterEach
    void putTheStockBack() {
        if (orderId == null) {
            return;
        }
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> jdbc.queryForList(
                "SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'", UUID.class, orderId)
                .forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE")));
    }

    private UUID handOverTwoCartons() {
        orders.markPacked(orderId, staff);
        OrderSummary order = orders.findById(orderId).orElseThrow();
        UUID shipment = ShipmentRows.ready(jdbc, tx, orderId, UUID.randomUUID());
        orders.recordHandover(orderId, new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), 2 * 50)), Instant.now(), staff));
        return shipment;
    }

    private String statusHistory() {
        return String.join(">", jdbc.queryForList("""
                SELECT to_status FROM ordering.order_status_history WHERE order_id = ? ORDER BY occurred_at, id""",
                String.class, orderId));
    }

    private String installment(int seq, String column) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM payment.installment WHERE order_id = ? AND seq = ?",
                String.class, orderId, seq);
    }

    private static ErrorCode code(Runnable action) {
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(action::run);
        assertThat(failure).isInstanceOf(BusinessException.class);
        return ((BusinessException) failure).errorCode();
    }

    @Test
    @DisplayName("Q-12 / Q-2 / Q-19: batch 2 lost and closed by Sales - refused on a changed order, a delivered "
            + "shipment, a second time; settled as SHIPMENT_RETURNED with half the shipping fee waived (55,000 of "
            + "110,000); on record with its evidence; the order delivered in full from the closure, its installment after "
            + "delivery settled, and the order left PARTIALLY_FULFILLED (nothing refunded, BE-45); the database keeps the "
            + "closure as it was")
    void aLostBatchWaivesItsShareOfTheShippingFee() {
        UUID draft = confirmedOrders.draft(customer, List.of(ConfirmedOrders.Line.cartons(sku, 4, 2_000)));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.order_fee_line (id, order_id, kind, sku, amount, tax_rate, tax_amount, currency)
                VALUES (?, ?, 'SHIPPING_FEE', NULL, 100000, 10, 10000, 'VND')""", Identifiers.newId(), draft));
        // The order's totals carry the fee line, as placing it would have.
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE ordering.customer_order SET total_amount = total_amount + 110000, tax_amount = tax_amount + 10000
                 WHERE id = ?""", draft));
        ConfirmedOrders.Signed signed = confirmedOrders.sign(draft, List.of(
                new SalesContracts.Installment("Deposit", "ON_SIGNING", null, new BigDecimal("50"), 0, null),
                new SalesContracts.Installment("After all", "AFTER_DELIVERY", null, new BigDecimal("50"), 10, null)),
                2);
        orderId = signed.orderId();
        assertThat(signed.total()).isEqualByComparingTo("510000");
        confirmedOrders.pay(signed);

        confirmedOrders.release(orderId, DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(orderId, staff);
        UUID first = handOverTwoCartons();
        orders.recordShipmentDelivered(orderId, first, staff);

        WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL,
                () -> context.getBean(OrderReleases.class).release(orderId, DemoData.WAREHOUSE_HCM, staff));
        UUID secondBatch = jdbc.queryForObject("""
                SELECT b.id FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
                 WHERE c.order_id = ? AND b.seq = 2""", UUID.class, orderId);
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        UUID consent = ChatRows.message(jdbc, tx, customer, orderId, "CUSTOMER");
        WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL, () -> context.getBean(DeliveryBatchService.class)
                .changeWindow(confirmedOrders.sales(), orderId, secondBatch, new DeliveryBatchService.WindowChange(today,
                        today.plusDays(2), WindowChangeReason.CUSTOMER_REQUEST, "The customer takes it now", null,
                        consent)));
        UUID second = handOverTwoCartons();
        orders.recordShipmentDeliveryFailed(orderId, second, DeliveryFailureReason.LOST, null, staff);
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.DELIVERY_FAILED);
        assertThat(installmentOrders.deliveredInFullAt(orderId)).as("batch 2 is still to be decided").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE order_id = ?", Long.class,
                orderId)).isZero();

        LostShipmentClosures closures = context.getBean(LostShipmentClosures.class);
        long version = tx.execute(s -> orderRepository.findById(new OrderId(orderId)).orElseThrow().version());
        assertThat(code(() -> tx.execute(s -> closures.closeLocked(staff, orderId, second, "Carrier confirmed the loss",
                "orders/lost/confirmation.pdf", "confirmation.pdf", version - 1))))
                .as("a closure on an order changed since it was read").isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(code(() -> tx.execute(s -> closures.closeLocked(staff, orderId, first, "Not lost at all",
                "orders/lost/confirmation.pdf", "confirmation.pdf", null))))
                .as("a delivered shipment is not closed as lost").isEqualTo(ErrorCode.ORDER_SHIPMENT_NOT_LOST_CLOSABLE);

        // Sales close the lost batch.
        tx.execute(s -> closures.closeLocked(staff, orderId, second, "Carrier confirmed the loss",
                "orders/lost/confirmation.pdf", "confirmation.pdf", version));
        Instant closedAt = jdbc.queryForObject("SELECT units_closed_at FROM ordering.order_shipment WHERE id = ?",
                java.sql.Timestamp.class, second).toInstant();
        assertThat(jdbc.queryForObject("SELECT note || ' ' || evidence_key || ' ' || closure_id::text FROM "
                + "ordering.order_shipment_loss_closure WHERE shipment_id = ?", String.class, second))
                .as("the closure on record with the carrier's confirmation and the key payment settled it under")
                .isEqualTo("Carrier confirmed the loss orders/lost/confirmation.pdf " + Order.shipmentClosureId(second));
        assertThat(code(() -> tx.execute(s -> closures.closeLocked(staff, orderId, second, "Again",
                "orders/lost/confirmation.pdf", "confirmation.pdf", null))))
                .as("closed once").isEqualTo(ErrorCode.ORDER_SHIPMENT_NOT_LOST_CLOSABLE);

        // Batch 1 arrived, batch 2 was lost (PARTIALLY_FULFILLED); nothing is refundable, so nothing was refunded: it
        // is not "Hoàn một phần" (WF-05 BE-45, audit r2 contract M-6) - it stays PARTIALLY_FULFILLED and the nightly
        // settlement closes it from there (Order.closeWithoutRefund).
        assertThat(statusHistory()).endsWith("DELIVERY_FAILED>PARTIALLY_FULFILLED");
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        OrderSettlementService settlement = context.getBean(OrderSettlementService.class);
        assertThat(settlement.closingCandidates(0, 1000)).as("a closing candidate from PARTIALLY_FULFILLED")
                .contains(orderId);
        SettlementReadiness.View readiness = settlement.readiness(orderId).orElseThrow().view();
        assertThat(readiness.branch()).isEqualTo(SettlementReadiness.Branch.REFUND_CLOSE);
        assertThat(readiness.blockers()).as("batch 1's return window is still open")
                .contains(SettlementReadiness.Blocker.RETURN_WINDOW_OPEN);
        assertThat(settlement.close(orderId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT closed_goods_value::bigint || '/' || shipping_fee_waived::bigint FROM "
                + "ordering.order_shipment WHERE id = ?", String.class, second))
                .as("the goods closed, with VAT, and the waiver payment answered, kept for invoicing")
                .isEqualTo("200000/55000");
        assertThat(jdbc.queryForObject("SELECT kind || ' ' || closed_amount::bigint || ' ' "
                + "|| shipping_fee_waived_amount::bigint FROM payment.order_settlement WHERE order_id = ?", String.class,
                orderId)).as("the goods (200,000) and their share of the shipping fee").isEqualTo(
                "SHIPMENT_RETURNED 255000 55000");
        assertThat(installmentOrders.deliveredInFullAt(orderId)).as("delivered in full from the closure (Q-3)")
                .contains(closedAt);
        // The 255,000 closed (goods and waived fee) came off the unpaid installment after delivery whole: what the
        // customer paid on signing (255,000) is exactly what they owe for the 200,000 kept and the 55,000 of the fee
        // left - nothing to refund.
        assertThat(installment(2, "status") + " " + installment(2, "reduced_amount::bigint"))
                .isEqualTo("CANCELLED 255000");
        assertThat(jdbc.queryForObject("SELECT refundable_amount::bigint FROM payment.order_settlement "
                + "WHERE order_id = ?", Long.class, orderId)).isZero();
        // Stated twice: the closure is set once, and its waiver never above the fee.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.order_shipment SET shipping_fee_waived = 0 WHERE id = ?", second)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("stay closed");
        tx.executeWithoutResult(s -> jdbc.execute("ALTER TABLE ordering.order_shipment DISABLE TRIGGER "
                + "tg_order_shipment_units_closed"));
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                    "UPDATE ordering.order_shipment SET shipping_fee_waived = -1 WHERE id = ?", second)))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_order_shipment_closed_money");
        } finally {
            tx.executeWithoutResult(s -> jdbc.execute("ALTER TABLE ordering.order_shipment ENABLE TRIGGER "
                    + "tg_order_shipment_units_closed"));
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE ordering.order_shipment SET units_closed_at = NULL WHERE id = ?""", second)))
                .isInstanceOf(DataAccessException.class);
        assertThat(code(() -> orders.recordShipmentDelivered(orderId, second, staff)))
                .as("a lost shipment Sales closed is never delivered on this order").isEqualTo(
                        ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION);
    }
}
