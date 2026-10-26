package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.internal.service.PutawayService;
import com.stockflow.inventory.internal.service.PutawayService.TaskView;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.procurement.internal.service.GoodsReceipts.NewPackage;
import com.stockflow.support.Await;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
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

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-92: confirming a putaway by scan against a real PostgreSQL. A two-step receipt line of 40 units - cartons of
 * 10 and 10, 20 loose - is received into a warehouse of its own whose bins the tests shape, so the hard rules for a
 * bin the clerk chose can each be broken in turn without touching the shared demo stock.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
class PutawayConfirmationIntegrationTest {

    @Autowired PutawayService putaway;
    @Autowired PutawayTasks putawayTasks;
    @Autowired GoodsReceipts receipts;
    @Autowired InventoryService inventory;
    @Autowired RecordedEvents.Recorder recorded;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String prefix;
    private UUID warehouse;
    private UUID clerk;
    private UUID receiptId;
    private String receiving;
    private String lpn1;
    private String lpn2;
    private UUID cartonTask;
    private UUID looseTask;
    private int shelfX;

    @BeforeEach
    void setUp() {
        sku = "PCF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        prefix = "P" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        receiving = prefix + "-RCV01";
        warehouse = Identifiers.newId();
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        UUID poLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Putaway confirmation fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Putaway confirmation fixture");
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            // BR-03: tg_goods_receipt_lines_item refuses a receipt line for an item with no weight or dimensions.
            jdbc.update("update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8,"
                    + " height_cm = 6, qc_required = false where id = ?", item);
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 100)", Identifiers.newId(), item);
            warehouse(warehouse, prefix);
            area(warehouse, "RCV01", receiving, "RECEIVING", 1);

            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);   // four eyes (ck_purchase_orders_four_eyes)
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, ?, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s WHERE s.code = 'GOVIET'""",
                    po, "PO-PC-" + po.toString().substring(24), warehouse, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 500, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        receiptId = receipts.create(new GoodsReceipts.Create(po, "DN-PC", null), clerk).id();
        receipts.replaceLines(receiptId, List.of(new GoodsReceipts.NewLine(poLine, 40, null, null, receiving, null,
                "CARTON", List.of(new NewPackage(null, 10), new NewPackage(null, 10)))));
        GoodsReceipts.LineView line = receipts.confirm(receiptId, clerk).lines().getFirst();
        lpn1 = line.packages().get(0).lpn();
        lpn2 = line.packages().get(1).lpn();
        Await.until("three putaway tasks", Duration.ofSeconds(20), () -> tasks().size() >= 3);
        cartonTask = taskOf(lpn1);
        looseTask = tasks().stream().filter(t -> t.stockPackageId() == null).findFirst().orElseThrow().id();
        recorded.clear();
    }

    private List<PutawayTaskView> tasks() {
        return putawayTasks.tasksOfReceipt(receiptId);
    }

    private UUID taskOf(String lpn) {
        UUID carton = jdbc.queryForObject("select id from inventory.stock_package where lpn = ?", UUID.class, lpn);
        return tasks().stream().filter(t -> carton.equals(t.stockPackageId())).findFirst().orElseThrow().id();
    }

    private void warehouse(UUID id, String code) {
        jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                + " map_height, version, created_at) VALUES (?, 'Putaway confirmation', 'ACTIVE', ?, 'Test address',"
                + " 'M', 50, 50, 0, NOW())", id, code);
    }

    private void area(UUID warehouseId, String code, String locationCode, String type, int x) {
        UUID location = Identifiers.newId();
        jdbc.update("INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,"
                + " is_pickable, is_putaway_target, status) VALUES (?, ?, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')",
                location, warehouseId, locationCode);
        jdbc.update("INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,"
                + " rotation, is_obstacle, status) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 5, 5, 0, FALSE, 'ACTIVE')",
                Identifiers.newId(), warehouseId, location, code, type, code, x);
    }

    /** A bin on a shelf of its own, a putaway target of {@code storageClass}; null limits mean none. */
    private String bin(UUID warehouseId, String name, String storageClass, Integer capacity, Integer maxWeight,
                       String status) {
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
                                                 is_obstacle, pick_north, pick_east, pick_south, pick_west,
                                                 default_storage_class, status)
                    VALUES (?, ?, ?, ?, 'Shelf', ?, 30, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'NORMAL',
                            'ACTIVE')""", shelf, warehouseId, zone, name, x);
            jdbc.update("""
                    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                    VALUES (?, ?, 1, 0, 1.4, 500)""", level, shelf);
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                            capacity_units, max_weight, is_pickable,
                                                            is_putaway_target, status)
                    VALUES (?, ?, 'BIN', ?, ?, ?, ?, TRUE, TRUE, ?)""", location, warehouseId, code, storageClass,
                    capacity, maxWeight, status);
            jdbc.update("""
                    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                    VALUES (?, ?, ?, 'A', 0, 0, 5, 1.2, 0, 'PALLET')""", Identifiers.newId(), level, location);
        });
        return code;
    }

    private String roomyBin(String name) {
        return bin(warehouse, name, "NORMAL", 500, 500, "ACTIVE");
    }

    private BusinessException refusal(Runnable confirm) {
        return org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, confirm::run);
    }

    private TaskView confirmCarton(UUID task, String lpn, String bin) {
        return putaway.confirm(task, bin, lpn, null, clerk);
    }

    private TaskView confirmLoose(String bin, int quantity) {
        return putaway.confirm(looseTask, bin, null, quantity, clerk);
    }

    private int ledgerLines(UUID task) {
        return jdbc.queryForObject("select count(*) from inventory.stock_movement where movement_type = 'MOVE'"
                + " and reference_type = 'PUTAWAY_TASK' and reference_id = ?", Integer.class, task);
    }

    @Test
    @DisplayName("a carton is confirmed whole: one ledger line naming it both sides, AVAILABLE, task DONE, one event")
    void cartonConfirmedMovesItWholeAsAvailable() {
        String bin = roomyBin("A01");

        TaskView done = confirmCarton(cartonTask, lpn1.toLowerCase(Locale.ROOT), bin.toLowerCase(Locale.ROOT));

        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.left()).isZero();
        assertThat(done.sourceLocationCode()).isEqualTo(bin);
        assertThat(done.targetLocationCode()).isEqualTo(bin);
        assertThat(ledgerLines(cartonTask)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT m.quantity, m.to_status, m.to_location_code, p.lpn AS from_lpn, q.lpn AS to_lpn
                  FROM inventory.stock_movement m
                  JOIN inventory.stock_package p ON p.id = m.from_stock_package_id
                  JOIN inventory.stock_package q ON q.id = m.to_stock_package_id
                 WHERE m.reference_type = 'PUTAWAY_TASK' AND m.reference_id = ?""", cartonTask))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("quantity")).isEqualTo(10);
                    assertThat(row.get("to_status")).isEqualTo("AVAILABLE");
                    assertThat(row.get("to_location_code")).isEqualTo(bin);
                    assertThat(row.get("from_lpn")).isEqualTo(lpn1);
                    assertThat(row.get("to_lpn")).isEqualTo(lpn1);
                });
        assertThat(recorded.matching(PutawayCompleted.class, e -> e.putawayTaskId().equals(cartonTask)))
                .singleElement().satisfies(event -> {
                    assertThat(event.goodsReceiptId()).isEqualTo(receiptId);
                    assertThat(event.goodsReceiptLineId()).isEqualTo(done.goodsReceiptLineId());
                });
    }

    @Test
    @DisplayName("loose units are put away in two bins: IN_PROGRESS after 12, DONE after 8 more, one event")
    void looseConfirmedInTwoBins() {
        String first = roomyBin("A01");
        String second = roomyBin("A02");

        TaskView part = confirmLoose(first, 12);

        assertThat(part.status()).isEqualTo("IN_PROGRESS");
        assertThat(part.left()).isEqualTo(8);
        assertThat(recorded.matching(PutawayCompleted.class, e -> e.putawayTaskId().equals(looseTask))).isEmpty();

        TaskView done = confirmLoose(second, 8);

        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.left()).isZero();
        assertThat(done.targetLocationCode()).isEqualTo(second);
        assertThat(jdbc.queryForObject("select coalesce(sum(quantity), 0) from inventory.stock_movement"
                + " where reference_type = 'PUTAWAY_TASK' and reference_id = ?", Integer.class, looseTask))
                .isEqualTo(20);
        assertThat(recorded.matching(PutawayCompleted.class, e -> e.putawayTaskId().equals(looseTask)))
                .hasSize(1);
    }

    @Test
    @DisplayName("more loose units than are left, or none: PUTAWAY_QUANTITY_EXCEEDED")
    void looseMoreThanLeftRefused() {
        String bin = roomyBin("A01");
        confirmLoose(bin, 12);

        assertThat(refusal(() -> confirmLoose(bin, 9)).errorCode()).isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);
        assertThat(refusal(() -> putaway.confirm(looseTask, bin, null, null, clerk)).errorCode())
                .isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);
        assertThat(ledgerLines(looseTask)).isEqualTo(1);
    }

    @Test
    @DisplayName("a task that is DONE cannot be confirmed again")
    void confirmAfterDoneRefused() {
        String bin = roomyBin("A01");
        confirmCarton(cartonTask, lpn1, bin);

        assertThat(refusal(() -> confirmCarton(cartonTask, lpn1, bin)).errorCode()).isEqualTo(ErrorCode.PUTAWAY_TASK_DONE);
        assertThat(ledgerLines(cartonTask)).isEqualTo(1);
    }

    @Test
    @DisplayName("another carton's LPN, or none: PUTAWAY_LPN_MISMATCH; a quantity on a carton task or an LPN on a loose one: VALIDATION_FAILED")
    void wrongScanRefused() {
        String bin = roomyBin("A01");

        assertThat(refusal(() -> confirmCarton(cartonTask, lpn2, bin)).errorCode()).isEqualTo(ErrorCode.PUTAWAY_LPN_MISMATCH);
        assertThat(refusal(() -> confirmCarton(cartonTask, null, bin)).errorCode()).isEqualTo(ErrorCode.PUTAWAY_LPN_MISMATCH);
        assertThat(refusal(() -> putaway.confirm(cartonTask, bin, lpn1, 10, clerk)).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> putaway.confirm(looseTask, bin, lpn1, 5, clerk)).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ledgerLines(cartonTask)).isZero();
    }

    @Test
    @DisplayName("a storage area is not a bin: PUTAWAY_TARGET_NOT_BIN; an unknown code: LOCATION_NOT_FOUND")
    void areaRefused() {
        assertThat(refusal(() -> confirmCarton(cartonTask, lpn1, receiving)).errorCode())
                .isEqualTo(ErrorCode.PUTAWAY_TARGET_NOT_BIN);
        assertThat(refusal(() -> confirmCarton(cartonTask, lpn1, prefix + "-NOPE")).errorCode())
                .isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
    }

    @Test
    @DisplayName("a bin of another warehouse: MOVE_ACROSS_WAREHOUSES")
    void otherWarehouseRefused() {
        UUID elsewhere = Identifiers.newId();
        String otherPrefix = "Q" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> warehouse(elsewhere, otherPrefix));
        String code = otherPrefix + "-B01-1-A";
        tx.executeWithoutResult(s -> {
            UUID zone = Identifiers.newId();
            UUID shelf = Identifiers.newId();
            UUID level = Identifiers.newId();
            UUID location = Identifiers.newId();
            jdbc.update("INSERT INTO warehouse.zone (id, warehouse_id, name, color) VALUES (?, ?, 'Zone', '#4F81BD')",
                    zone, elsewhere);
            jdbc.update("""
                    INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation,
                                                 is_obstacle, pick_north, pick_east, pick_south, pick_west,
                                                 default_storage_class, status)
                    VALUES (?, ?, ?, 'B01', 'Shelf', 5, 30, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'NORMAL',
                            'ACTIVE')""", shelf, elsewhere, zone);
            jdbc.update("""
                    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                    VALUES (?, ?, 1, 0, 1.4, 500)""", level, shelf);
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                            capacity_units, max_weight, is_pickable,
                                                            is_putaway_target, status)
                    VALUES (?, ?, 'BIN', ?, 'NORMAL', 500, 500, TRUE, TRUE, 'ACTIVE')""", location, elsewhere, code);
            jdbc.update("""
                    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                    VALUES (?, ?, ?, 'A', 0, 0, 5, 1.2, 0, 'PALLET')""", Identifiers.newId(), level, location);
        });

        assertThat(refusal(() -> confirmCarton(cartonTask, lpn1, code)).errorCode())
                .isEqualTo(ErrorCode.MOVE_ACROSS_WAREHOUSES);
    }

    @Test
    @DisplayName("a blocked bin: LOCATION_NOT_USABLE")
    void unusableBinRefused() {
        String bin = bin(warehouse, "A01", "NORMAL", 500, 500, "BLOCKED");

        assertThat(refusal(() -> confirmCarton(cartonTask, lpn1, bin)).errorCode()).isEqualTo(ErrorCode.LOCATION_NOT_USABLE);
    }

    @Test
    @DisplayName("a bin of another storage class: LOCATION_STORAGE_CLASS_MISMATCH")
    void wrongClassRefused() {
        String bin = bin(warehouse, "A01", "COLD", 500, 500, "ACTIVE");

        assertThat(refusal(() -> confirmCarton(cartonTask, lpn1, bin)).errorCode())
                .isEqualTo(ErrorCode.LOCATION_STORAGE_CLASS_MISMATCH);
    }

    @Test
    @DisplayName("a bin the units do not fit in: LOCATION_CAPACITY_EXCEEDED")
    void capacityRefused() {
        String bin = bin(warehouse, "A01", "NORMAL", 9, 500, "ACTIVE");

        assertThat(refusal(() -> confirmCarton(cartonTask, lpn1, bin)).errorCode())
                .isEqualTo(ErrorCode.LOCATION_CAPACITY_EXCEEDED);
        assertThat(ledgerLines(cartonTask)).isZero();
    }

    @Test
    @DisplayName("a bin the weight does not fit in: LOCATION_CAPACITY_EXCEEDED")
    void weightRefused() {
        String bin = bin(warehouse, "A01", "NORMAL", 500, 4, "ACTIVE");   // 10 units of 0.5 kg weigh 5

        assertThat(refusal(() -> confirmCarton(cartonTask, lpn1, bin)).errorCode())
                .isEqualTo(ErrorCode.LOCATION_CAPACITY_EXCEEDED);
    }

    @Test
    @DisplayName("putting goods away makes them available to promise: ATP grows by the units")
    void atpGrowsByThePutaway() {
        String bin = roomyBin("A01");
        int before = inventory.availableToPromise(new Sku(sku));

        confirmCarton(cartonTask, lpn1, bin);
        confirmLoose(bin, 12);

        assertThat(inventory.availableToPromise(new Sku(sku))).isEqualTo(before + 10 + 12);
    }

    @Test
    @DisplayName("a cancelled task reports nothing left and is refused with VALIDATION_FAILED")
    void cancelledTaskIsRefused() {
        tx.executeWithoutResult(s -> jdbc.update(
                "update warehouse.putaway_task set status = 'CANCELLED' where id = ?", cartonTask));
        String bin = roomyBin("A01");

        assertThat(putaway.get(cartonTask).left()).isZero();
        assertThat(putaway.get(cartonTask).suggestions()).isEmpty();
        assertThat(refusal(() -> confirmCarton(cartonTask, lpn1, bin)).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
