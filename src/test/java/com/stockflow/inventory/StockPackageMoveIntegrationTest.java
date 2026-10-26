package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.CountReceivingPackagesCommand.PackageCount;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockDisposition;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-473: a carton received into stock moves and is reclassified whole, keeping its LPN, its stock row and its
 * unopened state. Commits like production: the carton's rows are checked against it at commit (V20261020000200).
 * Each test counts a line of 2000 as one carton of 1000 and one of 980 and receives it (20 loose), then works on
 * the first carton.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class StockPackageMoveIntegrationTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final String BIN = "HCM-A01-1-A";
    private static final String QUARANTINE = "HCM-QC01";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

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
        sku = "SPM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
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
                    po, "PO-SPM-" + po.toString().substring(24), submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 2000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(po, "DN-SPM", null), clerk);
        line = receipts.replaceLines(draft.id(), List.of(new GoodsReceipts.NewLine(poLine, 2000, null, null,
                RECEIVING, null))).lines().getFirst().id();
    }

    private String lpn;
    private UUID cartonId;

    private List<StockPackageView> count(PackageCount... packages) {
        return inventory.countReceivingPackages(new CountReceivingPackagesCommand(line, new Sku(sku),
                packages.length == 0 ? null : carton, RECEIVING, List.of(packages), clerk));
    }

    /** Counts the two cartons and receives the line: the first carton is IN_STOCK, unopened, with one INBOUND row. */
    private void receiveCartons() {
        lpn = count(new PackageCount(null, 1000), new PackageCount(null, 980)).getFirst().lpn();
        inventory.receive(new ReceiveStockCommand(line, new Sku(sku), null, null, RECEIVING, 2000, clerk, RECEIVED));
        cartonId = jdbc.queryForObject("select id from inventory.stock_package where lpn = ?", UUID.class, lpn);
    }

    private MoveStockPackagesCommand move(String to) {
        return new MoveStockPackagesCommand(lpn, to, MoveReference.MOVE_TASK, Identifiers.newId(), clerk);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    private String cartonState() {
        return jdbc.queryForObject("select location_code || ':' || status || ':' || unopened"
                + " from inventory.stock_package where lpn = ?", String.class, lpn);
    }

    private String rowState() {
        return jdbc.queryForObject("select location_code || ':' || status || ':' || on_hand"
                + " from inventory.stock_item where stock_package_id = ?", String.class, cartonId);
    }

    @Test
    @DisplayName("a move takes the carton and its row along: same row, still unopened, one MOVE line naming the carton")
    void moveTakesTheRowsAlong() {
        receiveCartons();
        UUID row = jdbc.queryForObject("select id from inventory.stock_item where stock_package_id = ?", UUID.class,
                cartonId);

        StockPackageView moved = inventory.moveStockPackages(move(BIN)).getFirst();

        assertThat(moved.lpn()).isEqualTo(lpn);
        assertThat(moved.locationCode()).isEqualTo(BIN);
        assertThat(moved.quantity()).isEqualTo(1000);
        assertThat(moved.unopened()).isTrue();
        assertThat(cartonState()).isEqualTo(BIN + ":IN_STOCK:true");
        assertThat(rowState()).isEqualTo(BIN + ":INBOUND:1000");
        assertThat(jdbc.queryForObject("select id from inventory.stock_item where stock_package_id = ?", UUID.class,
                cartonId)).as("the row is the same one").isEqualTo(row);
        assertThat(jdbc.query("""
                select from_stock_package_id, to_stock_package_id, quantity from inventory.stock_movement
                 where movement_type = 'MOVE' and sku = ?""",
                (rs, n) -> rs.getObject("from_stock_package_id", UUID.class) + "|"
                        + rs.getObject("to_stock_package_id", UUID.class) + "|" + rs.getInt("quantity"), sku))
                .containsExactly(cartonId + "|" + cartonId + "|1000");
    }

    @Test
    @DisplayName("another warehouse is refused as the loose move is: MOVE_ACROSS_WAREHOUSES")
    void moveRefusesAnotherWarehouse() {
        receiveCartons();

        assertThatThrownBy(() -> inventory.moveStockPackages(move("HN-RCV01")))
                .extracting(StockPackageMoveIntegrationTest::code).isEqualTo(ErrorCode.MOVE_ACROSS_WAREHOUSES);
        assertThat(cartonState()).isEqualTo(RECEIVING + ":IN_STOCK:true");
    }

    @Test
    @DisplayName("the carton's own location is refused: VALIDATION_FAILED")
    void moveRefusesSameLocation() {
        receiveCartons();

        assertThatThrownBy(() -> inventory.moveStockPackages(move(RECEIVING)))
                .extracting(StockPackageMoveIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a closed (VOID) carton does not move: INVALID_STOCK_PACKAGE_TRANSITION")
    void moveRefusesAClosedCarton() {
        lpn = count(new PackageCount(null, 1000)).getFirst().lpn();
        count();   // the empty list voids it

        assertThatThrownBy(() -> inventory.moveStockPackages(move(BIN)))
                .extracting(StockPackageMoveIntegrationTest::code)
                .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
    }

    @Test
    @DisplayName("quarantine in place: the row is QUARANTINE in the carton at the quarantine area, ledger INBOUND to QUARANTINE")
    void reclassifyQuarantinesInPlace() {
        receiveCartons();

        StockPackageView view = inventory.reclassifyStockPackages(new ReclassifyStockPackagesCommand(lpn, QUARANTINE,
                StockDisposition.QUARANTINE, MoveReference.MOVE_TASK, Identifiers.newId(), clerk, "damaged outer"))
                .getFirst();

        assertThat(view.locationCode()).isEqualTo(QUARANTINE);
        assertThat(view.unopened()).isTrue();
        assertThat(cartonState()).isEqualTo(QUARANTINE + ":IN_STOCK:true");
        assertThat(rowState()).isEqualTo(QUARANTINE + ":QUARANTINE:1000");
        assertThat(jdbc.query("""
                select from_status, to_status, from_stock_package_id = to_stock_package_id as same_carton
                  from inventory.stock_movement where movement_type = 'MOVE' and sku = ?""",
                (rs, n) -> rs.getString("from_status") + ">" + rs.getString("to_status") + ":" + rs.getBoolean("same_carton"),
                sku)).containsExactly("INBOUND>QUARANTINE:true");
    }

    @Test
    @DisplayName("an unknown LPN is STOCK_PACKAGE_NOT_FOUND")
    void unknownLpn() {
        receiveCartons();

        assertThatThrownBy(() -> inventory.moveStockPackages(new MoveStockPackagesCommand("LPN-19990101-000000", BIN,
                MoveReference.MOVE_TASK, Identifiers.newId(), clerk)))
                .extracting(StockPackageMoveIntegrationTest::code).isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("both cartons move in one call, each with its row and one MOVE line naming it")
    void severalCartonsInOneCall() {
        receiveCartons();
        List<String> both = jdbc.queryForList("select lpn from inventory.stock_package where goods_receipt_line_id = ?"
                + " order by lpn", String.class, line);

        List<StockPackageView> moved = inventory.moveStockPackages(new MoveStockPackagesCommand(both, BIN,
                MoveReference.MOVE_TASK, Identifiers.newId(), clerk));

        assertThat(moved).extracting(StockPackageView::lpn).containsExactlyElementsOf(both);
        assertThat(moved).extracting(StockPackageView::locationCode).containsOnly(BIN);
        assertThat(moved).extracting(StockPackageView::quantity).containsExactly(1000, 980);
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_movement
                 where movement_type = 'MOVE' and sku = ? and from_stock_package_id = to_stock_package_id""",
                Integer.class, sku)).isEqualTo(2);
    }

    @Test
    @DisplayName("a loose move under the reference a carton moved with is not taken for a replay: the loose units move")
    void looseMoveAfterCartonUnderOneReference() {
        receiveCartons();
        UUID reference = Identifiers.newId();
        inventory.moveStockPackages(new MoveStockPackagesCommand(lpn, BIN, MoveReference.MOVE_TASK, reference, clerk));

        inventory.move(new com.stockflow.inventory.api.MoveStockCommand(Identifiers.newId(), new Sku(sku), null,
                RECEIVING, BIN, 20, MoveReference.MOVE_TASK, reference, clerk, RECEIVED));

        assertThat(jdbc.queryForObject("""
                select coalesce(sum(on_hand), 0) from inventory.stock_item
                 where sku = ? and location_code = ? and stock_package_id is null""",
                Integer.class, sku, BIN)).isEqualTo(20);
    }
}
