package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.CountReceivingPackagesCommand.PackageCount;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
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
 * would. Each test has its own SKU, with a CARTON unit of 1000, and its own CONFIRMED purchase order and draft
 * receipt of one line of 2000, on the demo supplier and the HCM receiving area.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ReceivingPackagesIntegrationTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");
    private static final String LPN = "^LPN-\\d{8}-\\d{6}$";

    @Autowired InventoryService inventory;
    @Autowired GoodsReceipts receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID carton;
    private UUID clerk;
    private UUID line;

    @BeforeEach
    void setUp() {
        sku = "RPK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        UUID poLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Receiving carton fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Receiving carton fixture");
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            // BR-03: tg_goods_receipt_lines_item refuses a receipt line for an item with no weight or dimensions.
            jdbc.update("update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8, height_cm = 6"
                    + " where id = ?", item);
            carton = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 1000)", carton, item);

            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);   // four eyes (ck_purchase_orders_four_eyes)
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, "PO-RP-" + po.toString().substring(24), submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 2000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(po, "DN-RP", null), clerk);
        line = receipts.replaceLines(draft.id(), List.of(new GoodsReceipts.NewLine(poLine, 2000, null, null,
                RECEIVING, null))).lines().getFirst().id();
    }

    private List<StockPackageView> count(PackageCount... packages) {
        return inventory.countReceivingPackages(new CountReceivingPackagesCommand(line, new Sku(sku),
                packages.length == 0 ? null : carton, RECEIVING, List.of(packages), clerk));
    }

    private StockMove receive(int quantity) {
        return inventory.receive(new ReceiveStockCommand(line, new Sku(sku), null, null, RECEIVING, quantity, clerk,
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
    @DisplayName("two packages: two RECEIVING cartons with their own LPNs, counted 1000 and 980 at the line's area")
    void countCreatesLabelledCartons() {
        List<StockPackageView> views = count(new PackageCount(null, 1000), new PackageCount(null, 980));

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
        assertThat(views).extracting(StockPackageView::quantity).containsExactly(1000, 980);
        assertThat(jdbc.queryForList("""
                select received_qty from inventory.stock_package where goods_receipt_line_id = ? order by lpn""",
                Integer.class, line)).containsExactly(1000, 980);
        assertThat(jdbc.queryForList("""
                select distinct location_code from inventory.stock_package where goods_receipt_line_id = ?""",
                String.class, line)).containsExactly(RECEIVING);
        assertThat(stockRows()).as("a carton being received holds no stock row").isZero();
    }

    @Test
    @DisplayName("a recount keeps the named carton's LPN and voids the one left out")
    void recountKeepsLpnAndVoidsTheDropped() {
        List<StockPackageView> first = count(new PackageCount(null, 1000), new PackageCount(null, 980));
        String kept = first.get(0).lpn();
        String dropped = first.get(1).lpn();

        List<StockPackageView> second = count(new PackageCount(kept, 990));

        assertThat(second).singleElement().satisfies(view -> {
            assertThat(view.lpn()).isEqualTo(kept);
            assertThat(view.quantity()).isEqualTo(990);
            assertThat(view.status()).isEqualTo("RECEIVING");
        });
        assertThat(cartons()).containsExactly(kept + ":RECEIVING:990", dropped + ":VOID:980");
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
        count(new PackageCount(null, 1000), new PackageCount(null, 980));

        assertThat(count()).isEmpty();

        assertThat(cartons()).hasSize(2).allSatisfy(row -> assertThat(row).contains(":VOID:"));
        assertThat(inventory.receivingPackagesOf(List.of(line))).isEmpty();
    }

    @Test
    @DisplayName("receive puts the goods into the cartons, IN_STOCK and unopened, and only the remainder loose")
    void receiveIntoCartons() {
        count(new PackageCount(null, 1000), new PackageCount(null, 980));
        int atp = inventory.availableToPromise(new Sku(sku));

        StockMove move = receive(2000);

        assertThat(move.quantity()).as("the loose line is the move returned").isEqualTo(20);
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
                "20:INBOUND:" + RECEIVING + ":loose",
                "980:INBOUND:" + RECEIVING + ":carton of 980",
                "1000:INBOUND:" + RECEIVING + ":carton of 1000");
        assertThat(jdbc.query("""
                select quantity, to_stock_package_id from inventory.stock_movement
                 where movement_type = 'RECEIPT' and reference_type = 'GOODS_RECEIPT_LINE' and reference_id = ?
                 order by quantity""",
                (rs, n) -> rs.getInt("quantity") + ":" + (rs.getObject("to_stock_package_id") == null ? "loose" : "carton"),
                line)).containsExactly("20:loose", "980:carton", "1000:carton");
        Map<UUID, List<StockPackageView>> shown = inventory.receivingPackagesOf(List.of(line));
        assertThat(shown.get(line)).extracting(StockPackageView::status, StockPackageView::quantity)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("IN_STOCK", 1000),
                        org.assertj.core.groups.Tuple.tuple("IN_STOCK", 980));
        assertThat(inventory.availableToPromise(new Sku(sku))).as("INBOUND is not sellable (BR-04)").isEqualTo(atp);
    }

    @Test
    @DisplayName("cartons holding more than the line received are refused, and nothing is written")
    void receiveRefusesCartonsAboveTheLine() {
        count(new PackageCount(null, 1000), new PackageCount(null, 980));

        assertThatThrownBy(() -> receive(1500))
                .extracting(ReceivingPackagesIntegrationTest::code).isEqualTo(ErrorCode.RECEIPT_PACKAGES_EXCEED_LINE);

        assertThat(cartons()).hasSize(2).allSatisfy(row -> assertThat(row).contains(":RECEIVING:"));
        assertThat(stockRows()).isZero();
        assertThat(receiptLines()).isZero();
    }

    @Test
    @DisplayName("a retried receipt returns the first move and writes nothing twice")
    void receiveReplayWritesNothingTwice() {
        count(new PackageCount(null, 1000), new PackageCount(null, 980));
        StockMove first = receive(2000);
        int rows = stockRows();
        int ledger = receiptLines();

        StockMove again = receive(2000);

        assertThat(again.movementId()).isEqualTo(first.movementId());
        assertThat(again.quantity()).isEqualTo(20);
        assertThat(stockRows()).isEqualTo(rows).isEqualTo(3);
        assertThat(receiptLines()).isEqualTo(ledger).isEqualTo(3);
        assertThat(cartons()).hasSize(2).allSatisfy(row -> assertThat(row).contains(":IN_STOCK:"));
    }
}
