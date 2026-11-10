package com.stockflow.order;

import com.stockflow.order.internal.repository.ProductionOrderLineReferences;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.support.DemoData;
import com.stockflow.support.ProductionReceiptTest;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kltn-docs 01 BR-14, 4A-8: what the discontinuation warning counts as "still on its way" - the lines of production
 * orders still open that have not received every carton they order - against the real tables.
 */
@ProductionReceiptTest
class ProductionOrderLineReferencesIntegrationTest {

    @Autowired ProductionReceivables receivables;
    @Autowired ProductionOrderLineReferences references;
    @Autowired ProductionOrderService productionOrders;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private List<ProductionOrderLineReferences.Row> open(String... skus) {
        return references.openLines(List.of(skus), 50);
    }

    @Test
    @DisplayName("a SENT production order with cartons still to come counts; a cancelled one, and one received in full, do not")
    void countsOnlyOpenLinesWithCartonsToCome() {
        ProductionReceivables.Receivable sent = receivables.sent(DemoData.WAREHOUSE_HCM, 2, 10);
        ProductionReceivables.Receivable cancelled = receivables.sent(DemoData.WAREHOUSE_HCM, 2, 10);
        ProductionReceivables.Receivable received = receivables.sent(DemoData.WAREHOUSE_HCM, 2, 10);
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        var order = productionOrders.detail(cancelled.productionOrderId()).order();
        productionOrders.cancel(order.id(), order.version(), "covered by stock", user);
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.production_order_line SET received_cartons = cartons WHERE production_order_id = ?",
                received.productionOrderId()));

        assertThat(open(sent.sku())).singleElement().satisfies(row -> {
            assertThat(row.productionOrderId()).isEqualTo(sent.productionOrderId());
            assertThat(row.code()).isEqualTo(sent.productionCode());
            assertThat(row.sku()).isEqualTo(sent.sku());
        });
        assertThat(open(cancelled.sku())).as("cancelled: nothing more comes").isEmpty();
        assertThat(open(received.sku())).as("every carton received: nothing more comes").isEmpty();
        assertThat(open(sent.sku(), cancelled.sku(), received.sku())).extracting(
                ProductionOrderLineReferences.Row::productionOrderId).containsExactly(sent.productionOrderId());
    }

    @Test
    @DisplayName("part received still counts; the limit caps the lines; no SKU, no lines")
    void partReceivedAndTheLimit() {
        ProductionReceivables.Receivable a = receivables.sent(DemoData.WAREHOUSE_HCM, 3, 10);
        ProductionReceivables.Receivable b = receivables.sent(DemoData.WAREHOUSE_HCM, 2, 10);
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.production_order_line SET received_cartons = 1 WHERE production_order_id = ?",
                a.productionOrderId()));

        assertThat(open(a.sku())).as("1 of 3 cartons received: 2 still to come").hasSize(1);
        assertThat(references.openLines(List.of(a.sku(), b.sku()), 1)).as("capped").hasSize(1);
        assertThat(references.openLines(List.of(a.sku(), b.sku()), 10)).hasSize(2);
        assertThat(references.openLines(List.of(), 10)).isEmpty();
    }
}
