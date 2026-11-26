package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
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
 * SCRUM-473 against a real PostgreSQL: the carton through its port, now that the carton is the stock (ADR-0011,
 * V20261231000600) - read back with its SKU, stock status, lot and FIFO date; recounted, voided and locked while it is
 * being received. And an adjustment named by SKU and bin and no carton is refused, leaving the bin's cartons as they
 * are: stock is adjusted by carton only.
 *
 * <p>Took the place of {@code StockPackageLayerIntegrationTest}, whose bin held a loose layer beside the cartons of
 * its receipt; there is no row beside a carton any more. (A person's move is by LPN:
 * {@code StockPackageMoveIntegrationTest}.)</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class StockPackagePersistenceIntegrationTest {

    private static final String SHELF = "HCM-A01-1-B";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired InventoryService inventory;
    @Autowired StockOperations operations;
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
            item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            carton = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, variant_id, unit_of_measure, factor)"
                    + " values (?, (select variant_id from inventory.inventory_items where id = ?), 'CARTON', 5)", carton, item);
            inCarton(SHELF, "A");
            inCarton(SHELF, "B");
        });
    }

    /** A carton of 5 of the receipt in stock: AVAILABLE, of the test's lot. */
    private UUID inCarton(String location, String name) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                    counted_at, location_code, status, stock_status, lot_id, received_at)
                values (?, ?, ?, ?, 5, now(), ?, 'IN_STOCK', 'AVAILABLE', ?, ?)""",
                id, "LPN-" + sku + "-" + name, item, carton, location,
                com.stockflow.support.CartonRows.lot(jdbc, sku, lot, RECEIVED), java.sql.Timestamp.from(RECEIVED));
        return id;
    }

    /** "location:status:stock status" of each carton of the SKU, by LPN. */
    private List<String> rows() {
        return jdbc.queryForList("""
                select p.location_code || ':' || p.status || ':' || p.stock_status
                  from inventory.stock_package p where p.inventory_item_id = ? order by p.lpn""", String.class, item);
    }

    @Test
    @DisplayName("an adjustment named by SKU and bin and no carton is refused when asked: stock is adjusted by "
            + "carton only, and no carton is opened for it (review C4 P-3)")
    void adjustmentNamingNoCartonIsRefused() {
        Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() -> tx.execute(s ->
                operations.requestAdjustment(new RequestAdjustmentCommand(null, SHELF, new Sku(sku),
                        StockAdjustmentReason.DAMAGED, "torn", clerk))));

        assertThat(refused).isInstanceOfSatisfying(com.stockflow.common.error.BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(com.stockflow.common.error.ErrorCode.STOCK_NOT_IN_CARTON));
        assertThat(rows()).as("neither carton is touched")
                .containsExactly(SHELF + ":IN_STOCK:AVAILABLE", SHELF + ":IN_STOCK:AVAILABLE");
    }

    @Test
    @DisplayName("a carton read back is the stock it is; a carton is read, recounted, voided and locked through its port")
    void stockPackagePersistence() {
        UUID packed = jdbc.queryForObject("select id from inventory.stock_package where inventory_item_id = ?"
                + " order by lpn limit 1", UUID.class, item);
        java.util.Optional<StockPackage> inStock = tx.execute(s -> stockPackages.findById(new StockPackageId(packed)));
        assertThat(inStock).get().satisfies(read -> {
            assertThat(read.sku()).as("its item's SKU, read with it").isEqualTo(new Sku(sku));
            assertThat(read.stockStatus()).isEqualTo(com.stockflow.inventory.internal.domain.StockStatus.AVAILABLE);
            assertThat(read.lotNumber()).as("its lot's number, read with it").isEqualTo(lot);
            assertThat(read.receivedAt()).as("its FIFO date").isEqualTo(RECEIVED);
            assertThat(read.unitsInStock()).as("in stock it stands for its count").isEqualTo(5);
            assertThat(read.isFree()).isTrue();
        });

        // A carton labelled on a receipt still being counted (no receipt line: as one repacked in the warehouse).
        UUID labelled = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                    location_code) values (?, ?, ?, ?, 5, 'HCM-RCV01')""", labelled, "LPN-" + sku + "-R", item, carton));
        tx.executeWithoutResult(s -> {
            StockPackage receiving = stockPackages.findByLpn("LPN-" + sku + "-R").orElseThrow();
            assertThat(receiving.status()).isEqualTo(StockPackageStatus.RECEIVING);
            receiving.count(Instant.parse("2026-11-20T03:00:00Z"));
            stockPackages.save(receiving);
        });
        tx.executeWithoutResult(s -> {
            StockPackage counted = stockPackages.findAllByIdForUpdate(List.of(new StockPackageId(labelled))).getFirst();
            assertThat(counted.countedAt()).as("when it was counted, kept").isEqualTo(Instant.parse("2026-11-20T03:00:00Z"));
            assertThat(counted.nominalQty()).as("what it stands for").isEqualTo(5);
            counted.cancel(Instant.now());
            stockPackages.save(counted);
        });
        assertThat(jdbc.queryForMap("select status, counted_at is not null as counted, closed_at is not null as closed"
                + " from inventory.stock_package where id = ?", labelled))
                .containsEntry("status", "VOID").containsEntry("counted", true).containsEntry("closed", true);
    }
}
