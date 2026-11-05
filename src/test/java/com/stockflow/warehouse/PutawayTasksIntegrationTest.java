package com.stockflow.warehouse;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.CountReceivingPackagesCommand.PackageCount;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.warehouse.api.NewPutawayTask;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-92: the putaway task behind {@code warehouse :: api}. Commits like production, on a real goods receipt line
 * (the task's foreign key) and, for a carton, a real counted carton of the same SKU (the task's trigger). The receipt
 * counts a line of 2000 as one carton of 1000 and one of 980.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class PutawayTasksIntegrationTest {

    private static final String RECEIVING = "HCM-RCV01";

    @Autowired PutawayTasks putaway;
    @Autowired InventoryService inventory;
    @Autowired GoodsReceipts receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID receipt;
    private UUID line;
    private UUID carton;
    private UUID bin;
    private UUID bin2;

    @BeforeEach
    void setUp() {
        sku = "PUT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        UUID poLine = Identifiers.newId();
        UUID cartonUnit = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Putaway fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Putaway fixture");
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            jdbc.update("update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8, height_cm = 6"
                    + " where id = ?", item);
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 1000)", cartonUnit, item);

            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, "PO-PUT-" + po.toString().substring(24), submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 2000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(po, "DN-PUT", null), clerk);
        receipt = draft.id();
        line = receipts.replaceLines(receipt, List.of(new GoodsReceipts.NewLine(poLine, 2000, null, null,
                RECEIVING, null))).lines().getFirst().id();
        List<StockPackageView> counted = inventory.countReceivingPackages(new CountReceivingPackagesCommand(line,
                new Sku(sku), cartonUnit, RECEIVING, List.of(new PackageCount(null, 1000)), clerk));
        carton = counted.getFirst().id();
        bin = DemoData.location("HCM-A01-1-A");
        bin2 = DemoData.location("HCM-A01-1-B");
    }

    private NewPutawayTask loose(int quantity) {
        return new NewPutawayTask(receipt, line, sku, quantity, null);
    }

    @Test
    @DisplayName("opening twice for one carton returns the one open task")
    void openTwiceForOneCartonReturnsOneTask() {
        NewPutawayTask request = new NewPutawayTask(receipt, line, sku, 1000, carton);

        PutawayTaskView first = putaway.open(request);
        PutawayTaskView second = putaway.open(request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.stockPackageId()).isEqualTo(carton);
        assertThat(putaway.tasksOfReceipt(receipt)).hasSize(1);
    }

    @Test
    @DisplayName("opening twice for one loose line returns the one task, even once it is DONE")
    void openTwiceForOneLooseLineReturnsOneTask() {
        PutawayTaskView first = putaway.open(loose(2000));
        putaway.recordPutaway(first.id(), 2000, 2000, bin);

        PutawayTaskView second = putaway.open(loose(2000));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo("DONE");
        assertThat(putaway.tasksOfReceipt(receipt)).hasSize(1);
    }

    @Test
    @DisplayName("recording a putaway stores the status and the target; the last part finishes the task")
    void recordPutawayPersistsStatusAndTarget() {
        PutawayTaskView task = putaway.open(loose(100));

        PutawayTaskView part = putaway.recordPutaway(task.id(), 40, 100, bin);

        assertThat(part.status()).isEqualTo("IN_PROGRESS");
        assertThat(part.targetLocationId()).isEqualTo(bin);
        assertThat(putaway.find(task.id())).get().extracting(PutawayTaskView::status).isEqualTo("IN_PROGRESS");
        assertThat(putaway.openTasks()).extracting(PutawayTaskView::id).contains(task.id());

        PutawayTaskView last = putaway.recordPutaway(task.id(), 60, 60, bin2);

        assertThat(last.status()).isEqualTo("DONE");
        assertThat(putaway.find(task.id())).get().extracting(PutawayTaskView::targetLocationId).isEqualTo(bin2);
        assertThat(putaway.openTasks()).extracting(PutawayTaskView::id).doesNotContain(task.id());
        assertThatThrownBy(() -> putaway.recordPutaway(task.id(), 1, 0, bin))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PUTAWAY_TASK_DONE);
    }

    @Test
    @DisplayName("a task that does not exist is PUTAWAY_TASK_NOT_FOUND when locked")
    void lockOfUnknownTaskIsNotFound() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> putaway.lockForUpdate(UUID.randomUUID())))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PUTAWAY_TASK_NOT_FOUND);
        assertThat(putaway.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("only DONE tasks count towards a receipt line: a DONE task of 40 and an IN_PROGRESS one give {line: 40}")
    void doneQuantityByReceiptLineCountsDoneTasksOnly() {
        // the unique index allows one loose task per line, so the second task is a carton task of the same line
        PutawayTaskView looseTask = putaway.open(loose(40));
        putaway.recordPutaway(looseTask.id(), 40, 40, bin);
        PutawayTaskView cartonTask = putaway.open(new NewPutawayTask(receipt, line, sku, 1000, carton));
        assertThatThrownBy(() -> putaway.recordPutaway(cartonTask.id(), 500, 1000, bin2))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);

        assertThat(putaway.doneQuantityByReceiptLine(receipt)).isEqualTo(Map.of(line, 40));
        assertThat(putaway.openTasks()).extracting(PutawayTaskView::id).contains(cartonTask.id());
    }
}
