package com.stockflow.inventory;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ProductionCartonWrittenOffDamaged;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.internal.domain.AdjustmentDirection;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.inventory.internal.service.StockOperations.PackageAdjustmentRequest;
import com.stockflow.notification.api.ProductionOrderDirectory;
import com.stockflow.notification.api.ShortSalesOrder;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.DemoData;
import com.stockflow.support.ProductionReceiptTest;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kltn-docs 20 §4.8 step 3 and 17 §4.13 step 51: when a {@code DAMAGED} write-off of a carton of a production order's lot
 * is approved, the Production Coordinator is told - the carton, its SKU, its lot (the production order's code) and the
 * sales order left short. Here the announcement the approval publishes, and the sales order the production order was
 * drawn up for being found short; what the coordinator reads is {@code ProductionCartonDamagedNotificationListenerTest}'s.
 * A write-off of a carton of any other lot, or for any other reason, announces nothing.
 */
@ProductionReceiptTest
@Import(RecordedEvents.class)
class ProductionCartonDamagedIntegrationTest {

    private static final String BIN = "HCM-B01-1-A";
    private static final String RECEIVING = "HCM-RCV01";

    @Autowired ProductionReceivables receivables;
    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
    @Autowired ProductionOrderDirectory productionOrders;
    @Autowired RecordedEvents.Recorder recorded;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private UUID clerk;
    private UUID manager;
    private ProductionReceivables.Receivable goods;
    private List<String> lpns;

    @BeforeEach
    void setUp() {
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        goods = receivables.sent(DemoData.WAREHOUSE_HCM, 4, 2);
        GoodsReceipts.ReceiptView receipt = receivables.received(goods, RECEIVING, 3, clerk);
        lpns = receipt.lines().getFirst().packages().stream().map(GoodsReceipts.PackageView::lpn).toList();
        tx.executeWithoutResult(s -> inventory.reclassifyStockPackages(new ReclassifyStockPackagesCommand(lpns, BIN,
                StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, Identifiers.newId(), clerk, "putaway")));
        recorded.clear();
    }

    private void writeOff(String lpn, StockAdjustmentReason reason) {
        var asked = operations.requestPackageAdjustment(new PackageAdjustmentRequest(lpn,
                AdjustmentDirection.WRITE_OFF, reason, reason == StockAdjustmentReason.OTHER ? "why" : null, clerk));
        operations.approve(asked.adjustmentId(), manager);
    }

    @Test
    @DisplayName("an approved DAMAGED write-off of a carton of a production order's lot announces the carton, SKU, lot "
            + "and production order, once; the sales order it was drawn up for is found short")
    void damagedCartonOfAProductionLotIsAnnounced() {
        String lpn = lpns.getFirst();

        writeOff(lpn, StockAdjustmentReason.DAMAGED);

        List<ProductionCartonWrittenOffDamaged> told = recorded.ofType(ProductionCartonWrittenOffDamaged.class);
        assertThat(told).singleElement().satisfies(event -> {
            assertThat(event.lpn()).isEqualTo(lpn);
            assertThat(event.sku()).isEqualTo(goods.sku());
            assertThat(event.lotNumber()).as("the lot is the production order's code").isEqualTo(goods.productionCode());
            assertThat(event.productionOrderId()).isEqualTo(goods.productionOrderId());
            assertThat(event.warehouseId()).isEqualTo(DemoData.WAREHOUSE_HCM);
            assertThat(event.approvedBy()).isEqualTo(manager);
            assertThat(event.adjustmentNumber()).isNotBlank();
        });
        ShortSalesOrder shortOrder = productionOrders.shortSalesOrder(goods.productionOrderId()).orElseThrow();
        assertThat(shortOrder.orderNumber()).isEqualTo(jdbc.queryForObject(
                "SELECT order_number FROM ordering.customer_order WHERE id = ?", String.class, goods.salesOrderId()));
        assertThat(shortOrder.shortCartons()).as("nothing of the order is held: all of it is short").isPositive();
    }

    @Test
    @DisplayName("any other reason announces nothing")
    void anotherReasonAnnouncesNothing() {
        writeOff(lpns.getFirst(), StockAdjustmentReason.OTHER);

        assertThat(recorded.ofType(ProductionCartonWrittenOffDamaged.class)).isEmpty();
    }

    @Test
    @DisplayName("a production order that does not exist leaves no sales order short")
    void noShortOrderForAnUnknownProductionOrder() {
        assertThat(productionOrders.shortSalesOrder(UUID.randomUUID())).isEmpty();
    }
}
