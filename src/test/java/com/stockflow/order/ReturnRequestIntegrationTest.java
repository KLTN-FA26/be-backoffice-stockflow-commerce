package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.ReturnDiscrepancy;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnItemCondition;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.OrderSettlementService;
import com.stockflow.order.internal.service.ReturnRequestQueries;
import com.stockflow.order.internal.service.ReturnRequestService;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Returns (RMA; SCRUM-243 / SCRUM-260) against a real database: every step through {@code ReturnRequestService} with
 * the order, inventory and payment beside it - stock counted into quarantine and back on sale, write-offs asked, refunds
 * placed on the money the order received, credit notes set against receivables, replacement orders placed - and the
 * unhappy paths a business meets: late, not delivered, cancelled, someone else's, too many units, a replay, a change
 * of mind on printed goods, goods that never come, fewer or other goods, no defect, more refund than paid, an overdue
 * customer, a design without a sample, two people at once.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class ReturnRequestIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final long PRICE = 1_000_000;
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D',
            'R', 0, 0, 0, 1, 0, 0, 0, 1};

    @Autowired OrderService orders;
    @Autowired OrderReleases releases;
    @Autowired CustomerService customers;
    @Autowired ReceivableService receivables;
    @Autowired OrderSettlementService settlements;
    @Autowired ReturnRequestService returns;
    @Autowired ReturnRequestQueries queries;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private UUID customer;
    private UUID customerUser;
    private UUID sales;
    private UUID coordinator;
    private UUID accountant;
    private UUID warehouse;
    private UUID qc;
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void people() {
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        customerUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = tx.execute(s -> ReferenceRows.user(entityManager));
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        warehouse = tx.execute(s -> ReferenceRows.user(entityManager));
        qc = tx.execute(s -> ReferenceRows.user(entityManager));
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, true, true, CommercialTerm.PREPAID,
                new BigDecimal("30"), new BigDecimal("50000000"), 30, "test terms", coordinator, null,
                com.stockflow.customer.api.DepositBalancePolicy.BEFORE_DELIVERY));
        com.stockflow.support.ConfirmedDesigns.stockBlanks(jdbc, tx, 12);
    }

    /** Stock the tests hold is given back, so other test classes find the demo stock they count on. */
    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderSummary order = orders.findById(id).orElseThrow();
            OrderStatus status = order.status();
            // A replacement order is cancelled only through its return: its holds are released instead.
            boolean replacement = order.lifecycle() != null && order.lifecycle().replacesReturnRequestId() != null;
            if (status.canTransitionTo(OrderStatus.CANCELLED) && !replacement) {
                orders.cancel(id, new CancelOrderCommand(CancellationReasonCode.OTHER, "test cleanup", null, null));
            } else {
                jdbc.queryForList("SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'",
                        UUID.class, id).forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE"));
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private OrderSummary place(PaymentTerm term, int units) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                List.of(new PlaceOrderCommand.Line(SOFA, units, Money.vnd(PRICE), null)), term, null));
        placed.add(order.orderId());
        return order;
    }

    private void transfer(UUID orderId, long amount) {
        receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer, reference(),
                BigDecimal.valueOf(amount), "VND", LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")), null, "payment",
                accountant, List.of(new ReceivableService.OrderPayment(orderId, BigDecimal.valueOf(amount)))));
    }

    /** A customer transfer paying their receivables, earliest due first. */
    private void payReceivables(long amount) {
        receivables.recordTransfer(new ReceivableService.RecordTransferCommand(customer, reference(),
                BigDecimal.valueOf(amount), "VND", LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")), null, "payment",
                accountant));
    }

    private static String reference() {
        return "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private OrderStatus statusOf(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    /** Placed, paid as its terms want, released to HCM, picked, packed, handed over whole and delivered. */
    private OrderSummary delivered(PaymentTerm term, int units) {
        OrderSummary order = place(term, units);
        if (term == PaymentTerm.PREPAID) {
            transfer(order.orderId(), units * PRICE);
            Await.until("paid", ASYNC, () -> statusOf(order.orderId()) == OrderStatus.CONFIRMED);
        }
        releases.release(order.orderId(), DemoData.WAREHOUSE_HCM, coordinator);
        orders.startPicking(order.orderId(), warehouse);
        orders.markPacked(order.orderId(), warehouse);
        orders.markShipped(order.orderId(), null, warehouse);
        OrderSummary done = orders.recordDelivery(order.orderId(), coordinator);
        assertThat(done.status()).isEqualTo(OrderStatus.DELIVERED);
        if (term == PaymentTerm.CREDIT) {
            Await.until("receivable", ASYNC, () -> receivableOutstanding(order.orderId()) != null);
        }
        return done;
    }

    /**
     * A delivered prepaid order with a line printed to the customer's design (the blank SOFA-3S-GREY and a confirmed
     * snapshot), written as the lifecycle leaves it: printing a real one runs the whole production module, which the
     * return does not need to prove its rules.
     */
    private UUID printedDelivered(int units) {
        UUID order = Identifiers.newId();
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, owner_user_id, status, created_at,
                                                     current_artifacts)
                    VALUES (?, ?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, customer, DemoData.PRODUCT_SOFA,
                    customerUser);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                                                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/cup.pdf', NOW(), NOW(), '[]'::jsonb)""", snapshot, draft,
                    "d".repeat(64));
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, payment_term, paid_amount, paid_in_full_at, warehouse_id, released_at,
                        released_by, packed_at, shipped_at, delivered_at, version, created_at)
                    VALUES (?, ?, ?, ?, 'DELIVERED', ?, 'VND', NOW() - interval '10 days', 'PREPAID', ?,
                        NOW() - interval '9 days', ?, NOW() - interval '8 days', ?, NOW() - interval '4 days',
                        NOW() - interval '3 days', NOW() - interval '2 days', 0, NOW())""",
                    order, "SO-20261020-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(100_000, 999_999)), customer, UUID.randomUUID(), units * 50_000L, units * 50_000L,
                    DemoData.WAREHOUSE_HCM, coordinator);
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency,
                                                     design_snapshot_id, design_checksum)
                    VALUES (?, ?, 'SOFA-3S-GREY', ?, 50000, 'VND', ?, ?)""", Identifiers.newId(), order, units,
                    snapshot, "d".repeat(64));
        });
        return order;
    }

    private UUID lineOf(UUID orderId) {
        return orders.findById(orderId).orElseThrow().lines().getFirst().lineId();
    }

    private ReturnRequest open(UUID orderId, ReturnReason reason, ReturnResolution wanted, int units) {
        return returns.openOwn(customer, new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(), orderId, reason,
                "Hàng bị lỗi", wanted, List.of(new ReturnRequest.Claim(lineOf(orderId), units))), customerUser);
    }

    private static FileUpload photo() {
        return new FileUpload("loi.png", "image/png", PNG.length, new ByteArrayInputStream(PNG));
    }

    /** A warehouse clerk limited to {@code warehouseId}, as the permission aspect puts them in force. */
    private static CurrentUser clerkOf(UUID warehouseId) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of(), Set.of(warehouseId));
    }

    private ReturnRequest withPhoto(ReturnRequest request) {
        return returns.addEvidence(request.id(), customer, ReturnEvidence.Stage.CLAIM,
                new FileUpload("loi.png", "image/png", PNG.length, new ByteArrayInputStream(PNG)), customerUser);
    }

    /** Approved on a photo - by the coordinator when Sales opened it: never by who opened it. */
    private ReturnRequest approved(ReturnRequest request, Boolean goodsBack) {
        withPhoto(request);
        UUID approver = request.channel().name().equals("BACK_OFFICE") ? coordinator : sales;
        return returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(null, goodsBack, null, null),
                approver);
    }

    /** Proposed by Sales, approved by the accountant: every resolution has two people. */
    private ReturnRequest resolved(ReturnRequest request, int replaced, int credited, ReturnResolution mode) {
        returns.proposeResolution(request.id(), new ReturnRequestService.ResolveReturnCommand(
                List.of(new ReturnRequest.Remedy(only(request), replaced, credited)), mode, null, null), sales);
        return returns.approveSettlement(request.id(), null, accountant);
    }

    /** Released, picked, packed, handed over and delivered: the way any order - a replacement too - reaches a customer. */
    private void deliver(UUID orderId) {
        releases.release(orderId, DemoData.WAREHOUSE_HCM, coordinator);
        orders.startPicking(orderId, warehouse);
        orders.markPacked(orderId, warehouse);
        orders.markShipped(orderId, null, warehouse);
        orders.recordDelivery(orderId, coordinator);
    }

    private static UUID only(ReturnRequest request) {
        return request.lines().getFirst().id();
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private BigDecimal receivableOutstanding(UUID orderId) {
        List<BigDecimal> found = jdbc.queryForList("SELECT amount - paid_amount FROM payment.receivable WHERE order_id = ?",
                BigDecimal.class, orderId);
        return found.isEmpty() ? null : found.getFirst();
    }

    private void backdateDelivery(UUID orderId, int days) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET delivered_at = NOW() - make_interval("
                + "days => ?) WHERE id = ?", days, orderId));
    }

    // ------------------------------------------------------------------------------------------------------ tests

    @Test
    @DisplayName("prepaid stock order, end to end: claim, completion held back, partial approval, parcel with one unit "
            + "too many into quarantine, QC back on sale / scrap / back to the customer, refund approved by a second "
            + "person on the money received; the order PARTIALLY_REFUNDED then CLOSED")
    void prepaidRefundEndToEnd() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 4);
        ReturnRequest request = open(order.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.REFUND, 3);
        assertThat(request.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(request.rmaNumber()).matches("RMA-\\d{8}-\\d{4}");
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.RETURN_REQUESTED);

        // BR-ORD-005: past the window, the open return keeps the order from completing.
        backdateDelivery(order.orderId(), 8);
        assertThat(settlements.completionCandidates(1000)).doesNotContain(order.orderId());
        assertThat(settlements.complete(order.orderId())).isFalse();

        assertThat(code(catchThrowable(() -> returns.approve(request.id(),
                new ReturnRequestService.ApproveReturnCommand(null, null, null, null), sales))))
                .isEqualTo(ErrorCode.RETURN_EVIDENCE_REQUIRED);
        withPhoto(request);
        ReturnRequest awaiting = returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(
                Map.of(only(request), 2), null, "2 chiếc móp", null), sales);
        assertThat(awaiting.status()).isEqualTo(ReturnStatus.AWAITING_RETURN);
        assertThat(awaiting.returnDeadline()).isAfter(Instant.now().plus(Duration.ofDays(13)));
        assertThat(code(catchThrowable(() -> returns.approve(request.id(),
                new ReturnRequestService.ApproveReturnCommand(null, null, null, null), sales))))
                .as("approved twice").isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);

        ReturnRequest received = returns.receive(request.id(), new ReturnRequestService.ReceiveReturnGoodsCommand(null,
                List.of(new ReturnRequest.Receipt(only(request), 3, null, null)), List.of(), "thùng móp", null),
                warehouse);
        assertThat(received.status()).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(received.receivingLocation()).as("HCM has no quarantine area: its QC area").isEqualTo("HCM-QC01");
        assertThat(received.discrepancies()).extracting(ReturnDiscrepancy::kind, ReturnDiscrepancy::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ReturnDiscrepancy.Kind.EXCESS, 3 - 2));
        assertThat(jdbc.queryForObject("""
                SELECT SUM(on_hand) FROM inventory.stock_item
                 WHERE location_code = 'HCM-QC01' AND sku = 'SOFA-3S-GREY' AND status = 'QUARANTINE'
                   AND received_at = ?""", Integer.class, java.sql.Timestamp.from(received.receivedAt())))
                .as("OQ-12: into quarantine, never sellable").isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement WHERE movement_type = 'RETURN_IN'
                   AND reference_type = 'RETURN_REQUEST_LINE' AND reference_id = ? AND order_id = ?""", Long.class,
                only(request), order.orderId())).isEqualTo(1L);

        ReturnRequest inspected = returns.inspect(request.id(), new ReturnRequestService.InspectReturnCommand(List.of(
                new ReturnRequest.Inspection(only(request), 2, 1, ReturnItemCondition.GOOD, 1, 1, 1,
                        "HCM-A02-1-A", "1 chiếc còn bán được")), null, null), qc);
        assertThat(inspected.status()).isEqualTo(ReturnStatus.INSPECTED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement WHERE movement_type = 'MOVE'
                   AND reference_type = 'RETURN_REQUEST_LINE' AND reference_id = ? AND to_location_code = 'HCM-A02-1-A'
                   AND to_status = 'AVAILABLE'""", Long.class, only(request))).as("back on sale").isEqualTo(1L);
        ReturnLine line = inspected.lines().getFirst();
        assertThat(line.scrapAdjustmentId()).isNotNull();
        assertThat(line.customerReturnAdjustmentId()).isNotNull();
        assertThat(jdbc.queryForList("SELECT reason_code || ' ' || quantity_delta || ' ' || status "
                        + "FROM inventory.stock_adjustment WHERE id IN (?, ?) ORDER BY reason_code", String.class,
                line.scrapAdjustmentId(),
                line.customerReturnAdjustmentId()))
                .as("write-offs wait for a second person (BR-STK-005)")
                .containsExactly("OTHER -1 PENDING_APPROVAL", "SCRAP -1 PENDING_APPROVAL");

        ReturnRequest pending = returns.proposeResolution(request.id(), new ReturnRequestService.ResolveReturnCommand(
                List.of(new ReturnRequest.Remedy(only(request), 0, 2)), ReturnResolution.REFUND, null, null), sales);
        assertThat(pending.status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
        assertThat(pending.settlementAmount()).isEqualByComparingTo("2000000");
        assertThat(code(catchThrowable(() -> returns.approveSettlement(request.id(), null, sales))))
                .as("four eyes").isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);

        ReturnRequest resolved = returns.approveSettlement(request.id(), pending.version(), accountant);
        assertThat(resolved.status()).isEqualTo(ReturnStatus.RESOLVED);
        assertThat(resolved.money().refunded()).isEqualByComparingTo("2000000");
        assertThat(resolved.money().offset()).isZero();
        assertThat(resolved.vatAdjustmentRequired()).isTrue();
        assertThat(jdbc.queryForList("""
                SELECT amount::bigint || ' ' || status || ' ' || (allocation_id IS NOT NULL) FROM payment.refund
                 WHERE return_request_id = ? AND source = 'RETURN'""", String.class, request.id()))
                .as("placed on the money the order received, waiting for the accountant")
                .containsExactly("2000000 PENDING true");

        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(orders.history(order.orderId())).extracting(OrderStatusChange::to)
                .containsSubsequence(OrderStatus.DELIVERED, OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED,
                        OrderStatus.PARTIALLY_REFUNDED);
        assertThat(queries.detail(request.id(), null).history()).extracting(t -> t.to().name())
                .containsExactly("REQUESTED", "AWAITING_RETURN", "RECEIVED", "INSPECTED", "SETTLEMENT_PENDING",
                        "RESOLVED");
        assertThat(code(catchThrowable(() -> open(order.orderId(), ReturnReason.DAMAGED_IN_TRANSIT,
                ReturnResolution.REFUND, 1)))).as("settled by a return").isIn(ErrorCode.RETURN_ORDER_NOT_RETURNABLE,
                ErrorCode.RETURN_WINDOW_CLOSED);

        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET refunded_at = NOW() - interval "
                + "'31 days' WHERE id = ?", order.orderId()));
        assertThat(settlements.close(order.orderId())).isTrue();
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    @DisplayName("credit order, receivable part paid and OVERDUE: no cash to an overdue customer; a credit note is set "
            + "off against the receivable, which stays OVERDUE until settled in full")
    void creditNoteAgainstOverdueReceivable() {
        OrderSummary order = delivered(PaymentTerm.CREDIT, 2);
        payReceivables(500_000);
        Await.until("part paid", ASYNC, () -> receivableOutstanding(order.orderId())
                .compareTo(BigDecimal.valueOf(1_500_000)) == 0);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE payment.receivable SET due_date = CURRENT_DATE - 3 "
                + "WHERE order_id = ?", order.orderId()));
        receivables.markOverdue();

        ReturnRequest request = returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(),
                order.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, "vỡ 1 bộ", ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(lineOf(order.orderId()), 1))), sales);
        assertThat(request.channel().name()).isEqualTo("BACK_OFFICE");
        ReturnRequest inspected = approved(request, false);
        assertThat(inspected.status()).as("1 000 000 VND: accepted on the photo, the goods stay")
                .isEqualTo(ReturnStatus.INSPECTED);

        assertThat(code(catchThrowable(() -> returns.proposeResolution(request.id(),
                new ReturnRequestService.ResolveReturnCommand(List.of(new ReturnRequest.Remedy(only(request), 0, 1)),
                        ReturnResolution.REFUND, null, null), sales))))
                .isEqualTo(ErrorCode.RETURN_REFUND_CUSTOMER_OVERDUE);
        returns.proposeResolution(request.id(), new ReturnRequestService.ResolveReturnCommand(
                List.of(new ReturnRequest.Remedy(only(request), 0, 1)), ReturnResolution.CREDIT_NOTE, "giảm trừ công nợ",
                null), sales);
        ReturnRequest resolved = returns.approveSettlement(request.id(), null, accountant);

        assertThat(resolved.money().offset()).isEqualByComparingTo("1000000");
        assertThat(resolved.money().credited()).isZero();
        assertThat(resolved.money().creditTransferId()).isNotNull();
        assertThat(receivableOutstanding(order.orderId())).isEqualByComparingTo("500000");
        assertThat(jdbc.queryForObject("SELECT status FROM payment.receivable WHERE order_id = ?", String.class,
                order.orderId())).isEqualTo("OVERDUE");
        assertThat(jdbc.queryForList("SELECT channel || ' ' || reference || ' ' || unallocated_amount::bigint "
                        + "FROM payment.customer_transfer WHERE id = ?", String.class,
                resolved.money().creditTransferId()))
                .containsExactly("RETURN_CREDIT " + request.rmaNumber() + " 0");
        Await.until("the order counts the credit", ASYNC, () -> orders.findById(order.orderId()).orElseThrow()
                .lifecycle().amountDue().compareTo(BigDecimal.valueOf(500_000)) == 0);
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("credit order already paid: the credit note is kept as the customer's credit; a refund goes back on the "
            + "transfer the customer paid with")
    void paidReceivableKeepsCreditOrRefunds() {
        OrderSummary first = delivered(PaymentTerm.CREDIT, 1);
        payReceivables(PRICE);
        Await.until("paid", ASYNC, () -> receivableOutstanding(first.orderId()).signum() == 0);
        ReturnRequest refund = returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(),
                first.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, null, ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(lineOf(first.orderId()), 1))), sales);
        approved(refund, false);
        returns.proposeResolution(refund.id(), new ReturnRequestService.ResolveReturnCommand(
                List.of(new ReturnRequest.Remedy(only(refund), 0, 1)), ReturnResolution.REFUND, null, null), sales);
        ReturnRequest paidBack = returns.approveSettlement(refund.id(), null, accountant);
        assertThat(paidBack.money().refunded()).isEqualByComparingTo("1000000");
        assertThat(paidBack.money().offset()).as("nothing left to set off").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.refund r JOIN payment.transfer_allocation a "
                + "ON a.id = r.allocation_id JOIN payment.customer_transfer t ON t.id = a.transfer_id "
                + "WHERE r.return_request_id = ? AND t.channel = 'BANK_TRANSFER'", Long.class, refund.id()))
                .as("on the real transfer, never on a credit").isEqualTo(1L);
        assertThat(statusOf(first.orderId())).as("everything given back").isEqualTo(OrderStatus.REFUNDED);

        OrderSummary order = delivered(PaymentTerm.CREDIT, 2);
        payReceivables(2 * PRICE);
        Await.until("paid", ASYNC, () -> receivableOutstanding(order.orderId()).signum() == 0);
        ReturnRequest credit = returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(),
                order.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, null, ReturnResolution.CREDIT_NOTE,
                List.of(new ReturnRequest.Claim(lineOf(order.orderId()), 1))), sales);
        approved(credit, false);
        returns.proposeResolution(credit.id(), new ReturnRequestService.ResolveReturnCommand(
                List.of(new ReturnRequest.Remedy(only(credit), 0, 1)), ReturnResolution.CREDIT_NOTE, null, null), sales);
        ReturnRequest kept = returns.approveSettlement(credit.id(), null, accountant);
        assertThat(kept.money().credited()).isEqualByComparingTo("1000000");
        assertThat(kept.money().offset()).isZero();
        assertThat(jdbc.queryForObject("SELECT unallocated_amount FROM payment.customer_transfer WHERE id = ?",
                BigDecimal.class, kept.money().creditTransferId())).as("the customer's credit")
                .isEqualByComparingTo("1000000");
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("a refund above the money the order still holds is refused whole - at approval when money went back "
            + "after Sales proposed it, and as Sales propose it again - and nothing is booked")
    void refundAbovePaidIsRefused() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 2);
        UUID allocation = jdbc.queryForObject("SELECT id FROM payment.transfer_allocation WHERE order_id = ?", UUID.class,
                order.orderId());
        ReturnRequest request = open(order.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.REFUND, 1);
        approved(request, false);
        // Proposed while the order still holds the 2 000 000 it received.
        returns.proposeResolution(request.id(), new ReturnRequestService.ResolveReturnCommand(
                List.of(new ReturnRequest.Remedy(only(request), 0, 1)), ReturnResolution.REFUND, null, null), sales);
        // Then the accountant gives 1 500 000 back by hand, before approving the return.
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.refund (id, allocation_id, amount, currency, reason, status, created_at, order_id,
                                            source)
                VALUES (?, ?, 1500000, 'VND', 'manual', 'PENDING', NOW(), ?, 'MANUAL')""", Identifiers.newId(),
                allocation, order.orderId()));
        assertThat(code(catchThrowable(() -> returns.approveSettlement(request.id(), null, accountant))))
                .isEqualTo(ErrorCode.RETURN_REFUND_EXCEEDS_PAID);
        assertThat(queries.detail(request.id(), null).request().status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.refund WHERE return_request_id = ?", Long.class,
                request.id())).isZero();
        // Sent back: proposing the same refund again is refused as Sales propose it, not after the accountant read it.
        returns.rejectSettlement(request.id(), "Hoàn tiền vượt số đã thu", null, accountant);
        ReturnStatus sentBack = queries.detail(request.id(), null).request().status();
        assertThat(code(catchThrowable(() -> returns.proposeResolution(request.id(),
                new ReturnRequestService.ResolveReturnCommand(List.of(new ReturnRequest.Remedy(only(request), 0, 1)),
                        ReturnResolution.REFUND, null, null), sales)))).isEqualTo(ErrorCode.RETURN_REFUND_EXCEEDS_PAID);
        assertThat(queries.detail(request.id(), null).request().status()).isEqualTo(sentBack);
        // Credited instead.
        returns.proposeResolution(request.id(), new ReturnRequestService.ResolveReturnCommand(
                List.of(new ReturnRequest.Remedy(only(request), 0, 1)), ReturnResolution.CREDIT_NOTE, null, null), sales);
        assertThat(returns.approveSettlement(request.id(), null, accountant).money().credited())
                .isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("replacement of a stock item: a free replacement order, CONFIRMED with its stock held and released "
            + "like any order; the delivered order goes back to DELIVERED and completes after its window")
    void replacementOrder() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 3);
        ReturnRequest request = open(order.orderId(), ReturnReason.SHORT_DELIVERY, ReturnResolution.REPLACEMENT, 1);
        assertThat(code(catchThrowable(() -> returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(
                null, null, "giao thiếu 1", null), sales)))).as("the signed delivery note, photographed")
                .isEqualTo(ErrorCode.RETURN_EVIDENCE_REQUIRED);
        withPhoto(request);
        ReturnRequest inspected = returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(null,
                null, "giao thiếu 1", null), sales);
        assertThat(inspected.status()).as("nothing to send back for a short delivery").isEqualTo(ReturnStatus.INSPECTED);

        ReturnRequest pending = returns.proposeResolution(request.id(), new ReturnRequestService.ResolveReturnCommand(
                List.of(new ReturnRequest.Remedy(only(request), 1, 0)), null, null, null), sales);
        assertThat(pending.status()).as("free goods wait for a second person too")
                .isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE replaces_return_request_id = ?",
                Long.class, request.id())).as("nothing placed before the approval").isZero();
        assertThat(code(catchThrowable(() -> returns.approveSettlement(request.id(), null, sales))))
                .isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
        ReturnRequest resolved = returns.approveSettlement(request.id(), null, accountant);
        assertThat(resolved.status()).isEqualTo(ReturnStatus.RESOLVED);
        assertThat(resolved.vatAdjustmentRequired()).isFalse();
        UUID replacement = resolved.replacementOrderId();
        placed.add(replacement);
        OrderSummary free = orders.findById(replacement).orElseThrow();
        assertThat(free.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(free.total().amount()).isZero();
        assertThat(free.lines()).singleElement().satisfies(l -> {
            assertThat(l.sku()).isEqualTo("SOFA-3S-GREY");
            assertThat(l.quantity()).isEqualTo(1);
            assertThat(l.lineId()).isEqualTo(resolved.lines().getFirst().replacementOrderLineId());
        });
        assertThat(jdbc.queryForObject("SELECT replaces_return_request_id FROM ordering.customer_order WHERE id = ?",
                UUID.class, replacement)).isEqualTo(request.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_reservation WHERE order_id = ? "
                + "AND status = 'HELD' AND expires_at IS NULL", Long.class, replacement)).as("held and pinned")
                .isEqualTo(1L);
        assertThat(releases.release(replacement, DemoData.WAREHOUSE_HCM, coordinator).status())
                .isEqualTo(OrderStatus.READY_TO_FULFILL);

        assertThat(statusOf(order.orderId())).as("replaced, nothing refunded").isEqualTo(OrderStatus.DELIVERED);
        backdateDelivery(order.orderId(), 8);
        assertThat(settlements.complete(order.orderId())).isTrue();
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("printed goods: never returned on a change of mind (BR-RMA-002), never back on sale (OQ-12), and "
            + "not reprinted without an approved sample or an earlier print run (BR-PRD-08)")
    void printedGoods() {
        UUID order = printedDelivered(100);
        assertThat(code(catchThrowable(() -> open(order, ReturnReason.CHANGED_MIND, ReturnResolution.REFUND, 10))))
                .isEqualTo(ErrorCode.RETURN_CUSTOM_ITEM_NOT_DEFECTIVE);

        ReturnRequest request = open(order, ReturnReason.PRINT_DEFECT, ReturnResolution.REPLACEMENT, 10);
        approved(request, true);
        returns.receive(request.id(), new ReturnRequestService.ReceiveReturnGoodsCommand("HCM-QC01",
                List.of(new ReturnRequest.Receipt(only(request), 10, null, null)), List.of(), null, null), warehouse);
        assertThat(code(catchThrowable(() -> returns.inspect(request.id(), new ReturnRequestService.InspectReturnCommand(
                List.of(new ReturnRequest.Inspection(only(request), 10, 0, ReturnItemCondition.DEFECTIVE, 10, 0, 0,
                        "HCM-A02-1-A", null)), null, null), qc)))).isEqualTo(ErrorCode.RETURN_RESTOCK_NOT_ALLOWED);
        returns.inspect(request.id(), new ReturnRequestService.InspectReturnCommand(List.of(new ReturnRequest.Inspection(
                only(request), 10, 0, ReturnItemCondition.DEFECTIVE, 0, 10, 0, null, "lem mực")), null, null), qc);

        assertThat(code(catchThrowable(() -> returns.proposeResolution(request.id(),
                new ReturnRequestService.ResolveReturnCommand(List.of(new ReturnRequest.Remedy(only(request), 10, 0)),
                        null, null, null), sales)))).isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);
        assertThat(queries.detail(request.id(), null).request().status()).as("nothing changed")
                .isEqualTo(ReturnStatus.INSPECTED);
        assertThat(returns.proposeResolution(request.id(), new ReturnRequestService.ResolveReturnCommand(
                List.of(new ReturnRequest.Remedy(only(request), 0, 10)), ReturnResolution.CREDIT_NOTE, null, null),
                sales).status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
    }

    @Test
    @DisplayName("refused when the order is not delivered, cancelled, past the window, someone else's, for too many "
            + "units, or a line already claimed; a replayed request returns the same return")
    void openingRefusals() {
        OrderSummary confirmed = place(PaymentTerm.CREDIT, 1);
        assertThat(code(catchThrowable(() -> open(confirmed.orderId(), ReturnReason.PRINT_DEFECT,
                ReturnResolution.REFUND, 1)))).isEqualTo(ErrorCode.RETURN_ORDER_NOT_DELIVERED);
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(confirmed.orderId(),
                new CancelOrderCommand(CancellationReasonCode.OTHER, "x", null, null)));
        assertThat(code(catchThrowable(() -> open(confirmed.orderId(), ReturnReason.PRINT_DEFECT,
                ReturnResolution.REFUND, 1)))).isEqualTo(ErrorCode.ORDER_CANCELLED);

        OrderSummary order = delivered(PaymentTerm.CREDIT, 2);
        UUID stranger = tx.execute(s -> ReferenceRows.customer(entityManager));
        assertThat(code(catchThrowable(() -> returns.openOwn(stranger, new ReturnRequestService.OpenReturnCommand(
                UUID.randomUUID(), order.orderId(), ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(lineOf(order.orderId()), 1))), customerUser))))
                .as("someone else's order is not revealed").isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(code(catchThrowable(() -> open(order.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
                3)))).isEqualTo(ErrorCode.RETURN_QUANTITY_EXCEEDED);

        UUID requestId = UUID.randomUUID();
        var command = new ReturnRequestService.OpenReturnCommand(requestId, order.orderId(), ReturnReason.PRINT_DEFECT,
                null, ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(lineOf(order.orderId()), 1)));
        ReturnRequest first = returns.openOwn(customer, command, customerUser);
        assertThat(returns.openOwn(customer, command, customerUser).id()).as("a replay").isEqualTo(first.id());
        assertThat(code(catchThrowable(() -> open(order.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
                1)))).isEqualTo(ErrorCode.RETURN_LINE_ALREADY_OPEN);
        OrderSummary other = delivered(PaymentTerm.CREDIT, 1);
        assertThat(code(catchThrowable(() -> returns.openOwn(customer, new ReturnRequestService.OpenReturnCommand(
                requestId, other.orderId(), ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(lineOf(other.orderId()), 1))), customerUser))))
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);

        // The table says it again: a line claimed beyond what was delivered is refused by the trigger.
        Throwable direct = catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, quantity, sku,
                                                         custom_printed, unit_price)
                VALUES (gen_random_uuid(), ?, ?, 5, 'SOFA-3S-GREY', FALSE, 1000000)""", first.id(),
                lineOf(order.orderId()))));
        assertThat(direct).isInstanceOf(DataAccessException.class).hasMessageContaining("would claim");

        backdateDelivery(other.orderId(), 8);
        assertThat(code(catchThrowable(() -> open(other.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
                1)))).isEqualTo(ErrorCode.RETURN_WINDOW_CLOSED);

        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET status = 'BLOCKED' WHERE id = ?",
                customer));
        OrderSummary third = orders.findById(other.orderId()).orElseThrow();
        backdateDelivery(third.orderId(), 1);
        assertThat(code(catchThrowable(() -> open(third.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND,
                1)))).as("a blocked account asks Sales").isEqualTo(ErrorCode.RETURN_CUSTOMER_NOT_ACTIVE);
        assertThat(returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(),
                third.orderId(), ReturnReason.PRINT_DEFECT, null, ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(lineOf(third.orderId()), 1))), sales).status())
                .isEqualTo(ReturnStatus.REQUESTED);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET status = 'ACTIVE' WHERE id = ?",
                customer));
    }

    @Test
    @DisplayName("rejected, the order is DELIVERED again and the customer may ask again; withdrawn after approval; "
            + "goods never sent expire and the order completes")
    void rejectedWithdrawnExpired() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 3);
        ReturnRequest refused = open(order.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND, 1);
        assertThat(code(catchThrowable(() -> returns.reject(refused.id(), " ", null, sales))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(returns.reject(refused.id(), "Không thấy lỗi trên ảnh", null, sales).status())
                .isEqualTo(ReturnStatus.REJECTED);
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.DELIVERED);

        ReturnRequest again = open(order.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND, 1);
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.RETURN_REQUESTED);
        approved(again, true);
        assertThat(code(catchThrowable(() -> returns.cancel(again.id(), UUID.randomUUID(), null, customerUser))))
                .as("not their return").isEqualTo(ErrorCode.RETURN_REQUEST_NOT_FOUND);
        assertThat(returns.cancel(again.id(), customer, "Tự xử lý được", customerUser).status())
                .isEqualTo(ReturnStatus.CANCELLED);
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.DELIVERED);

        ReturnRequest neverSent = open(order.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND, 2);
        approved(neverSent, true);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.return_request SET return_deadline = NOW() - "
                + "interval '1 hour' WHERE id = ?", neverSent.id()));
        assertThat(returns.expiryCandidates(1000)).contains(neverSent.id());
        assertThat(returns.expire(neverSent.id())).isTrue();
        assertThat(returns.expire(neverSent.id())).as("once").isFalse();
        assertThat(queries.detail(neverSent.id(), null).request().status()).isEqualTo(ReturnStatus.EXPIRED);
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.DELIVERED);
        backdateDelivery(order.orderId(), 8);
        assertThat(settlements.complete(order.orderId())).isTrue();
    }

    @Test
    @DisplayName("QC finds no defect: the return is REJECTED, the goods wait to go back to the customer, the order "
            + "is DELIVERED again; another SKU and an unknown item in the parcel are recorded")
    void noDefectAndWrongGoods() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 3);
        ReturnRequest request = open(order.orderId(), ReturnReason.WRONG_ITEM, ReturnResolution.REPLACEMENT, 2);
        approved(request, true);
        assertThat(code(catchThrowable(() -> returns.receive(request.id(),
                new ReturnRequestService.ReceiveReturnGoodsCommand("HCM-A02-1-A", List.of(new ReturnRequest.Receipt(
                        only(request), 2, null, null)), List.of(), null, null), warehouse))))
                .as("never straight onto a bin").isEqualTo(ErrorCode.RETURN_RECEIVING_LOCATION_INVALID);
        assertThat(code(catchThrowable(() -> returns.receive(request.id(),
                new ReturnRequestService.ReceiveReturnGoodsCommand(null, List.of(new ReturnRequest.Receipt(
                        only(request), 2, "NOT-OURS-1", null)), List.of(), null, null), warehouse))))
                .as("a SKU that is not ours is an unexpected item").isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
        ReturnRequest received = returns.receive(request.id(), new ReturnRequestService.ReceiveReturnGoodsCommand(null,
                List.of(new ReturnRequest.Receipt(only(request), 1, null, "LOT-9")),
                List.of(new ReturnRequest.Unexpected("NOT-OURS-1", 2, "hàng lạ trong thùng")), null, null), warehouse);
        assertThat(received.discrepancies()).extracting(d -> d.kind().name())
                .as("a shortfall is known only when no more parcels come").containsExactly("UNEXPECTED_ITEM");

        assertThat(code(catchThrowable(() -> returns.inspect(request.id(), new ReturnRequestService.InspectReturnCommand(
                List.of(new ReturnRequest.Inspection(only(request), 0, 1, ReturnItemCondition.GOOD, 0, 0, 1, null,
                        null)), "đúng hàng, không lỗi", null), qc)))).as("one approved unit may still come")
                .isEqualTo(ErrorCode.RETURN_PARCELS_OUTSTANDING);
        ReturnRequest rejected = returns.inspect(request.id(), new ReturnRequestService.InspectReturnCommand(List.of(
                new ReturnRequest.Inspection(only(request), 0, 1, ReturnItemCondition.GOOD, 0, 0, 1, null, null)),
                "đúng hàng, không lỗi", null, true), qc);
        assertThat(rejected.status()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(rejected.rejectionReason()).as("QC's words stay with staff").isEqualTo("NO_DEFECT_FOUND");
        assertThat(rejected.inspectionNote()).isEqualTo("đúng hàng, không lỗi");
        assertThat(rejected.discrepancies()).extracting(d -> d.kind().name())
                .containsExactlyInAnyOrder("SHORT", "UNEXPECTED_ITEM");
        assertThat(jdbc.queryForObject("SELECT status FROM inventory.stock_adjustment WHERE id = ?", String.class,
                rejected.lines().getFirst().customerReturnAdjustmentId())).isEqualTo("PENDING_APPROVAL");
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("two people approving at once: one wins, the other is told it moved on; a stale screen is refused; the "
            + "receipt racing the customer's withdrawal: the goods are counted in once, whichever came first")
    void concurrency() throws Exception {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 4);
        ReturnRequest request = open(order.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND, 2);
        ReturnRequest photographed = withPhoto(request);
        assertThat(code(catchThrowable(() -> returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(
                null, null, null, photographed.version() - 1), sales)))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        List<Throwable> outcomes = race(() -> returns.approve(request.id(),
                        new ReturnRequestService.ApproveReturnCommand(null, null, "A", null), sales),
                () -> returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(null, null, "B", null),
                        coordinator));
        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(java.util.Objects::nonNull).singleElement()
                .satisfies(t -> assertThat(code(t)).isIn(ErrorCode.RETURN_INVALID_TRANSITION, ErrorCode.OPTIMISTIC_LOCK));

        List<Throwable> race = race(() -> returns.receive(request.id(), new ReturnRequestService.ReceiveReturnGoodsCommand(
                        null, List.of(new ReturnRequest.Receipt(only(request), 2, null, null)), List.of(), null, null),
                        warehouse),
                () -> returns.cancel(request.id(), customer, null, customerUser));
        // The goods are here either way: counted in first, the withdrawal comes too late; withdrawn first, the parcel
        // that arrives anyway reopens the return.
        assertThat(race.get(0)).as("the parcel is always counted in").isNull();
        ReturnStatus end = queries.detail(request.id(), null).request().status();
        assertThat(end).isEqualTo(ReturnStatus.RECEIVED);
        Long quarantined = jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement WHERE reference_id = ? "
                + "AND movement_type = 'RETURN_IN'", Long.class, only(request));
        assertThat(quarantined).as("once").isEqualTo(1L);
    }

    @Test
    @DisplayName("goods in two parcels: one quarantine layer, one ledger line each; back on sale is a bin, not the QC "
            + "area")
    void parcels() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 4);
        ReturnRequest request = open(order.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.REFUND, 3);
        approved(request, true);
        ReturnRequest first = returns.receive(request.id(), new ReturnRequestService.ReceiveReturnGoodsCommand(null,
                List.of(new ReturnRequest.Receipt(only(request), 2, null, null)), List.of(), "đợt 1", null), warehouse);
        ReturnRequest second = returns.receive(request.id(), new ReturnRequestService.ReceiveReturnGoodsCommand(null,
                List.of(new ReturnRequest.Receipt(only(request), 1, null, null)), List.of(), "đợt 2", null), warehouse);
        assertThat(second.parcelCount()).isEqualTo(2);
        assertThat(second.lines().getFirst().receivedQuantity()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(on_hand) FROM inventory.stock_item WHERE location_code = 'HCM-QC01'"
                        + " AND sku = 'SOFA-3S-GREY' AND status = 'QUARANTINE' AND received_at = ?", Integer.class,
                java.sql.Timestamp.from(first.receivedAt()))).as("both parcels in the layer QC names").isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT quantity FROM inventory.stock_movement WHERE movement_type = 'RETURN_IN' "
                + "AND reference_id = ? ORDER BY occurred_at", Integer.class, only(request))).containsExactly(2, 1);

        assertThat(code(catchThrowable(() -> returns.inspect(request.id(), new ReturnRequestService.InspectReturnCommand(
                List.of(new ReturnRequest.Inspection(only(request), 3, 0, ReturnItemCondition.GOOD, 3, 0, 0, "HCM-QC01",
                        null)), null, null), qc)))).as("back on sale is a bin, not the QC area")
                .isEqualTo(ErrorCode.RETURN_RESTOCK_LOCATION_INVALID);
    }

    @Test
    @DisplayName("an approved return expires, the order completes and is CLOSED for good: a parcel after that is "
            + "refused, nothing is counted in and the return stays EXPIRED (BR-05)")
    void lateParcelOnAClosedOrder() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 3);
        ReturnRequest request = open(order.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND, 2);
        approved(request, true);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.return_request SET return_deadline = NOW() - "
                + "interval '1 hour' WHERE id = ?", request.id()));
        assertThat(returns.expire(request.id())).isTrue();
        backdateDelivery(order.orderId(), 8);
        assertThat(settlements.complete(order.orderId())).isTrue();
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET completed_at = NOW() - interval "
                + "'31 days' WHERE id = ?", order.orderId()));
        assertThat(settlements.close(order.orderId())).isTrue();

        assertThat(code(catchThrowable(() -> returns.receive(request.id(),
                new ReturnRequestService.ReceiveReturnGoodsCommand(null, List.of(new ReturnRequest.Receipt(only(request),
                        2, null, null)), List.of(), "về sau khi đơn đã đóng", null), warehouse))))
                .isEqualTo(ErrorCode.RETURN_ORDER_NOT_RETURNABLE);
        assertThat(queries.detail(request.id(), null).request().status()).isEqualTo(ReturnStatus.EXPIRED);
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CLOSED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement WHERE movement_type = 'RETURN_IN' "
                + "AND reference_id = ?", Long.class, only(request))).isZero();
    }

    @Test
    @DisplayName("staff limited to warehouses list, read and add warehouse photos to the returns of their warehouse's "
            + "orders; a clerk of another warehouse finds none of them (BR-SEC-002)")
    void staffOfAnotherWarehouse() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 2);
        ReturnRequest request = open(order.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.REFUND, 1);
        approved(request, true);
        CurrentUser here = clerkOf(DemoData.WAREHOUSE_HCM);
        CurrentUser elsewhere = clerkOf(UUID.randomUUID());

        assertThat(WithCurrentUser.call(here, DataScope.ALL, () -> queries.list(null, null, order.orderId(), null, null,
                0, 20, null)).items()).extracting(ReturnRequestQueries.Item::id).containsExactly(request.id());
        assertThat(WithCurrentUser.call(elsewhere, DataScope.ALL, () -> queries.list(null, null, order.orderId(), null,
                null, 0, 20, null)).items()).isEmpty();
        assertThat(WithCurrentUser.call(here, DataScope.ALL, () -> queries.detail(request.id(), null)).request().id())
                .isEqualTo(request.id());
        assertThat(code(catchThrowable(() -> WithCurrentUser.call(elsewhere, DataScope.ALL,
                () -> queries.detail(request.id(), null))))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);

        assertThat(code(catchThrowable(() -> WithCurrentUser.call(elsewhere, DataScope.WAREHOUSE,
                () -> returns.addEvidence(request.id(), null, ReturnEvidence.Stage.RECEIPT, photo(), warehouse)))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        ReturnRequest photographed = WithCurrentUser.call(here, DataScope.WAREHOUSE,
                () -> returns.addEvidence(request.id(), null, ReturnEvidence.Stage.RECEIPT, photo(), warehouse));
        assertThat(photographed.evidence()).extracting(ReturnEvidence::stage).contains(ReturnEvidence.Stage.RECEIPT);
    }

    @Test
    @DisplayName("an approved return expires, the goods come a week later: counted in, RECEIVED again, the order shows "
            + "the claim again")
    void goodsAfterTheDeadline() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 3);
        ReturnRequest request = open(order.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND, 2);
        approved(request, true);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.return_request SET return_deadline = NOW() - "
                + "interval '1 hour' WHERE id = ?", request.id()));
        assertThat(returns.expire(request.id())).isTrue();
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.DELIVERED);

        ReturnRequest received = returns.receive(request.id(), new ReturnRequestService.ReceiveReturnGoodsCommand(null,
                List.of(new ReturnRequest.Receipt(only(request), 2, null, null)), List.of(), "về trễ", null), warehouse);
        assertThat(received.status()).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(received.closedAt()).isNull();
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement WHERE movement_type = 'RETURN_IN' "
                + "AND reference_id = ?", Long.class, only(request))).isEqualTo(1L);
        assertThat(queries.detail(request.id(), null).history()).extracting(t -> t.reason())
                .contains("RECEIVED_AFTER_DEADLINE");
    }

    @Test
    @DisplayName("a late claim: Sales accept it with a reason on a completed order, another person approves it")
    void lateClaimBySales() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 2);
        backdateDelivery(order.orderId(), 30);
        assertThat(settlements.complete(order.orderId())).isTrue();
        var withoutReason = new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(), order.orderId(),
                ReturnReason.PRINT_DEFECT, null, ReturnResolution.CREDIT_NOTE,
                List.of(new ReturnRequest.Claim(lineOf(order.orderId()), 1)));
        assertThat(code(catchThrowable(() -> returns.openForCustomer(withoutReason, sales))))
                .isEqualTo(ErrorCode.RETURN_WINDOW_CLOSED);
        ReturnRequest late = returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(),
                order.orderId(), ReturnReason.PRINT_DEFECT, null, ReturnResolution.CREDIT_NOTE,
                List.of(new ReturnRequest.Claim(lineOf(order.orderId()), 1)), "Lỗi phát hiện khi mở pallet cuối"), sales);
        assertThat(late.windowOverride()).isEqualTo("Lỗi phát hiện khi mở pallet cuối");
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.RETURN_REQUESTED);
        withPhoto(late);
        assertThat(code(catchThrowable(() -> returns.approve(late.id(), new ReturnRequestService.ApproveReturnCommand(
                null, false, null, null), sales)))).isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
        assertThat(returns.approve(late.id(), new ReturnRequestService.ApproveReturnCommand(null, false, null, null),
                coordinator).status()).isEqualTo(ReturnStatus.INSPECTED);
    }

    @Test
    @DisplayName("a replacement order is cancelled only through its return: neither the customer nor staff cancel it; "
            + "withdrawn by two people, it is cancelled and its units credited (RMA-...-W)")
    void replacementWithdrawal() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 3);
        ReturnRequest request = open(order.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.REPLACEMENT, 1);
        approved(request, false);
        ReturnRequest resolved = resolved(request, 1, 0, null);
        UUID replacement = resolved.replacementOrderId();
        placed.add(replacement);

        assertThat(code(catchThrowable(() -> orders.cancelOwn(replacement, customer, null, null, customerUser))))
                .isEqualTo(ErrorCode.RETURN_REPLACEMENT_ORDER_LOCKED);
        assertThat(code(catchThrowable(() -> WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () ->
                orders.cancel(replacement, new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                        coordinator)))))).isEqualTo(ErrorCode.RETURN_REPLACEMENT_ORDER_LOCKED);

        returns.proposeReplacementWithdrawal(request.id(), "Khách muốn nhận tiền thay vì hàng", null, sales);
        assertThat(statusOf(replacement)).as("nothing changes before the approval").isEqualTo(OrderStatus.CONFIRMED);
        assertThat(code(catchThrowable(() -> returns.approveReplacementWithdrawal(request.id(), null, sales))))
                .isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
        ReturnRequest withdrawn = returns.approveReplacementWithdrawal(request.id(), null, accountant);
        assertThat(withdrawn.withdrawalStatus()).isEqualTo(ReturnRequest.WithdrawalStatus.APPROVED);
        assertThat(withdrawn.vatAdjustmentRequired()).as("money given back: the adjustment invoice").isTrue();
        assertThat(withdrawn.status()).isEqualTo(ReturnStatus.RESOLVED);
        assertThat(statusOf(replacement)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT cancellation_reason_code FROM ordering.customer_order WHERE id = ?",
                String.class, replacement)).isEqualTo("REPLACEMENT_WITHDRAWN");
        assertThat(withdrawn.withdrawalMoney().total()).isEqualByComparingTo(BigDecimal.valueOf(PRICE));
        assertThat(jdbc.queryForList("SELECT channel || ' ' || reference || ' ' || amount::bigint "
                + "FROM payment.customer_transfer WHERE id = ?", String.class,
                withdrawn.withdrawalMoney().creditTransferId()))
                .containsExactly("RETURN_CREDIT " + request.rmaNumber() + "-W " + PRICE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_reservation WHERE order_id = ? "
                + "AND status = 'HELD'", Long.class, replacement)).as("its stock released").isZero();
        assertThat(code(catchThrowable(() -> returns.proposeReplacementWithdrawal(request.id(), "again", null,
                sales)))).isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
    }

    @Test
    @DisplayName("a defective reprint: the replacement order's free line is credited at what the goods were paid, on the "
            + "original order's money")
    void claimOnAReplacement() {
        OrderSummary order = delivered(PaymentTerm.PREPAID, 3);
        ReturnRequest request = open(order.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.REPLACEMENT, 1);
        approved(request, false);
        UUID replacement = resolved(request, 1, 0, null).replacementOrderId();
        placed.add(replacement);
        deliver(replacement);
        assertThat(statusOf(replacement)).isEqualTo(OrderStatus.DELIVERED);

        ReturnRequest again = open(replacement, ReturnReason.DAMAGED_IN_TRANSIT, ReturnResolution.CREDIT_NOTE, 1);
        assertThat(again.lines().getFirst().unitPrice()).as("the price paid, not the replacement's zero")
                .isEqualByComparingTo(BigDecimal.valueOf(PRICE));
        approved(again, false);
        ReturnRequest credited = resolved(again, 0, 1, ReturnResolution.CREDIT_NOTE);
        assertThat(credited.money().credited()).isEqualByComparingTo(BigDecimal.valueOf(PRICE));
        assertThat(jdbc.queryForObject("SELECT customer_id FROM payment.customer_transfer WHERE id = ?", UUID.class,
                credited.money().creditTransferId())).isEqualTo(customer);
    }

    @Test
    @DisplayName("a photo must be an image whose bytes say so")
    void evidenceType() {
        OrderSummary order = delivered(PaymentTerm.CREDIT, 1);
        ReturnRequest request = open(order.orderId(), ReturnReason.PRINT_DEFECT, ReturnResolution.REFUND, 1);
        byte[] pdf = "%PDF-1.7 not an image".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(code(catchThrowable(() -> returns.addEvidence(request.id(), customer, null,
                new FileUpload("x.pdf", "application/pdf", pdf.length, new ByteArrayInputStream(pdf)), customerUser))))
                .isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(code(catchThrowable(() -> returns.addEvidence(request.id(), customer, null,
                new FileUpload("x.png", "image/png", pdf.length, new ByteArrayInputStream(pdf)), customerUser))))
                .as("a PDF declared as PNG").isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        ReturnRequest withOne = withPhoto(request);
        assertThat(withOne.evidence()).singleElement().satisfies(photo -> {
            assertThat(photo.stage()).isEqualTo(ReturnEvidence.Stage.CLAIM);
            assertThat(photo.contentType()).isEqualTo("image/png");
        });
        assertThat(queries.evidenceLink(request.id(), withOne.evidence().getFirst().id(), customer)).isNotBlank();
        assertThat(code(catchThrowable(() -> queries.evidenceLink(request.id(), withOne.evidence().getFirst().id(),
                UUID.randomUUID())))).isEqualTo(ErrorCode.RETURN_REQUEST_NOT_FOUND);
    }

    /** Runs both at once, each in its own transaction; null for the one that succeeded. */
    private static List<Throwable> race(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Callable<?> action : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        action.call();
                        return null;
                    } catch (Throwable thrown) {
                        return thrown;
                    }
                }));
            }
            start.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
