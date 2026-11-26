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
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.service.OrderSettlementService;
import com.stockflow.order.internal.service.ReturnRequestService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.service.InstallmentService;
import com.stockflow.payment.internal.service.PaymentReceiptService;
import com.stockflow.payment.internal.service.RefundService;
import com.stockflow.procurement.internal.service.ReturnReceiptService;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ShippedCartons;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Returns, from the claim to the refund (RMA; SCRUM-243 / SCRUM-260, reshaped by team plan 4D-2 / 4D-3; kltn-docs 17
 * §4.10, §5.1; 15 §4.5; 03 §4.2) against a real database and with every module beside it: a contract order paid on its
 * installments and delivered in cartons; the claim opened by Sales and approved by someone else; the goods back through
 * a return receipt into quarantine (the return and the order move on after commit, by the event); the refund proposed
 * by one person and approved by another, taking its value off the installments still owed and making the rest
 * refundable; the accountant's refund records moving the order to PARTIALLY_REFUNDED or REFUNDED.
 *
 * <p>The contract is the one {@code CancellationRefundIntegrationTest} signs: 1 000 pieces at 2 000 with 10% VAT
 * (2 200 000 dong, 20 cartons of 50), 30% on signing, 40% before delivery, 30% after delivery. A piece is worth 2 200
 * dong; the order is delivered the way the lifecycle leaves it (rows), its cartons CONSUMED on a DELIVERED shipment
 * ({@link ShippedCartons}).</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ReturnRequestIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(730_000);
    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final String QUARANTINE = "HCM-QC01";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D',
            'R', 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int CARTON = 50;

    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired ReturnRequestService returns;
    @Autowired com.stockflow.order.internal.service.ReturnRequestQueries returnQueries;
    @Autowired ReturnReceiptService returnReceipts;
    @Autowired com.stockflow.inventory.internal.service.CartonQuarantineService quarantine;
    @Autowired PaymentService payments;
    @Autowired InstallmentService installments;
    @Autowired PaymentReceiptService receipts;
    @Autowired RefundService refunds;
    @Autowired OrderSettlementService settlements;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID coordinator;
    private UUID accountant;
    private UUID clerk;
    private UUID customerId;
    private CustomerSkuRows.Issued sku;
    private LocalDate today;

    /** A delivered contract order: its id, its line, the installments in the plan's order, its shipped cartons. */
    private record Delivered(UUID orderId, UUID lineId, List<UUID> installmentIds, ShippedCartons.Shipped shipped) {

        UUID seq(int seq) {
            return installmentIds.get(seq - 1);
        }
    }

    @BeforeEach
    void setUp() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Cong ty Tra Hang', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "returns-" + customerId + "@example.com", "05" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '34 Le Loi, TP HCM', 'Tran Thi E', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
            jdbc.update("""
                    INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                        approved_at)
                    VALUES (?, ?, 100000000, 30, ?, NOW())""", Identifiers.newId(),
                    customerId, accountant);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, CARTON, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    private <T> T asHcmClerk(Supplier<T> call) {
        CurrentUser staff = new CurrentUser(clerk, "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of("HCM"), Set.of(DemoData.WAREHOUSE_HCM));
        return WithCurrentUser.call(staff, DataScope.WAREHOUSE, call);
    }

    /**
     * As warehouse staff of a warehouse other than HCM, under scope OWN: what the photo link declares for the
     * customer, and under which {@code WarehouseScope.restriction()} limits nothing.
     */
    private <T> T asOtherWarehouseClerkUnderOwn(Supplier<T> call) {
        CurrentUser staff = new CurrentUser(clerk, "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of("ELSE"), Set.of(Identifiers.newId()));
        return WithCurrentUser.call(staff, DataScope.OWN, call);
    }

    private static SalesContracts.Installment share(String name, String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    private static String reference() {
        return "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private void pay(UUID installmentId, long amount) {
        receipts.record(new PaymentReceiptService.RecordReceiptCommand(customerId, reference(), today, null,
                List.of(new PaymentReceipt.Payment(installmentId, BigDecimal.valueOf(amount))), accountant));
    }

    /**
     * A signed contract order (30 / 40 / 30), paid on signing and before delivery, delivered in {@code cartons} cartons
     * of 50 on one DELIVERED shipment; the installment after delivery is due and {@code payAfterDelivery} says whether
     * it is paid too.
     */
    private Delivered delivered(int cartons, boolean payAfterDelivery) {
        UUID orderId = tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), 1000, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), 20, CARTON).withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()), customerId,
                    Identifiers.newId(), List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
        UUID lineId = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        var drafted = asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(null,
                List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                        List.of(new SalesContracts.BatchLine(lineId, 20)))),
                List.of(share("Deposit", "ON_SIGNING", "30", 0), share("Before delivery", "BEFORE_DELIVERY", "40", null),
                        share("After delivery", "AFTER_DELIVERY", "30", 30)))));
        var sent = asSales(() -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        asSales(() -> contracts.sign(sales, orderId, sent.version(), IncomingFiles.sent(List.of(new FileUpload("signed.pdf",
                "application/pdf", scan.length, new ByteArrayInputStream(scan)))), today));
        List<UUID> ids = jdbc.queryForList("SELECT id FROM payment.installment WHERE order_id = ? ORDER BY seq",
                UUID.class, orderId);
        pay(ids.get(0), 660_000);
        Await.until("order confirmed", ASYNC,
                () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.CONFIRMED);
        installments.released(orderId, Instant.now());
        pay(ids.get(1), 880_000);
        // Delivered the way the lifecycle leaves an order (the goods' path is not this test's subject).
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");   // not through the transitions in between
            jdbc.update("""
                    UPDATE ordering.customer_order SET status = 'DELIVERED', warehouse_id = ?,
                           shipped_at = NOW() - interval '2 days', delivered_at = NOW() - interval '1 day' WHERE id = ?""",
                    DemoData.WAREHOUSE_HCM, orderId);
        });
        installments.delivered(orderId, Instant.now(), true);
        if (payAfterDelivery) {
            pay(ids.get(2), 660_000);
        }
        ShippedCartons.Shipped shipped = ShippedCartons.ship(jdbc, tx, orderId, lineId, sku.sku(), cartons, CARTON,
                "LOT-RET-" + orderId.toString().substring(0, 8), "DELIVERED", clerk);
        return new Delivered(orderId, lineId, ids, shipped);
    }

    private OrderStatus statusOf(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private ReturnStatus returnStatus(UUID returnId) {
        return ReturnStatus.valueOf(jdbc.queryForObject("SELECT status FROM ordering.return_request WHERE id = ?",
                String.class, returnId));
    }

    private ReturnRequest open(Delivered d, int units) {
        return asSales(() -> returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(),
                d.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, "cartons crushed in the truck", ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(d.lineId(), units))), sales.userId()));
    }

    private void photo(ReturnRequest request) {
        returns.addEvidence(request.id(), null, ReturnEvidence.Stage.CLAIM,
                new FileUpload("loi.png", "image/png", PNG.length, new ByteArrayInputStream(PNG)), sales.userId());
    }

    /** Opened by Sales, a photo, approved by the coordinator (never by who opened it). */
    private ReturnRequest approved(Delivered d, int units) {
        ReturnRequest request = open(d, units);
        photo(request);
        return returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(null, null, "ok", null),
                coordinator);
    }

    /**
     * The goods of an approved return come back: a receipt of those cartons, confirmed; fewer than approved, Sales say no
     * more is coming (WF-05 TH-3). The return is then RECEIVED.
     */
    private void goodsBack(ReturnRequest request, Delivered d, int cartons) {
        var draft = asHcmClerk(() -> returnReceipts.create(new ReturnReceiptService.Create(null, request.id(), QUARANTINE,
                null), clerk));
        d.shipped().lpns().subList(0, cartons).forEach(lpn -> asHcmClerk(() -> returnReceipts.scan(draft.id(), lpn, null,
                clerk)));
        asHcmClerk(() -> returnReceipts.confirm(draft.id(), clerk));
        complete(request, draft.id());
    }

    /** Once receipt {@code receiptId} was taken by the return, RECEIVED - Sales saying so when part is missing. */
    private void complete(ReturnRequest request, UUID receiptId) {
        Await.until("receipt " + receiptId + " taken by return " + request.rmaNumber(), ASYNC,
                () -> parcelTaken(receiptId));
        if (returnStatus(request.id()) == ReturnStatus.AWAITING_RETURN) {
            returns.markGoodsComplete(request.id(), "the customer has sent everything", null, sales.userId());
        }
        assertThat(returnStatus(request.id())).isEqualTo(ReturnStatus.RECEIVED);
    }

    private boolean parcelTaken(UUID receiptId) {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.return_request_receipt WHERE goods_receipt_id = ?",
                Long.class, receiptId) == 1;
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private BigDecimal installmentReduced(UUID installmentId) {
        return jdbc.queryForObject("SELECT reduced_amount FROM payment.installment WHERE id = ?", BigDecimal.class,
                installmentId);
    }

    private Map<String, Object> goodsReturnedSettlement(UUID returnId) {
        return jdbc.queryForMap("SELECT * FROM payment.order_settlement WHERE kind = 'GOODS_RETURNED' AND source_id = ?",
                returnId);
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("claim -> approval by another person -> goods back on a return receipt (RECEIVED, the order RETURNED, "
            + "the cartons in quarantine) -> refund proposed -> approved by a third person: the value comes off the "
            + "installment still owed, nothing is to refund, the order is PARTIALLY_REFUNDED at once")
    void returnedAndSetOffAgainstWhatIsStillOwed() {
        Delivered d = delivered(4, false);
        ReturnRequest request = open(d, 150);
        assertThat(request.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(request.rmaNumber()).matches("RMA-\\d{8}-\\d{4}");
        assertThat(statusOf(d.orderId())).isEqualTo(OrderStatus.RETURN_REQUESTED);

        // Sales opened it, so someone else approves it; and a photo of the claim comes first.
        photo(request);
        assertThat(code(catchThrowable(() -> returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(
                null, null, null, null), sales.userId())))).isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
        assertThat(code(catchThrowable(() -> returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(
                null, false, null, null), coordinator)))).as("the goods always come back (17 BR-18)")
                .isEqualTo(ErrorCode.RETURN_GOODS_REQUIRED);
        ReturnRequest awaiting = returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(null, null,
                "crushed cartons", null), coordinator);
        assertThat(awaiting.status()).isEqualTo(ReturnStatus.AWAITING_RETURN);

        // No refund before the goods are back.
        assertThat(code(catchThrowable(() -> returns.proposeRefund(request.id(),
                new ReturnRequestService.ProposeRefundCommand(null, null, null), sales.userId()))))
                .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);

        goodsBack(request, d, 3);
        assertThat(statusOf(d.orderId())).isEqualTo(OrderStatus.RETURNED);
        assertThat(jdbc.queryForMap("SELECT received_by, receiving_location_code FROM ordering.return_request "
                + "WHERE id = ?", request.id())).containsEntry("receiving_location_code", QUARANTINE)
                .containsEntry("received_by", clerk);
        assertThat(jdbc.queryForObject("SELECT goods_receipt_id FROM ordering.return_request WHERE id = ?", UUID.class,
                request.id())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT received_quantity FROM ordering.return_request_line WHERE "
                + "return_request_id = ?", Integer.class, request.id())).as("three cartons of 50").isEqualTo(150);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.return_request_discrepancy WHERE "
                + "return_request_id = ?", Integer.class, request.id())).isZero();
        assertThat(d.shipped().lpns().subList(0, 3)).allSatisfy(lpn -> assertThat(jdbc.queryForObject(
                "SELECT p.stock_status FROM inventory.stock_package p WHERE p.lpn = ?", String.class, lpn))
                .isEqualTo("QUARANTINE"));

        ReturnRequest proposed = returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null,
                "three cartons", null), sales.userId());
        assertThat(proposed.status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
        // 150 pieces at 2 000 with 10% VAT.
        assertThat(proposed.settlementAmount()).isEqualByComparingTo("330000");
        assertThat(code(catchThrowable(() -> returns.approveSettlement(request.id(), null, sales.userId()))))
                .as("four eyes: not the proposer").isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
        assertThat(returnStatus(request.id())).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);

        ReturnRequest resolved = returns.approveSettlement(request.id(), null, accountant);
        assertThat(resolved.status()).isEqualTo(ReturnStatus.RESOLVED);
        assertThat(resolved.vatAdjustmentRequired()).isTrue();
        Map<String, Object> settlement = goodsReturnedSettlement(request.id());
        assertThat((BigDecimal) settlement.get("reduced_amount")).as("off what is still owed")
                .isEqualByComparingTo("330000");
        assertThat((BigDecimal) settlement.get("refundable_amount")).isEqualByComparingTo("0");
        assertThat(installmentReduced(d.seq(3))).as("after delivery first").isEqualByComparingTo("330000");
        assertThat(installmentReduced(d.seq(1))).isEqualByComparingTo("0");
        // Everything made refundable (nothing) is refunded, and no return is open: the goods' value was not the order's.
        assertThat(payments.refundProgress(d.orderId()).settled()).isTrue();
        assertThat(statusOf(d.orderId())).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        // Approved twice: answered with the conflict, nothing is settled twice.
        assertThat(code(catchThrowable(() -> returns.approveSettlement(request.id(), null, accountant))))
                .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE source_id = ?", Integer.class,
                request.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("an order paid in full: the refund is to pay out - the order stays RETURNED until the accountant's "
            + "records add up to it, then PARTIALLY_REFUNDED; a refund sent back goes to RECEIVED and is proposed again")
    void returnedAndPaidOut() {
        Delivered d = delivered(4, true);
        ReturnRequest request = approved(d, 100);
        goodsBack(request, d, 2);

        returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null, "first try", null),
                sales.userId());
        assertThat(code(catchThrowable(() -> returns.rejectSettlement(request.id(), " ", null, accountant))))
                .as("a reason is required").isEqualTo(ErrorCode.VALIDATION_FAILED);
        ReturnRequest back = returns.rejectSettlement(request.id(), "recount the cartons", null, accountant);
        assertThat(back.status()).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(back.settlementAmount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE source_id = ?", Integer.class,
                request.id())).isZero();

        ReturnRequest again = returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(
                null, "second try", null), coordinator);
        assertThat(again.settlementAmount()).isEqualByComparingTo("220000");
        returns.approveSettlement(request.id(), null, accountant);

        Map<String, Object> settlement = goodsReturnedSettlement(request.id());
        assertThat((BigDecimal) settlement.get("reduced_amount")).as("nothing is owed any more").isEqualByComparingTo("0");
        assertThat((BigDecimal) settlement.get("refundable_amount")).isEqualByComparingTo("220000");
        assertThat(payments.refundProgress(d.orderId()).settled()).isFalse();
        assertThat(statusOf(d.orderId())).as("goods back, refund not paid yet").isEqualTo(OrderStatus.RETURNED);

        refunds.record(new RefundService.RecordRefundCommand(d.orderId(), new BigDecimal("100000"), today, reference(),
                null, accountant));
        assertThat(code(catchThrowable(() -> refunds.record(new RefundService.RecordRefundCommand(d.orderId(),
                new BigDecimal("120001"), today, reference(), null, accountant)))))
                .isEqualTo(ErrorCode.REFUND_EXCEEDS_REFUNDABLE);
        assertThat(payments.refundProgress(d.orderId()).settled()).isFalse();
        refunds.record(new RefundService.RecordRefundCommand(d.orderId(), new BigDecimal("120000"), today, reference(),
                null, accountant));
        Await.until("the order follows the refund", ASYNC, () -> statusOf(d.orderId()) == OrderStatus.PARTIALLY_REFUNDED);
        assertThat(payments.refundProgress(d.orderId()).settled()).isTrue();
        // Each record announced itself (OrderRefundRecorded): that is what moved the order, never a poll.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE event_type LIKE "
                + "'%OrderRefundRecorded' AND serialized_event LIKE ?", Long.class, "%" + d.orderId() + "%"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("everything returned and refunded: the order is REFUNDED, not partly")
    void everythingReturned() {
        Delivered d = delivered(20, true);
        ReturnRequest request = approved(d, 1000);
        goodsBack(request, d, 20);
        returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null, null, null),
                sales.userId());
        returns.approveSettlement(request.id(), null, accountant);
        assertThat((BigDecimal) goodsReturnedSettlement(request.id()).get("refundable_amount"))
                .isEqualByComparingTo("2200000");
        refunds.record(new RefundService.RecordRefundCommand(d.orderId(), new BigDecimal("2200000"), today, reference(),
                null, accountant));
        Await.until("the order follows the refund", ASYNC, () -> statusOf(d.orderId()) == OrderStatus.REFUNDED);
    }

    /** Every return of {@code d} back and settled: the whole order (2 200 000) to refund, nothing recorded yet. */
    private void returnedInFull(Delivered d) {
        ReturnRequest request = approved(d, 1000);
        goodsBack(request, d, 20);
        returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null, null, null),
                sales.userId());
        returns.approveSettlement(request.id(), null, accountant);
    }

    private UUID recordRefund(Delivered d, String reference) {
        refunds.record(new RefundService.RecordRefundCommand(d.orderId(), new BigDecimal("2200000"), today, reference,
                null, accountant));
        return jdbc.queryForObject("SELECT id FROM payment.refund_record WHERE reference = ?", UUID.class, reference);
    }

    /** Time passes: the order was refunded {@code days} ago, as far as the closing delay is concerned. */
    private void refundedDaysAgo(UUID orderId, int days) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET refunded_at = NOW() - "
                + "make_interval(days => ?) WHERE id = ?", days, orderId));
    }

    /**
     * Time passes for the delivery too: the order was delivered {@code days} ago - its shipments, or the order itself
     * when, as here, its goods were written as rows without shipments of the order. A refunded order closes
     * only once every delivered shipment's return window has ended (WF-05 Q-16 = b), and that window counts from the
     * shipment's outcome, not from the refund.
     */
    private void deliveredDaysAgo(UUID orderId, int days) {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");   // a test's clock, not a transition
            jdbc.update("UPDATE ordering.order_shipment SET outcome_at = NOW() - make_interval(days => ?) "
                    + "WHERE order_id = ? AND status = 'DELIVERED'", days, orderId);
            jdbc.update("UPDATE ordering.customer_order SET delivered_at = NOW() - make_interval(days => ?) "
                    + "WHERE id = ?", days, orderId);
        });
    }

    /** {@code count} OrderRefundRecorded of the order published, and every one handled by its listener. */
    private void refundEventsHandled(UUID orderId, int count) {
        Await.until(count + " refund events handled", ASYNC, () -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT count(*) = ? AND count(*) FILTER (WHERE completion_date IS NULL) = 0
                  FROM event_publication WHERE event_type LIKE '%OrderRefundRecorded' AND serialized_event LIKE ?""",
                Boolean.class, count, "%" + orderId + "%")));
    }

    @Test
    @DisplayName("TT-4: a refund voided takes a REFUNDED order back to RETURNED with the reason in its history, and a "
            + "refund owed keeps it from closing; recorded again it is REFUNDED and closes after the delay")
    void aVoidedRefundReopensTheOrderUntilItIsRefundedAgain() {
        Delivered d = delivered(20, true);
        returnedInFull(d);
        UUID first = recordRefund(d, reference());
        Await.until("the order follows the refund", ASYNC, () -> statusOf(d.orderId()) == OrderStatus.REFUNDED);
        refundedDaysAgo(d.orderId(), 40);
        deliveredDaysAgo(d.orderId(), 60);

        refunds.voidRecord(first, "  wrong statement line ", accountant);
        Await.until("the order follows the void", ASYNC, () -> statusOf(d.orderId()) == OrderStatus.RETURNED);
        OrderStatusChange reopened = orders.history(d.orderId()).getLast();
        assertThat(reopened.from()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(reopened.to()).isEqualTo(OrderStatus.RETURNED);
        assertThat(reopened.reason()).isEqualTo("REFUND_VOIDED: wrong statement line");
        assertThat(reopened.actorId()).isEqualTo(accountant);
        assertThat(jdbc.queryForObject("SELECT refunded_at IS NOT NULL FROM ordering.customer_order WHERE id = ?",
                Boolean.class, d.orderId())).as("kept: when it last became refunded").isTrue();
        assertThat(payments.refundProgress(d.orderId()).outstanding()).isEqualByComparingTo("2200000");
        assertThat(settlements.close(d.orderId())).as("RETURNED is not closed").isFalse();

        // The void's event not handled yet: the order still says REFUNDED, refunded long ago, while its refund is owed
        // again. The nightly closing asks payment under the order's lock and refuses it.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET status = 'REFUNDED' WHERE id = ?",
                d.orderId()));
        assertThat(settlements.close(d.orderId())).as("a refund is owed").isFalse();
        assertThat(statusOf(d.orderId())).isEqualTo(OrderStatus.REFUNDED);
        // The trigger takes the new edge too (V20261201000100): back where the void's event leaves it.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET status = 'RETURNED' WHERE id = ?",
                d.orderId()));

        recordRefund(d, reference());
        Await.until("the order follows the refund again", ASYNC,
                () -> statusOf(d.orderId()) == OrderStatus.REFUNDED);
        assertThat(settlements.close(d.orderId())).as("the delay counts from the new refund").isFalse();
        refundedDaysAgo(d.orderId(), 31);
        assertThat(contractStatus(d.orderId())).isEqualTo("SIGNED");
        assertThat(settlements.close(d.orderId())).isTrue();
        assertThat(statusOf(d.orderId())).isEqualTo(OrderStatus.CLOSED);
        // WF-05 DĐ-1: it never passed through COMPLETED, so its closing completes the contract.
        assertThat(contractStatus(d.orderId())).isEqualTo("COMPLETED");
    }

    private String contractStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM ordering.sales_contract WHERE order_id = ? AND status <> "
                + "'CANCELLED'", String.class, orderId);
    }

    @Test
    @DisplayName("TT-4 / BR-05: a refund voided after the order closed leaves it CLOSED; the debt stays on the "
            + "accountant's list with what remains, the oldest debt first")
    void aVoidAfterClosingShowsOnlyOnTheAccountantsList() {
        Delivered older = delivered(20, true);
        returnedInFull(older);
        UUID record = recordRefund(older, reference());
        Await.until("the order follows the refund", ASYNC, () -> statusOf(older.orderId()) == OrderStatus.REFUNDED);
        refundedDaysAgo(older.orderId(), 31);
        deliveredDaysAgo(older.orderId(), 60);
        assertThat(settlements.close(older.orderId())).isTrue();

        refunds.voidRecord(record, "the bank sent the transfer back", accountant);
        refundEventsHandled(older.orderId(), 2);
        assertThat(statusOf(older.orderId())).as("never reopened").isEqualTo(OrderStatus.CLOSED);
        assertThat(orders.history(older.orderId()).getLast().to()).isEqualTo(OrderStatus.CLOSED);

        // A newer debt of the same customer: settled after the older one, nothing refunded yet.
        Delivered newer = delivered(20, true);
        returnedInFull(newer);

        var outstanding = refunds.orderRefunds(customerId, true, 0, 20).items();
        assertThat(outstanding).extracting(com.stockflow.payment.internal.domain.OrderRefund::orderId)
                .as("oldest debt first").containsExactly(older.orderId(), newer.orderId());
        assertThat(outstanding).allSatisfy(row -> {
            assertThat(row.refunded()).isEqualByComparingTo("0");
            assertThat(row.remaining()).isEqualByComparingTo("2200000");
        });
        assertThat(refunds.orderRefunds(customerId, false, 0, 20).items()).isEmpty();
    }

    @Test
    @DisplayName("fewer cartons than approved: the return is RECEIVED with a SHORT discrepancy for Sales, and the "
            + "refund is for what came back, never more")
    void shortReceiptLeavesADiscrepancy() {
        Delivered d = delivered(4, true);
        ReturnRequest request = approved(d, 200);
        goodsBack(request, d, 3);
        assertThat(jdbc.queryForList("SELECT kind || ' ' || quantity FROM ordering.return_request_discrepancy WHERE "
                + "return_request_id = ?", String.class, request.id())).containsExactly("SHORT 50");
        assertThat(code(catchThrowable(() -> returns.proposeRefund(request.id(),
                new ReturnRequestService.ProposeRefundCommand(Map.of(request.lines().getFirst().id(), 200), null, null),
                sales.userId())))).as("only 150 came back").isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
        ReturnRequest proposed = returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null,
                null, null), sales.userId());
        assertThat(proposed.settlementAmount()).isEqualByComparingTo("330000");
    }

    @Test
    @DisplayName("without an approved return there is no receipt (RETURN_NOT_AWAITING_GOODS); one withdrawn before any "
            + "goods came leaves the order DELIVERED; goods not due yet do not expire")
    void withoutGoodsThereIsNoReceipt() {
        Delivered d = delivered(2, true);
        ReturnRequest requested = open(d, 50);
        assertThat(code(catchThrowable(() -> asHcmClerk(() -> returnReceipts.create(new ReturnReceiptService.Create(null,
                requested.id(), QUARANTINE, null), clerk))))).isEqualTo(ErrorCode.RETURN_NOT_AWAITING_GOODS);
        ReturnRequest withdrawn = returns.cancel(requested.id(), null, "changed our minds", sales.userId());
        assertThat(withdrawn.status()).isEqualTo(ReturnStatus.CANCELLED);
        assertThat(statusOf(d.orderId())).as("no return open, none brought goods back: delivered again")
                .isEqualTo(OrderStatus.DELIVERED);
        ReturnRequest second = approved(d, 50);
        assertThat(returns.expire(second.id())).as("the deadline has not passed").isFalse();
        assertThat(returnStatus(second.id())).isEqualTo(ReturnStatus.AWAITING_RETURN);
    }

    // ---- WF-05 TH-4: what came back is what was counted ----------------------------------------------------------------

    @Test
    @DisplayName("TH-4 / WF-05 Q-18: a carton counted 40 of 50 and 5 loose units with a photo: 45 received and refunded "
            + "(99,000); the opened carton is not stock - it stays consumed, marked, out of the ledger - and a warehouse "
            + "manager who counted nothing confirms the receipt; a count above the label and a loose SKU the order "
            + "does not have are refused")
    void anOpenedCartonAndLooseUnits() {
        Delivered d = delivered(4, true);
        ReturnRequest request = approved(d, 100);
        var draft = asHcmClerk(() -> returnReceipts.create(new ReturnReceiptService.Create(null, request.id(),
                QUARANTINE, null), clerk));
        String opened = d.shipped().lpns().getFirst();
        var scanned = asHcmClerk(() -> returnReceipts.scan(draft.id(), opened, "tape cut", 40, clerk));
        assertThat(scanned.cartons()).singleElement().satisfies(c -> {
            assertThat(c.countedQuantity()).isEqualTo(40);
            assertThat(c.opened()).isTrue();
            assertThat(c.stockEntered()).isFalse();
        });
        assertThat(code(catchThrowable(() -> asHcmClerk(() -> returnReceipts.scan(draft.id(), opened, null, 51,
                clerk))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        UUID photo = receiptPhoto(draft.id());
        assertThat(code(catchThrowable(() -> asHcmClerk(() -> returnReceipts.recordLoose(draft.id(), "NOT-OF-ORDER",
                null, 1, photo, clerk))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        var withLoose = asHcmClerk(() -> returnReceipts.recordLoose(draft.id(), sku.sku(), null, 5, photo, clerk));
        assertThat(withLoose.looseLines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(5));

        assertThat(code(catchThrowable(() -> asHcmClerk(() -> returnReceipts.confirm(draft.id(), clerk)))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_MANAGER_CONFIRMATION_REQUIRED);
        asHcmClerk(() -> returnReceipts.confirm(draft.id(), coordinator, true));
        complete(request, draft.id());
        assertThat(jdbc.queryForObject("SELECT received_quantity FROM ordering.return_request_line WHERE "
                + "return_request_id = ?", Integer.class, request.id())).isEqualTo(45);
        assertThat(jdbc.queryForMap("SELECT status, return_opened_at IS NOT NULL AS opened FROM inventory.stock_package "
                + "WHERE lpn = ?", opened)).as("no stock from a label: consumed, marked")
                .containsEntry("opened", true).containsEntry("status", "CONSUMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement WHERE reference_type = "
                + "'GOODS_RECEIPT' AND reference_id = ?", Long.class, draft.id())).as("opened and loose units are no stock")
                .isZero();

        ReturnRequest proposed = returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(
                null, null, null), sales.userId());
        assertThat(proposed.settlementAmount()).as("45 pieces at 2 200").isEqualByComparingTo("99000");
    }

    /** One of the receipt's own photos, as {@code POST /goods-receipts/{id}/photos} keeps it (WF-05 Q-18). */
    private UUID receiptPhoto(UUID receipt) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipt_photos (id, receipt_id, stage, storage_key, original_name,
                    content_type, size_bytes, checksum, taken_by, version, created_at)
                VALUES (?, ?, 'RECEIPT', ?, 'le.jpg', 'image/jpeg', 10, ?, ?, 0, NOW())""", id, receipt,
                "qc-evidence/" + id, "a".repeat(64), clerk));
        return id;
    }

    @Test
    @DisplayName("TH-4: loose units only - a receipt with no carton closes, and the return is RECEIVED with them")
    void looseUnitsOnly() {
        Delivered d = delivered(2, true);
        ReturnRequest request = approved(d, 50);
        var draft = asHcmClerk(() -> returnReceipts.create(new ReturnReceiptService.Create(null, request.id(),
                QUARANTINE, null), clerk));
        asHcmClerk(() -> returnReceipts.recordLoose(draft.id(), sku.sku(), "LOT-UNKNOWN", 30, receiptPhoto(draft.id()),
                clerk));
        assertThat(asHcmClerk(() -> returnReceipts.confirm(draft.id(), coordinator, true)).status().name())
                .isEqualTo("CLOSED");
        complete(request, draft.id());
        assertThat(jdbc.queryForObject("SELECT received_quantity FROM ordering.return_request_line WHERE "
                + "return_request_id = ?", Integer.class, request.id())).isEqualTo(30);
    }

    @Test
    @DisplayName("TH-4: nothing usable came back (one carton counted empty, confirmed by a manager): no refund can be "
            + "proposed, Sales close the return without one - RESOLVED, no settlement, no money")
    void nothingUsableClosesWithoutARefund() {
        Delivered d = delivered(2, true);
        ReturnRequest request = approved(d, 50);
        var draft = asHcmClerk(() -> returnReceipts.create(new ReturnReceiptService.Create(null, request.id(),
                QUARANTINE, null), clerk));
        asHcmClerk(() -> returnReceipts.scan(draft.id(), d.shipped().lpns().getFirst(), "empty", 0, clerk));
        asHcmClerk(() -> returnReceipts.confirm(draft.id(), coordinator, true));
        complete(request, draft.id());

        assertThat(code(catchThrowable(() -> returns.proposeRefund(request.id(),
                new ReturnRequestService.ProposeRefundCommand(null, null, null), sales.userId()))))
                .isEqualTo(ErrorCode.RETURN_QUANTITIES_INVALID);
        ReturnRequest closed = returns.closeWithoutRefund(request.id(), "the carton came back empty", null,
                sales.userId());

        assertThat(closed.status()).isEqualTo(ReturnStatus.RESOLVED);
        assertThat(closed.settlementAmount()).isZero();
        assertThat(closed.vatAdjustmentRequired()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE source_id = ?", Integer.class,
                request.id())).isZero();
        // WF-05 BE-45: nothing came back usable and nothing was refunded - never "partially refunded" (the order goes
        // back to the delivery path by G1's Order.returnsEndedWithoutRefund).
        assertThat(statusOf(d.orderId())).isNotIn(OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED);
    }

    // ---- WF-05 TH-3: the goods of one return in several parcels ----------------------------------------------------------

    @Test
    @DisplayName("TH-3: 100 approved, 50 back on Monday's receipt - still awaited, the order RETURN_REQUESTED; one draft "
            + "at a time; Thursday's receipt takes the other carton (not the first again): RECEIVED with 100, two "
            + "parcels on the books, the order RETURNED")
    void twoParcelsTwoReceipts() {
        Delivered d = delivered(4, true);
        ReturnRequest request = approved(d, 100);
        String first = d.shipped().lpns().get(0);
        String second = d.shipped().lpns().get(1);

        var monday = asHcmClerk(() -> returnReceipts.create(new ReturnReceiptService.Create(null, request.id(),
                QUARANTINE, null), clerk));
        asHcmClerk(() -> returnReceipts.scan(monday.id(), first, null, clerk));
        assertThat(code(catchThrowable(() -> asHcmClerk(() -> returnReceipts.create(new ReturnReceiptService.Create(
                null, request.id(), QUARANTINE, null), clerk))))).as("one draft at a time")
                .isEqualTo(ErrorCode.RETURN_RECEIPT_ALREADY_OPEN);
        asHcmClerk(() -> returnReceipts.confirm(monday.id(), clerk));
        Await.until("Monday's parcel taken", ASYNC, () -> parcelTaken(monday.id()));
        assertThat(returnStatus(request.id())).as("50 still to come").isEqualTo(ReturnStatus.AWAITING_RETURN);
        assertThat(jdbc.queryForObject("SELECT received_quantity FROM ordering.return_request_line WHERE "
                + "return_request_id = ?", Integer.class, request.id())).isEqualTo(50);
        assertThat(statusOf(d.orderId())).isEqualTo(OrderStatus.RETURN_REQUESTED);
        // Review defect: withdrawn now, Monday's 50 would sit in quarantine unrefunded and the order go back to DELIVERED.
        assertThat(code(catchThrowable(() -> returns.cancel(request.id(), null, "the customer gave up", sales.userId()))))
                .as("not withdrawn once a parcel is in").isEqualTo(ErrorCode.RETURN_GOODS_PARTLY_RECEIVED);
        assertThat(catchThrowable(() -> jdbc.update("UPDATE ordering.return_request SET status = 'CANCELLED' WHERE id = ?",
                request.id()))).as("nor by hand in the database")
                .hasMessageContaining("it is not withdrawn - mark its goods complete");
        assertThat(returnStatus(request.id())).isEqualTo(ReturnStatus.AWAITING_RETURN);
        assertThat(statusOf(d.orderId())).isEqualTo(OrderStatus.RETURN_REQUESTED);

        var thursday = asHcmClerk(() -> returnReceipts.create(new ReturnReceiptService.Create(null, request.id(),
                QUARANTINE, null), clerk));
        assertThat(code(catchThrowable(() -> asHcmClerk(() -> returnReceipts.scan(thursday.id(), first, null, clerk)))))
                .as("Monday's carton is back already").isEqualTo(ErrorCode.RETURN_CARTON_IN_WAREHOUSE);
        asHcmClerk(() -> returnReceipts.scan(thursday.id(), second, null, clerk));
        asHcmClerk(() -> returnReceipts.confirm(thursday.id(), clerk));
        Await.until("return " + request.rmaNumber() + " received", ASYNC,
                () -> returnStatus(request.id()) == ReturnStatus.RECEIVED);
        assertThat(jdbc.queryForObject("SELECT received_quantity FROM ordering.return_request_line WHERE "
                + "return_request_id = ?", Integer.class, request.id())).isEqualTo(100);
        assertThat(jdbc.queryForList("SELECT goods_receipt_id FROM ordering.return_request_receipt WHERE "
                + "return_request_id = ? ORDER BY parcel", UUID.class, request.id()))
                .containsExactly(monday.id(), thursday.id());
        assertThat(jdbc.queryForObject("SELECT goods_receipt_id FROM ordering.return_request WHERE id = ?", UUID.class,
                request.id())).as("the first receipt stays the one the return names").isEqualTo(monday.id());
        assertThat(jdbc.queryForList("SELECT kind FROM ordering.return_request_discrepancy WHERE return_request_id = ?",
                String.class, request.id())).isEmpty();
        assertThat(statusOf(d.orderId())).isEqualTo(OrderStatus.RETURNED);
        assertThat(code(catchThrowable(() -> returns.markGoodsComplete(request.id(), null, null, sales.userId()))))
                .as("nothing awaited any more").isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        assertThat(returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null, null, null),
                sales.userId()).settlementAmount()).as("100 pieces at 2 200").isEqualByComparingTo("220000");
    }

    // ---- WF-05 TH-2: an order with no installments -------------------------------------------------------------------

    @Test
    @DisplayName("TH-2: an order without installments (no contract signed) had nothing paid in the system: its refund "
            + "is proposed at 0, approved by someone else, RESOLVED without a settlement but with the invoice adjusted "
            + "for the units (WF-05 BE-44); the order PARTIALLY_REFUNDED")
    void noInstallmentsSettlesAtZero() {
        UUID orderId = tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), 100, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), 2, CARTON).withTax(BigDecimal.TEN);
            return orderRepository.save(Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()),
                    customerId, Identifiers.newId(), List.of(line), Instant.now())).id().value();
        });
        UUID lineId = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    UPDATE ordering.customer_order SET status = 'DELIVERED', warehouse_id = ?,
                           shipped_at = NOW() - interval '2 days', delivered_at = NOW() - interval '1 day' WHERE id = ?""",
                    DemoData.WAREHOUSE_HCM, orderId);
        });
        ShippedCartons.Shipped shipped = ShippedCartons.ship(jdbc, tx, orderId, lineId, sku.sku(), 2, CARTON,
                "LOT-SAMPLE-" + orderId.toString().substring(0, 8), "DELIVERED", clerk);
        Delivered d = new Delivered(orderId, lineId, List.of(), shipped);
        assertThat(payments.installmentStanding(orderId).opened()).isFalse();

        ReturnRequest request = approved(d, 100);
        goodsBack(request, d, 2);
        ReturnRequest proposed = returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null,
                "sample order, nothing was paid", null), sales.userId());
        assertThat(proposed.status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
        assertThat(proposed.settlementAmount()).isZero();
        assertThat(proposed.lines().getFirst().refundQuantity()).isEqualTo(100);
        assertThat(code(catchThrowable(() -> returns.approveSettlement(request.id(), null, sales.userId()))))
                .as("four eyes").isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);

        ReturnRequest resolved = returns.approveSettlement(request.id(), null, accountant);
        assertThat(resolved.status()).isEqualTo(ReturnStatus.RESOLVED);
        assertThat(resolved.vatAdjustmentRequired()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE source_id = ?", Integer.class,
                request.id())).isZero();
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("BR-SEC-002: a return's photo link is refused to warehouse staff of another warehouse though the "
            + "endpoint declares scope OWN (OUT_OF_DATA_SCOPE); staff of the order's warehouse get it")
    void photoLinkIsLimitedToTheOrdersWarehouse() {
        Delivered d = delivered(5, true);
        ReturnRequest request = open(d, 50);
        photo(request);
        UUID evidenceId = jdbc.queryForObject(
                "SELECT id FROM ordering.return_request_evidence WHERE return_request_id = ?", UUID.class, request.id());

        assertThat(code(catchThrowable(() -> asOtherWarehouseClerkUnderOwn(
                () -> returnQueries.evidenceLink(request.id(), evidenceId, null)))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(code(catchThrowable(() -> asOtherWarehouseClerkUnderOwn(
                () -> returnQueries.evidenceOriginalLink(request.id(), evidenceId, null)))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        // Staff of the order's warehouse pass the check and reach the object store, which the test profile does not
        // have (local storage signs no browser link): past the scope, not refused by it.
        CurrentUser hcm = new CurrentUser(clerk, "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of("HCM"), Set.of(DemoData.WAREHOUSE_HCM));
        assertThat(catchThrowable(() -> WithCurrentUser.call(hcm, DataScope.OWN,
                () -> returnQueries.evidenceLink(request.id(), evidenceId, null))))
                .isInstanceOf(com.stockflow.common.storage.StorageException.class);
    }
}
