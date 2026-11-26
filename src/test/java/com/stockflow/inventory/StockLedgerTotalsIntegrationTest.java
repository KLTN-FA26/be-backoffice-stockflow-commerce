package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.CartonReturns;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReceiveReturnedCartonsCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.api.StockLedgerTotals;
import com.stockflow.inventory.api.StockMovementTotals;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.consumption.PutBackCommand;
import com.stockflow.inventory.internal.domain.AdjustmentDirection;
import com.stockflow.inventory.internal.service.BookVisibility;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.CartonRows;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The stock ledger summed per SKU and warehouse (2026-11-14 plan, BE2-C; kltn-docs 20 §4.7) over flows that really
 * write it: a production order's goods received, put away, moved, picked for an order, one carton put back, one brought
 * back by a return receipt, cartons written off and one found again. Each column takes exactly its own kind, the moves
 * inside the warehouse change nothing, and - the point of the report - the ledger's closing is the stock's on hand.
 *
 * <p>That last invariant is the report's warrant, and is checked here rather than over the whole demo database: the
 * demo seed loaded its stock as rows without receipts (triggers off, V20261111000001), so its SKUs have on hand that no
 * ledger line explains. {@code tools/db/stock_ledger_balance.sql} reports those.</p>
 */
@ProductionReceiptTest
class StockLedgerTotalsIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String RECEIVING = "HCM-RCV01";
    private static final String BIN = "HCM-B01-1-A";
    private static final String OTHER_BIN = "HCM-B01-1-B";
    private static final String QUARANTINE = "HCM-QC01";
    private static final int PER_CARTON = 10;

    @Autowired ProductionReceivables receivables;
    @Autowired InventoryService inventory;
    @Autowired PickConsumption consumption;
    @Autowired CartonReturns returns;
    @Autowired StockOperations operations;
    @Autowired StockLedgerTotals ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID clerk;
    private UUID manager;
    private ProductionReceivables.Receivable goods;
    private String sku;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        goods = receivables.sent(DemoData.WAREHOUSE_HCM, 6, PER_CARTON);
        sku = goods.sku();
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    /** The production order's {@code cartons} cartons counted and received into the receiving area: their LPNs. */
    private List<String> received(int cartons) {
        GoodsReceipts.ReceiptView draft = receivables.draftReceipt(goods, clerk);
        UUID line = receivables.uncountedLine(goods, draft, RECEIVING, cartons);
        List<String> lpns = receivables.countCartons(goods.first(), line, RECEIVING, cartons, clerk).stream()
                .map(StockPackageView::lpn).sorted().toList();
        inventory.receive(new ReceiveStockCommand(line, new Sku(sku), goods.productionCode(), RECEIVING,
                cartons * PER_CARTON, clerk, Instant.now()));
        return lpns;
    }

    private UUID order() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                     currency, placed_at, version, created_at, contract_signed_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1000, 'VND', NOW(), 0, NOW(), NOW())""",
                order, "SO-20991230-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return order;
    }

    private String lpnPickedBy(UUID pickLine) {
        return jdbc.queryForObject("""
                SELECT p.lpn FROM inventory.stock_movement m
                  JOIN inventory.stock_package p ON p.id = m.stock_package_id
                 WHERE m.movement_type = 'PICK' AND m.reference_id = ?""", String.class, pickLine);
    }

    private void adjust(String lpn, AdjustmentDirection direction, StockAdjustmentReason reason) {
        UUID asked = operations.requestPackageAdjustment(new StockOperations.PackageAdjustmentRequest(lpn, direction,
                reason, null, clerk)).adjustmentId();
        operations.approve(asked, manager);
    }

    private StockMovementTotals totals(LocalDate from, LocalDate to) {
        List<StockMovementTotals> rows = ledger.movementTotals(from, to, null, List.of(sku));
        assertThat(rows).as("one warehouse, one row").hasSize(1);
        return rows.getFirst();
    }

    private long onHand() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand), 0) FROM inventory.stock_item
                 WHERE sku = ? AND location_code LIKE 'HCM-%'""", Long.class, sku);
    }

    /**
     * A line into {@link #BIN} at {@code at}, naming a carton of {@code quantity} units standing there: a ledger line
     * is its carton, whole, and counts for the carton's standard count (ADR-0011), so the carton is made of that size.
     * The test that uses it reads the ledger's days and units, not the stock.
     */
    private void ledgerLine(int quantity, Instant at) {
        tx.executeWithoutResult(s -> {
            UUID carton = CartonRows.stockOfUnit(jdbc, sku, CartonRows.unitOf(jdbc, sku, quantity), BIN, 1,
                    goods.productionCode(), at, "AVAILABLE").getFirst().id();
            jdbc.update("""
                    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code,
                        to_location_code, to_status, reference_type, reference_id, occurred_at, version,
                        created_at, stock_package_id)
                    VALUES (?, 'ADJUSTMENT', ?, ?, NULL, ?, 'AVAILABLE', 'STOCK_ADJUSTMENT', ?, ?, 0, NOW(), ?)""",
                    Identifiers.newId(), sku, goods.productionCode(), BIN, Identifiers.newId(),
                    OffsetDateTime.ofInstant(at, ZoneOffset.UTC), carton);
        });
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every column from its own flow; moves inside the warehouse change nothing; closing = on hand")
    void everyColumnFromRealFlows() {
        List<String> lpns = received(6);                                                    // +60 received
        tx.executeWithoutResult(s -> inventory.reclassifyStockPackages(new ReclassifyStockPackagesCommand(lpns, BIN,
                StockDisposition.AVAILABLE, MoveReference.PUTAWAY_TASK, Identifiers.newId(), clerk, "putaway")));
        StockMovementTotals afterPutaway = totals(today, today);
        assertThat(afterPutaway.receivedUnits()).isEqualTo(60);
        assertThat(afterPutaway.closingUnits()).as("putaway is a move inside HCM").isEqualTo(60);
        inventory.moveStockPackages(new MoveStockPackagesCommand(List.of(lpns.get(5)), OTHER_BIN,
                MoveReference.MOVE_TASK, Identifiers.newId(), clerk));
        assertThat(totals(today, today)).as("a carton moved between two bins: nothing changes")
                .isEqualTo(afterPutaway);

        // Two cartons held for an order, allocated, picked.                                   -20 picked
        UUID order = order();
        tx.executeWithoutResult(s -> Holds.units(inventory, order, sku, "HCM", 2 * PER_CARTON));
        List<StockReservation> allocated = tx.execute(s -> inventory.allocate(new AllocateStockCommand(order, "HCM")))
                .allocations().getFirst().holds();
        assertThat(allocated).hasSize(2);
        List<UUID> pickLines = List.of(Identifiers.newId(), Identifiers.newId());
        for (int i = 0; i < 2; i++) {
            UUID hold = allocated.get(i).reservationId();
            UUID line = pickLines.get(i);
            tx.executeWithoutResult(s -> consumption.consumeForPick(hold, line, clerk));
        }
        String putBack = lpnPickedBy(pickLines.get(0));
        String returned = lpnPickedBy(pickLines.get(1));
        // One picked carton put back onto a bin (the order cancelled), the other shipped and brought back by a return
        // receipt into quarantine.                                                             +10 put back, +10 returned
        tx.executeWithoutResult(s -> consumption.putBack(new PutBackCommand(pickLines.get(0),
                allocated.get(0).reservationId(), putBack, BIN, clerk)));
        tx.executeWithoutResult(s -> returns.receive(new ReceiveReturnedCartonsCommand(Identifiers.newId(), order,
                QUARANTINE, List.of(returned), clerk, Instant.now())));
        // Two cartons never picked written off (one damaged, one lost) and the lost one found again. -20 / +10
        List<String> untouched = lpns.stream().filter(lpn -> !lpn.equals(putBack) && !lpn.equals(returned)).toList();
        adjust(untouched.get(0), AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.DAMAGED);
        adjust(untouched.get(1), AdjustmentDirection.WRITE_OFF, StockAdjustmentReason.LOST);
        adjust(untouched.get(1), AdjustmentDirection.RESTOCK, StockAdjustmentReason.FOUND);

        StockMovementTotals period = totals(today, today);
        assertThat(period.warehousePrefix()).isEqualTo("HCM");
        assertThat(period.sku()).isEqualTo(sku);
        assertThat(period.openingUnits()).isZero();
        assertThat(period.receivedUnits()).isEqualTo(60);
        assertThat(period.returnedUnits()).isEqualTo(10);
        assertThat(period.putBackUnits()).isEqualTo(10);
        assertThat(period.pickedUnits()).isEqualTo(20);
        assertThat(period.adjustedInUnits()).isEqualTo(10);
        assertThat(period.adjustedOutUnits()).isEqualTo(20);
        assertThat(period.otherInUnits()).isZero();
        assertThat(period.otherOutUnits()).isZero();
        assertThat(period.closingUnits()).isEqualTo(60 + 10 + 10 - 20 + 10 - 20);
        assertThat(period.closingUnits()).as("the ledger balances against the stock: closing is on hand")
                .isEqualTo(onHand());

        // After the period: all of it is opening, nothing moved, the same closing. Before it: no line yet.
        StockMovementTotals tomorrow = totals(today.plusDays(1), today.plusDays(1));
        assertThat(tomorrow.openingUnits()).isEqualTo(period.closingUnits());
        assertThat(tomorrow.closingUnits()).isEqualTo(period.closingUnits());
        assertThat(tomorrow.receivedUnits() + tomorrow.pickedUnits() + tomorrow.adjustedOutUnits()).isZero();
        assertThat(ledger.movementTotals(today.minusDays(7), today.minusDays(1), null, List.of(sku))).isEmpty();

        // Filters: the warehouse by prefix, in any case; another warehouse; no SKU at all.
        assertThat(ledger.movementTotals(today, today, List.of("hcm"), List.of(sku))).containsExactly(period);
        assertThat(ledger.movementTotals(today, today, List.of("ZZZ"), List.of(sku))).isEmpty();
        assertThat(ledger.movementTotals(today, today, null, List.of())).isEmpty();

        // A caller limited to warehouses reads theirs only, whatever they ask.
        CurrentUser hcm = new CurrentUser(UUID.randomUUID(), "kho", Set.of(Role.WAREHOUSE_MANAGER), Set.of(),
                DataScope.WAREHOUSE, Set.of("HCM"), Set.of(DemoData.WAREHOUSE_HCM));
        CurrentUser elsewhere = new CurrentUser(UUID.randomUUID(), "kho", Set.of(Role.WAREHOUSE_MANAGER), Set.of(),
                DataScope.WAREHOUSE, Set.of("ZZZ"), Set.of(UUID.randomUUID()));
        assertThat(WithCurrentUser.call(hcm, DataScope.WAREHOUSE, () -> ledger.movementTotals(today, today, null,
                List.of(sku)))).containsExactly(period);
        assertThat(WithCurrentUser.call(elsewhere, DataScope.WAREHOUSE, () -> ledger.movementTotals(today, today, null,
                List.of(sku)))).isEmpty();
        assertThat(WithCurrentUser.call(elsewhere, DataScope.WAREHOUSE, () -> ledger.movementTotals(today, today,
                List.of("HCM"), List.of(sku)))).as("asking for HCM does not widen the scope").isEmpty();
    }

    @Test
    @DisplayName("a period is business days: a line at 23:59:59 Vietnam time is that day's, one at 00:00 the next's")
    void businessDayBounds() {
        ledgerLine(5, Instant.parse("2026-02-28T16:59:59Z"));      // 28/02 23:59:59 in Ho Chi Minh City
        ledgerLine(7, Instant.parse("2026-02-28T17:00:00Z"));      // 01/03 00:00:00
        ledgerLine(11, Instant.parse("2026-03-01T16:59:59.999Z")); // 01/03 23:59:59.999
        ledgerLine(13, Instant.parse("2026-03-01T17:00:00Z"));     // 02/03 00:00:00

        LocalDate march1 = LocalDate.of(2026, 3, 1);
        StockMovementTotals first = totals(march1, march1);
        assertThat(first.openingUnits()).isEqualTo(5);
        assertThat(first.adjustedInUnits()).isEqualTo(7 + 11);
        assertThat(first.closingUnits()).as("the line of 02/03 00:00 is the next day's").isEqualTo(23);
        StockMovementTotals february28 = totals(march1.minusDays(1), march1.minusDays(1));
        assertThat(february28.openingUnits()).isZero();
        assertThat(february28.adjustedInUnits()).isEqualTo(5);
        assertThat(february28.closingUnits()).isEqualTo(5);
        assertThat(totals(march1, march1.plusDays(1)).closingUnits()).isEqualTo(36);

        // The stock history drills down to exactly those lines: same days, same warehouse.
        var history = operations.ledger(new StockOperations.LedgerFilter(sku, null, null, march1, march1,
                DemoData.WAREHOUSE_HCM), 0, 20, BookVisibility.FULL);
        assertThat(history.items()).extracting(StockOperations.LedgerLine::quantity).containsExactly(11, 7);
        assertThat(operations.ledger(new StockOperations.LedgerFilter(sku, null, null, null, march1.minusDays(1),
                null), 0, 20, BookVisibility.FULL).items()).extracting(StockOperations.LedgerLine::quantity)
                .containsExactly(5);
        assertThat(code(catchThrowable(() -> operations.ledger(new StockOperations.LedgerFilter(sku, null, null,
                march1, march1.minusDays(1), null), 0, 20, BookVisibility.FULL))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> operations.ledger(new StockOperations.LedgerFilter(sku, null, null,
                null, null, UUID.randomUUID()), 0, 20, BookVisibility.FULL))))
                .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
    }
}
