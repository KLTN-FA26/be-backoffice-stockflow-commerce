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
        // The database says the same of the reversal flag: written once, never taken back.
        assertThat(catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE invoicing.einvoice SET reversed_at = NULL WHERE id = ?", wrong.id()))))
                .hasMessageContaining("reversed once");
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
