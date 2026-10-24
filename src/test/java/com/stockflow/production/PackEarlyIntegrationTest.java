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
import com.stockflow.fulfillment.internal.service.Dispatching;
import com.stockflow.fulfillment.internal.service.Packing;
import com.stockflow.fulfillment.internal.service.PickLists;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.service.PartialShipments;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * PACK-EARLY and PACK-PARTIAL (lead's decisions 2026-10-21) through the services, against a real database: a mixed
 * order's stock lines are picked, packed and handed over while its print line is printed - only when Sales allowed
 * partial shipments on the order - and the print line follows when production completes, or is closed and its job
 * stops. The sales order is rows with real holds, as in {@code PrintedGoodsAtPackingIntegrationTest}; production,
 * picking, packing and the handover run through their services.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class PackEarlyIntegrationTest {

    private static final String PRINT_SHOP = "HCM-PRD01";
    private static final String PACKING = "HCM-PACK01";
    private static final Sku BLANK = new Sku(ProductionFixtures.BLANK);
    /** KIT-FORCE: the blank ships as 2 boxes; packed in an ordinary package, it needs a reason. */
    private static final String ONE_CARTON = "test: packed as one carton";
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired com.stockflow.common.security.RoleAuthorizationLookup grants;
    @Autowired PrintRuns runs;
    @Autowired MaterialIssues issues;
    @Autowired ProductionWorkflow workflow;
    @Autowired ProductionOrders productionOrders;
    @Autowired SampleRequests samples;
    @Autowired SampleProductionProgress progress;
    @Autowired OrderProductionService orderProduction;
    @Autowired OrderService salesOrders;
    @Autowired PartialShipments partialShipments;
    @Autowired InventoryService inventory;
    @Autowired Dispatching dispatching;
    @Autowired com.stockflow.fulfillment.internal.service.LoadPlans loadPlans;
    @Autowired com.stockflow.order.internal.service.CustomerOrders customerOrders;
    @Autowired FulfillmentService fulfillment;
    @Autowired Packing packing;
    @Autowired PickLists pickLists;
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
        coordinator = user(Role.ORDER_COORDINATOR);
        picker = assignable(Role.WAREHOUSE_STAFF);
    }

    private CurrentUser user(Role role) {
        return as(tx.execute(s -> ReferenceRows.user(entityManager)), role);
    }

    private CurrentUser as(UUID id, Role role) {
        return new CurrentUser(id, role.name() + "-" + id, Set.of(role),
                grants.resolve(List.of(role.authority())).permissions(), DataScope.WAREHOUSE, Set.of("HCM"), Set.of(hcm));
    }

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

    record Mixed(UUID orderId, String orderNumber, UUID printLine, UUID stockLine, UUID productionOrderId) {
    }

    /**
     * A paid mixed order released from HCM: {@code printed} units printed to its design and {@code stocked} plain units
     * of the same blank SKU from storage, both at 2 000 VND. IN_PRODUCTION, its production order prepared and its
     * blanks issued - not printing yet.
     */
    private Mixed inProduction(int printed, int stocked) throws Exception {
        return inProduction(printed, stocked, 2000, 2000);
    }

    private Mixed inProduction(int printed, int stocked, long printPrice, long stockPrice) throws Exception {
        ProductionFixtures.Design design = fixtures.confirmedDesign();
        SampleRequests.Sample draft = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), ProductionFixtures.BLANK, 1, null)));
        samples.submit(sales, draft.id(), hcm);
        fixtures.workshopFinished(draft.id());
        samples.markSent(sales, draft.id());
        UUID sample = samples.approve(sales, draft.id()).id();

        UUID orderId = Identifiers.newId();
        UUID printLine = Identifiers.newId();
        UUID stockLine = Identifiers.newId();
        long total = printed * printPrice + stocked * stockPrice;
        String number = "SO-20991231-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, version, created_at, paid_amount, paid_in_full_at, warehouse_id,
                        released_at, released_by)
                    VALUES (?, ?, ?, ?, 'IN_PRODUCTION', ?, 'VND', NOW(), 0, NOW(), ?, NOW(), ?, NOW(), ?)""",
                    orderId, number, DemoData.CUSTOMER_ID, UUID.randomUUID(), total, total, hcm, sales.userId());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency,
                        design_snapshot_id, design_checksum) VALUES (?, ?, ?, ?, ?, 'VND', ?, ?)""",
                    printLine, orderId, ProductionFixtures.BLANK, printed, printPrice, design.snapshotId(),
                    design.checksum());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                    VALUES (?, ?, ?, ?, ?, 'VND')""", stockLine, orderId, ProductionFixtures.BLANK, stocked, stockPrice);
            UUID printedHold = inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), BLANK, printed, orderId))
                    .reservationIds().getFirst();
            inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), BLANK, stocked, orderId));
            inventory.pinReservations(orderId);
            // As the release does (SCRUM-152): each SKU reservation becomes allocations on HCM bins, which its line
            // then holds instead.
            inventory.allocate(new com.stockflow.inventory.api.AllocateStockCommand(orderId, "HCM")).allocations()
                    .forEach(allocation -> allocation.holdIds().forEach(id -> jdbc.update(
                            "INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES (?, ?)",
                            allocation.reservationId().equals(printedHold) ? printLine : stockLine, id)));
        });
        UUID productionOrderId = orderProduction.createFor(new OrderLinesReleasedForProduction(orderId, number, hcm,
                List.of(new OrderLinesReleasedForProduction.Line(printLine, ProductionFixtures.BLANK, printed,
                        design.snapshotId(), design.checksum(), sample, null)))).get(0).id();
        workflow.prepress(printer, productionOrderId, true, null);
        for (int scans = 0; scans < 10; scans++) {
            if (issues.issue(storekeeper, productionOrderId, new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP,
                    null)).order().summary().status() != ProductionOrder.Status.READY) {
                break;
            }
        }
        return new Mixed(orderId, number, printLine, stockLine, productionOrderId);
    }

    /** The print run and its QC: the production order completes and hands its goods to packing. */
    private void finishProduction(Mixed order, int printed) {
        runs.startPrinting(printer, order.productionOrderId());
        runs.recordOutput(printer, order.productionOrderId(), printed, List.of());
        ProductionOrders.Detail done = runs.recordQc(inspector, order.productionOrderId(), printed, 0, null, PACKING,
                sampleOf(order.productionOrderId()));
        assertThat(done.summary().status()).isEqualTo(ProductionOrder.Status.COMPLETED);
    }

    /** The coordinator opens the task, the picker picks every pick line (the stock line only) and completes. */
    private UUID pickedStock(Mixed order) {
        UUID task = fulfillment.createTask(order.orderId(), picker.userId(), coordinator).taskId();
        fulfillment.startPicking(task, picker);
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT id, location_code, allocated_qty, order_line_id FROM fulfillment.pick_line WHERE pick_id = ?",
                task);
        assertThat(lines).as("only the stock line is picked").isNotEmpty()
                .allSatisfy(line -> assertThat(line.get("order_line_id")).isEqualTo(order.stockLine()));
        for (Map<String, Object> line : lines) {
            pickLists.confirm(task, (UUID) line.get("id"), (String) line.get("location_code"), BLANK.code(),
                    (Integer) line.get("allocated_qty"), picker);
        }
        fulfillment.completePicking(task, picker);
        return task;
    }

    private UUID sealed(UUID task, UUID lineId, int quantity) {
        UUID parcel = packing.openPackage(task, null, ONE_CARTON, picker).packageId();
        packing.scan(task, parcel, new Packing.Scan(UUID.randomUUID(), BLANK.code(), null, null, quantity, lineId),
                picker);
        packing.seal(task, parcel, new Packing.Seal(new BigDecimal("2"), new BigDecimal("30"), new BigDecimal("30"),
                new BigDecimal("30"), null, null, null), picker);
        return parcel;
    }

    /** The dispatcher loads the shipments on a GHN load, scans their parcels and the driver signs (09 §4.1). */
    private void handOver(UUID... shipmentIds) {
        UUID ghn = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHN'", UUID.class);
        Dispatching.LoadDetail load = dispatching.open(new Dispatching.OpenLoad(UUID.randomUUID(), hcm, ghn,
                List.of(shipmentIds), "51C-999.99", "Driver", "0900000000"), picker);
        for (UUID shipmentId : shipmentIds) {
            for (String label : jdbc.queryForList("SELECT label_code FROM fulfillment.package WHERE shipment_id = ?",
                    String.class, shipmentId)) {
                load = dispatching.scan(load.loadId(), UUID.randomUUID(), label, picker);
            }
        }
        dispatching.handOver(load.loadId(), new Dispatching.Handover(load.version(), "Nguyen Van Tai", "51C-999.99",
                "0900000000", "MF-1", true, null), picker);
    }

    private OrderSummary order(Mixed order) {
        return salesOrders.findById(order.orderId()).orElseThrow();
    }

    private boolean awaiting(Mixed order) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT awaiting_production FROM ordering.customer_order WHERE id = ?", Boolean.class, order.orderId()));
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    // ------------------------------------------------------------------------------------------- tests

    @Test
    @DisplayName("allowed: the stock is picked, packed and handed over while the print line is printed (IN_PRODUCTION → "
            + "PARTIALLY_FULFILLED, awaiting production); production completes → the prints are packed and handed "
            + "over (PACKED → SHIPPED) → both delivered (DELIVERED); the decision is in the order's history")
    void stockShipsFirstThenThePrints() throws Exception {
        Mixed order = inProduction(4, 3);
        partialShipments.decide(order.orderId(), true, "Khách nhận hàng sẵn trước", sales.userId());
        assertThat(order(order).lifecycle().allowPartialShipment()).isTrue();
        assertThat(order(order).lifecycle().printLinesReady()).isFalse();

        UUID task = pickedStock(order);
        assertThat(order(order).status()).as("picked in parallel").isEqualTo(OrderStatus.IN_PRODUCTION);
        packing.start(task, picker);
        UUID tooEarly = packing.openPackage(task, null, ONE_CARTON, picker).packageId();
        assertThat(code(catchThrowable(() -> packing.scan(task, tooEarly, new Packing.Scan(UUID.randomUUID(),
                BLANK.code(), null, null, 1, order.printLine()), picker))))
                .as("the prints are not at packing yet").isNotNull();
        packing.voidPackage(task, tooEarly, picker);
        sealed(task, order.stockLine(), 3);
        assertThat(code(catchThrowable(() -> fulfillment.completePacking(task, picker))))
                .isEqualTo(ErrorCode.PACKING_INCOMPLETE);
        FulfillmentTask stock = fulfillment.completePacking(task,
                new PackingCompletion(UUID.randomUUID(), true, "Hàng sẵn đi trước"), picker);
        assertThat(order(order).status()).isEqualTo(OrderStatus.IN_PRODUCTION);

        handOver(stock.shipmentId());
        assertThat(order(order).status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(awaiting(order)).isTrue();
        assertThat(order(order).lifecycle().printLinesReady()).isFalse();

        finishProduction(order, 4);
        Await.until("the prints are at packing", ASYNC, () -> order(order).lifecycle().printLinesReady());
        assertThat(order(order).status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(awaiting(order)).isFalse();

        sealed(task, order.printLine(), 4);
        FulfillmentTask prints = fulfillment.completePacking(task, picker);
        assertThat(prints.packStatus()).isEqualTo("PACKED");
        assertThat(order(order).status()).isEqualTo(OrderStatus.PACKED);
        handOver(prints.shipmentId());
        assertThat(order(order).status()).isEqualTo(OrderStatus.SHIPPED);

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> {
            salesOrders.recordShipmentDelivered(order.orderId(), stock.shipmentId(), coordinator.userId());
            salesOrders.recordShipmentDelivered(order.orderId(), prints.shipmentId(), coordinator.userId());
        });
        assertThat(order(order).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(jdbc.queryForList("SELECT from_status || '>' || to_status FROM ordering.order_status_history "
                + "WHERE order_id = ? ORDER BY occurred_at, created_at", String.class, order.orderId()))
                .containsSubsequence("IN_PRODUCTION>PARTIALLY_FULFILLED", "PARTIALLY_FULFILLED>PACKED",
                        "PACKED>SHIPPED", "SHIPPED>DELIVERED");
        assertThat(partialShipments.decisions(order.orderId())).singleElement().satisfies(d -> {
            assertThat(d.allowed()).isTrue();
            assertThat(d.orderStatus()).isEqualTo(OrderStatus.IN_PRODUCTION);
            assertThat(d.decidedBy()).isEqualTo(sales.userId());
        });
    }

    @Test
    @DisplayName("not allowed: the stock is still picked in parallel, but packing ahead of the prints is refused (409 "
            + "PACK_PARTIAL_NOT_ALLOWED); production completes → READY_TO_FULFILL → the whole order packed (PACKED)")
    void withoutTheFlagTheOrderShipsWhole() throws Exception {
        Mixed order = inProduction(2, 3);
        UUID task = pickedStock(order);
        assertThat(code(catchThrowable(() -> packing.start(task, picker))))
                .isEqualTo(ErrorCode.PACK_PARTIAL_NOT_ALLOWED);

        finishProduction(order, 2);
        Await.until("ready to fulfil", ASYNC, () -> order(order).status() == OrderStatus.READY_TO_FULFILL);
        packing.start(task, picker);
        UUID parcel = sealed(task, order.stockLine(), 3);
        assertThat(code(catchThrowable(() -> fulfillment.completePacking(task,
                new PackingCompletion(UUID.randomUUID(), true, "only the stock"), picker))))
                .as("a part of the order, Sales did not allow it").isEqualTo(ErrorCode.PACK_PARTIAL_NOT_ALLOWED);
        packing.scan(task, packing.openPackage(task, null, ONE_CARTON, picker).packageId(), new Packing.Scan(UUID.randomUUID(),
                BLANK.code(), null, null, 2, order.printLine()), picker);
        UUID second = jdbc.queryForObject("SELECT id FROM fulfillment.package WHERE pack_id = (SELECT id FROM "
                + "fulfillment.pack WHERE pick_id = ?) AND status = 'OPEN'", UUID.class, task);
        packing.seal(task, second, new Packing.Seal(new BigDecimal("1"), new BigDecimal("20"), new BigDecimal("20"),
                new BigDecimal("20"), null, null, null), picker);
        FulfillmentTask whole = fulfillment.completePacking(task, picker);
        assertThat(whole.packStatus()).isEqualTo("PACKED");
        assertThat(parcel).isNotNull();
        assertThat(order(order).status()).as("READY_TO_FULFILL → PACKED (17 §4.2.6)").isEqualTo(OrderStatus.PACKED);
        handOver(whole.shipmentId());
        assertThat(order(order).status()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("the print line closed after the stock shipped early: the order shows its shipment (SHIPPED), its "
            + "production order stops (CANCELLED, blanks back), nothing is awaited any more")
    void printLineClosedAfterTheEarlyShipment() throws Exception {
        Mixed order = inProduction(2, 3);
        partialShipments.decide(order.orderId(), true, null, sales.userId());
        UUID task = pickedStock(order);
        packing.start(task, picker);
        sealed(task, order.stockLine(), 3);
        FulfillmentTask stock = fulfillment.completePacking(task,
                new PackingCompletion(UUID.randomUUID(), true, "Hàng sẵn đi trước"), picker);
        handOver(stock.shipmentId());
        assertThat(order(order).status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> salesOrders.closeRemainder(order.orderId(),
                CancellationReasonCode.CUSTOMER_REQUEST, "Khách không lấy hàng in nữa", sales.userId()));

        assertThat(order(order).status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(awaiting(order)).isFalse();
        assertThat(order(order).lifecycle().effectiveTotal()).isEqualByComparingTo("6000");
        Await.until("the production order stops", ASYNC, () -> "CANCELLED".equals(jdbc.queryForObject(
                "SELECT status FROM production.production_order WHERE id = ?", String.class, order.productionOrderId())));
        assertThat(code(catchThrowable(() -> runs.startPrinting(printer, order.productionOrderId()))))
                .as("nothing more is printed for it").isNotNull();
    }

    @Test
    @DisplayName("a handover of part of an order that does not allow it is refused (409 ORDER_PARTIAL_SHIPMENT_NOT_"
            + "ALLOWED) even when the shipment was packed while it was allowed; Sales' decision is locked once shipped")
    void partialHandoverNeedsTheFlag() throws Exception {
        Mixed order = inProduction(2, 3);
        partialShipments.decide(order.orderId(), true, null, sales.userId());
        UUID task = pickedStock(order);
        packing.start(task, picker);
        sealed(task, order.stockLine(), 3);
        FulfillmentTask stock = fulfillment.completePacking(task,
                new PackingCompletion(UUID.randomUUID(), true, "Hàng sẵn đi trước"), picker);
        partialShipments.decide(order.orderId(), false, "Khách muốn nhận một lần", sales.userId());

        UUID ghn = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHN'", UUID.class);
        // Review of #150, item 5: refused when the shipment is put on the manifest, not at the signature.
        assertThat(code(catchThrowable(() -> dispatching.open(new Dispatching.OpenLoad(UUID.randomUUID(), hcm, ghn,
                List.of(stock.shipmentId()), "51C-999.99", "Driver", "0900000000"), picker))))
                .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
        Dispatching.LoadDetail load = dispatching.open(new Dispatching.OpenLoad(UUID.randomUUID(), hcm, ghn, List.of(),
                "51C-999.99", "Driver", "0900000000"), picker);
        assertThat(code(catchThrowable(() -> dispatching.addShipments(load.loadId(), List.of(stock.shipmentId()),
                picker)))).isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED);
        assertThat(order(order).status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.shipment WHERE id = ?", String.class,
                stock.shipmentId())).isEqualTo("READY_TO_DISPATCH");
        assertThat(partialShipments.decisions(order.orderId())).extracting(d -> d.allowed()).containsExactly(true, false);

        finishProduction(order, 2);
        Await.until("ready to fulfil", ASYNC, () -> order(order).status() == OrderStatus.READY_TO_FULFILL);
        sealed(task, order.printLine(), 2);
        FulfillmentTask prints = fulfillment.completePacking(task, picker);
        Dispatching.LoadDetail both = dispatching.addShipments(load.loadId(), List.of(stock.shipmentId(),
                prints.shipmentId()), picker);
        for (String label : jdbc.queryForList("SELECT label_code FROM fulfillment.package WHERE shipment_id IN (?, ?)",
                String.class, stock.shipmentId(), prints.shipmentId())) {
            both = dispatching.scan(both.loadId(), UUID.randomUUID(), label, picker);
        }
        dispatching.handOver(both.loadId(), new Dispatching.Handover(both.version(), "Nguyen Van Tai", "51C-999.99",
                "0900000000", "MF-1", true, null), picker);
        assertThat(order(order).status()).as("both in one signature: nothing left behind").isEqualTo(OrderStatus.SHIPPED);
        assertThat(code(catchThrowable(() -> partialShipments.decide(order.orderId(), true, null, sales.userId()))))
                .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_LOCKED);
    }

    @Test
    @DisplayName("review of #150, item 1: prepaid 10M (2M stock, 8M prints), stock shipped early, prints printed, the "
            + "rest closed with a 3M fee: 3M kept, 5M asked back, the 4 printed units asked to be scrapped")
    void closedPrintsKeepTheFeeAndAreScrapped() throws Exception {
        Mixed order = inProduction(4, 10, 2_000_000, 200_000);
        partialShipments.decide(order.orderId(), true, null, sales.userId());
        UUID task = pickedStock(order);
        packing.start(task, picker);
        sealed(task, order.stockLine(), 10);
        FulfillmentTask stock = fulfillment.completePacking(task,
                new PackingCompletion(UUID.randomUUID(), true, "Hàng sẵn đi trước"), picker);
        handOver(stock.shipmentId());
        finishProduction(order, 4);
        Await.until("the prints are at packing", ASYNC, () -> order(order).lifecycle().printLinesReady());

        OrderSummary closed = WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, () -> salesOrders.closeRemainder(
                order.orderId(), CancellationReasonCode.CUSTOMER_REQUEST, "Khách không lấy ly in",
                new com.stockflow.order.api.CancellationFee(new BigDecimal("3000000"), "Phí in",
                        "4 ly đã in theo thiết kế"), sales.userId()));

        assertThat(closed.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(closed.lifecycle().amountDue()).isZero();
        assertThat(jdbc.queryForMap("SELECT remainder_refund_amount::bigint AS refund, remainder_fee_amount::bigint "
                + "AS fee FROM ordering.customer_order WHERE id = ?", order.orderId()))
                .containsEntry("refund", 5_000_000L).containsEntry("fee", 3_000_000L);
        Await.until("the printed units are asked to be scrapped", ASYNC, () -> jdbc.queryForObject("""
                SELECT COALESCE(SUM(-quantity_delta), 0)::int FROM inventory.stock_adjustment
                 WHERE reason_code = 'SCRAP' AND status = 'PENDING_APPROVAL' AND note LIKE ?""", Integer.class,
                "%" + order.orderNumber() + "%") == 4);
    }

    @Test
    @DisplayName("review of #150, item 4: stock packed ahead of the prints, nothing handed over: the order is still "
            + "cancelled with the printing fee - its shipment cancelled, parcels voided, picked stock to put back, "
            + "the running print job held")
    void packedAheadOfThePrintsIsCancelledWithAFee() throws Exception {
        Mixed order = inProduction(2, 3);
        partialShipments.decide(order.orderId(), true, null, sales.userId());
        UUID task = pickedStock(order);
        packing.start(task, picker);
        sealed(task, order.stockLine(), 3);
        FulfillmentTask stock = fulfillment.completePacking(task,
                new PackingCompletion(UUID.randomUUID(), true, "Hàng sẵn đi trước"), picker);
        runs.startPrinting(printer, order.productionOrderId());

        OrderSummary cancelled = WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, () -> salesOrders.cancel(
                order.orderId(), new com.stockflow.order.api.CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST,
                        "Khách huỷ", null, sales.userId(), new com.stockflow.order.api.CancellationFee(
                        new BigDecimal("4000"), "Phí in", "2 ly đang in"))));

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancellation().retainedAmount()).isEqualByComparingTo("4000");
        Await.until("the shipment is cancelled", ASYNC, () -> "CANCELLED".equals(jdbc.queryForObject(
                "SELECT status FROM fulfillment.shipment WHERE id = ?", String.class, stock.shipmentId())));
        assertThat(jdbc.queryForList("SELECT status FROM fulfillment.package WHERE shipment_id = ?", String.class,
                stock.shipmentId())).containsOnly("VOIDED");
        Await.until("the picked stock waits to be put back", ASYNC, () -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT needs_put_back FROM fulfillment.pick WHERE id = ?", Boolean.class, task)));
        Await.until("the print job is held", ASYNC, () -> "ON_HOLD".equals(jdbc.queryForObject(
                "SELECT status FROM production.production_order WHERE id = ?", String.class,
                order.productionOrderId())));
    }

    @Test
    @DisplayName("SCRUM-392 (review of #162): a pack-early FTL order - its stock in one shipment on two 1.25 t trucks. "
            + "Truck 1 leaves: goods_left_at set, staff cancellation and the customer's cancellation request are 409 "
            + "ORDER_NOT_CANCELLABLE, partial shipment cannot be turned off (409 ORDER_PARTIAL_SHIPMENT_LOCKED); the "
            + "last truck signs: PARTIALLY_FULFILLED; the print line closed: SHIPPED")
    void packEarlyFtlOrderIsNotCancelledOnceATruckLeft() throws Exception {
        Mixed order = inProduction(2, 3);
        partialShipments.decide(order.orderId(), true, null, sales.userId());
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET delivery_mode = 'FULL_TRUCKLOAD' "
                + "WHERE id = ?", order.orderId()));
        UUID task = pickedStock(order);
        packing.start(task, picker);
        for (int units : new int[] {1, 2}) {
            UUID parcel = packing.openPackage(task, null, ONE_CARTON, picker).packageId();
            packing.scan(task, parcel, new Packing.Scan(UUID.randomUUID(), BLANK.code(), null, null, units,
                    order.stockLine()), picker);
            // Sofa-sized: a 1.25 t truck takes one.
            packing.seal(task, parcel, new Packing.Seal(new BigDecimal("52.5"), new BigDecimal("215"),
                    new BigDecimal("95"), new BigDecimal("90"), null, null, null), picker);
        }
        UUID shipment = fulfillment.completePacking(task, new PackingCompletion(UUID.randomUUID(), true,
                "Hàng sẵn đi trước"), picker).shipmentId();

        UUID truck = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-1T25'", UUID.class);
        var plan = loadPlans.create(new com.stockflow.fulfillment.internal.service.LoadPlans.CreatePlan(
                UUID.randomUUID(), hcm, List.of(shipment), List.of(truck), "SINGLE_TYPE", null), picker);
        plan = loadPlans.calculate(plan.loadPlanId(), plan.version(), picker);
        plan = loadPlans.confirm(plan.loadPlanId(), plan.version(), picker);
        assertThat(plan.vehicles()).hasSize(2);
        UUID ghn = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHN'", UUID.class);
        List<UUID> trucks = plan.vehicles().stream().map(v -> v.load().loadId()).toList();

        // Packed ahead of the prints, nothing left yet: the portal still offers a cancellation request (#150).
        assertThat(customerOrders.myOrder(DemoData.CUSTOMER_ID, order.orderId()).allowedActions()
                .requestCancellation()).isTrue();

        // Truck 1 leaves with part of the shipment.
        signed(trucks.get(0), ghn);
        assertThat(customerOrders.myOrder(DemoData.CUSTOMER_ID, order.orderId()).allowedActions()
                .requestCancellation()).as("goods left: no cancellation request offered").isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.shipment WHERE id = ?", String.class, shipment))
                .isEqualTo("READY_TO_DISPATCH");
        assertThat(jdbc.queryForObject("SELECT goods_left_at IS NOT NULL FROM ordering.customer_order WHERE id = ?",
                Boolean.class, order.orderId())).isTrue();
        assertThat(order(order).status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        assertThat(code(catchThrowable(() -> WithCurrentUser.call(TestUsers.admin(), DataScope.ALL,
                () -> salesOrders.cancel(order.orderId(), new com.stockflow.order.api.CancelOrderCommand(
                        CancellationReasonCode.CUSTOMER_REQUEST, "Khách huỷ", null, sales.userId(),
                        new com.stockflow.order.api.CancellationFee(new BigDecimal("4000"), "Phí in", "2 ly")))))))
                .as("staff cancellation").isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(code(catchThrowable(() -> salesOrders.cancelOwn(order.orderId(), DemoData.CUSTOMER_ID,
                CancellationReasonCode.CUSTOMER_REQUEST, "Khách huỷ", DemoData.CUSTOMER_ID))))
                .as("the customer's cancellation request").isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(code(catchThrowable(() -> partialShipments.decide(order.orderId(), false, null, sales.userId()))))
                .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_LOCKED);

        // The last truck signs: the shipment is handed over, the prints are still to come.
        signed(trucks.get(1), ghn);
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.shipment WHERE id = ?", String.class, shipment))
                .isEqualTo("HANDED_OVER");
        assertThat(order(order).status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> salesOrders.closeRemainder(order.orderId(),
                CancellationReasonCode.CUSTOMER_REQUEST, "Khách không lấy hàng in nữa", sales.userId()));
        assertThat(order(order).status()).isEqualTo(OrderStatus.SHIPPED);
    }

    /** A load plan's vehicle booked, loaded with every parcel shown on it, and signed (SCRUM-392). */
    private void signed(UUID loadId, UUID carrier) {
        dispatching.book(loadId, new Dispatching.Booking(carrier, "51C-999.99", "Driver", null), picker);
        Dispatching.LoadDetail load = dispatching.startLoading(loadId, picker);
        for (Dispatching.LoadShipment s : load.shipments()) {
            for (Dispatching.LoadParcel p : s.packages()) {
                load = dispatching.scan(loadId, UUID.randomUUID(), p.labelCode(), picker);
            }
        }
        dispatching.handOver(loadId, new Dispatching.Handover(load.version(), "Nguyen Van Tai", null, null, null, true,
                null), picker);
    }

    /** The approved sample a QC round of this order names (BR-PRD-05, SCRUM-487); null when it has none. */
    private UUID sampleOf(UUID productionOrderId) {
        return jdbc.queryForObject("SELECT approved_sample_id FROM production.production_order WHERE id = ?",
                UUID.class, productionOrderId);
    }
}
