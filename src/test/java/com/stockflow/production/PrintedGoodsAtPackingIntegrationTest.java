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
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.api.FulfillmentTask;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.service.MaterialIssues;
import com.stockflow.production.internal.service.OrderProductionService;
import com.stockflow.production.internal.service.PrintRuns;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.ProductionWorkflow;
import com.stockflow.production.internal.service.SampleProductionProgress;
import com.stockflow.production.internal.service.SampleRequests;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Review of PR #109, item 5 (decisions of 2026-10-16), from goods handed to packing to the end of their life:
 *
 * <ul>
 *   <li><b>5b</b> - a print line's goods leave stock when their package is complete, in the transaction that verifies
 *       the design checksums and records the package: once, with one ledger row per hold naming the package. A
 *       mismatch deducts nothing; completing again deducts nothing.</li>
 *   <li><b>5a</b> - BR-PRD-10: the order cancelled while they wait in packing, they are scrapped, never sold. The
 *       hold stays - nobody else can take them, available-to-promise does not move - and a SCRAP write-off of
 *       exactly them is asked for; a rejection leaves them held; approval deducts them once. Cancelled after the
 *       package, nothing is asked twice and nothing comes back.</li>
 * </ul>
 *
 * <p>Statuses and stock move only through the services; the sales order is rows, as in
 * {@code PrintRunIntegrationTest}, with real holds.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class PrintedGoodsAtPackingIntegrationTest {

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
    @Autowired PickConsumption consumption;
    @Autowired FulfillmentService fulfillment;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private ProductionFixtures fixtures;
    private UUID hcm;
    private CurrentUser sales;
    private CurrentUser printer;
    private CurrentUser storekeeper;
    private CurrentUser inspector;
    private CurrentUser manager;
    private CurrentUser approver;
    private CurrentUser coordinator;
    private CurrentUser picker;

    @BeforeEach
    void setUp() {
        fixtures = new ProductionFixtures(storage, jdbc, tx, entityManager, progress);
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        ConfirmedDesigns.stockBlanks(jdbc, tx, 40);
        sales = user(Role.SALES_STAFF);
        printer = user(Role.PRODUCTION_STAFF);
        storekeeper = user(Role.WAREHOUSE_STAFF);
        inspector = user(Role.QC_STAFF);
        manager = user(Role.WAREHOUSE_MANAGER);
        approver = user(Role.WAREHOUSE_MANAGER);
        coordinator = user(Role.ORDER_COORDINATOR);
        picker = assignable(Role.WAREHOUSE_STAFF);
    }

    private CurrentUser user(Role role) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return as(id, role);
    }

    private CurrentUser as(UUID id, Role role) {
        return new CurrentUser(id, role.name() + "-" + id, Set.of(role), Set.of(), DataScope.WAREHOUSE, Set.of("HCM"),
                Set.of(hcm));
    }

    /** A warehouse staff member a pick may be given to: the role and the warehouse, as identity records them. */
    private CurrentUser assignable(Role role) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", id, role.name());
            jdbc.update("""
                    INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                    VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", id, hcm);
        });
        return as(id, role);
    }

    // ------------------------------------------------------------------------------------------- fixtures

    record Line(UUID orderId, UUID lineId, UUID productionOrderId, String orderNumber) {
    }

    private UUID approvedSample(ProductionFixtures.Design design) {
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ProductionFixtures.BLANK, 1, null)));
        samples.submit(sales, draft.id(), hcm);
        fixtures.workshopFinished(draft.id());
        samples.markSent(sales, draft.id());
        return samples.approve(sales, draft.id()).id();
    }

    /**
     * A paid order line of {@code quantity}, printed and passed QC: its goods held for it in packing, the order
     * READY_TO_FULFILL.
     */
    private Line inPacking(int quantity) throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        UUID sample = approvedSample(design);
        UUID orderId = Identifiers.newId();
        UUID lineId = Identifiers.newId();
        String number = "SO-20991230-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, version, created_at, paid_amount, paid_in_full_at, warehouse_id,
                        released_at, released_by)
                    VALUES (?, ?, ?, ?, 'IN_PRODUCTION', ?, 'VND', NOW(), 0, NOW(), ?, NOW(), ?, NOW(), ?)""",
                    orderId, number, DemoData.CUSTOMER_ID, UUID.randomUUID(), quantity * 2000, quantity * 2000, hcm,
                    sales.userId());
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
        UUID productionOrderId = orderProduction.createFor(new OrderLinesReleasedForProduction(orderId, number, hcm,
                List.of(new OrderLinesReleasedForProduction.Line(lineId, ProductionFixtures.BLANK, quantity,
                        design.snapshotId(), design.checksum(), sample, null)))).get(0).id();
        workflow.prepress(printer, productionOrderId, true, null);
        for (int scans = 0; scans < 10; scans++) {
            if (issues.issue(storekeeper, productionOrderId, new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP,
                    null)).order().summary().status() != ProductionOrder.Status.READY) {
                break;
            }
        }
        runs.startPrinting(printer, productionOrderId);
        runs.recordOutput(printer, productionOrderId, quantity, List.of());
        ProductionOrders.Detail done = runs.recordQc(inspector, productionOrderId, quantity, 0, null, PACKING);
        assertThat(done.summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        Await.until("the order is ready to fulfil", ASYNC,
                () -> salesOrders.findById(orderId).orElseThrow().status() == OrderStatus.READY_TO_FULFILL);
        return new Line(orderId, lineId, productionOrderId, number);
    }

    /** The coordinator gives the task to the picker, who starts and completes it: a print line has nothing to pick. */
    private UUID picked(Line line) {
        UUID task = fulfillment.createTask(line.orderId(), picker.userId(), coordinator).taskId();
        fulfillment.startPicking(task, picker);
        fulfillment.completePicking(task, picker);
        return task;
    }

    private List<UUID> holdsOf(Line line) {
        return jdbc.queryForList("SELECT reservation_id FROM ordering.order_line_reservation WHERE order_line_id = ?",
                UUID.class, line.lineId());
    }

    private List<String> holdStatuses(List<UUID> holds) {
        return holds.stream().map(id -> jdbc.queryForObject(
                "SELECT status FROM inventory.stock_reservation WHERE id = ?", String.class, id)).toList();
    }

    private int onHandAtPacking() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand), 0)::int FROM inventory.stock_item WHERE sku = ? AND location_code = ?""",
                Integer.class, BLANK.code(), PACKING);
    }

    private int packageLedgerRows(UUID packId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)::int FROM inventory.stock_movement
                 WHERE movement_type = 'PICK' AND reference_type = 'PACKAGE' AND reference_id = ?""", Integer.class, packId);
    }

    private UUID packOf(UUID task) {
        return jdbc.queryForObject("SELECT id FROM fulfillment.pack WHERE pick_id = ?", UUID.class, task);
    }

    private List<Map<String, Object>> scrapOf(List<UUID> holds) {
        return jdbc.queryForList("""
                SELECT id, status, quantity_delta, reason_code, location_code, note, reservation_id
                  FROM inventory.stock_adjustment WHERE reservation_id IN (%s) ORDER BY created_at, id"""
                .formatted(String.join(",", java.util.Collections.nCopies(holds.size(), "?"))), holds.toArray());
    }

    private void cancel(Line line) {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> salesOrders.cancel(line.orderId(),
                new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, "The customer withdrew", null,
                        sales.userId())));
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    // ------------------------------------------------------------------------------------------- 5b

    @Test
    @DisplayName("5b: completing the package deducts the print line's goods once, one ledger row per hold naming the "
            + "package; completing again deducts nothing")
    void packCompletionDeductsThePrintLineOnce() throws Exception {
        Line line = inPacking(3);
        List<UUID> holds = holdsOf(line);
        int before = onHandAtPacking();
        UUID task = picked(line);
        assertThat(onHandAtPacking()).as("picking takes nothing of a print line").isEqualTo(before);

        FulfillmentTask packed = fulfillment.completePacking(task, picker);

        assertThat(packed.packStatus()).isEqualTo("PACKED");
        assertThat(onHandAtPacking()).isEqualTo(before - 3);
        UUID pack = packOf(task);
        List<String> statuses = holdStatuses(holds);
        assertThat(statuses).contains("CONSUMED").allMatch(s -> s.equals("CONSUMED") || s.equals("RELEASED"));
        assertThat(packageLedgerRows(pack)).isEqualTo(statuses.stream().filter("CONSUMED"::equals).count());
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0)::int FROM inventory.stock_movement
                 WHERE movement_type = 'PICK' AND reference_type = 'PACKAGE' AND reference_id = ?""", Integer.class,
                pack)).isEqualTo(3);

        assertThat(code(catchThrowable(() -> fulfillment.completePacking(task, picker)))).isEqualTo(ErrorCode.CONFLICT);
        List<UUID> consumedHolds = holds.stream().filter(id -> "CONSUMED".equals(jdbc.queryForObject(
                "SELECT status FROM inventory.stock_reservation WHERE id = ?", String.class, id))).toList();
        List<com.stockflow.inventory.consumption.ConsumedPick> replay = tx.execute(s -> consumption.consumeForPack(pack,
                line.orderId(), consumedHolds, picker.userId()));
        assertThat(replay).as("a replay for the package is answered as done").isNotEmpty()
                .allSatisfy(consumed -> assertThat(consumed.alreadyConsumed()).isTrue());
        assertThat(onHandAtPacking()).as("deducted once").isEqualTo(before - 3);
        assertThat(packageLedgerRows(pack)).isEqualTo(statuses.stream().filter("CONSUMED"::equals).count());
    }

    @Test
    @DisplayName("5b: a design checksum that no longer matches holds the package and deducts nothing; the goods stay held")
    void aMismatchDeductsNothing() throws Exception {
        Line line = inPacking(2);
        List<UUID> holds = holdsOf(line);
        int before = onHandAtPacking();
        UUID task = picked(line);
        // The artwork changed under the order: the line's checksum no longer matches the confirmed snapshot. The
        // checksum is immutable to the application, so the trigger is set aside for this one statement.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.order_line SET design_checksum = ? WHERE id = ?", "0".repeat(64), line.lineId());
        });

        FulfillmentTask held = fulfillment.completePacking(task, picker);

        assertThat(held.packStatus()).isEqualTo("ON_HOLD");
        assertThat(onHandAtPacking()).isEqualTo(before);
        assertThat(holdStatuses(holds)).doesNotContain("CONSUMED");
        assertThat(packageLedgerRows(packOf(task))).isZero();
        assertThat(code(catchThrowable(() -> stockOperations.askScrapAgain(holds.get(0), "Scrap it", manager.userId()))))
                .as("a live order's goods are nobody's to scrap").isEqualTo(ErrorCode.CONFLICT);
        assertThat(scrapOf(holds)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------- 5a

    @Test
    @DisplayName("5a: cancelled while its goods wait in packing - they stay held (ATP unchanged), one SCRAP write-off of "
            + "exactly them is asked for; asked again, the same; rejected, still held; approved, deducted once")
    void cancelledInPackingIsScrappedNeverSold() throws Exception {
        Line line = inPacking(3);
        List<UUID> holds = holdsOf(line);
        int before = onHandAtPacking();
        int atp = inventory.availableToPromise(BLANK);
        List<String> heldBefore = holdStatuses(holds);

        cancel(line);

        assertThat(holdStatuses(holds)).as("kept, not freed for someone else").isEqualTo(heldBefore);
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp);
        assertThat(onHandAtPacking()).isEqualTo(before);
        List<Map<String, Object>> scrap = scrapOf(holds);
        assertThat(scrap).isNotEmpty().allSatisfy(row -> {
            assertThat(row).containsEntry("status", "PENDING_APPROVAL").containsEntry("reason_code", "SCRAP")
                    .containsEntry("location_code", PACKING);
            assertThat((String) row.get("note")).contains(line.orderNumber());
        });
        assertThat(scrap.stream().mapToInt(row -> (Integer) row.get("quantity_delta")).sum()).isEqualTo(-3);

        // Asked again (a retry, a second cancellation attempt): the same write-off, nothing more.
        UUID first = (UUID) scrap.get(0).get("reservation_id");
        StockAdjustmentSummary again = tx.execute(s -> inventory.scrapCancelledPrint(first, line.orderId(), "again",
                manager.userId())).orElseThrow();
        assertThat(again.adjustmentId()).isEqualTo(scrap.get(0).get("id"));
        assertThat(code(catchThrowable(() -> cancel(line)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(scrapOf(holds)).hasSameSizeAs(scrap);

        // Four eyes: the canceller does not approve; a rejection leaves the goods held, and they may be asked again.
        UUID adjustment = (UUID) scrap.get(0).get("id");
        assertThat(code(catchThrowable(() -> stockOperations.approve(adjustment, sales.userId()))))
                .isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
        stockOperations.reject(adjustment, approver.userId(), "Counted 2, not 3");
        assertThat(holdStatuses(holds)).isEqualTo(heldBefore);
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp);
        StockAdjustmentSummary renewed = stockOperations.askScrapAgain(first, "Recounted: 3", manager.userId());
        assertThat(renewed.adjustmentId()).isNotEqualTo(adjustment);
        assertThat(stockOperations.askScrapAgain(first, "twice", manager.userId()).adjustmentId())
                .as("asked again while it stands: the same").isEqualTo(renewed.adjustmentId());

        scrapOf(holds).stream().filter(row -> "PENDING_APPROVAL".equals(row.get("status")))
                .forEach(row -> stockOperations.approve((UUID) row.get("id"), approver.userId()));
        assertThat(onHandAtPacking()).isEqualTo(before - 3);
        assertThat(holdStatuses(holds)).as("closed as scrap").doesNotContain("HELD");
        assertThat(inventory.availableToPromise(BLANK)).as("never on sale").isEqualTo(atp);
        assertThat(code(catchThrowable(() -> stockOperations.approve(renewed.adjustmentId(), approver.userId()))))
                .as("deducted once").isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
        assertThat(onHandAtPacking()).isEqualTo(before - 3);
    }

    @Test
    @DisplayName("audit of #109: another warehouse's manager reads nothing of a cancelled order's scrap request, and cannot ask it again")
    void askingAgainIsWarehouseScoped() throws Exception {
        Line line = inPacking(2);
        UUID hold = holdsOf(line).getFirst();
        cancel(line);
        // Assigned to another warehouse only (the scope compares prefixes; the warehouse need not exist here).
        UUID otherWarehouse = UUID.randomUUID();
        String otherPrefix = "ZZ";
        CurrentUser elsewhere = new CurrentUser(manager.userId(), "elsewhere", Set.of(Role.WAREHOUSE_MANAGER), Set.of(),
                DataScope.WAREHOUSE, Set.of(otherPrefix), Set.of(otherWarehouse));

        Throwable refused = catchThrowable(() -> WithCurrentUser.run(elsewhere, DataScope.WAREHOUSE,
                () -> stockOperations.askScrapAgain(hold, "Scrap them", elsewhere.userId())));

        assertThat(code(refused)).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(refused.getMessage()).doesNotContain("ADJ-");
    }

    @Test
    @DisplayName("audit of #109: free blanks moved into a packing area and held by an order are released on its cancellation - only goods production handed there are kept")
    void freeStockInAPackingAreaIsReleasedNotKept() throws Exception {
        UUID orderId = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version, created_at,
                        received_at) VALUES (?, ?, ?, 1, 0, 'AVAILABLE', 0, NOW(), NOW() - interval '9000 days')""",
                    Identifiers.newId(), BLANK.code(), PACKING);
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, version, created_at) VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 2000, 'VND', NOW(), 0,
                        NOW())""", orderId, "SO-20991231-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current()
                    .nextInt(1_000_000)), DemoData.CUSTOMER_ID, UUID.randomUUID());
        });
        UUID hold = tx.execute(s -> inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), BLANK, 1, orderId)))
                .reservationIds().getFirst();
        assertThat(jdbc.queryForObject("""
                SELECT s.location_code FROM inventory.stock_reservation r JOIN inventory.stock_item s
                    ON s.id = r.stock_item_id WHERE r.id = ?""", String.class, hold)).as("the oldest layer").isEqualTo(PACKING);

        tx.executeWithoutResult(s -> inventory.release(hold, "ORDER_CANCELLED"));

        assertThat(holdStatuses(List.of(hold))).containsExactly("RELEASED");
    }

    @Test
    @DisplayName("03 §9: once the package is completed the order is not cancelled (it goes through a return) - deducted "
            + "once at the package, nothing scrapped, nothing back on sale")
    void aPackedOrderIsNotCancelled() throws Exception {
        Line line = inPacking(2);
        List<UUID> holds = holdsOf(line);
        int before = onHandAtPacking();
        UUID task = picked(line);
        fulfillment.completePacking(task, picker);
        int atp = inventory.availableToPromise(BLANK);
        assertThat(jdbc.queryForObject("SELECT packed_at FROM ordering.customer_order WHERE id = ?",
                java.sql.Timestamp.class, line.orderId())).isNotNull();

        assertThat(code(catchThrowable(() -> cancel(line)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);

        assertThat(salesOrders.findById(line.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.IN_FULFILMENT);
        assertThat(jdbc.queryForObject("SELECT p.status FROM fulfillment.pack p WHERE p.pick_id = ?", String.class, task))
                .isEqualTo("PACKED");
        assertThat(onHandAtPacking()).isEqualTo(before - 2);
        assertThat(scrapOf(holds)).isEmpty();
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp);
    }

    @Test
    @DisplayName("race: a cancellation while a package is being completed waits for it and is then refused; one that "
            + "came first stops the package")
    void aCancellationRacingThePackageIsOrdered() throws Exception {
        Line line = inPacking(2);
        UUID task = picked(line);
        java.util.concurrent.CountDownLatch packedNotCommitted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch commit = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Void> packing = java.util.concurrent.CompletableFuture.runAsync(() ->
                tx.executeWithoutResult(s -> {
                    salesOrders.recordPacked(line.orderId());
                    packedNotCommitted.countDown();
                    try {
                        commit.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(packedNotCommitted.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        java.util.concurrent.CompletableFuture<Throwable> cancelling = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> catchThrowable(() -> cancel(line)));
        try {
            Await.until("the cancellation waits on the order row", ASYNC, () -> jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                       AND wait_event_type = 'Lock'""", Integer.class) > 0);
            assertThat(cancelling).isNotDone();
        } finally {
            commit.countDown();
        }
        packing.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(code(cancelling.get(30, java.util.concurrent.TimeUnit.SECONDS)))
                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);

        Line other = inPacking(2);
        UUID otherTask = picked(other);
        cancel(other);
        assertThat(catchThrowable(() -> fulfillment.completePacking(otherTask, picker)))
                .as("a cancelled order's package is not completed").isInstanceOf(BusinessException.class);
        assertThat(packageLedgerRows(packOf(otherTask))).isZero();
    }
}
