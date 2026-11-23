package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.inventory.internal.service.CartonQuarantineService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.domain.WindowChangeReason;
import com.stockflow.order.internal.service.CustomerOrders;
import com.stockflow.order.internal.service.DeliveryBatchService;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.ReturnRequestService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.procurement.internal.service.ReturnReceiptService;
import com.stockflow.support.Await;
import com.stockflow.support.ChatRows;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ShipmentRows;
import com.stockflow.support.ShippedCartons;
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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WF-05 TH-1 end to end (owner decisions 2026-11-18; Luật Thương mại 2005 Điều 44, 313, 318; kltn-docs 17 §4.10): a
 * return of batch 1's goods while batch 2 is still to come, against the real order, payment, procurement, inventory and
 * invoicing modules. The return names shipment 1; the order's status is its deliveries' all along; the receipt takes
 * shipment 1's cartons only, one counted short (TH-4); the refund comes off batch 1's installment, not batch 2's, and
 * is taken off shipment 1's e-invoice; batch 2 is released, handed over and delivered as if nothing happened.
 *
 * <p>The goods: 4 cartons of 50 at 2,000 a piece, 10% VAT - 440,000 - two cartons a batch. The money: 40% on signing,
 * 30% after batch 1, 30% after batch 2.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false",
        "stockflow.notification.email.customer.einvoice-enabled=false"})
class Wf05ReturnPerDeliveryIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final long PRICE = 2_000;
    private static final String QUARANTINE = "HCM-QC01";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D',
            'R', 0, 0, 0, 1, 0, 0, 0, 1};

    @Autowired OrderService orders;
    @Autowired ReturnRequestService returns;
    @Autowired ReturnReceiptService returnReceipts;
    @Autowired CartonQuarantineService quarantine;
    @Autowired EInvoiceIssuance issuance;
    @Autowired EInvoiceQueryService queries;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired CustomerOrders customerOrders;

    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID staff;
    /** The warehouse manager who confirms a return receipt holding an opened carton (WF-05 Q-18): not its counter. */
    private UUID manager;
    private UUID coordinator;
    private UUID accountant;
    private CustomerSkuRows.Issued sku;
    private UUID orderId;
    private UUID lineId;

    @BeforeEach
    void aCustomerWithCreditAndALegalProfile() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        confirmedOrders.legalProfile(customer);
        String taxCode = "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customer.customer SET tax_code = ? WHERE id = ?", taxCode, customer);
            // 21 BR-06 (HĐ-8): an invoice is issued only to a buyer with an e-mail to receive it.
            jdbc.update("UPDATE customer.customer_legal_profile SET invoice_email = ? WHERE customer_id = ?",
                    "hoa.don@" + customer + ".example.com", customer);
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

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL, call);
    }

    private <T> T asHcmClerk(Supplier<T> call) {
        CurrentUser clerk = new CurrentUser(staff, "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of("HCM"), Set.of(DemoData.WAREHOUSE_HCM));
        return WithCurrentUser.call(clerk, DataScope.WAREHOUSE, call);
    }

    private BigDecimal reduced(int seq) {
        return jdbc.queryForObject("SELECT reduced_amount FROM payment.installment WHERE order_id = ? AND seq = ?",
                BigDecimal.class, orderId, seq);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    /** Signed (40% on signing, 30% after each batch), paid on signing, batch 1 released, handed over, delivered. */
    private UUID batchOneDelivered() {
        ConfirmedOrders.Signed signed = confirmedOrders.sign(confirmedOrders.draft(customer,
                List.of(ConfirmedOrders.Line.cartons(sku, 4, PRICE).withTax(BigDecimal.TEN))), List.of(
                new SalesContracts.Installment("Deposit", "ON_SIGNING", null, new BigDecimal("40"), 0, null),
                new SalesContracts.Installment("After batch 1", "AFTER_DELIVERY", null, new BigDecimal("30"), 10, 1),
                new SalesContracts.Installment("After batch 2", "AFTER_DELIVERY", null, new BigDecimal("30"), 10, 2)),
                2);
        orderId = signed.orderId();
        confirmedOrders.pay(signed);
        confirmedOrders.release(orderId, DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(orderId, staff);
        UUID first = handOverTwoCartons();
        orders.recordShipmentDelivered(orderId, first, staff);
        assertThat(status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        lineId = orders.findById(orderId).orElseThrow().lines().getFirst().lineId();
        return first;
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

    /** Batch 2 released once batch 1 left, its window moved to today with the customer's consent, then handed over. */
    private UUID batchTwoHandedOver() {
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
        return handOverTwoCartons();
    }

    private ReturnRequest open(UUID shipment, int units) {
        return asSales(() -> returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(),
                orderId, ReturnReason.DAMAGED_IN_TRANSIT, "cartons crushed in the truck", ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(lineId, units)), null, shipment), confirmedOrders.sales().userId()));
    }

    private ReturnRequest approved(UUID shipment, int units) {
        ReturnRequest request = open(shipment, units);
        returns.addEvidence(request.id(), null, ReturnEvidence.Stage.CLAIM,
                new FileUpload("loi.png", "image/png", PNG.length, new ByteArrayInputStream(PNG)),
                confirmedOrders.sales().userId());
        return returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(null, null, "ok", null),
                coordinator);
    }

    private boolean parcelTaken(UUID receiptId) {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.return_request_receipt WHERE goods_receipt_id = ?",
                Long.class, receiptId) == 1;
    }

    private ReturnStatus returnStatus(UUID returnId) {
        return ReturnStatus.valueOf(jdbc.queryForObject("SELECT status FROM ordering.return_request WHERE id = ?",
                String.class, returnId));
    }

    /** What the customer's order detail shows of shipment {@code shipment}'s line: carried, still returnable. */
    private CustomerOrders.ShipmentLine shown(UUID shipment) {
        return customerOrders.myOrder(customer, orderId).shipments().stream()
                .filter(s -> shipment.equals(s.shipmentId())).findFirst().orElseThrow()
                .lines().stream().filter(line -> line.orderLineId().equals(lineId)).findFirst().orElseThrow();
    }

    private EInvoice.Snapshot issuedInvoiceOf(UUID shipment) {
        Await.until("the draft e-invoice of shipment " + shipment, ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ? AND type = 'ORIGINAL'", Long.class,
                shipment) == 1);
        EInvoice.Snapshot draft = queries.get(jdbc.queryForObject(
                "SELECT id FROM invoicing.einvoice WHERE shipment_id = ? AND type = 'ORIGINAL'", UUID.class, shipment));
        return FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
    }

    // ---- scenarios ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("TH-1/TH-4: batch 1's goods returned while batch 2 is on its way - the order's status untouched, "
            + "shipment 1's cartons only, one counted 40 of 50 refunded for 40, off batch 1's installment and shipment "
            + "1's invoice; the opened carton is confirmed by a manager who counted nothing and stays CONSUMED (no "
            + "stock from a label, WF-05 Q-18); batch 2 still goes out and the order ends DELIVERED")
    void aReturnOfBatchOneWhileBatchTwoIsPending() {
        UUID first = batchOneDelivered();
        ShippedCartons.Shipped cartons = ShippedCartons.onShipment(jdbc, tx, orderId, lineId, sku.sku(), 2, 50,
                "LOT-B1-" + orderId.toString().substring(0, 8), first, staff);
        EInvoice.Snapshot firstInvoice = issuedInvoiceOf(first);

        // Bounded by what shipment 1 delivered (100), one open return per line and shipment, and the database again.
        assertThat(shown(first)).as("the customer is shown the same bound")
                .isEqualTo(new CustomerOrders.ShipmentLine(lineId, sku.sku(), 100, 100));
        assertThat(code(catchThrowable(() -> open(first, 101)))).isEqualTo(ErrorCode.RETURN_QUANTITY_EXCEEDED);
        ReturnRequest request = approved(first, 50);
        assertThat(shown(first).returnableQuantity()).as("a return of the line is open on shipment 1").isZero();
        assertThat(request.shipmentId()).isEqualTo(first);
        assertThat(status()).as("a return moves no status while batches are still to come")
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(code(catchThrowable(() -> open(first, 10)))).isEqualTo(ErrorCode.RETURN_LINE_ALREADY_OPEN);
        Throwable tooMany = catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.return_request_line SET approved_quantity = NULL, quantity = 101 "
                        + "WHERE return_request_id = ?", request.id())));
        assertThat(tooMany).as("check_return_request_line").hasMessageContaining("this delivery brought");

        // The receipt takes shipment 1's cartons; one came back opened with 40 of its 50 inside.
        ReturnReceiptService.ReturnReceiptView draft = asHcmClerk(() -> returnReceipts.create(
                new ReturnReceiptService.Create(null, request.id(), QUARANTINE, null), staff));
        assertThat(draft.expectedCartons()).extracting(ReturnReceiptService.ExpectedCarton::lpn)
                .containsExactlyInAnyOrderElementsOf(cartons.lpns());
        String opened = cartons.lpns().getFirst();
        ReturnReceiptService.ReturnReceiptView scanned = asHcmClerk(() -> returnReceipts.scan(draft.id(), opened,
                "tape cut", 40, staff));
        assertThat(scanned.cartons()).singleElement().satisfies(c -> {
            assertThat(c.quantity()).isEqualTo(50);
            assertThat(c.countedQuantity()).isEqualTo(40);
            assertThat(c.opened()).isTrue();
        });
        // WF-05 Q-18: a receipt with an opened carton is confirmed only by a warehouse manager who counted nothing on it.
        assertThat(code(catchThrowable(() -> asHcmClerk(() -> returnReceipts.confirm(draft.id(), staff)))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_MANAGER_CONFIRMATION_REQUIRED);
        asHcmClerk(() -> returnReceipts.confirm(draft.id(), manager, true));
        Await.until("the parcel taken", ASYNC, () -> parcelTaken(draft.id()));
        assertThat(jdbc.queryForObject("SELECT received_quantity FROM ordering.return_request_line WHERE "
                + "return_request_id = ?", Integer.class, request.id())).as("what was counted, not the label")
                .isEqualTo(40);
        // WF-05 TH-3: 10 of the 50 approved are still awaited, until Sales say the customer keeps them.
        assertThat(returnStatus(request.id())).isEqualTo(ReturnStatus.AWAITING_RETURN);
        returns.markGoodsComplete(request.id(), "the customer keeps the rest", null, confirmedOrders.sales().userId());
        assertThat(returnStatus(request.id())).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        // Opened on its way back, it is no stock: it stays CONSUMED, marked opened, and was never quarantined.
        assertThat(jdbc.queryForMap("SELECT status, return_opened_at IS NOT NULL AS opened FROM inventory.stock_package "
                + "WHERE lpn = ?", opened)).containsEntry("status", "CONSUMED").containsEntry("opened", true);
        assertThat(code(catchThrowable(() -> asHcmClerk(() -> quarantine.release(opened, staff)))))
                .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);

        // The refund: 40 pieces at 2,000 with 10% VAT, off batch 1's installment - batch 2's is left alone.
        ReturnRequest proposed = returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(
                null, null, null), confirmedOrders.sales().userId());
        assertThat(proposed.settlementAmount()).isEqualByComparingTo("88000");
        assertThat(returns.approveSettlement(request.id(), null, accountant).status()).isEqualTo(ReturnStatus.RESOLVED);
        assertThat(reduced(2)).as("batch 1's installment").isEqualByComparingTo("88000");
        assertThat(reduced(3)).as("batch 2's installment").isEqualByComparingTo("0");
        assertThat(status()).as("resolved, still nothing moves").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        // Closed, the return claims the 40 refunded, not the 50 approved: 60 of shipment 1's line are left, and opening
        // agrees to the unit.
        assertThat(shown(first).returnableQuantity()).isEqualTo(60);
        assertThat(code(catchThrowable(() -> open(first, 61)))).isEqualTo(ErrorCode.RETURN_QUANTITY_EXCEEDED);
        Await.until("the adjustment of shipment 1's invoice", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM invoicing.einvoice WHERE type = 'ADJUSTMENT' AND original_einvoice_id = ?""",
                Long.class, firstInvoice.id()) == 1);

        // Batch 2 goes out as if nothing happened; delivered, the order is DELIVERED and stays so.
        UUID second = batchTwoHandedOver();
        assertThat(shown(second)).as("with the carrier: listed, nothing to return yet")
                .isEqualTo(new CustomerOrders.ShipmentLine(lineId, sku.sku(), 100, 0));
        assertThat(orders.recordShipmentDelivered(orderId, second, staff).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(shown(second).returnableQuantity()).as("delivered: its own 100, the line's 160 left in all")
                .isEqualTo(100);
        Await.until("the second delivery handled", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ? AND type = 'ORIGINAL'", Long.class,
                second) == 1);
        assertThat(status()).as("the return ended before: it does not drag the order along")
                .isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("TH-3/TT-5: batch 1 paid in full, its two cartons returned in two parcels on two receipts - the "
            + "return awaits the second, then RECEIVED with both; the refund is money back, batch 2's installment "
            + "is left as it is")
    void batchOnePaidReturnedInTwoParcels() {
        UUID first = batchOneDelivered();
        ShippedCartons.Shipped cartons = ShippedCartons.onShipment(jdbc, tx, orderId, lineId, sku.sku(), 2, 50,
                "LOT-B1-" + orderId.toString().substring(0, 8), first, staff);
        UUID afterBatchOne = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ? AND seq = 2",
                UUID.class, orderId);
        Await.until("batch 1's installment due", ASYNC, () -> "DUE".equals(jdbc.queryForObject(
                "SELECT status FROM payment.installment WHERE id = ?", String.class, afterBatchOne)));
        confirmedOrders.pay(customer, afterBatchOne, new BigDecimal("132000"));
        ReturnRequest request = approved(first, 100);

        ReturnReceiptService.ReturnReceiptView monday = asHcmClerk(() -> returnReceipts.create(
                new ReturnReceiptService.Create(null, request.id(), QUARANTINE, null), staff));
        asHcmClerk(() -> returnReceipts.scan(monday.id(), cartons.lpns().get(0), null, staff));
        asHcmClerk(() -> returnReceipts.confirm(monday.id(), staff));
        Await.until("Monday's parcel taken", ASYNC, () -> parcelTaken(monday.id()));
        assertThat(returnStatus(request.id())).isEqualTo(ReturnStatus.AWAITING_RETURN);

        ReturnReceiptService.ReturnReceiptView thursday = asHcmClerk(() -> returnReceipts.create(
                new ReturnReceiptService.Create(null, request.id(), QUARANTINE, null), staff));
        asHcmClerk(() -> returnReceipts.scan(thursday.id(), cartons.lpns().get(1), null, staff));
        asHcmClerk(() -> returnReceipts.confirm(thursday.id(), staff));
        Await.until("the return received", ASYNC, () -> returnStatus(request.id()) == ReturnStatus.RECEIVED);

        // 100 pieces at 2,000 with 10% VAT: 220,000. Batch 1 owes nothing any more; batch 2 has not left.
        assertThat(returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null, null, null),
                confirmedOrders.sales().userId()).settlementAmount()).isEqualByComparingTo("220000");
        returns.approveSettlement(request.id(), null, accountant);
        assertThat(reduced(2)).isEqualByComparingTo("0");
        assertThat(reduced(3)).as("batch 2's installment: its goods are still to come").isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT refundable_amount FROM payment.order_settlement WHERE kind = "
                + "'GOODS_RETURNED' AND source_id = ?", BigDecimal.class, request.id()))
                .as("handed back: 176,000 + 132,000 was paid").isEqualByComparingTo("220000");
    }

    @Test
    @DisplayName("TH-1: a return of batch 1 still open when batch 2 is delivered takes the order to RETURN_REQUESTED "
            + "then, as one opened on a delivered order does")
    void aReturnStillOpenWhenTheLastBatchArrives() {
        UUID first = batchOneDelivered();
        ReturnRequest request = open(first, 20);
        assertThat(request.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        UUID second = batchTwoHandedOver();
        assertThat(orders.recordShipmentDelivered(orderId, second, staff).status()).isEqualTo(OrderStatus.DELIVERED);

        Await.until("the order follows its open return", ASYNC, () -> status() == OrderStatus.RETURN_REQUESTED);
        assertThat(customerOrders.myOrder(customer, orderId).statusGroup())
                .as("the customer reads a return being decided, not goods returned")
                .isEqualTo(CustomerOrders.StatusGroup.RETURN_REQUESTED);
        assertThat(shown(first).returnableQuantity()).isZero();
        assertThat(shown(second).returnableQuantity()).isEqualTo(100);
        // Shipment 2's goods are claimed on their own: naming no shipment is refused once there are two.
        assertThat(code(catchThrowable(() -> open(null, 1)))).isEqualTo(ErrorCode.RETURN_SHIPMENT_REQUIRED);
        assertThat(open(second, 5).shipmentId()).isEqualTo(second);
    }
}
