package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.service.StockOperations;
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

/**
 * SCRUM-473 (#140) against a real PostgreSQL: a bin holding a loose layer and cartons of the very same receipt
 * (V20261020000200 keys a carton's row by the carton, so all three share SKU, bin, lot and receipt time). A move
 * or an adjustment named by those takes the loose layer and never a carton - it used to pick one by chance, or
 * refuse with INVENTORY_POLICY_STOCK_CONFLICT - and goods arriving at a bin arrive loose.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class StockPackageLayerIntegrationTest {

    private static final String SHELF = "HCM-A01-1-B";
    private static final String OTHER_BIN = "HCM-A02-2-A";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
    @Autowired StockItemRepository stockItems;
    @Autowired StockPackageRepository stockPackages;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private String lot;
    private UUID item;
    private UUID carton;
    private UUID clerk;
    private UUID approver;

    @BeforeEach
    void setUp() {
        sku = "PKG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        lot = "LOT-" + UUID.randomUUID().toString().substring(0, 6);
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Carton fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Carton fixture");
            jdbc.update("update inventory.inventory_items set lot_tracked=true, removal_strategy='FIFO',"
                    + " policy_configured=true where sku=?", sku);
            item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            carton = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 5)", carton, item);
            loose(SHELF, 10);
            inCarton(SHELF, "A");
            inCarton(SHELF, "B");
        });
    }

    private void loose(String location, int onHand) {
        jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, lot_number, received_at, on_hand, reserved,
                    status, version, created_at) values (?, ?, ?, ?, ?, ?, 0, 'AVAILABLE', 0, now())""",
                Identifiers.newId(), sku, location, lot, java.sql.Timestamp.from(RECEIVED), onHand);
    }

    /** An unopened carton of 5 of the same receipt, counted in at 5. */
    private UUID inCarton(String location, String name) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                    received_qty, location_code, status) values (?, ?, ?, ?, 5, 5, ?, 'IN_STOCK')""",
                id, "LPN-" + sku + "-" + name, item, carton, location);
        jdbc.update("""
                insert into inventory.stock_item (id, sku, location_code, lot_number, received_at, on_hand, reserved,
                    status, version, created_at, stock_package_id) values (?, ?, ?, ?, ?, 5, 0, 'AVAILABLE', 0, now(), ?)""",
                Identifiers.newId(), sku, location, lot, java.sql.Timestamp.from(RECEIVED), id);
        return id;
    }

    /** "location:on_hand:carton-or-loose", by location then on_hand. */
    private List<String> rows() {
        return jdbc.queryForList("""
                select location_code, on_hand, stock_package_id from inventory.stock_item
                 where sku = ? order by location_code, on_hand, stock_package_id nulls first""", sku).stream()
                .map(r -> r.get("location_code") + ":" + r.get("on_hand") + ":"
                        + (r.get("stock_package_id") == null ? "loose" : "carton"))
                .toList();
    }

    @Test
    @DisplayName("a move named by SKU, bin, lot and receipt time takes the loose layer; the cartons are untouched")
    void moveTakesTheLooseLayer() {
        tx.executeWithoutResult(s -> inventory.move(new MoveStockCommand(UUID.randomUUID(), new Sku(sku), lot, SHELF,
                OTHER_BIN, 3, MoveReference.MOVE_TASK, Identifiers.newId(), clerk, RECEIVED)));

        assertThat(rows()).containsExactly(SHELF + ":5:carton", SHELF + ":5:carton", SHELF + ":7:loose",
                OTHER_BIN + ":3:loose");
    }

    @Test
    @DisplayName("without a receipt time the one loose layer is found: the cartons beside it no longer make it ambiguous")
    void moveWithoutReceiptTime() {
        tx.executeWithoutResult(s -> inventory.move(new MoveStockCommand(UUID.randomUUID(), new Sku(sku), lot, SHELF,
                OTHER_BIN, 2, MoveReference.MOVE_TASK, Identifiers.newId(), clerk, null)));

        assertThat(rows()).containsExactly(SHELF + ":5:carton", SHELF + ":5:carton", SHELF + ":8:loose",
                OTHER_BIN + ":2:loose");
    }

    @Test
    @DisplayName("goods moved onto a bin holding a carton of their receipt arrive loose, never into the carton")
    void arrivalIsLoose() {
        tx.executeWithoutResult(s -> inCarton(OTHER_BIN, "C"));

        tx.executeWithoutResult(s -> inventory.move(new MoveStockCommand(UUID.randomUUID(), new Sku(sku), lot, SHELF,
                OTHER_BIN, 4, MoveReference.MOVE_TASK, Identifiers.newId(), clerk, RECEIVED)));

        assertThat(rows()).containsExactly(SHELF + ":5:carton", SHELF + ":5:carton", SHELF + ":6:loose",
                OTHER_BIN + ":4:loose", OTHER_BIN + ":5:carton");
    }

    @Test
    @DisplayName("an adjustment named by SKU, bin and lot is the loose layer's, requested and posted")
    void adjustmentTakesTheLooseLayer() {
        var requested = tx.execute(s -> operations.requestAdjustment(new RequestAdjustmentCommand(SHELF,
                new Sku(sku), lot, -2, StockAdjustmentReason.DAMAGED, "torn", clerk)));
        tx.executeWithoutResult(s -> operations.approve(requested.adjustmentId(), approver));

        assertThat(rows()).containsExactly(SHELF + ":5:carton", SHELF + ":5:carton", SHELF + ":8:loose");
    }

    @Test
    @DisplayName("a row read back knows its carton; a carton is read, recounted, voided and locked through its port")
    void stockPackagePersistence() {
        List<com.stockflow.inventory.internal.domain.StockItem> loose =
                tx.execute(s -> stockItems.findLooseBySkuAndLocation(new Sku(sku), new LocationId(SHELF)));
        assertThat(loose).singleElement().satisfies(row -> assertThat(row.isLoose()).isTrue());
        UUID packed = jdbc.queryForObject("""
                select stock_package_id from inventory.stock_item where sku = ? and stock_package_id is not null
                 order by stock_package_id limit 1""", UUID.class, sku);
        UUID rowId = jdbc.queryForObject("select id from inventory.stock_item where stock_package_id = ?", UUID.class,
                packed);
        java.util.Optional<com.stockflow.inventory.internal.domain.StockItem> inCarton =
                tx.execute(s -> stockItems.findById(new com.stockflow.inventory.internal.domain.StockItemId(rowId)));
        assertThat(inCarton).get().satisfies(row -> assertThat(row.stockPackageId()).isEqualTo(new StockPackageId(packed)));

        // A carton labelled on a receipt still being counted (no receipt line: as one repacked in the warehouse).
        UUID labelled = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                    location_code) values (?, ?, ?, ?, 5, 'HCM-RCV01')""", labelled, "LPN-" + sku + "-R", item, carton));
        tx.executeWithoutResult(s -> {
            StockPackage receiving = stockPackages.findByLpn("LPN-" + sku + "-R").orElseThrow();
            assertThat(receiving.status()).isEqualTo(StockPackageStatus.RECEIVING);
            receiving.count(4);
            stockPackages.save(receiving);
        });
        tx.executeWithoutResult(s -> {
            StockPackage counted = stockPackages.findAllByIdForUpdate(List.of(new StockPackageId(labelled))).getFirst();
            assertThat(counted.receivedQty()).isEqualTo(4);
            counted.cancel(Instant.now());
            stockPackages.save(counted);
        });
        assertThat(jdbc.queryForMap("select status, received_qty, closed_at is not null as closed"
                + " from inventory.stock_package where id = ?", labelled))
                .containsEntry("status", "VOID").containsEntry("received_qty", 4).containsEntry("closed", true);
    }
}
