package com.stockflow.warehouse;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.CountReceivingPackagesCommand.PackageCount;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.DemoData;
import com.stockflow.support.ProductionReceiptTest;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import com.stockflow.warehouse.api.NewPutawayTask;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-92: the putaway task behind {@code warehouse :: api}. Commits like production, on a real goods receipt line
 * (the task's foreign key) and, for a carton, a real counted carton of the same SKU (the task's trigger). The receipt
 * counts a line of 2000 as one carton of 1000 and one of 980.
 */
@ProductionReceiptTest
class PutawayTasksIntegrationTest {

    private static final String RECEIVING = "HCM-RCV01";

    @Autowired PutawayTasks putaway;
    @Autowired InventoryService inventory;
    @Autowired ProductionReceivables receivables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID receipt;
    private UUID line;
    private UUID carton;
    private UUID carton2;
    private UUID bin;
    private UUID bin2;

    @BeforeEach
    void setUp() {
        UUID clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        ProductionReceivables.Receivable goods = receivables.sent(DemoData.WAREHOUSE_HCM, 2, 1000);
        sku = goods.sku();
        UUID cartonUnit = goods.cartonUnitId();
        GoodsReceipts.ReceiptView draft = receivables.draftReceipt(goods, clerk);
        receipt = draft.id();
        line = receivables.uncountedLine(goods, draft, RECEIVING, 2);
        List<StockPackageView> counted = inventory.countReceivingPackages(new CountReceivingPackagesCommand(line,
                new Sku(sku), cartonUnit, RECEIVING, List.of(PackageCount.fresh(), PackageCount.fresh()), clerk));
        carton = counted.get(0).id();
        carton2 = counted.get(1).id();
        bin = DemoData.location("HCM-A01-1-A");
        bin2 = DemoData.location("HCM-A01-1-B");
    }

    private NewPutawayTask loose(int quantity) {
        return new NewPutawayTask(receipt, line, sku, quantity, null);
    }

    private NewPutawayTask ofCarton(UUID stockPackageId) {
        return new NewPutawayTask(receipt, line, sku, 1000, stockPackageId);
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
    @DisplayName("a receipt line's task is one carton (05 BR-08, team plan 4C-2): a loose one is refused, nothing opens")
    void aLooseTaskIsRefused() {
        assertThatThrownBy(() -> putaway.open(loose(2000)))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(putaway.tasksOfReceipt(receipt)).isEmpty();
    }

    @Test
    @DisplayName("recording a putaway stores the status and the target: the carton put away whole finishes the task")
    void recordPutawayPersistsStatusAndTarget() {
        PutawayTaskView task = putaway.open(ofCarton(carton));
        assertThat(putaway.openTasks()).extracting(PutawayTaskView::id).contains(task.id());

        PutawayTaskView done = putaway.recordPutaway(task.id(), 1000, 1000, bin2);

        assertThat(done.status()).isEqualTo("DONE");
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
    @DisplayName("only DONE tasks count towards a receipt line: a DONE carton of 1000 and an open one give {line: 1000}")
    void doneQuantityByReceiptLineCountsDoneTasksOnly() {
        PutawayTaskView done = putaway.open(ofCarton(carton));
        putaway.recordPutaway(done.id(), 1000, 1000, bin);
        PutawayTaskView open = putaway.open(ofCarton(carton2));
        assertThatThrownBy(() -> putaway.recordPutaway(open.id(), 1001, 1000, bin2))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);

        assertThat(putaway.doneQuantityByReceiptLine(receipt)).isEqualTo(Map.of(line, 1000));
        assertThat(putaway.openTasks()).extracting(PutawayTaskView::id).contains(open.id());
    }
}
