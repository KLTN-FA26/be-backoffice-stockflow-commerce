package com.stockflow.inventory;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.internal.domain.LotTrace;
import com.stockflow.inventory.internal.service.LotTraces;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.DemoData;
import com.stockflow.support.Holds;
import com.stockflow.support.ProductionReceiptTest;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-142/153 end to end on a real database: two goods receipts reuse one lot number and become two lots, each
 * knowing its receipt, purchase order and supplier; the lot is traced through putaway, the allocation the order's
 * release made, the pick that consumed it and the order it left for, with the balance left now; and the report
 * filters by SKU, lot number, warehouse and day, pages, and keeps a warehouse-bound clerk to their warehouses.
 *
 * <p>Since team plan 4C-2 every unit is received in a carton (kltn-docs 03 BR-12): the demo table's CARTON unit holds
 * one piece, so a lot of 3 is three cartons, each with its own receipt, putaway and pick ledger line and its own
 * allocation.</p>
 */
@ProductionReceiptTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class LotTraceIntegrationTest {

    private static final String BIN = "HCM-B01-1-A";
    private static final String RECEIVING = "HCM-RCV01";

    @Autowired ProductionReceivables receivables;
    @Autowired InventoryService inventory;
    @Autowired PickConsumption consumption;
    @Autowired LotTraces traces;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;

    private UUID clerk;
    private ProductionReceivables.Receivable goods;
    private String sku;

    @BeforeEach
    void setUp() {
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        // 6 cartons of 2 units ordered of a SKU of its own, sent to HCM: its lot is the production order's, whatever
        // the receipts. Every unit is in a carton, and an order line takes whole cartons (team plan 4C-2).
        goods = receivables.sent(DemoData.WAREHOUSE_HCM, 6, 2);
        sku = goods.sku();
    }

    /** {@code cartons} of the production order counted and confirmed into {@link #RECEIVING}: a receipt of its own. */
    private GoodsReceipts.ReceiptView received(int cartons) {
        return receivables.received(goods, RECEIVING, cartons, clerk);
    }

    private UUID lot() {
        return jdbc.queryForObject("SELECT id FROM inventory.stock_lot WHERE production_order_id = ?", UUID.class,
                goods.productionOrderId());
    }

    /** Putaway: the receipt's cartons from the receiving area onto {@link #BIN} as AVAILABLE. */
    private void putAway(GoodsReceipts.ReceiptView receipt) {
        List<String> lpns = receipt.lines().getFirst().packages().stream().map(GoodsReceipts.PackageView::lpn).toList();
        tx.executeWithoutResult(s -> inventory.reclassifyStockPackages(new ReclassifyStockPackagesCommand(lpns, BIN,
                StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, Identifiers.newId(), clerk, "putaway")));
    }

    private UUID order() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                     currency, placed_at, version, created_at)
                VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW())""",
                order, "SO-20991231-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return order;
    }

    private static CurrentUser clerkOf(UUID warehouse, String prefix) {
        return new CurrentUser(UUID.randomUUID(), "kho", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of(prefix), Set.of(warehouse));
    }

    @Test
    @DisplayName("a production order's two receipts are one lot; it is traced from the production order to the order it was picked for")
    void receiptToOrder() throws Exception {
        GoodsReceipts.ReceiptView first = received(4);
        GoodsReceipts.ReceiptView second = received(2);
        UUID lot = lot();   // one lot per production order and SKU, across its receipts (kltn-docs 03 BR-03)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_lot WHERE sku = ?", Long.class, sku))
                .isEqualTo(1L);

        putAway(first);
        putAway(second);
        UUID order = order();
        tx.executeWithoutResult(s -> Holds.units(inventory, order, sku, "HCM", 6));
        List<StockReservation> allocated = tx.execute(s -> inventory.allocate(new AllocateStockCommand(order, "HCM")))
                .allocations().get(0).holds();
        assertThat(allocated).extracting(StockReservation::locationCode).containsOnly(BIN);
        assertThat(allocated).as("whole cartons of two").extracting(StockReservation::quantity)
                .containsExactly(2, 2, 2);
        // Two of the three are picked; the third stays held.
        List<UUID> pickLines = List.of(Identifiers.newId(), Identifiers.newId());
        for (int i = 0; i < pickLines.size(); i++) {
            UUID hold = allocated.get(i).reservationId();
            UUID pickLine = pickLines.get(i);
            tx.executeWithoutResult(s -> consumption.consumeForPick(hold, pickLine, clerk));
        }

        // The lot as received, and what is left of it.
        LotTrace trace = traces.trace(lot, null);
        assertThat(trace.lot().origin()).isEqualTo("PRODUCTION_ORDER");
        assertThat(trace.lot().lotNumber()).isEqualTo(goods.productionCode());
        assertThat(trace.lot().productionOrderId()).isEqualTo(goods.productionOrderId());
        assertThat(trace.lot().productionOrderCode()).isEqualTo(goods.productionCode());
        assertThat(trace.lot().receivingWarehouseId()).isEqualTo(DemoData.WAREHOUSE_HCM);
        assertThat(trace.lot().receivedQuantity()).isEqualTo(12);
        assertThat(trace.lot().onHand()).as("12 received, 4 picked").isEqualTo(8);
        assertThat(trace.lot().held()).as("the third carton's hold").isEqualTo(2);
        assertThat(trace.lot().available()).isEqualTo(6);
        String orderNumber = jdbc.queryForObject("SELECT order_number FROM ordering.customer_order WHERE id = ?",
                String.class, order);
        assertThat(trace.orders()).singleElement().satisfies(leg -> {
            assertThat(leg.orderId()).isEqualTo(order);
            assertThat(leg.orderNumber()).as("snapshot on the ledger line").isEqualTo(orderNumber);
            assertThat(leg.picked()).isEqualTo(4);
        });
        assertThat(trace.holds()).hasSize(3).allSatisfy(hold -> {
            assertThat(hold.orderId()).isEqualTo(order);
            assertThat(hold.allocatedFrom()).isNotNull();
            assertThat(hold.orderNumber()).as("snapshot on the allocation").isEqualTo(orderNumber);
        });
        assertThat(trace.holds()).extracting(LotTrace.Hold::status).containsExactlyInAnyOrder("CONSUMED", "CONSUMED",
                "HELD");
        var ledger = traces.movements(lot, null, null, null, 0, 20).items();
        assertThat(ledger).extracting(LotTrace.Movement::type).as("one line per carton")
                .containsExactlyInAnyOrder("RECEIPT", "RECEIPT", "RECEIPT", "RECEIPT", "RECEIPT", "RECEIPT", "MOVE",
                        "MOVE", "MOVE", "MOVE", "MOVE", "MOVE", "PICK", "PICK");
        List<LotTrace.Movement> picks = ledger.stream().filter(m -> m.type().equals("PICK")).toList();
        assertThat(picks).extracting(LotTrace.Movement::referenceId).containsExactlyInAnyOrderElementsOf(pickLines);
        assertThat(picks).allSatisfy(pick -> {
            assertThat(pick.orderId()).isEqualTo(order);
            assertThat(pick.orderNumber()).isEqualTo(orderNumber);
        });

        // Filters: by lot number (the production order's code), by warehouse, by day; paged.
        var byNumber = traces.lots(new LotTraces.Query(sku, goods.productionCode(), null, null, null, 0, 20));
        assertThat(byNumber.items()).extracting(LotTrace.Summary::lotId).containsExactly(lot);
        var page = traces.lots(new LotTraces.Query(sku, null, null, null, null, 1, 1));
        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(traces.lots(new LotTraces.Query(sku, null, DemoData.WAREHOUSE_HCM, null, null, 0, 20)).items())
                .hasSize(1);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(traces.lots(new LotTraces.Query(sku, null, null, today, today, 0, 20)).items()).hasSize(1);
        assertThat(traces.lots(new LotTraces.Query(sku, null, null, today.minusDays(10), today.minusDays(5), 0, 20))
                .items()).isEmpty();
        assertThat(traces.movements(lot, null, today.plusDays(1), null, 0, 20).items()).isEmpty();
        assertThat(((BusinessException) catchThrowable(() -> traces.lots(new LotTraces.Query(null, null, null,
                today, today.minusDays(1), 0, 20)))).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        // Data scope: an HCM clerk sees the lot, a clerk of another warehouse neither lists nor opens it.
        CurrentUser hcm = clerkOf(DemoData.WAREHOUSE_HCM, "HCM");
        CurrentUser elsewhere = clerkOf(UUID.randomUUID(), "ZZZ");
        assertThat(WithCurrentUser.call(hcm, hcm.scope(), () -> traces.lots(
                new LotTraces.Query(sku, null, null, null, null, 0, 20))).items()).hasSize(1);
        assertThat(WithCurrentUser.call(elsewhere, elsewhere.scope(), () -> traces.lots(
                new LotTraces.Query(sku, null, null, null, null, 0, 20))).items()).isEmpty();
        assertThat(((BusinessException) catchThrowable(() -> WithCurrentUser.call(elsewhere, elsewhere.scope(),
                () -> traces.trace(lot, null)))).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(((BusinessException) catchThrowable(() -> WithCurrentUser.call(elsewhere, elsewhere.scope(),
                () -> traces.lots(new LotTraces.Query(null, null, DemoData.WAREHOUSE_HCM, null, null, 0, 20)))))
                .errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);

        // The HTTP API answers with the same report.
        mvc.perform(get("/api/v1/inventory/lots").param("sku", sku).param("lotNumber", goods.productionCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].productionOrderCode").value(goods.productionCode()));
        mvc.perform(get("/api/v1/inventory/lots/{id}", lot))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders[0].orderId").value(order.toString()));
        mvc.perform(get("/api/v1/inventory/lots/{id}/movements", lot).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(ledger.size()));
        mvc.perform(get("/api/v1/inventory/lots/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a lot's available units are its AVAILABLE units less what is held: nothing expires (01 BR-17)")
    void lotIsAvailableLessWhatIsHeld() {
        String sku = "LOTX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku, "Lot fixture",
                    sku.toLowerCase(java.util.Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Lot fixture','')", Identifiers.newId(), product, sku);
            // Two cartons of one lot received together: the stock-row trigger puts both rows in the same UNTRACED lot.
            java.time.Instant received = java.time.Instant.now().minusSeconds(30L * 24 * 3600);
            com.stockflow.support.CartonRows.carton(jdbc, sku, "HCM-A01-1-A", 4, "X1", received, "AVAILABLE");
            UUID heldRow = com.stockflow.support.CartonRows.carton(jdbc, sku, "HCM-A01-1-B", 5, "X1", received,
                    "AVAILABLE").stockItemId();
            jdbc.update("UPDATE inventory.stock_item SET reserved = 2 WHERE id = ?", heldRow);
        });

        var lots = traces.lots(new LotTraces.Query(sku, "X1", null, null, null, 0, 20)).items();
        assertThat(lots).singleElement().satisfies(lot -> {
            assertThat(lot.origin()).isEqualTo("UNTRACED");
            assertThat(lot.onHand()).isEqualTo(9);
            assertThat(lot.held()).isEqualTo(2);
            assertThat(lot.available()).isEqualTo(7);
        });
    }
}
