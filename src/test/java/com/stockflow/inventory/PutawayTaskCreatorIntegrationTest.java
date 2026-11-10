package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.internal.service.PutawayTaskCreator;
import com.stockflow.support.Await;
import com.stockflow.support.ProductionReceiptTest;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
@ProductionReceiptTest
class PutawayTaskCreatorIntegrationTest {

    @Autowired ProductionReceivables receivables;
    @Autowired PutawayTasks putawayTasks;
    @Autowired PutawayTaskCreator creator;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String receiving;
    private UUID clerk;
    private UUID receiptId;
    private UUID lineId;
    private String lpn1;
    private String lpn2;
    private UUID warehouseId;
    /** Where cartons are put away to (or moved by hand to) in these tests. */
    private String shelf;

    /** A confirmed receipt of 2000 units: cartons of 1000 and 980, 20 loose. */
    private void receipt() {
        receipt(1000, 980);
    }

    /** A line of 2 cartons' worth of 1000, of which the cartons of {@code counts} are labelled; the rest is loose. */
    private void receipt(int... counts) {
        String prefix = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        receiving = prefix + "-RCV01";
        shelf = prefix + "-PUT01";
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID warehouse = Identifiers.newId();
        warehouseId = warehouse;
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                    + " map_height, version, created_at) VALUES (?, 'Putaway creator', 'ACTIVE', ?, 'Test address', 'M',"
                    + " 50, 50, 0, NOW())", warehouse, prefix);
            area(warehouse, "RCV01", receiving, "RECEIVING", 1);
            area(warehouse, "PUT01", shelf, "OVERFLOW", 40);
        });
        ProductionReceivables.Receivable goods = receivables.sent(warehouse, 2, 1000);
        sku = goods.sku();
        ProductionReceivables.Dock dock = receivables.onTheDock(goods, receiving, 2,
                java.util.Arrays.stream(counts).boxed().toList(), clerk);
        receiptId = dock.receiptId();
        lineId = dock.lineId();
        lpn1 = dock.lpns().get(0);
        lpn2 = dock.lpns().get(1);
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
    @DisplayName("confirmation opens a task per carton and one for the loose units")
    void confirmationOpensATaskPerCartonAndOneLoose() {
        receipt();
        awaitTasks(3);

        assertThat(tasks()).extracting(PutawayTaskView::quantity, PutawayTaskView::stockPackageId)
                .containsExactlyInAnyOrder(tuple(1000, cartonId(lpn1)), tuple(980, cartonId(lpn2)), tuple(20, null));
        assertThat(tasks()).extracting(PutawayTaskView::goodsReceiptLineId).containsOnly(lineId);
    }

    @Test
    @DisplayName("an event delivered again opens nothing new")
    void redeliveredEventOpensNothingNew() {
        receipt();
        awaitTasks(3);

        tx.executeWithoutResult(s -> redeliver(event(receiving, 2000)));

        assertThat(tasks()).hasSize(3);
    }

    @Test
    @DisplayName("a line wholly in cartons, put away and then redelivered, opens no loose task for stock already in bins")
    void redeliveryAfterPutawayOpensNothing() {
        receipt(1000, 1000);
        awaitTasks(2);
        inventory.reclassifyStockPackages(new ReclassifyStockPackagesCommand(List.of(lpn1, lpn2), shelf,
                StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, UUID.randomUUID(), clerk, null));

        tx.executeWithoutResult(s -> redeliver(event(receiving, 2000)));

        assertThat(tasks()).hasSize(2).extracting(PutawayTaskView::stockPackageId)
                .containsExactlyInAnyOrder(cartonId(lpn1), cartonId(lpn2));
    }

    /**
     * The tasks the confirmation opened are deleted first, so the event is handled as if it had not been yet, with
     * no asynchronous listener racing the hand move; it is then handled directly.
     */
    @Test
    @DisplayName("a carton moved by hand before the event is handled still gets its task, and loose is the true remainder")
    void cartonMovedByHandStillGetsATask() {
        receipt();
        awaitTasks(3);
        tx.executeWithoutResult(s -> jdbc.update("delete from warehouse.putaway_task where goods_receipt_id = ?",
                receiptId));
        inventory.moveStockPackages(new MoveStockPackagesCommand(lpn1, shelf, MoveReference.MOVE_TASK,
                UUID.randomUUID(), clerk));

        tx.executeWithoutResult(s -> redeliver(event(receiving, 2000)));

        assertThat(tasks()).extracting(PutawayTaskView::quantity, PutawayTaskView::stockPackageId)
                .containsExactlyInAnyOrder(tuple(1000, cartonId(lpn1)), tuple(980, cartonId(lpn2)), tuple(20, null));
    }
}
