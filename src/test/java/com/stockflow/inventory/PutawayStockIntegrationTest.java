package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.CountReceivingPackagesCommand.PackageCount;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.service.StockOperations;
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
 * SCRUM-92: the stock primitives putaway stands on. A line of 2000 is counted as cartons 1000 + 980 and received
 * (20 loose); a carton goes to a bin as AVAILABLE without being opened, and loose units go in several parts under
 * one putaway task, each part its own putaway (no replay lookup).
 */
@IntegrationTest
@Import(PostgresContainer.class)
class PutawayStockIntegrationTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final String BIN = "HCM-A01-1-A";
    private static final String BIN2 = "HCM-B01-1-B";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired StockOperations stockOperations;
    @Autowired StockMovementLog ledger;
    @Autowired GoodsReceipts receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID carton;
    private UUID clerk;
    private UUID line;
    private String lpn;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        sku = "PUT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        UUID poLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Putaway fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Putaway fixture");
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            jdbc.update("update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8, height_cm = 6"
                    + " where id = ?", item);
            carton = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 1000)", carton, item);

            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, "PO-PUT-" + po.toString().substring(24), submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 2000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(po, "DN-PUT", null), clerk);
        line = receipts.replaceLines(draft.id(), List.of(new GoodsReceipts.NewLine(poLine, 2000, null, null,
                RECEIVING, null))).lines().getFirst().id();
        lpn = inventory.countReceivingPackages(new CountReceivingPackagesCommand(line, new Sku(sku), carton,
                RECEIVING, List.of(new PackageCount(null, 1000), new PackageCount(null, 980)), clerk))
                .getFirst().lpn();
        inventory.receive(new ReceiveStockCommand(line, new Sku(sku), null, null, RECEIVING, 2000, clerk, RECEIVED));
        lotId = jdbc.queryForObject("select lot_id from inventory.stock_item where sku = ? and stock_package_id is null",
                UUID.class, sku);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    private int units(String location, String status) {
        return jdbc.queryForObject("""
                select coalesce(sum(on_hand), 0) from inventory.stock_item
                 where sku = ? and stock_package_id is null and location_code = ? and status = ?""",
                Integer.class, sku, location, status);
    }

    @Test
    @DisplayName("a carton put away as AVAILABLE stays one unopened carton: same row, now AVAILABLE at the bin")
    void cartonPutAwayAsAvailableStaysUnopened() {
        UUID cartonId = jdbc.queryForObject("select id from inventory.stock_package where lpn = ?", UUID.class, lpn);
        UUID row = jdbc.queryForObject("select id from inventory.stock_item where stock_package_id = ?", UUID.class,
                cartonId);

        inventory.reclassifyStockPackages(new ReclassifyStockPackagesCommand(lpn, BIN, StockDisposition.AVAILABLE,
                MoveReference.PUTAWAY_TASK, Identifiers.newId(), clerk, null));

        assertThat(jdbc.queryForObject("select location_code || ':' || status || ':' || unopened"
                + " from inventory.stock_package where lpn = ?", String.class, lpn))
                .isEqualTo(BIN + ":IN_STOCK:true");
        assertThat(jdbc.queryForObject("select id || ':' || location_code || ':' || status || ':' || on_hand"
                + " from inventory.stock_item where stock_package_id = ?", String.class, cartonId))
                .isEqualTo(row + ":" + BIN + ":AVAILABLE:1000");
    }

    @Test
    @DisplayName("loose units put away in two parts under one task: both parts move, one ledger total")
    void putAwayLooseTwiceUnderOneTaskMovesBoth() {
        UUID task = Identifiers.newId();

        assertThat(stockOperations.putAwayLoose(new Sku(sku), lotId, BIN, 12, task, clerk)).isEqualTo(12);
        assertThat(stockOperations.putAwayLoose(new Sku(sku), lotId, BIN2, 8, task, clerk)).isEqualTo(8);

        assertThat(units(BIN, "AVAILABLE")).isEqualTo(12);
        assertThat(units(BIN2, "AVAILABLE")).isEqualTo(8);
        assertThat(units(RECEIVING, "INBOUND")).isZero();
        assertThat(ledger.sumQuantityByReference(List.of(StockMovement.MovementType.MOVE),
                StockMovement.ReferenceType.PUTAWAY_TASK, task)).isEqualTo(20);
        assertThat(ledger.sumQuantityByReference(List.of(StockMovement.MovementType.MOVE),
                StockMovement.ReferenceType.PUTAWAY_TASK, Identifiers.newId())).isZero();
    }

    @Test
    @DisplayName("two lots of one SKU in the receiving bin: putaway of one lot moves only that lot's units")
    void putAwayLooseMovesOnlyTheNamedLot() {
        UUID otherLot = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("create temp table lot_copy on commit drop as select * from inventory.stock_lot where id = ?",
                    lotId);
            jdbc.update("update lot_copy set id = ?, lot_number = 'L2'", otherLot);
            jdbc.update("insert into inventory.stock_lot select * from lot_copy");
            jdbc.update("create temp table item_copy on commit drop as select * from inventory.stock_item"
                    + " where sku = ? and stock_package_id is null", sku);
            jdbc.update("update item_copy set id = ?, lot_id = ?, lot_number = 'L2', on_hand = 7",
                    Identifiers.newId(), otherLot);
            jdbc.update("insert into inventory.stock_item select * from item_copy");
        });

        stockOperations.putAwayLoose(new Sku(sku), otherLot, BIN, 5, Identifiers.newId(), clerk);

        assertThat(jdbc.queryForObject("select coalesce(sum(on_hand), 0) from inventory.stock_item"
                + " where sku = ? and lot_id = ? and location_code = ? and status = 'AVAILABLE'", Integer.class, sku,
                otherLot, BIN)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select coalesce(sum(on_hand), 0) from inventory.stock_item"
                + " where sku = ? and lot_id = ? and status = 'INBOUND' and stock_package_id is null", Integer.class,
                sku, otherLot)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select coalesce(sum(on_hand), 0) from inventory.stock_item"
                + " where sku = ? and lot_id = ? and status = 'INBOUND' and stock_package_id is null", Integer.class,
                sku, lotId)).isEqualTo(20);
    }

    @Test
    @DisplayName("more units than the receipt's loose goods is refused and nothing moves: STOCK_ITEM_NOT_FOUND")
    void putAwayLooseMoreThanThereIsRefused() {
        assertThatThrownBy(() -> stockOperations.putAwayLoose(new Sku(sku), lotId, BIN, 21, Identifiers.newId(),
                clerk)).extracting(PutawayStockIntegrationTest::code).isEqualTo(ErrorCode.STOCK_ITEM_NOT_FOUND);

        assertThat(units(RECEIVING, "INBOUND")).isEqualTo(20);
        assertThat(units(BIN, "AVAILABLE")).isZero();
    }
}
