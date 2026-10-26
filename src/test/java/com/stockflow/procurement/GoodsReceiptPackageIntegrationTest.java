package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.procurement.internal.service.GoodsReceipts.NewPackage;
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

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-473 design §3 against a real PostgreSQL: a draft receipt's count labels, recounts, moves and voids the
 * line's cartons through inventory, answers with their LPNs, and confirming puts the goods into them.
 *
 * <p>Nothing here runs in a test transaction: V20261020000200 checks a carton against its stock rows at commit, so
 * every call commits as production's would. Each test has its own SKU (2 steps: no QC, no lot) with a CARTON unit of
 * 1000 and a BOX unit of 500, its own warehouse with two receiving areas, and its own CONFIRMED purchase order of
 * 5000 on the demo supplier, with a draft receipt. Its own warehouse, so a second receiving area leaves the demo map
 * as other tests read it.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class GoodsReceiptPackageIntegrationTest {

    private static final String LPN = "^LPN-\\d{8}-\\d{6}$";

    @Autowired GoodsReceipts receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID carton;
    private UUID box;
    private String baseUnit;
    private String area;
    private String otherArea;
    private UUID clerk;
    private UUID poLine;
    private UUID receiptId;

    @BeforeEach
    void setUp() {
        sku = "GRP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String prefix = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        area = prefix + "-RCV01";
        otherArea = prefix + "-RCV02";
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID warehouse = Identifiers.newId();
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        poLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Goods receipt carton fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Goods receipt carton fixture");
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            // BR-03: tg_goods_receipt_lines_item refuses a receipt line for an item with no weight or dimensions.
            jdbc.update("update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8, height_cm = 6"
                    + " where id = ?", item);
            carton = Identifiers.newId();
            box = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 1000)", carton, item);
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'BOX', 500)", box, item);
            baseUnit = jdbc.queryForObject("""
                    select unit_of_measure from inventory.uom_conversions where inventory_item_id = ? and is_base""",
                    String.class, item);

            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                    + " map_height, version, created_at) VALUES (?, 'Carton receiving', 'ACTIVE', ?, 'Test address', 'M',"
                    + " 50, 50, 0, NOW())", warehouse, prefix);
            receivingArea(warehouse, "RCV01", area, 1);
            receivingArea(warehouse, "RCV02", otherArea, 10);

            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);   // four eyes (ck_purchase_orders_four_eyes)
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, ?, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s WHERE s.code = 'GOVIET'""",
                    po, "PO-GP-" + po.toString().substring(24), warehouse, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 5000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        receiptId = receipts.create(new GoodsReceipts.Create(po, "DN-GP", null), clerk).id();
    }

    private void receivingArea(UUID warehouse, String code, String locationCode, int x) {
        UUID location = Identifiers.newId();
        jdbc.update("INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,"
                + " is_pickable, is_putaway_target, status) VALUES (?, ?, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')",
                location, warehouse, locationCode);
        jdbc.update("INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,"
                + " rotation, is_obstacle, status) VALUES (?, ?, ?, ?, 'RECEIVING', ?, ?, 1, 5, 5, 0, FALSE, 'ACTIVE')",
                Identifiers.newId(), warehouse, location, code, code, x);
    }

    private GoodsReceipts.NewLine line(int quantity, String unit, String at, NewPackage... packages) {
        return lotLine(null, quantity, unit, at, packages);
    }

    private GoodsReceipts.NewLine lotLine(String lot, int quantity, String unit, String at, NewPackage... packages) {
        return new GoodsReceipts.NewLine(poLine, quantity, lot, null, at, null, unit, List.of(packages));
    }

    private GoodsReceipts.LineView count(GoodsReceipts.NewLine line) {
        return receipts.replaceLines(receiptId, List.of(line)).lines().getFirst();
    }

    /** 4990 units: four cartons of 1000, one of 980, 10 loose. */
    private GoodsReceipts.LineView fiveCartons() {
        return count(line(4990, "CARTON", area, new NewPackage(null, 1000), new NewPackage(null, 1000),
                new NewPackage(null, 1000), new NewPackage(null, 1000), new NewPackage(null, 980)));
    }

    private static NewPackage recount(GoodsReceipts.PackageView carton, int quantity) {
        return new NewPackage(carton.lpn(), quantity);
    }

    /** "status" of every carton ever labelled on the line, VOID ones included, by LPN. */
    private List<String> statuses(UUID lineId) {
        return jdbc.queryForList("""
                select status from inventory.stock_package where goods_receipt_line_id = ? order by lpn""",
                String.class, lineId);
    }

    private static List<String> fields(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).fieldErrors().stream().map(e -> e.field() + ":" + e.code()).toList();
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("five cartons on a line of 4990: five LPNs in the answer, the line in CARTON, 10 loose")
    void draftCartonsGetLpns() {
        GoodsReceipts.LineView line = fiveCartons();

        assertThat(line.unitOfMeasure()).isEqualTo("CARTON");
        assertThat(line.looseQuantity()).isEqualTo(10);
        assertThat(line.units()).as("every unit of the item for the picker, the base unit first, then by code")
                .containsExactly(new GoodsReceipts.UnitView(baseUnit, 1, true, true, true),
                        new GoodsReceipts.UnitView("BOX", 500, false, true, false),
                        new GoodsReceipts.UnitView("CARTON", 1000, false, true, false));
        assertThat(line.packages()).hasSize(5).allSatisfy(p -> {
            assertThat(p.lpn()).matches(LPN);
            assertThat(p.status()).isEqualTo("RECEIVING");
            assertThat(p.nominalQty()).isEqualTo(1000);
        });
        assertThat(line.packages()).extracting(GoodsReceipts.PackageView::lpn).doesNotHaveDuplicates().isSorted();
        assertThat(line.packages()).extracting(GoodsReceipts.PackageView::quantity)
                .containsExactlyInAnyOrder(1000, 1000, 1000, 1000, 980);
        assertThat(line.packages()).as("while RECEIVING, what is in a carton is what was counted into it")
                .allSatisfy(p -> assertThat(p.receivedQty()).isEqualTo(p.quantity()));
        assertThat(jdbc.queryForObject("select uom_conversion_id from procurement.goods_receipt_lines where id = ?",
                UUID.class, line.id())).isEqualTo(carton);
        assertThat(receipts.get(receiptId).lines().getFirst().packages()).as("the receipt read again shows them too")
                .extracting(GoodsReceipts.PackageView::lpn)
                .containsExactlyElementsOf(line.packages().stream().map(GoodsReceipts.PackageView::lpn).toList());
    }

    @Test
    @DisplayName("a recount naming two of the five keeps their LPNs, recounts the first to 990 and voids the other three")
    void recountKeepsAndVoids() {
        List<GoodsReceipts.PackageView> first = fiveCartons().packages();

        GoodsReceipts.LineView again = count(line(4990, "CARTON", area, recount(first.get(0), 990),
                recount(first.get(1), 1000)));

        assertThat(again.id()).as("the line keeps its id").isEqualTo(fiveCartonsLineId());
        assertThat(again.packages()).extracting(GoodsReceipts.PackageView::lpn, GoodsReceipts.PackageView::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(first.get(0).lpn(), 990),
                        org.assertj.core.groups.Tuple.tuple(first.get(1).lpn(), 1000));
        assertThat(again.looseQuantity()).isEqualTo(4990 - 1990);
        assertThat(statuses(again.id())).filteredOn("VOID"::equals).hasSize(3);
        assertThat(statuses(again.id())).filteredOn("RECEIVING"::equals).hasSize(2);
    }

    private UUID fiveCartonsLineId() {
        return jdbc.queryForObject("select id from procurement.goods_receipt_lines where receipt_id = ?", UUID.class,
                receiptId);
    }

    @Test
    @DisplayName("rule 9: a line counted again at another receiving area takes its cartons there")
    void recountMovesCartonsWithTheLine() {
        List<GoodsReceipts.PackageView> first = fiveCartons().packages();

        GoodsReceipts.LineView moved = count(line(4990, "CARTON", otherArea,
                first.stream().map(p -> recount(p, p.quantity())).toArray(NewPackage[]::new)));

        assertThat(moved.locationCode()).isEqualTo(otherArea);
        assertThat(moved.packages()).extracting(GoodsReceipts.PackageView::lpn)
                .containsExactlyElementsOf(first.stream().map(GoodsReceipts.PackageView::lpn).toList());
        assertThat(jdbc.queryForList("""
                select distinct location_code from inventory.stock_package where goods_receipt_line_id = ?""",
                String.class, moved.id())).containsExactly(otherArea);
    }

    @Test
    @DisplayName("rule 7: a new unit while an old carton is kept is refused; with every old carton voided it is taken")
    void unitChangeWithCartonsKept() {
        List<GoodsReceipts.PackageView> first = fiveCartons().packages();

        Throwable kept = catchThrowable(() -> count(line(4990, "BOX", area, recount(first.get(0), 500))));

        assertThat(code(kept)).isEqualTo(ErrorCode.RECEIPT_LINE_HAS_PACKAGES);
        assertThat(fields(kept)).containsExactly("lines[0].unitOfMeasure:RECEIPT_LINE_HAS_PACKAGES");
        assertThat(statuses(fiveCartonsLineId())).as("nothing changed").containsOnly("RECEIVING").hasSize(5);

        GoodsReceipts.LineView changed = count(line(4990, "BOX", area, new NewPackage(null, 500),
                new NewPackage(null, 490)));

        assertThat(changed.unitOfMeasure()).isEqualTo("BOX");
        assertThat(changed.packages()).extracting(GoodsReceipts.PackageView::nominalQty).containsOnly(500);
        assertThat(changed.looseQuantity()).isEqualTo(4000);
        assertThat(statuses(changed.id())).filteredOn("VOID"::equals).hasSize(5);
        assertThat(jdbc.queryForObject("select uom_conversion_id from procurement.goods_receipt_lines where id = ?",
                UUID.class, changed.id())).isEqualTo(box);
    }

    @Test
    @DisplayName("an LPN and a unit typed in lower case, with spaces, name the stored carton and unit: recounted, not voided")
    void lowerCaseLpnRecounts() {
        List<GoodsReceipts.PackageView> first = fiveCartons().packages();

        GoodsReceipts.LineView again = count(line(4990, "carton", area, first.stream()
                .map(p -> new NewPackage(" " + p.lpn().toLowerCase(Locale.ROOT) + " ", p.quantity()))
                .toArray(NewPackage[]::new)));

        assertThat(again.unitOfMeasure()).isEqualTo("CARTON");
        assertThat(again.packages()).extracting(GoodsReceipts.PackageView::lpn)
                .containsExactlyElementsOf(first.stream().map(GoodsReceipts.PackageView::lpn).toList());
        assertThat(statuses(again.id())).as("the same five, none voided").hasSize(5).containsOnly("RECEIVING");
    }

    @Test
    @DisplayName("a line that has had cartons keeps its lot: counted under another lot it is refused, its cartons live"
            + " or all voided, and nothing changes")
    void lotChangeOnACartonLineIsRefused() {
        tx.executeWithoutResult(s -> jdbc.update(
                "update inventory.inventory_items set lot_tracked = true where sku = ?", sku));
        UUID lineId = count(lotLine("L1", 4990, "CARTON", area, new NewPackage(null, 1000),
                new NewPackage(null, 980))).id();

        Throwable live = catchThrowable(() -> count(lotLine("L2", 4990, "CARTON", area, new NewPackage(null, 1000),
                new NewPackage(null, 980))));

        assertThat(code(live)).isEqualTo(ErrorCode.RECEIPT_LINE_HAS_PACKAGES);
        assertThat(statuses(lineId)).as("the cartons are untouched").hasSize(2).containsOnly("RECEIVING");
        assertThat(onlyLine()).as("no new line, the old one as it was").isEqualTo(lineId + ":L1");

        count(lotLine("L1", 4990, null, area));   // the line counted loose: both its cartons are voided
        assertThat(statuses(lineId)).hasSize(2).containsOnly("VOID");

        Throwable voided = catchThrowable(() -> count(lotLine("L2", 4990, null, area)));

        assertThat(code(voided)).as("a VOID carton still points at the line")
                .isEqualTo(ErrorCode.RECEIPT_LINE_HAS_PACKAGES);
        assertThat(statuses(lineId)).hasSize(2).containsOnly("VOID");
        assertThat(onlyLine()).isEqualTo(lineId + ":L1");
    }

    /** "id:lot" of the receipt's one line; queryForObject fails if there is another. */
    private String onlyLine() {
        return jdbc.queryForObject(
                "select id || ':' || lot_number from procurement.goods_receipt_lines where receipt_id = ?",
                String.class, receiptId);
    }

    @Test
    @DisplayName("rule 2: a packing unit without cartons is refused; the base unit without cartons is a loose line")
    void packingUnitWithoutCartons() {
        Throwable refused = catchThrowable(() -> count(line(100, "CARTON", area)));

        assertThat(code(refused)).isEqualTo(ErrorCode.RECEIPT_LINE_UNIT_INVALID);
        assertThat(fields(refused)).containsExactly("lines[0].unitOfMeasure:RECEIPT_LINE_UNIT_INVALID");

        GoodsReceipts.LineView loose = count(line(100, baseUnit, area));
        assertThat(loose.unitOfMeasure()).isNull();
        assertThat(loose.looseQuantity()).isEqualTo(100);
        assertThat(loose.packages()).isEmpty();
    }

    @Test
    @DisplayName("rule 3: cartons in the base unit are refused, as is a unit the item does not have")
    void baseUnitWithCartons() {
        Throwable base = catchThrowable(() -> count(line(100, baseUnit, area, new NewPackage(null, 100))));
        assertThat(code(base)).isEqualTo(ErrorCode.RECEIPT_LINE_UNIT_INVALID);
        assertThat(fields(base)).containsExactly("lines[0].unitOfMeasure:RECEIPT_LINE_UNIT_INVALID");

        Throwable unknown = catchThrowable(() -> count(line(100, "PALLET", area, new NewPackage(null, 100))));
        assertThat(code(unknown)).isEqualTo(ErrorCode.UOM_CONVERSION_NOT_FOUND);
        assertThat(fields(unknown)).containsExactly("lines[0].unitOfMeasure:UOM_CONVERSION_NOT_FOUND");
    }

    @Test
    @DisplayName("rule 5: cartons holding more than the line are refused on lines[0].packages, and nothing is saved")
    void cartonsAboveLine() {
        Throwable refused = catchThrowable(() -> count(line(1500, "CARTON", area, new NewPackage(null, 1000),
                new NewPackage(null, 980))));

        assertThat(code(refused)).isEqualTo(ErrorCode.RECEIPT_PACKAGES_EXCEED_LINE);
        assertThat(fields(refused)).containsExactly("lines[0].packages:RECEIPT_PACKAGES_EXCEED_LINE");
        assertThat(receipts.get(receiptId).lines()).isEmpty();
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_package p join procurement.goods_receipt_lines l
                  on l.id = p.goods_receipt_line_id where l.receipt_id = ?""", Integer.class, receiptId)).isZero();
    }

    @Test
    @DisplayName("cancelling the draft voids the cartons labelled on it")
    void cancelVoidsCartons() {
        UUID lineId = fiveCartons().id();

        GoodsReceipts.ReceiptView cancelled = receipts.cancel(receiptId);

        assertThat(statuses(lineId)).hasSize(5).containsOnly("VOID");
        assertThat(cancelled.lines().getFirst().packages()).as("a voided carton is not shown").isEmpty();
        assertThat(cancelled.lines().getFirst().unitOfMeasure()).as("the line still says what it was counted in")
                .isEqualTo("CARTON");
    }

    @Test
    @DisplayName("confirm: cartons IN_STOCK and unopened, one INBOUND row each, the remainder one loose row")
    void confirmIntoCartons() {
        UUID lineId = fiveCartons().id();

        GoodsReceipts.ReceiptView confirmed = receipts.confirm(receiptId, clerk);

        assertThat(jdbc.queryForList("""
                select status || ':' || unopened from inventory.stock_package where goods_receipt_line_id = ?""",
                String.class, lineId)).hasSize(5).containsOnly("IN_STOCK:true");
        assertThat(jdbc.query("""
                select s.on_hand, s.status, p.received_qty from inventory.stock_item s
                  left join inventory.stock_package p on p.id = s.stock_package_id
                 where s.sku = ? order by s.on_hand""",
                (rs, n) -> rs.getInt("on_hand") + ":" + rs.getString("status") + ":"
                        + (rs.getObject("received_qty") == null ? "loose" : "carton of " + rs.getInt("received_qty")),
                sku)).containsExactly("10:INBOUND:loose", "980:INBOUND:carton of 980",
                "1000:INBOUND:carton of 1000", "1000:INBOUND:carton of 1000", "1000:INBOUND:carton of 1000",
                "1000:INBOUND:carton of 1000");
        assertThat(jdbc.queryForObject("select uom_conversion_id from procurement.goods_receipt_lines where id = ?",
                UUID.class, lineId)).isEqualTo(carton);
        GoodsReceipts.LineView line = confirmed.lines().getFirst();
        assertThat(line.packages()).hasSize(5).extracting(GoodsReceipts.PackageView::status).containsOnly("IN_STOCK");
        assertThat(line.packages()).extracting(GoodsReceipts.PackageView::receivedQty)
                .containsExactlyInAnyOrder(1000, 1000, 1000, 1000, 980);
        assertThat(line.looseQuantity()).as("the line less what was counted into cartons").isEqualTo(10);
        assertThat(receipts.get(receiptId).lines().getFirst().looseQuantity()).as("read again").isEqualTo(10);
    }

    @Test
    @DisplayName("confirm a line wholly in cartons: no loose row is written")
    void confirmAllInCartons() {
        count(line(2000, "CARTON", area, new NewPackage(null, 1000), new NewPackage(null, 1000)));

        receipts.confirm(receiptId, clerk);

        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_item where sku = ?", Integer.class, sku))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_item where sku = ? and stock_package_id is null""",
                Integer.class, sku)).as("no loose row").isZero();
    }

    @Test
    @DisplayName("a carton holding more than its unit's 1000 is kept as counted: IN_STOCK, unopened, 1010 on hand")
    void excessCartonIsKept() {
        UUID lineId = count(line(1010, "CARTON", area, new NewPackage(null, 1010))).id();

        receipts.confirm(receiptId, clerk);

        assertThat(jdbc.queryForObject("""
                select status || ':' || unopened || ':' || nominal_qty from inventory.stock_package
                 where goods_receipt_line_id = ?""", String.class, lineId)).isEqualTo("IN_STOCK:true:1000");
        assertThat(jdbc.queryForList("select on_hand from inventory.stock_item where sku = ?", Integer.class, sku))
                .containsExactly(1010);
    }
}
