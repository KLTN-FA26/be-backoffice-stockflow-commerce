package com.stockflow.order;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.internal.domain.AdjustmentDirection;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.inventory.internal.service.StockOperations.PackageAdjustmentRequest;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderKind;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
import com.stockflow.support.ProductionReceiptTest;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-839 (kltn-docs 17 §4.13 steps 52-54, BR-31): a replacement production order for cartons of a production order's
 * goods written off as damaged after they were received. Built through the real flow: a production order sent and
 * received in full, its cartons put on a bin and held for the order, then a carton written off as {@code DAMAGED} and
 * the write-off approved - which gives up the hold on it, so the order is short again.
 */
@ProductionReceiptTest
class ReplacementProductionOrderIntegrationTest {

    private static final String BIN = "HCM-B01-1-A";
    private static final String RECEIVING = "HCM-RCV01";

    @Autowired ProductionReceivables receivables;
    @Autowired ProductionOrderService productionOrders;
    @Autowired ShippingWarehouseService shippingWarehouses;
    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private UUID clerk;
    private UUID manager;
    private UUID coordinator;
    private ProductionReceivables.Receivable goods;
    private List<String> lpns;

    @BeforeEach
    void setUp() {
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        goods = receivables.sent(DemoData.WAREHOUSE_HCM, 3, 2);
        lpns = onTheBin(receivables.received(goods, RECEIVING, 3, clerk));
        // Putting goods away tops up the orders waiting for them: here by hand, the cartons having been moved by hand.
        shippingWarehouses.topUpWaiting(goods.sku());
        assertThat(productionOrders.lackingByBatch(goods.salesOrderId()).values().iterator().next().values())
                .as("the order is held in full before any carton is written off").containsOnly(0);
    }

    /** The receipt's cartons moved to a bin, AVAILABLE, their putaway tasks closed so a write-off is not refused. */
    private List<String> onTheBin(GoodsReceipts.ReceiptView receipt) {
        List<String> received = receipt.lines().getFirst().packages().stream().map(GoodsReceipts.PackageView::lpn)
                .toList();
        tx.executeWithoutResult(s -> inventory.reclassifyStockPackages(new ReclassifyStockPackagesCommand(received,
                BIN, StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, Identifiers.newId(), clerk, "putaway")));
        String[] array = received.toArray(new String[0]);
        try {
            Await.until("a putaway task for every carton", Duration.ofSeconds(30), () -> jdbc.queryForObject("""
                    SELECT count(*) FROM warehouse.putaway_task
                     WHERE stock_package_id IN (SELECT id FROM inventory.stock_package WHERE lpn = ANY (?))""",
                    Long.class, (Object) array) == array.length);
        } catch (AssertionError none) {
            // no task was opened for them: nothing stands in the way of a write-off
        }
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE warehouse.putaway_task SET status = 'CANCELLED'
                 WHERE stock_package_id IN (SELECT id FROM inventory.stock_package WHERE lpn = ANY (?))""",
                (Object) array));
        return received;
    }

    /**
     * A write-off of the whole carton, asked by the clerk and approved by the manager; its adjustment id. Approving it
     * announces the carton gone ({@code StockCartonWrittenOff}) and the order module, after the commit, gives up the
     * part of the order's holds by SKU no carton covers any more (17 §4.13 step 51): waited for here.
     */
    private UUID writeOff(String lpn, StockAdjustmentReason reason) {
        var asked = operations.requestPackageAdjustment(new PackageAdjustmentRequest(lpn,
                AdjustmentDirection.WRITE_OFF, reason, reason == StockAdjustmentReason.OTHER ? "why" : null, clerk));
        operations.approve(asked.adjustmentId(), manager);
        Await.until("the holds no carton covers given up", Duration.ofSeconds(30), () ->
                inventory.skuHoldCover(new Sku(goods.sku()), "HCM").uncoveredUnits() == 0);
        return asked.adjustmentId();
    }

    private static ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    @DisplayName("a damaged carton written off is made up by a REPLACEMENT draft: same order, earliest short batch, the "
            + "cartons written off, the producer of the one replaced, no print sample by default")
    void drawsUpAReplacement() {
        UUID damaged = writeOff(lpns.getFirst(), StockAdjustmentReason.DAMAGED);
        ProductionOrder replaced = productionOrders.detail(goods.productionOrderId()).order();
        assertThat(productionOrders.lackingByBatch(goods.salesOrderId()).values().iterator().next().values())
                .as("the damaged carton's hold was given up with it: the order is short of one carton again")
                .containsOnly(1);

        ProductionOrder replacement = productionOrders.drawUpReplacement(goods.productionOrderId(), Set.of(damaged),
                null, coordinator);

        assertThat(replacement.kind()).isEqualTo(ProductionOrderKind.REPLACEMENT);
        assertThat(replacement.status()).isEqualTo(ProductionOrderStatus.DRAFT);
        assertThat(replacement.salesOrderId()).isEqualTo(goods.salesOrderId());
        assertThat(replacement.deliveryBatchId()).isEqualTo(replaced.deliveryBatchId());
        assertThat(replacement.producerId()).isEqualTo(replaced.producerId());
        assertThat(replacement.receivingWarehouseId()).isEqualTo(DemoData.WAREHOUSE_HCM);
        assertThat(replacement.sampleApprovalRequired()).as("the SKU has been produced (step 52)").isFalse();
        assertThat(replacement.lines()).singleElement().satisfies(line -> {
            assertThat(line.sku()).isEqualTo(goods.sku());
            assertThat(line.cartons()).as("one carton written off, one made").isEqualTo(1);
        });
        ProductionOrder.Replacement made = replacement.replacement().orElseThrow();
        assertThat(made.replacesId()).isEqualTo(goods.productionOrderId());
        assertThat(made.writeOffs()).extracting(ProductionOrder.WriteOff::adjustmentId).containsExactly(damaged);
        // Read back as stored.
        assertThat(productionOrders.detail(replacement.id()).order().replacement()).contains(made);
        assertThat(productionOrders.writeOffs(goods.productionOrderId())).singleElement().satisfies(listed -> {
            assertThat(listed.writeOff().adjustmentId()).isEqualTo(damaged);
            assertThat(listed.writeOff().lpn()).isEqualTo(lpns.getFirst());
            assertThat(listed.madeUpBy()).isEqualTo(replacement.code());
        });
        assertThat(productionOrders.lackingByBatch(goods.salesOrderId()).values().iterator().next().values())
                .as("the replacement covers what the write-off left short").containsOnly(0);
    }

    @Test
    @DisplayName("a write-off is made up once: a second replacement of it is refused, until the first is cancelled")
    void aWriteOffIsMadeUpOnce() {
        UUID damaged = writeOff(lpns.getFirst(), StockAdjustmentReason.DAMAGED);
        ProductionOrder first = productionOrders.drawUpReplacement(goods.productionOrderId(), Set.of(damaged), null,
                coordinator);

        assertThat(codeOf(() -> productionOrders.drawUpReplacement(goods.productionOrderId(), Set.of(damaged), null,
                coordinator))).isEqualTo(ErrorCode.PRODUCTION_ORDER_WRITE_OFF_MADE_UP);

        productionOrders.cancel(first.id(), first.version(), "Another producer will make it", coordinator);
        ProductionOrder second = productionOrders.drawUpReplacement(goods.productionOrderId(), Set.of(damaged), null,
                coordinator);
        assertThat(second.replacement().orElseThrow().writeOffs()).extracting(ProductionOrder.WriteOff::adjustmentId)
                .containsExactly(damaged);
    }

    @Test
    @DisplayName("only a posted DAMAGED write-off of a carton of the production order replaced is made up")
    void onlyADamagedWriteOffOfTheLot() {
        UUID other = writeOff(lpns.getFirst(), StockAdjustmentReason.OTHER);
        var pending = operations.requestPackageAdjustment(new PackageAdjustmentRequest(lpns.get(1),
                AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED, null, clerk));

        assertThat(codeOf(() -> productionOrders.drawUpReplacement(goods.productionOrderId(), Set.of(other), null,
                coordinator))).as("another reason").isEqualTo(ErrorCode.PRODUCTION_ORDER_WRITE_OFF_INVALID);
        assertThat(codeOf(() -> productionOrders.drawUpReplacement(goods.productionOrderId(),
                Set.of(pending.adjustmentId()), null, coordinator))).as("not yet approved")
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_WRITE_OFF_INVALID);
        assertThat(codeOf(() -> productionOrders.drawUpReplacement(goods.productionOrderId(),
                Set.of(UUID.randomUUID()), null, coordinator))).as("no such write-off")
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_WRITE_OFF_INVALID);
    }

    @Test
    @DisplayName("nothing is drawn up when the order lacks none of the cartons written off (step 54)")
    void notWhenNothingIsShort() {
        UUID damaged = writeOff(lpns.getFirst(), StockAdjustmentReason.DAMAGED);
        // What the write-off left short is covered by an ordinary production order drawn up first.
        ProductionOrder standard = productionOrders.drawUp(goods.salesOrderId(), 1,
                productionOrders.detail(goods.productionOrderId()).order().producerId(), coordinator);
        assertThat(standard.kind()).isEqualTo(ProductionOrderKind.STANDARD);

        assertThat(codeOf(() -> productionOrders.drawUpReplacement(goods.productionOrderId(), Set.of(damaged), null,
                coordinator))).isEqualTo(ErrorCode.PRODUCTION_ORDER_NOTHING_SHORT);
    }

    @Test
    @DisplayName("an edit of a replacement draft makes no more cartons than were written off")
    void anEditStaysWithinTheWriteOffs() {
        // Two cartons short: one written off, one more left short by giving its hold up as well.
        UUID damaged = writeOff(lpns.getFirst(), StockAdjustmentReason.DAMAGED);
        writeOff(lpns.get(1), StockAdjustmentReason.OTHER);
        ProductionOrder replacement = productionOrders.drawUpReplacement(goods.productionOrderId(), Set.of(damaged),
                null, coordinator);
        ProductionOrder.Line line = replacement.lines().getFirst();

        assertThat(codeOf(() -> productionOrders.edit(replacement.id(), replacement.version(),
                new ProductionOrder.Draft(replacement.producerId(), replacement.needByDate(), null, false, null,
                        Map.of(line.orderLineId(), new ProductionOrder.LineEdit(2, null))))))
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_EXCEEDS_WRITE_OFFS);
    }

    @Test
    @DisplayName("a replacement is sent and received like any other; a carton of its own lot written off is made up "
            + "by a replacement of the replacement")
    void aReplacementOfAReplacement() {
        UUID damaged = writeOff(lpns.getFirst(), StockAdjustmentReason.DAMAGED);
        ProductionOrder replacement = productionOrders.drawUpReplacement(goods.productionOrderId(), Set.of(damaged),
                null, coordinator);
        ProductionOrder sent = productionOrders.send(replacement.id(), replacement.version(), coordinator);
        assertThat(sent.status()).isEqualTo(ProductionOrderStatus.SENT);
        ProductionReceivables.Goods first = goods.first();
        ProductionReceivables.Receivable itsGoods = new ProductionReceivables.Receivable(goods.salesOrderId(),
                sent.id(), sent.code(), List.of(new ProductionReceivables.Goods(first.sku(), first.itemId(),
                first.cartonUnitId(), first.unitsPerCarton(), 1, sent.lines().getFirst().id())));
        List<String> remade = onTheBin(receivables.received(itsGoods, RECEIVING, 1, clerk));
        shippingWarehouses.topUpWaiting(goods.sku());
        UUID damagedAgain = writeOff(remade.getFirst(), StockAdjustmentReason.DAMAGED);

        assertThat(codeOf(() -> productionOrders.drawUpReplacement(goods.productionOrderId(), Set.of(damagedAgain),
                null, coordinator))).as("the carton is of the replacement's lot, not the first production order's")
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_WRITE_OFF_INVALID);
        ProductionOrder nested = productionOrders.drawUpReplacement(sent.id(), Set.of(damagedAgain), null,
                coordinator);
        assertThat(nested.replacement().orElseThrow().replacesId()).isEqualTo(sent.id());
        assertThat(nested.lines()).singleElement().extracting(ProductionOrder.Line::cartons).isEqualTo(1);
    }
}
