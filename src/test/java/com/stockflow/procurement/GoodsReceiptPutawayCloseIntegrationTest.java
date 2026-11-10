package com.stockflow.procurement;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.internal.service.PutawayService;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.procurement.internal.service.GoodsReceipts.LineView;
import com.stockflow.support.Await;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-92 spec §7 against a real PostgreSQL: a receipt in putaway closes once the quantities put away reach what
 * each line received. The fixture is a warehouse of its own with a RECEIVING area and one roomy bin, and a CONFIRMED
 * order of two lines. Loose units only, so a task is a quantity and nothing else.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class GoodsReceiptPutawayCloseIntegrationTest {

    @Autowired GoodsReceipts receipts;
    @Autowired PutawayService putaway;
    @Autowired PutawayTasks putawayTasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired @Qualifier("procurementPutawayCompletedListener") Object putawayCompletedListener;

    private String receiving;
    private String bin;
    private UUID clerk;
    private UUID po;
    private UUID plainPoLine;
    private UUID secondPoLine;

    @BeforeEach
    void setUp() {
        String prefix = "C" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        receiving = prefix + "-RCV01";
        bin = prefix + "-B01-1-A";
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID warehouse = Identifiers.newId();
        po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        plainPoLine = Identifiers.newId();
        secondPoLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID plainItem = item(prefix + "P", 6);
            UUID secondItem = item(prefix + "Q", 4);
            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                    + " map_height, version, created_at) VALUES (?, 'Putaway close', 'ACTIVE', ?, 'Test address', 'M',"
                    + " 50, 50, 0, NOW())", warehouse, prefix);
            area(warehouse, "RCV01", receiving, "RECEIVING", 1);
            bin(warehouse);

            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);   // four eyes (ck_purchase_orders_four_eyes)
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, ?, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s WHERE s.code = 'GOVIET'""",
                    po, "PO-PX-" + po.toString().substring(24), warehouse, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            poLine(revision, plainPoLine, 1, plainItem);
            poLine(revision, secondPoLine, 2, secondItem);
        });
    }

    /** An item sold in cartons of {@code perCarton}: a receipt puts every unit into one (team plan 4C-2). */
    private UUID item(String sku, int perCarton) {
        UUID product = Identifiers.newId();
        jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                "Putaway close fixture", sku.toLowerCase(Locale.ROOT));
        jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                Identifiers.newId(), product, sku, "Putaway close fixture");
        UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
        // BR-03: tg_goods_receipt_lines_item refuses a receipt line for an item with no weight or dimensions.
        jdbc.update("update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8,"
                + " height_cm = 6 where id = ?", item);
        jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                + " values (?, ?, 'CARTON', ?)", Identifiers.newId(), item, perCarton);
        return item;
    }

    private void poLine(UUID revision, UUID id, int lineNo, UUID item) {
        jdbc.update("""
                INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                    ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, ?, ?, 500, 1000, 0, NOW())""",
                id, po, revision, lineNo, item);
    }

    private void area(UUID warehouse, String code, String locationCode, String type, int x) {
        UUID location = Identifiers.newId();
        jdbc.update("INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,"
                + " is_pickable, is_putaway_target, status) VALUES (?, ?, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')",
                location, warehouse, locationCode);
        jdbc.update("INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,"
                + " rotation, is_obstacle, status) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 5, 5, 0, FALSE, 'ACTIVE')",
                Identifiers.newId(), warehouse, location, code, type, code, x);
    }

    private void bin(UUID warehouse) {
        UUID zone = Identifiers.newId();
        UUID shelf = Identifiers.newId();
        UUID level = Identifiers.newId();
        UUID location = Identifiers.newId();
        jdbc.update("INSERT INTO warehouse.zone (id, warehouse_id, name, color) VALUES (?, ?, 'Zone B01', '#4F81BD')",
                zone, warehouse);
        jdbc.update("""
                INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation,
                                             is_obstacle, pick_north, pick_east, pick_south, pick_west,
                                             default_storage_class, status)
                VALUES (?, ?, ?, 'B01', 'Shelf', 5, 30, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'NORMAL',
                        'ACTIVE')""", shelf, warehouse, zone);
        jdbc.update("""
                INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                VALUES (?, ?, 1, 0, 1.4, 500)""", level, shelf);
        jdbc.update("""
                INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                        capacity_units, max_weight, is_pickable, is_putaway_target,
                                                        status)
                VALUES (?, ?, 'BIN', ?, 'NORMAL', 500, 500, TRUE, TRUE, 'ACTIVE')""", location, warehouse, bin);
        jdbc.update("""
                INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                VALUES (?, ?, ?, 'A', 0, 0, 5, 1.2, 0, 'PALLET')""", Identifiers.newId(), level, location);
    }

    /** {@code cartons} cartons of {@code perCarton} on the PO line. */
    private GoodsReceipts.NewLine line(UUID poLine, int cartons, int perCarton) {
        return new GoodsReceipts.NewLine(poLine, cartons * perCarton, null, null, receiving, null, "CARTON",
                java.util.Collections.nCopies(cartons, new GoodsReceipts.NewPackage(null, perCarton)));
    }

    private GoodsReceipts.ReceiptView confirmedReceipt(GoodsReceipts.NewLine... lines) {
        UUID id = receipts.create(new GoodsReceipts.Create(po, "DN-PX", null), clerk).id();
        receipts.replaceLines(id, List.of(lines));
        return receipts.confirm(id, clerk);
    }

    private String status(UUID receiptId) {
        return jdbc.queryForObject("select status from procurement.goods_receipts where id = ?", String.class,
                receiptId);
    }

    private static LineView lineOf(GoodsReceipts.ReceiptView receipt, UUID poLine) {
        return receipt.lines().stream().filter(l -> l.purchaseOrderLineId().equals(poLine)).findFirst().orElseThrow();
    }

    /** The putaway tasks of a receipt line, one per carton, once the asynchronous receipt event has opened them. */
    private List<PutawayTaskView> tasksOf(UUID receiptId, UUID lineId, int cartons) {
        Await.until("the putaway tasks of line " + lineId, Duration.ofSeconds(20),
                () -> findTasks(receiptId, lineId).size() == cartons);
        return findTasks(receiptId, lineId);
    }

    private List<PutawayTaskView> findTasks(UUID receiptId, UUID lineId) {
        return putawayTasks.tasksOfReceipt(receiptId).stream()
                .filter(t -> lineId.equals(t.goodsReceiptLineId())).toList();
    }

    /** Puts the task's carton away by scanning its LPN. */
    private void putAway(PutawayTaskView task) {
        String lpn = jdbc.queryForObject("select lpn from inventory.stock_package where id = ?", String.class,
                task.stockPackageId());
        putaway.confirm(task.id(), bin, lpn, clerk);
    }

    @Test
    @DisplayName("a receipt closes after the last of its goods is put away, not before")
    void receiptClosesAfterItsLastTask() {
        GoodsReceipts.ReceiptView receipt = confirmedReceipt(line(plainPoLine, 2, 6));
        assertThat(status(receipt.id())).isEqualTo("IN_PUTAWAY");
        List<PutawayTaskView> tasks = tasksOf(receipt.id(), lineOf(receipt, plainPoLine).id(), 2);

        putAway(tasks.get(0));
        assertThat(status(receipt.id())).as("one carton of two is not enough").isEqualTo("IN_PUTAWAY");
        putAway(tasks.get(1));

        Await.until("the receipt to close", Duration.ofSeconds(20), () -> status(receipt.id()).equals("CLOSED"));
        assertThat(jdbc.queryForObject("select closed_at is not null from procurement.goods_receipts where id = ?",
                Boolean.class, receipt.id())).isTrue();
    }

    @Test
    @DisplayName("a two-line receipt stays in putaway until its second line is put away too")
    void staysInPutawayUntilEveryLineIsPutAway() {
        GoodsReceipts.ReceiptView receipt = confirmedReceipt(line(plainPoLine, 2, 6), line(secondPoLine, 1, 4));
        assertThat(status(receipt.id())).isEqualTo("IN_PUTAWAY");
        UUID plainLine = lineOf(receipt, plainPoLine).id();
        UUID secondLine = lineOf(receipt, secondPoLine).id();

        tasksOf(receipt.id(), plainLine, 2).forEach(this::putAway);
        Await.until("the plain line to be fully put away", Duration.ofSeconds(20),
                () -> putawayTasks.doneQuantityByReceiptLine(receipt.id()).getOrDefault(plainLine, 0) == 12);
        assertThat(status(receipt.id())).as("the second line is not put away yet").isEqualTo("IN_PUTAWAY");

        tasksOf(receipt.id(), secondLine, 1).forEach(this::putAway);
        Await.until("the receipt to close", Duration.ofSeconds(20), () -> status(receipt.id()).equals("CLOSED"));
    }

    @Test
    @DisplayName("the listener decides on the quantities as they are when it runs, not as the event saw them")
    void listenerReadsTheCurrentState() {
        GoodsReceipts.ReceiptView receipt = confirmedReceipt(line(plainPoLine, 1, 6), line(secondPoLine, 1, 4));
        UUID plainLine = lineOf(receipt, plainPoLine).id();
        UUID secondLine = lineOf(receipt, secondPoLine).id();
        UUID task = tasksOf(receipt.id(), plainLine, 1).getFirst().id();
        tasksOf(receipt.id(), plainLine, 1).forEach(this::putAway);
        tasksOf(receipt.id(), secondLine, 1).forEach(this::putAway);
        Await.until("the receipt to close", Duration.ofSeconds(20), () -> status(receipt.id()).equals("CLOSED"));
        // Put it back to what a lost race leaves: IN_PUTAWAY with everything put away, nothing closed it.
        jdbc.update("update procurement.goods_receipts set status = 'IN_PUTAWAY', closed_at = null where id = ?",
                receipt.id());

        // The event of the first task, delivered again late: it names the plain line, the second was put away since.
        Object target = AopTestUtils.getTargetObject(putawayCompletedListener);
        // The target is not proxied, so the transaction the module listener would run in is opened here.
        tx.executeWithoutResult(s -> ReflectionTestUtils.invokeMethod(target, "on",
                new PutawayCompleted(receipt.id(), plainLine, task)));

        assertThat(status(receipt.id())).isEqualTo("CLOSED");
    }
}
