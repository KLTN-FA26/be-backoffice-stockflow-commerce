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
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * kltn-docs 15 BR-11 as changed on 2026-11-26 (decision QĐ-B of the credit plan), against the real database: a contract
 * whose credit check fails when Sales send it is not sent but waits for accounting - no PDF drawn, locked against edits
 * - who approves it with a note (sent at once, as Sales) or refuses it with a reason (Sales' draft again, nothing
 * cancelled); Sales may withdraw it; a contract cancelled while it waits takes its review with it. At signing, one
 * approved before sending passes while no worse than approved (decision 2), and is held when worse.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ContractCreditReviewIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private CurrentUser accounting;
    private UUID customerId;
    private CustomerSkuRows.Issued sku;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        UUID accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        accounting = new CurrentUser(accountant, "accountant-" + accountant, Set.of(Role.ACCOUNTANT), Set.of(),
                DataScope.ALL, Set.of(), Set.of());
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, 'Cong ty Duyet No', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                    "credit-review-" + customerId + "@example.com", "05" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '7 Le Duan, TP HCM', 'Le Van D', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
            // 1,000,000 of credit: a contract of 2,200,000 paid after delivery does not fit it.
            jdbc.update("""
                    INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                        approved_at)
                    VALUES (?, ?, 1000000, 30, ?, NOW())""", Identifiers.newId(), customerId, accountant);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
    }

    /** A DRAFT order of 20 cartons of 50 at 2,000 a piece with 10% VAT: 2,200,000 with tax. */
    private UUID draftOrder() {
        return tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), 1000, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), 20, 50).withTax(BigDecimal.TEN);
            Order order = Order.draft(orderRepository.nextOrderNumber(today), customerId, Identifiers.newId(),
                    List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
    }

    private static SalesContracts.Installment share(String name, String milestone, String percent) {
        return new SalesContracts.Installment(name, milestone, null, new BigDecimal(percent), null, null);
    }

    private static List<SalesContracts.Installment> allAfterDelivery() {
        return List.of(share("After delivery", "AFTER_DELIVERY", "100"));
    }

    /** One batch three weeks out and {@code plan}, drawn up. */
    private SalesContracts.ContractView drawnUp(UUID orderId, List<SalesContracts.Installment> plan) {
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        return asSales(() -> contracts.create(sales, orderId, new SalesContracts.Draft(null, List.of(
                new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                        List.of(new SalesContracts.BatchLine(line, 20)))), plan)));
    }

    private SalesContracts.ContractView send(UUID orderId, long version) {
        return asSales(() -> contracts.send(sales, orderId, version));
    }

    private SalesContracts.ContractView approve(UUID orderId, long version, String note) {
        return WithCurrentUser.call(accounting, DataScope.ALL,
                () -> contracts.approveCredit(accounting, orderId, version, note));
    }

    private SalesContracts.ContractView sign(UUID orderId, long version) {
        byte[] scan = TestPdfs.blank();
        return asSales(() -> contracts.sign(sales, orderId, version, IncomingFiles.sent(List.of(new FileUpload(
                "signed.pdf", "application/pdf", scan.length, new ByteArrayInputStream(scan)))), today));
    }

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    private Map<String, Object> review(UUID reviewId) {
        return jdbc.queryForMap("SELECT outcome, check_outcome, credit_limit, exposure, after_delivery, decided_by, note "
                + "FROM ordering.contract_credit_review WHERE id = ?", reviewId);
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    @Test
    @DisplayName("over the limit: not sent, no PDF; locked; in accounting's queue; whoever sent it does not decide it")
    void overTheLimitWaitsForAccounting() {
        UUID orderId = draftOrder();
        SalesContracts.ContractView drafted = drawnUp(orderId, allAfterDelivery());
        assertThat(drafted.creditWarning().outcome()).as("warned before sending").isEqualTo("OVER_LIMIT");

        SalesContracts.ContractView waiting = send(orderId, drafted.version());

        assertThat(waiting.status()).isEqualTo("DRAFT");
        assertThat(waiting.creditReview()).isEqualTo("PENDING");
        assertThat(waiting.sentAt()).isNull();
        assertThat(status(orderId)).as("the order waits with its contract").isEqualTo(OrderStatus.DRAFT);
        assertThat(jdbc.queryForObject("SELECT sent_document_key FROM ordering.sales_contract WHERE id = ?",
                String.class, waiting.id())).as("no PDF drawn").isNull();
        SalesContracts.CreditReviewView snapshot = waiting.latestCreditReview();
        assertThat(snapshot.outcome()).isEqualTo("PENDING");
        assertThat(snapshot.checkOutcome()).isEqualTo("OVER_LIMIT");
        assertThat(snapshot.creditLimit()).isEqualByComparingTo("1000000");
        assertThat(snapshot.exposure()).isEqualByComparingTo("0");
        assertThat(snapshot.afterDelivery()).isEqualByComparingTo("2200000");
        assertThat(snapshot.contractVersion()).isEqualTo(drafted.version());
        assertThat(snapshot.submittedBy()).isEqualTo(sales.userId());
        assertThat(contracts.creditReviewsWaiting(0, 200).items()).filteredOn(w -> w.orderId().equals(orderId))
                .singleElement().satisfies(w -> {
                    assertThat(w.reviewId()).isEqualTo(snapshot.id());
                    assertThat(w.contractVersion()).isEqualTo(waiting.version());
                    assertThat(w.customerId()).isEqualTo(customerId);
                });

        assertThatThrownBy(() -> asSales(() -> contracts.edit(sales, orderId, waiting.version(),
                new SalesContracts.Draft(3, null, null)))).extracting(ContractCreditReviewIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_CREDIT_REVIEW_PENDING);
        assertThatThrownBy(() -> send(orderId, waiting.version())).extracting(ContractCreditReviewIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_CREDIT_REVIEW_PENDING);
        assertThatThrownBy(() -> asSales(() -> contracts.approveCredit(sales, orderId, waiting.version(), "ok")))
                .extracting(ContractCreditReviewIntegrationTest::code).isEqualTo(ErrorCode.CONTRACT_CREDIT_REVIEW_SELF);
        assertThatThrownBy(() -> approve(orderId, waiting.version(), " "))
                .extracting(ContractCreditReviewIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> approve(orderId, drafted.version(), "ok")).as("a version read before it waited")
                .extracting(ContractCreditReviewIntegrationTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    @DisplayName("refused with a reason: Sales' draft again, nothing cancelled; the plan changed, it is sent")
    void refusedThenChangedAndSent() {
        UUID orderId = draftOrder();
        SalesContracts.ContractView waiting = send(orderId, drawnUp(orderId, allAfterDelivery()).version());
        assertThatThrownBy(() -> WithCurrentUser.call(accounting, DataScope.ALL,
                () -> contracts.refuseCredit(accounting, orderId, waiting.version(), "")))
                .extracting(ContractCreditReviewIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);

        SalesContracts.ContractView refused = WithCurrentUser.call(accounting, DataScope.ALL,
                () -> contracts.refuseCredit(accounting, orderId, waiting.version(), "Thu trước 50%"));

        assertThat(refused.status()).isEqualTo("DRAFT");
        assertThat(refused.creditReview()).isEqualTo("NONE");
        assertThat(refused.latestCreditReview().outcome()).isEqualTo("REJECTED");
        assertThat(refused.latestCreditReview().note()).isEqualTo("Thu trước 50%");
        assertThat(refused.latestCreditReview().decidedBy()).isEqualTo(accounting.userId());
        assertThat(status(orderId)).isEqualTo(OrderStatus.DRAFT);
        assertThat(contracts.creditReviewsWaiting(0, 200).items()).noneMatch(w -> w.orderId().equals(orderId));

        SalesContracts.ContractView changed = asSales(() -> contracts.edit(sales, orderId, refused.version(),
                new SalesContracts.Draft(null, null, List.of(share("On signing", "ON_SIGNING", "100")))));
        assertThat(changed.creditWarning()).as("nothing after delivery any more").isNull();
        SalesContracts.ContractView sent = send(orderId, changed.version());
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(status(orderId)).isEqualTo(OrderStatus.PENDING_CONTRACT);
    }

    @Test
    @DisplayName("withdrawn by Sales; submitted again and cancelled while it waits: the review is withdrawn with it")
    void withdrawnAndCancelledWhileWaiting() {
        UUID orderId = draftOrder();
        SalesContracts.ContractView waiting = send(orderId, drawnUp(orderId, allAfterDelivery()).version());

        SalesContracts.ContractView withdrawn = asSales(() -> contracts.withdrawCreditReview(sales, orderId,
                waiting.version()));
        assertThat(withdrawn.creditReview()).isEqualTo("NONE");
        assertThat(withdrawn.latestCreditReview().outcome()).isEqualTo("WITHDRAWN");
        assertThatThrownBy(() -> asSales(() -> contracts.withdrawCreditReview(sales, orderId, withdrawn.version())))
                .extracting(ContractCreditReviewIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_CREDIT_REVIEW_NOT_PENDING);

        SalesContracts.ContractView again = send(orderId, withdrawn.version());
        assertThat(again.creditReview()).isEqualTo("PENDING");
        WithCurrentUser.run(sales, DataScope.ALL, () -> orders.cancel(orderId, new CancelOrderCommand(
                CancellationReasonCode.CUSTOMER_REQUEST, "khách rút đơn", null, sales.userId())));

        assertThat(status(orderId)).isEqualTo(OrderStatus.CANCELLED);
        Map<String, Object> closed = review(again.latestCreditReview().id());
        assertThat(closed.get("outcome")).isEqualTo("WITHDRAWN");
        assertThat(closed.get("decided_by")).isEqualTo(sales.userId());
        assertThat(jdbc.queryForObject("SELECT credit_review FROM ordering.sales_contract WHERE id = ?", String.class,
                again.id())).isEqualTo("NONE");
        assertThat(contracts.creditReviewsWaiting(0, 200).items()).noneMatch(w -> w.orderId().equals(orderId));
    }

    @Test
    @DisplayName("approved with a note: sent at once as Sales; signed no worse than approved: not held, the check "
            + "names the approval")
    void approvedSentAndSignedWithoutAHold() {
        UUID orderId = draftOrder();
        SalesContracts.ContractView waiting = send(orderId, drawnUp(orderId, allAfterDelivery()).version());

        SalesContracts.ContractView sent = approve(orderId, waiting.version(), "Khách lâu năm, trả đúng hạn");

        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.creditReview()).isEqualTo("NONE");
        assertThat(sent.sentBy()).as("sent as the Sales account that asked").isEqualTo(sales.userId());
        assertThat(jdbc.queryForObject("SELECT sent_document_key FROM ordering.sales_contract WHERE id = ?",
                String.class, sent.id())).as("the PDF the customer received").isNotNull();
        assertThat(review(sent.latestCreditReview().id())).containsEntry("outcome", "APPROVED")
                .containsEntry("decided_by", accounting.userId()).containsEntry("note", "Khách lâu năm, trả đúng hạn");
        assertThat(status(orderId)).isEqualTo(OrderStatus.PENDING_CONTRACT);

        sign(orderId, sent.version());

        assertThat(status(orderId)).as("nothing due on signing, not held: confirmed").isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForMap("SELECT outcome, pre_send_review_id FROM ordering.order_credit_check WHERE "
                + "order_id = ? ORDER BY checked_at DESC, created_at DESC LIMIT 1", orderId))
                .containsEntry("outcome", "OVER_LIMIT").containsEntry("pre_send_review_id", sent.latestCreditReview().id());
    }

    @Test
    @DisplayName("approved, then worse by the signature (another contract signed meanwhile): held for credit as before")
    void worseThanApprovedIsHeld() {
        UUID first = draftOrder();
        UUID second = draftOrder();
        SalesContracts.ContractView firstSent = approve(first,
                send(first, drawnUp(first, allAfterDelivery()).version()).version(), "ok one");
        SalesContracts.ContractView secondSent = approve(second,
                send(second, drawnUp(second, allAfterDelivery()).version()).version(), "ok two");

        sign(second, secondSent.version());
        assertThat(status(second)).as("as approved: owed 0 + 2,200,000").isEqualTo(OrderStatus.CONFIRMED);
        sign(first, firstSent.version());

        assertThat(status(first)).as("owed 2,200,000 + 2,200,000, approved on 0 + 2,200,000")
                .isEqualTo(OrderStatus.ON_HOLD);
        assertThat(orders.findById(first).orElseThrow().lifecycle().holdReason()).isEqualTo(HoldReason.CREDIT);
        Map<String, Object> check = jdbc.queryForMap("SELECT outcome, pre_send_review_id FROM ordering.order_credit_check "
                + "WHERE order_id = ? ORDER BY checked_at DESC, created_at DESC LIMIT 1", first);
        assertThat(check.get("outcome")).isEqualTo("OVER_LIMIT");
        assertThat(check.get("pre_send_review_id")).isNull();
    }
}
