package com.stockflow.invoicing.internal.service;

import com.stockflow.common.security.DataScope;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.service.OrderSettlementService;
import com.stockflow.support.Await;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fifth condition of completing an order (plan §4; kltn-docs 17 §4.6 step 21, 21): every delivered shipment has
 * an ISSUED e-invoice. Against the real database, with the other four conditions already holding in every test - all
 * of it delivered, the return window passed, no return open, every installment paid (the contract pays on signing) -
 * so the one thing that keeps the order DELIVERED is its invoices.
 *
 * <p>Only ISSUED counts: a draft, an invoice waiting at the provider, a rejected one and a voided draft all leave the
 * order where it is, with its contract SIGNED. The order is completed by {@link OrderSettlementService#complete}, what
 * the nightly job calls for every candidate; a refused order stays a candidate, so it is looked at again once its
 * invoices are issued.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class InvoicedBeforeCompletionIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final long PRICE = 2_000;

    @Autowired OrderService orders;
    @Autowired OrderSettlementService settlements;
    @Autowired EInvoiceDrafts drafts;
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
    private String taxCode;
    private CustomerSkuRows.Issued sku;
    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void aCustomerWithItsLegalProfile() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        taxCode = "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        confirmedOrders.legalProfile(customer);
        buyer(taxCode, "ke.toan@" + customer + ".example.com");
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50, "STANDARD", true);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 1_000);
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> jdbc.queryForList(
                "SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'", UUID.class, id)
                .forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE"))));
    }

    private void buyer(String taxCode, String invoiceEmail) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customer.customer SET tax_code = ? WHERE id = ?", taxCode, customer);
            jdbc.update("UPDATE customer.customer_legal_profile SET invoice_email = ? WHERE customer_id = ?",
                    invoiceEmail, customer);
        });
    }

    /** An order of {@code cartons} cartons paid on signing, released from HCM, picked and packed. */
    private OrderSummary packed(int cartons) {
        OrderSummary order = picking(cartons);
        return orders.markPacked(order.orderId(), staff);
    }

    /** An order of {@code cartons} cartons paid on signing, released from HCM and picking. */
    private OrderSummary picking(int cartons) {
        ConfirmedOrders.Confirmed confirmed = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.cartons(sku, cartons, PRICE).withTax(BigDecimal.TEN));
        placed.add(confirmed.orderId());
        confirmedOrders.release(confirmed.orderId(), DemoData.WAREHOUSE_HCM, staff);
        return orders.startPicking(confirmed.orderId(), staff);
    }

    /** {@code cartons} cartons of the order handed over on a shipment of their own and delivered; the shipment. */
    private UUID deliver(OrderSummary order, int cartons) {
        UUID shipment = ShipmentRows.ready(jdbc, tx, order.orderId(), UUID.randomUUID());
        orders.recordHandover(order.orderId(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), cartons * 50)), Instant.now(),
                staff));
        orders.recordShipmentDelivered(order.orderId(), shipment, staff);
        return shipment;
    }

    /** The original invoice in force for a shipment, awaited from the listener. */
    private EInvoice.Snapshot draftOf(UUID shipment) {
        String inForce = "SELECT id FROM invoicing.einvoice WHERE shipment_id = ? AND type = 'ORIGINAL' AND status "
                + "NOT IN ('VOIDED_DRAFT', 'REPLACED')";
        Await.until("the draft e-invoice of shipment " + shipment, ASYNC,
                () -> !jdbc.queryForList(inForce, UUID.class, shipment).isEmpty());
        return queries.get(jdbc.queryForObject(inForce, UUID.class, shipment));
    }

    /** The other four conditions: delivered in full eight days ago, nothing owed, no return open. */
    private void pastTheReturnWindow(UUID orderId) {
        assertThat(status(orderId)).isEqualTo(OrderStatus.DELIVERED);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET delivered_at = NOW() - interval "
                + "'8 days' WHERE id = ?", orderId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.installment WHERE order_id = ? AND status <> "
                + "'PAID'", Long.class, orderId)).as("every installment is paid").isZero();
        assertThat(settlements.completionCandidates(0, 10_000)).contains(orderId);
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private String contractStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM ordering.sales_contract WHERE order_id = ?", String.class,
                orderId);
    }

    private void staysDelivered(UUID orderId, String because) {
        assertThat(settlements.complete(orderId)).as(because).isFalse();
        assertThat(status(orderId)).as(because).isEqualTo(OrderStatus.DELIVERED);
        assertThat(contractStatus(orderId)).as(because).isEqualTo("SIGNED");
        assertThat(settlements.completionCandidates(0, 10_000)).as("looked at again the next night").contains(orderId);
    }

    private void completes(UUID orderId) {
        assertThat(settlements.complete(orderId)).as("all five conditions hold").isTrue();
        assertThat(status(orderId)).isEqualTo(OrderStatus.COMPLETED);
        assertThat(contractStatus(orderId)).as("the contract is completed with its order").isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a delivered shipment whose invoice is a draft keeps the order DELIVERED; issued, the order and its "
            + "contract are COMPLETED")
    void aDraftKeepsTheOrderDelivered() {
        OrderSummary order = packed(2);
        EInvoice.Snapshot draft = draftOf(deliver(order, 2));
        pastTheReturnWindow(order.orderId());

        staysDelivered(order.orderId(), "its one invoice is a draft");

        assertThat(issuance.issue(draft.id(), draft.version(), accountant).status()).isEqualTo(EInvoiceStatus.ISSUED);
        completes(order.orderId());
    }

    @Test
    @DisplayName("an invoice waiting at the provider is not issued yet: DELIVERED until the provider answers")
    void anInvoiceWaitingAtTheProviderKeepsTheOrderDelivered() {
        buyer(taxCode, "ke.toan+sandbox-reply-lost@" + customer + ".example.com");
        OrderSummary order = packed(1);
        EInvoice.Snapshot draft = draftOf(deliver(order, 1));
        pastTheReturnWindow(order.orderId());
        assertThat(issuance.issue(draft.id(), draft.version(), accountant).status())
                .isEqualTo(EInvoiceStatus.SUBMITTING);

        staysDelivered(order.orderId(), "its invoice is SUBMITTING");

        assertThat(issuance.reconcile(draft.id()).status()).isEqualTo(EInvoiceStatus.ISSUED);
        completes(order.orderId());
    }

    @Test
    @DisplayName("a rejected invoice, and the draft it goes back to, keep the order DELIVERED; corrected and issued, "
            + "it completes")
    void aRejectedInvoiceKeepsTheOrderDelivered() {
        buyer("0000000001", "ke.toan@" + customer + ".example.com");
        OrderSummary order = packed(1);
        EInvoice.Snapshot draft = draftOf(deliver(order, 1));
        pastTheReturnWindow(order.orderId());
        EInvoice.Snapshot rejected = issuance.issue(draft.id(), draft.version(), accountant);
        assertThat(rejected.status()).isEqualTo(EInvoiceStatus.REJECTED);

        staysDelivered(order.orderId(), "its invoice is REJECTED");

        EInvoice.Snapshot reopened = drafts.reopen(draft.id(), rejected.version());
        staysDelivered(order.orderId(), "its invoice is a draft again");
        EInvoice.Snapshot corrected = drafts.revise(new EInvoiceDrafts.Revise(draft.id(), reopened.version(), null,
                taxCode, null, null, null));
        assertThat(issuance.issue(draft.id(), corrected.version(), accountant).status())
                .isEqualTo(EInvoiceStatus.ISSUED);
        completes(order.orderId());
    }

    @Test
    @DisplayName("a voided draft is no invoice: DELIVERED with none in force, and until the one drawn up again is issued")
    void aVoidedDraftKeepsTheOrderDelivered() {
        OrderSummary order = packed(1);
        UUID shipment = deliver(order, 1);
        EInvoice.Snapshot draft = draftOf(shipment);
        pastTheReturnWindow(order.orderId());
        drafts.voidDraft(draft.id(), draft.version(), "the buyer asked for another name", accountant);

        staysDelivered(order.orderId(), "its shipment has no invoice in force");

        EInvoice.Snapshot again = drafts.drawUp(order.orderId(), shipment).invoice();
        staysDelivered(order.orderId(), "drawn up again, a draft");
        assertThat(issuance.issue(again.id(), again.version(), accountant).status()).isEqualTo(EInvoiceStatus.ISSUED);
        completes(order.orderId());
    }

    @Test
    @DisplayName("two delivered shipments: one issued is not enough - the order completes when the last one is issued")
    void everyDeliveredShipmentIsIssued() {
        OrderSummary order = picking(4);
        // Split shipments: Sales allowed them on the order (PACK-PARTIAL).
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", order.orderId()));
        EInvoice.Snapshot first = draftOf(deliver(order, 1));
        orders.markPacked(order.orderId(), staff);
        EInvoice.Snapshot second = draftOf(deliver(order, 3));
        pastTheReturnWindow(order.orderId());

        staysDelivered(order.orderId(), "neither invoice is issued");
        assertThat(issuance.issue(first.id(), first.version(), accountant).status()).isEqualTo(EInvoiceStatus.ISSUED);
        staysDelivered(order.orderId(), "one of two is issued");

        assertThat(issuance.issue(second.id(), second.version(), accountant).status())
                .isEqualTo(EInvoiceStatus.ISSUED);
        completes(order.orderId());
    }

    @Test
    @DisplayName("the invoices issued do not replace the other conditions: inside the return window the order waits")
    void issuedInsideTheReturnWindowStillWaits() {
        OrderSummary order = packed(1);
        EInvoice.Snapshot draft = draftOf(deliver(order, 1));
        assertThat(issuance.issue(draft.id(), draft.version(), accountant).status()).isEqualTo(EInvoiceStatus.ISSUED);

        assertThat(settlements.complete(order.orderId())).as("delivered today").isFalse();
        assertThat(status(order.orderId())).isEqualTo(OrderStatus.DELIVERED);

        pastTheReturnWindow(order.orderId());
        completes(order.orderId());
    }
}
