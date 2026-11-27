package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.CountReceivingPackagesCommand.PackageCount;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.api.StockPackageView;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-473 against a real PostgreSQL: the cartons of a goods-receipt line are labelled, recounted and voided while
 * the receipt is a draft, and {@code receive} puts the goods into them - one INBOUND row and one RECEIPT ledger line
 * per carton, the remainder loose.
 *
 * <p>Nothing here runs in a test transaction: V20261020000200 checks a carton against its rows at commit (an IN_STOCK
 * carton holds stock, exactly what was counted into it), so every call commits as production's
 * would. Each test has its own SKU, with a CARTON unit of 1000, and its own production order of 2 cartons (SENT, delivering to HCM) with a draft
 * receipt of one line of 2000 and no carton labelled yet, on the HCM receiving area.</p>
 */
@ProductionReceiptTest
class ReceivingPackagesIntegrationTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");
    private static final String LPN = "^LPN-\\d{8}-\\d{6}$";

    @Autowired InventoryService inventory;
    @Autowired ProductionReceivables receivables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID carton;
    private UUID clerk;
    private UUID line;

    @BeforeEach
    void setUp() {
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        // Two cartons of 1000 ordered; the clerk counts them below, so the line starts with none labelled.
        ProductionReceivables.Receivable goods = receivables.sent(DemoData.WAREHOUSE_HCM, 2, 1000);
        sku = goods.sku();
        carton = goods.cartonUnitId();
        line = receivables.uncountedLine(goods, receivables.draftReceipt(goods, clerk), RECEIVING, 2);
    }

    private List<StockPackageView> count(PackageCount... packages) {
        return inventory.countReceivingPackages(new CountReceivingPackagesCommand(line, new Sku(sku),
                packages.length == 0 ? null : carton, RECEIVING, List.of(packages), clerk));
    }

    /** The units of the ledger line a move wrote: the line names a carton and no number (ADR-0011). */
    private int unitsOfLine(StockMove move) {
        return jdbc.queryForObject("""
                select p.nominal_qty from inventory.stock_movement m
                  join inventory.stock_package p on p.id = m.stock_package_id where m.id = ?""",
                Integer.class, move.movementId());
    }

    private StockMove receive(int quantity) {
        return inventory.receive(new ReceiveStockCommand(line, new Sku(sku), "LOT-FX", RECEIVING, quantity, clerk,
                RECEIVED));
    }

    /** "lpn:status:units counted into it" (its standard count once counted, null before), by LPN. */
    private List<String> cartons() {
        return jdbc.query("""
                select lpn, status, case when counted_at is not null then nominal_qty end as counted_units
                  from inventory.stock_package
                 where goods_receipt_line_id = ? order by lpn""",
                (rs, n) -> rs.getString("lpn") + ":" + rs.getString("status") + ":" + rs.getObject("counted_units"),
                line);
    }

    /** Cartons of the SKU that are stock: IN_STOCK, so with a stock status. A carton being received is not one. */
    private int cartonsInStock() {
        return jdbc.queryForObject("select count(*) from inventory.stock_package p join inventory.inventory_items i"
                + " on i.id = p.inventory_item_id where i.sku = ? and p.stock_status is not null", Integer.class, sku);
    }

    private int receiptLines() {
        return jdbc.queryForObject("""
                select count(*) from inventory.stock_movement
                 where movement_type = 'RECEIPT' and reference_type = 'GOODS_RECEIPT_LINE' and reference_id = ?""",
                Integer.class, line);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("two packages: two RECEIVING cartons with their own LPNs, each counted at the standard 1000")
    void countCreatesLabelledCartons() {
        List<StockPackageView> views = count(PackageCount.fresh(), PackageCount.fresh());

        assertThat(views).hasSize(2).allSatisfy(view -> {
            assertThat(view.lpn()).matches(LPN);
            assertThat(view.status()).isEqualTo("RECEIVING");
            assertThat(view.receiptLineId()).isEqualTo(line);
            assertThat(view.sku()).isEqualTo(sku);
            assertThat(view.uomConversionId()).isEqualTo(carton);
            assertThat(view.unitOfMeasure()).isEqualTo("CARTON");
            assertThat(view.nominalQty()).isEqualTo(1000);
            assertThat(view.locationCode()).isEqualTo(RECEIVING);
        });
        assertThat(views).extracting(StockPackageView::lpn).doesNotHaveDuplicates().isSorted();
        assertThat(views).extracting(StockPackageView::quantity).containsExactly(1000, 1000);
        assertThat(jdbc.queryForList("""
                select case when counted_at is not null then nominal_qty end
                  from inventory.stock_package where goods_receipt_line_id = ? order by lpn""",
                Integer.class, line)).as("counted in as they were labelled, at the standard").containsExactly(1000, 1000);
        assertThat(jdbc.queryForList("""
                select distinct location_code from inventory.stock_package where goods_receipt_line_id = ?""",
                String.class, line)).containsExactly(RECEIVING);
        assertThat(cartonsInStock()).as("a carton being received is not stock yet").isZero();
    }

    @Test
    @DisplayName("a recount keeps the named carton's LPN and voids the one left out")
    void recountKeepsLpnAndVoidsTheDropped() {
        List<StockPackageView> first = count(PackageCount.fresh(), PackageCount.fresh());
        String kept = first.get(0).lpn();
        String dropped = first.get(1).lpn();

        List<StockPackageView> second = count(new PackageCount(kept.toLowerCase(Locale.ROOT)));

        assertThat(second).singleElement().satisfies(view -> {
            assertThat(view.lpn()).isEqualTo(kept);
            assertThat(view.quantity()).isEqualTo(1000);
            assertThat(view.status()).isEqualTo("RECEIVING");
        });
        assertThat(cartons()).containsExactly(kept + ":RECEIVING:1000", dropped + ":VOID:1000");
        assertThat(jdbc.queryForObject("select closed_at is not null from inventory.stock_package where lpn = ?",
                Boolean.class, dropped)).isTrue();
        assertThat(inventory.receivingPackagesOf(List.of(line))).as("a voided carton is not shown")
                .containsOnlyKeys(line)
                .satisfies(byLine -> assertThat(byLine.get(line)).extracting(StockPackageView::lpn)
                        .containsExactly(kept));
    }

    @Test
    @DisplayName("an empty list voids every carton of the line")
    void emptyListVoidsAll() {
        count(PackageCount.fresh(), PackageCount.fresh());

        assertThat(count()).isEmpty();

        assertThat(cartons()).hasSize(2).allSatisfy(row -> assertThat(row).contains(":VOID:"));
        assertThat(inventory.receivingPackagesOf(List.of(line))).isEmpty();
    }

    @Test
    @DisplayName("receive puts every unit into the cartons, IN_STOCK and sealed, one RECEIPT line each, none loose")
    void receiveIntoCartons() {
        count(PackageCount.fresh(), PackageCount.fresh());
        int atp = inventory.availableToPromise(new Sku(sku));

        StockMove move = receive(2000);

        assertThat(unitsOfLine(move)).as("the first carton's line is the move returned: one carton of 1,000")
                .isEqualTo(1000);
        assertThat(jdbc.queryForList("""
                select status from inventory.stock_package where goods_receipt_line_id = ?""",
                String.class, line)).containsExactly("IN_STOCK", "IN_STOCK");
        assertThat(jdbc.query("""
                select p.nominal_qty as on_hand, p.stock_status as status, p.location_code,
                       case when p.counted_at is not null then p.nominal_qty end as counted_units
                  from inventory.stock_package p
                  join inventory.inventory_items i on i.id = p.inventory_item_id
                 where i.sku = ? and p.status = 'IN_STOCK' order by p.lpn""",
                (rs, n) -> rs.getInt("on_hand") + ":" + rs.getString("status") + ":" + rs.getString("location_code")
                        + ":" + (rs.getObject("counted_units") == null ? "loose" : "carton of " + rs.getInt("counted_units")),
                sku)).containsExactly(
                "1000:INBOUND:" + RECEIVING + ":carton of 1000",
                "1000:INBOUND:" + RECEIVING + ":carton of 1000");
        assertThat(jdbc.query("""
                select p.nominal_qty as quantity, p.status
                  from inventory.stock_movement m join inventory.stock_package p on p.id = m.stock_package_id
                 where m.movement_type = 'RECEIPT' and m.reference_type = 'GOODS_RECEIPT_LINE' and m.reference_id = ?
                 order by m.id""",
                (rs, n) -> rs.getInt("quantity") + ":carton " + rs.getString("status"),
                line)).as("one line per carton, each the carton whole")
                .containsExactly("1000:carton IN_STOCK", "1000:carton IN_STOCK");
        Map<UUID, List<StockPackageView>> shown = inventory.receivingPackagesOf(List.of(line));
        assertThat(shown.get(line)).extracting(StockPackageView::status, StockPackageView::quantity)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("IN_STOCK", 1000),
                        org.assertj.core.groups.Tuple.tuple("IN_STOCK", 1000));
        assertThat(inventory.availableToPromise(new Sku(sku))).as("INBOUND is not sellable (BR-04)").isEqualTo(atp);
    }

    @Test
    @DisplayName("cartons holding more than the line received are refused, and nothing is written")
    void receiveRefusesCartonsAboveTheLine() {
        count(PackageCount.fresh(), PackageCount.fresh());

        assertThatThrownBy(() -> receive(1500))
                .extracting(ReceivingPackagesIntegrationTest::code).isEqualTo(ErrorCode.RECEIPT_PACKAGES_EXCEED_LINE);

        assertThat(cartons()).hasSize(2).allSatisfy(row -> assertThat(row).contains(":RECEIVING:"));
        assertThat(cartonsInStock()).isZero();
        assertThat(receiptLines()).isZero();
    }

    @Test
    @DisplayName("units outside a carton are not received (03 BR-12): RECEIPT_LINE_NOT_IN_CARTONS, nothing is written")
    void receiveRefusesUnitsOutsideTheCartons() {
        count(PackageCount.fresh());

        assertThatThrownBy(() -> receive(2000))
                .extracting(ReceivingPackagesIntegrationTest::code).isEqualTo(ErrorCode.RECEIPT_LINE_NOT_IN_CARTONS);
        assertThatThrownBy(() -> receive(2000)).as("nor a line with no carton at all")
                .extracting(ReceivingPackagesIntegrationTest::code).isEqualTo(ErrorCode.RECEIPT_LINE_NOT_IN_CARTONS);

        assertThat(cartons()).singleElement().satisfies(row -> assertThat(row).contains(":RECEIVING:"));
        assertThat(cartonsInStock()).isZero();
        assertThat(receiptLines()).isZero();
    }

    @Test
    @DisplayName("a carton cannot go past receiving uncounted, not even with the row triggers off: "
            + "ck_stock_package_counted (V20261231000300); counted again it is a counted draft carton as before")
    void anUncountedCartonCannotLeaveReceiving() {
        String lpn = count(PackageCount.fresh(), PackageCount.fresh()).getFirst().lpn();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("update inventory.stock_package set counted_at = null, status = 'IN_STOCK' where lpn = ?",
                    lpn);
        })).hasMessageContaining("ck_stock_package_counted");
        assertThat(cartonsInStock()).isZero();

        count(new PackageCount(lpn), PackageCount.fresh());
        assertThat(cartons()).filteredOn(row -> row.startsWith(lpn)).containsExactly(lpn + ":RECEIVING:1000");
    }

    @Test
    @DisplayName("a retried receipt returns the first move and writes nothing twice")
    void receiveReplayWritesNothingTwice() {
        count(PackageCount.fresh(), PackageCount.fresh());
        StockMove first = receive(2000);
        int rows = cartonsInStock();
        int ledger = receiptLines();

        StockMove again = receive(2000);

        assertThat(again.movementId()).isEqualTo(first.movementId());
        assertThat(unitsOfLine(again)).isEqualTo(1000);
        assertThat(cartonsInStock()).isEqualTo(rows).isEqualTo(2);
        assertThat(receiptLines()).isEqualTo(ledger).isEqualTo(2);
        assertThat(cartons()).hasSize(2).allSatisfy(row -> assertThat(row).contains(":IN_STOCK:"));
    }
}
