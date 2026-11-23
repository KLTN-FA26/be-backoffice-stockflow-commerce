package com.stockflow.payment;

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
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.OrderSettlementResult;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.SettleRemainderClosureCommand;
import com.stockflow.payment.api.SettleShipmentReturnCommand;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.OrderRefund;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.service.InstallmentService;
import com.stockflow.payment.internal.service.PaymentReceiptService;
import com.stockflow.payment.internal.service.RefundService;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ShipmentRows;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-05 against the real database: a shipment the carrier brought back, or lost, settled as a return (BE-03, decision
 * Q-2: kind {@code SHIPMENT_RETURNED}, its checks and triggers), the fee still owed shown beside the refund (BE-51), and
 * a receipt that let goods go not voided once they went (BE-33, decision Q-20).
 *
 * <p>The order's own module would close the shipment's units and call {@code settleShipmentReturn} at the return
 * receipt's confirmation (G1); here the order's shipment rows are written by SQL - the one fact the settlement's
 * trigger reads - and the settlement is called directly, in a transaction as the order's would be.</p>
 *
 * <p>Every order is 2,200,000 dong (1,000 pieces at 2,000 with 10% VAT); the plan is 30% on signing (660,000), 40%
 * before delivery (880,000) and 30% after delivery (660,000), none tied to a batch.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ShipmentReturnSettlementIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(716_000);
    private static final long AMPLE_CREDIT = 100_000_000;

    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentService payments;
    @Autowired InstallmentService installments;
    @Autowired PaymentReceiptService receipts;
    @Autowired RefundService refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID accountant;
    private UUID customerId;
    private CustomerSkuRows.Issued sku;
    private LocalDate today;

    private record Signed(UUID orderId, List<UUID> installmentIds) {

        UUID seq(int seq) {
            return installmentIds.get(seq - 1);
        }
    }

    @BeforeEach
    void setUp() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Cong ty Hoan Hang', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "shipment-returns-" + customerId + "@example.com", "04" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '34 Le Loi, TP HCM', 'Tran Thi E', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
            jdbc.update("""
                    INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                        approved_at)
                    VALUES (?, ?, ?, 30, ?, NOW())""", Identifiers.newId(), customerId,
                    BigDecimal.valueOf(AMPLE_CREDIT), accountant);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private static SalesContracts.Installment share(String name, String milestone, String percent, Integer dueDays) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), dueDays, null);
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    /** A new order with the 30 / 40 / 30 plan, signed today. */
    private Signed signed() {
        UUID orderId = tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), 1000, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), 20, 50).withTax(BigDecimal.TEN);
            Order order = Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()), customerId,
                    Identifiers.newId(), List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        SalesContracts.ContractView drafted = asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(
                null, List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                List.of(new SalesContracts.BatchLine(line, 20)))), List.of(share("Deposit", "ON_SIGNING", "30", 0),
                share("Before delivery", "BEFORE_DELIVERY", "40", null),
                share("After delivery", "AFTER_DELIVERY", "30", 30)))));
        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, orderId, drafted.version()));
        byte[] scan = TestPdfs.blank();
        asSales(() -> contracts.sign(sales, orderId, sent.version(), IncomingFiles.sent(List.of(new FileUpload(
                "signed.pdf", "application/pdf", scan.length, new ByteArrayInputStream(scan)))), today));
        return new Signed(orderId, jdbc.queryForList(
                "SELECT id FROM payment.installment WHERE order_id = ? ORDER BY seq", UUID.class, orderId));
    }

    private void awaitConfirmed(UUID orderId) {
        Await.until("order " + orderId + " confirmed", Duration.ofSeconds(15),
                () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.CONFIRMED);
    }

    private PaymentReceipt pay(UUID installmentId, long amount) {
        String reference = "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        return receipts.record(new PaymentReceiptService.RecordReceiptCommand(customerId, reference, today, null,
                List.of(new PaymentReceipt.Payment(installmentId, BigDecimal.valueOf(amount))), accountant))
                .receipt();
    }

    /**
     * A shipment of the order handed over to a carrier, as the order knows it ({@code ordering.order_shipment}), in
     * {@code status}; written by SQL, as the order module's own tests hand goods over without packing.
     */
    private UUID shipment(UUID orderId, String status, String failureReason) {
        UUID id = ShipmentRows.ready(jdbc, tx, orderId, Identifiers.newId());
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, outcome_at,
                                                     failure_reason, version, created_at)
                VALUES (?, ?, ?, 2200000, NOW() - INTERVAL '2 hours',
                        CASE WHEN ? = 'HANDED_OVER' THEN NULL ELSE NOW() - INTERVAL '1 hour' END, ?, 0, NOW())""",
                id, orderId, status, status, failureReason));
        return id;
    }

    private SettleShipmentReturnCommand returned(UUID orderId, UUID shipmentId, UUID closureId, long goods,
                                                 long shippingWaived, SettleShipmentReturnCommand.Cause cause) {
        return new SettleShipmentReturnCommand(orderId, shipmentId, null, closureId, dong(goods), cause,
                Instant.now(), accountant, Set.of(), dong(shippingWaived));
    }

    private OrderSettlementResult settle(SettleShipmentReturnCommand command) {
        return tx.execute(s -> payments.settleShipmentReturn(command)).orElseThrow();
    }

    private InstallmentStatus status(UUID installmentId) {
        return installments.detail(installmentId, null).installment().status();
    }

    private void voidReceipt(PaymentReceipt receipt) {
        receipts.voidReceipt(new PaymentReceiptService.VoidReceiptCommand(receipt.id(), "Recorded against the wrong "
                + "statement line", true, accountant));
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    private static BigDecimal dong(long amount) {
        return BigDecimal.valueOf(amount);
    }

    // ---- BE-03 ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BE-03: a shipment brought back is settled once, as a return - its value off what is owed, what was "
            + "paid above it refundable - and the receipts it counted are not voided afterwards")
    void carrierReturnSettledOnce() {
        Signed signed = signed();
        PaymentReceipt deposit = pay(signed.seq(1), 660_000);
        pay(signed.seq(2), 880_000);
        awaitConfirmed(signed.orderId());
        UUID shipment = shipment(signed.orderId(), "RETURNED", "REFUSED");
        UUID closure = Identifiers.newId();

        OrderSettlementResult result = settle(returned(signed.orderId(), shipment, closure, 2_200_000, 0,
                SettleShipmentReturnCommand.Cause.CARRIER_RETURN));

        assertThat(result.kind()).isEqualTo("SHIPMENT_RETURNED");
        assertThat(result.collected()).isEqualByComparingTo(dong(1_540_000));
        assertThat(result.reduced()).as("the installment after delivery, still owed").isEqualByComparingTo(
                dong(660_000));
        assertThat(result.refundable()).as("everything paid comes back: nothing was kept").isEqualByComparingTo(
                dong(1_540_000));
        assertThat(result.fee()).isZero();
        assertThat(status(signed.seq(3))).isEqualTo(InstallmentStatus.CANCELLED);
        assertThat(jdbc.queryForMap("SELECT kind, shipment_id, source_id, shipping_fee_waived_amount FROM "
                + "payment.order_settlement WHERE id = ?", result.settlementId()))
                .containsEntry("kind", "SHIPMENT_RETURNED").containsEntry("shipment_id", shipment)
                .containsEntry("source_id", closure);

        // Idempotent on the closure, and once per shipment: a second receipt of the same shipment settles nothing.
        assertThat(settle(returned(signed.orderId(), shipment, closure, 2_200_000, 0,
                SettleShipmentReturnCommand.Cause.CARRIER_RETURN)).settlementId()).isEqualTo(result.settlementId());
        assertThat(settle(returned(signed.orderId(), shipment, Identifiers.newId(), 1_000_000, 0,
                SettleShipmentReturnCommand.Cause.CARRIER_RETURN)).settlementId()).isEqualTo(result.settlementId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE order_id = ?", Long.class,
                signed.orderId())).isEqualTo(1);
        // The database says it again: the same row under another closure, its triggers off so that only the unique
        // index can refuse it.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number,
                        contract_number, kind, source_id, currency, collected_amount, closed_amount, reduced_amount,
                        surplus_amount, fee_amount, fee_covered_amount, refundable_amount, fee_owed_amount,
                        settled_by, settled_at, shipment_id, shipping_fee_waived_amount, version, created_at)
                    SELECT ?, order_id, contract_id, customer_id, order_number, contract_number, kind, ?, currency,
                           collected_amount, closed_amount, reduced_amount, surplus_amount, fee_amount,
                           fee_covered_amount, refundable_amount, fee_owed_amount, settled_by, settled_at,
                           shipment_id, shipping_fee_waived_amount, 0, NOW()
                      FROM payment.order_settlement WHERE id = ?""", Identifiers.newId(), Identifiers.newId(),
                    result.settlementId());
        })).as("uk_order_settlement_shipment: one settlement per shipment")
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        RefundService.OrderRefundDetail refund = refunds.detail(signed.orderId(), null);
        assertThat(refund.settlements()).singleElement().satisfies(s -> {
            assertThat(s.kind().name()).isEqualTo("SHIPMENT_RETURNED");
            assertThat(s.shipmentId()).isEqualTo(shipment);
        });
        assertThat(refund.feeOwed()).isZero();

        // 15 §4.4 step 13: the deposit was counted by the settlement's refund; taking it back would leave the refund
        // wrong.
        assertThatThrownBy(() -> voidReceipt(deposit)).extracting(ShipmentReturnSettlementIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_VOID_NOT_ALLOWED);
    }

    @Test
    @DisplayName("BE-03: only a shipment whose goods are not with the customer is settled so - brought back, or lost "
            + "(DELIVERY_FAILED for LOST, Q-12); one on its way, or failed for another reason, is refused by the "
            + "database")
    void onlyGoodsNotWithTheCustomer() {
        Signed signed = signed();
        pay(signed.seq(1), 660_000);
        awaitConfirmed(signed.orderId());
        UUID onItsWay = shipment(signed.orderId(), "HANDED_OVER", null);
        UUID refused = shipment(signed.orderId(), "DELIVERY_FAILED", "REFUSED");
        UUID lost = shipment(signed.orderId(), "DELIVERY_FAILED", "LOST");

        assertThatThrownBy(() -> settle(returned(signed.orderId(), onItsWay, Identifiers.newId(), 1_100_000, 0,
                SettleShipmentReturnCommand.Cause.CARRIER_RETURN))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> settle(returned(signed.orderId(), refused, Identifiers.newId(), 1_100_000, 0,
                SettleShipmentReturnCommand.Cause.LOST))).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE order_id = ?", Long.class,
                signed.orderId())).as("a refused settlement leaves nothing").isZero();
        assertThat(status(signed.seq(3))).isEqualTo(InstallmentStatus.NOT_DUE);

        // Lost, nothing delivered: the shipping fee is waived with the goods (Q-19) and stated apart.
        OrderSettlementResult result = settle(returned(signed.orderId(), lost, Identifiers.newId(), 2_000_000,
                200_000, SettleShipmentReturnCommand.Cause.LOST));
        assertThat(result.kind()).isEqualTo("SHIPMENT_RETURNED");
        assertThat(result.refundable()).isEqualByComparingTo(dong(660_000));
        assertThat(jdbc.queryForObject("SELECT shipping_fee_waived_amount FROM payment.order_settlement WHERE id = ?",
                BigDecimal.class, result.settlementId())).isEqualByComparingTo(dong(200_000));
        assertThat(jdbc.queryForObject("SELECT closed_amount FROM payment.order_settlement WHERE id = ?",
                BigDecimal.class, result.settlementId())).isEqualByComparingTo(dong(2_200_000));
    }

    // ---- BE-51 ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BE-51: a fee item still owed on the order is shown beside the refund - on the order and on the "
            + "accountant's work list - and is never set off by itself")
    void feeOwedBesideTheRefund() {
        Signed signed = signed();
        pay(signed.seq(1), 660_000);
        awaitConfirmed(signed.orderId());
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET printing_started_at = NOW() WHERE id = ?", signed.orderId()));
        // Half the goods closed with an agreed fee above what the closure freed: the fee item owes 800,000.
        OrderSettlementResult closure = tx.execute(s -> payments.settleRemainderClosure(
                new SettleRemainderClosureCommand(signed.orderId(), Identifiers.newId(), dong(1_100_000),
                        dong(800_000), "Printed cups never shipped", "500 cups printed to the customer's design",
                        sales.userId(), Instant.now()))).orElseThrow();
        assertThat(closure.feeOwed()).isEqualByComparingTo(dong(800_000));
        // The other half came back with the carrier: the deposit is refundable.
        UUID shipment = shipment(signed.orderId(), "RETURNED", "REFUSED");
        OrderSettlementResult back = settle(returned(signed.orderId(), shipment, Identifiers.newId(), 1_100_000, 0,
                SettleShipmentReturnCommand.Cause.CARRIER_RETURN));
        assertThat(back.refundable()).isEqualByComparingTo(dong(660_000));

        RefundService.OrderRefundDetail detail = refunds.detail(signed.orderId(), null);
        assertThat(detail.feeOwed()).isEqualByComparingTo(dong(800_000));
        assertThat(detail.standing().remaining()).as("not set off").isEqualByComparingTo(dong(660_000));
        OrderRefund row = refunds.orderRefunds(customerId, null, 0, 20).items().stream()
                .filter(r -> r.orderId().equals(signed.orderId())).findFirst().orElseThrow();
        assertThat(row.feeOwed()).isEqualByComparingTo(dong(800_000));
        assertThat(row.remaining()).isEqualByComparingTo(dong(660_000));

        UUID feeItem = closure.feeItemId();
        pay(feeItem, 800_000);
        assertThat(refunds.detail(signed.orderId(), null).feeOwed()).isZero();
        assertThat(refunds.orderRefunds(customerId, null, 0, 20).items().stream()
                .filter(r -> r.orderId().equals(signed.orderId())).findFirst().orElseThrow().feeOwed()).isZero();
    }

    // ---- BE-33 ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BE-33 (Q-20): a receipt on signing or before delivery is not voided once the goods it let go were "
            + "handed over; before the handover it is, and one after delivery always is")
    void noVoidAfterHandover() {
        Signed before = signed();
        PaymentReceipt earlyDeposit = pay(before.seq(1), 660_000);
        awaitConfirmed(before.orderId());
        voidReceipt(earlyDeposit);
        assertThat(status(before.seq(1))).as("nothing handed over yet: voided").isEqualTo(InstallmentStatus.DUE);

        Signed after = signed();
        PaymentReceipt deposit = pay(after.seq(1), 660_000);
        awaitConfirmed(after.orderId());
        PaymentReceipt gate = pay(after.seq(2), 880_000);
        PaymentReceipt early = pay(after.seq(3), 100_000);
        shipment(after.orderId(), "HANDED_OVER", null);

        assertThatThrownBy(() -> voidReceipt(deposit)).extracting(ShipmentReturnSettlementIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_VOID_AFTER_HANDOVER);
        assertThatThrownBy(() -> voidReceipt(gate)).extracting(ShipmentReturnSettlementIntegrationTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_VOID_AFTER_HANDOVER);
        assertThat(status(after.seq(1))).isEqualTo(InstallmentStatus.PAID);
        assertThat(status(after.seq(2))).as("never left owing with no due day").isEqualTo(InstallmentStatus.PAID);

        voidReceipt(early);
        assertThat(installments.detail(after.seq(3), null).installment().paidAmount()).as("after delivery: voided")
                .isZero();
    }
}
