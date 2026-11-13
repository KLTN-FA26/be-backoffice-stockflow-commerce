package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.CountReceivingPackagesCommand.PackageCount;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.service.StockOperations;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-92: the stock primitive putaway stands on. A line of 2000 is counted as two cartons of the standard 1000 and
 * received; a carton goes to a bin as AVAILABLE without being opened. (Loose putaway is gone with team plan 4C-2:
 * a receipt puts every unit into a carton, kltn-docs 03 BR-12, 05 BR-08.)
 */
@ProductionReceiptTest
class PutawayStockIntegrationTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final String BIN = "HCM-A01-1-A";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired StockOperations stockOperations;
    @Autowired StockMovementLog ledger;
    @Autowired ProductionReceivables receivables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID carton;
    private UUID clerk;
    private UUID line;
    private String lpn;

    @BeforeEach
    void setUp() {
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        // Two cartons of 1000 ordered; the clerk counts them below, so the line starts with none labelled.
        ProductionReceivables.Receivable goods = receivables.sent(DemoData.WAREHOUSE_HCM, 2, 1000);
        sku = goods.sku();
        carton = goods.cartonUnitId();
        line = receivables.uncountedLine(goods, receivables.draftReceipt(goods, clerk), RECEIVING, 2);
        lpn = inventory.countReceivingPackages(new CountReceivingPackagesCommand(line, new Sku(sku), carton,
                RECEIVING, List.of(PackageCount.fresh(), PackageCount.fresh()), clerk))
                .getFirst().lpn();
        inventory.receive(new ReceiveStockCommand(line, new Sku(sku), "LOT-FX", RECEIVING, 2000, clerk, RECEIVED));
    }

    @Test
    @DisplayName("a carton put away as AVAILABLE stays one whole carton: same row, now AVAILABLE at the bin")
    void cartonPutAwayAsAvailableStaysWhole() {
        UUID cartonId = jdbc.queryForObject("select id from inventory.stock_package where lpn = ?", UUID.class, lpn);
        UUID row = jdbc.queryForObject("select id from inventory.stock_item where stock_package_id = ?", UUID.class,
                cartonId);

        inventory.reclassifyStockPackages(new ReclassifyStockPackagesCommand(lpn, BIN, StockDisposition.AVAILABLE,
                MoveReference.PUTAWAY_TASK, Identifiers.newId(), clerk, null));

        assertThat(jdbc.queryForObject("select location_code || ':' || status"
                + " from inventory.stock_package where lpn = ?", String.class, lpn))
                .isEqualTo(BIN + ":IN_STOCK");
        assertThat(jdbc.queryForObject("select id || ':' || location_code || ':' || status || ':' || on_hand"
                + " from inventory.stock_item where stock_package_id = ?", String.class, cartonId))
                .isEqualTo(row + ":" + BIN + ":AVAILABLE:1000");
    }
}
