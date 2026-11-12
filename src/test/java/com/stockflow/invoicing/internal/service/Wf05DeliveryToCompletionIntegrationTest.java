package com.stockflow.invoicing.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.WindowChangeReason;
import com.stockflow.order.internal.service.DeliveryBatchService;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.OrderSettlementService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.support.Await;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-05 DS-05a end to end (plan §4, §7; kltn-docs 17 §4.6, 21) against the real database, the real order, payment and
 * invoicing modules and the sandbox provider, in the order the business does it: the contract is signed, its two
 * delivery batches are delivered, each delivery is drawn up as a draft e-invoice, the accountant issues them, the
 * installments after delivery fall due and are paid, the seven days of the return window pass - and only then the
 * order and its contract are COMPLETED.
 *
 * <p>Nothing is written where a module would write it: the contract is drawn up, sent and signed through
 * {@link ConfirmedOrders}, the money comes in as receipts, the goods leave through {@link OrderService}, the drafts
 * come from the listener (awaited, never slept for), the invoices are issued through {@link EInvoiceIssuance}, the
 * order is completed by {@link OrderSettlementService} - what the nightly job calls. The one thing a test cannot wait
 * for is time: the return window is passed by moving {@code delivered_at} back eight days.</p>
 *
 * <p>The goods: 4 cartons of 50 at 2,000 a piece, 10% VAT - 400,000 + 40,000 = 440,000 - two cartons a batch. The
 * money: 40% on signing (176,000), 30% ten days after batch 1 (132,000), 30% twenty days after the whole order
 * (132,000).</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class Wf05DeliveryToCompletionIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final long PRICE = 2_000;

    @Autowired OrderService orders;
    @Autowired OrderSettlementService settlements;
    @Autowired EInvoiceIssuance issuance;
    @Autowired EInvoiceQueryService queries;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID staff;
    private UUID accountant;
    private CustomerSkuRows.Issued sku;
    private UUID orderId;

    @BeforeEach
    void aCustomerWithCreditAndALegalProfile() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        confirmedOrders.legalProfile(customer);
        String taxCode = "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customer.customer SET tax_code = ? WHERE id = ?", taxCode, customer);
            jdbc.update("UPDATE customer.customer_legal_profile SET invoice_email = ? WHERE customer_id = ?",
                    "ke.toan@" + customer + ".example.com", customer);
            // What is paid after delivery is on the customer's credit, approved by accounting (15 BR-11).
            jdbc.update("""
                    INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                                                         approved_at)
                    VALUES (?, ?, 10000000000, 30, ?, NOW())""", Identifiers.newId(), customer, accountant);
        });
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

    private OrderStatus status() {
        return orders.findById(orderId).orElseThrow().status();
    }

    private String contractStatus() {
        return jdbc.queryForObject("SELECT status FROM ordering.sales_contract WHERE order_id = ?", String.class,
                orderId);
    }

    private String installment(int seq, String column) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM payment.installment WHERE order_id = ? AND seq = ?",
                String.class, orderId, seq);
    }

    /** The two cartons of the batch in flight packed and handed over on a shipment of their own. */
    private UUID handOverTwoCartons() {
        orders.markPacked(orderId, staff);
        OrderSummary order = orders.findById(orderId).orElseThrow();
        UUID shipment = ShipmentRows.ready(jdbc, tx, orderId, UUID.randomUUID());
        orders.recordHandover(orderId, new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), 2 * 50)), Instant.now(), staff));
        return shipment;
    }

    /** The draft the listener drew up for a delivered shipment. */
    private EInvoice.Snapshot draftOf(UUID shipment) {
        Await.until("the draft e-invoice of shipment " + shipment, ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ? AND type = 'ORIGINAL'", Long.class,
                shipment) == 1);
        return queries.get(jdbc.queryForObject("SELECT id FROM invoicing.einvoice WHERE shipment_id = ?", UUID.class,
                shipment));
    }

    private void pay(int seq) {
        confirmedOrders.pay(customer, UUID.fromString(installment(seq, "id")), new BigDecimal(installment(seq, "amount")));
    }

    @Test
    @DisplayName("DS-05a: signed, both batches delivered, a draft per shipment, issued, the installments after delivery "
            + "due then paid, seven days on - the order and its contract are COMPLETED, and not a step earlier")
    void fromTheSignatureToCompleted() {
        // 1. The contract is signed: 40% on signing, 30% after batch 1, 30% after the whole order; two batches.
        ConfirmedOrders.Signed signed = confirmedOrders.sign(confirmedOrders.draft(customer,
                List.of(ConfirmedOrders.Line.cartons(sku, 4, PRICE).withTax(BigDecimal.TEN))), List.of(
                new SalesContracts.Installment("Deposit", "ON_SIGNING", null, new BigDecimal("40"), 0, null),
                new SalesContracts.Installment("After batch 1", "AFTER_DELIVERY", null, new BigDecimal("30"), 10, 1),
                new SalesContracts.Installment("After all", "AFTER_DELIVERY", null, new BigDecimal("30"), 20, null)), 2);
        orderId = signed.orderId();
        assertThat(contractStatus()).isEqualTo("SIGNED");
        assertThat(signed.total()).isEqualByComparingTo("440000");
        assertThat(status()).as("the deposit on signing is awaited").isEqualTo(OrderStatus.PENDING_PAYMENT);
        confirmedOrders.pay(signed);
        assertThat(status()).isEqualTo(OrderStatus.CONFIRMED);

        // 2. Batch 1 is released, picked, handed over and delivered.
        confirmedOrders.release(orderId, DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(orderId, staff);
        UUID first = handOverTwoCartons();
        assertThat(status()).as("batch 2 still to come").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        orders.recordShipmentDelivered(orderId, first, staff);
        assertThat(status()).as("half of it delivered").isNotIn(OrderStatus.DELIVERED, OrderStatus.COMPLETED);

        // 3. Its delivery is drawn up as a draft; 4. the accountant issues it.
        EInvoice.Snapshot firstDraft = draftOf(first);
        assertThat(firstDraft.status()).isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(firstDraft.contractNumber()).isEqualTo(signed.contractNumber());
        assertThat(firstDraft.grandTotal()).as("two cartons at 100,000 and 10%").isEqualByComparingTo("220000");
        assertThat(firstDraft.warnings()).isEmpty();
        EInvoice.Snapshot firstIssued = FirstSend.issue(issuance, queries, firstDraft.id(), firstDraft.version(), accountant);
        assertThat(firstIssued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(firstIssued.provider()).isEqualTo("SANDBOX");
        // Customer e-mail is off here (CUSTOMER_EMAIL_ENABLED): the invoice's mail is recorded as skipped, not sent.
        String mail = "customer-mail:einvoice.issued:" + firstIssued.id();
        Await.until("the invoice's e-mail is settled", ASYNC, () -> jdbc.queryForObject("SELECT count(*) FROM "
                + "notification.delivery_log WHERE operation_reference = ?", Long.class, mail) == 1);
        assertThat(jdbc.queryForObject("SELECT status || ':' || error FROM notification.delivery_log WHERE "
                + "operation_reference = ?", String.class, mail)).isEqualTo("SKIPPED:CUSTOMER_EMAIL_DISABLED");
        assertThat(context.getBean(com.stockflow.notification.internal.service.RecordingEmailTransport.class)
                .sentTo("ke.toan@" + customer + ".example.com")).as("nothing reaches the buyer's mailbox").isEmpty();

        // 5. The installment after batch 1 fell due with that delivery; the one after the whole order waits.
        Await.until("batch 1's installment after delivery due", ASYNC, () -> "DUE".equals(installment(2, "status")));
        assertThat(installment(3, "status")).isEqualTo("NOT_DUE");
        assertThat(settlements.complete(orderId)).as("an order still on its way is not completed").isFalse();

        // 2 again. Batch 2: released once batch 1 left, its window moved to today with the customer's consent.
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
        assertThat(status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(orders.recordShipmentDelivered(orderId, second, staff).status()).isEqualTo(OrderStatus.DELIVERED);

        // 3, 4 again: the second delivery's draft, issued under the next number.
        EInvoice.Snapshot secondDraft = draftOf(second);
        assertThat(secondDraft.id()).isNotEqualTo(firstDraft.id());
        assertThat(secondDraft.grandTotal()).isEqualByComparingTo("220000");
        assertThat(jdbc.queryForObject("SELECT sum(grand_total) FROM invoicing.einvoice WHERE order_id = ?",
                BigDecimal.class, orderId)).as("the invoices of the deliveries add up to the contract")
                .isEqualByComparingTo(signed.total());
        EInvoice.Snapshot secondIssued = FirstSend.issue(issuance, queries, secondDraft.id(), secondDraft.version(), accountant);
        assertThat(secondIssued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(secondIssued.symbol()).isEqualTo(firstIssued.symbol());
        assertThat(secondIssued.invoiceNumber()).isGreaterThan(firstIssued.invoiceNumber());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice WHERE order_id = ? AND status = "
                + "'ISSUED' AND type = 'ORIGINAL'", Long.class, orderId)).as("one issued invoice per delivered shipment")
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM ordering.order_shipment WHERE order_id = ? AND "
                        + "status = 'DELIVERED'", Long.class, orderId)).isEqualTo(2L);

        // 5. Delivered in full: the last installment is due. Every invoice issued, and still the order owes.
        Await.until("the installment after the whole order due", ASYNC, () -> "DUE".equals(installment(3, "status")));
        assertThat(settlements.complete(orderId)).as("inside the return window, and owing").isFalse();
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET delivered_at = NOW() - interval "
                + "'8 days' WHERE id = ?", orderId));
        assertThat(settlements.completionCandidates(0, 10_000)).contains(orderId);
        assertThat(settlements.complete(orderId)).as("17 §4.6 step 21: two installments unpaid").isFalse();
        pay(2);
        assertThat(settlements.complete(orderId)).as("one installment unpaid").isFalse();
        assertThat(status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(contractStatus()).isEqualTo("SIGNED");
        pay(3);
        assertThat(installment(2, "status")).isEqualTo("PAID");
        assertThat(installment(3, "status")).isEqualTo("PAID");

        // 6. The seven days have passed (delivered_at above); 7. the order and its contract are COMPLETED together.
        assertThat(settlements.complete(orderId)).as("all five conditions hold").isTrue();
        assertThat(status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(contractStatus()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM ordering.sales_contract WHERE order_id = ?",
                Boolean.class, orderId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT string_agg(to_status, '>' ORDER BY occurred_at, id) FROM "
                + "ordering.order_status_history WHERE order_id = ?", String.class, orderId))
                .endsWith("PACKED>PARTIALLY_FULFILLED>PACKED>SHIPPED>DELIVERED>COMPLETED");
        assertThat(settlements.complete(orderId)).as("completed once").isFalse();
        // The invoices are what they were issued as: completing the order touches neither.
        assertThat(queries.get(firstDraft.id())).isEqualTo(firstIssued);
        assertThat(queries.get(secondDraft.id())).isEqualTo(secondIssued);
    }
}
