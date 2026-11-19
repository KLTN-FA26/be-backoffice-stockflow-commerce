package com.stockflow.order;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.service.PaymentReceiptService;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.VatOfTheDay;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DS-02b of the WF-02 review as the presenter walks it, on the rows db/demo leaves ({@code V20261203000100}): Trà Sữa
 * Mây (credit 300,000,000 at 15 days) owes an instalment overdue since order O01 of the demo history, and its order
 * SO-20261202-900002 of 60 cartons waits under a DRAFT contract that pays batch 2 after delivery - so the seed is
 * proven to be what the demo needs, not only to load.
 *
 * <ol>
 *   <li>demo.sales opens the contract: the DRAFT reads creditWarning OVERDUE (14 §4.3 step 13: decide before sending);
 *       sends it - warned, never refused (15 BR-11).</li>
 *   <li>demo.sales uploads the signed scan: the order goes ON_HOLD for CREDIT, the check OVERDUE recorded, and lands in
 *       the accountant's queue; its instalments open, nothing is held.</li>
 *   <li>demo.ketoan approves with a note: PENDING_PAYMENT - the deposit is still owed (14 §5.3).</li>
 *   <li>demo.ketoan records the deposit: CONFIRMED by itself.</li>
 * </ol>
 *
 * <p>The refusal (CANCELLED, {@code CREDIT_REJECTED}, the deposit to refund) is walked on fixtures in
 * {@link Wf02SalesFlowIntegrationTest#ds02bCreditRefusedRefundsTheDeposit}: the seeded order is used up by the approval.
 * The context is {@link Wf02SalesFlowIntegrationTest}'s (same configuration and mocks), so it starts nothing new.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.provider=smtp", "stockflow.notification.email.customer.enabled=false"})
class Wf02CreditExceptionSeedIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration ASYNC = Duration.ofSeconds(15);

    private static final UUID CUSTOMER = DemoData.demoId("demo:history:customer:TSM");
    private static final UUID ORDER = DemoData.demoId("demo:wf02b:order");
    private static final UUID SALES = DemoData.demoId("demo:wf02:user:demo.sales");
    private static final UUID ACCOUNTANT = DemoData.demoId("demo:wf02:user:demo.ketoan");

    @Autowired SalesContracts contracts;
    @Autowired CreditHoldService creditHolds;
    @Autowired OrderService orders;
    @Autowired PaymentService payments;
    @Autowired PaymentReceiptService receipts;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSenderImpl mail;

    private final CurrentUser sales = new CurrentUser(SALES, "demo.sales", Set.of(Role.SALES_STAFF), Set.of(),
            DataScope.ALL, Set.of(), Set.of());

    @Test
    @DisplayName("DS-02b on the seed: overdue customer -> DRAFT warned OVERDUE -> sent, warned -> signed: ON_HOLD (CREDIT) "
            + "-> approved with a note: PENDING_PAYMENT -> deposit recorded: CONFIRMED")
    void ds02bOnTheSeed() {
        LocalDate today = LocalDate.now(VIETNAM);
        // 60 cartons x 1,000 cups x 1,200 = 72,000,000 + VAT at the STANDARD rate of the build day (8% through 2026).
        BigDecimal total = new BigDecimal("72000000").add(VatOfTheDay.tax(72_000_000, VatOfTheDay.standard(jdbc)));
        BigDecimal deposit = total.multiply(new BigDecimal("0.30")).setScale(0, RoundingMode.HALF_UP);
        assertThat(payments.creditPosition(CUSTOMER).hasOverdue()).as("the precondition: something overdue").isTrue();
        assertThat(orders.findById(ORDER).orElseThrow().status()).isEqualTo(OrderStatus.DRAFT);

        // 1. The DRAFT, opened by Sales: warned before sending.
        SalesContracts.ContractView drafted = asSales(() -> contracts.get(ORDER));
        assertThat(drafted.status()).isEqualTo("DRAFT");
        assertThat(drafted.totals().totalAmount()).isEqualByComparingTo(total);
        assertThat(drafted.paymentPlan()).extracting(SalesContracts.InstallmentView::milestone)
                .containsExactly("ON_SIGNING", "BEFORE_DELIVERY", "AFTER_DELIVERY");
        assertThat(drafted.paymentPlan().getFirst().amount()).isEqualByComparingTo(deposit);
        assertThat(drafted.creditWarning()).isNotNull();
        assertThat(drafted.creditWarning().outcome()).isEqualTo("OVERDUE");
        assertThat(drafted.creditWarning().creditLimit()).isEqualByComparingTo("300000000");

        SalesContracts.ContractView sent = asSales(() -> contracts.send(sales, ORDER, drafted.version()));
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.creditWarning().outcome()).as("sent anyway, warned").isEqualTo("OVERDUE");
        assertThat(orders.findById(ORDER).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_CONTRACT);

        // 2. Signed: held for credit.
        byte[] scan = TestPdfs.blank();
        SalesContracts.ContractView signed = asSales(() -> contracts.sign(sales, ORDER, sent.version(),
                IncomingFiles.sent(List.of(new FileUpload("HD-20261202-900002-1-da-ky.pdf", "application/pdf",
                        scan.length, new ByteArrayInputStream(scan)))), today));
        assertThat(signed.status()).isEqualTo("SIGNED");
        var held = orders.findById(ORDER).orElseThrow();
        assertThat(held.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(held.lifecycle().holdReason()).isEqualTo(HoldReason.CREDIT);
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM ordering.order_credit_check WHERE order_id = ?
                ORDER BY checked_at DESC, created_at DESC LIMIT 1""", String.class, ORDER)).isEqualTo("OVERDUE");
        assertThat(creditHolds.heldOrders(0, 200).items()).contains(ORDER);
        assertThat(jdbc.queryForList("SELECT status FROM payment.installment WHERE order_id = ? ORDER BY seq",
                String.class, ORDER)).containsExactly("DUE", "NOT_DUE", "NOT_DUE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_reservation WHERE order_id = ?",
                Long.class, ORDER)).as("nothing held").isZero();

        // 3. Approved by the accountant: the deposit is still owed.
        assertThat(creditHolds.approve(ORDER, "Khách cam kết trả khoản quá hạn trong tuần này", ACCOUNTANT).status())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);

        // 4. The deposit recorded: confirmed by itself.
        UUID onSigning = jdbc.queryForObject("SELECT id FROM payment.installment WHERE order_id = ? AND milestone = "
                + "'ON_SIGNING'", UUID.class, ORDER);
        receipts.record(new PaymentReceiptService.RecordReceiptCommand(CUSTOMER,
                "FT" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(), today, null,
                List.of(new PaymentReceipt.Payment(onSigning, deposit)), ACCOUNTANT));
        Await.until("the order confirmed by its deposit", ASYNC,
                () -> orders.findById(ORDER).orElseThrow().status() == OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForList("SELECT to_status FROM ordering.order_status_history WHERE order_id = ? "
                + "ORDER BY occurred_at, created_at", String.class, ORDER))
                .containsSubsequence("DRAFT", "PENDING_CONTRACT", "ON_HOLD", "PENDING_PAYMENT", "CONFIRMED");
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }
}
