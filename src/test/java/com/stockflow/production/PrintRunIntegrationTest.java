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
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationFee;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionRecords;
import com.stockflow.production.internal.service.MaterialIssues;
import com.stockflow.production.internal.service.OrderProductionService;
import com.stockflow.production.internal.service.PrintRuns;
import com.stockflow.production.internal.service.Subcontracting;
import com.stockflow.production.internal.service.ProductionDashboard;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.ProductionWorkflow;
import com.stockflow.production.internal.service.SampleProductionProgress;
import com.stockflow.production.internal.service.SampleRequests;
import com.stockflow.production.internal.domain.SampleStatus;
import com.stockflow.support.ApprovalLimitFixtures;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
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
import static org.assertj.core.api.Assertions.assertThatCode;
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
// The buyer's details: sending the SUBCONTRACT PO to the subcontractor needs them (procurement, SCRUM-434).
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false", "PO_BUYER_COMPANY_NAME=Buyer test",
        "PO_BUYER_COMPANY_ADDRESS=Company test address", "PO_BUYER_CONTACT_NAME=Buyer contact",
        "PO_BUYER_PHONE=0901234567", "PO_BUYER_EMAIL=buyer@example.com", "PO_RECEIVING_ADDRESS=Test warehouse"})
class PrintRunIntegrationTest {

    private static final String PRINT_SHOP = "HCM-PRD01";
    private static final String PACKING = "HCM-PACK01";
    private static final Sku BLANK = new Sku(ProductionFixtures.BLANK);
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired PrintRuns runs;
    @Autowired Subcontracting subcontracts;
    @Autowired com.stockflow.procurement.api.ProcurementService procurement;
    @Autowired org.springframework.context.ApplicationEventPublisher publisher;
    @Autowired ProductionDashboard dashboard;
    @Autowired MaterialIssues issues;
    @Autowired ProductionWorkflow workflow;
    @Autowired ProductionOrders productionOrders;
    @Autowired SampleRequests samples;
    @Autowired SampleProductionProgress progress;
    @Autowired OrderProductionService orderProduction;
    @Autowired OrderService salesOrders;
    @Autowired com.stockflow.payment.internal.service.ReceivableService receivables;
    @Autowired InventoryService inventory;
    @Autowired StockOperations stockOperations;
    // The test profile stores files locally, which hands out no browser link; the dispatch note needs one (BR-PRD-14).
    // Signing itself is MinioMediaIntegrationTest's; here the link only has to look like the S3 provider's.
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean FileStorage storage;
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
        org.mockito.Mockito.doAnswer(call -> "http://objects.test/" + call.getArgument(0)).when(storage)
                .presignedDownloadUrl(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        fixtures = new ProductionFixtures(storage, jdbc, tx, entityManager, progress);
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        ConfirmedDesigns.stockBlanks(jdbc, tx, 80);
        sales = user(Role.SALES_STAFF);
        printer = user(Role.PRODUCTION_STAFF);
        storekeeper = user(Role.WAREHOUSE_STAFF);
        inspector = user(Role.QC_STAFF);
        manager = user(Role.WAREHOUSE_MANAGER);
        approver = user(Role.WAREHOUSE_MANAGER);
        // Approving a purchase order needs an approval limit (SCRUM-114): the role in a token is not what is read.
        tx.executeWithoutResult(s -> ApprovalLimitFixtures.makeApprover(entityManager, approver.userId()));
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
                    VALUES (?, ?, ?, ?, 'IN_PRODUCTION', ?, 'VND', NOW(), 0, NOW(), 20000, ?, ?, NOW(), ?)""",
                    orderId, "SO-20991229-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(1_000_000)), DemoData.CUSTOMER_ID, UUID.randomUUID(), quantity * 2000,
                    // ck_order_paid_in_full: 20,000 paid is "in full" only for an order of at most that.
                    quantity * 2000 <= 20000 ? java.sql.Timestamp.from(java.time.Instant.now()) : null, hcm,
                    sales.userId());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency,
                        design_snapshot_id, design_checksum) VALUES (?, ?, ?, ?, 2000, 'VND', ?, ?)""",
                    lineId, orderId, ProductionFixtures.BLANK, quantity, design.snapshotId(), design.checksum());
        });
        tx.executeWithoutResult(s -> {
            inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), BLANK, quantity, orderId));
            inventory.pinReservations(orderId);
            // As the release does (SCRUM-152): the SKU reservation becomes allocations on HCM bins.
            inventory.allocate(new com.stockflow.inventory.api.AllocateStockCommand(orderId, "HCM")).allocations()
                    .get(0).holdIds().forEach(id -> jdbc.update(
                            "INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES (?, ?)",
                            lineId, id));
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

    /**
     * G4-Q2 (2026-10-21): units of the order or the LSX that left stock at {@code location} when production completed -
     * consumed holds with their CONSUMPTION ledger row.
     */
    private int consumedAt(Line line, String location) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.quantity), 0) FROM inventory.stock_reservation r
                  JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.status = 'CONSUMED' AND s.location_code = ?
                   AND (r.order_id = ? OR r.production_order_id = ?)
                   AND EXISTS (SELECT 1 FROM inventory.stock_movement m WHERE m.movement_type = 'CONSUMPTION'
                                  AND m.reference_type = 'PRODUCTION_ORDER' AND m.reason = 'Hold ' || r.id
                                  AND m.from_location_code = s.location_code)""", Integer.class, location,
                line.orderId(), line.productionOrderId());
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
        // One write-off per receipt layer the units came from: the order's holds may span layers of the shared stock.
        List<Map<String, Object>> scrap = writeOffs(line.productionOrderId());
        assertThat(scrap).isNotEmpty().allSatisfy(row -> {
            assertThat(row).containsEntry("status", "PENDING_APPROVAL").containsEntry("reason_code", "SCRAP")
                    .containsEntry("scrap_reason", "PRINT_DEFECT");
            assertThat(row.get("received_at")).as("names its layer").isNotNull();
        });
        assertThat(scrap.stream().mapToInt(row -> (Integer) row.get("quantity_delta")).sum()).isEqualTo(-2);

        scrap.forEach(row -> stockOperations.approve((UUID) row.get("id"), approver.userId()));
        assertThat(onHandAt(PRINT_SHOP)).isEqualTo(printShop - 2);
        UUID writeOff = (UUID) scrap.get(0).get("id");
        assertThatThrownBy(() -> stockOperations.approve(writeOff, approver.userId())).as("deducted once")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp);

        ProductionOrders.Detail short1 = runs.recordQc(inspector, line.productionOrderId(), 7, 1, "Colour off", null);
        assertThat(short1.summary().status()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(short1.summary().goodQuantity()).isEqualTo(7);
        assertThat(inventory.productionHolds(line.productionOrderId())).as("3 blanks held for the reprint")
                .extracting(h -> h.quantity()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Integer.class))
                .satisfies(held -> assertThat(held.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3));
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp - 3);
        assertThat(runs.scrapOf(manager, line.productionOrderId()).stream().map(ProductionRecords.Scrap::reason)
                .distinct().toList())
                .containsExactly(ProductionRecords.ScrapReason.PRINT_DEFECT, ProductionRecords.ScrapReason.QC_FAILED);

        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        runs.recordOutput(printer, line.productionOrderId(), 3, List.of());
        ProductionOrders.Detail done = runs.recordQc(inspector, line.productionOrderId(), 3, 0, null, null);

        assertThat(done.summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(done.summary().goodQuantity()).isEqualTo(10);
        assertThat(done.completedAt()).isNotNull();
        // G4-Q2: handed to packing and deducted there as the order completed; nothing of it is held any more.
        assertThat(consumedAt(line, PACKING)).as("all 10 good, the order's, deducted in packing").isEqualTo(10);
        assertThat(heldAt(line, PACKING)).isZero();
        assertThat(heldAt(line, PRINT_SHOP)).isZero();
        assertThat(inventory.availableToPromise(BLANK)).as("held: nobody else's").isEqualTo(atp - 3);
        assertThat(recorded.matching(ProductionCompleted.class, e -> e.productionOrderId().equals(line.productionOrderId())))
                .singleElement().isEqualTo(new ProductionCompleted(line.orderId(),
                        done.summary().salesOrderLineId(), line.productionOrderId(), 10));
        assertThat(runs.qcRoundsOf(manager, line.productionOrderId())).extracting(ProductionRecords.QcRound::round)
                .containsExactly(1, 2);
        // Fulfilment sees every good unit: the reprint hold is the order's now, and on its line.
        List<UUID> held = jdbc.queryForList("""
                SELECT r.id FROM inventory.stock_reservation r JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.status = 'CONSUMED' AND s.location_code = ? AND r.order_id = ?""", UUID.class, PACKING,
                line.orderId());
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM inventory.stock_reservation WHERE id IN ("
                + String.join(",", java.util.Collections.nCopies(held.size(), "?")) + ")", Integer.class,
                held.toArray())).isEqualTo(10);
        assertThat(inventory.productionHolds(line.productionOrderId())).as("nothing left the LSX's own").isEmpty();
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().lines()).singleElement()
                .satisfies(l -> assertThat(l.reservationIds()).containsAll(held));
        assertThat(done.history()).extracting(ProductionOrders.StatusChange::toStatus).containsExactly(
                "PENDING_PREPRESS", "READY", "MATERIAL_ISSUED", "PRINTING", "QC", "READY", "MATERIAL_ISSUED",
                "PRINTING", "QC", "COMPLETED");
    }

    @Test
    @DisplayName("decision 2026-10-17: before printing an order is refunded in full - neither an agreed amount nor a share is kept; once a run starts, an agreed amount is")
    void noFeeIsKeptBeforePrinting() throws Exception {
        Line notPrinted = released(10);
        issueAll(notPrinted.productionOrderId());
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.payment (id, order_id, amount, currency, method, status, captured_at, created_at)
                VALUES (?, ?, 20000, 'VND', 'BANK_TRANSFER', 'CAPTURED', NOW(), NOW())""", Identifiers.newId(),
                notPrinted.orderId()));
        CancellationFee agreed = new CancellationFee(new BigDecimal("5000"), "Blanks prepared", "Agreed by phone");
        assertThatThrownBy(() -> cancelWithFee(notPrinted.orderId(), null, agreed))
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.CANCELLATION_FEE_BEFORE_PRINTING);
        assertThatThrownBy(() -> cancelWithFee(notPrinted.orderId(), new BigDecimal("10"), null))
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.CANCELLATION_FEE_BEFORE_PRINTING);
        assertThat(jdbc.queryForObject("SELECT printing_started_at FROM ordering.customer_order WHERE id = ?",
                java.sql.Timestamp.class, notPrinted.orderId())).isNull();

        cancelWithFee(notPrinted.orderId(), null, null);
        assertThat(salesOrders.findById(notPrinted.orderId()).orElseThrow().cancellation().retainedAmount())
                .isEqualByComparingTo("0");
        Await.until("everything paid is asked back", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM payment.refund WHERE order_id = ? AND amount = 20000""", Integer.class,
                notPrinted.orderId()) == 1);

        Line printing = released(10);
        issueAll(printing.productionOrderId());
        runs.startPrinting(printer, printing.productionOrderId());
        assertThat(jdbc.queryForObject("SELECT printing_started_at FROM ordering.customer_order WHERE id = ?",
                java.sql.Timestamp.class, printing.orderId())).as("recorded by the first run").isNotNull();
        cancelWithFee(printing.orderId(), null, agreed);
        assertThat(salesOrders.findById(printing.orderId()).orElseThrow().cancellation().retainedAmount())
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("audit P1-3: a run that printed 9 of 10 issued blanks, all passed, goes back to MATERIAL_ISSUED and prints the last one - no stuck READY, nothing held twice")
    void aPartialRunPrintsTheRestOfItsIssuedBlanks() throws Exception {
        Line line = released(10);
        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        runs.recordOutput(printer, line.productionOrderId(), 9, List.of());

        ProductionOrders.Detail afterQc = runs.recordQc(inspector, line.productionOrderId(), 9, 0, null, null);

        assertThat(afterQc.summary().status()).as("the 1 unprinted blank covers the shortfall")
                .isEqualTo(ProductionOrder.Status.MATERIAL_ISSUED);
        assertThat(inventory.productionHolds(line.productionOrderId())).as("no reprint blanks held").isEmpty();
        assertThat(heldAt(line, PRINT_SHOP)).isEqualTo(10);
        runs.startPrinting(printer, line.productionOrderId());
        runs.recordOutput(printer, line.productionOrderId(), 1, List.of());
        ProductionOrders.Detail done = runs.recordQc(inspector, line.productionOrderId(), 1, 0, null, PACKING);
        assertThat(done.summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(consumedAt(line, PACKING)).isEqualTo(10);
        assertThat(done.history()).extracting(ProductionOrders.StatusChange::toStatus).containsSubsequence(
                "QC", "READY", "MATERIAL_ISSUED", "PRINTING", "QC", "COMPLETED");
    }

    @Test
    @DisplayName("audit P1-4: a job cancelled after a QC round writes its 7 good units off (four eyes, in the canceller's name) with the 3 QC failures; nothing stays held in the print shop")
    void aJobCancelledAfterQcWritesItsGoodUnitsOff() throws Exception {
        Line line = released(10);
        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        runs.recordOutput(printer, line.productionOrderId(), 10, List.of());
        assertThat(runs.recordQc(inspector, line.productionOrderId(), 7, 3, "Colour off", null).summary().status())
                .isEqualTo(ProductionOrder.Status.READY);
        int atp = inventory.availableToPromise(BLANK);

        ProductionOrders.Detail cancelled = runs.cancel(manager, line.productionOrderId(), "Customer withdrew the design");

        assertThat(cancelled.summary().status()).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(cancelled.summary().goodQuantity()).isZero();
        assertThat(cancelled.scrapQuantity()).as("printed 10: 3 failed, 7 sold to nobody").isEqualTo(10);
        assertThat(heldAt(line, PRINT_SHOP)).as("no hold left in the print shop").isZero();
        assertThat(inventory.productionHolds(line.productionOrderId())).as("the reprint blanks went back").isEmpty();
        List<Map<String, Object>> scrap = writeOffs(line.productionOrderId());
        assertThat(scrap).allSatisfy(row -> assertThat(row).containsEntry("status", "PENDING_APPROVAL")
                .containsEntry("reason_code", "SCRAP"));
        assertThat(scrap.stream().filter(row -> "OTHER".equals(row.get("scrap_reason")))
                .mapToInt(row -> (Integer) row.get("quantity_delta")).sum()).isEqualTo(-7);
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT a.requested_by FROM production.production_scrap s
                  JOIN inventory.stock_adjustment a ON a.id = s.stock_adjustment_id
                 WHERE s.production_order_id = ? AND s.reason = 'OTHER'""", UUID.class, line.productionOrderId()))
                .containsExactly(manager.userId());
        assertThat(inventory.availableToPromise(BLANK)).as("the printed cups are not sellable blanks")
                .isEqualTo(atp + 3);
    }

    @Test
    @DisplayName("audit P1-4: an order cancelled by Sales after a QC round cancels the job and asks the write-off in Sales' name; the system cancels no order that started printing, and one it cancels before printing leaves the job with issued blanks ON_HOLD for the manager")
    void anOrderCancelledAfterQcHandsTheGoodUnitsToAWriteOff() throws Exception {
        Line bySales = released(4);
        issueAll(bySales.productionOrderId());
        runs.startPrinting(printer, bySales.productionOrderId());
        runs.recordOutput(printer, bySales.productionOrderId(), 4, List.of());
        runs.recordQc(inspector, bySales.productionOrderId(), 3, 1, "Smudge", null);
        cancelOrder(bySales.orderId(), "Customer withdrew");
        Await.until("the job is cancelled", ASYNC, () -> detail(bySales.productionOrderId()).summary().status()
                == ProductionOrder.Status.CANCELLED);
        assertThat(heldAt(bySales, PRINT_SHOP)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM production.production_scrap s JOIN inventory.stock_adjustment a
                    ON a.id = s.stock_adjustment_id
                 WHERE s.production_order_id = ? AND s.reason = 'OTHER' AND a.requested_by = ? AND a.quantity_delta < 0""",
                Integer.class, bySales.productionOrderId(), sales.userId())).isPositive();

        // Decision 2026-10-17: once printing started the system cancels nothing on its own - staff decide.
        Line printedBySystem = released(4);
        issueAll(printedBySystem.productionOrderId());
        runs.startPrinting(printer, printedBySystem.productionOrderId());
        assertThatThrownBy(() -> WithCurrentUser.run(TestUsers.admin(), DataScope.ALL,
                () -> salesOrders.cancel(printedBySystem.orderId(), "Payment never came")))
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.CANCELLATION_NEEDS_STAFF);

        // Before printing it may; the job's issued blanks wait for a manager to cancel it under their own name.
        Line bySystem = released(4);
        issueAll(bySystem.productionOrderId());
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> salesOrders.cancel(bySystem.orderId(),
                "Payment never came"));
        Await.until("the job waits for the manager", ASYNC, () -> detail(bySystem.productionOrderId()).summary()
                .status() == ProductionOrder.Status.ON_HOLD);
        ProductionOrders.Detail byManager = runs.cancel(manager, bySystem.productionOrderId(), "Order cancelled");
        assertThat(byManager.summary().status()).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(heldAt(bySystem, PRINT_SHOP)).isZero();
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
                .singleElement().satisfies(e -> {
                    assertThat(e.reasonCode()).isEqualTo("BLANKS_SHORT");
                    assertThat(e.blankSku()).isEqualTo(BLANK.code());
                    assertThat(e.blanksNeeded()).as("purchasing reads how many").isPositive();
                });

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
        assertThat(jdbc.queryForObject("""
                SELECT SUM(quantity_delta) FROM inventory.stock_adjustment WHERE reason_code = 'SAMPLE'
                   AND status = 'PENDING_APPROVAL' AND note LIKE ?""", Integer.class, done.summary().orderNumber() + "%"))
                .as("one write-off per layer the 2 came from").isEqualTo(-2);
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
        assertThat(consumedAt(line, PACKING)).as("deducted once").isEqualTo(3);
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

    private void cancelWithFee(UUID orderId, BigDecimal percent, CancellationFee fee) {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> salesOrders.cancel(orderId, new CancelOrderCommand(
                CancellationReasonCode.CUSTOMER_REQUEST, "Customer cancelled while printing", percent, sales.userId(),
                fee)));
    }

    @Test
    @DisplayName("Q2: Sales keeps the amount agreed with the customer (reason and detail required, at most what was paid); the rest is asked back, nothing is charged, the payment is untouched, the job keeps only quantities")
    void agreedFeeOnAJobOnThePress() throws Exception {
        Line line = released(10);
        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        UUID payment = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.payment (id, order_id, amount, currency, method, status, captured_at, created_at)
                VALUES (?, ?, 20000, 'VND', 'BANK_TRANSFER', 'CAPTURED', NOW(), NOW())""", payment, line.orderId()));
        CancellationFee agreed = new CancellationFee(new BigDecimal("12000"), "Printing had started",
                "Agreed by phone with the buyer on 2026-10-12: 6 cups printed and the setup");

        for (Object[] wrong : List.<Object[]>of(
                new Object[] {null, new CancellationFee(new BigDecimal("1500.5"), "r", "d"), "not whole VND"},
                new Object[] {null, new CancellationFee(BigDecimal.ZERO, "r", "d"), "zero"},
                new Object[] {null, new CancellationFee(new BigDecimal("12000"), "r", " "), "no detail"},
                new Object[] {null, new CancellationFee(new BigDecimal("12000"), null, "d"), "no reason"},
                new Object[] {new BigDecimal("10"), agreed, "a percentage and an amount"})) {
            assertThatThrownBy(() -> cancelWithFee(line.orderId(), (BigDecimal) wrong[0], (CancellationFee) wrong[1]))
                    .as((String) wrong[2]).extracting(PrintRunIntegrationTest::code)
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().status().name()).isEqualTo("IN_PRODUCTION");

        List<Object> outcomes = race(List.of(() -> {
            cancelWithFee(line.orderId(), null, agreed);
            return "cancelled";
        }, () -> {
            cancelWithFee(line.orderId(), null, agreed);
            return "cancelled";
        }));
        assertThat(outcomes).filteredOn("cancelled"::equals).hasSize(1);
        // The loser fails as the order module fails a concurrent cancel today (refused for the state, or for the
        // version it read); either way it changes nothing — one cancellation, one refund below.
        assertThat(outcomes).filteredOn(Exception.class::isInstance).hasSize(1);

        var cancellation = salesOrders.findById(line.orderId()).orElseThrow().cancellation();
        assertThat(cancellation.retainedAmount()).isEqualByComparingTo("12000");
        assertThat(cancellation.feeReason()).isEqualTo("Printing had started");
        assertThat(cancellation.feeDetail()).startsWith("Agreed by phone");
        Await.until("the rest is asked back", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM payment.refund WHERE order_id = ? AND status = 'PENDING' AND amount = 8000""",
                Integer.class, line.orderId()) == 1);
        Thread.sleep(300);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.refund WHERE order_id = ?", Integer.class,
                line.orderId())).as("asked once").isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status, amount FROM payment.payment WHERE id = ?", payment))
                .as("the payment received is history, not changed").containsEntry("status", "CAPTURED")
                .satisfies(row -> assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("20000"));

        Await.until("the job waits for its stop", ASYNC,
                () -> detail(line.productionOrderId()).summary().status() == ProductionOrder.Status.ON_HOLD);
        ProductionOrders.Detail stopped = runs.stop(manager, line.productionOrderId(), 6, List.of(), "Customer cancelled");
        assertThat(stopped.printedQuantity()).isEqualTo(6);
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().cancellation().retainedAmount())
                .as("the job's count does not change the agreed amount").isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("SCRUM-415: the dashboard counts open orders by status, the overdue ones, the scrap rate of a period and the samples waiting for the customer")
    void dashboard() throws Exception {
        ProductionDashboard.Summary before = dashboard.summary(manager, null, null);
        Line line = released(4);
        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        runs.recordOutput(printer, line.productionOrderId(), 4,
                List.of(new PrintRuns.ScrapLine(ProductionRecords.ScrapReason.PRINT_DEFECT, 1, null)));
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ProductionFixtures.BLANK, 1, null)));
        samples.submit(sales, draft.id(), hcm);
        fixtures.workshopFinished(draft.id());
        samples.markSent(sales, draft.id());

        ProductionDashboard.Summary after = dashboard.summary(manager, null, null);
        assertThat(after.openByStatus().getOrDefault("QC", 0L)).isEqualTo(before.openByStatus().getOrDefault("QC", 0L) + 1);
        assertThat(after.printed()).isEqualTo(before.printed() + 4);
        assertThat(after.scrap()).isEqualTo(before.scrap() + 1);
        assertThat(after.scrapRate()).isEqualByComparingTo(java.math.BigDecimal.valueOf(after.scrap())
                .divide(java.math.BigDecimal.valueOf(after.printed()), 4, java.math.RoundingMode.HALF_UP));
        assertThat(after.samplesAwaitingApproval()).isEqualTo(before.samplesAwaitingApproval() + 1);
        assertThat(dashboard.samplesAwaitingApproval(manager, 0, 200).items())
                .extracting(SampleRequests.Row::id).contains(draft.id());

        // An order due yesterday is overdue; its count and its row agree.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE production.production_order SET due_date = ? WHERE id = ?",
                after.today().minusDays(1), line.productionOrderId()));
        assertThat(dashboard.summary(manager, null, null).overdue()).isEqualTo(after.overdue() + 1);
        assertThat(dashboard.overdue(manager, 0, 200).items()).extracting(ProductionOrders.Row::id)
                .contains(line.productionOrderId());

        LocalDate tomorrow = after.today().plusDays(1);
        ProductionDashboard.Summary empty = dashboard.summary(manager, tomorrow, tomorrow);
        assertThat(empty.printed()).as("nothing printed tomorrow").isZero();
        assertThat(empty.periodBasis()).as("QA3: the period selects what was printed and scrapped in it")
                .isEqualTo(ProductionDashboard.ACTIVITY_IN_PERIOD);

        // QA3: an order created last week and printed today counts today, not in last week's cohort.
        ProductionDashboard.Summary activityBefore = dashboard.summary(manager, after.today(), after.today());
        ProductionDashboard.Summary cohortBefore = dashboard.summary(manager, after.today(), after.today(),
                ProductionDashboard.Basis.CREATED);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE production.production_order SET created_at = NOW() - INTERVAL '7 days' "
                + "WHERE id = ?", line.productionOrderId()));
        LocalDate today = after.today();
        ProductionDashboard.Summary activity = dashboard.summary(manager, today, today);
        ProductionDashboard.Summary cohort = dashboard.summary(manager, today, today, ProductionDashboard.Basis.CREATED);
        assertThat(activity.printed()).as("printed today").isGreaterThanOrEqualTo(4);
        assertThat(activity.scrap()).isGreaterThanOrEqualTo(1);
        assertThat(cohort.periodBasis()).isEqualTo(ProductionDashboard.CREATED_IN_PERIOD);
        assertThat(activity.printed()).as("the creation day does not matter").isEqualTo(activityBefore.printed());
        assertThat(cohort.printed()).as("no longer in today's cohort").isEqualTo(cohortBefore.printed() - 4);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(printed_quantity), 0) FROM production.production_print_run "
                + "WHERE production_order_id = ?", Integer.class, line.productionOrderId())).as("one run row").isEqualTo(4);
        assertThat(empty.scrapRate()).isNull();
        assertThatThrownBy(() -> dashboard.summary(manager, tomorrow, after.today()))
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("QA3 no double counting: a unit spoilt on the press, one failed in QC, one written off when its job was stopped and the stopped run itself are each counted once in the period, the same as the orders' own totals; a subcontractor's receipt counts as printed; nothing of it falls in another period")
    void dashboardActivityCountsEachUnitOnce() throws Exception {
        LocalDate today = dashboard.summary(manager, null, null).today();
        ProductionDashboard.Summary before = dashboard.summary(manager, today, today);
        ProductionDashboard.Summary yesterdayBefore = dashboard.summary(manager, today.minusDays(1), today.minusDays(1));

        // Order A: printed 4 (1 spoilt on the press), QC passes 2 and fails 1.
        Line a = released(4);
        issueAll(a.productionOrderId());
        runs.startPrinting(printer, a.productionOrderId());
        runs.recordOutput(printer, a.productionOrderId(), 4,
                List.of(new PrintRuns.ScrapLine(ProductionRecords.ScrapReason.PRINT_DEFECT, 1, null)));
        runs.recordQc(inspector, a.productionOrderId(), 2, 1, "smudge", PACKING);
        // Order B: a run of 3 (1 spoilt) recorded by the stop itself; its 2 good units are written off with it.
        Line b = released(3);
        issueAll(b.productionOrderId());
        runs.startPrinting(printer, b.productionOrderId());
        runs.stop(manager, b.productionOrderId(), 3,
                List.of(new PrintRuns.ScrapLine(ProductionRecords.ScrapReason.PRINT_DEFECT, 1, null)), "customer withdrew");
        // Order C: 2 printed by a subcontractor, received today.
        Line c = released(4);
        UUID childId = subcontracts.split(manager, c.productionOrderId(),
                split(2, ProductionOrder.SubcontractMethod.FULL_SERVICE, printSubcontractor())).summary().id();
        UUID poId = purchaseOrderOf(childId);
        approvePurchaseOrder(poId);
        subcontracts.sendOut(storekeeper, childId);
        sendPurchaseOrder(poId);
        receiveSubcontracted(poId, 2);

        ProductionDashboard.Summary after = dashboard.summary(manager, today, today);
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT SUM(printed_quantity) AS printed, SUM(scrap_quantity) AS scrap FROM production.production_order
                 WHERE id IN (?, ?, ?)""", a.productionOrderId(), b.productionOrderId(), childId);
        assertThat(((Number) totals.get("printed")).longValue()).as("4 + 3 + 2 received").isEqualTo(9);
        assertThat(((Number) totals.get("scrap")).longValue()).as("1 press + 1 QC + 1 press + 2 stopped").isEqualTo(5);
        assertThat(after.printed() - before.printed()).as("printed in the period = the orders' own totals")
                .isEqualTo(9);
        assertThat(after.scrap() - before.scrap()).as("each spoilt unit once").isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM production.production_print_run WHERE production_order_id IN (?, ?)""",
                Integer.class, a.productionOrderId(), b.productionOrderId())).as("one row per recorded run").isEqualTo(2);

        ProductionDashboard.Summary yesterday = dashboard.summary(manager, today.minusDays(1), today.minusDays(1));
        assertThat(yesterday.printed()).as("today's activity is not yesterday's").isEqualTo(yesterdayBefore.printed());
        assertThat(yesterday.scrap()).isEqualTo(yesterdayBefore.scrap());
        assertThat(dashboard.summary(manager, null, null).printed() - dashboard.summary(manager, today, today).printed())
                .as("open period = before today + today").isEqualTo(
                        dashboard.summary(manager, null, today.minusDays(1)).printed());
    }

    /** The demo print subcontractor (V20261010000510). */
    private UUID printSubcontractor() {
        return jdbc.queryForObject("SELECT id FROM procurement.suppliers WHERE code = 'INNHANH'", UUID.class);
    }

    private Subcontracting.Split split(int quantity, ProductionOrder.SubcontractMethod method, UUID supplier) {
        return new Subcontracting.Split(quantity, supplier, method, new BigDecimal("1500"), "VND", null);
    }

    @Test
    @DisplayName("SCRUM-441 FULL_SERVICE: the split child gets its DRAFT SUBCONTRACT PO, the order lets 4 blanks go, goes out only once the PO is approved; two partial receipts held for the child on receipt (Q8), counted once, QC'd, and handed to the order in packing")
    void subcontractFullService() throws Exception {
        Line line = released(10);
        int atp = inventory.availableToPromise(BLANK);
        assertThatThrownBy(() -> subcontracts.split(manager, line.productionOrderId(),
                split(4, ProductionOrder.SubcontractMethod.FULL_SERVICE, sales.userId())))
                .as("not a supplier at all").extracting(PrintRunIntegrationTest::code)
                .isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
        assertThat(detail(line.productionOrderId()).summary().plannedQuantity()).as("nothing split").isEqualTo(10);

        ProductionOrders.Detail child = subcontracts.split(manager, line.productionOrderId(),
                split(4, ProductionOrder.SubcontractMethod.FULL_SERVICE, printSubcontractor()));

        assertThat(child.summary().status()).as("inherits the prepress result").isEqualTo(ProductionOrder.Status.READY);
        assertThat(child.summary().mode()).isEqualTo("SUBCONTRACTED");
        assertThat(child.summary().parentId()).isEqualTo(line.productionOrderId());
        assertThat(child.summary().plannedQuantity()).isEqualTo(4);
        ProductionOrders.Detail parent = detail(line.productionOrderId());
        assertThat(parent.summary().plannedQuantity()).isEqualTo(6);
        assertThat(parent.summary().status()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(parent.history()).extracting(ProductionOrders.StatusChange::reason)
                .anySatisfy(reason -> assertThat(reason).startsWith("Split 4 to " + child.summary().orderNumber()));
        Map<String, Object> po = jdbc.queryForMap("""
                SELECT id, po_type, status, production_order_id FROM procurement.purchase_orders
                 WHERE production_order_id = ?""", child.summary().id());
        assertThat(po).containsEntry("po_type", "SUBCONTRACT").containsEntry("status", "DRAFT");
        assertThat(inventory.availableToPromise(BLANK)).as("the subcontractor brings its own blanks").isEqualTo(atp + 4);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory.stock_reservation
                 WHERE status = 'HELD' AND order_id = ?""", Integer.class, line.orderId()))
                .as("the order still holds what its parent prints").isEqualTo(6);

        assertThatThrownBy(() -> subcontracts.sendOut(storekeeper, child.summary().id())).as("BR-PRD-13: PO not approved")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        UUID poId = (UUID) po.get("id");
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> {
            procurement.submit(poId, manager.userId());
            procurement.approve(poId, approver.userId());
        });
        assertThat(subcontracts.sendOut(storekeeper, child.summary().id()).summary().status())
                .isEqualTo(ProductionOrder.Status.SUBCONTRACTED);
        sendPurchaseOrder(poId);

        // Q8: counted in by the warehouse, held for the child at once and handed to the print shop for QC.
        UUID first = receiveSubcontracted(poId, 3);
        ProductionOrders.Detail received = detail(child.summary().id());
        assertThat(received.summary().status()).isEqualTo(ProductionOrder.Status.QC);
        assertThat(received.printedQuantity()).isEqualTo(3);
        assertThat(heldForProduction(child.summary().id(), "IN_PRODUCTION")).as("held for QC").isEqualTo(3);
        assertThat(heldAtFor(child.summary().id(), QC_AREA)).as("waiting in the QC area, not the print shop").isEqualTo(3);
        assertThat(freeAtReceipt(first)).as("never free at receiving, where a putaway would sell it").isZero();
        assertThat(inventory.availableToPromise(BLANK)).as("received goods are not for sale").isEqualTo(atp + 4);
        var again = new com.stockflow.contracts.GoodsReceiptConfirmed(first, "GR-again", poId, hcm, List.of(
                new com.stockflow.contracts.GoodsReceiptConfirmed.Line(Identifiers.newId(), Identifiers.newId(),
                        BLANK.code(), null, 3, false, "HCM-RCV01")), java.time.Instant.now());
        tx.executeWithoutResult(s -> publisher.publishEvent(again));
        assertThat(detail(child.summary().id()).printedQuantity()).as("counted once").isEqualTo(3);
        assertThat(heldForProduction(child.summary().id(), "IN_PRODUCTION")).as("held once").isEqualTo(3);

        assertThat(runs.recordQc(inspector, child.summary().id(), 3, 0, null, PACKING).summary().status())
                .as("1 still to come from the subcontractor").isEqualTo(ProductionOrder.Status.READY);
        receiveSubcontracted(poId, 1);
        ProductionOrders.Detail done = runs.recordQc(inspector, child.summary().id(), 1, 0, null, PACKING);

        assertThat(done.summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(heldForProduction(child.summary().id(), null)).as("nothing left the child's own").isZero();
        List<UUID> packed = jdbc.queryForList("""
                SELECT r.id FROM inventory.stock_reservation r JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.status = 'CONSUMED' AND s.location_code = ? AND s.status = 'AVAILABLE' AND r.order_id = ?
                   AND r.production_order_id IS NULL""", UUID.class, PACKING, line.orderId());
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM inventory.stock_reservation WHERE id = ANY(?)",
                Integer.class, (Object) packed.toArray(UUID[]::new))).as("the 4 subcontracted cups are the order's").isEqualTo(4);
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().lines().getFirst().reservationIds())
                .as("and on its line for fulfilment").containsAll(packed);
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp + 4);
        assertThat(recorded.matching(ProductionCompleted.class, e -> e.productionOrderId().equals(child.summary().id())))
                .singleElement().isEqualTo(new ProductionCompleted(line.orderId(), parent.summary().salesOrderLineId(),
                        child.summary().id(), 4));
        assertThat(subcontracts.settlement(manager, child.summary().id())).satisfies(settled -> {
            assertThat(settled.status()).isEqualTo("FINAL");
            assertThat(settled.passedQuantity()).isEqualTo(4);
            assertThat(settled.payableAmount()).isEqualByComparingTo("6000");
            assertThat(settled.blanksSent()).as("FULL_SERVICE: the subcontractor's blanks").isZero();
        });

        // A redelivered release of the line still agrees with the parent: 6 + 4 split off = 10.
        assertThatCode(() -> orderProduction.createFor(new OrderLinesReleasedForProduction(line.orderId(), "SO-IT",
                hcm, List.of(new OrderLinesReleasedForProduction.Line(parent.summary().salesOrderLineId(),
                        ProductionFixtures.BLANK, 10, parent.summary().designSnapshotId(), parent.designChecksum(),
                        parent.approvedSampleId(), null))))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SCRUM-441 SUPPLIED_BLANKS: blanks held for the child go to its subcontractor's location (Q7); each receipt consumes the blanks it used and is reconciled by the warehouse before QC (Q9); partial receipts leave the rest there; a failed cup is scrapped, not a blank; the shortfall is sent again")
    void subcontractSuppliedBlanks() throws Exception {
        Line line = released(8);
        int atp = inventory.availableToPromise(BLANK);
        ProductionOrders.Detail child = subcontracts.split(manager, line.productionOrderId(),
                split(3, ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS, printSubcontractor()));

        assertThat(inventory.availableToPromise(BLANK)).as("moved, not freed").isEqualTo(atp);
        assertThat(inventory.productionHolds(child.summary().id()).stream().mapToInt(h -> h.quantity()).sum())
                .as("3 held for the child, from however many layers").isEqualTo(3);
        assertThatThrownBy(() -> issues.issue(storekeeper, child.summary().id(),
                new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP, null))).as("not printed here")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        UUID poId = jdbc.queryForObject("SELECT id FROM procurement.purchase_orders WHERE production_order_id = ?",
                UUID.class, child.summary().id());
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> {
            procurement.submit(poId, manager.userId());
            procurement.approve(poId, approver.userId());
        });
        UUID childId = child.summary().id();
        assertThat(subcontracts.sendOut(storekeeper, childId).summary().status())
                .isEqualTo(ProductionOrder.Status.SUBCONTRACTED);
        assertThatThrownBy(() -> subcontracts.sendOut(storekeeper, childId)).as("sent once")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        // Q7: one location per subcontractor and sending warehouse, outside available-to-promise.
        Map<String, Object> site = jdbc.queryForMap("""
                SELECT l.location_code, l.kind, l.is_pickable, l.is_putaway_target FROM warehouse.subcontractor_site s
                  JOIN warehouse.storage_location l ON l.id = s.location_id
                 WHERE s.warehouse_id = ? AND s.supplier_id = ?""", hcm, printSubcontractor());
        assertThat(site).containsEntry("location_code", SUBCONTRACTOR).containsEntry("kind", "SUBCONTRACTOR")
                .containsEntry("is_pickable", false).containsEntry("is_putaway_target", false);
        assertThat(atSubcontractor(childId)).as("the 3 blanks, still ours, still the child's").isEqualTo(3);
        assertThat(inventory.availableToPromise(BLANK)).as("not for sale").isEqualTo(atp);

        sendPurchaseOrder(poId);
        // Q9 at small scale: of 3 blanks, 1 comes back printed now; 1 was spoilt; 1 is still there.
        UUID first = receiveSubcontracted(poId, 1);
        assertThat(atSubcontractor(childId)).as("the blank the cup was printed on left with the receipt").isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory.stock_movement WHERE movement_type = 'CONSUMPTION'
                   AND reference_type = 'PRODUCTION_ORDER' AND reference_id = ?""", Integer.class, childId)).isEqualTo(1);
        assertThat(heldForProduction(childId, "IN_PRODUCTION")).as("1 cup + 2 blanks, all the child's").isEqualTo(3);
        assertThatThrownBy(() -> runs.recordQc(inspector, childId, 1, 0, null, PACKING))
                .as("BR-PRD-16: reconciled first").extracting(PrintRunIntegrationTest::code)
                .isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        assertThatThrownBy(() -> subcontracts.reconcile(storekeeper, childId, first, 2, 1, null))
                .as("more than the subcontractor holds").extracting(PrintRunIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        subcontracts.reconcile(storekeeper, childId, first, 1, 0, null);
        assertThatCode(() -> subcontracts.reconcile(storekeeper, childId, first, 1, 0, null)).as("a retry")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> subcontracts.reconcile(storekeeper, childId, first, 0, 1, null))
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.CONFLICT);
        assertThat(atSubcontractor(childId)).as("not received yet is not lost").isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT reason_code, quantity_delta, status FROM inventory.stock_adjustment
                 WHERE location_code = ? AND reason_code = 'SUBCONTRACT_LOSS' AND note LIKE ?""", SUBCONTRACTOR,
                // The subcontractor's site is shared by every test's orders; the note names the order.
                child.summary().orderNumber() + " %"))
                .as("the spoilt blank waits for the adjustment approval").singleElement()
                .satisfies(row -> assertThat(row).containsEntry("quantity_delta", -1).containsEntry("status", "PENDING_APPROVAL"));

        // QC fails the cup: it is scrapped as a cup, never a blank again.
        ProductionOrders.Detail afterQc = runs.recordQc(inspector, childId, 0, 1, "smudged", PACKING);
        assertThat(afterQc.summary().status()).as("short: G9 back to READY").isEqualTo(ProductionOrder.Status.READY);
        assertThat(writeOffs(childId)).extracting(row -> row.get("reason_code")).containsExactly("SCRAP");
        assertThat(inventory.availableToPromise(BLANK)).as("no cup became a blank").isEqualTo(atp);

        // The rest arrives after the QC round: the last blank comes back unused, as surplus.
        UUID second = receiveSubcontracted(poId, 1);
        assertThat(atSubcontractor(childId)).isZero();
        assertThatThrownBy(() -> subcontracts.reconcile(storekeeper, childId, second, 0, 1, null))
                .as("nothing left to return").extracting(PrintRunIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        subcontracts.reconcile(storekeeper, childId, second, 0, 0, null);
        assertThat(subcontracts.receipts(manager, childId)).extracting(ProductionRecords.SubcontractReceipt::quantity,
                        ProductionRecords.SubcontractReceipt::usedBlanks, ProductionRecords.SubcontractReceipt::spoiltBlanks)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 1, 1), org.assertj.core.groups.Tuple.tuple(1, 1, 0));
        assertThat(runs.recordQc(inspector, childId, 1, 0, null, PACKING).summary().status())
                .as("1 good of 3").isEqualTo(ProductionOrder.Status.READY);

        // G9 "gửi bù": the PO of 3 had 2 received; 2 are owed, so it is supplemented first - approved, it takes an
        // amendment approved again by someone else (BR-02) - then the 2 missing blanks are held and sent.
        assertThatThrownBy(() -> subcontracts.sendOut(storekeeper, childId)).as("the PO allows 1 more, 2 are owed")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        subcontracts.supplement(manager, childId, 1, "resend after the smudged cup");
        assertThatThrownBy(() -> subcontracts.sendOut(storekeeper, childId)).as("the amendment waits for approval")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> amendments.approveAmendment(poId, approver.userId()));
        subcontracts.sendOut(storekeeper, childId);
        assertThat(atSubcontractor(childId)).isEqualTo(2);
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp - 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse.subcontractor_site WHERE supplier_id = ?",
                Integer.class, printSubcontractor())).isEqualTo(1);

        issueAll(line.productionOrderId());
        assertThat(detail(line.productionOrderId()).issuedQuantity()).as("the parent issues its 5").isEqualTo(5);
        assertThatThrownBy(() -> subcontracts.split(manager, line.productionOrderId(),
                split(1, ProductionOrder.SubcontractMethod.FULL_SERVICE, printSubcontractor())))
                .as("MATERIAL_ISSUED is not split").extracting(PrintRunIntegrationTest::code)
                .isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
    }

    @Test
    @DisplayName("Q9 example: 20 blanks sent, 15 good back + 3 spoilt + 2 surplus returned free; nothing left at the subcontractor, nothing counted twice")
    void subcontractReconciliationExample() throws Exception {
        Line line = released(30);
        ProductionOrders.Detail child = subcontracts.split(manager, line.productionOrderId(),
                split(20, ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS, printSubcontractor()));
        UUID childId = child.summary().id();
        UUID poId = jdbc.queryForObject("SELECT id FROM procurement.purchase_orders WHERE production_order_id = ?",
                UUID.class, childId);
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> {
            procurement.submit(poId, manager.userId());
            procurement.approve(poId, approver.userId());
        });
        int atSiteBefore = onHandAt(SUBCONTRACTOR);   // earlier tests' blanks may still be there
        subcontracts.sendOut(storekeeper, childId);
        sendPurchaseOrder(poId);
        int atp = inventory.availableToPromise(BLANK);
        int onHand = jdbc.queryForObject("SELECT COALESCE(SUM(on_hand), 0) FROM inventory.stock_item WHERE sku = ?",
                Integer.class, BLANK.code());

        UUID receipt = receiveSubcontracted(poId, 15);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(on_hand), 0) FROM inventory.stock_item WHERE sku = ?",
                Integer.class, BLANK.code())).as("15 cups in, the 15 blanks they used out: not counted twice")
                .isEqualTo(onHand);
        subcontracts.reconcile(storekeeper, childId, receipt, 3, 2, null);

        assertThat(atSubcontractor(childId)).isZero();
        assertThat(onHandAt(SUBCONTRACTOR) - atSiteBefore).as("only the 3 spoilt, waiting for the write-off approval")
                .isEqualTo(3);
        assertThat(inventory.availableToPromise(BLANK)).as("the 2 surplus are free again").isEqualTo(atp + 2);
        assertThat(heldForProduction(childId, "IN_PRODUCTION")).as("the 15 cups, the child's, for QC").isEqualTo(15);
        Await.until("audited", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'production-subcontract-receipt'
                   AND resource_id = ? AND outcome = 'SUCCESS'""", Integer.class, receipt.toString()) == 1);
    }

    private static final String SUBCONTRACTOR = "HCM-SUB-INNHANH";

    @Autowired com.stockflow.procurement.internal.service.GoodsReceipts receipts;

    /** The approved SUBCONTRACT PO sent to the subcontractor, so goods can be received against it. */
    private void sendPurchaseOrder(UUID poId) {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> procurement.confirm(poId, manager.userId(),
                new com.stockflow.procurement.api.SendPurchaseOrderCommand(java.time.LocalDate.now().plusDays(7), null)));
    }

    /** The subcontractor's goods counted in by the warehouse staff at HCM receiving, as Q9 has it. */
    private UUID receiveSubcontracted(UUID poId, int quantity) {
        UUID poLine = jdbc.queryForObject("SELECT id FROM procurement.purchase_order_lines WHERE po_id = ?", UUID.class,
                poId);
        return WithCurrentUser.call(storekeeper, DataScope.WAREHOUSE, () -> {
            var draft = receipts.create(new com.stockflow.procurement.internal.service.GoodsReceipts.Create(poId, null,
                    null), storekeeper.userId());
            receipts.replaceLines(draft.id(), List.of(new com.stockflow.procurement.internal.service.GoodsReceipts.NewLine(
                    poLine, quantity, null, null, "HCM-RCV01", null)));
            return receipts.confirm(draft.id(), storekeeper.userId()).id();
        });
    }

    /** Units of the receipt still at receiving and held by nobody. */
    private int freeAtReceipt(UUID receiptId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(s.on_hand - COALESCE((SELECT SUM(r.quantity) FROM inventory.stock_reservation r
                                                          WHERE r.stock_item_id = s.id AND r.status = 'HELD'), 0)), 0)
                  FROM inventory.stock_item s JOIN procurement.goods_receipts g ON g.confirmed_at = s.received_at
                 WHERE g.id = ? AND s.sku = ? AND s.location_code = 'HCM-RCV01'""", Integer.class, receiptId, BLANK.code());
    }

    /** Units held, live, by the production order itself, optionally only on stock of {@code status}. */
    private int heldForProduction(UUID productionOrderId, String status) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.quantity), 0) FROM inventory.stock_reservation r
                  JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.status = 'HELD' AND r.production_order_id = ? AND (CAST(? AS VARCHAR) IS NULL OR s.status = ?)""",
                Integer.class, productionOrderId, status, status);
    }

    /** Blanks held for the child at its subcontractor. */
    private int atSubcontractor(UUID childId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.quantity), 0) FROM inventory.stock_reservation r
                  JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.status = 'HELD' AND r.production_order_id = ? AND s.location_code = ?""", Integer.class,
                childId, SUBCONTRACTOR);
    }

    private static final String QC_AREA = "HCM-QCA01";

    @Autowired com.stockflow.procurement.internal.service.SubcontractAmendments amendments;

    /** Units held, live, by the production order itself at {@code location}. */
    private int heldAtFor(UUID productionOrderId, String location) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.quantity), 0) FROM inventory.stock_reservation r
                  JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.status = 'HELD' AND r.production_order_id = ? AND s.location_code = ?""", Integer.class,
                productionOrderId, location);
    }

    private UUID purchaseOrderOf(UUID productionOrderId) {
        return jdbc.queryForObject("SELECT id FROM procurement.purchase_orders WHERE production_order_id = ?",
                UUID.class, productionOrderId);
    }

    private String purchaseOrderStatus(UUID poId) {
        return jdbc.queryForObject("SELECT status FROM procurement.purchase_orders WHERE id = ?", String.class, poId);
    }

    /** Submitted by the manager, approved by someone else (four eyes). */
    private void approvePurchaseOrder(UUID poId) {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> {
            procurement.submit(poId, manager.userId());
            procurement.approve(poId, approver.userId());
        });
    }

    /** The line's release delivered again: it must still agree with the tree of production orders (BR-PRD-01). */
    private void releaseAgain(Line line, int quantity) {
        ProductionOrders.Detail root = detail(line.productionOrderId());
        assertThatCode(() -> orderProduction.createFor(new OrderLinesReleasedForProduction(line.orderId(), "SO-IT", hcm,
                List.of(new OrderLinesReleasedForProduction.Line(root.summary().salesOrderLineId(),
                        ProductionFixtures.BLANK, quantity, root.summary().designSnapshotId(), root.designChecksum(),
                        root.approvedSampleId(), null))))).as("replay agrees with %d", quantity).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("User decision 2026-10-13: a subcontractor's goods of a SKU that needs receiving QC get ONE inspection, the production QC - receiving counts them, no receiving QC, no putaway, receipt CLOSED; held in the QC area; failed units scrapped as cups; partial receipts; a late subcontractor's hold ends when the goods come")
    void subcontractReceiptGetsOneQc() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.inventory_items SET qc_required = TRUE WHERE sku = ?",
                BLANK.code()));
        try {
            Line line = released(6);
            UUID childId = subcontracts.split(manager, line.productionOrderId(),
                    split(2, ProductionOrder.SubcontractMethod.FULL_SERVICE, printSubcontractor())).summary().id();
            UUID poId = purchaseOrderOf(childId);
            approvePurchaseOrder(poId);
            subcontracts.sendOut(storekeeper, childId);
            sendPurchaseOrder(poId);
            int atp = inventory.availableToPromise(BLANK);
            workflow.hold(manager, childId, ProductionOrder.HoldReason.SUBCONTRACTOR_LATE, "2 days late");

            UUID receipt = receiveSubcontracted(poId, 1);
            assertThat(jdbc.queryForMap("SELECT status, closed_at FROM procurement.goods_receipts WHERE id = ?", receipt))
                    .as("nothing for receiving to inspect or put away").containsEntry("status", "CLOSED")
                    .satisfies(row -> assertThat(row.get("closed_at")).isNotNull());
            assertThat(jdbc.queryForObject("SELECT bool_or(qc_required) FROM procurement.goods_receipt_lines WHERE receipt_id = ?",
                    Boolean.class, receipt)).as("no receiving QC on the line").isFalse();
            assertThat(receipts.get(receipt).lines()).singleElement().satisfies(l -> {
                assertThat(l.qcProgress()).isEqualTo(com.stockflow.procurement.internal.service.GoodsReceipts.QcProgress.PRODUCTION_QC);
                assertThat(l.quantityForPutaway()).isZero();
            });
            assertThat(detail(childId).summary().status()).as("the late goods came: the hold is over")
                    .isEqualTo(ProductionOrder.Status.QC);
            assertThat(heldAtFor(childId, QC_AREA)).isEqualTo(1);
            assertThat(heldAtFor(childId, PRINT_SHOP)).as("not routed through the print shop").isZero();
            assertThat(freeAtReceipt(receipt)).isZero();
            assertThat(inventory.availableToPromise(BLANK)).as("not sellable").isEqualTo(atp);

            assertThat(runs.recordQc(inspector, childId, 0, 1, "colour off the approved sample", PACKING).summary()
                    .status()).isEqualTo(ProductionOrder.Status.READY);
            assertThat(writeOffs(childId)).extracting(row -> row.get("reason_code")).containsExactly("SCRAP");
            assertThat(inventory.availableToPromise(BLANK)).as("the failed cup is no blank").isEqualTo(atp);

            receiveSubcontracted(poId, 1);
            assertThat(runs.recordQc(inspector, childId, 1, 0, null, PACKING).summary().status())
                    .as("1 good of 2").isEqualTo(ProductionOrder.Status.READY);
        } finally {
            tx.executeWithoutResult(s -> jdbc.update(
                    "UPDATE inventory.inventory_items SET qc_required = FALSE WHERE sku = ?", BLANK.code()));
        }
    }

    @Test
    @DisplayName("BR-PRD-12: sending the whole order converts it - same row and number, no child, its own PO, blanks held for it; a replay still agrees; an order with blanks issued cannot be converted")
    void wholeOrderConversion() throws Exception {
        Line line = released(5);
        int atp = inventory.availableToPromise(BLANK);
        String number = detail(line.productionOrderId()).summary().orderNumber();
        ProductionOrders.Detail converted = subcontracts.split(manager, line.productionOrderId(),
                split(5, ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS, printSubcontractor()));

        assertThat(converted.summary().id()).isEqualTo(line.productionOrderId());
        assertThat(converted.summary().orderNumber()).isEqualTo(number);
        assertThat(converted.summary().mode()).isEqualTo("SUBCONTRACTED");
        assertThat(converted.summary().parentId()).isNull();
        assertThat(converted.origin()).isNull();
        assertThat(converted.summary().plannedQuantity()).isEqualTo(5);
        assertThat(converted.subcontract().method()).isEqualTo("SUPPLIED_BLANKS");
        assertThat(converted.subcontract().purchaseOrderId()).isEqualTo(purchaseOrderOf(line.productionOrderId()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.production_order WHERE parent_id = ?",
                Integer.class, line.productionOrderId())).as("no child").isZero();
        assertThat(inventory.productionHolds(line.productionOrderId()).stream().mapToInt(h -> h.quantity()).sum())
                .as("its blanks held for it").isEqualTo(5);
        assertThat(inventory.availableToPromise(BLANK)).as("moved, not freed").isEqualTo(atp);
        assertThatThrownBy(() -> issues.issue(storekeeper, line.productionOrderId(),
                new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP, null))).as("printed outside now")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        releaseAgain(line, 5);

        Line started = released(5);
        issues.issue(storekeeper, started.productionOrderId(), new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP, null));
        int unissued = 5 - detail(started.productionOrderId()).issuedQuantity();
        // The scan issues one hold, and the holds span however many stock layers the shared stock has: either some
        // blanks are still unissued (READY, a split must take fewer) or none are (MATERIAL_ISSUED, nothing to split).
        assertThatThrownBy(() -> subcontracts.split(manager, started.productionOrderId(),
                split(5, ProductionOrder.SubcontractMethod.FULL_SERVICE, printSubcontractor())))
                .as("blanks in the print shop: only the %d unissued can go".formatted(unissued))
                .extracting(PrintRunIntegrationTest::code).isEqualTo(unissued > 0 ? ErrorCode.VALIDATION_FAILED
                        : ErrorCode.INVALID_PRODUCTION_TRANSITION);
        assertThat(detail(started.productionOrderId()).summary().mode()).as("not converted").isEqualTo("IN_HOUSE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.production_order WHERE parent_id = ?",
                Integer.class, started.productionOrderId())).as("no child either").isZero();
    }

    @Test
    @DisplayName("03 §16 G9 in-house: the gap goes to a new in-house order once the blanks are accounted for (recall, SUBCONTRACT_LOSS); the subcontracted order completes with its good units, its PO is closed short and settled; the loss above tolerance waits for the manager's deduction; the dispatch note lists what went out")
    void shortfallTakenInHouse() throws Exception {
        Line line = released(10);
        UUID childId = subcontracts.split(manager, line.productionOrderId(),
                split(4, ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS, printSubcontractor())).summary().id();
        UUID poId = purchaseOrderOf(childId);
        approvePurchaseOrder(poId);
        subcontracts.sendOut(storekeeper, childId);
        sendPurchaseOrder(poId);

        Subcontracting.DispatchNote note = subcontracts.dispatchNote(storekeeper, childId);
        assertThat(note.purchaseOrderId()).isEqualTo(poId);
        assertThat(note.subcontractorCode()).isEqualTo("INNHANH");
        assertThat(note.quantity()).isEqualTo(4);
        assertThat(note.blanks().stream().mapToInt(Subcontracting.SentBlanks::quantity).sum()).isEqualTo(4);
        assertThat(note.blanks()).allSatisfy(b -> assertThat(b.toLocation()).isEqualTo(SUBCONTRACTOR));
        assertThat(note.printFile()).as("the prepress-checked PRINT_READY file (BR-PRD-14)").isNotNull();
        assertThat(note.approvedSampleId()).isEqualTo(detail(line.productionOrderId()).approvedSampleId());

        UUID receipt = receiveSubcontracted(poId, 3);
        subcontracts.reconcile(storekeeper, childId, receipt, 0, 0, null);
        assertThat(runs.recordQc(inspector, childId, 3, 0, null, PACKING).summary().status())
                .isEqualTo(ProductionOrder.Status.READY);
        assertThatThrownBy(() -> subcontracts.takeInHouse(manager, childId, 1, "subcontractor out of ink", PACKING))
                .as("BR-PRD-16: the blank still there is accounted for first")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        UUID recallId = UUID.randomUUID();
        subcontracts.recall(storekeeper, childId, new Subcontracting.Recall(recallId, 0, 1, null, "lost in transit"));
        assertThatCode(() -> subcontracts.recall(storekeeper, childId,
                new Subcontracting.Recall(recallId, 0, 1, null, null))).as("a replay").doesNotThrowAnyException();
        assertThatThrownBy(() -> subcontracts.recall(storekeeper, childId,
                new Subcontracting.Recall(recallId, 1, 0, null, null))).as("same request, other numbers")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.CONFLICT);
        assertThat(atSubcontractor(childId)).isZero();

        ProductionOrders.Detail inHouse = subcontracts.takeInHouse(manager, childId, 1, "subcontractor out of ink",
                PACKING);
        assertThat(inHouse.summary().mode()).isEqualTo("IN_HOUSE");
        assertThat(inHouse.origin()).isEqualTo("SHORTFALL");
        assertThat(inHouse.summary().parentId()).isEqualTo(childId);
        assertThat(inHouse.summary().plannedQuantity()).isEqualTo(1);
        assertThat(inHouse.summary().status()).as("inherits the prepress result").isEqualTo(ProductionOrder.Status.READY);
        assertThat(inventory.productionHolds(inHouse.summary().id()).stream().mapToInt(h -> h.quantity()).sum())
                .as("its blank held now").isEqualTo(1);
        ProductionOrders.Detail done = detail(childId);
        assertThat(done.summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(done.summary().plannedQuantity()).isEqualTo(3);
        assertThat(recorded.matching(ProductionCompleted.class, e -> e.productionOrderId().equals(childId)))
                .singleElement().extracting(ProductionCompleted::goodQuantity).isEqualTo(3);
        assertThat(purchaseOrderStatus(poId)).as("3 of 4 received: closed short").isEqualTo("CLOSED");
        releaseAgain(line, 10);

        var settled = subcontracts.settlement(manager, childId);
        assertThat(settled.status()).as("1 lost, 3% of 4 tolerated = 0").isEqualTo("AWAITING_APPROVAL");
        assertThat(settled.blanksSent()).isEqualTo(4);
        assertThat(settled.blanksUsed()).isEqualTo(3);
        assertThat(settled.blanksLost()).isEqualTo(1);
        assertThat(settled.excessLoss()).isEqualTo(1);
        assertThat(settled.payableAmount()).isEqualByComparingTo("4500");
        var approved = subcontracts.approveLoss(manager, childId, new BigDecimal("1000"), "agreed with INNHANH");
        assertThat(approved.status()).isEqualTo("FINAL");
        assertThat(approved.netPayable()).isEqualByComparingTo("3500");

        // The in-house order prints the gap from its own hold, never from the line's checkout holds.
        issueAll(inHouse.summary().id());
        runs.startPrinting(printer, inHouse.summary().id());
        runs.recordOutput(printer, inHouse.summary().id(), 1, List.of());
        assertThat(runs.recordQc(inspector, inHouse.summary().id(), 1, 0, null, PACKING).summary().status())
                .isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(detail(line.productionOrderId()).issuedQuantity()).as("the root's blanks untouched").isZero();
    }

    @Test
    @DisplayName("part of a SUPPLIED_BLANKS child taken back before it goes out: only the blanks still owed are sent, the surplus is let go")
    void partTakenBackBeforeSending() throws Exception {
        Line line = released(8);
        UUID childId = subcontracts.split(manager, line.productionOrderId(),
                split(4, ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS, printSubcontractor())).summary().id();
        UUID poId = purchaseOrderOf(childId);
        approvePurchaseOrder(poId);
        int atp = inventory.availableToPromise(BLANK);
        UUID inHouse = subcontracts.takeInHouse(manager, childId, 1, "print shop has a free slot", null).summary().id();
        assertThat(detail(childId).summary().plannedQuantity()).isEqualTo(3);
        subcontracts.sendOut(storekeeper, childId);
        assertThat(atSubcontractor(childId)).as("3 owed, 3 sent").isEqualTo(3);
        assertThat(inventory.productionHolds(childId).stream().mapToInt(h -> h.quantity()).sum()).isEqualTo(3);
        assertThat(inventory.availableToPromise(BLANK)).as("1 let go by the child, 1 held by the in-house order")
                .isEqualTo(atp);
        assertThat(inventory.productionHolds(inHouse).stream().mapToInt(h -> h.quantity()).sum()).isEqualTo(1);
        releaseAgain(line, 8);
    }

    @Test
    @DisplayName("03 §16 SUBCONTRACTED -> CANCELLED while the order still wants the goods: blanks recalled first, the PO cancelled, the agreed fee settled, a REPLACEMENT in-house order holds the blanks; a fee needs its reason")
    void subcontractCancelledAndReplaced() throws Exception {
        Line line = released(6);
        UUID childId = subcontracts.split(manager, line.productionOrderId(),
                split(2, ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS, printSubcontractor())).summary().id();
        UUID poId = purchaseOrderOf(childId);
        approvePurchaseOrder(poId);
        subcontracts.sendOut(storekeeper, childId);
        sendPurchaseOrder(poId);
        int atp = inventory.availableToPromise(BLANK);

        assertThatThrownBy(() -> runs.cancel(manager, childId, "subcontractor closed", null, null))
                .as("2 blanks still at the subcontractor")
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        subcontracts.recall(storekeeper, childId, new Subcontracting.Recall(UUID.randomUUID(), 2, 0, null, null));
        assertThat(inventory.availableToPromise(BLANK)).as("recalled blanks are free again").isEqualTo(atp + 2);
        assertThatThrownBy(() -> runs.cancel(manager, childId, "subcontractor closed", new BigDecimal("50000"), " "))
                .as("a fee says why").extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);

        ProductionOrders.Detail cancelled = runs.cancel(manager, childId, "subcontractor closed",
                new BigDecimal("50000"), "setup already paid for");
        assertThat(cancelled.summary().status()).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(purchaseOrderStatus(poId)).isEqualTo("CANCELLED");
        UUID replacement = jdbc.queryForObject("""
                SELECT id FROM production.production_order WHERE parent_id = ? AND origin = 'REPLACEMENT'""",
                UUID.class, childId);
        ProductionOrders.Detail inHouse = detail(replacement);
        assertThat(inHouse.summary().mode()).isEqualTo("IN_HOUSE");
        assertThat(inHouse.summary().plannedQuantity()).isEqualTo(2);
        assertThat(inHouse.summary().status()).isEqualTo(ProductionOrder.Status.READY);
        assertThat(inventory.availableToPromise(BLANK)).as("held again for the replacement").isEqualTo(atp);
        var settled = subcontracts.settlement(manager, childId);
        assertThat(settled.status()).isEqualTo("FINAL");
        assertThat(settled.blanksReturned()).isEqualTo(2);
        assertThat(settled.cancellationFee()).isEqualByComparingTo("50000");
        assertThat(settled.netPayable()).isEqualByComparingTo("50000");
        releaseAgain(line, 6);
    }

    @Test
    @DisplayName("a whole order converted, sent, recalled and cancelled is replaced in house; the root is out of the tree's sum yet its line's release still replays")
    void wholeOrderCancelledAndReplaced() throws Exception {
        Line line = released(5);
        UUID rootId = line.productionOrderId();
        subcontracts.split(manager, rootId, split(5, ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS,
                printSubcontractor()));
        UUID poId = purchaseOrderOf(rootId);
        approvePurchaseOrder(poId);
        subcontracts.sendOut(storekeeper, rootId);
        sendPurchaseOrder(poId);
        subcontracts.recall(storekeeper, rootId, new Subcontracting.Recall(UUID.randomUUID(), 5, 0, null, null));

        assertThat(runs.cancel(manager, rootId, "subcontractor closed", null, null).summary().status())
                .isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(detail(rootId).summary().plannedQuantity()).as("a cancelled order keeps its plan").isEqualTo(5);
        ProductionOrders.Detail replacement = detail(jdbc.queryForObject("""
                SELECT id FROM production.production_order WHERE parent_id = ? AND origin = 'REPLACEMENT'""",
                UUID.class, rootId));
        assertThat(replacement.summary().mode()).isEqualTo("IN_HOUSE");
        assertThat(replacement.summary().plannedQuantity()).isEqualTo(5);
        // Counting the cancelled root's 5 as well as its replacement's would make the tree 10 against the line's 5.
        releaseAgain(line, 5);
    }

    @Test
    @DisplayName("an order cancelled under a subcontracted order: it is held for the manager (who is told), then cancelled with its PO; its good unit is written off, but the subcontractor is paid for it")
    void subcontractedOrderOfACancelledOrder() throws Exception {
        Line line = released(5);
        UUID childId = subcontracts.split(manager, line.productionOrderId(),
                split(2, ProductionOrder.SubcontractMethod.FULL_SERVICE, printSubcontractor())).summary().id();
        UUID poId = purchaseOrderOf(childId);
        approvePurchaseOrder(poId);
        subcontracts.sendOut(storekeeper, childId);
        sendPurchaseOrder(poId);
        receiveSubcontracted(poId, 1);
        runs.recordQc(inspector, childId, 1, 0, null, PACKING);

        cancelOrder(line.orderId(), "customer withdrew");
        Await.until("held", ASYNC, () -> detail(childId).summary().status() == ProductionOrder.Status.ON_HOLD);
        assertThat(recorded.matching(ProductionOrderHeld.class, e -> e.productionOrderId().equals(childId)))
                .isNotEmpty();
        ProductionOrders.Detail cancelled = runs.cancel(manager, childId, "customer withdrew", null, null);
        assertThat(cancelled.summary().status()).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(writeOffs(childId)).as("the good unit is sold to nobody else").isNotEmpty();
        assertThat(purchaseOrderStatus(poId)).as("1 of 2 received: closed short").isEqualTo("CLOSED");
        assertThat(subcontracts.settlement(manager, childId)).satisfies(settled -> {
            assertThat(settled.passedQuantity()).as("the subcontractor's good unit is paid").isEqualTo(1);
            assertThat(settled.payableAmount()).isEqualByComparingTo("1500");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.production_order WHERE parent_id = ?",
                Integer.class, childId)).as("no replacement for a cancelled order").isZero();
    }

    @Test
    @DisplayName("QA2: the subcontractor gives up after delivering 1 good of 3 while the order is live: the good unit stays the order's (completed, packed, announced), the 2 owed go to a new in-house order, the PO is closed short, the agreed fee settled; units waiting for QC are inspected first")
    void subcontractorAbandonsWithGoodUnits() throws Exception {
        Line line = released(7);
        UUID childId = subcontracts.split(manager, line.productionOrderId(),
                split(3, ProductionOrder.SubcontractMethod.FULL_SERVICE, printSubcontractor())).summary().id();
        UUID poId = purchaseOrderOf(childId);
        approvePurchaseOrder(poId);
        subcontracts.sendOut(storekeeper, childId);
        sendPurchaseOrder(poId);
        receiveSubcontracted(poId, 1);
        assertThatThrownBy(() -> runs.cancel(manager, childId, "subcontractor gave up", null, null))
                .as("its received unit is inspected first").extracting(PrintRunIntegrationTest::code)
                .isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        runs.recordQc(inspector, childId, 1, 0, null, PACKING);

        ProductionOrders.Detail ended = runs.cancel(manager, childId, "subcontractor gave up", new BigDecimal("20000"),
                "setup costs agreed");
        assertThat(ended.summary().status()).as("its good unit is the order's").isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(ended.summary().plannedQuantity()).isEqualTo(1);
        assertThat(recorded.matching(ProductionCompleted.class, e -> e.productionOrderId().equals(childId)))
                .singleElement().extracting(ProductionCompleted::goodQuantity).isEqualTo(1);
        ProductionOrders.Detail inHouse = detail(jdbc.queryForObject("""
                SELECT id FROM production.production_order WHERE parent_id = ? AND origin = 'SHORTFALL'""",
                UUID.class, childId));
        assertThat(inHouse.summary().plannedQuantity()).isEqualTo(2);
        assertThat(inHouse.summary().mode()).isEqualTo("IN_HOUSE");
        assertThat(purchaseOrderStatus(poId)).isEqualTo("CLOSED");
        assertThat(subcontracts.settlement(manager, childId)).satisfies(settled -> {
            assertThat(settled.passedQuantity()).isEqualTo(1);
            assertThat(settled.cancellationFee()).isEqualByComparingTo("20000");
            assertThat(settled.netPayable()).isEqualByComparingTo("21500");
        });
        releaseAgain(line, 7);
    }

    @Test
    @DisplayName("Jira 396: a job in QC can be held (waiting for the customer) and resumed back to QC; no QC result while held")
    void holdFromQc() throws Exception {
        Line line = released(2);
        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        runs.recordOutput(printer, line.productionOrderId(), 2, List.of());
        ProductionOrders.Detail held = workflow.hold(printer, line.productionOrderId(),
                ProductionOrder.HoldReason.WAITING_CUSTOMER, "colour approval");
        assertThat(held.summary().status()).isEqualTo(ProductionOrder.Status.ON_HOLD);
        assertThat(jdbc.queryForObject("SELECT status_before_hold FROM production.production_order WHERE id = ?",
                String.class, line.productionOrderId())).isEqualTo("QC");
        assertThatThrownBy(() -> runs.recordQc(inspector, line.productionOrderId(), 2, 0, null, PACKING))
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        assertThat(workflow.resume(manager, line.productionOrderId()).summary().status())
                .isEqualTo(ProductionOrder.Status.QC);
        assertThat(runs.recordQc(inspector, line.productionOrderId(), 2, 0, null, PACKING).summary().status())
                .isEqualTo(ProductionOrder.Status.COMPLETED);
    }

    private SampleRequests.Sample submittedSample(int quantity) throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ProductionFixtures.BLANK, quantity, null)));
        return samples.submit(sales, draft.id(), hcm);
    }

    @Test
    @DisplayName("Q5: a SAMPLE production order cancelled before printing cancels its round (history kept), lets its blanks go, and a new round holds blanks again with no answer of the customer's")
    void sampleCancelledBeforePrinting() throws Exception {
        int atp = inventory.availableToPromise(BLANK);
        SampleRequests.Sample round = submittedSample(2);
        UUID lsx = round.productionOrder().id();
        workflow.prepress(printer, lsx, true, null);
        issueAll(lsx);
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp - 2);

        runs.cancel(manager, lsx, "Customer withdrew the sample");

        SampleRequests.Sample cancelled = samples.get(sales, round.id());
        assertThat(cancelled.status()).isEqualTo(SampleStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).contains(round.productionOrder().orderNumber())
                .contains("Customer withdrew the sample");
        assertThat(inventory.availableToPromise(BLANK)).as("unprinted blanks back, free").isEqualTo(atp);
        assertThat(inventory.productionHolds(lsx)).isEmpty();
        progress.productionCancelled(lsx, "retried");
        assertThat(samples.get(sales, round.id()).cancellationReason()).as("a retry changes nothing")
                .contains("Customer withdrew the sample");
        Await.until("audited", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'sample-request' AND resource_id = ?
                   AND outcome = 'SUCCESS'""", Integer.class, lsx.toString()) >= 1);

        SampleRequests.Sample next = samples.startNextRound(sales, round.id(), null);
        assertThat(next.status()).isEqualTo(SampleStatus.DRAFT);
        assertThat(next.round()).isEqualTo(2);
        assertThat(next.previousRoundId()).isEqualTo(round.id());
        assertThat(next.respondedBy()).as("no answer inherited").isNull();
        assertThat(next.productionOrder()).isNull();
        assertThatThrownBy(() -> samples.startNextRound(sales, round.id(), null))
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.SAMPLE_NEXT_ROUND_EXISTS);
        SampleRequests.Sample resubmitted = samples.submit(sales, next.id(), hcm);
        assertThat(inventory.availableToPromise(BLANK)).as("held again for the new round").isEqualTo(atp - 2);
        assertThat(resubmitted.productionOrder().status()).isEqualTo(ProductionOrder.Status.PENDING_PREPRESS);
        assertThat(samples.get(sales, next.id()).rounds()).hasSize(2);
    }

    @Test
    @DisplayName("Q5: stopped on the press, the printed sample units stay written off (not blanks again), the unprinted come back, the round is cancelled and may start again; a draft cancelled by Sales may not")
    void sampleStoppedAfterPrinting() throws Exception {
        int atp = inventory.availableToPromise(BLANK);
        SampleRequests.Sample round = submittedSample(3);
        UUID lsx = round.productionOrder().id();
        workflow.prepress(printer, lsx, true, null);
        issueAll(lsx);
        runs.startPrinting(printer, lsx);

        runs.stop(manager, lsx, 2, List.of(), "Customer cancelled the quote");

        assertThat(samples.get(sales, round.id()).status()).isEqualTo(SampleStatus.CANCELLED);
        assertThat(detail(lsx).printedQuantity()).isEqualTo(2);
        assertThat(writeOffs(lsx).stream().mapToInt(row -> ((Number) row.get("quantity_delta")).intValue()).sum())
                .as("the 2 printed are written off").isEqualTo(-2);
        assertThat(inventory.availableToPromise(BLANK)).as("only the unprinted blank is free again").isEqualTo(atp - 2);
        assertThat(samples.startNextRound(sales, round.id(), null).round()).isEqualTo(2);

        ProductionFixtures.Design design = fixtures.confirmedDesign();
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ProductionFixtures.BLANK, 1, null)));
        samples.cancel(sales, draft.id(), "Not needed");
        assertThatThrownBy(() -> samples.startNextRound(sales, draft.id(), null))
                .extracting(PrintRunIntegrationTest::code).isEqualTo(ErrorCode.INVALID_SAMPLE_REQUEST_TRANSITION);
    }

    /** A released order of 10 with a captured payment of 20,000 and its job stopped on the press. */
    private Line paidJobOnThePress() throws Exception {
        return paidJobOnThePress(10);
    }

    /** {@code quantity} at 2,000 VND on the press, 20,000 VND of it paid. */
    private Line paidJobOnThePress(int quantity) throws Exception {
        Line line = released(quantity);
        issueAll(line.productionOrderId());
        runs.startPrinting(printer, line.productionOrderId());
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.payment (id, order_id, amount, currency, method, status, captured_at, created_at)
                VALUES (?, ?, 20000, 'VND', 'BANK_TRANSFER', 'CAPTURED', NOW(), NOW())""", Identifiers.newId(),
                line.orderId()));
        return line;
    }

    @Test
    @DisplayName("Q6a: on an order of 40,000 with 20,000 paid, an agreed 30,000 keeps all that was paid, refunds nothing and owes 10,000 as one receivable, once; above the order total it is refused; an agreed 20,000 owes and refunds nothing")
    void agreedFeeAboveWhatWasPaid() throws Exception {
        Line above = paidJobOnThePress(20);
        // Decision 2026-10-17: above what was paid, but never above what the order is worth (40,000).
        assertThatThrownBy(() -> cancelWithFee(above.orderId(), null, new CancellationFee(new BigDecimal("40001"),
                "Printing finished", "More than the order"))).extracting(PrintRunIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        UUID customer = salesOrders.findById(above.orderId()).orElseThrow().customerId();
        cancelWithFee(above.orderId(), null, new CancellationFee(new BigDecimal("30000"), "Printing finished",
                "Agreed with the buyer: 10 cups printed and the plates"));

        var cancellation = salesOrders.findById(above.orderId()).orElseThrow().cancellation();
        assertThat(cancellation.retainedAmount()).isEqualByComparingTo("20000");
        assertThat(cancellation.feeAmount()).isEqualByComparingTo("30000");
        Await.until("the shortfall is owed", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM payment.receivable WHERE order_id = ? AND kind = 'CANCELLATION_FEE'
                   AND amount = 10000 AND paid_amount = 0 AND status = 'OPEN'""", Integer.class, above.orderId()) == 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.refund WHERE order_id = ?", Integer.class,
                above.orderId())).as("nothing to refund").isZero();
        var redelivered = new com.stockflow.contracts.OrderCancelled(above.orderId(), "SO-IT", customer, "IN_PRODUCTION",
                "CUSTOMER_REQUEST", null, new BigDecimal("20000"), BigDecimal.ZERO, "VND", sales.userId(),
                java.time.Instant.now(), new BigDecimal("10000"));
        tx.executeWithoutResult(s -> publisher.publishEvent(redelivered));
        Thread.sleep(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.receivable WHERE order_id = ?", Integer.class,
                above.orderId())).as("owed once").isEqualTo(1);

        // The customer pays the shortfall (review audit P1-5): the receivable is paid, and the order counts the money
        // as kept against its fee - paid 30,000, kept 30,000 - instead of failing ck_order_cancellation_fee and
        // leaving the PaymentCaptured publication to be retried for ever.
        receivables.recordTransfer(new com.stockflow.payment.internal.service.ReceivableService.RecordTransferCommand(
                customer, "Q6A-" + above.orderId(), new BigDecimal("10000"), "VND", java.time.LocalDate.now(), List.of(),
                null, sales.userId()));
        Await.until("the order counts the payment of its fee", ASYNC, () -> salesOrders.findById(above.orderId())
                .orElseThrow().cancellation().retainedAmount().compareTo(new BigDecimal("30000")) == 0);
        assertThat(jdbc.queryForMap("SELECT paid_amount, cancellation_retained_amount, cancellation_fee_amount "
                + "FROM ordering.customer_order WHERE id = ?", above.orderId()))
                .allSatisfy((column, value) -> assertThat((BigDecimal) value).as(column).isEqualByComparingTo("30000"));
        assertThat(jdbc.queryForObject("""
                SELECT status FROM payment.receivable WHERE order_id = ?""", String.class, above.orderId())).isEqualTo("PAID");
        Await.until("no publication of the payment is left to retry", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL
                   AND serialized_event LIKE ?""", Integer.class, "%" + above.orderId() + "%") == 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.refund WHERE order_id = ?", Integer.class,
                above.orderId())).as("the fee, paid, refunds nothing").isZero();

        Line equal = paidJobOnThePress();
        cancelWithFee(equal.orderId(), null, new CancellationFee(new BigDecimal("20000"), "Printing finished",
                "Agreed with the buyer: what was paid"));
        Thread.sleep(800);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.refund WHERE order_id = ?", Integer.class,
                equal.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.receivable WHERE order_id = ?", Integer.class,
                equal.orderId())).isZero();
    }
}
