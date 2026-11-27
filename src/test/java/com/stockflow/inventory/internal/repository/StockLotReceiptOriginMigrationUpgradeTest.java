package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.LotTrace;
import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261214000200 on the real upgrade path (receiving redundancy removal, A3). It drops the {@code GOODS_RECEIPT}
 * origin of {@code inventory.stock_lot}, its two columns and its index. A lot stored with that origin before it holds
 * stock rows and may be named by ledger lines, so it must stay and become {@code UNTRACED}, with the lot trace still
 * reading it; the rows are written with triggers off because what is tested is the migration on rows the old rules
 * accepted. That a new lot cannot take the origin is {@code tools/db/qa/96_receiving_redundancy_removal.sql}'s (section
 * A3), which cannot stage an old row: it runs after the migration.
 *
 * <p>In this package because {@link LotTraceSearchAdapter} is package-private.</p>
 */
class StockLotReceiptOriginMigrationUpgradeTest {

    private static final String BEFORE = "20261214000100";
    private static final String MIGRATION = "20261214000200";
    private static final String LOT = "00000000-0000-4000-8000-0000000e0a01";
    private static final String CARTON = "00000000-0000-4000-8000-0000000e0a02";
    private static final String ROW = "00000000-0000-4000-8000-0000000e0a03";
    private static final String RECEIPT = "00000000-0000-4000-8000-0000000e0a04";
    private static final String SKU = "UPG-LOT-SKU";

    @Test
    void aLotOfTheRemovedOriginIsKeptAsUntracedAndStillTraced() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT origin_type || ':' || lot_number FROM inventory.stock_lot "
                        + "WHERE id = ?::uuid", LOT)).as("the lot stays, no longer of a receipt")
                        .containsExactly("UNTRACED:UPG-LOT-1");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item WHERE lot_id = ?::uuid", LOT))
                        .as("the stock it holds still names it").isEqualTo(1);
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_lot' AND column_name LIKE 'goods_receipt%'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conname IN "
                        + "('ck_stock_lot_origin', 'ck_stock_lot_production_order') AND convalidated"))
                        .as("the rewritten rules hold for the rows that were there").isEqualTo(2);
                RawSql.refusedBy(db, "ck_stock_lot_origin", "a lot of the removed origin",
                        "INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type) "
                                + "VALUES (gen_random_uuid(), ?, 'UPG-LOT-2', NOW(), 'GOODS_RECEIPT')", SKU);

                // The adapter is today's code and reads today's schema: its ledger sums go through the carton a line
                // names (V20261231000400), which this old version does not have yet. The rest of the path is run
                // first; the lot, its carton and its row are carried through it.
                flyway(postgres, "latest").migrate();
                var adapter = new LotTraceSearchAdapter(new JdbcTemplate(new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
                Optional<LotTrace.Summary> traced = adapter.lot(UUID.fromString(LOT), Set.of());
                assertThat(traced).isPresent();
                assertThat(traced.get().origin()).isEqualTo("UNTRACED");
                assertThat(traced.get().onHand()).isEqualTo(10);
                assertThat(adapter.balances(UUID.fromString(LOT), Set.of())).hasSize(1);
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        // The receipt the lot names is a real row, and a PRODUCTION one: V20261214000100 has already deleted every
        // PURCHASE receipt (and turned the lots they opened UNTRACED), so a lot of the removed origin can only still name
        // a receipt that migration leaves - a production receipt from before lots followed production orders.
        RawSql.update(db, "INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, "
                + "production_order_id, warehouse_id, status, received_at, received_by, confirmed_at, confirmed_by, "
                + "created_at) VALUES (?::uuid, 'GR-UPG-1', 'PRODUCTION', gen_random_uuid(), gen_random_uuid(), "
                + "'IN_PUTAWAY', NOW(), gen_random_uuid(), NOW(), gen_random_uuid(), NOW())", RECEIPT);
        RawSql.update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, "
                + "goods_receipt_id, goods_receipt_number, created_at) VALUES (?::uuid, ?, 'UPG-LOT-1', NOW(), "
                + "'GOODS_RECEIPT', ?::uuid, 'GR-UPG-1', NOW())", LOT, SKU, RECEIPT);
        RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                + "nominal_qty, received_qty, location_code, status, created_at) VALUES (?::uuid, 'UPG-LPN-1', "
                + "gen_random_uuid(), gen_random_uuid(), 10, 10, 'HCM-A01-1-A', 'IN_STOCK', NOW())", CARTON);
        RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, lot_id, on_hand, "
                + "reserved, status, created_at, received_at, stock_package_id) VALUES (?::uuid, ?, 'HCM-A01-1-A', "
                + "'UPG-LOT-1', ?::uuid, 10, 0, 'AVAILABLE', NOW(), NOW(), ?::uuid)", ROW, SKU, LOT, CARTON);
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
