package com.stockflow.production;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.production.internal.domain.SampleStatus;
import com.stockflow.production.internal.service.SampleProductionProgress;
import com.stockflow.production.internal.service.SampleRequests;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-394 × SCRUM-448 against a real PostgreSQL: a sample linked to a quote, its approval unlocking that
 * quote's acceptance, the accepted quote becoming the order — and the sample's quote checked through
 * {@code order :: api} (open, same customer, same design on the same blank). Every step goes through the
 * modules' services and events; the workshop's completion goes through {@link SampleProductionProgress},
 * the contract SCRUM-400 will call (prepress, printing and QC of the production order are not exercised).
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class SampleQuoteIntegrationTest {

    private static final String BLANK = ConfirmedDesigns.BLANK;
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired SampleRequests samples;
    @Autowired SampleProductionProgress workshop;
    @Autowired Quotes quotes;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private UUID hcm;
    private CurrentUser sales;
    private UUID customerId;
    private CurrentUser portal;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        UUID salesId = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesId, "sales-" + salesId, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        UUID portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, phone, status, version, created_at)
                VALUES (?, ?, 'Cong ty In Ly B', ?, '0901234567', 'ACTIVE', 0, NOW())""",
                customerId, portalUser, "b-" + customerId + "@example.com"));
        customers.addAddress(customerId, salesId, true, new SaveAddressCommand("SHIPPING", "Nguyen Van B",
                "0901234567", "2 Le Loi", null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN", null, true, null));
        portal = new CurrentUser(portalUser, "portal-" + portalUser, Set.of(Role.CUSTOMER), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
    }

    // ------------------------------------------------------------------------------------- fixtures

    private ConfirmedDesigns.Design design() throws Exception {
        return ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, customerId);
    }

    private Quotes.Draft quoteDraft(UUID design) {
        return new Quotes.Draft(PaymentTerm.PREPAID, null, Instant.now().plus(Duration.ofDays(7)), null,
                List.of(new Quotes.Line(BLANK, design, "Logo front", 3, List.of(new Quotes.Tier(2, new BigDecimal("3000"))))));
    }

    private Quotes.QuoteView sentQuote(UUID design) {
        Quotes.QuoteView draft = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId, quoteDraft(design)));
        return quotes.send(sales, draft.id(), draft.version());
    }

    /** Submit, the workshop's completion through its contract, and the hand-over: SENT_TO_CUSTOMER. */
    private SampleRequests.Sample made(SampleRequests.Sample draft) {
        SampleRequests.Sample submitted = samples.submit(sales, draft.id(), hcm);
        workshop.productionCompleted(submitted.productionOrder().id());
        return samples.markSent(sales, draft.id());
    }

    private void awaitApprovalRecorded(UUID sampleId) {
        Await.until("the order module records the approval", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM ordering.approved_sample WHERE sample_request_id = ?", Integer.class,
                sampleId) == 1);
    }

    private int samplesOf(UUID customer) {
        return jdbc.queryForObject("SELECT count(*) FROM production.sample_request WHERE customer_id = ?",
                Integer.class, customer);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    // ------------------------------------------------------------------------------------- tests

    @Test
    @DisplayName("quote sent -> sample of that quote made and approved -> quote accepted -> one order of that design")
    void sampleApprovedThenQuoteAcceptedThenOrder() throws Exception {
        ConfirmedDesigns.Design design = design();
        Quotes.QuoteView quote = sentQuote(design.snapshotId());

        SampleRequests.Sample sample = samples.create(sales, new SampleRequests.Create(customerId, quote.id(),
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null)));
        assertThat(sample.quoteId()).isEqualTo(quote.id());
        assertThat(jdbc.queryForObject("SELECT quote_id FROM production.sample_request WHERE id = ?", UUID.class,
                sample.id())).isEqualTo(quote.id());

        made(sample);
        assertThatThrownBy(() -> quotes.accept(portal, quote.id(), new Quotes.Acceptance(quote.version(), Map.of())))
                .as("sample sent, not yet approved").extracting(SampleQuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);
        SampleRequests.Sample approved = samples.approve(portal, sample.id());
        assertThat(approved.status()).isEqualTo(SampleStatus.APPROVED);
        awaitApprovalRecorded(sample.id());

        Quotes.QuoteView accepted = quotes.accept(portal, quote.id(), new Quotes.Acceptance(quote.version(), Map.of()));
        assertThat(accepted.status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(accepted.lines().getFirst().sample().approvedSampleId()).isEqualTo(sample.id());
        Quotes.QuoteView converted = quotes.convert(sales, quote.id(), new Quotes.Conversion(accepted.version(), null, null));
        OrderSummary order = orders.findById(converted.convertedOrder().orderId()).orElseThrow();
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.designSnapshotId()).isEqualTo(design.snapshotId());
            assertThat(line.designChecksum()).isEqualTo(design.checksum());
            assertThat(line.unitPrice().amount()).isEqualByComparingTo("3000");
        });

        assertThatThrownBy(() -> samples.create(sales, new SampleRequests.Create(customerId, quote.id(),
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null))))
                .as("a converted quote takes no new sample").extracting(SampleQuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.QUOTE_NOT_OPEN);
    }

    @Test
    @DisplayName("changes on the sample and a new design: the quote is revised to it first, then the next round, approval, acceptance")
    void revisedDesignGoesThroughTheQuoteRevision() throws Exception {
        ConfirmedDesigns.Design first = design();
        ConfirmedDesigns.Design second = design();
        Quotes.QuoteView quote = sentQuote(first.snapshotId());
        SampleRequests.Sample round1 = made(samples.create(sales, new SampleRequests.Create(customerId, quote.id(),
                new SampleRequests.Draft(first.snapshotId(), BLANK, 2, null))));
        samples.requestChanges(portal, round1.id(), "Logo 20% larger");

        assertThatThrownBy(() -> samples.startNextRound(sales, round1.id(),
                new SampleRequests.Draft(second.snapshotId(), BLANK, 2, null)))
                .as("the quote does not quote the new design yet").extracting(SampleQuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.SAMPLE_QUOTE_MISMATCH);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.sample_request WHERE previous_round_id = ?",
                Integer.class, round1.id())).as("nothing written").isZero();

        // The customer asks for the quote to change too; Sales revises it to the new design.
        Quotes.QuoteView changed = quotes.requestChanges(portal, quote.id(), quote.version(), "New logo size");
        Quotes.QuoteView reopened = quotes.reopen(sales, quote.id(), changed.version());
        Quotes.QuoteView revised = quotes.edit(sales, quote.id(), reopened.version(), quoteDraft(second.snapshotId()));
        assertThat(revised.revision()).isEqualTo(2);

        SampleRequests.Sample round2 = samples.startNextRound(sales, round1.id(),
                new SampleRequests.Draft(second.snapshotId(), BLANK, 2, null));
        assertThat(round2.round()).isEqualTo(2);
        assertThat(round2.quoteId()).isEqualTo(quote.id());
        made(round2);
        samples.approve(portal, round2.id());
        awaitApprovalRecorded(round2.id());

        Quotes.QuoteView resent = quotes.send(sales, quote.id(), revised.version());
        Quotes.QuoteView accepted = quotes.accept(portal, quote.id(), new Quotes.Acceptance(resent.version(), Map.of()));
        assertThat(accepted.lines()).singleElement().satisfies(line -> {
            assertThat(line.designSnapshotId()).isEqualTo(second.snapshotId());
            assertThat(line.sample().approvedSampleId()).isEqualTo(round2.id());
        });
        assertThat(samples.get(sales, round1.id()).status()).as("the reviewed round is kept")
                .isEqualTo(SampleStatus.CHANGES_REQUESTED);
    }

    @Test
    @DisplayName("a quote of another customer, of another design, unknown or closed is refused and nothing is written")
    void wrongQuotesAreRefused() throws Exception {
        ConfirmedDesigns.Design design = design();
        ConfirmedDesigns.Design other = design();
        Quotes.QuoteView quote = sentQuote(design.snapshotId());
        int before = samplesOf(customerId);

        assertThatThrownBy(() -> samples.create(sales, new SampleRequests.Create(customerId, quote.id(),
                new SampleRequests.Draft(other.snapshotId(), BLANK, 2, null))))
                .as("another design").extracting(SampleQuoteIntegrationTest::code).isEqualTo(ErrorCode.SAMPLE_QUOTE_MISMATCH);
        assertThatThrownBy(() -> samples.create(sales, new SampleRequests.Create(customerId, UUID.randomUUID(),
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null))))
                .as("no such quote").isInstanceOf(NotFoundException.class);

        UUID strangerUser = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID stranger = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Stranger', ?, 'ACTIVE', 0, NOW())""", stranger, strangerUser, "s-" + stranger + "@example.com"));
        ConfirmedDesigns.Design strangers = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, stranger);
        assertThatThrownBy(() -> samples.create(sales, new SampleRequests.Create(stranger, quote.id(),
                new SampleRequests.Draft(strangers.snapshotId(), BLANK, 2, null))))
                .as("another customer's quote").extracting(SampleQuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.SAMPLE_QUOTE_MISMATCH);

        Quotes.QuoteView draft = quotes.create(sales, new Quotes.Create(UUID.randomUUID(), customerId,
                quoteDraft(design.snapshotId())));
        quotes.cancel(sales, draft.id(), draft.version(), "Duplicate");
        assertThatThrownBy(() -> samples.create(sales, new SampleRequests.Create(customerId, draft.id(),
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null))))
                .as("a cancelled quote").extracting(SampleQuoteIntegrationTest::code).isEqualTo(ErrorCode.QUOTE_NOT_OPEN);

        assertThat(samplesOf(customerId)).as("every refusal rolled back").isEqualTo(before);

        SampleRequests.Sample linked = samples.create(sales, new SampleRequests.Create(customerId, quote.id(),
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null)));
        assertThatThrownBy(() -> samples.edit(sales, linked.id(), new SampleRequests.Draft(other.snapshotId(), BLANK, 2,
                null))).as("an edit off the quote").extracting(SampleQuoteIntegrationTest::code)
                .isEqualTo(ErrorCode.SAMPLE_QUOTE_MISMATCH);
        assertThat(samples.get(sales, linked.id()).specification().designSnapshotId()).isEqualTo(design.snapshotId());
    }

    @Test
    @DisplayName("regression: concurrent answers and next rounds on one round end with one winner and a business refusal, never a lock error")
    void concurrentRoundChanges() throws Exception {
        ConfirmedDesigns.Design design = design();
        Quotes.QuoteView quote = sentQuote(design.snapshotId());
        for (int attempt = 0; attempt < 3; attempt++) {
            SampleRequests.Sample sent = made(samples.create(sales, new SampleRequests.Create(customerId, quote.id(),
                    new SampleRequests.Draft(design.snapshotId(), BLANK, 1, null))));
            List<Object> answers = race(List.of(
                    () -> samples.approve(portal, sent.id()),
                    () -> samples.requestChanges(portal, sent.id(), "Brighter")));
            assertThat(answers).filteredOn(SampleRequests.Sample.class::isInstance).hasSize(1);
            assertThat(answers).filteredOn(BusinessException.class::isInstance).singleElement()
                    .satisfies(e -> assertThat(code((Throwable) e)).isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION));
        }

        SampleRequests.Sample changed = made(samples.create(sales, new SampleRequests.Create(customerId, quote.id(),
                new SampleRequests.Draft(design.snapshotId(), BLANK, 1, null))));
        samples.requestChanges(portal, changed.id(), "Darker");
        List<Object> nextRounds = race(List.of(
                () -> samples.startNextRound(sales, changed.id(), null),
                () -> samples.startNextRound(sales, changed.id(), null)));
        assertThat(nextRounds).filteredOn(SampleRequests.Sample.class::isInstance).hasSize(1);
        assertThat(nextRounds).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(code((Throwable) e)).isEqualTo(ErrorCode.SAMPLE_NEXT_ROUND_EXISTS));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.sample_request WHERE previous_round_id = ?",
                Integer.class, changed.id())).isEqualTo(1);
    }

    /** Starts every task at the same moment; each result or exception, in order. */
    private static List<Object> race(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
