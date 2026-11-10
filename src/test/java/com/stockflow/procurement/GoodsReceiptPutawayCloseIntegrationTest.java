package com.stockflow.procurement;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.internal.service.PutawayService;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.procurement.internal.service.GoodsReceipts.LineView;
import com.stockflow.support.Await;
import com.stockflow.support.ProductionReceiptTest;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * each line received. The fixture is a warehouse of its own with a RECEIVING area and one roomy bin, and a production
 * order of two lines (2 cartons of 12 and 1 carton of 4). A task is a carton.
 */
@ProductionReceiptTest
class GoodsReceiptPutawayCloseIntegrationTest {

    @Autowired GoodsReceipts receipts;
    @Autowired ProductionReceivables receivables;
    @Autowired PutawayService putaway;
    @Autowired PutawayTasks putawayTasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired @Qualifier("procurementPutawayCompletedListener") Object putawayCompletedListener;

    private String receiving;
    private String bin;
    private UUID clerk;
    private ProductionReceivables.Receivable production;
    private ProductionReceivables.Goods plain;
    private ProductionReceivables.Goods second;

    @BeforeEach
    void setUp() {
        String prefix = "C" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        receiving = prefix + "-RCV01";
        bin = prefix + "-B01-1-A";
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID warehouse = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                    + " map_height, version, created_at) VALUES (?, 'Putaway close', 'ACTIVE', ?, 'Test address', 'M',"
                    + " 50, 50, 0, NOW())", warehouse, prefix);
            area(warehouse, "RCV01", receiving, "RECEIVING", 1);
            bin(warehouse);
        });
        // A line of 2 cartons of 12 and a line of 1 carton of 4.
        production = receivables.sentLines(warehouse, new int[] {2, 12}, new int[] {1, 4});
        plain = production.goods().get(0);
        second = production.goods().get(1);
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

    /** A confirmed receipt counting 2 cartons of the plain line and, when asked, 1 of the second. */
    private GoodsReceipts.ReceiptView confirmedReceipt(boolean withSecond) {
        GoodsReceipts.ReceiptView draft = receivables.draftReceipt(production, clerk);
        List<GoodsReceipts.NewLine> lines = new java.util.ArrayList<>(List.of(plain.line(receiving, 2)));
        if (withSecond) {
            lines.add(second.line(receiving, 1));
        }
        receipts.replaceLines(draft.id(), lines);
        return receipts.confirm(draft.id(), clerk);
    }

    private String status(UUID receiptId) {
        return jdbc.queryForObject("select status from procurement.goods_receipts where id = ?", String.class,
                receiptId);
    }

    private static LineView lineOf(GoodsReceipts.ReceiptView receipt, ProductionReceivables.Goods goods) {
        return receipt.lines().stream().filter(l -> l.sku().equals(goods.sku())).findFirst().orElseThrow();
    }

    /** The putaway tasks of a receipt line (a carton each), once the asynchronous receipt event has opened them. */
    private List<PutawayTaskView> tasksOf(UUID receiptId, UUID lineId, int count) {
        Await.until(count + " putaway task(s) of line " + lineId, Duration.ofSeconds(20),
                () -> findTasks(receiptId, lineId).size() == count);
        return findTasks(receiptId, lineId);
    }

    private List<PutawayTaskView> findTasks(UUID receiptId, UUID lineId) {
        return putawayTasks.tasksOfReceipt(receiptId).stream().filter(t -> lineId.equals(t.goodsReceiptLineId()))
                .toList();
    }

    /** Puts the task's carton away into the bin, scanning its label. */
    private void putAwayCarton(PutawayTaskView task) {
        String lpn = jdbc.queryForObject("select lpn from inventory.stock_package where id = ?", String.class,
                task.stockPackageId());
        putaway.confirm(task.id(), bin, lpn, clerk);
    }

    @Test
    @DisplayName("a receipt closes after the last of its cartons is put away, not before")
    void receiptClosesAfterItsLastTask() {
        GoodsReceipts.ReceiptView receipt = confirmedReceipt(false);
        assertThat(status(receipt.id())).isEqualTo("IN_PUTAWAY");
        List<PutawayTaskView> tasks = tasksOf(receipt.id(), lineOf(receipt, plain).id(), 2);

        putAwayCarton(tasks.get(0));
        assertThat(status(receipt.id())).as("one carton of two is not enough").isEqualTo("IN_PUTAWAY");
        putAwayCarton(tasks.get(1));

        Await.until("the receipt to close", Duration.ofSeconds(20), () -> status(receipt.id()).equals("CLOSED"));
        assertThat(jdbc.queryForObject("select closed_at is not null from procurement.goods_receipts where id = ?",
                Boolean.class, receipt.id())).isTrue();
    }

    @Test
    @DisplayName("a two-line receipt stays in putaway until its second line is put away too")
    void staysInPutawayUntilEveryLineIsPutAway() {
        GoodsReceipts.ReceiptView receipt = confirmedReceipt(true);
        assertThat(status(receipt.id())).isEqualTo("IN_PUTAWAY");
        UUID plainLine = lineOf(receipt, plain).id();
        UUID secondLine = lineOf(receipt, second).id();

        tasksOf(receipt.id(), plainLine, 2).forEach(this::putAwayCarton);
        Await.until("the plain line to be fully put away", Duration.ofSeconds(20),
                () -> putawayTasks.doneQuantityByReceiptLine(receipt.id()).getOrDefault(plainLine, 0) == 24);
        assertThat(status(receipt.id())).as("the second line is not put away yet").isEqualTo("IN_PUTAWAY");

        putAwayCarton(tasksOf(receipt.id(), secondLine, 1).getFirst());
        Await.until("the receipt to close", Duration.ofSeconds(20), () -> status(receipt.id()).equals("CLOSED"));
    }

    @Test
    @DisplayName("the listener decides on the quantities as they are when it runs, not as the event saw them")
    void listenerReadsTheCurrentState() {
        GoodsReceipts.ReceiptView receipt = confirmedReceipt(true);
        UUID plainLine = lineOf(receipt, plain).id();
        UUID secondLine = lineOf(receipt, second).id();
        List<PutawayTaskView> plainTasks = tasksOf(receipt.id(), plainLine, 2);
        plainTasks.forEach(this::putAwayCarton);
        putAwayCarton(tasksOf(receipt.id(), secondLine, 1).getFirst());
        Await.until("the receipt to close", Duration.ofSeconds(20), () -> status(receipt.id()).equals("CLOSED"));
        // Put it back to what a lost race leaves: IN_PUTAWAY with everything put away, nothing closed it.
        jdbc.update("update procurement.goods_receipts set status = 'IN_PUTAWAY', closed_at = null where id = ?",
                receipt.id());

        // The event of the first task, delivered again late: it names the plain line, the second was put away since.
        Object target = AopTestUtils.getTargetObject(putawayCompletedListener);
        // The target is not proxied, so the transaction the module listener would run in is opened here.
        tx.executeWithoutResult(s -> ReflectionTestUtils.invokeMethod(target, "on",
                new PutawayCompleted(receipt.id(), plainLine, plainTasks.getFirst().id())));

        assertThat(status(receipt.id())).isEqualTo("CLOSED");
    }
}
