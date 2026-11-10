package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.service.CartonQuarantineService;
import com.stockflow.inventory.internal.service.PutawayService;
import com.stockflow.inventory.internal.service.PutawayService.TaskView;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.support.CartonRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import com.stockflow.warehouse.api.PutawaySource;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Team plan 4D-1 against PostgreSQL (kltn-docs 20 §4.8, BR-01, BR-14, BR-21; 05 §4.4, BR-01, BR-14). Cartons of ten
 * stand on a bin of a warehouse of their own, beside a QUARANTINE area and a RECEIVING one: a warehouse manager
 * quarantines a free carton whole (one STATUS_CHANGE line, out of ATP, never allocated), but not a held one; releasing
 * it opens a QUARANTINE_RELEASE putaway task while the carton stays QUARANTINE; putting it away makes it AVAILABLE and
 * announces no receipt's putaway; or it is written off whole by an approved DAMAGED adjustment of its LPN - one of the
 * two, never both.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
class CartonQuarantineIntegrationTest {

    private static final int FACTOR = 10;
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(System.nanoTime() % 400_000 + 500_000);

    @Autowired CartonQuarantineService quarantine;
    @Autowired PutawayService putaway;
    @Autowired PutawayTasks putawayTasks;
    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
    @Autowired RecordedEvents.Recorder recorded;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String prefix;
    private String bin;
    private String otherBin;
    private String quarantineArea;
    private String receiving;
    private UUID manager;
    private UUID approver;
    private int shelfX;

    @BeforeEach
    void setUp() {
        sku = "QRN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        prefix = "Q" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        quarantineArea = prefix + "-QC01";
        receiving = prefix + "-RCV01";
        UUID warehouse = Identifiers.newId();
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Quarantine fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Quarantine fixture");
            jdbc.update("update inventory.inventory_items set removal_strategy='FIFO', policy_configured=true"
                    + " where sku=?", sku);
            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                    + " map_height, version, created_at) VALUES (?, 'Quarantine', 'ACTIVE', ?, 'Test address',"
                    + " 'M', 50, 50, 0, NOW())", warehouse, prefix);
            area(warehouse, "QC01", quarantineArea, "QUARANTINE", 1);
            area(warehouse, "RCV01", receiving, "RECEIVING", 8);
        });
        bin = bin(warehouse, "A01");
        otherBin = bin(warehouse, "A02");
        recorded.clear();
    }

    private void area(UUID warehouseId, String code, String locationCode, String type, int x) {
        UUID location = Identifiers.newId();
        jdbc.update("INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code,"
                + " is_pickable, is_putaway_target, status) VALUES (?, ?, 'AREA', ?, FALSE, FALSE, 'ACTIVE')",
                location, warehouseId, locationCode);
        jdbc.update("INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,"
                + " rotation, is_obstacle, status) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 5, 5, 0, FALSE, 'ACTIVE')",
                Identifiers.newId(), warehouseId, location, code, type, code, x);
    }

    /** A pickable bin on a shelf of its own, a NORMAL putaway target with room for 500 units. */
    private String bin(UUID warehouseId, String name) {
        String code = prefix + "-" + name + "-1-A";
        tx.executeWithoutResult(s -> {
            UUID zone = Identifiers.newId();
            UUID shelf = Identifiers.newId();
            UUID level = Identifiers.newId();
            UUID location = Identifiers.newId();
            int x = 5 + 12 * shelfX++;
            jdbc.update("INSERT INTO warehouse.zone (id, warehouse_id, name, color) VALUES (?, ?, ?, '#4F81BD')",
                    zone, warehouseId, "Zone " + name);
            jdbc.update("""
                    INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation,
                                                 is_obstacle, pick_north, pick_east, pick_south, pick_west, status)
                    VALUES (?, ?, ?, ?, 'Shelf', ?, 30, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'ACTIVE')""", shelf, warehouseId, zone, name, x);
            jdbc.update("""
                    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                    VALUES (?, ?, 1, 0, 1.4, 500)""", level, shelf);
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, max_weight, is_pickable,
                                                            is_putaway_target, status)
                    VALUES (?, ?, 'BIN', ?, 500, TRUE, TRUE, 'ACTIVE')""", location, warehouseId, code);
            jdbc.update("""
                    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                    VALUES (?, ?, ?, 'A', 0, 0, 5, 1.2, 0, 'PALLET')""", Identifiers.newId(), level, location);
        });
        return code;
    }

    /** One sealed AVAILABLE carton of ten on {@link #bin}, received on {@code day} of January 2026. */
    private String carton(int day) {
        return tx.execute(s -> CartonRows.stock(jdbc, sku, bin, 1, FACTOR, null,
                Instant.parse("2026-01-%02dT00:00:00Z".formatted(day)))).getFirst().lpn();
    }

    private UUID heldOrder(int quantity) {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status,
                        total_amount, currency, placed_at, version, created_at, contract_signed_at)
                    VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), NOW())""", order,
                    "SO-20991227-%06d".formatted(ORDER_NUMBERS.incrementAndGet()), DemoData.CUSTOMER_ID,
                    UUID.randomUUID());
            inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), quantity, order));
            inventory.pinReservations(order);
        });
        return order;
    }

    private List<HoldView> release(UUID order) {
        List<UUID> holds = tx.execute(s -> inventory.allocate(new AllocateStockCommand(order, prefix)).allocations()
                .stream().flatMap(allocation -> allocation.holdIds().stream()).toList());
        return inventory.holds(holds);
    }

    private StockPackageView quarantine(String lpn, String to) {
        return quarantine.quarantine(lpn, to, "Crushed corner", manager);
    }

    private BusinessException refusal(Runnable call) {
        return catchThrowableOfType(BusinessException.class, call::run);
    }

    private Map<String, Object> cartonRow(String lpn) {
        return jdbc.queryForMap("""
                SELECT p.status AS carton, p.location_code AS location, i.status AS stock, i.on_hand, i.reserved
                  FROM inventory.stock_package p JOIN inventory.stock_item i ON i.stock_package_id = p.id
                 WHERE p.lpn = ?""", lpn);
    }

    private int atp() {
        return inventory.availableToPromise(new Sku(sku));
    }

    @Test
    @DisplayName("a free carton is quarantined whole: one STATUS_CHANGE line with the reason, QUARANTINE, out of ATP")
    void freeCartonIsQuarantinedWhole() {
        String lpn = carton(2);
        assertThat(atp()).isEqualTo(10);

        StockPackageView view = quarantine(lpn.toLowerCase(Locale.ROOT), quarantineArea);

        assertThat(view.locationCode()).isEqualTo(quarantineArea);
        assertThat(view.status()).isEqualTo("IN_STOCK");
        assertThat(cartonRow(lpn)).containsEntry("carton", "IN_STOCK").containsEntry("location", quarantineArea)
                .containsEntry("stock", "QUARANTINE").containsEntry("on_hand", 10);
        assertThat(jdbc.queryForList("""
                SELECT m.movement_type, m.from_location_code, m.to_location_code, m.from_status, m.to_status,
                       m.quantity, m.reference_type, m.reason
                  FROM inventory.stock_movement m JOIN inventory.stock_package p ON p.id = m.to_stock_package_id
                 WHERE p.lpn = ?""", lpn)).singleElement().satisfies(line -> {
                    assertThat(line).containsEntry("movement_type", "STATUS_CHANGE")
                            .containsEntry("from_location_code", bin).containsEntry("to_location_code", quarantineArea)
                            .containsEntry("from_status", "AVAILABLE").containsEntry("to_status", "QUARANTINE")
                            .containsEntry("quantity", 10).containsEntry("reference_type", "STOCK_PACKAGE")
                            .containsEntry("reason", "Crushed corner");
                });
        assertThat(atp()).isZero();
        assertThat(refusal(() -> quarantine(lpn, quarantineArea)).errorCode())
                .isEqualTo(ErrorCode.STOCK_PACKAGE_QUARANTINED);
    }

    @Test
    @DisplayName("a carton held for an order is not quarantined (20 BR-21): STOCK_PACKAGE_HELD, nothing changes")
    void heldCartonIsRefused() {
        String held = carton(2);
        release(heldOrder(10));
        assertThat(cartonRow(held)).containsEntry("reserved", 10);

        BusinessException refused = refusal(() -> quarantine(held, quarantineArea));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);
        assertThat(refused.getMessage()).contains(held);
        assertThat(cartonRow(held)).containsEntry("location", bin).containsEntry("stock", "AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement WHERE movement_type = "
                + "'STATUS_CHANGE' AND sku = ?", Integer.class, sku)).isZero();
    }

    @Test
    @DisplayName("a quarantined carton is never allocated: the release takes the later one, FIFO notwithstanding")
    void quarantinedCartonIsNotAllocated() {
        String older = carton(2);
        String newer = carton(5);
        quarantine(older, quarantineArea);

        List<HoldView> holds = release(heldOrder(10));

        assertThat(holds).extracting(HoldView::lpn).containsExactly(newer);
        assertThat(cartonRow(older)).containsEntry("reserved", 0);
        assertThat(atp()).isZero();
        // ...and a second order finds no ATP for it at all.
        assertThat(refusal(() -> heldOrder(10)).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    @DisplayName("release opens a QUARANTINE_RELEASE task, the carton stays QUARANTINE until put away, then AVAILABLE")
    void releaseThenPutawayMakesItAvailable() {
        String lpn = carton(2);
        quarantine(lpn, quarantineArea);

        UUID taskId = quarantine.release(lpn, manager);

        PutawayTaskView task = putawayTasks.find(taskId).orElseThrow();
        assertThat(task.source()).isEqualTo(PutawaySource.QUARANTINE_RELEASE);
        assertThat(task.goodsReceiptId()).isNull();
        assertThat(task.goodsReceiptLineId()).isNull();
        assertThat(task.quantity()).isEqualTo(10);
        assertThat(task.status()).isEqualTo("PENDING");
        assertThat(cartonRow(lpn)).containsEntry("stock", "QUARANTINE").containsEntry("location", quarantineArea);
        assertThat(atp()).isZero();
        // Sent again, the same task answers.
        assertThat(quarantine.release(lpn, manager)).isEqualTo(taskId);

        TaskView read = putaway.get(taskId);
        assertThat(read.source()).isEqualTo(PutawaySource.QUARANTINE_RELEASE);
        assertThat(read.sourceLocationCode()).isEqualTo(quarantineArea);
        assertThat(read.lpn()).isEqualTo(lpn);
        assertThat(read.suggestions()).extracting(PutawayRanker.Suggestion::locationCode).contains(otherBin);

        TaskView done = putaway.confirm(taskId, otherBin, lpn, manager);

        assertThat(done.status()).isEqualTo("DONE");
        assertThat(cartonRow(lpn)).containsEntry("stock", "AVAILABLE").containsEntry("location", otherBin);
        assertThat(atp()).isEqualTo(10);
        assertThat(jdbc.queryForMap("""
                SELECT m.movement_type, m.from_status, m.to_status, m.from_location_code, m.to_location_code
                  FROM inventory.stock_movement m WHERE m.reference_type = 'PUTAWAY_TASK' AND m.reference_id = ?""",
                taskId)).containsEntry("movement_type", "MOVE").containsEntry("from_status", "QUARANTINE")
                .containsEntry("to_status", "AVAILABLE").containsEntry("from_location_code", quarantineArea)
                .containsEntry("to_location_code", otherBin);
        assertThat(recorded.matching(PutawayCompleted.class, e -> e.putawayTaskId().equals(taskId))).isEmpty();
        assertThat(refusal(() -> quarantine.release(lpn, manager)).errorCode())
                .isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_QUARANTINED);
    }

    @Test
    @DisplayName("a carton that is not quarantined is not released, nor quarantined into a non-QUARANTINE area")
    void releaseAndQuarantineRefusals() {
        String lpn = carton(2);

        assertThat(refusal(() -> quarantine.release(lpn, manager)).errorCode())
                .isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_QUARANTINED);
        assertThat(refusal(() -> quarantine(lpn, receiving)).errorCode()).isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);
        assertThat(refusal(() -> quarantine(lpn, otherBin)).errorCode()).isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);
        assertThat(refusal(() -> quarantine(lpn, "HCM-QC01")).errorCode()).isEqualTo(ErrorCode.MOVE_ACROSS_WAREHOUSES);
        assertThat(refusal(() -> quarantine("LPN-NOPE-0001", quarantineArea)).errorCode())
                .isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
        assertThat(cartonRow(lpn)).containsEntry("stock", "AVAILABLE").containsEntry("location", bin);
    }

    @Test
    @DisplayName("a quarantined carton is written off whole by an approved DAMAGED adjustment of its LPN")
    void writeOffByDamagedAdjustment() {
        String lpn = carton(2);
        quarantine(lpn, quarantineArea);

        assertThat(refusal(() -> operations.requestAdjustment(RequestAdjustmentCommand.ofCarton(lpn, -4,
                StockAdjustmentReason.DAMAGED, null, manager))).errorCode())
                .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
        StockAdjustmentSummary asked = operations.requestAdjustment(RequestAdjustmentCommand.ofCarton(
                lpn.toLowerCase(Locale.ROOT), -10, StockAdjustmentReason.DAMAGED, "Water damage", manager));
        assertThat(asked.status().name()).isEqualTo("PENDING_APPROVAL");
        // Nothing has left yet, and the carton cannot be released while its write-off waits.
        assertThat(cartonRow(lpn)).containsEntry("on_hand", 10).containsEntry("stock", "QUARANTINE");
        assertThat(refusal(() -> quarantine.release(lpn, manager)).errorCode())
                .isEqualTo(ErrorCode.STOCK_PACKAGE_DECISION_PENDING);
        assertThat(refusal(() -> operations.approve(asked.adjustmentId(), manager)).errorCode())
                .isEqualTo(ErrorCode.ADJUSTMENT_SELF_APPROVAL);

        operations.approve(asked.adjustmentId(), approver);

        assertThat(cartonRow(lpn)).containsEntry("carton", "CONSUMED").containsEntry("on_hand", 0);
        assertThat(jdbc.queryForMap("""
                SELECT m.movement_type, m.from_location_code, m.to_location_code, m.from_status, m.quantity
                  FROM inventory.stock_movement m WHERE m.reference_type = 'STOCK_ADJUSTMENT' AND m.reference_id = ?""",
                asked.adjustmentId())).containsEntry("movement_type", "ADJUSTMENT")
                .containsEntry("from_location_code", quarantineArea).containsEntry("from_status", "QUARANTINE")
                .containsEntry("quantity", 10);
        assertThat(atp()).isZero();
    }

    @Test
    @DisplayName("a hand move neither carries sellable stock into quarantine nor quarantined stock out of it")
    void handMovesDoNotStandInForTheDecisions() {
        String sellable = carton(2);
        String quarantined = carton(3);
        quarantine(quarantined, quarantineArea);

        assertThat(refusal(() -> inventory.moveStockPackages(new MoveStockPackagesCommand(List.of(sellable),
                quarantineArea, MoveReference.MOVE_TASK, Identifiers.newId(), manager))).errorCode())
                .isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);
        assertThat(refusal(() -> inventory.moveStockPackages(new MoveStockPackagesCommand(List.of(quarantined),
                otherBin, MoveReference.MOVE_TASK, Identifiers.newId(), manager))).errorCode())
                .isEqualTo(ErrorCode.STOCK_PACKAGE_QUARANTINED);
        assertThat(cartonRow(sellable)).containsEntry("location", bin).containsEntry("stock", "AVAILABLE");
        assertThat(cartonRow(quarantined)).containsEntry("location", quarantineArea);
    }

    @Test
    @DisplayName("a released carton is not written off as well: STOCK_PACKAGE_DECISION_PENDING while its task is open")
    void releasedCartonIsNotWrittenOff() {
        String lpn = carton(2);
        quarantine(lpn, quarantineArea);
        quarantine.release(lpn, manager);

        assertThat(refusal(() -> operations.requestAdjustment(RequestAdjustmentCommand.ofCarton(lpn, -10,
                StockAdjustmentReason.DAMAGED, null, manager))).errorCode())
                .isEqualTo(ErrorCode.STOCK_PACKAGE_DECISION_PENDING);
    }

    @Test
    @DisplayName("a write-off asked before the release is not approved once the release is open")
    void writeOffAskedBeforeAReleaseIsNotApprovedAfterIt() {
        String lpn = carton(2);
        quarantine(lpn, quarantineArea);
        StockAdjustmentSummary asked = operations.requestAdjustment(RequestAdjustmentCommand.ofCarton(lpn, -10,
                StockAdjustmentReason.DAMAGED, null, manager));
        // The release refuses while it waits; withdrawn, the release goes ahead, and a second request is refused.
        operations.withdraw(asked.adjustmentId(), manager);
        quarantine.release(lpn, manager);

        assertThat(refusal(() -> operations.requestAdjustment(RequestAdjustmentCommand.ofCarton(lpn, -10,
                StockAdjustmentReason.DAMAGED, null, manager))).errorCode())
                .isEqualTo(ErrorCode.STOCK_PACKAGE_DECISION_PENDING);
        assertThat(cartonRow(lpn)).containsEntry("on_hand", 10).containsEntry("stock", "QUARANTINE");
    }
}
