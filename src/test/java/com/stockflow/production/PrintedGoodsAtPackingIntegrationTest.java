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
import com.stockflow.fulfillment.api.PackingCompletion;
import com.stockflow.fulfillment.internal.service.Packing;
import com.stockflow.fulfillment.internal.service.PickLists;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.fulfillment.internal.service.Dispatching;
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
 * Printed goods from the QC round that completes their production order to the end of their life. Decision G4-Q2 /
 * PACK-PRINT (lead, 2026-10-21) replaced item 5b of the review of PR #109 (decision 2026-10-16):
 *
 * <ul>
 *   <li><b>G4-Q2</b> - the goods leave stock when the production order completes, in the QC round's transaction: once,
 *       one {@code CONSUMPTION} ledger row per hold naming the production order. Packing only puts them into parcels -
 *       whole, split across shipments, or held for a design mismatch - and deducts nothing.</li>
 *   <li><b>5a / BR-10</b> (decision 2026-10-19: cancel allowed with a production fee, the printed cups are SCRAP) -
 *       the order cancelled while they wait in packing, or its unshipped rest closed, they are scrapped, never sold:
 *       exactly the units that will not ship come back onto their packing-area layer under a new pinned hold
 *       (available-to-promise does not move) and a SCRAP write-off of them is asked for; a rejection leaves them held;
 *       approval takes them off again, so net they left stock once.</li>
 * </ul>
 *
 * <p>Statuses and stock move only through the services; the sales order is rows, as in
 * {@code PrintRunIntegrationTest}, with real holds.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class PrintedGoodsAtPackingIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    com.stockflow.common.security.RoleAuthorizationLookup grants;

    /** The role's permissions as seeded: services check permissions, not role names. */
    private java.util.Set<com.stockflow.common.security.PermissionCode> seeded(Role role) {
        return grants.resolve(java.util.List.of(role.authority())).permissions();
    }

    private static final String PRINT_SHOP = "HCM-PRD01";
    private static final String PACKING = "HCM-PACK01";
    private static final Sku BLANK = new Sku(ProductionFixtures.BLANK);
    /** KIT-FORCE: the blank ships as 2 boxes; packed in an ordinary package, it needs a reason. */
    private static final String ONE_CARTON = "test: packed as one carton";
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
    @Autowired Dispatching dispatching;
    @Autowired FulfillmentService fulfillment;
    @Autowired Packing packing;
    @Autowired PickLists pickLists;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired org.springframework.context.ApplicationEventPublisher publisher;

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
        return new CurrentUser(id, role.name() + "-" + id, Set.of(role), seeded(role), DataScope.WAREHOUSE, Set.of("HCM"),
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

    /**
     * @param onHandBefore {@link #BLANK} on hand in the HCM warehouse just before the QC round that completed the order
     * @param atpBefore    its available-to-promise then
     */
    record Line(UUID orderId, UUID lineId, UUID productionOrderId, String orderNumber, int onHandBefore, int atpBefore) {
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
        return inPacking(quantity, 0, null);
    }

    /**
     * The same order with a second, plain line of {@code stockQuantity} units of the same SKU held in storage: a mixed
     * order (kltn-docs 17 §4.2 step 5). {@code stockLine} receives that line's id.
     */
    private Line inPacking(int quantity, int stockQuantity, UUID[] stockLine) throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        UUID sample = approvedSample(design);
        UUID orderId = Identifiers.newId();
        UUID lineId = Identifiers.newId();
        String number = "SO-20991230-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, version, created_at, paid_amount, paid_in_full_at, warehouse_id,
                        released_at, released_by, allow_partial_shipment)
                    VALUES (?, ?, ?, ?, 'IN_PRODUCTION', ?, 'VND', NOW(), 0, NOW(), ?, NOW(), ?, NOW(), ?, TRUE)""",
                    orderId, number, DemoData.CUSTOMER_ID, UUID.randomUUID(), quantity * 2000, quantity * 2000, hcm,
                    sales.userId());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency,
                        design_snapshot_id, design_checksum) VALUES (?, ?, ?, ?, 2000, 'VND', ?, ?)""",
                    lineId, orderId, ProductionFixtures.BLANK, quantity, design.snapshotId(), design.checksum());
        });
        UUID plainLine = Identifiers.newId();
        if (stockQuantity > 0) {
            stockLine[0] = plainLine;
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE ordering.customer_order SET total_amount = total_amount + ?, paid_amount = "
                        + "paid_amount + ? WHERE id = ?", stockQuantity * 2000, stockQuantity * 2000, orderId);
                jdbc.update("""
                        INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                        VALUES (?, ?, ?, ?, 2000, 'VND')""", plainLine, orderId, ProductionFixtures.BLANK, stockQuantity);
            });
        }
        tx.executeWithoutResult(s -> {
            UUID printedHold = inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), BLANK, quantity, orderId))
                    .reservationIds().get(0);
            if (stockQuantity > 0) {
                inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), BLANK, stockQuantity, orderId));
            }
            inventory.pinReservations(orderId);
            // As the release does (SCRUM-152): each SKU reservation becomes allocations on HCM bins, which its line
            // then holds instead.
            inventory.allocate(new com.stockflow.inventory.api.AllocateStockCommand(orderId, "HCM")).allocations()
                    .forEach(allocation -> allocation.holdIds().forEach(id -> jdbc.update(
                            "INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES (?, ?)",
                            allocation.reservationId().equals(printedHold) ? lineId : plainLine, id)));
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
        int onHandBefore = onHandInWarehouse();
        int atpBefore = inventory.availableToPromise(BLANK);
        ProductionOrders.Detail done = runs.recordQc(inspector, productionOrderId, quantity, 0, null, PACKING,
                sampleOf(productionOrderId));
        assertThat(done.summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        Await.until("the order is ready to fulfil", ASYNC,
                () -> salesOrders.findById(orderId).orElseThrow().status() == OrderStatus.READY_TO_FULFILL);
        return new Line(orderId, lineId, productionOrderId, number, onHandBefore, atpBefore);
    }

    /** The coordinator gives the task to the picker, who starts and completes it: a print line has nothing to pick. */
    private UUID picked(Line line) {
        UUID task = fulfillment.createTask(line.orderId(), picker.userId(), coordinator).taskId();
        fulfillment.startPicking(task, picker);
        fulfillment.completePicking(task, picker);
        return task;
    }

    /**
     * G4-5: the packer takes the order at the packing table, scans {@code quantity} units of {@code lineId} into a new
     * package and seals it with its weight and size. Returns the package.
     */
    private UUID sealedPackage(UUID task, UUID lineId, int quantity) {
        packing.start(task, picker);
        UUID parcel = packing.openPackage(task, null, ONE_CARTON, picker).packageId();
        packing.scan(task, parcel, new Packing.Scan(UUID.randomUUID(), BLANK.code(), null, null, quantity, lineId),
                picker);
        packing.seal(task, parcel, new Packing.Seal(new java.math.BigDecimal("3.5"), new java.math.BigDecimal("40"),
                new java.math.BigDecimal("30"), new java.math.BigDecimal("30"), null, null, null), picker);
        return parcel;
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

    /** {@link #BLANK} on hand anywhere in the HCM warehouse: storage, print shop, packing. */
    private int onHandInWarehouse() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand), 0)::int FROM inventory.stock_item
                 WHERE sku = ? AND location_code LIKE 'HCM-%'""", Integer.class, BLANK.code());
    }

    /** Every ledger row of {@link #BLANK}: packing must add none. */
    private int ledgerRows() {
        return jdbc.queryForObject("SELECT COUNT(*)::int FROM inventory.stock_movement WHERE sku = ?", Integer.class,
                BLANK.code());
    }

    /** The CONSUMPTION rows the completion of {@code line}'s production order wrote: count, units, where from. */
    private Map<String, Object> consumption(Line line) {
        return jdbc.queryForMap("""
                SELECT COUNT(*)::int AS rows, COALESCE(SUM(quantity), 0)::int AS units,
                       STRING_AGG(DISTINCT from_location_code, ',') AS from_location,
                       COUNT(*) FILTER (WHERE order_id IS NULL OR lot_id IS NULL)::int AS untraced,
                       STRING_AGG(DISTINCT order_id::text, ',') AS order_id
                  FROM inventory.stock_movement
                 WHERE movement_type = 'CONSUMPTION' AND reference_type = 'PRODUCTION_ORDER' AND reference_id = ?""",
                line.productionOrderId());
    }

    /** The holds a cancellation or closure opened to bring {@code holds}' consumed goods back for their scrap. */
    private List<UUID> scrapHoldsOf(List<UUID> holds) {
        assertThat(holds).as("the line's holds, read before a cancellation clears them").isNotEmpty();
        return jdbc.queryForList("""
                SELECT id FROM inventory.stock_reservation WHERE root_request_id IN (%s) AND id NOT IN (%s)
                 ORDER BY reserved_at, id""".formatted(placeholders(holds), placeholders(holds)), UUID.class,
                java.util.stream.Stream.concat(holds.stream(), holds.stream()).toArray());
    }

    private int heldUnits(List<UUID> holds) {
        return holds.isEmpty() ? 0 : jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0)::int FROM inventory.stock_reservation WHERE status = 'HELD'
                   AND id IN (%s)""".formatted(placeholders(holds)), Integer.class, holds.toArray());
    }

    private int putBackUnits(Line line) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0)::int FROM inventory.stock_movement
                 WHERE movement_type = 'PUT_BACK' AND reference_type = 'ORDER' AND reference_id = ?""", Integer.class,
                line.orderId());
    }

    private static String placeholders(List<UUID> ids) {
        return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    }

    /** The order's holds and its goods consumed when production completed, as G4-Q2 leaves them. */
    private void assertDeductedAtCompletion(Line line, List<UUID> holds) {
        assertThat(onHandInWarehouse()).as("deducted once, when production completed").isEqualTo(line.onHandBefore()
                - jdbc.queryForObject("SELECT quantity FROM ordering.order_line WHERE id = ?", Integer.class,
                line.lineId()));
        List<String> statuses = holdStatuses(holds);
        assertThat(statuses).contains("CONSUMED").allMatch(s -> s.equals("CONSUMED") || s.equals("RELEASED"));
        Map<String, Object> consumed = consumption(line);
        assertThat(consumed.get("rows")).as("one CONSUMPTION row per hold")
                .isEqualTo((int) statuses.stream().filter("CONSUMED"::equals).count());
        assertThat(consumed).containsEntry("from_location", PACKING);
        // SCRUM-153: the lot trace reaches the order its printed goods were consumed for.
        assertThat(consumed).as("every row names its lot and its order").containsEntry("untraced", 0)
                .containsEntry("order_id", jdbc.queryForObject("SELECT order_id::text FROM ordering.order_line WHERE id = ?",
                        String.class, line.lineId()));
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

    // ------------------------------------------------------------------------------------------- G4-Q2

    @Test
    @DisplayName("G4-Q2: the production order's completion deducts the print line's goods once - one CONSUMPTION row per "
            + "hold naming it, ATP unchanged; picking, packing and completing again deduct nothing")
    void productionCompletionDeductsThePrintLineOnce() throws Exception {
        Line line = inPacking(3);
        List<UUID> holds = holdsOf(line);

        assertDeductedAtCompletion(line, holds);
        assertThat(consumption(line)).containsEntry("units", 3);
        assertThat(inventory.availableToPromise(BLANK)).as("the units were held already").isEqualTo(line.atpBefore());

        int ledger = ledgerRows();
        int onHand = onHandInWarehouse();
        UUID task = picked(line);
        assertThat(onHandInWarehouse()).as("picking takes nothing of a print line").isEqualTo(onHand);
        UUID parcel = sealedPackage(task, line.lineId(), 3);
        FulfillmentTask packed = fulfillment.completePacking(task, picker);

        assertThat(packed.packStatus()).isEqualTo("PACKED");
        assertThat(packed.shipmentId()).isNotNull();
        assertThat(onHandInWarehouse()).as("packing moves the goods into parcels, no deduction").isEqualTo(onHand);
        assertThat(ledgerRows()).as("no ledger row from packing").isEqualTo(ledger);
        assertThat(packageLedgerRows(parcel)).isZero();
        // Completed again (a retried request): answered as it is, the same shipment, nothing deducted.
        assertThat(fulfillment.completePacking(task, picker).shipmentId()).isEqualTo(packed.shipmentId());
        assertThat(onHandInWarehouse()).isEqualTo(onHand);
        assertThat(ledgerRows()).isEqualTo(ledger);
    }

    @Test
    @DisplayName("G4-Q2: a design checksum that no longer matches holds the package; nothing ships and nothing moves in "
            + "stock - the goods left it when production completed")
    void aMismatchDeductsNothing() throws Exception {
        Line line = inPacking(2);
        List<UUID> holds = holdsOf(line);
        int onHand = onHandInWarehouse();
        int ledger = ledgerRows();
        UUID task = picked(line);
        // The artwork changed under the order: the line's checksum no longer matches the confirmed snapshot. The
        // checksum is immutable to the application, so the trigger is set aside for this one statement.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.order_line SET design_checksum = ? WHERE id = ?", "0".repeat(64), line.lineId());
        });

        UUID parcel = sealedPackage(task, line.lineId(), 2);
        PackingCompletion request = new PackingCompletion(UUID.randomUUID(), false, null);
        FulfillmentTask held = fulfillment.completePacking(task, request, picker);

        assertThat(held.packStatus()).isEqualTo("ON_HOLD");
        assertThat(held.shipmentId()).isNull();
        // Review #3: the same request again (a retry) gets the same answer, not a state conflict.
        FulfillmentTask retried = fulfillment.completePacking(task, request, picker);
        assertThat(retried.packStatus()).isEqualTo("ON_HOLD");
        assertThat(retried.shipmentId()).isNull();
        assertThat(onHandInWarehouse()).isEqualTo(onHand);
        assertThat(ledgerRows()).isEqualTo(ledger);
        assertDeductedAtCompletion(line, holds);
        assertThat(packageLedgerRows(parcel)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment WHERE order_id = ?", Integer.class,
                line.orderId())).as("nothing ships").isZero();
        assertThat(code(catchThrowable(() -> stockOperations.askScrapAgain(holds.get(0), "Scrap it", manager.userId()))))
                .as("a live order's goods are nobody's to scrap").isEqualTo(ErrorCode.CONFLICT);
        assertThat(scrapHoldsOf(holds)).isEmpty();
    }

    @Test
    @DisplayName("G4-Q2: a print line split across two shipments - each package takes its part, neither deducts, the "
            + "goods were deducted once when production completed")
    void aSplitPrintLineDeductsNothingAtPacking() throws Exception {
        Line line = inPacking(5);
        List<UUID> holds = holdsOf(line);
        assertDeductedAtCompletion(line, holds);
        int onHand = onHandInWarehouse();
        int ledger = ledgerRows();
        UUID task = picked(line);

        UUID first = sealedPackage(task, line.lineId(), 2);
        FulfillmentTask part = fulfillment.completePacking(task,
                new PackingCompletion(UUID.randomUUID(), true, "Two now, the rest tomorrow"), picker);
        assertThat(part.shipmentId()).isNotNull();
        assertThat(part.packStatus()).isEqualTo("PACKING");
        assertThat(onHandInWarehouse()).isEqualTo(onHand);

        UUID second = packing.openPackage(task, null, ONE_CARTON, picker).packageId();
        packing.scan(task, second, new Packing.Scan(UUID.randomUUID(), BLANK.code(), null, null, 3, line.lineId()),
                picker);
        packing.seal(task, second, new Packing.Seal(new java.math.BigDecimal("2"), new java.math.BigDecimal("30"),
                new java.math.BigDecimal("30"), new java.math.BigDecimal("30"), null, null, null), picker);
        FulfillmentTask rest = fulfillment.completePacking(task, picker);

        assertThat(rest.packStatus()).isEqualTo("PACKED");
        assertThat(rest.shipmentId()).isNotEqualTo(part.shipmentId());
        assertThat(onHandInWarehouse()).as("neither shipment deducts").isEqualTo(onHand);
        assertThat(ledgerRows()).isEqualTo(ledger);
        assertThat(packageLedgerRows(first)).isZero();
        assertThat(packageLedgerRows(second)).isZero();
        assertThat(heldUnits(holds)).as("no hold left HELD").isZero();
        assertThat(scrapHoldsOf(holds)).isEmpty();
    }

    @Test
    @DisplayName("G4-Q2 + BR-10: two of five printed units shipped, the rest closed - exactly the three closed units "
            + "come back held and are asked to be scrapped; approved, net the five left stock once, nothing left HELD")
    void aClosedRemainderScrapsOnlyTheUnshippedUnits() throws Exception {
        Line line = inPacking(5);
        List<UUID> holds = holdsOf(line);
        UUID task = picked(line);
        sealedPackage(task, line.lineId(), 2);
        FulfillmentTask part = fulfillment.completePacking(task,
                new PackingCompletion(UUID.randomUUID(), true, "Two now"), picker);
        handOver(part.shipmentId());
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        int onHand = onHandInWarehouse();
        int atp = inventory.availableToPromise(BLANK);

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> salesOrders.closeRemainder(line.orderId(),
                CancellationReasonCode.CUSTOMER_REQUEST, "The customer keeps two", sales.userId()));

        List<UUID> scrapHolds = scrapHoldsOf(holds);
        assertThat(heldUnits(scrapHolds)).as("only the unshipped units come back").isEqualTo(3);
        assertThat(putBackUnits(line)).isEqualTo(3);
        assertThat(onHandInWarehouse()).isEqualTo(onHand + 3);
        assertThat(inventory.availableToPromise(BLANK)).as("held, never on sale").isEqualTo(atp);
        List<Map<String, Object>> scrap = scrapOf(scrapHolds);
        assertThat(scrap).isNotEmpty().allSatisfy(row -> assertThat(row).containsEntry("status", "PENDING_APPROVAL")
                .containsEntry("reason_code", "SCRAP").containsEntry("location_code", PACKING));
        assertThat(scrap.stream().mapToInt(row -> (Integer) row.get("quantity_delta")).sum()).isEqualTo(-3);

        scrap.forEach(row -> stockOperations.approve((UUID) row.get("id"), approver.userId()));
        assertThat(onHandInWarehouse()).isEqualTo(onHand);
        assertThat(onHandInWarehouse()).as("net: the five left stock once").isEqualTo(line.onHandBefore() - 5);
        assertThat(heldUnits(holds) + heldUnits(scrapHolds)).as("nothing left HELD").isZero();
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp);
    }

    /** The dispatcher loads the shipment on a GHN load, scans its parcels and the driver signs (09 §4.1). */
    private void handOver(UUID shipmentId) {
        UUID ghn = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHN'", UUID.class);
        Dispatching.LoadDetail load = dispatching.open(new Dispatching.OpenLoad(UUID.randomUUID(), hcm, ghn,
                List.of(shipmentId), "51C-999.99", "Driver", "0900000000"), picker);
        for (String label : jdbc.queryForList("SELECT label_code FROM fulfillment.package WHERE shipment_id = ?",
                String.class, shipmentId)) {
            load = dispatching.scan(load.loadId(), UUID.randomUUID(), label, picker);
        }
        dispatching.handOver(load.loadId(), new Dispatching.Handover(load.version(), "Nguyen Van Tai", "51C-999.99",
                "0900000000", "MF-1", true, null), picker);
    }

    // ------------------------------------------------------------------------------------------- 5a / BR-10

    @Test
    @DisplayName("BR-10 after G4-Q2: cancelled while its goods wait in packing - the consumed units come back held (ATP "
            + "unchanged), one SCRAP write-off of exactly them is asked for; asked again, the same; rejected, still "
            + "held; approved, off again - net deducted once")
    void cancelledInPackingIsScrappedNeverSold() throws Exception {
        Line line = inPacking(3);
        List<UUID> holds = holdsOf(line);
        int onHand = onHandInWarehouse();
        int atPacking = onHandAtPacking();
        int atp = inventory.availableToPromise(BLANK);

        cancel(line);

        assertThat(holdStatuses(holds)).as("the order's own holds stay consumed").doesNotContain("HELD");
        List<UUID> scrapHolds = scrapHoldsOf(holds);
        assertThat(heldUnits(scrapHolds)).as("the printed units, held for their scrap").isEqualTo(3);
        assertThat(putBackUnits(line)).isEqualTo(3);
        assertThat(onHandInWarehouse()).isEqualTo(onHand + 3);
        assertThat(onHandAtPacking()).as("back onto the packing area's layer").isEqualTo(atPacking + 3);
        assertThat(inventory.availableToPromise(BLANK)).as("never on sale").isEqualTo(atp);
        List<Map<String, Object>> scrap = scrapOf(scrapHolds);
        assertThat(scrap).isNotEmpty().allSatisfy(row -> {
            assertThat(row).containsEntry("status", "PENDING_APPROVAL").containsEntry("reason_code", "SCRAP")
                    .containsEntry("location_code", PACKING);
            assertThat((String) row.get("note")).contains(line.orderNumber());
        });
        assertThat(scrap.stream().mapToInt(row -> (Integer) row.get("quantity_delta")).sum()).isEqualTo(-3);

        // Asked again with the same key (a retry): the same write-offs, nothing brought back twice.
        UUID requestId = UUID.nameUUIDFromBytes("print-scrap:cancel:%s:0".formatted(line.lineId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<StockAdjustmentSummary> again = tx.execute(s -> inventory.scrapCancelledPrint(
                new com.stockflow.inventory.api.ScrapCancelledPrintCommand(line.orderId(), requestId, holds, 3, "again",
                        manager.userId())));
        assertThat(again).extracting(StockAdjustmentSummary::adjustmentId)
                .containsExactlyInAnyOrderElementsOf(scrap.stream().map(row -> (UUID) row.get("id")).toList());
        assertThat(code(catchThrowable(() -> cancel(line)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(scrapHoldsOf(holds)).isEqualTo(scrapHolds);
        assertThat(onHandInWarehouse()).isEqualTo(onHand + 3);

        // Four eyes: the canceller does not approve; a rejection leaves the goods held, and they may be asked again.
        UUID first = (UUID) scrap.get(0).get("reservation_id");
        UUID adjustment = (UUID) scrap.get(0).get("id");
        assertThat(code(catchThrowable(() -> stockOperations.approve(adjustment, sales.userId()))))
                .isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);
        stockOperations.reject(adjustment, approver.userId(), "Counted 2, not 3");
        assertThat(heldUnits(scrapHolds)).isEqualTo(3);
        assertThat(inventory.availableToPromise(BLANK)).isEqualTo(atp);
        StockAdjustmentSummary renewed = stockOperations.askScrapAgain(first, "Recounted: 3", manager.userId());
        assertThat(renewed.adjustmentId()).isNotEqualTo(adjustment);
        assertThat(stockOperations.askScrapAgain(first, "twice", manager.userId()).adjustmentId())
                .as("asked again while it stands: the same").isEqualTo(renewed.adjustmentId());

        scrapOf(scrapHolds).stream().filter(row -> "PENDING_APPROVAL".equals(row.get("status")))
                .forEach(row -> stockOperations.approve((UUID) row.get("id"), approver.userId()));
        assertThat(onHandInWarehouse()).as("net: the three left stock once").isEqualTo(line.onHandBefore() - 3);
        assertThat(heldUnits(holds) + heldUnits(scrapHolds)).as("closed as scrap, nothing left HELD").isZero();
        assertThat(inventory.availableToPromise(BLANK)).as("never on sale").isEqualTo(atp);
        assertThat(code(catchThrowable(() -> stockOperations.approve(renewed.adjustmentId(), approver.userId()))))
                .as("deducted once").isEqualTo(ErrorCode.INVALID_ADJUSTMENT_TRANSITION);
        assertThat(onHandInWarehouse()).isEqualTo(line.onHandBefore() - 3);
    }

    @Test
    @DisplayName("decision 2026-10-17: a payment failing after printing started cancels nothing; Sales and the coordinators are told, once")
    void aFailedPaymentAfterPrintingIsReferredToStaff() throws Exception {
        Line line = inPacking(2);
        CurrentUser salesPerson = assignable(Role.SALES_STAFF);
        CurrentUser coordinatorPerson = assignable(Role.ORDER_COORDINATOR);
        var failed = new com.stockflow.contracts.PaymentFailed(line.orderId(), UUID.randomUUID(), "CARD_DECLINED",
                "declined");

        tx.executeWithoutResult(s -> publisher.publishEvent(failed));

        Await.until("Sales and the coordinator are told", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification WHERE category = 'ORDER_ATTENTION'
                   AND source_id = ? AND recipient_id IN (?, ?)""", Integer.class, line.orderId().toString(),
                salesPerson.userId(), coordinatorPerson.userId()) == 2);
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().status()).as("not cancelled")
                .isEqualTo(OrderStatus.READY_TO_FULFILL);
        tx.executeWithoutResult(s -> publisher.publishEvent(failed));
        Thread.sleep(800);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification WHERE category = 'ORDER_ATTENTION'
                   AND source_id = ? AND recipient_id = ?""", Integer.class, line.orderId().toString(),
                salesPerson.userId())).as("told once").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?""",
                Integer.class, "%" + line.orderId() + "%")).as("nothing left to retry").isZero();
    }

    @Test
    @DisplayName("audit of #109: another warehouse's manager reads nothing of a cancelled order's scrap request, and cannot ask it again")
    void askingAgainIsWarehouseScoped() throws Exception {
        Line line = inPacking(2);
        List<UUID> holds = holdsOf(line);
        cancel(line);
        UUID hold = scrapHoldsOf(holds).getFirst();
        // Assigned to another warehouse only (the scope compares prefixes; the warehouse need not exist here).
        UUID otherWarehouse = UUID.randomUUID();
        String otherPrefix = "ZZ";
        CurrentUser elsewhere = new CurrentUser(manager.userId(), "elsewhere", Set.of(Role.WAREHOUSE_MANAGER), seeded(Role.WAREHOUSE_MANAGER),
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
        UUID item = Identifiers.newId();
        UUID hold = Identifiers.newId();
        // SCRUM-152: a checkout no longer chooses a location, and an allocation never chooses a packing area (not
        // pickable), so such a hold is one a checkout placed before that change - written here as it was stored.
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version, created_at,
                        received_at) VALUES (?, ?, ?, 1, 1, 'AVAILABLE', 0, NOW(), NOW() - interval '9000 days')""",
                    item, BLANK.code(), PACKING);
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, version, created_at) VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 2000, 'VND', NOW(), 0,
                        NOW())""", orderId, "SO-20991231-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current()
                    .nextInt(1_000_000)), DemoData.CUSTOMER_ID, UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO inventory.stock_reservation (id, stock_item_id, sku, order_id, root_request_id, request_id,
                        quantity, reserved_at, expires_at, status)
                    VALUES (?, ?, ?, ?, gen_random_uuid(), gen_random_uuid(), 1, NOW(), NOW() + interval '30 minutes',
                        'HELD')""", hold, item, BLANK.code(), orderId);
        });
        assertThat(jdbc.queryForObject("""
                SELECT s.location_code FROM inventory.stock_reservation r JOIN inventory.stock_item s
                    ON s.id = r.stock_item_id WHERE r.id = ?""", String.class, hold)).as("the oldest layer").isEqualTo(PACKING);

        assertThat(code(catchThrowable(() -> tx.execute(s -> inventory.consumeFinishedGoods(hold, orderId,
                Identifiers.newId(), picker.userId()))))).as("G4-Q2: not finished goods, never consumed as such")
                .isEqualTo(ErrorCode.STOCK_STATUS_MISMATCH);

        tx.executeWithoutResult(s -> inventory.release(hold, "ORDER_CANCELLED"));

        assertThat(holdStatuses(List.of(hold))).containsExactly("RELEASED");
    }

    @Test
    @DisplayName("03 §9: once the package is completed the order is not cancelled (it goes through a return) - deducted "
            + "once at completion, nothing scrapped, nothing back on sale")
    void aPackedOrderIsNotCancelled() throws Exception {
        Line line = inPacking(2);
        List<UUID> holds = holdsOf(line);
        UUID task = picked(line);
        sealedPackage(task, line.lineId(), 2);
        fulfillment.completePacking(task, picker);
        int atp = inventory.availableToPromise(BLANK);
        assertThat(jdbc.queryForObject("SELECT packed_at FROM ordering.customer_order WHERE id = ?",
                java.sql.Timestamp.class, line.orderId())).isNotNull();

        assertThat(code(catchThrowable(() -> cancel(line)))).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);

        assertThat(salesOrders.findById(line.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
        assertThat(jdbc.queryForObject("SELECT p.status FROM fulfillment.pack p WHERE p.pick_id = ?", String.class, task))
                .isEqualTo("PACKED");
        assertDeductedAtCompletion(line, holds);
        assertThat(scrapHoldsOf(holds)).isEmpty();
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
        java.util.concurrent.CompletableFuture<Void> packingDone = java.util.concurrent.CompletableFuture.runAsync(() ->
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
        packingDone.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(code(cancelling.get(30, java.util.concurrent.TimeUnit.SECONDS)))
                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(scrapHoldsOf(holdsOf(line))).as("a refused cancellation scraps nothing").isEmpty();

        Line other = inPacking(2);
        UUID otherTask = picked(other);
        cancel(other);
        assertThat(catchThrowable(() -> fulfillment.completePacking(otherTask, picker)))
                .as("a cancelled order's package is not completed").isInstanceOf(BusinessException.class);
        assertThat(packageLedgerRows(packOf(otherTask))).isZero();
    }

    // ------------------------------------------------------------------------------------------- G4-5

    @Test
    @DisplayName("G4-5: a design hold during packing - the warehouse manager re-verifies, packing is back in progress (08 §5), the packer "
            + "completes; stock moved only when production completed")
    void aDesignHoldIsReverifiedThenPacked() throws Exception {
        Line line = inPacking(2);
        int onHand = onHandInWarehouse();
        UUID task = picked(line);
        String confirmed = jdbc.queryForObject("SELECT design_checksum FROM ordering.order_line WHERE id = ?",
                String.class, line.lineId());
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.order_line SET design_checksum = ? WHERE id = ?", "0".repeat(64), line.lineId());
        });
        UUID parcel = sealedPackage(task, line.lineId(), 2);
        assertThat(fulfillment.completePacking(task, picker).packStatus()).isEqualTo("ON_HOLD");
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(code(catchThrowable(() -> packing.openPackage(task, null, ONE_CARTON, picker))))
                .as("nothing changes while held").isEqualTo(ErrorCode.PACK_STATE_CONFLICT);

        // Still wrong: the manager's attempt is kept as evidence, the hold stays.
        assertThat(fulfillment.reverifyDesignIntegrity(task, "checked", manager).packStatus()).isEqualTo("ON_HOLD");
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.order_line SET design_checksum = ? WHERE id = ?", confirmed, line.lineId());
        });
        FulfillmentTask resumed = fulfillment.reverifyDesignIntegrity(task, "Artwork restored", manager);

        assertThat(resumed.packStatus()).isEqualTo("PACKING");
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PICKING);
        FulfillmentTask packed = fulfillment.completePacking(task, picker);
        assertThat(packed.packStatus()).isEqualTo("PACKED");
        assertThat(packed.shipmentId()).isNotNull();
        assertThat(onHandInWarehouse()).isEqualTo(onHand);
        assertThat(packageLedgerRows(parcel)).isZero();
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
    }

    @Test
    @DisplayName("G4-5, decision 2026-10-21 (D9): a mixed order ships its stock line first and its printed line later - "
            + "two shipments; not cancellable after the first; the stock line deducted when picked, the print line "
            + "when production completed; PACKED only then")
    void aMixedOrderShipsItsStockFirst() throws Exception {
        UUID[] stock = new UUID[1];
        Line line = inPacking(2, 3, stock);
        UUID stockLine = stock[0];
        List<UUID> printHolds = holdsOf(line);
        assertThat(onHandInWarehouse()).isEqualTo(line.onHandBefore() - 2);
        int beforePicking = onHandInWarehouse();
        UUID task = fulfillment.createTask(line.orderId(), picker.userId(), coordinator).taskId();
        fulfillment.startPicking(task, picker);
        for (Map<String, Object> pickLine : jdbc.queryForList("""
                SELECT id, location_code, allocated_qty FROM fulfillment.pick_line WHERE pick_id = ?""", task)) {
            pickLists.confirm(task, (UUID) pickLine.get("id"), (String) pickLine.get("location_code"), BLANK.code(),
                    (Integer) pickLine.get("allocated_qty"), picker);
        }
        fulfillment.completePicking(task, picker);
        assertThat(onHandInWarehouse()).as("the stock line, when picked").isEqualTo(beforePicking - 3);

        packing.start(task, picker);
        UUID first = packing.openPackage(task, null, ONE_CARTON, picker).packageId();
        assertThat(code(catchThrowable(() -> packing.scan(task, first, new Packing.Scan(UUID.randomUUID(), BLANK.code(),
                null, null, 1, null), picker)))).as("the SKU is on both lines").isEqualTo(ErrorCode.PACK_SCAN_AMBIGUOUS);
        packing.scan(task, first, new Packing.Scan(UUID.randomUUID(), BLANK.code(), null, null, 3, stockLine), picker);
        packing.seal(task, first, new Packing.Seal(new java.math.BigDecimal("2"), new java.math.BigDecimal("30"),
                new java.math.BigDecimal("30"), new java.math.BigDecimal("30"), null, null, null), picker);
        assertThat(code(catchThrowable(() -> fulfillment.completePacking(task, picker))))
                .as("the whole order is expected unless partial").isEqualTo(ErrorCode.PACKING_INCOMPLETE);

        FulfillmentTask stockFirst = fulfillment.completePacking(task,
                new PackingCompletion(UUID.randomUUID(), true, "Sale ships the stock cups first"), picker);

        assertThat(stockFirst.shipmentId()).isNotNull();
        assertThat(stockFirst.packStatus()).isEqualTo("PACKING");
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PICKING);
        assertThat(holdStatuses(printHolds)).as("deducted when production completed").doesNotContain("HELD");
        assertThat(code(catchThrowable(() -> cancel(line)))).as("a package was completed")
                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);

        UUID second = packing.openPackage(task, null, ONE_CARTON, picker).packageId();
        // The stock line is full: the SKU now means the printed line.
        packing.scan(task, second, new Packing.Scan(UUID.randomUUID(), BLANK.code(), null, null, 2, null), picker);
        packing.seal(task, second, new Packing.Seal(new java.math.BigDecimal("1.5"), new java.math.BigDecimal("30"),
                new java.math.BigDecimal("30"), new java.math.BigDecimal("20"), null, null, null), picker);
        FulfillmentTask printedLater = fulfillment.completePacking(task, picker);

        assertThat(printedLater.packStatus()).isEqualTo("PACKED");
        assertThat(printedLater.shipmentId()).isNotEqualTo(stockFirst.shipmentId());
        assertThat(salesOrders.findById(line.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
        assertThat(onHandInWarehouse()).as("packing deducted nothing").isEqualTo(beforePicking - 3);
        assertThat(packageLedgerRows(second)).isZero();
        assertThat(packageLedgerRows(first)).isZero();
        assertThat(jdbc.queryForList("""
                SELECT code || ':' || status || ':' || partial || ':' || package_count FROM fulfillment.shipment
                 WHERE order_id = ? ORDER BY ready_at, code""", String.class, line.orderId()))
                .containsExactly(line.orderNumber() + "-S1:READY_TO_DISPATCH:true:1",
                        line.orderNumber() + "-S2:READY_TO_DISPATCH:false:1");
    }

    /** The approved sample a QC round of this order names (BR-PRD-05, SCRUM-487); null when it has none. */
    private UUID sampleOf(UUID productionOrderId) {
        return jdbc.queryForObject("SELECT approved_sample_id FROM production.production_order WHERE id = ?",
                UUID.class, productionOrderId);
    }
}
