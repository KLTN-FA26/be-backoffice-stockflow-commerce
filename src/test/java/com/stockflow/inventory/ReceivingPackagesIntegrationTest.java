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
 * carton holds stock, an unopened one exactly what was counted into it), so every call commits as production's
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

    private StockMove receive(int quantity) {
        return inventory.receive(new ReceiveStockCommand(line, new Sku(sku), "LOT-FX", RECEIVING, quantity, clerk,
                RECEIVED));
    }

    /** "lpn:status:received_qty", by LPN. */
    private List<String> cartons() {
        return jdbc.query("""
                select lpn, status, received_qty from inventory.stock_package
                 where goods_receipt_line_id = ? order by lpn""",
                (rs, n) -> rs.getString("lpn") + ":" + rs.getString("status") + ":" + rs.getObject("received_qty"),
                line);
    }

    private int stockRows() {
        return jdbc.queryForObject("select count(*) from inventory.stock_item where sku = ?", Integer.class, sku);
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
            assertThat(view.unopened()).isTrue();
        });
        assertThat(views).extracting(StockPackageView::lpn).doesNotHaveDuplicates().isSorted();
        assertThat(views).extracting(StockPackageView::quantity).containsExactly(1000, 1000);
        assertThat(jdbc.queryForList("""
                select received_qty from inventory.stock_package where goods_receipt_line_id = ? order by lpn""",
                Integer.class, line)).containsExactly(1000, 1000);
        assertThat(jdbc.queryForList("""
                select distinct location_code from inventory.stock_package where goods_receipt_line_id = ?""",
                String.class, line)).containsExactly(RECEIVING);
        assertThat(stockRows()).as("a carton being received holds no stock row").isZero();
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

        assertThat(move.quantity()).as("the first carton's line is the move returned").isEqualTo(1000);
        assertThat(jdbc.queryForList("""
                select status || ':' || unopened from inventory.stock_package where goods_receipt_line_id = ?""",
                String.class, line)).containsExactly("IN_STOCK:true", "IN_STOCK:true");
        assertThat(jdbc.query("""
                select s.on_hand, s.status, s.location_code, p.received_qty from inventory.stock_item s
                  left join inventory.stock_package p on p.id = s.stock_package_id
                 where s.sku = ? order by s.on_hand""",
                (rs, n) -> rs.getInt("on_hand") + ":" + rs.getString("status") + ":" + rs.getString("location_code")
                        + ":" + (rs.getObject("received_qty") == null ? "loose" : "carton of " + rs.getInt("received_qty")),
                sku)).containsExactly(
                "1000:INBOUND:" + RECEIVING + ":carton of 1000",
                "1000:INBOUND:" + RECEIVING + ":carton of 1000");
        assertThat(jdbc.query("""
                select quantity, to_stock_package_id from inventory.stock_movement
                 where movement_type = 'RECEIPT' and reference_type = 'GOODS_RECEIPT_LINE' and reference_id = ?
                 order by quantity""",
                (rs, n) -> rs.getInt("quantity") + ":" + (rs.getObject("to_stock_package_id") == null ? "loose" : "carton"),
                line)).containsExactly("1000:carton", "1000:carton");
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
        assertThat(stockRows()).isZero();
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
        assertThat(stockRows()).isZero();
        assertThat(receiptLines()).isZero();
    }

    @Test
    @DisplayName("a draft carton counted at another figure before 4C-2 is refused at receipt until counted again")
    void aNonStandardDraftCartonIsRecountedFirst() {
        String lpn = count(PackageCount.fresh(), PackageCount.fresh()).getFirst().lpn();
        // As a draft counted under the old rules stands: the row triggers are off only for this transaction's write.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("update inventory.stock_package set received_qty = 980 where lpn = ?", lpn);
        });

        assertThatThrownBy(() -> receive(2000))
                .extracting(ReceivingPackagesIntegrationTest::code).isEqualTo(ErrorCode.RECEIPT_PACKAGE_NOT_STANDARD);
        assertThat(stockRows()).isZero();

        count(new PackageCount(lpn), PackageCount.fresh());
        assertThat(cartons()).filteredOn(row -> row.startsWith(lpn)).containsExactly(lpn + ":RECEIVING:1000");
    }

    @Test
    @DisplayName("a retried receipt returns the first move and writes nothing twice")
    void receiveReplayWritesNothingTwice() {
        count(PackageCount.fresh(), PackageCount.fresh());
        StockMove first = receive(2000);
        int rows = stockRows();
        int ledger = receiptLines();

        StockMove again = receive(2000);

        assertThat(again.movementId()).isEqualTo(first.movementId());
        assertThat(again.quantity()).isEqualTo(1000);
        assertThat(stockRows()).isEqualTo(rows).isEqualTo(2);
        assertThat(receiptLines()).isEqualTo(ledger).isEqualTo(2);
        assertThat(cartons()).hasSize(2).allSatisfy(row -> assertThat(row).contains(":IN_STOCK:"));
    }
}
