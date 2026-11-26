package com.stockflow.inventory;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261231000400 on the real upgrade path (ADR-0011, step 3b): ledger lines written while a line carried a quantity
 * end as the one carton they name.
 *
 * <ul>
 *   <li><b>Nothing is guessed.</b> A line whose quantity is not its carton's standard count says that part of a carton
 *       moved, which no carton can say: the migration refuses to run and names the line, rather than drop the
 *       number.</li>
 *   <li><b>History.</b> A line that names no carton - loose stock from before everything was boxed, which step 2 let
 *       stand - is deleted: without a quantity it would record nothing. The lines that name a carton stay, each
 *       standing for its carton's {@code nominal_qty}.</li>
 *   <li><b>From then on.</b> The column and its CHECK are gone, a line without a carton is refused by the NOT NULL,
 *       the ledger is append-only again, and no function is left reading the dropped column (Postgres does not track
 *       what a function body mentions).</li>
 * </ul>
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the
 * migration on rows the old shape held, not how the application made them. The loose line needs the step 2 CHECK out
 * of its way for the insert only - it is NOT VALID, which judges every new row - so it is dropped and put back as it
 * was, leaving the database as one that carried such a line through step 2.</p>
 */
class LedgerLineIsACartonMigrationUpgradeTest {

    /** The last migration before the one under test. */
    private static final String BEFORE = "20261231000300";
    private static final String MIGRATION = "20261231000400";

    private static final String CARTON_50 = "00000000-0000-4000-8000-0000000c0041";
    private static final String CARTON_1000 = "00000000-0000-4000-8000-0000000c0042";
    private static final String ODD_LINE = "00000000-0000-4000-8000-0000000d0041";

    @Test
    void theQuantityGoesAndEveryLineIsItsCarton() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                // ---- a line of part of a carton stops the migration, by name
                assertThatThrownBy(() -> flyway(postgres, MIGRATION).migrate())
                        .as("a line of 7 that names a carton of 50 cannot be said without a quantity")
                        .hasMessageContaining(ODD_LINE)
                        .hasMessageContaining("of 7 names carton UPG-Q-41 of 50");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_movement WHERE sku = 'SKU-UPG-Q'"))
                        .as("the refusal rolled the whole migration back: the loose line is still there").isEqualTo(6);
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "DELETE FROM inventory.stock_movement WHERE id = ?::uuid", ODD_LINE);
                RawSql.update(db, "SET session_replication_role = origin");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // ---- the lines that name a carton stay, each for its carton's count; the loose one is gone
                assertThat(RawSql.texts(db, "SELECT m.reason || ':' || p.lpn || ':' || p.nominal_qty "
                        + "FROM inventory.stock_movement m JOIN inventory.stock_package p ON p.id = m.stock_package_id "
                        + "WHERE m.sku = 'SKU-UPG-Q' ORDER BY m.reason"))
                        .containsExactly("1 receipt:UPG-Q-41:50", "2 putaway:UPG-Q-41:50", "3 pick:UPG-Q-41:50",
                                "4 receipt:UPG-Q-42:1000");
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_movement WHERE sku = 'SKU-UPG-Q'"))
                        .as("the line that named no carton is deleted, not converted").isEqualTo(4);

                // ---- the column, its CHECK and the rule of new lines only are gone; the carton is required
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_movement' AND column_name = 'quantity'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conname IN "
                        + "('ck_stock_movement_qty', 'ck_stock_movement_names_carton')")).isZero();
                assertThat(RawSql.text(db, "SELECT attnotnull::text FROM pg_attribute WHERE attrelid = "
                        + "'inventory.stock_movement'::regclass AND attname = 'stock_package_id'")).isEqualTo("true");
                assertThat(RawSql.text(db, "SELECT indexdef FROM pg_indexes WHERE schemaname = 'inventory' AND "
                        + "indexname = 'ix_stock_movement_package'"))
                        .as("a carton's history: every line has a carton, so the index is whole")
                        .contains("(stock_package_id, occurred_at)").doesNotContain("WHERE");

                // ---- nothing live reads the dropped column, and the ledger is append-only again
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = "
                        + "p.pronamespace WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND p.prosrc ~* "
                        + "'stock_movement' AND p.prosrc ~* '\\m(m|sm|mv)\\.quantity\\M'"))
                        .as("no function still reads stock_movement.quantity").isZero();
                assertThat(RawSql.text(db, "SELECT tgenabled::text FROM pg_trigger WHERE tgname = "
                        + "'tg_stock_movement_append_only'")).as("the delete of history left the guard on")
                        .isEqualTo("O");
                assertThatThrownBy(() -> RawSql.update(db, "DELETE FROM inventory.stock_movement WHERE sku = "
                        + "'SKU-UPG-Q'")).isInstanceOf(SQLException.class);

                // ---- from then on (triggers off: the column's own rule is what is asked)
                RawSql.update(db, "SET session_replication_role = replica");
                String line = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, "
                        + "reference_type, reference_id, occurred_at, stock_package_id) VALUES (gen_random_uuid(), "
                        + "'PICK', 'SKU-UPG-Q', 'HCM-A01-1-A', 'PICK_LINE', gen_random_uuid(), NOW(), %s)";
                RawSql.refusedBy(db, "null value in column \"stock_package_id\"", "a new line that names no carton",
                        line.formatted("NULL"));
                RawSql.update(db, line.formatted("?::uuid"), CARTON_1000);
                RawSql.update(db, "SET session_replication_role = origin");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        carton(db, CARTON_50, "UPG-Q-41", 50);
        carton(db, CARTON_1000, "UPG-Q-42", 1000);
        String line = "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, "
                + "to_location_code, quantity, reference_type, reference_id, reason, occurred_at, stock_package_id) "
                + "VALUES (COALESCE(?::uuid, gen_random_uuid()), ?, 'SKU-UPG-Q', ?, ?, ?, ?, gen_random_uuid(), ?, "
                + "NOW(), ?::uuid)";
        // A carton's life, every line its standard count.
        RawSql.update(db, line, null, "RECEIPT", null, "HCM-RCV01", 50, "GOODS_RECEIPT_LINE", "1 receipt", CARTON_50);
        RawSql.update(db, line, null, "MOVE", "HCM-RCV01", "HCM-A01-1-A", 50, "PUTAWAY_TASK", "2 putaway", CARTON_50);
        RawSql.update(db, line, null, "PICK", "HCM-A01-1-A", null, 50, "PICK_LINE", "3 pick", CARTON_50);
        RawSql.update(db, line, null, "RECEIPT", null, "HCM-RCV01", 1000, "GOODS_RECEIPT_LINE", "4 receipt",
                CARTON_1000);
        // Part of a carton: what the migration must not call whole.
        RawSql.update(db, line, ODD_LINE, "MOVE", "HCM-A01-1-A", "HCM-A01-1-B", 7, "RETURN_REQUEST_LINE", "5 odd",
                CARTON_50);
        // Loose units from before everything was boxed: no carton. The CHECK of step 2 is NOT VALID, which still
        // judges a new row, so it steps aside for this one insert and comes back exactly as it was.
        RawSql.update(db, "ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_names_carton");
        RawSql.update(db, line, null, "MOVE", "HCM-A01-1-A", "HCM-A01-1-B", 3, "RETURN_REQUEST_LINE", "6 loose", null);
        RawSql.update(db, "ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_names_carton "
                + "CHECK (stock_package_id IS NOT NULL) NOT VALID");
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static void carton(Connection db, String id, String lpn, int units) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                + "nominal_qty, counted_at, location_code, status) VALUES (?::uuid, ?, gen_random_uuid(), "
                + "gen_random_uuid(), ?, NOW(), 'HCM-A01-1-A', 'IN_STOCK')", id, lpn, units);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
