package com.stockflow.inventory;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261230000200 on the real upgrade path (ADR-0011, step 2): ledger lines written while a line had a carton per side
 * end with the one carton they were about.
 *
 * <ul>
 *   <li><b>The carton.</b> A line that named it on the arriving side only (a receipt), on the leaving side only (a
 *       pick, a write-off) or on both (a putaway) names it in {@code stock_package_id}; which way it went is still
 *       said by the line's places, which the migration does not touch.</li>
 *   <li><b>Loose history.</b> A line that named no carton stays, naming none: the new CHECK is NOT VALID so that it
 *       does.</li>
 *   <li><b>From then on.</b> A new line without a carton is refused, and no function is left reading a column that is
 *       gone (Postgres does not track what a function body mentions).</li>
 * </ul>
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the
 * migration on rows the old shape held, not how the application made them. The cartons are real rows, because the
 * migration adds the new column's foreign key over the lines that are there.</p>
 */
class LedgerOneCartonColumnMigrationUpgradeTest {

    /** The last migration before the one under test. */
    private static final String BEFORE = "20261230000100";
    private static final String MIGRATION = "20261230000200";

    private static final String CARTON_1 = "00000000-0000-4000-8000-0000000c0021";
    private static final String CARTON_2 = "00000000-0000-4000-8000-0000000c0022";
    private static final String CARTON_3 = "00000000-0000-4000-8000-0000000c0023";

    @Test
    void theTwoSidesBecomeTheOneCartonAndNewLinesMustNameIt() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // ---- one carton per line, the places as they were
                assertThat(RawSql.texts(db, "SELECT reason || ':' || coalesce(right(stock_package_id::text, 2), 'none')"
                        + " || ':' || coalesce(from_location_code, '-') || '>' || coalesce(to_location_code, '-') "
                        + "FROM inventory.stock_movement WHERE sku = 'SKU-UPG-L' ORDER BY reason"))
                        .as("arriving side, both sides, leaving side (twice), and a loose line that names none")
                        .containsExactly("1 receipt:21:->HCM-RCV01", "2 putaway:21:HCM-RCV01>HCM-A01-1-A",
                                "3 pick:22:HCM-A01-1-A>-", "4 write-off:23:HCM-A01-1-B>-",
                                "5 loose:none:HCM-A01-1-A>HCM-A01-1-B");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_movement' AND column_name IN ('from_stock_package_id', "
                        + "'to_stock_package_id')")).as("the two sides are dropped").isZero();
                assertThat(RawSql.texts(db, "SELECT indexname FROM pg_indexes WHERE schemaname = 'inventory' AND "
                        + "tablename = 'stock_movement' AND indexdef LIKE '%stock_package_id%' ORDER BY indexname"))
                        .as("one index on the carton, and the return receipt's one line per carton")
                        .containsExactly("ix_stock_movement_package", "uk_stock_movement_return_receipt");

                // ---- what read a side reads the carton
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE prosrc ~ "
                        + "'(from|to)_stock_package_id'")).as("no function still reads a dropped column").isZero();
                assertThat(RawSql.texts(db, "SELECT tgname FROM pg_trigger WHERE tgrelid = "
                        + "'inventory.stock_movement'::regclass AND NOT tgisinternal AND tgname IN "
                        + "('tg_stock_movement_package', 'tg_stock_movement_manual_move') ORDER BY tgname"))
                        .as("the two triggers that named a side are back").hasSize(2);

                // ---- from then on (triggers off: the CHECK itself is what is asked)
                assertThat(RawSql.text(db, "SELECT convalidated::text FROM pg_constraint WHERE conname = "
                        + "'ck_stock_movement_names_carton'")).as("NOT VALID: the loose line above is history")
                        .isEqualTo("false");
                RawSql.update(db, "SET session_replication_role = replica");
                String line = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, "
                        + "quantity, reference_type, reference_id, occurred_at, stock_package_id) VALUES "
                        + "(gen_random_uuid(), 'PICK', 'SKU-UPG-L', 'HCM-A01-1-A', 50, 'PICK_LINE', gen_random_uuid(), "
                        + "NOW(), %s)";
                RawSql.refusedBy(db, "ck_stock_movement_names_carton", "a new line that names no carton",
                        line.formatted("NULL"));
                RawSql.update(db, line.formatted("?::uuid"), CARTON_2);
                RawSql.update(db, "SET session_replication_role = origin");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        for (String carton : new String[] {CARTON_1, CARTON_2, CARTON_3}) {
            RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                    + "nominal_qty, received_qty, location_code, status) VALUES (?::uuid, ?, gen_random_uuid(), "
                    + "gen_random_uuid(), 50, 50, 'HCM-A01-1-A', 'IN_STOCK')", carton,
                    "UPG-L-" + carton.substring(carton.length() - 2));
        }
        String line = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, "
                + "to_location_code, quantity, reference_type, reference_id, reason, occurred_at, "
                + "from_stock_package_id, to_stock_package_id) VALUES (gen_random_uuid(), ?, 'SKU-UPG-L', ?, ?, 50, ?, "
                + "gen_random_uuid(), ?, NOW(), ?::uuid, ?::uuid)";
        // Arrived: the carton on the arriving side only.
        RawSql.update(db, line, "RECEIPT", null, "HCM-RCV01", "GOODS_RECEIPT_LINE", "1 receipt", null, CARTON_1);
        // Moved whole: the same carton on both sides.
        RawSql.update(db, line, "MOVE", "HCM-RCV01", "HCM-A01-1-A", "PUTAWAY_TASK", "2 putaway", CARTON_1, CARTON_1);
        // Left: the carton on the leaving side only.
        RawSql.update(db, line, "PICK", "HCM-A01-1-A", null, "PICK_LINE", "3 pick", CARTON_2, null);
        RawSql.update(db, line, "ADJUSTMENT", "HCM-A01-1-B", null, "STOCK_ADJUSTMENT", "4 write-off", CARTON_3, null);
        // Loose units moved for a return line, from before everything was boxed: no carton on either side.
        RawSql.update(db, line, "MOVE", "HCM-A01-1-A", "HCM-A01-1-B", "RETURN_REQUEST_LINE", "5 loose", null, null);
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
