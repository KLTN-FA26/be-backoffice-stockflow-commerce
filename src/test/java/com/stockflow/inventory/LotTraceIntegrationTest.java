package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.internal.domain.LotTrace;
import com.stockflow.inventory.internal.service.LotTraces;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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
 */
@IntegrationTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Import(PostgresContainer.class)
class LotTraceIntegrationTest {

    private static final String TABLE = "TABLE-OAK-160";   // lot- and expiry-tracked in the demo data
    private static final String BIN = "HCM-B01-1-A";

    @Autowired GoodsReceipts receipts;
    @Autowired InventoryService inventory;
    @Autowired PickConsumption consumption;
    @Autowired LotTraces traces;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;

    private UUID clerk;
    private String lotNumber;
    private LocalDate expiry;

    @BeforeEach
    void setUp() {
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        lotNumber = "TR-" + UUID.randomUUID().toString().substring(0, 8);
        // Earlier than any other TABLE stock, so FEFO allocates these lots first.
        expiry = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(20);
    }

    /** A CONFIRMED purchase order of {@code quantity} TABLE, received in full into HCM-RCV01 as lot {@link #lotNumber}. */
    private GoodsReceipts.ReceiptView received(int quantity) {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);   // four eyes (ck_purchase_orders_four_eyes)
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, "PO-LT-" + po.toString().substring(24), submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at)
                    SELECT ?, ?, ?, 1, id, ?, 1000, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                    Identifiers.newId(), po, revision, quantity, TABLE);
        });
        UUID line = jdbc.queryForObject("SELECT id FROM procurement.purchase_order_lines WHERE po_id = ?", UUID.class, po);
        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(po, "DN-LT", null), clerk);
        receipts.replaceLines(draft.id(), List.of(new GoodsReceipts.NewLine(line, quantity, lotNumber, expiry,
                "HCM-RCV01", null)));
        return receipts.confirm(draft.id(), clerk);
    }

    private UUID lotOf(GoodsReceipts.ReceiptView receipt) {
        return jdbc.queryForObject("SELECT id FROM inventory.stock_lot WHERE goods_receipt_id = ?", UUID.class,
                receipt.id());
    }

    /** Putaway: the lot's INBOUND units at the receiving area onto {@link #BIN} as AVAILABLE. */
    private void putAway(UUID lot, int quantity) {
        Instant receivedAt = jdbc.queryForObject(
                "SELECT received_at FROM inventory.stock_lot WHERE id = ?", java.sql.Timestamp.class, lot).toInstant();
        tx.executeWithoutResult(s -> inventory.reclassify(new ReclassifyStockCommand(new Sku(TABLE), lotNumber,
                "HCM-RCV01", BIN, quantity, StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, Identifiers.newId(),
                clerk, "putaway", receivedAt)));
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
    @DisplayName("a reused lot number is two lots; each is traced from supplier to the order it was picked for")
    void receiptToOrder() throws Exception {
        GoodsReceipts.ReceiptView first = received(3);
        GoodsReceipts.ReceiptView second = received(2);
        UUID lot1 = lotOf(first);
        UUID lot2 = lotOf(second);
        assertThat(lot1).isNotEqualTo(lot2);

        putAway(lot1, 3);
        putAway(lot2, 2);
        UUID order = order();
        tx.executeWithoutResult(s -> inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(TABLE), 4,
                order)));
        List<StockReservation> allocated = tx.execute(s -> inventory.allocate(new AllocateStockCommand(order, "HCM")))
                .allocations().get(0).holds();
        assertThat(allocated).extracting(StockReservation::locationCode).containsOnly(BIN);
        assertThat(allocated).extracting(StockReservation::quantity).containsExactly(3, 1);   // FEFO tie: older receipt first
        UUID pickLine = Identifiers.newId();
        tx.executeWithoutResult(s -> consumption.consumeForPick(allocated.get(0).reservationId(), pickLine, clerk));

        // The lot as received, and what is left of it.
        LotTrace trace = traces.trace(lot1, null);
        assertThat(trace.lot().origin()).isEqualTo("GOODS_RECEIPT");
        assertThat(trace.lot().lotNumber()).isEqualTo(lotNumber);
        assertThat(trace.lot().goodsReceiptId()).isEqualTo(first.id());
        assertThat(trace.lot().goodsReceiptNumber()).isEqualTo(first.number());
        assertThat(trace.lot().purchaseOrderNumber()).startsWith("PO-LT-");
        assertThat(trace.lot().supplierName()).isEqualTo("Xưởng gỗ Việt");
        assertThat(trace.lot().receivingWarehouseId()).isEqualTo(DemoData.WAREHOUSE_HCM);
        assertThat(trace.lot().receivedQuantity()).isEqualTo(3);
        assertThat(trace.lot().onHand()).isZero();
        assertThat(trace.lot().available()).isZero();
        String orderNumber = jdbc.queryForObject("SELECT order_number FROM ordering.customer_order WHERE id = ?",
                String.class, order);
        assertThat(trace.orders()).singleElement().satisfies(leg -> {
            assertThat(leg.orderId()).isEqualTo(order);
            assertThat(leg.orderNumber()).as("snapshot on the ledger line").isEqualTo(orderNumber);
            assertThat(leg.picked()).isEqualTo(3);
        });
        assertThat(trace.holds()).singleElement().satisfies(hold -> {
            assertThat(hold.orderId()).isEqualTo(order);
            assertThat(hold.allocatedFrom()).isNotNull();
            assertThat(hold.orderNumber()).as("snapshot on the allocation").isEqualTo(orderNumber);
            assertThat(hold.status()).isEqualTo("CONSUMED");
        });
        var ledger = traces.movements(lot1, null, null, null, 0, 20).items();
        assertThat(ledger).extracting(LotTrace.Movement::type).containsExactly("RECEIPT", "MOVE", "PICK");
        assertThat(ledger.get(2).referenceId()).isEqualTo(pickLine);
        assertThat(ledger.get(2).orderId()).isEqualTo(order);
        assertThat(ledger.get(2).orderNumber()).isEqualTo(orderNumber);

        LotTrace other = traces.trace(lot2, null);
        assertThat(other.lot().goodsReceiptId()).isEqualTo(second.id());
        assertThat(other.lot().onHand()).isEqualTo(2);
        assertThat(other.lot().held()).isEqualTo(1);
        assertThat(other.lot().available()).as("on hand minus what the allocation holds").isEqualTo(1);
        assertThat(other.balances()).singleElement().satisfies(b -> {
            assertThat(b.locationCode()).isEqualTo(BIN);
            assertThat(b.onHand()).isEqualTo(2);
            assertThat(b.held()).isEqualTo(1);
        });
        assertThat(other.orders()).isEmpty();

        // Filters: by lot number both lots, newest receipt first; paged one at a time.
        var byNumber = traces.lots(new LotTraces.Query(TABLE, lotNumber, null, null, null, 0, 20));
        assertThat(byNumber.items()).extracting(LotTrace.Summary::lotId).containsExactly(lot2, lot1);
        var page = traces.lots(new LotTraces.Query(null, lotNumber, null, null, null, 1, 1));
        assertThat(page.items()).extracting(LotTrace.Summary::lotId).containsExactly(lot1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(traces.lots(new LotTraces.Query(TABLE, lotNumber, DemoData.WAREHOUSE_HCM, null, null, 0, 20))
                .items()).hasSize(2);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(traces.lots(new LotTraces.Query(TABLE, lotNumber, null, today, today, 0, 20)).items()).hasSize(2);
        assertThat(traces.lots(new LotTraces.Query(TABLE, lotNumber, null, today.minusDays(10), today.minusDays(5),
                0, 20)).items()).isEmpty();
        assertThat(traces.movements(lot1, null, today.plusDays(1), null, 0, 20).items()).isEmpty();
        assertThat(((BusinessException) catchThrowable(() -> traces.lots(new LotTraces.Query(null, null, null,
                today, today.minusDays(1), 0, 20)))).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        // Data scope: an HCM clerk sees the lots, a clerk of another warehouse neither lists nor opens them.
        CurrentUser hcm = clerkOf(DemoData.WAREHOUSE_HCM, "HCM");
        CurrentUser elsewhere = clerkOf(UUID.randomUUID(), "ZZZ");
        assertThat(WithCurrentUser.call(hcm, hcm.scope(), () -> traces.lots(
                new LotTraces.Query(TABLE, lotNumber, null, null, null, 0, 20))).items()).hasSize(2);
        assertThat(WithCurrentUser.call(elsewhere, elsewhere.scope(), () -> traces.lots(
                new LotTraces.Query(TABLE, lotNumber, null, null, null, 0, 20))).items()).isEmpty();
        assertThat(((BusinessException) catchThrowable(() -> WithCurrentUser.call(elsewhere, elsewhere.scope(),
                () -> traces.trace(lot1, null)))).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(((BusinessException) catchThrowable(() -> WithCurrentUser.call(elsewhere, elsewhere.scope(),
                () -> traces.lots(new LotTraces.Query(null, null, DemoData.WAREHOUSE_HCM, null, null, 0, 20))))).errorCode())
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);

        // The HTTP API answers with the same report.
        mvc.perform(get("/api/v1/inventory/lots").param("sku", TABLE).param("lotNumber", lotNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[1].supplierName").value("Xưởng gỗ Việt"));
        mvc.perform(get("/api/v1/inventory/lots/{id}", lot1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders[0].orderId").value(order.toString()));
        mvc.perform(get("/api/v1/inventory/lots/{id}/movements", lot1).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        mvc.perform(get("/api/v1/inventory/lots/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an expired lot is on hand but never available; a fresh one is, less what is held")
    void expiredLotIsNotAvailable() {
        String sku = "LOTX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku, "Lot fixture",
                    sku.toLowerCase(java.util.Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Lot fixture','')", Identifiers.newId(), product, sku);
            for (Object[] row : List.of(new Object[] {"HCM-A01-1-A", today.minusDays(1), 4, 0},
                    new Object[] {"HCM-A01-1-B", today.plusDays(9), 5, 2})) {
                jdbc.update("""
                        INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved,
                                                          status, received_at, version, created_at)
                        VALUES (?, ?, ?, 'X1', ?, ?, ?, 'AVAILABLE', NOW() - interval '30 days', 0, NOW())""",
                        Identifiers.newId(), sku, row[0], row[1], row[2], row[3]);
            }
        });

        var lots = traces.lots(new LotTraces.Query(sku, "X1", null, null, null, 0, 20)).items();
        assertThat(lots).hasSize(2).allSatisfy(lot -> assertThat(lot.origin()).isEqualTo("UNTRACED"));
        assertThat(lots).filteredOn(lot -> lot.expiryDate().isBefore(today)).singleElement().satisfies(expired -> {
            assertThat(expired.onHand()).isEqualTo(4);
            assertThat(expired.available()).isZero();
        });
        assertThat(lots).filteredOn(lot -> !lot.expiryDate().isBefore(today)).singleElement().satisfies(fresh -> {
            assertThat(fresh.onHand()).isEqualTo(5);
            assertThat(fresh.held()).isEqualTo(2);
            assertThat(fresh.available()).isEqualTo(3);
        });
    }
}
