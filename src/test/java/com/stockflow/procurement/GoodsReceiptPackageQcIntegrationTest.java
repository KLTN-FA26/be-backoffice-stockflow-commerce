package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackageCommand;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.QcOutcome;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.procurement.internal.service.GoodsReceipts.Decision;
import com.stockflow.procurement.internal.service.GoodsReceipts.NewPackage;
import com.stockflow.procurement.internal.service.GoodsReceipts.Part;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * SCRUM-473 design §5 against a real PostgreSQL: QC on a goods-receipt line whose goods are in cartons. Moving the
 * line to QC takes its cartons whole and its loose remainder; the decision quarantines or rejects whole cartons by
 * LPN, loose units by quantity, and BR-08 still adds up.
 *
 * <p>Nothing runs in a test transaction: V20261020000200 checks a carton against its stock rows at commit, so every
 * call commits as production's would. The fixture is {@link GoodsReceiptPackageIntegrationTest}'s, made 3-step: a SKU
 * of its own with {@code qc_required} and a CARTON unit of 100, a warehouse of its own with a RECEIVING, a
 * QUALITY_CONTROL and two QUARANTINE areas (as HCM has HCM-RCV01, HCM-QCA01, HCM-QC01 and HCM-RTV01), and a CONFIRMED
 * order of 1000. Each test starts from a confirmed receipt of 205 units - two cartons of 100 and 5 loose - waiting to
 * be moved to QC.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class GoodsReceiptPackageQcIntegrationTest {

    @Autowired GoodsReceipts receipts;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String receiving;
    private String qcArea;
    private String quarantine;
    private String returnToVendor;
    private UUID clerk;
    private UUID qc;
    private UUID po;
    private UUID poLine;
    private UUID receiptId;
    private UUID lineId;
    private String lpn1;
    private String lpn2;

    @BeforeEach
    void setUp() {
        sku = "GRQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String prefix = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        receiving = prefix + "-RCV01";
        qcArea = prefix + "-QCA01";
        quarantine = prefix + "-QC01";
        returnToVendor = prefix + "-RTV01";
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        qc = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID warehouse = Identifiers.newId();
        po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        poLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Goods receipt carton QC fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Goods receipt carton QC fixture");
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            jdbc.update("update inventory.inventory_items set qc_required = true where id = ?", item);
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 100)", Identifiers.newId(), item);

            jdbc.update("INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width,"
                    + " map_height, version, created_at) VALUES (?, 'Carton QC', 'ACTIVE', ?, 'Test address', 'M',"
                    + " 50, 50, 0, NOW())", warehouse, prefix);
            area(warehouse, "RCV01", receiving, "RECEIVING", 1);
            area(warehouse, "QCA01", qcArea, "QUALITY_CONTROL", 10);
            area(warehouse, "QC01", quarantine, "QUARANTINE", 20);
            area(warehouse, "RTV01", returnToVendor, "QUARANTINE", 30);

            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);   // four eyes (ck_purchase_orders_four_eyes)
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, ?, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s WHERE s.code = 'GOVIET'""",
                    po, "PO-GQ-" + po.toString().substring(24), warehouse, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 1000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        receiptId = receipts.create(new GoodsReceipts.Create(po, "DN-GQ", null), clerk).id();
        receipts.replaceLines(receiptId, List.of(line(205, new NewPackage(null, 100), new NewPackage(null, 100))));
        GoodsReceipts.LineView confirmed = receipts.confirm(receiptId, clerk).lines().getFirst();
        lineId = confirmed.id();
        assertThat(confirmed.qcProgress()).isEqualTo(GoodsReceipts.QcProgress.AWAITING_MOVE_TO_QC);
        assertThat(confirmed.looseQuantity()).isEqualTo(5);
        lpn1 = confirmed.packages().get(0).lpn();
        lpn2 = confirmed.packages().get(1).lpn();
    }

    private void area(UUID warehouse, String code, String locationCode, String type, int x) {
        UUID location = Identifiers.newId();
        jdbc.update("INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,"
                + " is_pickable, is_putaway_target, status) VALUES (?, ?, 'AREA', ?, 'NORMAL', FALSE, FALSE, 'ACTIVE')",
                location, warehouse, locationCode);
        jdbc.update("INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length,"
                + " rotation, is_obstacle, status) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 5, 5, 0, FALSE, 'ACTIVE')",
                Identifiers.newId(), warehouse, location, code, type, code, x);
    }

    private GoodsReceipts.NewLine line(int quantity, NewPackage... packages) {
        return new GoodsReceipts.NewLine(poLine, quantity, null, null, receiving, null, "CARTON", List.of(packages));
    }

    private GoodsReceipts.LineView moveToQc() {
        return receipts.moveToQc(receiptId, lineId, qcArea, clerk).lines().getFirst();
    }

    /** "STATUS:on hand" of the loose stock of the SKU at a location, "none" when nothing loose is there. */
    private String loose(String location) {
        return jdbc.query("""
                SELECT string_agg(status || ':' || total, ',' ORDER BY status) FROM (
                    SELECT status, SUM(on_hand) AS total FROM inventory.stock_item
                     WHERE sku = ? AND location_code = ? AND stock_package_id IS NULL AND on_hand > 0
                     GROUP BY status) layers""",
                (rs, n) -> rs.getString(1), sku, location).stream().filter(java.util.Objects::nonNull)
                .findFirst().orElse("none");
    }

    /** "location:status:unopened" of a carton. */
    private String carton(String lpn) {
        return jdbc.queryForObject("""
                select location_code || ':' || status || ':' || unopened from inventory.stock_package where lpn = ?""",
                String.class, lpn);
    }

    /** "location:STATUS:on hand" of each stock row in a carton. */
    private List<String> rowsOf(String lpn) {
        return jdbc.queryForList("""
                select s.location_code || ':' || s.status || ':' || s.on_hand from inventory.stock_item s
                  join inventory.stock_package p on p.id = s.stock_package_id where p.lpn = ?""", String.class, lpn);
    }

    private UUID cartonId(String lpn) {
        return jdbc.queryForObject("select id from inventory.stock_package where lpn = ?", UUID.class, lpn);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static List<String> fields(Throwable thrown) {
        return ((BusinessException) thrown).fieldErrors().stream().map(e -> e.field() + ":" + e.code()).toList();
    }

    @Test
    @DisplayName("moveToQc: both cartons go whole, unopened, their rows with them, and the 5 loose units go too")
    void moveToQcMovesCartonsAndLoose() {
        GoodsReceipts.LineView moved = moveToQc();

        assertThat(moved.qcProgress()).isEqualTo(GoodsReceipts.QcProgress.IN_QC_AREA);
        assertThat(moved.qcLocationCode()).isEqualTo(qcArea);
        assertThat(List.of(carton(lpn1), carton(lpn2))).containsOnly(qcArea + ":IN_STOCK:true");
        assertThat(rowsOf(lpn1)).as("a carton's rows are where the carton is").containsExactly(qcArea + ":INBOUND:100");
        assertThat(rowsOf(lpn2)).containsExactly(qcArea + ":INBOUND:100");
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_item s join inventory.stock_package p on p.id = s.stock_package_id
                 where p.goods_receipt_line_id = ? and s.location_code <> p.location_code""", Integer.class, lineId))
                .isZero();
        // The loose move runs before the cartons': written after, it would take their MOVE rows for its own replay.
        assertThat(loose(qcArea)).as("the loose units are in the QC area too").isEqualTo("INBOUND:5");
        assertThat(loose(receiving)).isEqualTo("none");
        assertThat(jdbc.queryForList("""
                select case when to_stock_package_id is null then 'loose' else 'carton' end || ':' || quantity
                  from inventory.stock_movement
                 where movement_type = 'MOVE' and reference_type = 'GOODS_RECEIPT_LINE' and reference_id = ?""",
                String.class, lineId)).containsExactlyInAnyOrder("loose:5", "carton:100", "carton:100");
    }

    @Test
    @DisplayName("quarantine one carton by LPN: it goes whole to the quarantine area, QUARANTINE and unopened; the rest is "
            + "accepted and stays")
    void quarantineOneCarton() {
        moveToQc();

        GoodsReceipts.ReceiptView decided = receipts.inspect(receiptId, lineId,
                new Decision(105, new Part(0, quarantine, "dented", List.of(lpn1)), null), qc);

        assertThat(carton(lpn1)).isEqualTo(quarantine + ":IN_STOCK:true");
        assertThat(rowsOf(lpn1)).containsExactly(quarantine + ":QUARANTINE:100");
        assertThat(carton(lpn2)).isEqualTo(qcArea + ":IN_STOCK:true");
        assertThat(rowsOf(lpn2)).containsExactly(qcArea + ":INBOUND:100");
        assertThat(loose(qcArea)).isEqualTo("INBOUND:5");
        assertThat(loose(quarantine)).isEqualTo("none");
        assertThat(decided.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        GoodsReceipts.LineView line = decided.lines().getFirst();
        assertThat(line.inspections()).extracting(GoodsReceipts.InspectionView::outcome,
                        GoodsReceipts.InspectionView::quantity)
                .containsExactlyInAnyOrder(tuple(QcOutcome.ACCEPTED, 105), tuple(QcOutcome.QUARANTINE, 100));
        assertThat(line.quantityForPutaway()).isEqualTo(105);
        UUID id = cartonId(lpn1);
        assertThat(jdbc.queryForList("""
                select from_status || '>' || to_status || ':' || quantity from inventory.stock_movement
                 where reference_type = 'QC_INSPECTION' and from_stock_package_id = ? and to_stock_package_id = ?""",
                String.class, id, id)).containsExactly("INBOUND>QUARANTINE:100");
    }

    @Test
    @DisplayName("3 loose and a carton quarantined, 2 loose rejected: 103 + 2 + 100 accepted add up, each where it belongs")
    void looseAndCartonTogether() {
        moveToQc();

        GoodsReceipts.ReceiptView decided = receipts.inspect(receiptId, lineId, new Decision(100,
                new Part(3, quarantine, "scratched", List.of(lpn2)), new Part(2, returnToVendor, "cracked")), qc);

        assertThat(carton(lpn2)).isEqualTo(quarantine + ":IN_STOCK:true");
        assertThat(rowsOf(lpn2)).containsExactly(quarantine + ":QUARANTINE:100");
        // The loose reclassify runs before the carton's: written after, it would take the carton's row for its replay.
        assertThat(loose(quarantine)).as("the 3 loose quarantined units").isEqualTo("QUARANTINE:3");
        assertThat(loose(returnToVendor)).isEqualTo("BLOCKED:2");
        assertThat(loose(qcArea)).as("no loose unit is left undecided").isEqualTo("none");
        assertThat(carton(lpn1)).isEqualTo(qcArea + ":IN_STOCK:true");
        assertThat(rowsOf(lpn1)).containsExactly(qcArea + ":INBOUND:100");
        assertThat(decided.lines().getFirst().inspections()).extracting(GoodsReceipts.InspectionView::outcome,
                        GoodsReceipts.InspectionView::quantity)
                .containsExactlyInAnyOrder(tuple(QcOutcome.ACCEPTED, 100), tuple(QcOutcome.QUARANTINE, 103),
                        tuple(QcOutcome.REJECTED, 2));
        assertThat(decided.status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
    }

    @Test
    @DisplayName("BR-08: a carton counts at what was counted into it; parts that do not add up are refused, nothing moves")
    void partsMustAddUp() {
        moveToQc();

        Throwable refused = catchThrowable(() -> receipts.inspect(receiptId, lineId,
                new Decision(100, new Part(0, quarantine, "dented", List.of(lpn1)), null), qc));

        assertThat(code(refused)).isEqualTo(ErrorCode.QC_QUANTITY_MISMATCH);
        assertThat(carton(lpn1)).isEqualTo(qcArea + ":IN_STOCK:true");
        assertThat(receipts.get(receiptId).lines().getFirst().qcProgress())
                .isEqualTo(GoodsReceipts.QcProgress.IN_QC_AREA);
    }

    @Test
    @DisplayName("loose parts above the line's 5 loose units are refused even when the total adds up")
    void looseAboveRemainder() {
        moveToQc();

        Throwable refused = catchThrowable(() -> receipts.inspect(receiptId, lineId,
                new Decision(195, new Part(10, quarantine, "dented"), null), qc));

        assertThat(code(refused)).isEqualTo(ErrorCode.QC_QUANTITY_MISMATCH);
        assertThat(loose(qcArea)).isEqualTo("INBOUND:5");
        assertThat(loose(quarantine)).isEqualTo("none");
        assertThat(receipts.get(receiptId).lines().getFirst().inspections()).isEmpty();
    }

    @Test
    @DisplayName("a carton of another receipt's line is not this line's: STOCK_PACKAGE_NOT_FOUND, and it stays put")
    void inspectRefusesAnotherLinesCarton() {
        moveToQc();
        UUID other = receipts.create(new GoodsReceipts.Create(po, "DN-GQ-2", null), clerk).id();
        String foreign = receipts.replaceLines(other, List.of(line(100, new NewPackage(null, 100))))
                .lines().getFirst().packages().getFirst().lpn();

        Throwable refused = catchThrowable(() -> receipts.inspect(receiptId, lineId,
                new Decision(105, new Part(0, quarantine, "dented", List.of(foreign)), null), qc));

        assertThat(code(refused)).isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
        assertThat(fields(refused)).containsExactly("quarantined.packages[0]:STOCK_PACKAGE_NOT_FOUND");
        assertThat(carton(foreign)).isEqualTo(receiving + ":RECEIVING:true");
    }

    @Test
    @DisplayName("a carton named in both parts is refused: VALIDATION_FAILED on the second naming")
    void sameCartonInBothParts() {
        moveToQc();

        Throwable refused = catchThrowable(() -> receipts.inspect(receiptId, lineId, new Decision(5,
                new Part(0, quarantine, "dented", List.of(lpn1)), new Part(0, returnToVendor, "cracked", List.of(lpn1))),
                qc));

        assertThat(code(refused)).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(fields(refused)).containsExactly("rejected.packages[0]:VALIDATION_FAILED");
        assertThat(carton(lpn1)).isEqualTo(qcArea + ":IN_STOCK:true");
    }

    @Test
    @DisplayName("a carton taken off the QC area by hand before the decision: STOCK_PACKAGE_WRONG_LOCATION")
    void cartonMovedOffQcArea() {
        moveToQc();
        inventory.moveStockPackage(new MoveStockPackageCommand(lpn1, receiving, MoveReference.MOVE_TASK,
                Identifiers.newId(), clerk));

        Throwable refused = catchThrowable(() -> receipts.inspect(receiptId, lineId,
                new Decision(105, new Part(0, quarantine, "dented", List.of(lpn1)), null), qc));

        assertThat(code(refused)).isEqualTo(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION);
        assertThat(fields(refused)).containsExactly("quarantined.packages[0]:STOCK_PACKAGE_WRONG_LOCATION");
        assertThat(carton(lpn1)).isEqualTo(receiving + ":IN_STOCK:true");
        assertThat(rowsOf(lpn1)).containsExactly(receiving + ":INBOUND:100");
    }
}
