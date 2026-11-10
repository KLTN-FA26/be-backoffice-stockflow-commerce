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
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
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
    @Autowired ReturnReceiptService returnReceipts;
    @Autowired PaymentService payments;
    @Autowired InstallmentService installments;
    @Autowired PaymentReceiptService receipts;
    @Autowired RefundService refunds;
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
                    INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, allow_credit,
                        default_payment_term, credit_limit, credit_term_days, approved_by, approved_at)
                    VALUES (?, ?, true, false, true, 'PREPAID', 100000000, 30, ?, NOW())""", Identifiers.newId(),
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
            order.applyTerms(PaymentTerm.PREPAID, null, null, null);
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

    /** The goods of an approved return come back: a receipt of those cartons, confirmed; the return is then RECEIVED. */
    private void goodsBack(ReturnRequest request, Delivered d, int cartons) {
        var draft = asHcmClerk(() -> returnReceipts.create(new ReturnReceiptService.Create(null, request.id(), QUARANTINE,
                null), clerk));
        d.shipped().lpns().subList(0, cartons).forEach(lpn -> asHcmClerk(() -> returnReceipts.scan(draft.id(), lpn, null,
                clerk)));
        asHcmClerk(() -> returnReceipts.confirm(draft.id(), clerk));
        Await.until("return " + request.rmaNumber() + " received", ASYNC,
                () -> returnStatus(request.id()) == ReturnStatus.RECEIVED);
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
                "SELECT s.status FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id "
                        + "WHERE p.lpn = ?", String.class, lpn)).isEqualTo("QUARANTINE"));

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
}
