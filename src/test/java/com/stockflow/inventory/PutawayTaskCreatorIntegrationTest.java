package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.internal.service.PutawayTaskCreator;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.procurement.internal.service.GoodsReceipts.Decision;
import com.stockflow.procurement.internal.service.GoodsReceipts.NewPackage;
import com.stockflow.procurement.internal.service.GoodsReceipts.Part;
import com.stockflow.support.Await;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * SCRUM-92: the listener of {@code ReceiptStockReadyForPutaway} against a real PostgreSQL. A receipt line of 2000
 * units - a carton of 1000, a carton of 980 and 20 loose - is driven through {@code GoodsReceipts} exactly as
 * production does, and the tasks the asynchronous listener opens are read back through {@link PutawayTasks}.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class PutawayTaskCreatorIntegrationTest {

    @Autowired GoodsReceipts receipts;
    @Autowired PutawayTasks putawayTasks;
    @Autowired PutawayTaskCreator creator;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String receiving;
    private String qcArea;
    private String quarantine;
    private UUID clerk;
    private UUID qc;
    private UUID poLine;
    private UUID receiptId;
    private UUID lineId;
    private String lpn1;
    private String lpn2;
    private UUID warehouseId;
    /** Where cartons are put away to (or moved by hand to) in these tests. */
    private String shelf;

    /** A confirmed receipt of 2000 units: cartons of 1000 and 980, 20 loose. */
    private void receipt(boolean qcRequired) {
        receipt(qcRequired, 2000, new NewPackage(null, 1000), new NewPackage(null, 980));
    }

    private void receipt(boolean qcRequired, int quantity, NewPackage... packages) {
        sku = "PTC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String prefix = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        receiving = prefix + "-RCV01";
        qcArea = prefix + "-QCA01";
        quarantine = prefix + "-QC01";
        shelf = prefix + "-PUT01";
        String returnToVendor = prefix + "-RTV01";
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        qc = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID warehouse = Identifiers.newId();
        warehouseId = warehouse;
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        poLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Putaway task creator fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Putaway task creator fixture");
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            // BR-03: tg_goods_receipt_lines_item refuses a receipt line for an item with no weight or dimensions.
            jdbc.update("update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8, height_cm = 6"
                    + " where id = ?", item);
            jdbc.update("update inventory.inventory_items set qc_required = " + qcRequired + " where id = ?", item);
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 100)", Identifiers.newId(), item);

            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                    + " map_height, version, created_at) VALUES (?, 'Putaway creator', 'ACTIVE', ?, 'Test address', 'M',"
                    + " 50, 50, 0, NOW())", warehouse, prefix);
            area(warehouse, "RCV01", receiving, "RECEIVING", 1);
            area(warehouse, "QCA01", qcArea, "QUALITY_CONTROL", 10);
            area(warehouse, "QC01", quarantine, "QUARANTINE", 20);
            area(warehouse, "RTV01", returnToVendor, "QUARANTINE", 30);
            area(warehouse, "PUT01", shelf, "OVERFLOW", 40);

            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);   // four eyes (ck_purchase_orders_four_eyes)
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, ?, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s WHERE s.code = 'GOVIET'""",
                    po, "PO-PT-" + po.toString().substring(24), warehouse, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 5000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        receiptId = receipts.create(new GoodsReceipts.Create(po, "DN-PT", null), clerk).id();
        receipts.replaceLines(receiptId, List.of(new GoodsReceipts.NewLine(poLine, quantity, null, null, receiving, null,
                "CARTON", List.of(packages))));
        GoodsReceipts.LineView confirmed = receipts.confirm(receiptId, clerk).lines().getFirst();
        lineId = confirmed.id();
        lpn1 = confirmed.packages().get(0).lpn();
        lpn2 = confirmed.packages().get(1).lpn();
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

    /**
     * Calls the listener on the object behind the Spring proxy: through the proxy {@code @ApplicationModuleListener}
     * is asynchronous, the call returns at once, and "nothing new was opened" would be asserted before anything ran.
     */
    private void redeliver(ReceiptStockReadyForPutaway event) {
        AopTestUtils.<PutawayTaskCreator>getTargetObject(creator).on(event);
    }

    private List<PutawayTaskView> tasks() {
        return putawayTasks.tasksOfReceipt(receiptId);
    }

    private UUID cartonId(String lpn) {
        return jdbc.queryForObject("select id from inventory.stock_package where lpn = ?", UUID.class, lpn);
    }

    private ReceiptStockReadyForPutaway event(String location, int quantity) {
        return new ReceiptStockReadyForPutaway(receiptId, "GR-TEST", warehouseId,
                List.of(new ReceiptStockReadyForPutaway.Line(lineId, sku, null, location, quantity)));
    }

    private void awaitTasks(int count) {
        Await.until(count + " putaway tasks", Duration.ofSeconds(20), () -> tasks().size() >= count);
    }

    @Test
    @DisplayName("two-step: confirmation opens a task per carton and one for the loose units")
    void twoStepLineOpensATaskPerCartonAndOneLoose() {
        receipt(false);
        awaitTasks(3);

        assertThat(tasks()).extracting(PutawayTaskView::quantity, PutawayTaskView::stockPackageId)
                .containsExactlyInAnyOrder(tuple(1000, cartonId(lpn1)), tuple(980, cartonId(lpn2)), tuple(20, null));
        assertThat(tasks()).extracting(PutawayTaskView::goodsReceiptLineId).containsOnly(lineId);
    }

    @Test
    @DisplayName("three-step: QC accepting part of the line opens tasks for the accepted part only")
    void threeStepOpensTasksForTheAcceptedPartOnly() {
        receipt(true);
        receipts.moveToQc(receiptId, lineId, qcArea, clerk);
        receipts.inspect(receiptId, lineId, new Decision(1000, new Part(0, quarantine, "dented", List.of(lpn1)), null), qc);
        awaitTasks(2);

        assertThat(tasks()).extracting(PutawayTaskView::quantity, PutawayTaskView::stockPackageId)
                .containsExactlyInAnyOrder(tuple(980, cartonId(lpn2)), tuple(20, null));
    }

    @Test
    @DisplayName("an event delivered again opens nothing new")
    void redeliveredEventOpensNothingNew() {
        receipt(false);
        awaitTasks(3);

        tx.executeWithoutResult(s -> redeliver(event(receiving, 2000)));

        assertThat(tasks()).hasSize(3);
    }

    @Test
    @DisplayName("a line wholly in cartons, put away and then redelivered, opens no loose task for stock already in bins")
    void redeliveryAfterPutawayOpensNothing() {
        receipt(false, 1980, new NewPackage(null, 1000), new NewPackage(null, 980));
        awaitTasks(2);
        inventory.reclassifyStockPackages(new ReclassifyStockPackagesCommand(List.of(lpn1, lpn2), shelf,
                StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, UUID.randomUUID(), clerk, null));

        tx.executeWithoutResult(s -> redeliver(event(receiving, 1980)));

        assertThat(tasks()).hasSize(2).extracting(PutawayTaskView::stockPackageId)
                .containsExactlyInAnyOrder(cartonId(lpn1), cartonId(lpn2));
    }

    /**
     * Driven through a 3-step line, whose confirmation publishes nothing, so no asynchronous listener races the
     * hand move; the event is then handled directly, as the QC acceptance would have delivered it.
     */
    @Test
    @DisplayName("a carton moved by hand before the event is handled still gets its task, and loose is the true remainder")
    void cartonMovedByHandStillGetsATask() {
        receipt(true);
        receipts.moveToQc(receiptId, lineId, qcArea, clerk);
        inventory.moveStockPackages(new MoveStockPackagesCommand(lpn1, shelf, MoveReference.MOVE_TASK,
                UUID.randomUUID(), clerk));

        tx.executeWithoutResult(s -> redeliver(event(qcArea, 2000)));

        assertThat(tasks()).extracting(PutawayTaskView::quantity, PutawayTaskView::stockPackageId)
                .containsExactlyInAnyOrder(tuple(1000, cartonId(lpn1)), tuple(980, cartonId(lpn2)), tuple(20, null));
    }
}
