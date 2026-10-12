package com.stockflow.production;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.ProductionWorkflow;
import com.stockflow.production.internal.service.SampleProductionProgress;
import com.stockflow.production.internal.service.SampleRequests;
import com.stockflow.support.ConfirmedDesigns;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-407 against a real PostgreSQL: a production order born of a real sample submission, its prepress
 * checked against the real stored print file through {@code design :: api}, holds and resumes with the
 * status history, and the hold announcement. No status is set by SQL.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class ProductionWorkflowIntegrationTest {

    @Autowired ProductionWorkflow workflow;
    @Autowired SampleRequests samples;
    @Autowired SampleProductionProgress progress;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired RecordedEvents.Recorder recorded;

    private UUID hcm;
    private CurrentUser sales;
    private CurrentUser printer;
    private CurrentUser manager;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        // A sample's blanks are held from submission (Q1): bring this test's own, not the shared demo stock.
        com.stockflow.support.ConfirmedDesigns.stockBlanks(jdbc, tx, 30);
        sales = user(Role.SALES_STAFF, DataScope.ALL, Set.of());
        printer = user(Role.PRODUCTION_STAFF, DataScope.WAREHOUSE, Set.of(hcm));
        manager = user(Role.WAREHOUSE_MANAGER, DataScope.WAREHOUSE, Set.of(hcm));
        recorded.clear();
    }

    private CurrentUser user(Role role, DataScope scope, Set<UUID> warehouses) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(id, role.name() + "-" + id, Set.of(role), Set.of(), scope,
                warehouses.isEmpty() ? Set.of() : Set.of("HCM"), warehouses);
    }

    /** A SAMPLE production order, PENDING_PREPRESS, from a real submission; returns {order id, design}. */
    private Object[] pendingOrder() throws Exception {
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, DemoData.CUSTOMER_ID);
        SampleRequests.Sample sample = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ConfirmedDesigns.BLANK, 1, null)));
        UUID order = samples.submit(sales, sample.id(), hcm).productionOrder().id();
        return new Object[] {order, design};
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("prepress PASS re-verifies the stored file and moves to READY; hold and resume return to READY, all on the history")
    void prepressHoldResume() throws Exception {
        UUID order = (UUID) pendingOrder()[0];

        ProductionOrders.Detail ready = workflow.prepress(printer, order, true, null);
        assertThat(ready.summary().status()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(ready.prepressCheckedAt()).isNotNull();

        ProductionOrders.Detail held = workflow.hold(printer, order, ProductionOrder.HoldReason.MACHINE_DOWN, "Press 2");
        assertThat(held.summary().status()).isEqualTo(ProductionOrder.Status.ON_HOLD);
        assertThat(held.summary().holdReason()).isEqualTo("MACHINE_DOWN");
        assertThat(recorded.matching(ProductionOrderHeld.class, e -> e.productionOrderId().equals(order)))
                .singleElement().satisfies(e -> assertThat(e.reason()).isEqualTo("MACHINE_DOWN"));

        ProductionOrders.Detail resumed = workflow.resume(manager, order);
        assertThat(resumed.summary().status()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(resumed.history()).extracting(ProductionOrders.StatusChange::toStatus)
                .containsExactly("PENDING_PREPRESS", "READY", "ON_HOLD", "READY");
        assertThat(jdbc.queryForObject("SELECT status_before_hold IS NULL AND hold_reason IS NULL FROM "
                + "production.production_order WHERE id = ?", Boolean.class, order)).isTrue();
    }

    @Test
    @DisplayName("prepress FAIL holds the order FILE_REJECTED with the note and announces it; PASS refuses a tampered file")
    void rejectedAndTamperedFiles() throws Exception {
        UUID rejected = (UUID) pendingOrder()[0];
        assertThatThrownBy(() -> workflow.prepress(printer, rejected, false, " ")).as("a rejection says why")
                .extracting(ProductionWorkflowIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        ProductionOrders.Detail held = workflow.prepress(printer, rejected, false, "Bleed missing on the left edge");
        assertThat(held.summary().status()).isEqualTo(ProductionOrder.Status.ON_HOLD);
        assertThat(held.summary().holdReason()).isEqualTo("FILE_REJECTED");
        assertThat(recorded.matching(ProductionOrderHeld.class, e -> e.productionOrderId().equals(rejected)))
                .singleElement().satisfies(e -> {
                    assertThat(e.reason()).isEqualTo("FILE_REJECTED");
                    assertThat(e.note()).isEqualTo("Bleed missing on the left edge");
                    assertThat(e.sampleRequestId()).isNotNull();
                });

        Object[] tamperedOrder = pendingOrder();
        UUID tampered = (UUID) tamperedOrder[0];
        String key = jdbc.queryForObject("SELECT artifact_url FROM design.design_snapshot WHERE id = ?", String.class,
                ((ConfirmedDesigns.Design) tamperedOrder[1]).snapshotId());
        Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "stockflow-test-storage").resolve(key),
                "%PDF-1.4\n% someone else's art");
        assertThatThrownBy(() -> workflow.prepress(printer, tampered, true, null))
                .extracting(ProductionWorkflowIntegrationTest::code).isEqualTo(ErrorCode.PRINT_FILE_MISMATCH);
        assertThat(jdbc.queryForObject("SELECT status FROM production.production_order WHERE id = ?", String.class,
                tampered)).isEqualTo("PENDING_PREPRESS");
    }

    @Test
    @DisplayName("two holds at once on one order: one wins, the other is refused for the state, one history row")
    void concurrentHolds() throws Exception {
        UUID order = (UUID) pendingOrder()[0];
        workflow.prepress(printer, order, true, null);

        List<Object> outcomes = race(List.of(
                () -> workflow.hold(printer, order, ProductionOrder.HoldReason.MACHINE_DOWN, null),
                () -> workflow.hold(manager, order, ProductionOrder.HoldReason.BLANKS_SHORT, null)));
        assertThat(outcomes).filteredOn(ProductionOrders.Detail.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(code((Throwable) e)).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.production_order_status_history "
                + "WHERE production_order_id = ? AND to_status = 'ON_HOLD'", Integer.class, order)).isEqualTo(1);
    }

    private static List<Object> race(List<Callable<?>> tasks) throws Exception {
        var pool = Executors.newFixedThreadPool(tasks.size());
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
            for (Future<Object> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
