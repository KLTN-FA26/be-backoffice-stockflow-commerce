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
 * V20261230000600 on the real upgrade path (ADR-0011, step 3d): cartons written while each had a stock row beside it
 * end as the stock themselves.
 *
 * <ul>
 *   <li><b>Nothing live is guessed.</b> A carton that has been in stock with no row (so no lot), a live hold on loose
 *       stock, a second live hold on one carton, a live hold on stock that is not AVAILABLE, and a closed hold on loose
 *       stock that something still references each stop the migration, by name.</li>
 *   <li><b>The carton takes its row's state.</b> The stock status only while it is in stock - the stale status the
 *       empty row of a CONSUMED or LOST carton carried is not kept - the lot whatever its status, and the FIFO date as
 *       it was: an undated carton stays undated.</li>
 *   <li><b>No allocation is lost.</b> {@code fk_reservation_stock_item} was ON DELETE CASCADE: every reservation on a
 *       carton's row is repointed to the carton before the table goes, and what references it still does. Only the
 *       closed history of loose rows is deleted, with those rows.</li>
 *   <li><b>From then on.</b> The table, the view and the column are gone, no function reads them, every trigger of
 *       the reservations is on, and the new rules refuse what they should.</li>
 * </ul>
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the
 * migration on rows the old shape held, not how the application made them. What the new triggers refuse on a
 * consistent database is asked in {@code tools/db/qa/82_carton_is_the_stock.sql}.</p>
 */
class CartonIsTheStockMigrationUpgradeTest {

    /** The last migration before the one under test. */
    private static final String BEFORE = "20261230000500";
    private static final String MIGRATION = "20261230000600";

    private static final String SKU = "SKU-UPG-D";
    private static final String LOT = "00000000-0000-4000-8000-0000000d0001";

    private static final String HELD_CARTON = "UPG-D-HELD";
    private static final String CONSUMED_CARTON = "UPG-D-CONSUMED";
    private static final String UNDATED_CARTON = "UPG-D-UNDATED";
    private static final String LOST_CARTON = "UPG-D-LOST";
    private static final String RECEIVING_CARTON = "UPG-D-RECEIVING";
    private static final String CARTON_WITHOUT_ROW = "UPG-D-NOROW";
    private static final String LOOSE_ROW = "00000000-0000-4000-8000-0000000d0099";

    private static final String ALLOCATION = "00000000-0000-4000-8000-0000000d0101";
    private static final String PICKED = "00000000-0000-4000-8000-0000000d0102";
    private static final String LOOSE_HISTORY = "00000000-0000-4000-8000-0000000d0103";
    private static final String LOOSE_HISTORY_REFERENCED = "00000000-0000-4000-8000-0000000d0104";
    private static final String LIVE_ON_LOOSE = "00000000-0000-4000-8000-0000000d0201";
    private static final String SECOND_LIVE = "00000000-0000-4000-8000-0000000d0202";
    private static final String LIVE_ON_QUARANTINE = "00000000-0000-4000-8000-0000000d0203";
    private static final String ORDER_LINE = "00000000-0000-4000-8000-0000000d0301";

    @Test
    void theCartonTakesItsRowsStateAndEveryAllocation() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                // ---- a carton that has been in stock with no row has no lot to take: named, nothing changed
                assertThatThrownBy(() -> flyway(postgres, MIGRATION).migrate())
                        .as("nobody can tell its lot")
                        .hasMessageContaining(CARTON_WITHOUT_ROW + " (IN_STOCK)")
                        .hasMessageNotContaining(RECEIVING_CARTON);
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_package' AND column_name = 'stock_status'"))
                        .as("the refusal rolled the whole migration back").isZero();
                replica(db, "UPDATE inventory.stock_package SET status = 'VOID', closed_at = NOW() WHERE lpn = '"
                        + CARTON_WITHOUT_ROW + "'");

                // ---- live holds that are not the one allocation of an AVAILABLE carton in stock, each by name
                assertThatThrownBy(() -> flyway(postgres, MIGRATION).migrate())
                        .hasMessageContaining(LIVE_ON_LOOSE)
                        .hasMessageContaining("which is in no carton")
                        .hasMessageContaining(SECOND_LIVE)
                        .hasMessageContaining("carton " + HELD_CARTON + ", which another live hold names too")
                        .hasMessageContaining(LIVE_ON_QUARANTINE)
                        .hasMessageContaining("carton " + UNDATED_CARTON + ", whose stock is QUARANTINE")
                        .hasMessageNotContaining(PICKED);
                for (String hold : new String[] {LIVE_ON_LOOSE, SECOND_LIVE, LIVE_ON_QUARANTINE}) {
                    replica(db, "UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = "
                            + "'MANUAL_OVERRIDE', closed_at = NOW() WHERE id = '" + hold + "'::uuid");
                }

                // ---- loose history something still references is not deleted from under it
                assertThatThrownBy(() -> flyway(postgres, MIGRATION).migrate())
                        .hasMessageContaining(LOOSE_HISTORY_REFERENCED + " (RELEASED) on stock row " + LOOSE_ROW)
                        .hasMessageNotContaining(LOOSE_HISTORY + " ");
                replica(db, "DELETE FROM ordering.order_line_reservation WHERE reservation_id = '"
                        + LOOSE_HISTORY_REFERENCED + "'::uuid");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // ---- the carton takes its row's state
                assertThat(carton(db, HELD_CARTON)).as("in stock: its row's status, lot and FIFO date")
                        .isEqualTo("IN_STOCK:AVAILABLE:lot:2026-01-10");
                assertThat(carton(db, CONSUMED_CARTON)).as("the stale AVAILABLE of its empty row is not kept; its lot "
                        + "and date are").isEqualTo("CONSUMED:-:lot:2026-01-11");
                assertThat(carton(db, UNDATED_CARTON)).as("an undated carton stays undated: it sorts last")
                        .isEqualTo("IN_STOCK:QUARANTINE:lot:-");
                assertThat(carton(db, LOST_CARTON)).isEqualTo("LOST:-:lot:2026-01-12");
                assertThat(carton(db, RECEIVING_CARTON)).as("never in stock: nothing to take")
                        .isEqualTo("RECEIVING:-:-:-");

                // ---- no allocation is lost (fk_reservation_stock_item was ON DELETE CASCADE)
                assertThat(allocation(db, ALLOCATION)).as("the live allocation names its carton")
                        .isEqualTo("HELD:" + HELD_CARTON);
                assertThat(allocation(db, PICKED)).isEqualTo("CONSUMED:" + CONSUMED_CARTON);
                assertThat(allocation(db, SECOND_LIVE)).as("released before the run: history of its carton")
                        .isEqualTo("RELEASED:" + HELD_CARTON);
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.order_line_reservation WHERE "
                        + "reservation_id = ?::uuid", ALLOCATION)).as("what referenced it still does").isEqualTo(1);
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_reservation WHERE id IN ('"
                        + LOOSE_HISTORY + "'::uuid, '" + LOOSE_HISTORY_REFERENCED + "'::uuid, '" + LIVE_ON_LOOSE
                        + "'::uuid)")).as("closed history of loose stock went with its rows").isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_reservation"))
                        .as("everything on a carton is still there").isEqualTo(4);

                // ---- the old shape is gone, and nothing live reads it
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = "
                        + "c.relnamespace WHERE n.nspname = 'inventory' AND c.relname IN ('stock_item', "
                        + "'stock_package_balance')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_reservation' AND column_name = 'stock_item_id'"))
                        .isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = "
                        + "p.pronamespace WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND (p.prosrc ~* "
                        + "'stock_item' OR p.prosrc ~* '\\mon_hand\\M' OR p.prosrc ~* 'stock_package_balance')"))
                        .as("Postgres does not track what a function body mentions").isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_views WHERE schemaname NOT IN ('pg_catalog', "
                        + "'information_schema') AND definition ~* 'stock_item|on_hand'")).isZero();
                assertThat(RawSql.texts(db, "SELECT tgname || ':' || tgenabled::text FROM pg_trigger WHERE tgrelid = "
                        + "'inventory.stock_reservation'::regclass AND NOT tgisinternal ORDER BY tgname"))
                        .as("the backfill's DISABLE TRIGGER USER left none off, and the rewritten ones are there")
                        .containsExactly("tg_stock_reservation_carton_insert:O", "tg_stock_reservation_carton_update:O",
                                "tg_stock_reservation_order_number:O", "tg_stock_reservation_sku:O",
                                "tg_stock_reservation_soft_atp_insert:O", "tg_stock_reservation_soft_atp_update:O",
                                "tg_stock_reservation_warehouse:O");

                // ---- from then on (triggers off: the columns' own rules are what is asked)
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.refusedBy(db, "ck_stock_package_stock_status", "a carton out of stock given a stock status",
                        "UPDATE inventory.stock_package SET stock_status = 'AVAILABLE' WHERE lpn = '" + CONSUMED_CARTON
                                + "'");
                RawSql.refusedBy(db, "ck_stock_package_stock_status", "a carton in stock with no stock status",
                        "UPDATE inventory.stock_package SET stock_status = NULL WHERE lpn = '" + HELD_CARTON + "'");
                RawSql.refusedBy(db, "ck_stock_package_lot", "a carton that has been in stock losing its lot",
                        "UPDATE inventory.stock_package SET lot_id = NULL WHERE lpn = '" + LOST_CARTON + "'");
                String hold = "INSERT INTO inventory.stock_reservation (id, stock_package_id, order_id, "
                        + "root_request_id, request_id, cartons, units_per_carton, reserved_at, status, sku) VALUES "
                        + "(gen_random_uuid(), %s, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), %s, 1000, "
                        + "NOW(), '%s', '" + SKU + "')";
                String held = "(SELECT id FROM inventory.stock_package WHERE lpn = '" + HELD_CARTON + "')";
                RawSql.refusedBy(db, "uk_stock_reservation_live_carton", "a second live allocation of one carton",
                        hold.formatted(held, "1", "HELD"));
                RawSql.refusedBy(db, "ck_stock_reservation_kind", "an allocation of two cartons",
                        hold.formatted("(SELECT id FROM inventory.stock_package WHERE lpn = '" + UNDATED_CARTON + "')",
                                "2", "HELD"));
                RawSql.refusedBy(db, "ck_stock_reservation_kind", "an ALLOCATED row naming a carton",
                        "UPDATE inventory.stock_reservation SET status = 'ALLOCATED' WHERE id = '" + PICKED
                                + "'::uuid");
                RawSql.update(db, hold.formatted("NULL", "40", "HELD"));
                RawSql.update(db, "SET session_replication_role = origin");

                // With the triggers on: the lot and the FIFO date are frozen once the carton is in stock.
                RawSql.refusedBy(db, "never changed", "an undated carton dated on the shelf",
                        "UPDATE inventory.stock_package SET received_at = NOW() - INTERVAL '1 day' WHERE lpn = '"
                                + UNDATED_CARTON + "'");
            }
        }
    }

    private static String carton(Connection db, String lpn) throws SQLException {
        return RawSql.text(db, "SELECT status || ':' || coalesce(stock_status, '-') || ':' || CASE WHEN lot_id = '"
                + LOT + "'::uuid THEN 'lot' ELSE '-' END || ':' || coalesce(to_char(received_at AT TIME ZONE 'UTC', "
                + "'YYYY-MM-DD'), '-') FROM inventory.stock_package WHERE lpn = ?", lpn);
    }

    private static String allocation(Connection db, String id) throws SQLException {
        return RawSql.text(db, "SELECT r.status || ':' || p.lpn FROM inventory.stock_reservation r JOIN "
                + "inventory.stock_package p ON p.id = r.stock_package_id WHERE r.id = ?::uuid", id);
    }

    /** What an operator does about a row the migration named, written past the old shape's triggers. */
    private static void replica(Connection db, String sql) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, sql);
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at) "
                + "VALUES (?::uuid, ?, 'LOT-UPG-D', NULL, 'UNTRACED', NOW())", LOT, SKU);
        // A carton with its row and a live allocation; a picked carton whose empty row still says AVAILABLE; an
        // undated carton in quarantine; a LOST one; one still being received (no row at all); one in stock with no row.
        carton(db, HELD_CARTON, "IN_STOCK", "AVAILABLE", 1000, 1000, "'2026-01-10T00:00:00Z'");
        carton(db, CONSUMED_CARTON, "CONSUMED", "AVAILABLE", 0, 0, "'2026-01-11T00:00:00Z'");
        carton(db, UNDATED_CARTON, "IN_STOCK", "QUARANTINE", 1000, 0, "NULL");
        carton(db, LOST_CARTON, "LOST", "QUARANTINE", 0, 0, "'2026-01-12T00:00:00Z'");
        carton(db, RECEIVING_CARTON, "RECEIVING", null, 0, 0, "NULL");
        carton(db, CARTON_WITHOUT_ROW, "IN_STOCK", null, 0, 0, "NULL");
        // An empty loose row, as the baseline left them.
        RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, "
                + "status, created_at, lot_id) VALUES (?::uuid, ?, 'HCM-A01-1-A', 'LOT-UPG-D', 0, 0, 'AVAILABLE', "
                + "NOW(), ?::uuid)", LOOSE_ROW, SKU, LOT);

        onRow(db, ALLOCATION, rowOf(HELD_CARTON), "HELD");
        onRow(db, SECOND_LIVE, rowOf(HELD_CARTON), "HELD");
        onRow(db, PICKED, rowOf(CONSUMED_CARTON), "CONSUMED");
        onRow(db, LIVE_ON_QUARANTINE, rowOf(UNDATED_CARTON), "HELD");
        onRow(db, LOOSE_HISTORY, "'" + LOOSE_ROW + "'::uuid", "RELEASED");
        onRow(db, LOOSE_HISTORY_REFERENCED, "'" + LOOSE_ROW + "'::uuid", "RELEASED");
        onRow(db, LIVE_ON_LOOSE, "'" + LOOSE_ROW + "'::uuid", "HELD");
        for (String referenced : new String[] {ALLOCATION, LOOSE_HISTORY_REFERENCED}) {
            RawSql.update(db, "INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) VALUES "
                    + "(?::uuid, ?::uuid)", ORDER_LINE, referenced);
        }
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static String rowOf(String lpn) {
        return "md5('row:" + lpn + "')::uuid";
    }

    /** A carton and, with {@code rowStatus}, the stock row the old shape kept beside it. */
    private static void carton(Connection db, String lpn, String status, String rowStatus, int onHand, int reserved,
                               String receivedAt) throws SQLException {
        boolean closed = "CONSUMED".equals(status) || "LOST".equals(status);
        RawSql.update(db, "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, "
                + "nominal_qty, counted_at, location_code, status, closed_at) VALUES (md5(?)::uuid, ?, "
                + "gen_random_uuid(), gen_random_uuid(), 1000, " + ("RECEIVING".equals(status) ? "NULL" : "NOW()")
                + ", 'HCM-A01-1-A', ?, " + (closed ? "NOW()" : "NULL") + ")", lpn, lpn, status);
        if (rowStatus != null) {
            RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, "
                    + "reserved, status, created_at, received_at, lot_id, stock_package_id) VALUES (" + rowOf(lpn)
                    + ", ?, 'HCM-A01-1-A', 'LOT-UPG-D', ?, ?, ?, NOW(), " + receivedAt + ", ?::uuid, md5(?)::uuid)",
                    SKU, onHand, reserved, rowStatus, LOT, lpn);
        }
    }

    private static void onRow(Connection db, String id, String row, String status) throws SQLException {
        RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, "
                + "request_id, cartons, units_per_carton, reserved_at, status, closed_at, sku) VALUES (?::uuid, " + row
                + ", gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1, 1000, NOW(), ?, "
                + ("HELD".equals(status) ? "NULL" : "NOW()") + ", ?)", id, status, SKU);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
