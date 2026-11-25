package com.stockflow.inventory;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261230000300 on the real upgrade path (ADR-0011, step 3a): cartons written while a count was a number of units
 * end with the moment they were counted in, or none.
 *
 * <ul>
 *   <li><b>Counted.</b> A carton that carried {@code received_qty} - in stock, consumed, or still a counted draft -
 *       has {@code counted_at}, taken from its own {@code created_at} (the application counts a carton in the call
 *       that labels it); what it stands for is still {@code nominal_qty}.</li>
 *   <li><b>Not counted.</b> A carton only labelled, or voided before it was counted, keeps no count.</li>
 *   <li><b>From then on.</b> The number is gone with the two CHECKs that only constrained it, no function or view is
 *       left reading it (Postgres does not track what a function body mentions), and a carton past receiving must
 *       have been counted.</li>
 *   <li><b>Nothing merged silently.</b> A carton counted at another figure than its standard count cannot be said by a
 *       timestamp: the migration stops and names it rather than call it a whole carton.</li>
 * </ul>
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the
 * migration on rows the old shape held, not how the application made them.</p>
 */
class StockPackageCountedAtMigrationUpgradeTest {

    /** The last migration before the one under test. */
    private static final String BEFORE = "20261230000200";
    private static final String MIGRATION = "20261230000300";

    private static final String CARTON = "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, "
            + "uom_conversion_id, nominal_qty, received_qty, location_code, status, closed_at, created_at) VALUES "
            + "(gen_random_uuid(), ?, gen_random_uuid(), gen_random_uuid(), 50, ?, 'HCM-A01-1-A', ?, ?::timestamptz, "
            + "?::timestamptz)";

    @Test
    void aCountedCartonKeepsTheMomentAndTheNumberIsGone() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, CARTON, "UPG-C-1-STOCK", 50, "IN_STOCK", null, "2026-11-02T03:00:00Z");
                RawSql.update(db, CARTON, "UPG-C-2-CONSUMED", 50, "CONSUMED", "2026-11-05T03:00:00Z",
                        "2026-11-03T03:00:00Z");
                RawSql.update(db, CARTON, "UPG-C-3-DRAFT", 50, "RECEIVING", null, "2026-11-04T03:00:00Z");
                RawSql.update(db, CARTON, "UPG-C-4-LABELLED", null, "RECEIVING", null, "2026-11-04T04:00:00Z");
                RawSql.update(db, CARTON, "UPG-C-5-VOID", null, "VOID", "2026-11-04T06:00:00Z",
                        "2026-11-04T05:00:00Z");
                RawSql.update(db, "SET session_replication_role = origin");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // ---- the moment, where there was a number
                assertThat(RawSql.texts(db, "SELECT lpn || ':' || status || ':' || nominal_qty || ':' || coalesce("
                        + "to_char(counted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI'), 'not counted') || ':' || "
                        + "(counted_at IS NOT DISTINCT FROM created_at OR counted_at IS NULL) "
                        + "FROM inventory.stock_package WHERE lpn LIKE 'UPG-C-%' ORDER BY lpn"))
                        .as("counted cartons take their created_at; the two never counted take nothing")
                        .containsExactly("UPG-C-1-STOCK:IN_STOCK:50:2026-11-02T03:00:true",
                                "UPG-C-2-CONSUMED:CONSUMED:50:2026-11-03T03:00:true",
                                "UPG-C-3-DRAFT:RECEIVING:50:2026-11-04T03:00:true",
                                "UPG-C-4-LABELLED:RECEIVING:50:not counted:true",
                                "UPG-C-5-VOID:VOID:50:not counted:true");

                // ---- the number is gone, with what only constrained it and what read it
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name IN ('stock_package', 'stock_package_balance') AND column_name = "
                        + "'received_qty'")).as("dropped from the table and from the balance view").isZero();
                assertThat(RawSql.texts(db, "SELECT conname FROM pg_constraint WHERE conrelid = "
                        + "'inventory.stock_package'::regclass AND conname IN ('ck_stock_package_counted', "
                        + "'ck_stock_package_received', 'ck_stock_package_standard_count') ORDER BY conname"))
                        .as("only 'counted' is still a rule").containsExactly("ck_stock_package_counted");
                assertThat(RawSql.texts(db, "SELECT n.nspname || '.' || p.proname FROM pg_proc p JOIN pg_namespace n "
                        + "ON n.oid = p.pronamespace WHERE p.prosrc ILIKE '%received_qty%' AND n.nspname NOT IN "
                        + "('pg_catalog', 'information_schema') ORDER BY 1"))
                        .as("the functions left reading a received_qty read procurement.goods_receipt_lines', "
                                + "another column of another table")
                        .containsExactly("procurement.check_goods_receipt_line",
                                "procurement.check_goods_receipt_line_frozen",
                                "procurement.check_production_receipt_cartons");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_views WHERE definition ILIKE '%received_qty%'"))
                        .as("no view still reads it").isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_trigger WHERE tgrelid = "
                        + "'inventory.stock_package'::regclass AND NOT tgisinternal AND tgname IN "
                        + "('tg_stock_package_transition', 'tg_stock_package_rows') AND pg_get_triggerdef(oid) LIKE "
                        + "'%counted_at%'")).as("the two triggers that fired on the number fire on the moment")
                        .isEqualTo(2);
                // The fixture cartons name no real item, so the view (which joins the item) is asked for its shape.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_package_balance' AND column_name IN ('counted_at', "
                        + "'nominal_qty', 'on_hand')")).as("the balance view is back, answering the count")
                        .isEqualTo(3);

                // ---- from then on
                RawSql.refusedBy(db, "ck_stock_package_counted", "a carton only labelled going into stock",
                        "UPDATE inventory.stock_package SET status = 'IN_STOCK' WHERE lpn = 'UPG-C-4-LABELLED'");
                RawSql.refusedBy(db, "counted in only while it is being received", "a carton in stock counted again",
                        "UPDATE inventory.stock_package SET counted_at = NOW() WHERE lpn = 'UPG-C-1-STOCK'");
                RawSql.update(db, "UPDATE inventory.stock_package SET counted_at = NOW() WHERE lpn = "
                        + "'UPG-C-3-DRAFT'");
            }
        }
    }

    @Test
    void aCartonCountedOffItsStandardStopsTheMigration() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                // CONSUMED: the one place the old CHECKs still let another figure stand (history from before whole
                // cartons were the rule).
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, CARTON, "UPG-C-9-ODD", 48, "CONSUMED", "2026-11-05T03:00:00Z",
                        "2026-11-03T03:00:00Z");
                RawSql.update(db, "SET session_replication_role = origin");

                assertThatThrownBy(() -> flyway(postgres, MIGRATION).migrate())
                        .hasMessageContaining("UPG-C-9-ODD (48 of 50)");

                assertThat(RawSql.number(db, "SELECT received_qty FROM inventory.stock_package WHERE lpn = "
                        + "'UPG-C-9-ODD'")).as("nothing was changed: the migration is one transaction").isEqualTo(48);
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
