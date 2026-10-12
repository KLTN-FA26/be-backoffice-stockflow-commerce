package com.stockflow.production;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.contracts.OrderLinesReleasedForProduction;
import com.stockflow.contracts.SampleApproved;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.SampleStatus;
import com.stockflow.production.internal.service.OrderProductionService;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.SampleRequests;
import com.stockflow.production.internal.service.SampleProductionProgress;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * SCRUM-394/395 against a real PostgreSQL with Tu's migrations, the real design verification (stored
 * bytes, checksum, product of the blank), real stock and the real event plumbing.
 *
 * <p>Security is off in this context (the {@code test} profile): who may call what is covered over HTTP
 * by {@code ProductionSecurityIntegrationTest}. Here the caller is passed in, as the controller does.</p>
 *
 * <p>The workshop moves a submitted round to IN_PRODUCTION and READY through {@link SampleProductionProgress},
 * the contract its production-order workflow (SCRUM-396..400) calls. That workflow does not exist yet, so the
 * tests call the contract directly: the round's transitions are real, the production order's own prepress,
 * printing and QC are not exercised here.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
// No ClamAV container in tests. Switching the scanner off is the supported developer setting
// (UploadInspection javadoc); design verification still hashes the stored bytes.
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class ProductionIntegrationTest {

    private static final String BLANK = ProductionFixtures.BLANK;
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired SampleRequests samples;
    @Autowired ProductionOrders productionOrders;
    @Autowired OrderProductionService orderProduction;
    @Autowired OrderReleases releases;
    @Autowired FileStorage storage;
    @Autowired SampleProductionProgress progress;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired RecordedEvents.Recorder recorded;

    private ProductionFixtures fixtures;
    private UUID hcm;
    private CurrentUser sales;
    private UUID coordinator;

    @BeforeEach
    void setUp() {
        fixtures = new ProductionFixtures(storage, jdbc, tx, entityManager, progress);
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        recorded.clear();
    }

    // ------------------------------------------------------------------------------------------- fixtures

    private SampleRequests.Sample draftSample(ProductionFixtures.Design design) {
        return samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), BLANK, 3, null)));
    }

    private SampleRequests.Sample sentSample(ProductionFixtures.Design design) {
        SampleRequests.Sample sample = samples.submit(sales, draftSample(design).id(), hcm);
        fixtures.workshopFinished(sample.id());
        return samples.markSent(sales, sample.id());
    }

    /** A CONFIRMED (paid) order of the demo customer with one print line per design, 10 each. Returns line ids. */
    private UUID confirmedOrder(UUID orderId, String status, ProductionFixtures.Design... designs) {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, version, created_at, paid_amount, paid_in_full_at, warehouse_id,
                        released_at, released_by)
                    VALUES (?, ?, ?, ?, ?, 20000, 'VND', NOW(), 0, NOW(), 20000, NOW(), ?, ?, ?)""",
                    orderId, "SO-20991230-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(1_000_000)), DemoData.CUSTOMER_ID, UUID.randomUUID(), status,
                    "IN_PRODUCTION".equals(status) ? hcm : null,
                    "IN_PRODUCTION".equals(status) ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                    "IN_PRODUCTION".equals(status) ? coordinator : null);
            for (ProductionFixtures.Design design : designs) {
                jdbc.update("""
                        INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency,
                            design_snapshot_id, design_checksum) VALUES (?, ?, ?, 10, 2000, 'VND', ?, ?)""",
                        Identifiers.newId(), orderId, BLANK, design.snapshotId(), design.checksum());
            }
        });
        return orderId;
    }

    private List<OrderLinesReleasedForProduction.Line> printLines(UUID orderId, UUID approvedSample) {
        return jdbc.query("SELECT id, design_snapshot_id, design_checksum FROM ordering.order_line WHERE order_id = ?",
                (rs, i) -> new OrderLinesReleasedForProduction.Line(rs.getObject(1, UUID.class), BLANK, 10,
                        rs.getObject(2, UUID.class), rs.getString(3), approvedSample, null), orderId);
    }

    private int productionOrdersOf(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM production.production_order WHERE sales_order_id = ?",
                Integer.class, orderId);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    // ------------------------------------------------------------------------------------------- tests

    @Test
    @DisplayName("draft -> submitted with its SAMPLE order -> sent -> approved; the order module records it and a release of the design creates the ORDER order")
    void sampleToApprovalToReleasedProduction() throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        SampleRequests.Sample draft = draftSample(design);
        assertThat(draft.status()).isEqualTo(SampleStatus.DRAFT);
        assertThat(draft.requestNumber()).startsWith("SR-");
        assertThat(draft.round()).isEqualTo(1);
        assertThat(draft.createdBy()).isNotBlank();

        SampleRequests.Sample submitted = samples.submit(sales, draft.id(), hcm);
        assertThat(submitted.status()).isEqualTo(SampleStatus.SUBMITTED);
        assertThat(submitted.productionOrder()).isNotNull();
        assertThat(submitted.productionOrder().orderNumber()).startsWith("LSX-");
        var sampleOrder = jdbc.queryForMap("""
                SELECT type, mode, status, warehouse_id, design_checksum, planned_quantity, sales_order_id
                  FROM production.production_order WHERE sample_request_id = ?""", draft.id());
        assertThat(sampleOrder).containsEntry("type", "SAMPLE").containsEntry("mode", "IN_HOUSE")
                .containsEntry("status", "PENDING_PREPRESS").containsEntry("warehouse_id", hcm)
                .containsEntry("design_checksum", design.checksum()).containsEntry("planned_quantity", 3);
        assertThat(sampleOrder.get("sales_order_id")).isNull();
        ProductionOrders.Detail detail = productionOrders.get(sales, submitted.productionOrder().id());
        assertThat(detail.history()).singleElement().satisfies(change -> {
            assertThat(change.fromStatus()).isNull();
            assertThat(change.toStatus()).isEqualTo("PENDING_PREPRESS");
            assertThat(change.changedBy()).isEqualTo(sales.userId());
        });

        assertThatThrownBy(() -> samples.markSent(sales, draft.id())).as("not made yet")
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        UUID sampleOrderId = submitted.productionOrder().id();
        progress.productionStarted(sampleOrderId);
        assertThat(samples.get(sales, draft.id()).status()).isEqualTo(SampleStatus.IN_PRODUCTION);
        progress.productionStarted(sampleOrderId);   // reported twice: no change
        progress.productionCompleted(sampleOrderId);
        assertThat(samples.get(sales, draft.id()).status()).isEqualTo(SampleStatus.READY);
        SampleRequests.Sample sent = samples.markSent(sales, draft.id());
        assertThat(sent.status()).isEqualTo(SampleStatus.SENT_TO_CUSTOMER);
        assertThat(sent.sentAt()).isNotNull();

        SampleRequests.Sample approved = samples.approve(sales, draft.id());
        assertThat(approved.status()).isEqualTo(SampleStatus.APPROVED);
        assertThat(approved.respondedBy()).isEqualTo(sales.userId());
        assertThat(recorded.ofType(SampleApproved.class)).singleElement().isEqualTo(new SampleApproved(draft.id(),
                DemoData.CUSTOMER_ID, design.snapshotId(), BLANK));
        Await.until("the order module records the approval", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM ordering.approved_sample WHERE sample_request_id = ?", Integer.class,
                draft.id()) == 1);
        assertThatThrownBy(() -> samples.approve(sales, draft.id())).as("approval is terminal")
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);

        // The coordinator releases an order of that design: the release listener creates its ORDER order.
        UUID order = confirmedOrder(Identifiers.newId(), "CONFIRMED", design);
        assertThat(releases.release(order, hcm, coordinator).status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        Await.until("the release becomes a production order", ASYNC, () -> productionOrdersOf(order) == 1);
        var orderOrder = jdbc.queryForMap("""
                SELECT type, status, approved_sample_id, warehouse_id, planned_quantity, design_checksum
                  FROM production.production_order WHERE sales_order_id = ?""", order);
        assertThat(orderOrder).containsEntry("type", "ORDER").containsEntry("status", "PENDING_PREPRESS")
                .containsEntry("approved_sample_id", draft.id()).containsEntry("warehouse_id", hcm)
                .containsEntry("planned_quantity", 10).containsEntry("design_checksum", design.checksum());

        var page = productionOrders.list(sales, new ProductionOrders.Criteria(List.of(ProductionOrder.Type.ORDER),
                List.of(ProductionOrder.Status.PENDING_PREPRESS), hcm, null, null, null, order, null, 0, 20, null));
        assertThat(page.items()).singleElement().satisfies(row -> assertThat(row.salesOrderId()).isEqualTo(order));
    }

    @Test
    @DisplayName("changes requested keep the feedback; the next round is a new request, started once, with its own SAMPLE order")
    void changesAndNextRound() throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        SampleRequests.Sample first = sentSample(design);
        assertThatThrownBy(() -> samples.requestChanges(sales, first.id(), "  ")).as("feedback is required")
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        SampleRequests.Sample changed = samples.requestChanges(sales, first.id(), "Logo 20% larger");
        assertThat(changed.status()).isEqualTo(SampleStatus.CHANGES_REQUESTED);
        assertThat(changed.customerFeedback()).isEqualTo("Logo 20% larger");

        SampleRequests.Sample second = samples.startNextRound(sales, first.id(), null);
        assertThat(second.round()).isEqualTo(2);
        assertThat(second.previousRoundId()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(SampleStatus.DRAFT);
        assertThat(second.requestNumber()).isNotEqualTo(first.requestNumber());
        assertThatThrownBy(() -> samples.startNextRound(sales, first.id(), null))
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.SAMPLE_NEXT_ROUND_EXISTS);

        SampleRequests.Sample history = samples.get(sales, second.id());
        assertThat(history.rounds()).extracting(SampleRequests.Round::round).containsExactly(1, 2);
        assertThat(history.rounds().getFirst().customerFeedback()).isEqualTo("Logo 20% larger");
        assertThat(samples.get(sales, first.id()).nextRoundId()).isEqualTo(second.id());

        samples.submit(sales, second.id(), hcm);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM production.production_order
                 WHERE sample_request_id IN (?, ?) AND type = 'SAMPLE'""", Integer.class, first.id(), second.id()))
                .as("one SAMPLE order per round").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM production.sample_request WHERE id = ?", String.class,
                first.id())).as("the reviewed round is not reopened").isEqualTo("CHANGES_REQUESTED");
    }

    @Test
    @DisplayName("only a draft is edited or cancelled, with a reason; a cancelled draft has no production order")
    void editAndCancel() throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        ProductionFixtures.Design other = fixtures.confirmedDesign();
        SampleRequests.Sample draft = draftSample(design);
        SampleRequests.Sample edited = samples.edit(sales, draft.id(),
                new SampleRequests.Draft(other.snapshotId(), BLANK, 2, java.time.LocalDate.of(2026, 10, 30)));
        assertThat(edited.specification().designSnapshotId()).isEqualTo(other.snapshotId());
        assertThat(edited.specification().quantity()).isEqualTo(2);

        SampleRequests.Sample cancelled = samples.cancel(sales, draft.id(), "Customer chose a stock design");
        assertThat(cancelled.status()).isEqualTo(SampleStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).isEqualTo("Customer chose a stock design");
        assertThat(cancelled.productionOrder()).isNull();
        assertThatThrownBy(() -> samples.submit(sales, draft.id(), hcm))
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);

        SampleRequests.Sample submitted = samples.submit(sales, draftSample(design).id(), hcm);
        assertThatThrownBy(() -> samples.cancel(sales, submitted.id(), "too late"))
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
        assertThatThrownBy(() -> samples.edit(sales, submitted.id(),
                new SampleRequests.Draft(other.snapshotId(), BLANK, 2, null)))
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
    }

    @Test
    @DisplayName("references are checked: another customer's design, an unknown warehouse, more blanks than held")
    void references() throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        UUID stranger = tx.execute(s -> ReferenceRows.customer(entityManager));
        assertThatThrownBy(() -> samples.create(sales, new SampleRequests.Create(stranger, null,
                new SampleRequests.Draft(design.snapshotId(), BLANK, 3, null))))
                .as("the snapshot is the demo customer's").extracting(ProductionIntegrationTest::code)
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> samples.create(sales, new SampleRequests.Create(UUID.randomUUID(), null,
                new SampleRequests.Draft(design.snapshotId(), BLANK, 3, null))))
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);

        SampleRequests.Sample draft = draftSample(design);
        assertThatThrownBy(() -> samples.submit(sales, draft.id(), UUID.randomUUID()))
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        samples.edit(sales, draft.id(), new SampleRequests.Draft(design.snapshotId(), BLANK, 1000, null));
        assertThatThrownBy(() -> samples.submit(sales, draft.id(), hcm))
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.production_order WHERE sample_request_id = ?",
                Integer.class, draft.id())).isZero();
        assertThat(samples.get(sales, draft.id()).status()).isEqualTo(SampleStatus.DRAFT);
    }

    @Test
    @DisplayName("two simultaneous submits of one draft: one wins, one SAMPLE order exists")
    void concurrentSubmit() throws Exception {
        UUID draft = draftSample(fixtures.confirmedDesign()).id();
        List<Object> outcomes = race(2, () -> samples.submit(sales, draft, hcm));

        assertThat(outcomes).filteredOn(SampleRequests.Sample.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(code((Throwable) e)).isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.production_order WHERE sample_request_id = ?",
                Integer.class, draft)).isEqualTo(1);
    }

    @Test
    @DisplayName("a rolled-back approval leaves neither the approval nor an event publication")
    void approvalRollback() throws Exception {
        SampleRequests.Sample sent = sentSample(fixtures.confirmedDesign());
        int publications = jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class);

        tx.executeWithoutResult(status -> {
            samples.approve(sales, sent.id());
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT status FROM production.sample_request WHERE id = ?", String.class,
                sent.id())).isEqualTo("SENT_TO_CUSTOMER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class)).isEqualTo(publications);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.approved_sample WHERE sample_request_id = ?",
                Integer.class, sent.id())).isZero();
    }

    @Test
    @DisplayName("a release delivered three times, two at once, creates one production order per line")
    void releaseIsIdempotentUnderConcurrency() throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        SampleRequests.Sample sample = sentSample(design);
        samples.approve(sales, sample.id());
        UUID order = confirmedOrder(Identifiers.newId(), "IN_PRODUCTION", design, design);
        var release = new OrderLinesReleasedForProduction(order, "SO-IT", hcm, printLines(order, sample.id()));

        List<Object> outcomes = race(2, () -> orderProduction.createFor(release));
        List<ProductionOrder> replay = orderProduction.createFor(release);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(List.class));
        assertThat(productionOrdersOf(order)).isEqualTo(2);
        for (Object outcome : outcomes) {
            assertThat(((List<?>) outcome)).extracting(o -> ((ProductionOrder) o).id())
                    .containsExactlyElementsOf(replay.stream().map(ProductionOrder::id).toList());
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM production.production_order_status_history h
                  JOIN production.production_order p ON p.id = h.production_order_id WHERE p.sales_order_id = ?""",
                Integer.class, order)).as("one creation row per order, not per delivery").isEqualTo(2);

        var conflicting = new OrderLinesReleasedForProduction(order, "SO-IT", hcm, printLines(order, sample.id())
                .stream().map(l -> new OrderLinesReleasedForProduction.Line(l.orderLineId(), l.blankSku(), 9,
                        l.designSnapshotId(), l.designChecksum(), l.approvedSampleId(), null)).toList());
        assertThatThrownBy(() -> orderProduction.createFor(conflicting)).as("the line says 10")
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("one line of a new design without an approved sample: no production order for any line")
    void wholeReleaseRollsBack() throws Exception {
        ProductionFixtures.Design approvedDesign = fixtures.confirmedDesign();
        ProductionFixtures.Design newDesign = fixtures.confirmedDesign();
        SampleRequests.Sample sample = sentSample(approvedDesign);
        samples.approve(sales, sample.id());
        UUID order = confirmedOrder(Identifiers.newId(), "IN_PRODUCTION", approvedDesign, newDesign);
        List<OrderLinesReleasedForProduction.Line> lines = printLines(order, null).stream()
                .map(l -> l.designSnapshotId().equals(approvedDesign.snapshotId())
                        ? new OrderLinesReleasedForProduction.Line(l.orderLineId(), l.blankSku(), l.quantity(),
                        l.designSnapshotId(), l.designChecksum(), sample.id(), null) : l)
                .toList();

        assertThatThrownBy(() -> orderProduction.createFor(new OrderLinesReleasedForProduction(order, "SO-IT", hcm,
                lines))).extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);
        assertThat(productionOrdersOf(order)).isZero();
    }

    @Test
    @DisplayName("the workshop contract refuses an ORDER production order and a round that was never submitted")
    void workshopContractRefusals() throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        SampleRequests.Sample sample = sentSample(design);
        samples.approve(sales, sample.id());
        UUID order = confirmedOrder(Identifiers.newId(), "IN_PRODUCTION", design);
        List<ProductionOrder> created = orderProduction.createFor(
                new OrderLinesReleasedForProduction(order, "SO-IT", hcm, printLines(order, sample.id())));

        assertThatThrownBy(() -> progress.productionCompleted(created.getFirst().id()))
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> progress.productionStarted(UUID.randomUUID()))
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(samples.get(sales, sample.id()).status()).as("an approved round is left alone")
                .isEqualTo(SampleStatus.APPROVED);
        progress.productionCompleted(fixtures.sampleProductionOrder(sample.id()));
        assertThat(samples.get(sales, sample.id()).status()).isEqualTo(SampleStatus.APPROVED);
    }

    @Test
    @DisplayName("only an ACTIVE customer gets new sample work; a draft of a customer blocked since is cancelled, not submitted")
    void customerEligibility() throws Exception {
        UUID customer = fixtures.customerOf(fixtures.user());
        ProductionFixtures.Design design = fixtures.confirmedDesign(customer);
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(customer, null,
                new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null)));
        SampleRequests.Sample sent = samples.submit(sales, samples.create(sales, new SampleRequests.Create(customer,
                null, new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null))).id(), hcm);
        fixtures.workshopFinished(sent.id());
        samples.markSent(sales, sent.id());

        for (String status : List.of("BLOCKED", "INACTIVE")) {
            tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET status = ? WHERE id = ?", status,
                    customer));
            assertThatThrownBy(() -> samples.create(sales, new SampleRequests.Create(customer, null,
                    new SampleRequests.Draft(design.snapshotId(), BLANK, 2, null))))
                    .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE);
            assertThatThrownBy(() -> samples.submit(sales, draft.id(), hcm))
                    .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE);
        }
        assertThat(samples.requestChanges(sales, sent.id(), "Brighter red").status())
                .as("the answer to a sample already sent is still recorded").isEqualTo(SampleStatus.CHANGES_REQUESTED);
        assertThatThrownBy(() -> samples.startNextRound(sales, sent.id(), null))
                .extracting(ProductionIntegrationTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE);
        assertThat(samples.cancel(sales, draft.id(), "Customer is inactive").status()).isEqualTo(SampleStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.production_order WHERE sample_request_id = ?",
                Integer.class, draft.id())).isZero();
    }

    /** Starts {@code callers} copies of {@code work} at the same moment; each result or exception, in order. */
    private static List<Object> race(int callers, Callable<?> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return work.call();
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
