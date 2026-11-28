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
import com.stockflow.contracts.ContractChangeRequested;
import com.stockflow.contracts.ContractSignDeadlinePassed;
import com.stockflow.contracts.ContractTermsReviewDecided;
import com.stockflow.contracts.ContractTermsReviewRequested;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.ContractSignDeadlineService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SalesOwners;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract e-signing plan (2026-11-28), BE-A's part, against the real database: the terms review (QĐ-4), the
 * revisions and the customer's change request, the signing deadline and its nightly notice, CUSTOMER_SIGNED (which never
 * confirms the order), strict mode on every new Sales write, and the contract list with its tiles.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ContractNegotiationIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired SalesContracts contracts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired ContractSignDeadlineService deadlines;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationContext context;
    @Autowired RecordedEvents.Recorder recorded;
    @MockitoBean JavaMailSenderImpl mail;

    private SalesOwners owners;
    private CurrentUser sales;
    private CurrentUser manager;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        owners = new SalesOwners(context);
        sales = SalesOwners.caller(tx.execute(s -> ReferenceRows.user(entityManager)), Role.SALES_STAFF);
        manager = SalesOwners.caller(tx.execute(s -> ReferenceRows.user(entityManager)), Role.SALES_MANAGER);
        today = LocalDate.now(VIETNAM);
        recorded.clear();
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    /** A customer with a legal profile and its own SKU in cartons of 50. */
    private record Buyer(UUID customerId, CustomerSkuRows.Issued sku, String name) { }

    private Buyer buyer(String name) {
        UUID customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW())""", customerId, name,
                    "negotiation-" + customerId + "@example.com", "06" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)));
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '9 Hai Ba Trung, TP HCM', 'Pham Van E', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        return new Buyer(customerId, CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true),
                name);
    }

    /** A DRAFT order of 20 cartons of 50 at 2,000 a piece with 10% VAT: 2,200,000 with tax. */
    private UUID draftOrder(Buyer buyer) {
        return tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(buyer.sku().sku()), 1000, Money.vnd(2_000), null)
                    .withCartons(buyer.sku().uomConversionId(), 20, 50).withTax(BigDecimal.TEN);
            Order order = Order.draft(orderRepository.nextOrderNumber(today), buyer.customerId(), Identifiers.newId(),
                    List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
    }

    private SalesContracts.ContractView drawnUp(CurrentUser by, UUID orderId, SalesContracts.AgreementInput agreement) {
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        return as(by, () -> contracts.create(by, orderId, new SalesContracts.Draft(null, List.of(
                new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                        List.of(new SalesContracts.BatchLine(line, 20)))), List.of(
                new SalesContracts.Installment("On signing", "ON_SIGNING", null, new BigDecimal("100"), null, null)),
                null, agreement)));
    }

    private SalesContracts.ContractView drawnUp(UUID orderId, SalesContracts.AgreementInput agreement) {
        return drawnUp(sales, orderId, agreement);
    }

    private static SalesContracts.AgreementInput special() {
        return new SalesContracts.AgreementInput("Free replacement of damaged cartons within 30 days.", null, null, null,
                null, null);
    }

    private SalesContracts.ContractView send(UUID orderId, long version) {
        return as(sales, () -> contracts.send(sales, orderId, version));
    }

    private SalesContracts.ContractView approve(UUID orderId, long version, String note) {
        return as(manager, () -> contracts.approveTerms(manager, orderId, version, note));
    }

    private SalesContracts.ContractView submit(UUID orderId, long version) {
        return as(sales, () -> contracts.submitForTermsReview(sales, orderId, version));
    }

    private <T> T as(CurrentUser user, Supplier<T> call) {
        return WithCurrentUser.call(user, DataScope.ALL, call);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    private OrderStatus status(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    /** A standard contract, sent. */
    private SalesContracts.ContractView sentContract(Buyer buyer, UUID orderId) {
        return send(orderId, drawnUp(orderId, null).version());
    }

    private UUID customerUser() {
        return tx.execute(s -> ReferenceRows.user(entityManager));
    }

    // ---- terms review (QĐ-4) ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("QĐ-4: special terms are approved by a manager before the contract is sent; the sent contract carries them "
            + "and a deadline")
    void nonStandardTermsAreApprovedBeforeTheyAreSent() {
        Buyer buyer = buyer("Cong ty Dieu Khoan");
        UUID orderId = draftOrder(buyer);
        SalesContracts.ContractView drafted = drawnUp(orderId, special());
        assertThat(drafted.nonStandardTerms()).isTrue();
        assertThat(drafted.specialTerms()).isEqualTo("Free replacement of damaged cartons within 30 days.");
        assertThat(drafted.termsReview()).isEqualTo("NONE");
        assertThat(drafted.standardTerms().penaltyPercent()).as("the defaults an override is shown beside").isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT drawn_up_by FROM ordering.sales_contract WHERE id = ?", UUID.class,
                drafted.id())).isEqualTo(sales.userId());

        assertThatThrownBy(() -> send(orderId, drafted.version())).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_REQUIRED);

        SalesContracts.ContractView waiting = submit(orderId, drafted.version());
        assertThat(waiting.status()).isEqualTo("DRAFT");
        assertThat(waiting.termsReview()).isEqualTo("PENDING");
        assertThat(waiting.termsReviewDetail().requestedBy()).isEqualTo(sales.userId());
        assertThat(recorded.matching(ContractTermsReviewRequested.class, e -> e.orderId().equals(orderId))).hasSize(1);
        assertThatThrownBy(() -> as(sales, () -> contracts.edit(sales, orderId, waiting.version(),
                new SalesContracts.Draft(3, null, null)))).as("locked while a manager has it")
                .extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_PENDING);
        assertThatThrownBy(() -> send(orderId, waiting.version())).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_PENDING);
        assertThatThrownBy(() -> submit(orderId, waiting.version())).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_PENDING);
        assertThatThrownBy(() -> approve(orderId, drafted.version(), null)).as("a version read before it waited")
                .extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        SalesContracts.ContractView approved = approve(orderId, waiting.version(), "Agreed with the director");
        assertThat(approved.termsReview()).isEqualTo("APPROVED");
        assertThat(approved.termsReviewDetail().note()).isEqualTo("Agreed with the director");
        assertThat(approved.termsReviewDetail().decidedBy()).isEqualTo(manager.userId());
        assertThat(recorded.matching(ContractTermsReviewDecided.class, e -> e.orderId().equals(orderId)))
                .singleElement().satisfies(e -> {
                    assertThat(e.outcome()).isEqualTo("APPROVED");
                    assertThat(e.requestedBy()).isEqualTo(sales.userId());
                    assertThat(e.decidedBy()).isEqualTo(manager.userId());
                });
        assertThatThrownBy(() -> approve(orderId, approved.version(), null)).as("nothing waits any more")
                .extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_NOT_PENDING);

        SalesContracts.ContractView sent = send(orderId, approved.version());
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.revision()).isEqualTo(1);
        assertThat(sent.signBy()).as("seven days by default").isEqualTo(today.plusDays(7));
        assertThat(sent.overdueToSign()).isFalse();
        assertThat(sent.canSign()).isTrue();
        assertThat(sent.canRequestChanges()).isTrue();
        assertThat(sent.termsReview()).isEqualTo("APPROVED");
        assertThat(sent.specialTerms()).isNotNull();
        assertThat(as(sales, () -> contracts.revisions(orderId))).singleElement().satisfies(r -> {
            assertThat(r.revision()).isEqualTo(1);
            assertThat(r.documentAvailable()).isTrue();
            assertThat(r.sentBy()).isEqualTo(sales.userId());
            assertThat(r.changeRequestReason()).isNull();
        });
    }

    @Test
    @DisplayName("a standard contract needs no review: asking is refused, and the configured terms written down are standard")
    void aStandardContractGoesWithoutReview() {
        Buyer buyer = buyer("Cong ty Chuan");
        UUID orderId = draftOrder(buyer);
        SalesContracts.ContractView drafted = drawnUp(orderId, new SalesContracts.AgreementInput(null, 8, 5, 6,
                new SalesContracts.SellerSigner("Ms C", "Deputy director", "12/UQ-2026"),
                new SalesContracts.BuyerSigner("Mr D", "Owner")));
        assertThat(drafted.nonStandardTerms()).isFalse();
        assertThat(drafted.sellerSigner()).isEqualTo(new SalesContracts.SellerSigner("Ms C", "Deputy director",
                "12/UQ-2026"));
        assertThat(drafted.buyerSigner()).isEqualTo(new SalesContracts.BuyerSigner("Mr D", "Owner"));
        assertThatThrownBy(() -> submit(orderId, drafted.version())).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_NOT_NEEDED);
        assertThat(send(orderId, drafted.version()).status()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("an edit takes an approval back; a rejection needs a reason and hands the draft back; Sales may withdraw")
    void editRejectionAndWithdrawal() {
        Buyer buyer = buyer("Cong ty Tu Choi");
        UUID orderId = draftOrder(buyer);
        SalesContracts.ContractView drafted = drawnUp(orderId, new SalesContracts.AgreementInput(null, 5, null, null,
                null, null));
        assertThat(drafted.nonStandardTerms()).as("a penalty of 5% is not the configured 8%").isTrue();
        SalesContracts.ContractView waiting = submit(orderId, drafted.version());
        SalesContracts.ContractView approved = approve(orderId, waiting.version(), null);
        assertThat(approved.termsReviewDetail().note()).isNull();

        SalesContracts.ContractView edited = as(sales, () -> contracts.edit(sales, orderId, approved.version(),
                new SalesContracts.Draft(3, null, null)));
        assertThat(edited.termsReview()).as("any edit takes the approval back").isEqualTo("NONE");
        assertThat(edited.penaltyPercent()).as("what the edit did not mention is kept").isEqualTo(5);
        assertThatThrownBy(() -> send(orderId, edited.version())).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_REQUIRED);

        SalesContracts.ContractView again = submit(orderId, edited.version());
        assertThatThrownBy(() -> as(manager, () -> contracts.rejectTerms(manager, orderId, again.version(), " ")))
                .extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        SalesContracts.ContractView rejected = as(manager, () -> contracts.rejectTerms(manager, orderId,
                again.version(), "5% leaves us unprotected"));
        assertThat(rejected.termsReview()).isEqualTo("REJECTED");
        assertThat(rejected.termsReviewDetail().note()).isEqualTo("5% leaves us unprotected");
        assertThat(recorded.matching(ContractTermsReviewDecided.class, e -> e.orderId().equals(orderId)
                && e.outcome().equals("REJECTED"))).hasSize(1);

        // Back to the standard penalty, written down as such: nothing to approve any more.
        SalesContracts.ContractView standard = as(sales, () -> contracts.edit(sales, orderId, rejected.version(),
                new SalesContracts.Draft(null, null, null, null, new SalesContracts.AgreementInput(null, 8, null, null,
                        null, null))));
        assertThat(standard.nonStandardTerms()).isFalse();
        assertThat(standard.termsReview()).isEqualTo("NONE");

        // And withdrawn: a non-standard draft sent for review and taken back.
        SalesContracts.ContractView special = as(sales, () -> contracts.edit(sales, orderId, standard.version(),
                new SalesContracts.Draft(null, null, null, null, new SalesContracts.AgreementInput("A clause", null,
                        null, null, null, null))));
        SalesContracts.ContractView pending = submit(orderId, special.version());
        SalesContracts.ContractView withdrawn = as(sales, () -> contracts.withdrawTermsReview(sales, orderId,
                pending.version()));
        assertThat(withdrawn.termsReview()).isEqualTo("NONE");
        assertThatThrownBy(() -> as(sales, () -> contracts.withdrawTermsReview(sales, orderId, withdrawn.version())))
                .extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_NOT_PENDING);

        // A blank text clears the special terms.
        SalesContracts.ContractView cleared = as(sales, () -> contracts.edit(sales, orderId, withdrawn.version(),
                new SalesContracts.Draft(null, null, null, null, new SalesContracts.AgreementInput("  ", null, null,
                        null, null, null))));
        assertThat(cleared.specialTerms()).isNull();
    }

    // ---- revisions and the change request --------------------------------------------------------------------------------

    @Test
    @DisplayName("the customer asks for changes: SENT -> DRAFT revision 2, the order back to a draft, the PDF and the "
            + "reason kept; the contract is sent again as revision 2")
    void changeRequestAndRevisions() {
        Buyer buyer = buyer("Cong ty Chinh Sua");
        UUID orderId = draftOrder(buyer);
        SalesContracts.ContractView sent = sentContract(buyer, orderId);
        assertThat(status(orderId)).isEqualTo(OrderStatus.PENDING_CONTRACT);
        UUID account = customerUser();

        assertThatThrownBy(() -> contracts.requestChanges(buyer.customerId(), account, orderId, sent.version(), "short"))
                .extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> contracts.requestChanges(UUID.randomUUID(), account, orderId, sent.version(),
                "Please split the goods in two deliveries")).as("someone else's order")
                .extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> contracts.requestChanges(buyer.customerId(), account, orderId, sent.version() + 5,
                "Please split the goods in two deliveries")).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        SalesContracts.ContractView portal = contracts.requestChanges(buyer.customerId(), account, orderId,
                sent.version(), "  Please split the goods in two deliveries  ");

        assertThat(portal.status()).as("what the customer sees of a draft being revised").isEqualTo("CHANGES_REQUESTED");
        assertThat(portal.revision()).isEqualTo(2);
        assertThat(portal.deliverySchedule()).as("Sales' work in progress is not theirs yet").isEmpty();
        assertThat(portal.canSign()).isFalse();
        assertThat(portal.canRequestChanges()).isFalse();
        assertThat(status(orderId)).isEqualTo(OrderStatus.DRAFT);
        assertThat(jdbc.queryForMap("SELECT status, revision, sent_at, sent_document_key, sign_by "
                + "FROM ordering.sales_contract WHERE id = ?", sent.id()))
                .containsEntry("status", "DRAFT").containsEntry("revision", 2).containsEntry("sent_at", null)
                .containsEntry("sent_document_key", null).containsEntry("sign_by", null);
        assertThat(recorded.matching(ContractChangeRequested.class, e -> e.orderId().equals(orderId)))
                .singleElement().satisfies(e -> {
                    assertThat(e.revision()).as("the revision that ended").isEqualTo(1);
                    assertThat(e.contractId()).isEqualTo(sent.id());
                    assertThat(e.customerId()).isEqualTo(buyer.customerId());
                });
        assertThat(contracts.customerContract(buyer.customerId(), orderId).status()).isEqualTo("CHANGES_REQUESTED");
        assertThatThrownBy(() -> contracts.requestChanges(buyer.customerId(), account, orderId, portal.version(),
                "Please split the goods in two deliveries")).as("a draft is not asked changes of twice")
                .extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        assertThat(contracts.customerRevisions(buyer.customerId(), orderId)).singleElement().satisfies(r -> {
            assertThat(r.changeRequestReason()).isEqualTo("Please split the goods in two deliveries");
            assertThat(r.changeRequestedAt()).isNotNull();
            assertThat(r.sentBy()).as("no sender named to the customer").isNull();
            assertThat(r.sentByName()).isNull();
        });

        // Sales change it and send it again: revision 2, a deadline of its own, revision 1 kept with its reason.
        SalesContracts.ContractView backOffice = as(sales, () -> contracts.get(orderId));
        assertThat(backOffice.status()).isEqualTo("DRAFT");
        assertThat(backOffice.revision()).isEqualTo(2);
        assertThat(backOffice.sentAt()).isNull();
        SalesContracts.ContractView second = send(orderId, as(sales, () -> contracts.edit(sales, orderId,
                backOffice.version(), new SalesContracts.Draft(4, null, null))).version());
        assertThat(second.status()).isEqualTo("SENT");
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.signBy()).isEqualTo(today.plusDays(7));
        assertThat(status(orderId)).isEqualTo(OrderStatus.PENDING_CONTRACT);
        List<SalesContracts.RevisionView> revisions = as(sales, () -> contracts.revisions(orderId));
        assertThat(revisions).extracting(SalesContracts.RevisionView::revision).containsExactly(1, 2);
        assertThat(revisions.getFirst().changeRequestReason()).isEqualTo("Please split the goods in two deliveries");
        assertThat(revisions.getLast().changeRequestReason()).isNull();
        // Each revision keeps the PDF it was sent as (the download link needs the S3 provider, which this test has not).
        assertThat(jdbc.queryForList("SELECT sent_document_key FROM ordering.sales_contract_revision WHERE contract_id = ? "
                + "ORDER BY revision", String.class, sent.id())).hasSize(2).doesNotContainNull()
                .doesNotHaveDuplicates();
        assertThatThrownBy(() -> as(sales, () -> contracts.revisionDocument(orderId, 3)))
                .extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.sales_contract_revision WHERE contract_id = ?",
                Long.class, sent.id())).isEqualTo(2);
    }

    // ---- the signing deadline ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Sales move the deadline while the contract is SENT (not past, 60 days at most); a draft has none")
    void extendingTheDeadline() {
        Buyer buyer = buyer("Cong ty Han Ky");
        UUID orderId = draftOrder(buyer);
        SalesContracts.ContractView drafted = drawnUp(orderId, null);
        assertThatThrownBy(() -> as(sales, () -> contracts.extendSignBy(sales, orderId, drafted.version(),
                today.plusDays(10)))).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        SalesContracts.ContractView sent = send(orderId, drafted.version());

        assertThatThrownBy(() -> as(sales, () -> contracts.extendSignBy(sales, orderId, sent.version(),
                today.minusDays(1)))).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> as(sales, () -> contracts.extendSignBy(sales, orderId, sent.version(),
                today.plusDays(61)))).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        SalesContracts.ContractView extended = as(sales, () -> contracts.extendSignBy(sales, orderId, sent.version(),
                today.plusDays(60)));
        assertThat(extended.signBy()).isEqualTo(today.plusDays(60));
        assertThat(extended.status()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("past the deadline the contract is overdue to sign (and cannot be signed); the nightly run announces it "
            + "once per revision, and a contract extended is not")
    void overdueContractIsAnnouncedOnce() {
        Buyer buyer = buyer("Cong ty Qua Han");
        UUID orderId = draftOrder(buyer);
        SalesContracts.ContractView sent = sentContract(buyer, orderId);
        Buyer onTime = buyer("Cong ty Dung Han");
        UUID onTimeOrder = draftOrder(onTime);
        SalesContracts.ContractView stillOpen = sentContract(onTime, onTimeOrder);
        assertThat(deadlines.announceOverdue()).as("nothing is overdue yet").isZero();
        recorded.clear();

        // Time cannot move in the test: the contract is sent ten days ago, its deadline was the day before yesterday.
        tx.executeWithoutResult(s -> {
            jdbc.execute("ALTER TABLE ordering.sales_contract DISABLE TRIGGER tg_sales_contract_transition");
            jdbc.update("UPDATE ordering.sales_contract SET sent_at = sent_at - interval '10 days', sign_by = ? "
                    + "WHERE id = ?", java.sql.Date.valueOf(today.minusDays(2)), sent.id());
            // The deferred checks of the update run now: a table with trigger events pending takes no ALTER.
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            jdbc.execute("ALTER TABLE ordering.sales_contract ENABLE TRIGGER tg_sales_contract_transition");
        });

        SalesContracts.ContractView overdue = as(sales, () -> contracts.get(orderId));
        assertThat(overdue.overdueToSign()).isTrue();
        assertThat(overdue.canSign()).as("overdue: Sales extend it first").isFalse();
        assertThat(overdue.canRequestChanges()).as("asking for changes is still the customer's right").isTrue();
        assertThat(as(sales, () -> contracts.get(onTimeOrder)).overdueToSign()).isFalse();
        assertThatThrownBy(() -> contracts.customerSigned(buyer.customerId(), customerUser(), orderId, overdue.version()))
                .extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.CONTRACT_SIGN_DEADLINE_PASSED);
        SalesContracts.ContractSummary summary = contracts.summary();
        assertThat(summary.overdueToSign()).isGreaterThanOrEqualTo(1);
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of("SENT"), null, today, null,
                overdue.contractNumber(), 0, 20)).items()).singleElement().satisfies(row -> {
                    assertThat(row.contractId()).isEqualTo(sent.id());
                    assertThat(row.overdueToSign()).isTrue();
                });

        assertThat(deadlines.announceOverdue()).isGreaterThanOrEqualTo(1);
        assertThat(recorded.matching(ContractSignDeadlinePassed.class, e -> e.orderId().equals(orderId)))
                .singleElement().satisfies(e -> {
                    assertThat(e.revision()).isEqualTo(1);
                    assertThat(e.signBy()).isEqualTo(today.minusDays(2));
                    assertThat(e.customerId()).isEqualTo(buyer.customerId());
                });
        assertThat(recorded.matching(ContractSignDeadlinePassed.class, e -> e.orderId().equals(onTimeOrder)))
                .as("the contract still open").isEmpty();
        recorded.clear();
        assertThat(deadlines.announceOverdue()).as("once per revision").isZero();
        assertThat(recorded.ofType(ContractSignDeadlinePassed.class)).isEmpty();

        // Extended, it is no longer overdue; nothing is cancelled by the deadline.
        SalesContracts.ContractView extended = as(sales, () -> contracts.extendSignBy(sales, orderId,
                overdue.version(), today.plusDays(5)));
        assertThat(extended.overdueToSign()).isFalse();
        assertThat(extended.canSign()).isTrue();
        assertThat(extended.status()).isEqualTo("SENT");
        assertThat(contracts.customerSigned(buyer.customerId(), customerUser(), orderId, extended.version()).status())
                .as("signed once Sales extended the deadline").isEqualTo("CUSTOMER_SIGNED");
        assertThat(stillOpen.status()).isEqualTo("SENT");
    }

    // ---- CUSTOMER_SIGNED ---------------------------------------------------------------------------------------------------

    /** The customer signs through the seam the signing machinery uses. */
    private void customerSigns(Buyer buyer, UUID orderId) {
        long version = as(sales, () -> contracts.get(orderId)).version();
        contracts.customerSigned(buyer.customerId(), customerUser(), orderId, version);
    }

    @Test
    @DisplayName("CUSTOMER_SIGNED is not signed: the order keeps waiting, the customer cannot ask for changes, the contract "
            + "reads as sent and waiting for the company; a file signed by both makes it SIGNED and confirms the order")
    void customerSignedDoesNotConfirmTheOrder() {
        Buyer buyer = buyer("Cong ty Da Ky");
        UUID orderId = draftOrder(buyer);
        sentContract(buyer, orderId);

        customerSigns(buyer, orderId);

        assertThat(status(orderId)).as("the order reacts only to SIGNED").isEqualTo(OrderStatus.PENDING_CONTRACT);
        SalesContracts.ContractView waiting = as(sales, () -> contracts.get(orderId));
        assertThat(waiting.status()).isEqualTo("CUSTOMER_SIGNED");
        assertThat(waiting.customerSignedAt()).isNotNull();
        assertThat(waiting.signedAt()).isNull();
        assertThat(waiting.canSign()).isFalse();
        assertThat(waiting.canRequestChanges()).isFalse();
        assertThatThrownBy(() -> contracts.customerSigned(buyer.customerId(), customerUser(), orderId, waiting.version()))
                .as("twice").extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_NOT_SIGNABLE);
        assertThatThrownBy(() -> contracts.customerSigned(UUID.randomUUID(), customerUser(), orderId, waiting.version()))
                .as("someone else's order").extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.installment i JOIN ordering.sales_contract c ON "
                + "c.id = i.contract_id WHERE c.id = ?", Long.class, waiting.id())).as("no installments yet").isZero();
        assertThat(contracts.customerContract(buyer.customerId(), orderId).status()).isEqualTo("CUSTOMER_SIGNED");
        assertThatThrownBy(() -> contracts.requestChanges(buyer.customerId(), customerUser(), orderId, waiting.version(),
                "Please split the goods in two deliveries")).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        assertThatThrownBy(() -> as(sales, () -> contracts.extendSignBy(sales, orderId, waiting.version(),
                today.plusDays(9)))).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        assertThat(as(sales, () -> contracts.history(orderId))).singleElement()
                .extracting(SalesContracts.ContractView::status).isEqualTo("CUSTOMER_SIGNED");
        assertThat(contracts.summary().awaitingCompanySignature()).isGreaterThanOrEqualTo(1);

        // The existing "record a signed file" path works from CUSTOMER_SIGNED, and then the order is placed.
        byte[] scan = TestPdfs.blank();
        SalesContracts.ContractView signed = as(sales, () -> contracts.sign(sales, orderId, waiting.version(),
                IncomingFiles.sent(List.of(new FileUpload("signed.pdf", "application/pdf", scan.length,
                        new ByteArrayInputStream(scan)))), today));
        assertThat(signed.status()).isEqualTo("SIGNED");
        assertThat(signed.customerSignedAt()).as("kept").isNotNull();
        assertThat(status(orderId)).as("placed now: the plan's installment is due on signing")
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("the company signs after the customer: only a manager other than whoever drew it up or sent it; the "
            + "contract is in force and the order placed as when a signed file is recorded")
    void theCompanySignsAfterTheCustomer() {
        Buyer buyer = buyer("Cong ty Ky Sau");
        UUID orderId = draftOrder(buyer);
        SalesContracts.ContractView sent = sentContract(buyer, orderId);
        String signedFile = jdbc.queryForObject("SELECT sent_document_key FROM ordering.sales_contract WHERE id = ?",
                String.class, sent.id());

        assertThatThrownBy(() -> as(manager, () -> contracts.companySigned(manager, orderId, sent.version(), signedFile,
                "hd-signed.pdf"))).as("the customer has not signed").extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        customerSigns(buyer, orderId);
        long version = as(sales, () -> contracts.get(orderId)).version();
        assertThatThrownBy(() -> as(sales, () -> contracts.companySigned(sales, orderId, version, signedFile,
                "hd-signed.pdf"))).as("whoever sent it").extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_SIGNER_IS_AUTHOR);
        assertThatThrownBy(() -> as(manager, () -> contracts.companySigned(manager, orderId, version + 9, signedFile,
                "hd-signed.pdf"))).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(status(orderId)).isEqualTo(OrderStatus.PENDING_CONTRACT);

        SalesContracts.ContractView signed = as(manager, () -> contracts.companySigned(manager, orderId, version,
                signedFile, "hd-signed.pdf"));

        assertThat(signed.status()).isEqualTo("SIGNED");
        assertThat(signed.signedBy()).isEqualTo(manager.userId());
        assertThat(signed.signedOn()).isEqualTo(today);
        assertThat(signed.signedFileName()).isEqualTo("hd-signed.pdf");
        assertThat(signed.customerSignedAt()).isNotNull();
        assertThat(status(orderId)).as("placed: the plan's installment is due on signing")
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.installment WHERE contract_id = ?", Long.class,
                sent.id())).as("the installments opened").isEqualTo(1);
        assertThatThrownBy(() -> as(manager, () -> contracts.companySigned(manager, orderId, signed.version(),
                signedFile, "hd-signed.pdf"))).as("once").extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
    }

    @Test
    @DisplayName("cancelling the order takes a contract the customer signed with it; Sales may cancel it too")
    void aCustomerSignedContractIsCancelled() {
        Buyer buyer = buyer("Cong ty Huy Hop Dong");
        UUID orderId = draftOrder(buyer);
        sentContract(buyer, orderId);
        customerSigns(buyer, orderId);
        SalesContracts.ContractView waiting = as(sales, () -> contracts.get(orderId));

        SalesContracts.ContractView cancelled = as(sales, () -> contracts.cancel(sales, orderId, waiting.version(),
                "The customer withdrew"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(status(orderId)).as("back to a draft, to be drawn up again").isEqualTo(OrderStatus.DRAFT);

        Buyer other = buyer("Cong ty Huy Don");
        UUID otherOrder = draftOrder(other);
        sentContract(other, otherOrder);
        customerSigns(other, otherOrder);
        WithCurrentUser.run(sales, DataScope.ALL, () -> orders.cancel(otherOrder, new CancelOrderCommand(
                CancellationReasonCode.CUSTOMER_REQUEST, "khách rút đơn", null, sales.userId())));
        assertThat(status(otherOrder)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.sales_contract WHERE order_id = ?", String.class,
                otherOrder)).isEqualTo("CANCELLED");
    }

    // ---- strict mode ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("strict mode (BR-S12): another salesperson may not send for review, withdraw or move the deadline of a "
            + "customer's contract; the owner, the owner's cover do; a manager approves whoever owns it")
    void strictModeOnTheNewSalesWrites() {
        Buyer buyer = buyer("Cong ty Co Chu");
        UUID owner = owners.salesperson();
        owners.own(buyer.customerId(), owner);
        CurrentUser ownerUser = SalesOwners.caller(owner, Role.SALES_STAFF);
        CurrentUser other = SalesOwners.caller(owners.salesperson(), Role.SALES_STAFF);
        UUID orderId = draftOrder(buyer);

        SalesContracts.ContractView drafted = drawnUp(ownerUser, orderId, special());
        assertThat(jdbc.queryForObject("SELECT sales_owner_id FROM ordering.sales_contract WHERE id = ?", UUID.class,
                drafted.id())).as("the owner of the day it was drawn up").isEqualTo(owner);
        assertThatThrownBy(() -> as(other, () -> contracts.submitForTermsReview(other, orderId, drafted.version())))
                .extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_OWNED_BY_OTHER_SALES);

        SalesContracts.ContractView waiting = as(ownerUser, () -> contracts.submitForTermsReview(ownerUser, orderId,
                drafted.version()));
        assertThatThrownBy(() -> as(other, () -> contracts.withdrawTermsReview(other, orderId, waiting.version())))
                .extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_OWNED_BY_OTHER_SALES);
        SalesContracts.ContractView approved = approve(orderId, waiting.version(), "Fine");
        assertThat(approved.termsReview()).as("a manager is nobody's customer's owner but decides").isEqualTo("APPROVED");

        SalesContracts.ContractView sent = as(ownerUser, () -> contracts.send(ownerUser, orderId, approved.version()));
        assertThatThrownBy(() -> as(other, () -> contracts.extendSignBy(other, orderId, sent.version(),
                today.plusDays(20)))).extracting(ContractNegotiationIntegrationTest::code)
                .isEqualTo(ErrorCode.CUSTOMER_OWNED_BY_OTHER_SALES);
        assertThat(as(ownerUser, () -> contracts.extendSignBy(ownerUser, orderId, sent.version(), today.plusDays(20)))
                .signBy()).isEqualTo(today.plusDays(20));
    }

    // ---- the list ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the contract list: filters by status, owner, deadline, terms review and a search; paged; one row for each "
            + "contract with its order's number and total, the owner's name, and the tiles over it")
    void theContractList() {
        Buyer buyer = buyer("Cong ty Danh Sach Alpha");
        UUID owner = owners.salesperson();
        owners.own(buyer.customerId(), owner);
        CurrentUser ownerUser = SalesOwners.caller(owner, Role.SALES_STAFF);

        UUID signedOrder = draftOrder(buyer);
        SalesContracts.ContractView sentOne = as(ownerUser, () -> contracts.send(ownerUser, signedOrder,
                drawnUp(ownerUser, signedOrder, null).version()));
        customerSigns(buyer, signedOrder);
        UUID pendingOrder = draftOrder(buyer);
        SalesContracts.ContractView pending = as(ownerUser, () -> contracts.submitForTermsReview(ownerUser, pendingOrder,
                drawnUp(ownerUser, pendingOrder, special()).version()));
        UUID sentOrder = draftOrder(buyer);
        SalesContracts.ContractView sent = as(ownerUser, () -> contracts.send(ownerUser, sentOrder,
                drawnUp(ownerUser, sentOrder, null).version()));
        Buyer unowned = buyer("Cong ty Danh Sach Beta");
        UUID otherOrder = draftOrder(unowned);
        SalesContracts.ContractView drafted = drawnUp(otherOrder, null);

        SalesContracts.ContractSummary before = contracts.summary();

        // The owner's contracts, newest first, with their order's number and the owner's name.
        var mine = contracts.list(new SalesContracts.ContractQuery(Set.of(), owner, null, null, null, 0, 20));
        assertThat(mine.items()).extracting(SalesContracts.ContractRow::contractId)
                .containsExactly(sent.id(), pending.id(), sentOne.id());
        assertThat(mine.totalElements()).isEqualTo(3);
        SalesContracts.ContractRow row = mine.items().getFirst();
        assertThat(row.orderId()).isEqualTo(sentOrder);
        assertThat(row.orderNumber()).isEqualTo(jdbc.queryForObject("SELECT order_number FROM ordering.customer_order "
                + "WHERE id = ?", String.class, sentOrder));
        assertThat(row.customerId()).isEqualTo(buyer.customerId());
        assertThat(row.customerName()).as("the buyer as the contract names it").isEqualTo(buyer.name());
        assertThat(row.status()).isEqualTo("SENT");
        assertThat(row.revision()).isEqualTo(1);
        assertThat(row.totalAmount()).isEqualByComparingTo("2200000");
        assertThat(row.signBy()).isEqualTo(today.plusDays(7));
        assertThat(row.overdueToSign()).isFalse();
        assertThat(row.termsReview()).isEqualTo("NONE");
        assertThat(row.creditReview()).isEqualTo("NONE");
        assertThat(row.salesOwnerId()).isEqualTo(owner);
        assertThat(row.salesOwnerName()).isNotBlank();
        assertThat(mine.items().get(2).customerSignedAt()).isNotNull();

        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of("CUSTOMER_SIGNED"), owner, null, null, null, 0,
                20)).items()).extracting(SalesContracts.ContractRow::contractId).containsExactly(sentOne.id());
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of("DRAFT", "SENT"), owner, null, null, null, 0,
                20)).items()).extracting(SalesContracts.ContractRow::contractId).containsExactly(sent.id(), pending.id());
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of(), owner, null, "PENDING", null, 0, 20))
                .items()).extracting(SalesContracts.ContractRow::contractId).containsExactly(pending.id());
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of(), owner, today.plusDays(8), null, null, 0, 20))
                .items()).as("deadline before the day after the deadline: SENT only")
                .extracting(SalesContracts.ContractRow::contractId).containsExactly(sent.id());
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of(), owner, today.plusDays(7), null, null, 0, 20))
                .items()).as("the deadline day itself is not before it").isEmpty();
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of(), null, null, null,
                "danh sach alpha", 0, 20)).items()).as("a search by the buyer's name").hasSize(3);
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of(), null, null, null, sent.contractNumber(), 0,
                20)).items()).extracting(SalesContracts.ContractRow::contractId).containsExactly(sent.id());
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of(), null, null, null, row.orderNumber(), 0, 20))
                .items()).extracting(SalesContracts.ContractRow::contractId).containsExactly(sent.id());
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of(), null, null, null, "100%_", 0, 20)).items())
                .as("wildcards are text").isEmpty();
        var page = contracts.list(new SalesContracts.ContractQuery(Set.of(), owner, null, null, null, 1, 2));
        assertThat(page.items()).extracting(SalesContracts.ContractRow::contractId).containsExactly(sentOne.id());
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.hasPrevious()).isTrue();
        assertThat(page.hasNext()).isFalse();
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of(), null, null, null, "Beta", 0, 20)).items())
                .extracting(SalesContracts.ContractRow::contractId).containsExactly(drafted.id());
        assertThat(contracts.list(new SalesContracts.ContractQuery(Set.of(), null, null, null, "Beta", 0, 20)).items()
                .getFirst().salesOwnerId()).as("no owner when it was drawn up").isNull();
        assertThatThrownBy(() -> contracts.list(new SalesContracts.ContractQuery(Set.of("NOPE"), null, null, null, null,
                0, 20))).extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER);
        assertThatThrownBy(() -> contracts.list(new SalesContracts.ContractQuery(Set.of(), null, null, "MAYBE", null, 0,
                20))).extracting(ContractNegotiationIntegrationTest::code).isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER);

        // The tiles move by what changes.
        UUID anotherOrder = draftOrder(buyer);
        as(ownerUser, () -> contracts.send(ownerUser, anotherOrder, drawnUp(ownerUser, anotherOrder, null).version()));
        customerSigns(buyer, anotherOrder);
        SalesContracts.ContractSummary after = contracts.summary();
        assertThat(after.byStatus().get("CUSTOMER_SIGNED")).isEqualTo(before.byStatus().get("CUSTOMER_SIGNED") + 1);
        assertThat(after.awaitingCompanySignature()).isEqualTo(before.awaitingCompanySignature() + 1);
        assertThat(after.termsReviewPending()).isGreaterThanOrEqualTo(1);
        assertThat(after.byStatus()).containsKeys("DRAFT", "SENT", "CUSTOMER_SIGNED", "SIGNED", "COMPLETED", "CANCELLED",
                "LIQUIDATED");
    }
}
