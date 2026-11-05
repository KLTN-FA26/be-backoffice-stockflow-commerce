package com.stockflow.procurement;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.internal.service.PutawayService;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.procurement.internal.service.GoodsReceipts.Decision;
import com.stockflow.procurement.internal.service.GoodsReceipts.LineView;
import com.stockflow.procurement.internal.service.GoodsReceipts.Part;
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
 * each line had to put away. The fixture is a warehouse of its own with a RECEIVING, a QUALITY_CONTROL and a
 * QUARANTINE area and one roomy bin, and a CONFIRMED order of two lines: a plain SKU and a {@code qc_required} one.
 * Loose units only, so a task is a quantity and nothing else.
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
    private String qcArea;
    private String quarantine;
    private String bin;
    private UUID clerk;
    private UUID po;
    private UUID plainPoLine;
    private UUID qcPoLine;

    @BeforeEach
    void setUp() {
        String prefix = "C" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        receiving = prefix + "-RCV01";
        qcArea = prefix + "-QCA01";
        quarantine = prefix + "-QUA01";
        bin = prefix + "-B01-1-A";
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID warehouse = Identifiers.newId();
        po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        plainPoLine = Identifiers.newId();
        qcPoLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID plainItem = item(prefix + "P", false);
            UUID qcItem = item(prefix + "Q", true);
            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                    + " map_height, version, created_at) VALUES (?, 'Putaway close', 'ACTIVE', ?, 'Test address', 'M',"
                    + " 50, 50, 0, NOW())", warehouse, prefix);
            area(warehouse, "RCV01", receiving, "RECEIVING", 1);
            area(warehouse, "QCA01", qcArea, "QUALITY_CONTROL", 10);
            area(warehouse, "QUA01", quarantine, "QUARANTINE", 20);
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
            poLine(revision, qcPoLine, 2, qcItem);
        });
    }

    private UUID item(String sku, boolean qcRequired) {
        UUID product = Identifiers.newId();
        jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                "Putaway close fixture", sku.toLowerCase(Locale.ROOT));
        jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                Identifiers.newId(), product, sku, "Putaway close fixture");
        UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
        // BR-03: tg_goods_receipt_lines_item refuses a receipt line for an item with no weight or dimensions.
        jdbc.update("update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8,"
                + " height_cm = 6, qc_required = ? where id = ?", qcRequired, item);
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

    private GoodsReceipts.NewLine line(UUID poLine, int quantity) {
        return new GoodsReceipts.NewLine(poLine, quantity, null, null, receiving, null, null, List.of());
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

    /** The putaway task of a receipt line, once the asynchronous receipt event has opened it. */
    private UUID taskOf(UUID receiptId, UUID lineId) {
        Await.until("the putaway task of line " + lineId, Duration.ofSeconds(20),
                () -> findTask(receiptId, lineId) != null);
        return findTask(receiptId, lineId);
    }

    private UUID findTask(UUID receiptId, UUID lineId) {
        return putawayTasks.tasksOfReceipt(receiptId).stream()
                .filter(t -> lineId.equals(t.goodsReceiptLineId())).map(PutawayTaskView::id).findFirst().orElse(null);
    }

    @Test
    @DisplayName("a two-step receipt closes after the last of its goods is put away, not before")
    void twoStepReceiptClosesAfterItsLastTask() {
        GoodsReceipts.ReceiptView receipt = confirmedReceipt(line(plainPoLine, 12));
        assertThat(status(receipt.id())).isEqualTo("IN_PUTAWAY");
        UUID task = taskOf(receipt.id(), lineOf(receipt, plainPoLine).id());

        putaway.confirm(task, bin, null, 5, clerk);
        assertThat(status(receipt.id())).as("5 of 12 is not enough").isEqualTo("IN_PUTAWAY");
        putaway.confirm(task, bin, null, 7, clerk);

        Await.until("the receipt to close", Duration.ofSeconds(20), () -> status(receipt.id()).equals("CLOSED"));
        assertThat(jdbc.queryForObject("select closed_at is not null from procurement.goods_receipts where id = ?",
                Boolean.class, receipt.id())).isTrue();
    }

    @Test
    @DisplayName("a mixed receipt closes at the QC decision that accepts nothing, once the plain line is put away")
    void mixedReceiptClosesWhenLastQcLineAcceptsNothing() {
        GoodsReceipts.ReceiptView receipt = confirmedReceipt(line(plainPoLine, 10), line(qcPoLine, 4));
        assertThat(status(receipt.id())).isEqualTo("IN_QC");
        UUID plainLine = lineOf(receipt, plainPoLine).id();
        UUID qcLine = lineOf(receipt, qcPoLine).id();

        putaway.confirm(taskOf(receipt.id(), plainLine), bin, null, 10, clerk);
        Await.until("the plain line to be fully put away", Duration.ofSeconds(20),
                () -> putawayTasks.doneQuantityByReceiptLine(receipt.id()).getOrDefault(plainLine, 0) == 10);
        // The listener has run (or will) and sees a receipt still in QC: it is left alone.
        assertThat(status(receipt.id())).isEqualTo("IN_QC");

        receipts.moveToQc(receipt.id(), qcLine, qcArea, clerk);
        receipts.inspect(receipt.id(), qcLine, new Decision(0, null, new Part(4, quarantine, "wrong print")), clerk);

        // Closed by inspect itself, in its own transaction: nothing asynchronous is left to wait for.
        assertThat(status(receipt.id())).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("the listener closes a receipt that went IN_PUTAWAY after the last task was DONE: it reads the current state")
    void listenerClosesAReceiptThatBecameInPutAwayAfterTheLastTask() {
        GoodsReceipts.ReceiptView receipt = confirmedReceipt(line(plainPoLine, 10), line(qcPoLine, 4));
        UUID plainLine = lineOf(receipt, plainPoLine).id();
        UUID qcLine = lineOf(receipt, qcPoLine).id();
        UUID task = taskOf(receipt.id(), plainLine);
        putaway.confirm(task, bin, null, 10, clerk);
        Await.until("the plain line to be fully put away", Duration.ofSeconds(20),
                () -> putawayTasks.doneQuantityByReceiptLine(receipt.id()).getOrDefault(plainLine, 0) == 10);
        receipts.moveToQc(receipt.id(), qcLine, qcArea, clerk);
        receipts.inspect(receipt.id(), qcLine, new Decision(0, null, new Part(4, quarantine, "wrong print")), clerk);
        // inspect closes this receipt itself; put it back to what a lost race leaves: IN_PUTAWAY, nothing closed it.
        jdbc.update("update procurement.goods_receipts set status = 'IN_PUTAWAY', closed_at = null where id = ?",
                receipt.id());

        // Called directly, as PutawayTaskCreatorIntegrationTest.redeliver does: the asynchronous delivery is out of reach.
        Object target = AopTestUtils.getTargetObject(putawayCompletedListener);
        // The target is not proxied, so the transaction the module listener would run in is opened here.
        tx.executeWithoutResult(s -> ReflectionTestUtils.invokeMethod(target, "on",
                new PutawayCompleted(receipt.id(), plainLine, task)));

        assertThat(status(receipt.id())).isEqualTo("CLOSED");
    }
}
