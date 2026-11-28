package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.invoicing.internal.domain.CorrectionBasis;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.CompletionHoldReason;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.service.OrderCompletionHolds;
import com.stockflow.order.internal.service.OrderSettlementService;
import com.stockflow.order.internal.service.ReturnRequestService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.PaymentService;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WF-05 GH-2, GH-3 and HĐ-3 end to end against the real database and the real order, payment and invoicing modules:
 * a delivery the carrier reported wrongly, its invoice issued the same day, corrected to a failure by the warehouse
 * manager, then delivered again a week later.
 *
 * <ul>
 *   <li>GH-3: the installment after that delivery goes back to NOT_DUE with no due day - so it is never overdue and the
 *       customer's credit shows nothing overdue - and falls due again from the redelivery's day;</li>
 *   <li>HĐ-3: the issued invoice is reduced in full by a draft the system draws up ({@code DELIVERY_FAILED}, no
 *       agreement), stops standing for the shipment, and the redelivery is drawn up as a new original; the order
 *       completes once both are issued, and not before;</li>
 *   <li>GH-2: Sales pause the completion while a delivery is verified, and the nightly completion respects it;</li>
 *   <li>a delivery with a return request against it is not corrected to a failure.</li>
 * </ul>
 *
 * <p>The correction is made as fulfillment makes it: {@code OrderService.correctShipmentToFailed} and the
 * {@code ShipmentStatusChanged} from DELIVERED to DELIVERY_FAILED in one transaction (ShipmentService.correctOutcome),
 * heard after its commit by the real listeners through the event registry. The one thing waited for by hand is time:
 * the return window is passed by moving {@code delivered_at} back.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false",
        "stockflow.notification.email.customer.einvoice-enabled=false"})
class Wf05DeliveryCorrectionIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final long PRICE = 2_000;

    @Autowired OrderService orders;
    @Autowired OrderSettlementService settlements;
    @Autowired OrderCompletionHolds completionHolds;
    @Autowired ReturnRequestService returns;
    @Autowired PaymentService payments;
    @Autowired EInvoiceIssuance issuance;
    @Autowired EInvoiceQueryService queries;
    @Autowired EInvoiceDrafts drafts;
    @Autowired EInvoiceCorrections invoiceCorrections;
    @Autowired com.stockflow.order.api.DeliveryInvoices deliveryInvoices;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired ApplicationEventPublisher publisher;
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

    // ---- fixtures -----------------------------------------------------------------------------------------------------

    private OrderStatus status() {
        return orders.findById(orderId).orElseThrow().status();
    }

    private String installment(int seq, String column) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM payment.installment WHERE order_id = ? AND seq = ?",
                String.class, orderId, seq);
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL, call);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    /**
     * Signed with 40% on signing and 60% thirty days after batch 1, delivered in {@code batches} batches of two
     * cartons; the deposit paid, batch 1 released, handed over and delivered. Returns its shipment.
     */
    private UUID batchOneDelivered(int batches) {
        ConfirmedOrders.Signed signed = confirmedOrders.sign(confirmedOrders.draft(customer,
                List.of(ConfirmedOrders.Line.cartons(sku, 2 * batches, PRICE))), List.of(
                new SalesContracts.Installment("Deposit", "ON_SIGNING", null, new BigDecimal("40"), 0, null),
                new SalesContracts.Installment("After batch 1", "AFTER_DELIVERY", null, new BigDecimal("60"), 30, 1)),
                batches);
        orderId = signed.orderId();
        confirmedOrders.pay(signed);
        confirmedOrders.release(orderId, DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(orderId, staff);
        orders.markPacked(orderId, staff);
        OrderSummary order = orders.findById(orderId).orElseThrow();
        UUID shipment = ShipmentRows.ready(jdbc, tx, orderId, UUID.randomUUID());
        orders.recordHandover(orderId, new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), 2 * 50)), Instant.now(), staff));
        orders.recordShipmentDelivered(orderId, shipment, staff);
        return shipment;
    }

    /** As fulfillment's ShipmentService.correctOutcome: the order follows, and the step is published, together. */
    private void correctToFailed(UUID shipment) {
        tx.executeWithoutResult(s -> {
            orders.correctShipmentToFailed(orderId, shipment, DeliveryFailureReason.CUSTOMER_ABSENT, null,
                    Instant.now(), staff);
            publisher.publishEvent(new ShipmentStatusChanged(UUID.randomUUID(), shipment, "T-" + shipment, orderId,
                    orders.findById(orderId).orElseThrow().orderNumber(), customer, DemoData.WAREHOUSE_HCM,
                    "DELIVERED", "DELIVERY_FAILED", "CUSTOMER_ABSENT", 1, "Truck Co", null, staff, Instant.now()));
        });
    }

    /**
     * Waits until invoicing's own listener has handled the correction of {@code shipment} - its publication of
     * {@code ShipmentStatusChanged} completed - so what the test does next cannot race it. Asking
     * {@code voidForCorrectedDelivery} instead proves nothing: the test's own call does the voiding, answers at once,
     * and the listener still runs afterwards on the one async thread, behind whatever earlier steps left queued. Run
     * after an adjustment held back by the provider was issued, it finds nothing holding the original back any more and
     * reduces it itself, and the accountant's reduction the test makes next is refused as already reduced.
     */
    private void correctionHeard(UUID shipment) {
        Await.until("the correction heard by invoicing", ASYNC, () -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT count(*) > 0 AND count(*) FILTER (WHERE completion_date IS NULL) = 0 FROM event_publication
                 WHERE event_type LIKE '%ShipmentStatusChanged' AND listener_id LIKE '%DeliveryCorrectionListener%'
                   AND serialized_event LIKE ? AND serialized_event LIKE '%"status":"DELIVERY_FAILED"%'""",
                Boolean.class, "%" + shipment + "%")));
    }

    private List<UUID> originalsOf(UUID shipment) {
        return jdbc.queryForList("""
                SELECT id FROM invoicing.einvoice WHERE shipment_id = ? AND type = 'ORIGINAL' AND status <> 'VOIDED_DRAFT'
                 ORDER BY created_at, id""", UUID.class, shipment);
    }

    private EInvoice.Snapshot issue(EInvoice.Snapshot draft) {
        return FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
    }

    /** Whether installment {@code seq} counts as overdue {@code days} from today, as the credit check reads it. */
    private boolean overdueIn(int seq, int days) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT payment.installment_is_overdue(due_date, status, CAST(? AS date))
                  FROM payment.installment WHERE order_id = ? AND seq = ?""", Boolean.class,
                java.sql.Date.valueOf(LocalDate.now(ConfirmedOrders.VIETNAM).plusDays(days)), orderId, seq));
    }

    private void returnWindowPassed() {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET delivered_at = NOW() - interval "
                + "'8 days' WHERE id = ?", orderId));
    }

    // ---- scenarios ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("GH-3 + HĐ-3: issued the day of a wrong delivery report, corrected to a failure - the installment is "
            + "NOT_DUE again and never overdue, the invoice reduced in full by a system draft with no agreement; "
            + "delivered again - due from the new day, a new original drawn up; completed only once both are issued")
    void aWrongDeliveryReportCorrectedThenDeliveredAgain() {
        UUID shipment = batchOneDelivered(1);
        assertThat(status()).isEqualTo(OrderStatus.DELIVERED);
        Await.until("the installment after delivery due", ASYNC, () -> "DUE".equals(installment(2, "status")));
        String wrongDueDate = installment(2, "due_date");
        assertThat(overdueIn(2, 40)).as("as it was: overdue forty days after the wrong report").isTrue();
        Await.until("the draft e-invoice of the delivery", ASYNC, () -> originalsOf(shipment).size() == 1);
        EInvoice.Snapshot wrong = issue(queries.get(originalsOf(shipment).getFirst()));
        assertThat(wrong.status()).isEqualTo(EInvoiceStatus.ISSUED);

        // The customer received nothing: the warehouse manager corrects the delivery to a failure.
        correctToFailed(shipment);

        // GH-3: back to NOT_DUE, no due day - not overdue whenever it is asked, nothing overdue on the credit.
        Await.until("the installment taken back", ASYNC, () -> "NOT_DUE".equals(installment(2, "status")));
        assertThat(installment(2, "due_date")).isNull();
        assertThat(installment(2, "reached_at")).isNull();
        assertThat(overdueIn(2, 40)).isFalse();
        assertThat(overdueIn(2, 400)).isFalse();
        assertThat(payments.creditPosition(customer).hasOverdue()).isFalse();

        // HĐ-3: a draft reducing the issued invoice in full, on its own basis, for the accountant.
        Await.until("the reduction drawn up", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM invoicing.einvoice WHERE original_einvoice_id = ? AND type = 'ADJUSTMENT'""",
                Long.class, wrong.id()) == 1);
        EInvoice.Snapshot reversal = queries.get(jdbc.queryForObject(
                "SELECT id FROM invoicing.einvoice WHERE original_einvoice_id = ?", UUID.class, wrong.id()));
        assertThat(reversal.status()).isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(reversal.type()).isEqualTo(EInvoiceType.ADJUSTMENT);
        assertThat(reversal.correctionBasis()).isEqualTo(CorrectionBasis.DELIVERY_FAILED);
        assertThat(reversal.grandTotal().add(wrong.grandTotal())).as("the two come to nothing")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reversal.agreementFileKey()).isNull();
        assertThat(jdbc.queryForObject("SELECT reversed_at IS NOT NULL FROM invoicing.einvoice WHERE id = ?",
                Boolean.class, wrong.id())).as("no longer the shipment's invoice").isTrue();
        assertThat(queries.get(wrong.id()).status()).as("issued stays issued").isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(code(catchThrowable(() -> drafts.voidDraft(reversal.id(), reversal.version(), "not wanted",
                accountant)))).isEqualTo(ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE);
        // The database says the same of the reversal flag: taken back only with its reduction voided as corrected
        // back in the same transaction (BE-29) - never by hand.
        assertThat(catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE invoicing.einvoice SET reversed_at = NULL WHERE id = ?", wrong.id()))))
                .hasMessageContaining("stays reversed");
        // Heard again, nothing more is drawn up.
        correctionHeardAgain(shipment);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice WHERE original_einvoice_id = ?",
                Long.class, wrong.id())).isEqualTo(1L);
        assertThat(settlements.complete(orderId)).isFalse();

        // A week later the carrier delivers again.
        assertThat(orders.recordShipmentDelivered(orderId, shipment, staff).status()).isEqualTo(OrderStatus.DELIVERED);

        // GH-3: due again, from the day of the real delivery.
        Await.until("the installment due again", ASYNC, () -> "DUE".equals(installment(2, "status")));
        Timestamp redelivered = jdbc.queryForObject("SELECT outcome_at FROM ordering.order_shipment WHERE id = ?",
                Timestamp.class, shipment);
        assertThat(jdbc.queryForObject("SELECT reached_at FROM payment.installment WHERE order_id = ? AND seq = 2",
                Timestamp.class, orderId)).isEqualTo(redelivered);
        assertThat(installment(2, "due_date")).isNotNull();
        assertThat(LocalDate.parse(installment(2, "due_date"))).isAfterOrEqualTo(LocalDate.parse(wrongDueDate));

        // HĐ-3: a new original for the goods really delivered, beside the reduced one.
        Await.until("a new original drawn up", ASYNC, () -> originalsOf(shipment).size() == 2);
        EInvoice.Snapshot redrawn = queries.get(originalsOf(shipment).getLast());
        assertThat(redrawn.id()).isNotEqualTo(wrong.id());
        assertThat(redrawn.status()).isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(redrawn.grandTotal()).as("the same goods and fees again").isEqualByComparingTo(wrong.grandTotal());
        assertThat(drafts.drawUp(orderId, shipment).invoice().id()).as("one original in force").isEqualTo(redrawn.id());

        // Paid, past the window: still not completed while the reduction waits, nor while the new original does.
        confirmedOrders.pay(customer, UUID.fromString(installment(2, "id")),
                new BigDecimal(installment(2, "amount")));
        returnWindowPassed();
        assertThat(settlements.complete(orderId)).as("neither the new original nor the reduction issued").isFalse();
        issue(redrawn);
        assertThat(settlements.complete(orderId)).as("the reduction is not issued: an invoice still stands for goods "
                + "the customer did not receive").isFalse();
        EInvoice.Snapshot reversalIssued = issue(queries.get(reversal.id()));
        assertThat(reversalIssued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(queries.get(wrong.id()).adjusted()).isTrue();

        assertThat(settlements.complete(orderId)).as("every delivered shipment invoiced by an invoice in force").isTrue();
        assertThat(status()).isEqualTo(OrderStatus.COMPLETED);
    }

    /** What the listener does when the correction is heard again: nothing stands for the shipment, nothing is drawn. */
    private void correctionHeardAgain(UUID shipment) {
        assertThat(drafts.voidForCorrectedDelivery(orderId, shipment))
                .isEqualTo(EInvoiceDrafts.CorrectionOutcome.NO_INVOICE);
        assertThat(drafts.reverseForCorrectedDelivery(orderId, shipment)).isEmpty();
    }

    /** The buyer's tax code and invoice e-mail, as the sandbox provider reads them to rehearse a failure. */
    private void buyer(String taxCode, String invoiceEmail) {
        tx.executeWithoutResult(s -> {
            // A tax code belongs to one customer (uk_customer_tax_code): the sandbox's refused one is taken back from
            // whichever earlier test customer was given it.
            jdbc.update("UPDATE customer.customer SET tax_code = NULL WHERE tax_code = ? AND id <> ?", taxCode, customer);
            jdbc.update("UPDATE customer.customer SET tax_code = ? WHERE id = ?", taxCode, customer);
            jdbc.update("UPDATE customer.customer_legal_profile SET invoice_email = ? WHERE customer_id = ?",
                    invoiceEmail, customer);
        });
    }

    private String currentTaxCode() {
        return jdbc.queryForObject("SELECT tax_code FROM customer.customer WHERE id = ?", String.class, customer);
    }

    private boolean markedDeliveryFailed(UUID invoice) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT delivery_failed_at IS NOT NULL FROM invoicing.einvoice WHERE id = ?", Boolean.class, invoice));
    }

    private long reductionsOf(UUID invoice) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM invoicing.einvoice
                 WHERE original_einvoice_id = ? AND type = 'ADJUSTMENT' AND correction_basis = 'DELIVERY_FAILED'""",
                Long.class, invoice);
    }

    @Test
    @DisplayName("WF-05 review, defect 5 + finding 5: corrected while its invoice is at the provider, delivered after "
            + "all before the provider's answer is settled - once issued the invoice stands: no reduction, no second "
            + "original")
    void anInvoiceAtTheProviderDeliveredAfterAllStands() {
        // The first send is taken in and its reply lost: the provider holds it, so nothing needs sending again.
        buyer(currentTaxCode(), "ke.toan+sandbox-reply-lost@" + customer + ".example.com");
        UUID shipment = batchOneDelivered(1);
        Await.until("the draft e-invoice of the delivery", ASYNC, () -> originalsOf(shipment).size() == 1);
        EInvoice.Snapshot sent = issue(queries.get(originalsOf(shipment).getFirst()));
        assertThat(sent.status()).as("the provider could not be reached").isEqualTo(EInvoiceStatus.SUBMITTING);

        correctToFailed(shipment);
        Await.until("the invoice marked", ASYNC, () -> markedDeliveryFailed(sent.id()));
        assertThat(queries.get(sent.id()).status()).as("it may exist in law: not voided")
                .isEqualTo(EInvoiceStatus.SUBMITTING);
        java.time.Instant markedAt = queries.get(sent.id()).deliveryFailedAt();
        assertThat(markedAt).as("the back office reads the mark off the invoice").isNotNull();
        assertThat(reductionsOf(sent.id())).isZero();
        // The mark is written once: moved to another time, refused.
        assertThat(catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE invoicing.einvoice SET delivery_failed_at = NOW() + interval '1 day' WHERE id = ?",
                sent.id())))).hasMessageContaining("marked once");

        // Delivered after all while the provider still has the invoice (the event that would take the mark back not
        // heard yet): it still stands, nothing new is drawn up.
        orders.recordShipmentDelivered(orderId, shipment, staff);
        assertThat(drafts.drawUp(orderId, shipment).invoice().id()).isEqualTo(sent.id());

        // The provider answers: issued. The shipment is delivered as it is now, so the invoice stands.
        assertThat(issuance.reconcile(sent.id()).status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(queries.get(sent.id()).deliveryFailedAt()).as("saving the issued invoice left the mark alone")
                .isEqualTo(markedAt);
        assertThat(drafts.settleCorrectedDelivery(sent.id()).settlement())
                .isEqualTo(EInvoiceDrafts.Settlement.DELIVERED_AFTER_ALL);
        assertThat(reductionsOf(sent.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT reversed_at IS NULL FROM invoicing.einvoice WHERE id = ?",
                Boolean.class, sent.id())).isTrue();
        assertThat(originalsOf(shipment)).as("one invoice for one delivery").containsExactly(sent.id());
        assertThat(drafts.drawUp(orderId, shipment).invoice().id()).isEqualTo(sent.id());
    }

    /** As fulfillment's ShipmentService.correctOutcome, the other way: the failure corrected back to delivered. */
    private void correctToDelivered(UUID shipment) {
        tx.executeWithoutResult(s -> {
            orders.recordShipmentDelivered(orderId, shipment, staff);
            publisher.publishEvent(new ShipmentStatusChanged(UUID.randomUUID(), shipment, "T-" + shipment, orderId,
                    orders.findById(orderId).orElseThrow().orderNumber(), customer, DemoData.WAREHOUSE_HCM,
                    "DELIVERY_FAILED", "DELIVERED", null, 1, "Truck Co", null, staff, Instant.now()));
        });
    }

    @Test
    @DisplayName("WF-05 review finding 5: corrected to a failure while its invoice is at the provider, then corrected "
            + "back to delivered - the mark is taken back and the provider's answer is taken as for any invoice")
    void aFailureCorrectedBackTakesTheMarkBack() {
        buyer(currentTaxCode(), "ke.toan+sandbox-unreachable@" + customer + ".example.com");
        UUID shipment = batchOneDelivered(1);
        Await.until("the draft e-invoice of the delivery", ASYNC, () -> originalsOf(shipment).size() == 1);
        EInvoice.Snapshot sent = issue(queries.get(originalsOf(shipment).getFirst()));
        assertThat(sent.status()).isEqualTo(EInvoiceStatus.SUBMITTING);

        correctToFailed(shipment);
        Await.until("the invoice marked", ASYNC, () -> markedDeliveryFailed(sent.id()));
        assertThat(queries.get(sent.id()).deliveryFailedAt()).isNotNull();
        correctToDelivered(shipment);
        Await.until("the mark taken back", ASYNC, () -> !markedDeliveryFailed(sent.id()));
        assertThat(queries.get(sent.id()).deliveryFailedAt()).isNull();

        assertThat(issuance.reconcile(sent.id()).status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(markedDeliveryFailed(sent.id())).as("the save at issue did not put the old mark back").isFalse();
        assertThat(reductionsOf(sent.id())).isZero();
        assertThat(originalsOf(shipment)).containsExactly(sent.id());
    }

    @Test
    @DisplayName("second review, finding 1: the failure event heard late - after the shipment was corrected back to "
            + "delivered - leaves the delivered shipment's invoice as it is")
    void aLateFailureEventChangesNothing() {
        UUID shipment = batchOneDelivered(1);
        Await.until("the draft e-invoice of the delivery", ASYNC, () -> originalsOf(shipment).size() == 1);
        UUID draft = originalsOf(shipment).getFirst();
        tx.executeWithoutResult(s -> orders.correctShipmentToFailed(orderId, shipment,
                DeliveryFailureReason.CUSTOMER_ABSENT, null, Instant.now(), staff));
        orders.recordShipmentDelivered(orderId, shipment, staff);

        // As the registry replaying the DELIVERED -> DELIVERY_FAILED event now.
        assertThat(drafts.voidForCorrectedDelivery(orderId, shipment))
                .isEqualTo(EInvoiceDrafts.CorrectionOutcome.NO_INVOICE);
        assertThat(drafts.reverseForCorrectedDelivery(orderId, shipment)).isEmpty();
        assertThat(queries.get(draft).status()).isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(originalsOf(shipment)).containsExactly(draft);
    }

    /**
     * Second review, finding 3: a marked invoice the provider never received is not sent again - asked about, it stays
     * at the provider - and the accountant takes it back once its last send is old enough (moved back four hours here,
     * as the clock would), which records the refusal its settling then voids.
     */
    private void takenBack(UUID invoice) {
        long submitted = submissionsOf(invoice);
        assertThat(issuance.reconcile(invoice).status()).as("asked about, not sent").isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(submissionsOf(invoice)).as("no submission since it was marked").isEqualTo(submitted);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE invoicing.einvoice SET submitted_at = submitted_at - interval '4 hours' WHERE id = ?",
                    invoice);
            jdbc.update("""
                    UPDATE invoicing.einvoice_sending SET last_sent_at = last_sent_at - interval '4 hours'
                     WHERE einvoice_id = ?""", invoice);
        });
        issuance.withdraw(invoice, queries.get(invoice).version(), "SePay portal: no invoice under this reference",
                accountant);
    }

    private long submissionsOf(UUID invoice) {
        return queries.calls(invoice).stream().filter(call -> "SUBMIT".equals(call.operation().name())).count();
    }

    /** A replacement of {@code original}, renaming its goods, agreed by both parties and naming {@code buyer}. */
    private EInvoice.Snapshot agreedReplacement(EInvoice.Snapshot original, EInvoiceCorrections.Buyer buyer)
            throws Exception {
        EInvoice.Snapshot draft = invoiceCorrections.replace(new EInvoiceCorrections.Replace(original.id(),
                "sai ten hang", buyer, List.of(new EInvoiceCorrections.LineChange(original.lines().getFirst().id(),
                        null, null, null, "Ly giay in logo (ten dung)"))));
        java.io.ByteArrayOutputStream pdf = new java.io.ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(pdf);
        }
        byte[] bytes = pdf.toByteArray();
        return invoiceCorrections.attachAgreement(draft.id(), draft.version(), "BB-01/2026",
                LocalDate.now(ConfirmedOrders.VIETNAM), new com.stockflow.common.storage.FileUpload("bien-ban.pdf",
                        "application/pdf", bytes.length, new java.io.ByteArrayInputStream(bytes)));
    }

    /** An issued original of a delivered batch, of a shipment returned. */
    private EInvoice.Snapshot issuedOriginal(UUID shipment) {
        Await.until("the draft e-invoice of the delivery", ASYNC, () -> originalsOf(shipment).size() == 1);
        EInvoice.Snapshot original = issue(queries.get(originalsOf(shipment).getFirst()));
        assertThat(original.status()).isEqualTo(EInvoiceStatus.ISSUED);
        return original;
    }

    @Test
    @DisplayName("WF-05 review finding 4: corrected while a replacement of the issued original is only drawn up - the "
            + "replacement is voided and the original reduced in full by the system")
    void aReplacementDrawnUpIsVoidedAndTheOriginalReduced() throws Exception {
        UUID shipment = batchOneDelivered(1);
        EInvoice.Snapshot original = issuedOriginal(shipment);
        EInvoice.Snapshot replacement = agreedReplacement(original, null);
        assertThat(replacement.status()).isEqualTo(EInvoiceStatus.DRAFT);

        correctToFailed(shipment);

        Await.until("the original reduced", ASYNC, () -> reductionsOf(original.id()) == 1);
        assertThat(queries.get(replacement.id()).status()).isEqualTo(EInvoiceStatus.VOIDED_DRAFT);
        assertThat(queries.get(original.id()).status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(jdbc.queryForObject("SELECT reversed_at IS NOT NULL FROM invoicing.einvoice WHERE id = ?",
                Boolean.class, original.id())).isTrue();
    }

    @Test
    @DisplayName("WF-05 review finding 4, Q-9: corrected while a replacement is at the provider - the replacement is "
            + "marked, and once issued (the original replaced) it is not adjusted by the system: no provider adjusts a "
            + "replacement, so the accountants are told to reduce it on the portal")
    void aReplacementAtTheProviderIssuedIsNotAdjusted() throws Exception {
        UUID shipment = batchOneDelivered(1);
        EInvoice.Snapshot original = issuedOriginal(shipment);
        EInvoice.Snapshot replacement = issue(agreedReplacement(original, new EInvoiceCorrections.Buyer(null, null,
                null, "ke.toan+sandbox-reply-lost@" + customer + ".example.com")));
        assertThat(replacement.status()).as("taken in, its reply lost").isEqualTo(EInvoiceStatus.SUBMITTING);

        correctToFailed(shipment);
        Await.until("the replacement marked", ASYNC, () -> markedDeliveryFailed(replacement.id()));
        assertThat(markedDeliveryFailed(original.id())).isFalse();
        assertThat(reductionsOf(original.id())).as("the original waits with its replacement").isZero();

        assertThat(issuance.reconcile(replacement.id()).status()).isEqualTo(EInvoiceStatus.ISSUED);
        Await.until("the replacement's answer settled", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL
                   AND event_type LIKE '%DeliveryFailedInvoiceAnswered'""", Long.class) == 0);
        assertThat(queries.get(original.id()).status()).isEqualTo(EInvoiceStatus.REPLACED);
        assertThat(reductionsOf(original.id())).isZero();
        assertThat(reductionsOf(replacement.id())).as("never an adjustment SePay refuses for good").isZero();
        assertThat(jdbc.queryForObject("SELECT reversed_at IS NULL FROM invoicing.einvoice WHERE id = ?",
                Boolean.class, replacement.id())).isTrue();
    }

    @Test
    @DisplayName("WF-05 review finding 4: corrected while a replacement is at the provider, which refuses it - the "
            + "replacement is voided and the original, still in force, reduced in full")
    void aReplacementAtTheProviderRefusedLeavesTheOriginalReduced() throws Exception {
        UUID shipment = batchOneDelivered(1);
        EInvoice.Snapshot original = issuedOriginal(shipment);
        // Its first send never arrives; marked, it is not sent again, and the accountant takes it back.
        EInvoice.Snapshot replacement = issue(agreedReplacement(original, new EInvoiceCorrections.Buyer(null, null,
                null, "ke.toan+sandbox-unreachable@" + customer + ".example.com")));
        assertThat(replacement.status()).isEqualTo(EInvoiceStatus.SUBMITTING);

        correctToFailed(shipment);
        Await.until("the replacement marked", ASYNC, () -> markedDeliveryFailed(replacement.id()));
        takenBack(replacement.id());
        Await.until("the original reduced", ASYNC, () -> reductionsOf(original.id()) == 1);
        assertThat(queries.get(replacement.id()).status()).isEqualTo(EInvoiceStatus.VOIDED_DRAFT);
        assertThat(queries.get(original.id()).status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(jdbc.queryForObject("SELECT reversed_at IS NOT NULL FROM invoicing.einvoice WHERE id = ?",
                Boolean.class, original.id())).isTrue();
    }

    @Test
    @DisplayName("WF-05 review, defect 5: corrected while its invoice is at the provider, which then refuses it - the "
            + "invoice is voided and not sent again; the shipment delivered again gets a new original")
    void anInvoiceAtTheProviderRefusedIsVoided() {
        // The first send never arrives, and a marked invoice is never sent again: the accountant takes it back.
        buyer(currentTaxCode(), "ke.toan+sandbox-unreachable@" + customer + ".example.com");
        UUID shipment = batchOneDelivered(1);
        Await.until("the draft e-invoice of the delivery", ASYNC, () -> originalsOf(shipment).size() == 1);
        EInvoice.Snapshot sent = issue(queries.get(originalsOf(shipment).getFirst()));
        assertThat(sent.status()).isEqualTo(EInvoiceStatus.SUBMITTING);

        correctToFailed(shipment);
        Await.until("the invoice marked", ASYNC, () -> markedDeliveryFailed(sent.id()));
        takenBack(sent.id());
        Await.until("the refused invoice voided", ASYNC,
                () -> queries.get(sent.id()).status() == EInvoiceStatus.VOIDED_DRAFT);
        assertThat(reductionsOf(sent.id())).isZero();
        assertThat(originalsOf(shipment)).as("nothing stands for the shipment").isEmpty();

        orders.recordShipmentDelivered(orderId, shipment, staff);
        Await.until("a new original for the redelivery", ASYNC, () -> originalsOf(shipment).size() == 1);
        assertThat(originalsOf(shipment).getFirst()).isNotEqualTo(sent.id());
    }

    /** An ERROR adjustment of {@code original} taking {@code cartons} off its goods, agreed in writing, to {@code email}. */
    private EInvoice.Snapshot agreedAdjustment(EInvoice.Snapshot original, int cartons, String email) throws Exception {
        EInvoice.Snapshot draft = invoiceCorrections.adjust(new EInvoiceCorrections.Adjust(original.id(),
                CorrectionBasis.ERROR, "sai so luong", new EInvoiceCorrections.Buyer(null, null, null, email),
                List.of(new EInvoiceCorrections.LineChange(original.lines().getFirst().id(), cartons, null, null,
                        null)), null, null));
        java.io.ByteArrayOutputStream pdf = new java.io.ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(pdf);
        }
        byte[] bytes = pdf.toByteArray();
        return invoiceCorrections.attachAgreement(draft.id(), draft.version(), "BB-02/2026",
                LocalDate.now(ConfirmedOrders.VIETNAM), new com.stockflow.common.storage.FileUpload("bien-ban.pdf",
                        "application/pdf", bytes.length, new java.io.ByteArrayInputStream(bytes)));
    }

    @Test
    @DisplayName("BE #423 + audit 25/11 N-1: corrected to a failure while an adjustment of the issued original is at "
            + "the provider - the system cannot reduce it; the back office is told so (no button, the order's block "
            + "names the shipment); once the adjustment is issued the flag says the command is accepted, and it is; "
            + "reduced, the flag and the block clear")
    void aReductionHeldBackByAnAdjustmentAtTheProviderIsOfferedOnceItSettles() throws Exception {
        UUID shipment = batchOneDelivered(1);
        EInvoice.Snapshot original = issuedOriginal(shipment);
        assertThat(drafts.deliveryFailureReducible(queries.get(original.id())))
                .as("delivered: nothing failed").isFalse();
        EInvoice.Snapshot adjustment = issue(agreedAdjustment(original, -1,
                "ke.toan+sandbox-reply-lost@" + customer + ".example.com"));
        assertThat(adjustment.status()).as("taken in, its reply lost").isEqualTo(EInvoiceStatus.SUBMITTING);

        correctToFailed(shipment);
        correctionHeard(shipment);
        assertThat(drafts.voidForCorrectedDelivery(orderId, shipment))
                .isEqualTo(EInvoiceDrafts.CorrectionOutcome.LEFT_FOR_ADJUSTMENT);
        assertThat(drafts.reverseOrExplain(orderId, shipment).notReversed())
                .isEqualTo(com.stockflow.contracts.EInvoiceNeedsAttention.Cause.DELIVERY_FAILED_NOT_REVERSED);
        assertThat(reductionsOf(original.id())).isZero();
        EInvoice.Snapshot held = queries.get(original.id());
        assertThat(held.deliveryFailedAt()).as("issued before the failure: never marked - why #423 exists").isNull();
        assertThat(drafts.deliveryFailureReducible(held)).as("the adjustment at the provider holds it back").isFalse();
        assertThat(code(catchThrowable(() -> drafts.reduceForFailedDelivery(held.id(), held.version()))))
                .as("the flag and the command agree").isEqualTo(ErrorCode.EINVOICE_REVERSAL_NOT_APPLICABLE);
        assertThat(deliveryInvoices.invoiced(List.of(shipment))).as("N-1: the order's block names the shipment")
                .containsExactly(shipment);

        // The adjustment was never marked (only an original or a replacement at the provider is), so its issue hands
        // nothing on to be settled: the reduction is the accountant's, below, and nothing draws it up meanwhile.
        assertThat(issuance.reconcile(adjustment.id()).status()).isEqualTo(EInvoiceStatus.ISSUED);
        EInvoice.Snapshot settled = queries.get(original.id());
        assertThat(drafts.deliveryFailureReducible(settled)).as("BE #423: the button is offered").isTrue();
        assertThat(reductionsOf(original.id())).as("reading the flag writes nothing").isZero();
        EInvoice.Snapshot reduction = drafts.reduceForFailedDelivery(settled.id(), settled.version());
        assertThat(reduction.correctionBasis()).isEqualTo(CorrectionBasis.DELIVERY_FAILED);
        assertThat(reductionsOf(original.id())).isEqualTo(1);
        assertThat(drafts.deliveryFailureReducible(queries.get(original.id()))).as("reduced: no button").isFalse();
        assertThat(deliveryInvoices.invoiced(List.of(shipment))).as("reduced: the block names nothing").isEmpty();
    }

    @Test
    @DisplayName("WF-05 Q-9, Q-8 (BE-09, BE-10): corrected after the shipment's invoice was replaced - nothing is drawn "
            + "up against the replacement; the accountant reduces it on the provider's portal and records it (an "
            + "issued adjustment, PORTAL_RECORD); the replacement then stands for nothing and the redelivery gets its "
            + "own original")
    void aReplacementInForceIsReducedOnThePortal() throws Exception {
        UUID shipment = batchOneDelivered(1);
        EInvoice.Snapshot original = issuedOriginal(shipment);
        EInvoice.Snapshot replacement = issue(agreedReplacement(original, null));
        assertThat(replacement.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(code(catchThrowable(() -> issuance.recordExternalReduction(replacement.id(), portalReduction(
                replacement, 1), accountant)))).as("not while its shipment is delivered")
                .isEqualTo(ErrorCode.EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE);

        correctToFailed(shipment);
        correctionHeard(shipment);
        assertThat(drafts.voidForCorrectedDelivery(orderId, shipment))
                .isEqualTo(EInvoiceDrafts.CorrectionOutcome.LEFT_FOR_ADJUSTMENT);
        assertThat(drafts.reverseOrExplain(orderId, shipment).notReversed())
                .isEqualTo(com.stockflow.contracts.EInvoiceNeedsAttention.Cause.REPLACEMENT_NOT_REDUCIBLE);
        assertThat(reductionsOf(replacement.id())).isZero();
        assertThat(code(catchThrowable(() -> drafts.reduceForFailedDelivery(replacement.id(),
                queries.get(replacement.id()).version())))).isEqualTo(ErrorCode.EINVOICE_REPLACEMENT_NOT_ADJUSTABLE);
        assertThat(drafts.deliveryFailureReducible(queries.get(replacement.id())))
                .as("BE #423: no button for a replacement (Q-9)").isFalse();
        assertThat(deliveryInvoices.invoiced(List.of(shipment)))
                .as("BE #423 / N-1: the order's block names the shipment until the portal's reduction is recorded")
                .containsExactly(shipment);

        int number = 900_000 + ThreadLocalRandom.current().nextInt(99_999);
        EInvoice.Snapshot recorded = issuance.recordExternalReduction(replacement.id(),
                portalReduction(replacement, number), accountant);

        assertThat(recorded.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(recorded.type()).isEqualTo(EInvoiceType.ADJUSTMENT);
        assertThat(recorded.correctionBasis()).isEqualTo(CorrectionBasis.DELIVERY_FAILED);
        assertThat(recorded.issuedVia()).isEqualTo(EInvoice.IssuedVia.PORTAL_RECORD);
        assertThat(recorded.invoiceNumber()).isEqualTo(number);
        assertThat(recorded.grandTotal().add(replacement.grandTotal())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("SELECT reversed_at IS NOT NULL FROM invoicing.einvoice WHERE id = ?",
                Boolean.class, replacement.id())).isTrue();
        assertThat(deliveryInvoices.invoiced(List.of(shipment))).as("recorded: nothing stands any more").isEmpty();
        assertThat(code(catchThrowable(() -> issuance.recordExternalReduction(replacement.id(),
                portalReduction(replacement, number + 1), accountant)))).as("recorded once")
                .isEqualTo(ErrorCode.EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE);

        orders.recordShipmentDelivered(orderId, shipment, staff);
        Await.until("a new original for the redelivery", ASYNC, () -> originalsOf(shipment).size() == 2);
        EInvoice.Snapshot redrawn = queries.get(originalsOf(shipment).getLast());
        issue(redrawn);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ? AND type IN ('ORIGINAL', 'REPLACEMENT')
                   AND status = 'ISSUED' AND reversed_at IS NULL""", Long.class, shipment))
                .as("one invoice in force per shipment: the new original").isEqualTo(1L);
    }

    @Test
    @DisplayName("audit r3 EINV-2, the ERROR half (Q-8, HĐ-5): an original issued through a provider that issues no "
            + "corrections; a correction of its quantity agreed with the buyer cannot be sent, is voided and made on "
            + "the provider's portal - offered as ERROR, recorded as the draft stated it with its agreement, once; the "
            + "database keeps the record tied to its draft")
    void anErrorOfAnOriginalNoProviderCanCorrectIsRecordedFromThePortal() throws Exception {
        UUID shipment = batchOneDelivered(1);
        EInvoice.Snapshot original = issuedOriginal(shipment);
        // Issued through Viettel before the switch (it issues no corrections here): written with triggers off, as the
        // row an earlier deployment left.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE invoicing.einvoice SET provider = 'VIETTEL_SINVOICE' WHERE id = ?", original.id());
        });
        EInvoice.Snapshot drawn = invoiceCorrections.adjust(new EInvoiceCorrections.Adjust(original.id(),
                CorrectionBasis.ERROR, "sai so luong: giao 1 thung, ghi 2", null, List.of(
                new EInvoiceCorrections.LineChange(original.lines().getFirst().id(), -1, null, null, null)), null,
                List.of()));
        java.io.ByteArrayOutputStream pdf = new java.io.ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(pdf);
        }
        byte[] bytes = pdf.toByteArray();
        EInvoice.Snapshot agreed = invoiceCorrections.attachAgreement(drawn.id(), drawn.version(), "BB-02/2026",
                LocalDate.now(ConfirmedOrders.VIETNAM), new com.stockflow.common.storage.FileUpload("bien-ban.pdf",
                        "application/pdf", bytes.length, new java.io.ByteArrayInputStream(bytes)));
        assertThat(issuance.externalReductions(queries.get(original.id())).any())
                .as("not while the draft stands").isFalse();
        EInvoice.Snapshot voided = drafts.voidDraft(agreed.id(), agreed.version(), "NCC cu khong dieu chinh duoc",
                accountant);

        EInvoiceIssuance.ExternalReductionOptions options = issuance.externalReductions(queries.get(original.id()));
        assertThat(options.errors()).singleElement().satisfies(option -> {
            assertThat(option.voidedAdjustmentId()).isEqualTo(voided.id());
            assertThat(option.grandTotal()).isEqualByComparingTo(voided.grandTotal());
        });
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        EInvoiceIssuance.ExternalReduction base = portalReduction(original,
                700_000 + ThreadLocalRandom.current().nextInt(99_999));
        assertThat(code(catchThrowable(() -> issuance.recordExternalReduction(original.id(),
                new EInvoiceIssuance.ExternalReduction(base.templateCode(), base.symbol(), base.invoiceNumber(), today,
                        null, null, base.xml(), base.pdf(), base.note(), CorrectionBasis.ERROR, null, null),
                accountant)))).as("ERROR names its voided draft").isEqualTo(ErrorCode.VALIDATION_FAILED);
        EInvoice.Snapshot recorded = issuance.recordExternalReduction(original.id(),
                new EInvoiceIssuance.ExternalReduction(base.templateCode(), base.symbol(), base.invoiceNumber(), today,
                        null, null, base.xml(), base.pdf(), base.note(), CorrectionBasis.ERROR, null, voided.id()),
                accountant);

        assertThat(recorded.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(recorded.type()).isEqualTo(EInvoiceType.ADJUSTMENT);
        assertThat(recorded.correctionBasis()).isEqualTo(CorrectionBasis.ERROR);
        assertThat(recorded.issuedVia()).isEqualTo(EInvoice.IssuedVia.PORTAL_RECORD);
        assertThat(recorded.adjustmentReason()).isEqualTo("sai so luong: giao 1 thung, ghi 2");
        assertThat(recorded.agreementNumber()).isEqualTo("BB-02/2026");
        assertThat(recorded.grandTotal()).isEqualByComparingTo(voided.grandTotal());
        assertThat(recorded.lines()).singleElement().satisfies(line -> assertThat(line.quantity()).isEqualTo(-1));
        assertThat(queries.get(original.id()).adjusted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT recorded_einvoice_id FROM invoicing.einvoice_portal_record "
                + "WHERE voided_draft_id = ?", UUID.class, voided.id())).isEqualTo(recorded.id());

        assertThat(issuance.externalReductions(queries.get(original.id())).any()).as("recorded once").isFalse();
        EInvoiceIssuance.ExternalReduction again = portalReduction(original, base.invoiceNumber() + 1);
        assertThat(code(catchThrowable(() -> issuance.recordExternalReduction(original.id(),
                new EInvoiceIssuance.ExternalReduction(again.templateCode(), again.symbol(), again.invoiceNumber(),
                        today, null, null, again.xml(), again.pdf(), again.note(), CorrectionBasis.ERROR, null,
                        voided.id()), accountant)))).isEqualTo(ErrorCode.EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE);

        // Stated twice: the database keeps each voided draft recorded once, and the record tied to it.
        assertThat(catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO invoicing.einvoice_portal_record (voided_draft_id, recorded_einvoice_id)
                VALUES (?, ?)""", voided.id(), recorded.id())))).hasMessageContaining("duplicate key");
        assertThat(catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(
                "DELETE FROM invoicing.einvoice_portal_record WHERE voided_draft_id = ?", voided.id()))))
                .hasMessageContaining("never changed or removed");
    }

    /** A reduction of {@code invoice} as the provider's portal gave it: number {@code number}, dated today. */
    private EInvoiceIssuance.ExternalReduction portalReduction(EInvoice.Snapshot invoice, int number) {
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        String symbol = "C%02dTPR".formatted(today.getYear() % 100);
        String xml = """
                <HDon><DLHDon><TTChung><KHMSHDon>1</KHMSHDon><KHHDon>%s</KHHDon><SHDon>%d</SHDon><NLap>%s</NLap>\
                </TTChung></DLHDon><MCCQT>M1-PR-%d</MCCQT></HDon>""".formatted(symbol, number, today, number);
        return new EInvoiceIssuance.ExternalReduction("1", symbol, number, today, null, null,
                xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), "%PDF-1.7 portal".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8), "Giam tren cong NCC - " + invoice.code());
    }

    @Test
    @DisplayName("WF-05 BE-29 (V1): corrected to a failure and back to delivered while the reduction is still a draft "
            + "- the reduction is voided as DELIVERY_CORRECTED_BACK and the original stands again: one invoice for "
            + "one delivery, not three")
    void aFailureCorrectedBackBeforeTheReductionLeft() {
        UUID shipment = batchOneDelivered(1);
        EInvoice.Snapshot original = issuedOriginal(shipment);
        correctToFailed(shipment);
        Await.until("the reduction drawn up", ASYNC, () -> reductionsOf(original.id()) == 1);
        UUID reduction = jdbc.queryForObject(
                "SELECT id FROM invoicing.einvoice WHERE original_einvoice_id = ? AND type = 'ADJUSTMENT'", UUID.class,
                original.id());

        correctToDelivered(shipment);

        Await.until("the reduction voided", ASYNC,
                () -> queries.get(reduction).status() == EInvoiceStatus.VOIDED_DRAFT);
        assertThat(queries.get(reduction).voidReasonCode()).isEqualTo(EInvoice.VoidReasonCode.DELIVERY_CORRECTED_BACK);
        Await.until("the original standing again", ASYNC, () -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT reversed_at IS NULL FROM invoicing.einvoice WHERE id = ?", Boolean.class, original.id())));
        Await.until("no second original", ASYNC, () -> originalsOf(shipment).equals(List.of(original.id())));
        assertThat(drafts.drawUp(orderId, shipment).invoice().id()).isEqualTo(original.id());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ? AND status <> 'VOIDED_DRAFT'""",
                Long.class, shipment)).as("the original alone").isEqualTo(1L);
    }

    @Test
    @DisplayName("WF-05 BE-10: the reduction of a failed delivery is voided only under UNSENDABLE_REVERSAL, which the "
            + "database checks too; a provider that can still send it is not let off (EINVOICE_REVERSAL_STILL_SENDABLE)")
    void anUnsendableReductionIsVoidedOnlyUnderItsCode() {
        UUID shipment = batchOneDelivered(1);
        EInvoice.Snapshot original = issuedOriginal(shipment);
        correctToFailed(shipment);
        Await.until("the reduction drawn up", ASYNC, () -> reductionsOf(original.id()) == 1);
        UUID reduction = jdbc.queryForObject(
                "SELECT id FROM invoicing.einvoice WHERE original_einvoice_id = ? AND type = 'ADJUSTMENT'", UUID.class,
                original.id());

        assertThat(code(catchThrowable(() -> drafts.voidUnsendableReversal(reduction,
                queries.get(reduction).version(), "khong gui duoc", accountant))))
                .as("the sandbox can send it").isEqualTo(ErrorCode.EINVOICE_REVERSAL_STILL_SENDABLE);
        assertThat(catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'x'
                 WHERE id = ?""", reduction)))).hasMessageContaining("issued, not voided");
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE invoicing.einvoice SET status = 'VOIDED_DRAFT', voided_at = NOW(), void_reason = 'x',
                       void_reason_code = 'UNSENDABLE_REVERSAL' WHERE id = ?""", reduction));
        assertThat(jdbc.queryForObject("SELECT reversed_at IS NOT NULL FROM invoicing.einvoice WHERE id = ?",
                Boolean.class, original.id())).as("the original stays reversed").isTrue();
        assertThat(settlements.complete(orderId)).as("a failed delivery is not completed on").isFalse();
        int number = 800_000 + ThreadLocalRandom.current().nextInt(99_999);
        EInvoice.Snapshot recorded = issuance.recordExternalReduction(original.id(), portalReduction(original, number),
                accountant);
        assertThat(recorded.issuedVia()).isEqualTo(EInvoice.IssuedVia.PORTAL_RECORD);
        assertThat(recorded.grandTotal().add(original.grandTotal())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queries.get(original.id()).adjusted()).isTrue();

        // V5: the voided reduction holds nothing back - delivered again, the new original is what counts.
        orders.recordShipmentDelivered(orderId, shipment, staff);
        Await.until("a new original for the redelivery", ASYNC, () -> originalsOf(shipment).size() == 2);
        issue(queries.get(originalsOf(shipment).getLast()));
        assertThat(context.getBean(com.stockflow.invoicing.internal.domain.EInvoiceQueries.class)
                .invoicedAmong(List.of(shipment))).containsExactly(shipment);
    }

    @Test
    @DisplayName("HĐ-3: a draft invoice of a corrected delivery is still voided, as before, and nothing is reduced")
    void aDraftIsStillVoided() {
        UUID shipment = batchOneDelivered(1);
        Await.until("the draft e-invoice of the delivery", ASYNC, () -> originalsOf(shipment).size() == 1);
        UUID draft = originalsOf(shipment).getFirst();

        correctToFailed(shipment);

        Await.until("the draft voided", ASYNC, () -> queries.get(draft).status() == EInvoiceStatus.VOIDED_DRAFT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice WHERE order_id = ? AND type = "
                + "'ADJUSTMENT'", Long.class, orderId)).isZero();
    }

    @Test
    @DisplayName("GH-2: Sales pause the completion while a delivery is verified - the nightly run passes the order by "
            + "and the database refuses it too - and release it; then the order completes")
    void theCompletionHoldIsRespected() {
        UUID shipment = batchOneDelivered(1);
        Await.until("the draft e-invoice of the delivery", ASYNC, () -> originalsOf(shipment).size() == 1);
        issue(queries.get(originalsOf(shipment).getFirst()));
        Await.until("the installment after delivery due", ASYNC, () -> "DUE".equals(installment(2, "status")));
        confirmedOrders.pay(customer, UUID.fromString(installment(2, "id")),
                new BigDecimal(installment(2, "amount")));
        returnWindowPassed();
        assertThat(settlements.completionCandidates(0, 10_000)).contains(orderId);

        OrderCompletionHolds.CompletionHold held = completionHolds.hold(orderId, CompletionHoldReason.DELIVERY_DISPUTED,
                "Khach bao chua nhan duoc hang", staff);
        assertThat(held.releasedAt()).isNull();
        assertThat(completionHolds.current(orderId)).contains(held);
        assertThat(code(catchThrowable(() -> completionHolds.hold(orderId, CompletionHoldReason.OTHER, "again",
                staff)))).isEqualTo(ErrorCode.ORDER_COMPLETION_ALREADY_HELD);
        assertThat(code(catchThrowable(() -> completionHolds.hold(orderId, CompletionHoldReason.OTHER, " ", staff))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(settlements.completionCandidates(0, 10_000)).as("left out of the batch").doesNotContain(orderId);
        assertThat(settlements.complete(orderId)).as("paused").isFalse();
        assertThat(status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET status = 'COMPLETED' WHERE id = ?", orderId))))
                .hasMessageContaining("completion is held");

        OrderCompletionHolds.CompletionHold released = completionHolds.release(orderId,
                "Hang xac nhan da giao, khach da nhan du", staff);
        assertThat(released.releasedAt()).isNotNull();
        assertThat(completionHolds.current(orderId)).isEmpty();
        assertThat(code(catchThrowable(() -> completionHolds.release(orderId, "again", staff))))
                .isEqualTo(ErrorCode.ORDER_COMPLETION_NOT_HELD);

        assertThat(settlements.complete(orderId)).isTrue();
        assertThat(status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(code(catchThrowable(() -> completionHolds.hold(orderId, CompletionHoldReason.DELIVERY_DISPUTED,
                null, staff)))).isEqualTo(ErrorCode.ORDER_COMPLETION_HOLD_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("a delivery with a return request against it is not corrected to a failure; once the return is "
            + "rejected it is")
    void aDeliveryWithAReturnIsNotCorrected() {
        UUID shipment = batchOneDelivered(2);
        assertThat(status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        UUID lineId = orders.findById(orderId).orElseThrow().lines().getFirst().lineId();
        ReturnRequest request = asSales(() -> returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(
                UUID.randomUUID(), orderId, ReturnReason.DAMAGED_IN_TRANSIT, "cartons crushed in the truck",
                ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(lineId, 10)), null, shipment),
                confirmedOrders.sales().userId()));

        assertThat(code(catchThrowable(() -> orders.correctShipmentToFailed(orderId, shipment,
                DeliveryFailureReason.CUSTOMER_ABSENT, null, Instant.now(), staff))))
                .isEqualTo(ErrorCode.ORDER_DELIVERY_HAS_RETURN);
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.order_shipment WHERE id = ?", String.class,
                shipment)).isEqualTo("DELIVERED");

        returns.reject(request.id(), "not damaged by the carrier", null, staff);
        orders.correctShipmentToFailed(orderId, shipment, DeliveryFailureReason.CUSTOMER_ABSENT, null, Instant.now(),
                staff);
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.order_shipment WHERE id = ?", String.class,
                shipment)).isEqualTo("DELIVERY_FAILED");
    }
}
