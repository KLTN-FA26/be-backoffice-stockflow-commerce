package com.stockflow.payment;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.service.PaymentReceiptService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Audit r3 LM-3 against the real database: a contract paid before each batch, its rest closed before batch 2 was ever
 * released. Batch 2's installment before delivery is above batch 2's goods - it carries its share of the fee lines
 * (tied installments share the whole total by batch value) - and its milestone, the batch's release, never comes. The
 * closure takes the goods off it and carries what it still owes onto the closure's fee item, due from the closure's day,
 * so the order can be paid off, completed and closed; the receipt that paid part of it early is no longer voided.
 *
 * <p>The goods: 4 cartons of 50 at 2,000, no VAT (400,000), two cartons a batch; a shipping fee line of 100,000 + 10%
 * (110,000) - 510,000 in all, paid 50% before batch 1 and 50% before batch 2 (255,000 each). Batch 2's goods are
 * 200,000: 55,000 of its installment is the fee lines' share. The customer paid 20,000 of it early, so the closure
 * carries 35,000.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ClosedBatchFeeCarryIntegrationTest {

    @Autowired OrderService orders;
    @Autowired PaymentService payments;
    @Autowired PaymentReceiptService receipts;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;

    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID staff;
    private UUID accountant;
    private CustomerSkuRows.Issued sku;
    private UUID orderId;

    @BeforeEach
    void aCustomerWithCredit() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
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

    private Map<String, Object> installment(int seq) {
        return jdbc.queryForMap("""
                SELECT id, milestone, status, amount::bigint AS amount, paid_amount::bigint AS paid,
                       reduced_amount::bigint AS reduced, due_date
                  FROM payment.installment WHERE order_id = ? AND seq = ?""", orderId, seq);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("audit r3 LM-3: batch 2 closed before its release - what its installment before delivery still owed "
            + "above its goods is carried onto the closure's fee item, due today; the early receipt on it is not "
            + "voided; paid, the order owes nothing more")
    void aClosedBatchsFeeShareFallsDueOnTheFeeItem() {
        UUID draft = confirmedOrders.draft(customer, List.of(ConfirmedOrders.Line.cartons(sku, 4, 2_000)));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.order_fee_line (id, order_id, kind, sku, amount, tax_rate, tax_amount, currency)
                VALUES (?, ?, 'SHIPPING_FEE', NULL, 100000, 10, 10000, 'VND')""", Identifiers.newId(), draft));
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE ordering.customer_order SET total_amount = total_amount + 110000, tax_amount = tax_amount + 10000
                 WHERE id = ?""", draft));
        ConfirmedOrders.Signed signed = confirmedOrders.sign(draft, List.of(
                new SalesContracts.Installment("Before batch 1", "BEFORE_DELIVERY", null, new BigDecimal("50"), null,
                        1),
                new SalesContracts.Installment("Before batch 2", "BEFORE_DELIVERY", null, new BigDecimal("50"), null,
                        2)), 2);
        orderId = signed.orderId();
        assertThat(signed.total()).isEqualByComparingTo("510000");
        confirmedOrders.awaitStatus(orderId, OrderStatus.CONFIRMED);
        assertThat(installment(1).get("amount")).isEqualTo(255_000L);
        assertThat(installment(2).get("amount")).isEqualTo(255_000L);

        // Batch 1: released, its installment paid (the handover's gate, BR-08), handed over and delivered.
        confirmedOrders.release(orderId, DemoData.WAREHOUSE_HCM, staff);
        confirmedOrders.pay(customer, (UUID) installment(1).get("id"), new BigDecimal("255000"));
        orders.startPicking(orderId, staff);
        orders.markPacked(orderId, staff);
        OrderSummary order = orders.findById(orderId).orElseThrow();
        UUID shipment = ShipmentRows.ready(jdbc, tx, orderId, UUID.randomUUID());
        orders.recordHandover(orderId, new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), 2 * 50)), Instant.now(), staff));
        orders.recordShipmentDelivered(orderId, shipment, staff);
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        // 20,000 of batch 2's installment paid early (15 BR-05: a NOT_DUE installment may be paid).
        PaymentReceipt early = confirmedOrders.pay(customer, (UUID) installment(2).get("id"), new BigDecimal("20000"));
        assertThat(installment(2).get("status")).isEqualTo("PARTIALLY_PAID");

        // Sales close the rest before batch 2 was released.
        orders.closeRemainder(orderId, CancellationReasonCode.CUSTOMER_REQUEST, "Khach khong lay dot 2", staff);

        Map<String, Object> batch2 = installment(2);
        assertThat(batch2.get("status")).as("settled: paid 20,000, the rest taken off").isEqualTo("PAID");
        assertThat(batch2.get("reduced")).as("200,000 of goods and the 35,000 carried").isEqualTo(235_000L);
        Map<String, Object> fee = installment(21);
        assertThat(fee.get("milestone")).isEqualTo("CANCELLATION_FEE");
        assertThat(fee.get("amount")).as("batch 2's share of the fee lines it still owed").isEqualTo(35_000L);
        assertThat(fee.get("status")).isEqualTo("DUE");
        assertThat(fee.get("due_date")).as("due from the closure's day (15 §5.1)")
                .isEqualTo(java.sql.Date.valueOf(LocalDate.now(ConfirmedOrders.VIETNAM)));
        assertThat(jdbc.queryForObject("""
                SELECT closed_amount::bigint || '/' || reduced_amount::bigint || '/' || carried_to_fee_amount::bigint
                       || '/' || surplus_amount::bigint || '/' || refundable_amount::bigint || '/'
                       || fee_owed_amount::bigint || '/' || (fee_installment_id = ?)
                  FROM payment.order_settlement WHERE order_id = ? AND kind = 'REMAINDER_CLOSED'""", String.class,
                fee.get("id"), orderId)).isEqualTo("200000/235000/35000/0/0/0/true");
        assertThat(payments.installmentStanding(orderId).total().subtract(payments.installmentStanding(orderId).paid()))
                .as("the customer owes exactly what they owed before, on the fee item").isEqualByComparingTo("35000");

        // The early receipt is not voided: batch 2's installment would owe 20,000 again with no milestone, no due day.
        assertThat(receipts.receipt(early.id()).voidBlockedBy()).isEqualTo(ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED);
        assertThat(code(catchThrowable(() -> receipts.voidReceipt(new PaymentReceiptService.VoidReceiptCommand(
                early.id(), "Recorded against the wrong statement line", true, accountant)))))
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED);
        assertThat(catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE payment.payment_receipt SET status = 'VOIDED', voided_at = NOW(), voided_by = ?,
                       void_reason = 'by hand' WHERE id = ?""", accountant, early.id()))))
                .as("stated twice: tg_payment_receipt_void").hasMessageContaining("no milestone");

        // Paid, the order owes nothing more.
        confirmedOrders.pay(customer, (UUID) fee.get("id"), new BigDecimal("35000"));
        assertThat(installment(21).get("status")).isEqualTo("PAID");
        var standing = payments.installmentStanding(orderId);
        assertThat(standing.total()).isEqualByComparingTo(standing.paid());
    }
}
