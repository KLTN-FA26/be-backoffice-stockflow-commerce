package com.stockflow.production;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.contracts.OrderLinesReleasedForProduction;
import com.stockflow.contracts.ProductionCompleted;
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionRecords;
import com.stockflow.production.internal.service.MaterialIssues;
import com.stockflow.production.internal.service.OrderProductionService;
import com.stockflow.production.internal.service.PrintRuns;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.ProductionWorkflow;
import com.stockflow.production.internal.service.SampleProductionProgress;
import com.stockflow.production.internal.service.SampleRequests;
import com.stockflow.production.internal.domain.SampleStatus;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-398..401 / BE 411, 418, 420, 413 against a real PostgreSQL, from blanks issued to goods in packing:
 * printing, spoilage written off through the adjustment approval without waiting for it (Q4), QC with a reprint
 * round and a BLANKS_SHORT hold, completion (goods still held for the order, {@code ProductionCompleted} once; a
 * sample written off as SAMPLE and its round READY), cancellation before printing, an order cancelled under a job
 * and a job stopped on the press (Q2) — and, throughout, that no printed unit becomes stock anybody can buy.
 * Statuses and stock move only through the services; the sales order and its lines are rows, as in
 * {@code MaterialIssueIntegrationTest}, with real holds.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class PrintRunIntegrationTest {

    private static final String PRINT_SHOP = "HCM-PRD01";
    private static final String PACKING = "HCM-PACK01";
    private static final Sku BLANK = new Sku(ProductionFixtures.BLANK);
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired PrintRuns runs;
    @Autowired MaterialIssues issues;
    @Autowired ProductionWorkflow workflow;
    @Autowired ProductionOrders productionOrders;
    @Autowired SampleRequests samples;
    @Autowired SampleProductionProgress progress;
    @Autowired OrderProductionService orderProduction;
    @Autowired OrderService salesOrders;
    @Autowired InventoryService inventory;
    @Autowired StockOperations stockOperations;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired RecordedEvents.Recorder recorded;

    private ProductionFixtures fixtures;
    private UUID hcm;
    private CurrentUser sales;
    private CurrentUser printer;
    private CurrentUser storekeeper;
    private CurrentUser inspector;
    private CurrentUser manager;
    private CurrentUser approver;

    @BeforeEach
    void setUp() {
        fixtures = new ProductionFixtures(storage, jdbc, tx, entityManager, progress);
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        ConfirmedDesigns.stockBlanks(jdbc, tx, 80);
        sales = user(Role.SALES_STAFF);
        printer = user(Role.PRODUCTION_STAFF);
        storekeeper = user(Role.WAREHOUSE_STAFF);
        inspector = user(Role.QC_STAFF);
        manager = user(Role.WAREHOUSE_MANAGER);
        approver = user(Role.WAREHOUSE_MANAGER);
        recorded.clear();
    }

    private CurrentUser user(Role role) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(id, role.name() + "-" + id, Set.of(role), Set.of(), DataScope.WAREHOUSE, Set.of("HCM"),
                Set.of(hcm));
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    // ------------------------------------------------------------------------------------------- fixtures

    private UUID approvedSample(ProductionFixtures.Design design) {
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ProductionFixtures.BLANK, 1, null)));
        samples.submit(sales, draft.id(), hcm);
        fixtures.workshopFinished(draft.id());
        samples.markSent(sales, draft.id());
        return samples.approve(sales, draft.id()).id();
    }

    record Line(UUID orderId, UUID productionOrderId) {
    }

    /** A released, paid order line of {@code quantity} with its blanks held at checkout; its LSX passed prepress. */
    private Line released(int quantity) throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        UUID sample = approvedSample(design);
        UUID orderId = Identifiers.newId();
        UUID lineId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, version, created_at, paid_amount, paid_in_full_at, warehouse_id,
                        released_at, released_by)
                    VALUES (?, ?, ?, ?, 'IN_PRODUCTION', 20000, 'VND', NOW(), 0, NOW(), 20000, NOW(), ?, NOW(), ?)""",
                    orderId, "SO-20991229-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(1_000_000)), DemoData.CUSTOMER_ID, UUID.randomUUID(), hcm, sales.userId());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency,
                        design_snapshot_id, design_checksum) VALUES (?, ?, ?, ?, 2000, 'VND', ?, ?)""",
                    lineId, orderId, ProductionFixtures.BLANK, quantity, design.snapshotId(), design.checksum());
        });
        tx.executeWithoutResult(s -> {
            ReserveStockResult held = inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), BLANK, quantity,
                    orderId));
            held.reservationIds().forEach(id -> jdbc.update(
                    "INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES (?, ?)",
                    lineId, id));
            inventory.pinReservations(orderId);
        });
        UUID productionOrderId = orderProduction.createFor(new OrderLinesReleasedForProduction(orderId, "SO-IT", hcm,
                List.of(new OrderLinesReleasedForProduction.Line(lineId, ProductionFixtures.BLANK, quantity,
                        design.snapshotId(), design.checksum(), sample, null)))).get(0).id();
        workflow.prepress(printer, productionOrderId, true, null);
        return new Line(orderId, productionOrderId);
    }

    /** Scans until the LSX has all its blanks. */
    private void issueAll(UUID productionOrderId) {
        for (int scans = 0; scans < 10; scans++) {
            ProductionOrders.Detail after = issues.issue(storekeeper, productionOrderId,
                    new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP, null)).order();
            if (after.summary().status() != ProductionOrder.Status.READY) {
                return;
            }
        }
        throw new AssertionError("still READY after 10 scans");
    }

    /** Units held, live, for the order or the LSX at {@code location}. */
    private int heldAt(Line line, String location) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.quantity), 0) FROM inventory.stock_reservation r
                  JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.status = 'HELD' AND s.location_code = ?
                   AND (r.order_id = ? OR r.production_order_id = ?)""", Integer.class, location, line.orderId(),
                line.productionOrderId());
    }

    private int onHandAt(String location) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(on_hand), 0) FROM inventory.stock_item WHERE sku = ? AND location_code = ?",
                Integer.class, BLANK.code(), location);
    }

    private List<Map<String, Object>> writeOffs(UUID productionOrderId) {
        return jdbc.queryForList("""
                SELECT a.id, a.status, a.quantity_delta, a.reason_code, a.received_at, s.reason AS scrap_reason
                  FROM production.production_scrap s JOIN inventory.stock_adjustment a ON a.id = s.stock_adjustment_id
                 WHERE s.production_order_id = ? ORDER BY s.recorded_at, s.id""", productionOrderId);
    }

    /** Sales cancels the order through the order module, as an admin request would. */
    private void cancelOrder(UUID orderId, String note) {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> salesOrders.cancel(orderId,
                new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, note, null, sales.userId())));
    }

    private ProductionOrders.Detail detail(UUID productionOrderId) {
        return productionOrders.get(manager, productionOrderId);
    }

    // ------------------------------------------------------------------------------------------- tests

    @Test
    @DisplayName("ORDER: print, spoil 2 (out of the hold at once, write-off approved once), QC fails 1, reprint 3 held and issued, complete: 10 good held for the order in packing, announced once")
    void orderFromPressToPacking() throws Exception {
        Line line = released(10);
        issueAll(line.productionOrderId());
        assertThatThrownBy(() -> runs.recordOutput(printer, line.productionOrderId(), 10, List.of()))
                .as("not started").extracting(PrintRunIntegrationTest::code)
                .isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        assertThat(runs.startPrinting(printer, line.productionOrderId()).summary().status())
                .isEqualTo(ProductionOrder.Status.PRINTING);
        int atp = inventory.availableToPromise(BLANK);
        int printShop = onHandAt(PRINT_SHOP);

        assertThatThrownBy(() -> runs.recordOutput(printer, line.productionOrderId(), 11, List.of()))
                .as("only 10 blanks were issued").extracting(PrintRunIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        ProductionOrders.Detail out = runs.recordOutput(printer, line.productionOrderId(), 10,
                List.of(new PrintRuns.ScrapLine(ProductionRecords.ScrapReason.PRINT_DEFECT, 2, "Smudged logo")));
        assertThat(out.summary().status()).isEqualTo(ProductionOrder.Status.QC);
        assertThat(out.printedQuantity()).isEqualTo(10);
        assertThat(out.scrapQuantity()).isEqualTo(2);
        assertThat(heldAt(line, PRINT_SHOP)).as("the spoilt units left the hold at once").isEqualTo(8);
        assertThat(onHandAt(PRINT_SHOP)).as("and are still there, waiting for the write-off").isEqualTo(printShop);
        assertThat(inventory.availableToPromise(BLANK)).as("but nobody can take them").isEqualTo(atp);
        List<Map<String, Object>> scrap = writeOffs(line.productionOrderId());
        assertThat(scrap).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "PENDING_APPROVAL").containsEntry("quantity_delta", -2)
                    .containsEntry("reason_code", "SCRAP").containsEntry("scrap_reason", "PRINT_DEFECT");
            assertThat(row.get("received_at")).as("names its layer").isNotNull();
        });

        UUID writeOff = (UUID) scrap.get(0).get("id");
        stockOperations.approve(writeOff, approver.userId());
        assertThat(onHandAt(PRINT_SHOP)).isEqualTo(printShop - 2);
        assertThatThrownBy(() -> stockOperations.approve(writeOff, approver.userId())).as("deducted once")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp);

        ProductionOrders.Detail short1 = runs.recordQc(inspector, line.productionOrderId(), 7, 1, "Colour off", null);
        assertThat(short1.summary().status()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(short1.summary().goodQuantity()).isEqualTo(7);
        assertThat(inventory.productionHolds(line.productionOrderId())).as("3 blanks held for the reprint")
                .extracting(h -> h.quantity()).containsExactly(3);
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp - 3);
        assertThat(runs.scrapOf(manager, line.productionOrderId())).extracting(ProductionRecords.Scrap::reason)
                .containsExactly(ProductionRecords.ScrapReason.PRINT_DEFECT, ProductionRecords.ScrapReason.QC_FAILED);

        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        runs.recordOutput(printer, line.productionOrderId(), 3, List.of());
        ProductionOrders.Detail done = runs.recordQc(inspector, line.productionOrderId(), 3, 0, null, null);

        assertThat(done.summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(done.summary().goodQuantity()).isEqualTo(10);
        assertThat(done.completedAt()).isNotNull();
        assertThat(heldAt(line, PACKING)).as("all 10 good, held for the order, in packing").isEqualTo(10);
        assertThat(heldAt(line, PRINT_SHOP)).isZero();
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT s.status FROM inventory.stock_reservation r JOIN inventory.stock_item s
                    ON s.id = r.stock_item_id WHERE r.status = 'HELD' AND (r.order_id = ? OR r.production_order_id = ?)""",
                String.class, line.orderId(), line.productionOrderId())).containsExactly("AVAILABLE");
        assertThat(inventory.availableToPromise(BLANK)).as("held: nobody else's").isEqualTo(atp - 3);
        assertThat(recorded.matching(ProductionCompleted.class, e -> e.productionOrderId().equals(line.productionOrderId())))
                .singleElement().isEqualTo(new ProductionCompleted(line.orderId(),
                        done.summary().salesOrderLineId(), line.productionOrderId(), 10));
        assertThat(runs.qcRoundsOf(manager, line.productionOrderId())).extracting(ProductionRecords.QcRound::round)
                .containsExactly(1, 2);
        assertThat(done.history()).extracting(ProductionOrders.StatusChange::toStatus).containsExactly(
                "PENDING_PREPRESS", "READY", "MATERIAL_ISSUED", "PRINTING", "QC", "READY", "MATERIAL_ISSUED",
                "PRINTING", "QC", "COMPLETED");
    }

    @Test
    @DisplayName("Q4: the job goes on while a write-off waits; a rejected write-off leaves the spoilt units unsellable in the print shop")
    void rejectedWriteOffNeverSells() throws Exception {
        Line line = released(4);
        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        runs.recordOutput(printer, line.productionOrderId(), 4,
                List.of(new PrintRuns.ScrapLine(ProductionRecords.ScrapReason.SETUP, 1, null)));
        int atp = inventory.availableToPromise(BLANK);
        int printShop = onHandAt(PRINT_SHOP);
        UUID writeOff = (UUID) writeOffs(line.productionOrderId()).get(0).get("id");

        ProductionOrders.Detail qc = runs.recordQc(inspector, line.productionOrderId(), 3, 0, null, null);
        assertThat(qc.summary().status()).as("did not wait for the approval").isEqualTo(ProductionOrder.Status.READY);
        stockOperations.reject(writeOff, approver.userId(), "Counted again: it was spoilt, but the form is wrong");

        assertThat(onHandAt(PRINT_SHOP)).isEqualTo(printShop);
        assertThat(inventory.availableToPromise(BLANK)).as("rejection puts nothing on sale")
                .isEqualTo(atp - 1);   // only the reprint hold of 1 changed it
        assertThat(heldAt(line, PRINT_SHOP)).isEqualTo(3);
    }

    @Test
    @DisplayName("a reprint the warehouse cannot cover puts the order ON_HOLD (BLANKS_SHORT), holds nothing and tells purchasing; resumed, the scan holds and issues them")
    void blanksShort() throws Exception {
        Line line = released(2);
        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        runs.recordOutput(printer, line.productionOrderId(), 2, List.of());

        // Every free blank in HCM goes to another order for the moment.
        UUID other = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, paid_amount, paid_in_full_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 20000, 'VND', NOW(), 0, NOW(), 20000, NOW())""", other,
                "SO-20991228-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        int free = inventory.availableToPromise(BLANK, "HCM");
        List<UUID> drained = tx.execute(s -> inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), BLANK, free,
                other)).reservationIds());

        ProductionOrders.Detail held = runs.recordQc(inspector, line.productionOrderId(), 0, 2, "All smeared", null);
        assertThat(held.summary().status()).isEqualTo(ProductionOrder.Status.ON_HOLD);
        assertThat(held.summary().holdReason()).isEqualTo("BLANKS_SHORT");
        assertThat(inventory.productionHolds(line.productionOrderId())).isEmpty();
        assertThat(recorded.matching(ProductionOrderHeld.class, e -> e.productionOrderId().equals(line.productionOrderId())))
                .singleElement().satisfies(e -> assertThat(e.reason()).isEqualTo("BLANKS_SHORT"));

        tx.executeWithoutResult(s -> drained.forEach(id -> inventory.release(id, "ORDER_CANCELLED")));
        assertThat(workflow.resume(manager, line.productionOrderId()).summary().status())
                .isEqualTo(ProductionOrder.Status.READY);
        issueAll(line.productionOrderId());
        assertThat(detail(line.productionOrderId()).summary().status()).isEqualTo(ProductionOrder.Status.MATERIAL_ISSUED);
        assertThat(heldAt(line, PRINT_SHOP)).as("the 2 new blanks").isEqualTo(2);
    }

    @Test
    @DisplayName("cancel before printing: the issued blanks go back where they came from, still held for the order, which still wants them")
    void cancelBeforePrinting() throws Exception {
        Line line = released(5);
        issueAll(line.productionOrderId());
        int atp = inventory.availableToPromise(BLANK);
        List<String> origins = jdbc.queryForList("SELECT DISTINCT from_location FROM production.production_material_issue "
                + "WHERE production_order_id = ?", String.class, line.productionOrderId());

        assertThatThrownBy(() -> runs.cancel(manager, line.productionOrderId(), " ")).as("says why")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        ProductionOrders.Detail cancelled = runs.cancel(manager, line.productionOrderId(), "Customer changed the design");

        assertThat(cancelled.summary().status()).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(cancelled.cancellationReason()).isEqualTo("Customer changed the design");
        assertThat(heldAt(line, PRINT_SHOP)).isZero();
        assertThat(origins.stream().mapToInt(location -> heldAt(line, location)).sum()).isEqualTo(5);
        assertThat(inventory.availableToPromise(BLANK)).as("still the order's").isEqualTo(atp);
        assertThatThrownBy(() -> runs.startPrinting(printer, line.productionOrderId()))
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
    }

    @Test
    @DisplayName("an order cancelled after its blanks were issued: its job is cancelled and the blanks come back free; one on the press is held for the manager instead")
    void orderCancelledUnderTheJob() throws Exception {
        Line issued = released(5);
        issueAll(issued.productionOrderId());
        int atp = inventory.availableToPromise(BLANK);

        cancelOrder(issued.orderId(), "No longer needed");
        Await.until("the job is cancelled", ASYNC,
                () -> detail(issued.productionOrderId()).summary().status() == ProductionOrder.Status.CANCELLED);
        assertThat(heldAt(issued, PRINT_SHOP)).isZero();
        assertThat(inventory.availableToPromise(BLANK)).as("the 5 unprinted blanks are free again").isEqualTo(atp + 5);

        Line printing = released(6);
        issueAll(printing.productionOrderId());
        runs.startPrinting(printer, printing.productionOrderId());
        cancelOrder(printing.orderId(), "No longer needed");
        Await.until("the job on the press is held", ASYNC,
                () -> detail(printing.productionOrderId()).summary().status() == ProductionOrder.Status.ON_HOLD);
        assertThat(heldAt(printing, PRINT_SHOP)).as("nothing let go: only the print shop knows what it printed")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("Q2: a job stopped on the press keeps its printed count, writes the printed units off, and returns only the unprinted blanks")
    void stopOnThePress() throws Exception {
        Line line = released(10);
        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        cancelOrder(line.orderId(), "Cancelled while printing");
        Await.until("held for the manager", ASYNC,
                () -> detail(line.productionOrderId()).summary().status() == ProductionOrder.Status.ON_HOLD);
        int atp = inventory.availableToPromise(BLANK);

        assertThatThrownBy(() -> runs.cancel(manager, line.productionOrderId(), "too late")).as("printing started")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        ProductionOrders.Detail stopped = runs.stop(manager, line.productionOrderId(), 4,
                List.of(new PrintRuns.ScrapLine(ProductionRecords.ScrapReason.SETUP, 1, null)), "Customer cancelled");

        assertThat(stopped.summary().status()).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(stopped.printedQuantity()).as("kept on the record").isEqualTo(4);
        assertThat(stopped.scrapQuantity()).isEqualTo(4);
        assertThat(heldAt(line, PRINT_SHOP)).isZero();
        List<Map<String, Object>> writtenOff = writeOffs(line.productionOrderId());
        assertThat(writtenOff.stream().mapToInt(row -> ((Number) row.get("quantity_delta")).intValue()).sum())
                .as("1 spoilt in setup + 3 printed good, sold to nobody").isEqualTo(-4);
        assertThat(writtenOff).extracting(row -> row.get("scrap_reason")).containsOnly("SETUP", "OTHER");
        assertThat(inventory.availableToPromise(BLANK)).as("only the 6 unprinted blanks").isEqualTo(atp + 6);
    }

    @Test
    @DisplayName("SAMPLE: started (round IN_PRODUCTION), printed, QC passed: COMPLETED, its units written off as SAMPLE, the round READY, no ProductionCompleted")
    void sampleCompletes() throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ProductionFixtures.BLANK, 2, null)));
        UUID lsx = samples.submit(sales, draft.id(), hcm).productionOrder().id();
        workflow.prepress(printer, lsx, true, null);
        issueAll(lsx);
        runs.startPrinting(printer, lsx);
        assertThat(samples.get(sales, draft.id()).status()).isEqualTo(SampleStatus.IN_PRODUCTION);
        runs.recordOutput(printer, lsx, 2, List.of());
        ProductionOrders.Detail done = runs.recordQc(inspector, lsx, 2, 0, null, null);

        assertThat(done.summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(samples.get(sales, draft.id()).status()).isEqualTo(SampleStatus.READY);
        assertThat(inventory.productionHolds(lsx)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT quantity_delta FROM inventory.stock_adjustment WHERE reason_code = 'SAMPLE'
                   AND status = 'PENDING_APPROVAL' AND note LIKE ?""", Integer.class, done.summary().orderNumber() + "%"))
                .containsExactly(-2);
        assertThat(recorded.ofType(ProductionCompleted.class)).isEmpty();
    }

    @Test
    @DisplayName("two QC rounds recorded at once: one completes the order, the other is refused; announced once")
    void concurrentQc() throws Exception {
        Line line = released(3);
        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        runs.recordOutput(printer, line.productionOrderId(), 3, List.of());

        List<Object> outcomes = race(List.of(
                () -> runs.recordQc(inspector, line.productionOrderId(), 3, 0, null, null),
                () -> runs.recordQc(inspector, line.productionOrderId(), 3, 0, null, null)));
        assertThat(outcomes).filteredOn(ProductionOrders.Detail.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(code((Throwable) e)).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION));
        assertThat(recorded.matching(ProductionCompleted.class, e -> e.productionOrderId().equals(line.productionOrderId())))
                .hasSize(1);
        assertThat(heldAt(line, PACKING)).isEqualTo(3);
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
