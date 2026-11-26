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
 * V20261231000500 on the real upgrade path (ADR-0011, step 3c): reservations written while a row counted units end as
 * cartons of one count.
 *
 * <ul>
 *   <li><b>Nothing live is guessed.</b> A HELD row on a carton that holds part of it, a HELD SKU-level hold that is not
 *       a whole number of cartons, and one whose carton count nobody can tell each stop the migration, by name.</li>
 *   <li><b>Where the count comes from.</b> A row on a stock row is its carton; a SKU-level hold takes the count of its
 *       order line, else of the cartons its allocations took, else of the SKU's active packing unit.</li>
 *   <li><b>History is converted by rule.</b> A closed hold that is not whole cartons is rounded up; one with no count to
 *       resolve is one carton of its own quantity; a closed row on a carton is that carton.</li>
 *   <li><b>Deadlines.</b> A live hold that still carried one is closed the way V20261117000300 closed them, its units
 *       given back to the stock row, before the column goes.</li>
 *   <li><b>From then on.</b> The two columns, their CHECKs and their index are gone, the new columns are required, an
 *       allocation of two cartons is refused, every trigger of the table is back on, and no function is left reading
 *       the dropped columns (Postgres does not track what a function body mentions).</li>
 * </ul>
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the
 * migration on rows the old shape held, not how the application made them. What the rewritten triggers refuse on a
 * consistent database is asked in {@code tools/db/qa} (15, 19, 41).</p>
 */
class ReservationsCountCartonsMigrationUpgradeTest {

    /** The last migration before the one under test. */
    private static final String BEFORE = "20261231000400";
    private static final String MIGRATION = "20261231000500";

    private static final String SKU = "SKU-UPG-R";
    private static final String SKU_UNIT_ONLY = "SKU-UPG-R2";
    private static final String SKU_UNKNOWN = "SKU-UPG-R3";

    private static final String ROW_1000_A = "00000000-0000-4000-8000-0000000a0051";
    private static final String ROW_1000_B = "00000000-0000-4000-8000-0000000a0052";
    private static final String ROW_1000_C = "00000000-0000-4000-8000-0000000a0053";
    private static final String LINE_OF_1000 = "00000000-0000-4000-8000-0000000b0051";

    private static final String ALLOCATION = "00000000-0000-4000-8000-0000000e0001";
    private static final String CLOSED_PART_OF_A_CARTON = "00000000-0000-4000-8000-0000000e0002";
    private static final String HOLD_OF_ITS_LINE = "00000000-0000-4000-8000-0000000e0003";
    private static final String ALLOCATED_HOLD = "00000000-0000-4000-8000-0000000e0004";
    private static final String HOLD_OF_THE_PACKING_UNIT = "00000000-0000-4000-8000-0000000e0005";
    private static final String CLOSED_NOT_WHOLE = "00000000-0000-4000-8000-0000000e0006";
    private static final String CLOSED_UNKNOWN_COUNT = "00000000-0000-4000-8000-0000000e0007";
    private static final String DEADLINE_PAST_ON_A_ROW = "00000000-0000-4000-8000-0000000e0008";
    private static final String DEADLINE_AHEAD = "00000000-0000-4000-8000-0000000e0009";
    private static final String LIVE_PART_OF_A_CARTON = "00000000-0000-4000-8000-0000000f0001";
    private static final String LIVE_NOT_WHOLE = "00000000-0000-4000-8000-0000000f0002";
    private static final String LIVE_UNKNOWN_COUNT = "00000000-0000-4000-8000-0000000f0003";

    @Test
    void reservationsCountCartonsOfOneCount() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                // ---- a live hold of part of a carton stops the migration, by name
                assertThatThrownBy(() -> flyway(postgres, MIGRATION).migrate())
                        .as("400 held of a carton of 1000 is not a carton")
                        .hasMessageContaining(LIVE_PART_OF_A_CARTON + " of 400 on carton UPG-R-52 of 1000")
                        .hasMessageNotContaining(CLOSED_PART_OF_A_CARTON);
                release(db, LIVE_PART_OF_A_CARTON);

                // ---- so do a live SKU-level hold that is not whole cartons, and one of no known count
                assertThatThrownBy(() -> flyway(postgres, MIGRATION).migrate())
                        .as("2500 in cartons of 1000, and a hold nobody can tell the carton of")
                        .hasMessageContaining(LIVE_NOT_WHOLE + " of 2500 " + SKU + " at HCM in cartons of 1000")
                        .hasMessageContaining(LIVE_UNKNOWN_COUNT + " of 70 " + SKU_UNKNOWN
                                + " at HCM, whose carton count is not known")
                        .hasMessageNotContaining(CLOSED_NOT_WHOLE);
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_reservation' AND column_name IN ('quantity', "
                        + "'expires_at', 'cartons')")).as("the refusal rolled the whole migration back").isEqualTo(2);
                assertThat(RawSql.text(db, "SELECT status FROM inventory.stock_reservation WHERE id = ?::uuid",
                        DEADLINE_AHEAD)).as("the deadlines too: nothing was closed yet").isEqualTo("HELD");
                release(db, LIVE_NOT_WHOLE);
                release(db, LIVE_UNKNOWN_COUNT);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // ---- every row is cartons of one count
                assertThat(shape(db, ALLOCATION)).as("an allocation is its carton").isEqualTo("CONSUMED:1x1000");
                assertThat(shape(db, CLOSED_PART_OF_A_CARTON)).as("history on a carton is that carton, by rule")
                        .isEqualTo("RELEASED:1x1000");
                assertThat(shape(db, HOLD_OF_ITS_LINE)).as("the count of the order line it is held for")
                        .isEqualTo("HELD:3x1000");
                assertThat(shape(db, ALLOCATED_HOLD)).as("no line keeps an ALLOCATED hold: the count of the cartons "
                        + "its allocations took").isEqualTo("ALLOCATED:2x1000");
                assertThat(shape(db, HOLD_OF_THE_PACKING_UNIT)).as("no line, no allocation: the SKU's active packing "
                        + "unit").isEqualTo("HELD:3x500");
                assertThat(shape(db, CLOSED_NOT_WHOLE)).as("history that is not whole cartons is rounded up")
                        .isEqualTo("RELEASED:3x1000");
                assertThat(shape(db, CLOSED_UNKNOWN_COUNT)).as("history of no known count: one carton of its quantity")
                        .isEqualTo("RELEASED:1x70");
                assertThat(shape(db, LIVE_NOT_WHOLE)).as("released by hand before the run: history, rounded up")
                        .isEqualTo("RELEASED:3x1000");

                // ---- a live hold that carried a deadline is closed as V20261117000300 closed them
                assertThat(RawSql.text(db, "SELECT status || ':' || release_reason FROM inventory.stock_reservation "
                        + "WHERE id = ?::uuid", DEADLINE_PAST_ON_A_ROW)).isEqualTo("EXPIRED:RESERVATION_EXPIRED");
                assertThat(RawSql.number(db, "SELECT reserved FROM inventory.stock_item WHERE id = ?::uuid", ROW_1000_C))
                        .as("its units went back to the stock row").isZero();
                assertThat(RawSql.text(db, "SELECT status || ':' || release_reason FROM inventory.stock_reservation "
                        + "WHERE id = ?::uuid", DEADLINE_AHEAD)).isEqualTo("RELEASED:MANUAL_OVERRIDE");

                // ---- the old shape is gone, the new one required
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_reservation' AND column_name IN ('quantity', "
                        + "'expires_at')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_attribute WHERE attrelid = "
                        + "'inventory.stock_reservation'::regclass AND attname IN ('cartons', 'units_per_carton') "
                        + "AND attnotnull")).isEqualTo(2);
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conname IN "
                        + "('ck_stock_reservation_quantity', 'ck_stock_reservation_allocation', "
                        + "'ck_stock_reservation_expired_had_deadline')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'inventory' AND "
                        + "indexname = 'ix_stock_reservation_expiry'")).isZero();

                // ---- nothing live reads the dropped columns, and every trigger of the table is back on
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = "
                        + "p.pronamespace WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND (p.prosrc ~* "
                        + "'stock_reservation' OR p.proname IN ('check_sku_reservation_covered', "
                        + "'check_allocation_whole_carton')) AND (p.prosrc ~* 'expires_at' OR p.prosrc ~* "
                        + "'\\m(r|a|x|h|o|parent|new|old)\\.quantity\\M' OR p.prosrc ~* 'sum\\(quantity\\)')"))
                        .as("no function still reads a reservation's quantity or deadline").isZero();
                assertThat(RawSql.texts(db, "SELECT tgname || ':' || tgenabled::text FROM pg_trigger WHERE tgrelid = "
                        + "'inventory.stock_reservation'::regclass AND NOT tgisinternal ORDER BY tgname"))
                        .as("the backfill's DISABLE TRIGGER USER left none off, and the two rewritten ones are there")
                        .containsExactly("tg_stock_reservation_order_number:O", "tg_stock_reservation_sku:O",
                                "tg_stock_reservation_soft_atp_insert:O", "tg_stock_reservation_soft_atp_update:O",
                                "tg_stock_reservation_warehouse:O", "tg_stock_reservation_whole_carton:O");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_trigger t WHERE t.tgrelid = "
                        + "'inventory.stock_reservation'::regclass AND NOT t.tgisinternal AND pg_get_triggerdef(t.oid) "
                        + "~* '(quantity|expires_at)'")).isZero();

                // ---- from then on (triggers off: the columns' own rules are what is asked)
                RawSql.update(db, "SET session_replication_role = replica");
                String row = "INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, "
                        + "request_id, cartons, units_per_carton, reserved_at, status, sku) VALUES (gen_random_uuid(), "
                        + "%s, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), %s, %s, NOW(), 'HELD', '"
                        + SKU + "')";
                RawSql.refusedBy(db, "ck_stock_reservation_cartons", "a hold of no carton",
                        row.formatted("NULL", "0", "1000"));
                RawSql.refusedBy(db, "ck_stock_reservation_units_per_carton", "a hold of cartons of nothing",
                        row.formatted("NULL", "1", "0"));
                RawSql.refusedBy(db, "null value in column \"units_per_carton\"", "a hold that names no count",
                        row.formatted("NULL", "1", "NULL"));
                RawSql.refusedBy(db, "ck_stock_reservation_one_carton", "two cartons on one carton's row",
                        row.formatted("'" + ROW_1000_A + "'::uuid", "2", "1000"));
                RawSql.update(db, row.formatted("NULL", "40", "1000"));
                RawSql.update(db, row.formatted("'" + ROW_1000_A + "'::uuid", "1", "1000"));
                RawSql.update(db, "SET session_replication_role = origin");
            }
        }
    }

    private static String shape(Connection db, String id) throws SQLException {
        return RawSql.text(db, "SELECT status || ':' || cartons || 'x' || units_per_carton FROM "
                + "inventory.stock_reservation WHERE id = ?::uuid", id);
    }

    /** What an operator does about a hold the migration named: releases it. */
    private static void release(Connection db, String id) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = "
                + "'MANUAL_OVERRIDE', closed_at = NOW() WHERE id = ?::uuid", id);
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        carton(db, ROW_1000_A, "UPG-R-51", 1000, 0);
        carton(db, ROW_1000_B, "UPG-R-52", 1000, 400);
        carton(db, ROW_1000_C, "UPG-R-53", 1000, 1000);
        // An order line quoted in cartons of 1000, and a SKU whose only word on its cartons is its packing unit.
        RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, "
                + "uom_conversion_id, cartons, units_per_carton) VALUES (?::uuid, gen_random_uuid(), ?, 6000, 1, 'VND', "
                + "gen_random_uuid(), 6, 1000)", LINE_OF_1000, SKU);
        RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku) VALUES "
                + "('00000000-0000-4000-8000-0000000c0051'::uuid, ?)", SKU_UNIT_ONLY);
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "active) VALUES (gen_random_uuid(), '00000000-0000-4000-8000-0000000c0051'::uuid, 'CTN500', 500, "
                + "TRUE), (gen_random_uuid(), '00000000-0000-4000-8000-0000000c0051'::uuid, 'CTN900', 900, FALSE)");

        // On a stock row.
        onRow(db, ALLOCATION, ROW_1000_A, 1000, "CONSUMED", ALLOCATED_HOLD, null);
        onRow(db, CLOSED_PART_OF_A_CARTON, ROW_1000_A, 400, "RELEASED", null, null);
        onRow(db, LIVE_PART_OF_A_CARTON, ROW_1000_B, 400, "HELD", null, null);
        onRow(db, DEADLINE_PAST_ON_A_ROW, ROW_1000_C, 1000, "HELD", null, "NOW() - INTERVAL '1 hour'");
        // SKU-level.
        skuLevel(db, HOLD_OF_ITS_LINE, SKU, 3000, "HELD", null);
        skuLevel(db, ALLOCATED_HOLD, SKU, 2000, "ALLOCATED", null);
        skuLevel(db, HOLD_OF_THE_PACKING_UNIT, SKU_UNIT_ONLY, 1500, "HELD", null);
        skuLevel(db, CLOSED_NOT_WHOLE, SKU, 2500, "RELEASED", null);
        skuLevel(db, CLOSED_UNKNOWN_COUNT, SKU_UNKNOWN, 70, "RELEASED", null);
        skuLevel(db, DEADLINE_AHEAD, SKU_UNIT_ONLY, 500, "HELD", "NOW() + INTERVAL '1 hour'");
        skuLevel(db, LIVE_NOT_WHOLE, SKU, 2500, "HELD", null);
        skuLevel(db, LIVE_UNKNOWN_COUNT, SKU_UNKNOWN, 70, "HELD", null);
        for (String held : new String[] {HOLD_OF_ITS_LINE, CLOSED_NOT_WHOLE, LIVE_NOT_WHOLE}) {
            RawSql.update(db, "INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES "
                    + "(?::uuid, ?::uuid)", LINE_OF_1000, held);
        }
        RawSql.update(db, "SET session_replication_role = origin");
    }

    /** A carton of {@code units} in stock with its row, {@code reserved} of it held. */
    private static void carton(Connection db, String rowId, String lpn, int units, int reserved) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                + "nominal_qty, counted_at, location_code, status) VALUES (md5(?)::uuid, ?, gen_random_uuid(), "
                + "gen_random_uuid(), ?, NOW(), 'HCM-A01-1-A', 'IN_STOCK')", lpn, lpn, units);
        RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, "
                + "status, created_at, lot_id, stock_package_id) VALUES (?::uuid, ?, 'HCM-A01-1-A', 'LOT-UPG', ?, ?, "
                + "'AVAILABLE', NOW(), gen_random_uuid(), md5(?)::uuid)", rowId, SKU, units, reserved, lpn);
    }

    private static void onRow(Connection db, String id, String rowId, int quantity, String status, String from,
                              String deadline) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, "
                + "request_id, quantity, reserved_at, expires_at, status, closed_at, sku, allocated_from_id) VALUES "
                + "(?::uuid, ?::uuid, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), ?, NOW(), "
                + (deadline == null ? "NULL" : deadline) + ", ?, " + ("HELD".equals(status) ? "NULL" : "NOW()")
                + ", ?, ?::uuid)", id, rowId, quantity, status, SKU, from);
    }

    private static void skuLevel(Connection db, String id, String sku, int quantity, String status, String deadline)
            throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, order_id, root_request_id, request_id, "
                + "quantity, reserved_at, expires_at, status, closed_at, sku, warehouse_code) VALUES (?::uuid, "
                + "gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), ?, NOW(), "
                + (deadline == null ? "NULL" : deadline) + ", ?, " + ("HELD".equals(status) ? "NULL" : "NOW()")
                + ", ?, 'HCM')", id, quantity, status, sku);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
