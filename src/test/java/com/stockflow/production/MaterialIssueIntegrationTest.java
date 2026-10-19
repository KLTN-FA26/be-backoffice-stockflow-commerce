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
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.service.MaterialIssues;
import com.stockflow.production.internal.service.OrderProductionService;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.ProductionWorkflow;
import com.stockflow.production.internal.service.SampleProductionProgress;
import com.stockflow.production.internal.service.SampleRequests;
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
 * SCRUM-397 / BE 409 against a real PostgreSQL: blanks held for an order at checkout, or for a sample from its
 * submission (decision Q1), issued whole to the PRODUCTION area through {@code inventory :: api}. Proves the
 * hold never lets go (same reservation id, still HELD, available-to-promise unchanged), that issued goods are
 * not sellable even once their hold is released (Q3), the BR-PRD-03 cap, replay and concurrency. No status
 * and no stock row is set by SQL; the sales order and its lines are, as in {@code ProductionIntegrationTest},
 * because placing a print order through checkout is not what is under test — its holds are real
 * {@code reserve} calls.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class MaterialIssueIntegrationTest {

    private static final String PRINT_SHOP = "HCM-PRD01";
    private static final Sku BLANK = new Sku(ProductionFixtures.BLANK);

    @Autowired MaterialIssues issues;
    @Autowired ProductionWorkflow workflow;
    @Autowired ProductionOrders productionOrders;
    @Autowired SampleRequests samples;
    @Autowired SampleProductionProgress progress;
    @Autowired OrderProductionService orderProduction;
    @Autowired InventoryService inventory;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private ProductionFixtures fixtures;
    private UUID hcm;
    private CurrentUser sales;
    private CurrentUser printer;
    private CurrentUser storekeeper;

    @BeforeEach
    void setUp() {
        fixtures = new ProductionFixtures(storage, jdbc, tx, entityManager, progress);
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        com.stockflow.support.ConfirmedDesigns.stockBlanks(jdbc, tx, 60);
        sales = user(Role.SALES_STAFF, DataScope.ALL, Set.of());
        printer = user(Role.PRODUCTION_STAFF, DataScope.WAREHOUSE, Set.of(hcm));
        storekeeper = user(Role.WAREHOUSE_STAFF, DataScope.WAREHOUSE, Set.of(hcm));
    }

    private CurrentUser user(Role role, DataScope scope, Set<UUID> warehouses) {
        UUID id = tx.execute(s -> ReferenceRows.user(entityManager));
        return new CurrentUser(id, role.name() + "-" + id, Set.of(role), Set.of(), scope,
                warehouses.isEmpty() ? Set.of() : Set.of("HCM"), warehouses);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    // ------------------------------------------------------------------------------------------- fixtures

    /** An approved sample of a new design, so a release of that design may print (BR-PRD-05). */
    private UUID approvedSample(ProductionFixtures.Design design) {
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ProductionFixtures.BLANK, 1, null)));
        samples.submit(sales, draft.id(), hcm);
        fixtures.workshopFinished(draft.id());
        samples.markSent(sales, draft.id());
        return samples.approve(sales, draft.id()).id();
    }

    record OrderLine(UUID orderId, UUID lineId, List<UUID> holds, UUID productionOrderId) {
    }

    /**
     * A released order with one print line of {@code quantity}, its blanks held by a real {@code reserve} under
     * the order's id and attached to the line, pinned as a release pins them; then its ORDER production order,
     * created by the release listener's own service and passed through prepress against the real file.
     */
    private OrderLine releasedLine(int quantity, boolean pinned) throws Exception {
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
                    orderId, "SO-20991231-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(1_000_000)), DemoData.CUSTOMER_ID, UUID.randomUUID(), hcm, sales.userId());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency,
                        design_snapshot_id, design_checksum) VALUES (?, ?, ?, ?, 2000, 'VND', ?, ?)""",
                    lineId, orderId, ProductionFixtures.BLANK, quantity, design.snapshotId(), design.checksum());
        });
        List<UUID> holds = tx.execute(s -> {
            ReserveStockResult held = inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), BLANK, quantity,
                    orderId));
            held.reservationIds().forEach(id -> jdbc.update(
                    "INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES (?, ?)",
                    lineId, id));
            if (pinned) {
                inventory.pinReservations(orderId);
            }
            return held.reservationIds();
        });
        var release = new OrderLinesReleasedForProduction(orderId, "SO-IT", hcm, List.of(
                new OrderLinesReleasedForProduction.Line(lineId, ProductionFixtures.BLANK, quantity,
                        design.snapshotId(), design.checksum(), sample, null)));
        UUID productionOrderId = orderProduction.createFor(release).get(0).id();
        workflow.prepress(printer, productionOrderId, true, null);
        return new OrderLine(orderId, lineId, holds, productionOrderId);
    }

    private MaterialIssues.Result scan(UUID productionOrderId, UUID reservationId) {
        return issues.issue(storekeeper, productionOrderId,
                new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP, reservationId));
    }

    private Map<String, Object> hold(UUID reservationId) {
        return jdbc.queryForMap("""
                SELECT r.status, r.order_id, r.production_order_id, r.quantity, r.expires_at, s.location_code,
                       s.status AS stock_status
                  FROM inventory.stock_reservation r JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.id = ?""", reservationId);
    }

    private int printShopOnHand() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand), 0) FROM inventory.stock_item WHERE sku = ? AND location_code = ?""",
                Integer.class, BLANK.code(), PRINT_SHOP);
    }

    // ------------------------------------------------------------------------------------------- tests

    @Test
    @DisplayName("ORDER: each held lot is issued whole, the same hold still HELD for the order, ATP unchanged; READY -> MATERIAL_ISSUED once all 10 are issued")
    void orderBlanksAreIssuedWithTheirHold() throws Exception {
        OrderLine line = releasedLine(10, true);
        int atpBefore = inventory.availableToPromise(BLANK);
        int printShopBefore = printShopOnHand();

        // One scan per hold: each scan issues the next hold of the order, whichever it is.
        List<MaterialIssues.Result> results = new ArrayList<>();
        for (int scans = 0; scans < line.holds().size(); scans++) {
            results.add(scan(line.productionOrderId(), null));
        }

        assertThat(results).extracting(r -> r.issue().reservationId()).containsExactlyInAnyOrderElementsOf(line.holds());
        assertThat(results).extracting(r -> r.issue().toLocation()).containsOnly(PRINT_SHOP);
        assertThat(results.stream().mapToInt(r -> r.issue().quantity()).sum()).isEqualTo(10);
        ProductionOrders.Detail after = results.get(results.size() - 1).order();
        assertThat(after.summary().status()).isEqualTo(ProductionOrder.Status.MATERIAL_ISSUED);
        assertThat(after.issuedQuantity()).isEqualTo(10);
        assertThat(after.history()).extracting(ProductionOrders.StatusChange::toStatus)
                .containsExactly("PENDING_PREPRESS", "READY", "MATERIAL_ISSUED");

        for (UUID hold : line.holds()) {
            assertThat(hold(hold)).as("the order's own hold, moved with its goods")
                    .containsEntry("status", "HELD").containsEntry("order_id", line.orderId())
                    .containsEntry("location_code", PRINT_SHOP).containsEntry("stock_status", "IN_PRODUCTION");
            assertThat(hold(hold).get("production_order_id")).isNull();
        }
        assertThat(jdbc.queryForList("SELECT reservation_id FROM ordering.order_line_reservation WHERE order_line_id = ?",
                UUID.class, line.lineId())).as("the ids the order stored still find the holds")
                .containsExactlyInAnyOrderElementsOf(line.holds());
        assertThat(inventory.availableToPromise(BLANK)).as("nothing freed, nothing taken twice").isEqualTo(atpBefore);
        assertThat(printShopOnHand()).isEqualTo(printShopBefore + 10);

        List<Map<String, Object>> ledger = jdbc.queryForList("""
                SELECT movement_type, from_status, to_status, to_location_code, quantity FROM inventory.stock_movement
                 WHERE reference_type = 'PRODUCTION_ORDER' AND reference_id = ?""", line.productionOrderId());
        assertThat(ledger).hasSize(line.holds().size()).allSatisfy(row -> assertThat(row)
                .containsEntry("movement_type", "MOVE").containsEntry("from_status", "AVAILABLE")
                .containsEntry("to_status", "IN_PRODUCTION").containsEntry("to_location_code", PRINT_SHOP));
        assertThat(issues.list(storekeeper, line.productionOrderId())).hasSize(line.holds().size());

        assertThatThrownBy(() -> scan(line.productionOrderId(), null)).as("MATERIAL_ISSUED: nothing more to issue")
                .extracting(MaterialIssueIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
    }

    @Test
    @DisplayName("Q3: an order cancelled after the issue does not free the goods in the print shop: still held, never available to promise")
    void issuedGoodsAreNeverResold() throws Exception {
        OrderLine line = releasedLine(4, true);
        for (int scans = 0; scans < line.holds().size(); scans++) {
            scan(line.productionOrderId(), null);
        }
        int atpBefore = inventory.availableToPromise(BLANK);

        tx.executeWithoutResult(s -> line.holds().forEach(id -> inventory.release(id, "ORDER_CANCELLED")));

        assertThat(line.holds()).as("production closes them when it stops the job, unit by unit")
                .allSatisfy(id -> assertThat(hold(id)).containsEntry("status", "HELD")
                        .containsEntry("stock_status", "IN_PRODUCTION"));
        assertThat(inventory.availableToPromise(BLANK)).as("IN_PRODUCTION is never available to promise")
                .isEqualTo(atpBefore);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> inventory.reserve(new ReserveStockCommand(
                UUID.randomUUID(), BLANK, atpBefore + 1, Identifiers.newId()))))
                .as("a checkout cannot reach them").isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a retried scan returns the first issue and moves nothing; a hold of another order, a hold not pinned, a non-PRODUCTION area and an order before prepress are refused")
    void replayAndRefusals() throws Exception {
        OrderLine line = releasedLine(5, true);
        OrderLine other = releasedLine(2, true);
        assertThatThrownBy(() -> scan(line.productionOrderId(), other.holds().get(0)))
                .as("another order's blanks").extracting(MaterialIssueIntegrationTest::code)
                .isEqualTo(ErrorCode.RESERVATION_NOT_ISSUABLE);
        UUID request = UUID.randomUUID();
        MaterialIssues.Result first = issues.issue(storekeeper, line.productionOrderId(),
                new MaterialIssues.Issue(request, PRINT_SHOP, line.holds().get(0)));
        MaterialIssues.Result again = issues.issue(storekeeper, line.productionOrderId(),
                new MaterialIssues.Issue(request, PRINT_SHOP, line.holds().get(0)));
        assertThat(again.issue()).isEqualTo(first.issue());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement WHERE reference_type = 'PRODUCTION_ORDER'
                   AND reference_id = ?""", Integer.class, line.productionOrderId())).isEqualTo(1);

        assertThatThrownBy(() -> issues.issue(storekeeper, other.productionOrderId(),
                new MaterialIssues.Issue(UUID.randomUUID(), "HCM-PACK01", null)))
                .as("packing is not the print shop").extracting(MaterialIssueIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(hold(other.holds().get(0))).containsEntry("stock_status", "AVAILABLE");

        OrderLine unpaid = releasedLine(2, false);
        assertThatThrownBy(() -> scan(unpaid.productionOrderId(), null)).as("a hold that can still expire")
                .extracting(MaterialIssueIntegrationTest::code).isEqualTo(ErrorCode.RESERVATION_NOT_ISSUABLE);
        assertThat(productionOrders.get(sales, unpaid.productionOrderId()).issuedQuantity()).isZero();

        UUID pending = fixtures.sampleProductionOrder(samples.submit(sales, samples.create(sales,
                new SampleRequests.Create(DemoData.CUSTOMER_ID, null, new SampleRequests.Draft(
                        fixtures.confirmedDesign().snapshotId(), ProductionFixtures.BLANK, 1, null))).id(), hcm).id());
        assertThatThrownBy(() -> scan(pending, null)).as("prepress first")
                .extracting(MaterialIssueIntegrationTest::code).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
    }

    @Test
    @DisplayName("SAMPLE (Q1): submit holds the blanks for the production order at once, never for an invented order; the issue moves that hold")
    void sampleBlanksAreHeldFromSubmission() throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ProductionFixtures.BLANK, 3, null)));
        int atpBefore = inventory.availableToPromise(BLANK);

        UUID productionOrderId = samples.submit(sales, draft.id(), hcm).productionOrder().id();

        assertThat(inventory.availableToPromise(BLANK)).as("held at submit").isEqualTo(atpBefore - 3);
        List<Map<String, Object>> held = jdbc.queryForList("""
                SELECT order_id, quantity, expires_at, status FROM inventory.stock_reservation
                 WHERE production_order_id = ?""", productionOrderId);
        assertThat(held).isNotEmpty().allSatisfy(row -> {
            assertThat(row.get("order_id")).isNull();
            assertThat(row.get("expires_at")).as("a production hold does not expire").isNull();
            assertThat(row).containsEntry("status", "HELD");
        });
        assertThat(held.stream().mapToInt(row -> ((Number) row.get("quantity")).intValue()).sum()).isEqualTo(3);

        workflow.prepress(printer, productionOrderId, true, null);
        // One scan per hold; the assertions above found three units held, so there is at least one.
        MaterialIssues.Result result = scan(productionOrderId, null);
        for (int i = 1; i < held.size(); i++) {
            result = scan(productionOrderId, null);
        }
        assertThat(result.order().summary().status()).isEqualTo(ProductionOrder.Status.MATERIAL_ISSUED);
        assertThat(inventory.availableToPromise(BLANK)).as("the issue changes nothing more").isEqualTo(atpBefore - 3);
        assertThat(inventory.productionHolds(productionOrderId)).extracting(h -> h.locationCode()).containsOnly(PRINT_SHOP);
    }

    @Test
    @DisplayName("SAMPLE: too few blanks in the warehouse refuses the submit and leaves no production order and no hold")
    void sampleSubmitWithoutBlanksRollsBack() throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        int atp = inventory.availableToPromise(BLANK, "HCM");
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ProductionFixtures.BLANK, atp + 1, null)));

        assertThatThrownBy(() -> samples.submit(sales, draft.id(), hcm))
                .extracting(MaterialIssueIntegrationTest::code).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.production_order WHERE sample_request_id = ?",
                Integer.class, draft.id())).isZero();
        assertThat(inventory.availableToPromise(BLANK, "HCM")).isEqualTo(atp);
    }

    @Test
    @DisplayName("two scans of the last hold at once: one issues it, the other is refused; the same scan twice at once issues once")
    void concurrentScans() throws Exception {
        OrderLine line = releasedLine(6, true);
        UUID last = line.holds().get(line.holds().size() - 1);
        for (UUID hold : line.holds().subList(0, line.holds().size() - 1)) {
            scan(line.productionOrderId(), hold);
        }

        List<Object> outcomes = race(List.of(
                () -> scan(line.productionOrderId(), last),
                () -> scan(line.productionOrderId(), last)));
        assertThat(outcomes).filteredOn(MaterialIssues.Result.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(code((Throwable) e)).isIn(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                        ErrorCode.RESERVATION_NOT_ISSUABLE));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production.production_material_issue WHERE reservation_id = ?",
                Integer.class, last)).isEqualTo(1);

        OrderLine second = releasedLine(3, true);
        UUID request = UUID.randomUUID();
        Callable<Object> same = () -> issues.issue(storekeeper, second.productionOrderId(),
                new MaterialIssues.Issue(request, PRINT_SHOP, second.holds().get(0)));
        List<Object> twins = race(List.of(same, same));
        assertThat(twins).allSatisfy(o -> assertThat(o).isInstanceOf(MaterialIssues.Result.class));
        assertThat(((MaterialIssues.Result) twins.get(0)).issue()).isEqualTo(((MaterialIssues.Result) twins.get(1)).issue());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement WHERE reference_type = 'PRODUCTION_ORDER'
                   AND reference_id = ?""", Integer.class, second.productionOrderId())).isEqualTo(1);
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
