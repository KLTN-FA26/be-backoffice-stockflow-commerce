package com.stockflow.production;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.stockflow.support.RawSql.number;
import static com.stockflow.support.RawSql.text;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of PR #109, item 11: the print-run and packing-area backfills on the data a develop database really holds -
 * production orders written the way the generated develop seed writes them (printed_quantity set, PRINTING -> QC
 * history rows without a reason, scrap in production_scrap, no hand-over in the ledger). Upgraded from develop's
 * newest migration through every Sprint 2 file, then read as the workshop dashboard reads it (QA3: printed in the
 * period from production_print_run).
 */
class DevelopHistoryBackfillMigrationUpgradeTest {

    /** The newest migration on develop (e0d082e, #120): what the deployed database holds. */
    private static final String DEVELOP = "20261016000100";
    private static final String HCM = "(SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM')";
    private static final String USER = "(SELECT id FROM identity.app_user ORDER BY created_at LIMIT 1)";
    private static int salesOrders;

    @Test
    void printedHistoryAndHeldGoodsAreBackfilledAndNothingIsInvented() throws Exception {
        Instant leftPress = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, DEVELOP).migrate();
            try (Connection db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                // Completed, printed 11 (10 good, 1 failed QC), shipped since: nothing left in packing.
                UUID completed = order(db, "LSX-DV-DONE", "COMPLETED", 10, 11, 10, leftPress);
                update(db, "INSERT INTO production.production_scrap (id, production_order_id, quantity, reason, note,"
                        + " recorded_at, recorded_by) VALUES (gen_random_uuid(), ?, 1, 'QC_FAILED', 'Lech mau', ?, "
                        + USER + ")", completed, Timestamp.from(leftPress.plus(1, ChronoUnit.HOURS)));
                // Printed 5, waiting for QC.
                UUID inQc = order(db, "LSX-DV-QC", "QC", 5, 5, 5, leftPress.minus(1, ChronoUnit.DAYS));
                // Completed and its goods still held for the line in HCM-PACK01.
                UUID packed = order(db, "LSX-DV-PACK", "COMPLETED", 4, 4, 4, leftPress);
                holdInPacking(db, packed, 4);
                // Printed but no PRINTING -> QC row at all: no time to put a run at.
                UUID noHistory = order(db, "LSX-DV-NOH", "QC", 3, 3, 3, null);

                flyway(pg, null).migrate();

                assertThat(run(db, completed)).as("printed 11 when it left the press").isEqualTo("11/0@" + leftPress);
                assertThat(run(db, inQc)).isEqualTo("5/0@" + leftPress.minus(1, ChronoUnit.DAYS));
                assertThat(number(db, "SELECT count(*) FROM production.production_print_run WHERE production_order_id = ?",
                        noHistory)).as("nothing invented without a time").isZero();

                // The dashboard (QA3) now sees what was printed in the period, and the scrap once.
                Timestamp from = Timestamp.from(leftPress.minus(2, ChronoUnit.DAYS));
                Timestamp to = Timestamp.from(leftPress.plus(1, ChronoUnit.DAYS));
                assertThat(number(db, "SELECT COALESCE(SUM(r.printed_quantity), 0) FROM production.production_print_run r"
                        + " WHERE r.recorded_at >= ? AND r.recorded_at < ? AND r.production_order_id IN (?, ?, ?)",
                        from, to, completed, inQc, packed)).isEqualTo(20);
                assertThat(number(db, "SELECT COALESCE(SUM(s.quantity), 0) FROM production.production_scrap s"
                        + " WHERE s.recorded_at >= ? AND s.recorded_at < ? AND s.production_order_id = ?",
                        from, to, completed)).isEqualTo(1);

                assertThat(text(db, "SELECT COALESCE(packing_location_code, 'none') FROM production.production_order"
                        + " WHERE id = ?", packed)).as("its goods are held there").isEqualTo("HCM-PACK01");
                assertThat(text(db, "SELECT COALESCE(packing_location_code, 'none') FROM production.production_order"
                        + " WHERE id = ?", completed)).as("shipped since: not known, not invented").isEqualTo("none");

                // Running the backfill again adds nothing: it fills only what is missing.
                long runs = number(db, "SELECT count(*) FROM production.production_print_run");
                update(db, "INSERT INTO production.production_print_run (id, production_order_id, printed_quantity,"
                        + " scrap_quantity, recorded_at, recorded_by) SELECT gen_random_uuid(), o.id, o.printed_quantity"
                        + " - (SELECT COALESCE(SUM(printed_quantity), 0) FROM production.production_print_run r"
                        + " WHERE r.production_order_id = o.id), 0, NOW(), NULL FROM production.production_order o"
                        + " WHERE o.id = ? AND o.printed_quantity > (SELECT COALESCE(SUM(printed_quantity), 0)"
                        + " FROM production.production_print_run r WHERE r.production_order_id = o.id)", completed);
                assertThat(number(db, "SELECT count(*) FROM production.production_print_run")).isEqualTo(runs);
            }
        }
    }

    private static String run(Connection db, UUID order) throws Exception {
        return text(db, "SELECT printed_quantity || '/' || scrap_quantity || '@' || to_char(recorded_at AT TIME ZONE 'UTC',"
                + " 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM production.production_print_run WHERE production_order_id = ?",
                order);
    }

    /** An in-house ORDER production order as the develop seed writes one: history row per step, no reason. */
    private static UUID order(Connection db, String number, String status, int planned, int printed, int good,
                              Instant pressedAt) throws Exception {
        UUID draft = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        UUID sales = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID production = UUID.randomUUID();
        String checksum = "d".repeat(64);
        update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)"
                + " VALUES (?, 'c0000000-0000-4000-8000-000000000001', md5('demo:product:SOFA-3S')::uuid, 'DRAFT',"
                + " NOW(), '{}'::jsonb)", draft);
        update(db, "INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,"
                + " artifact_manifest) VALUES (?, ?, ?, 'https://example.com/p.pdf', NOW(), NOW(), '[]'::jsonb)",
                snapshot, draft, checksum);
        update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,"
                + " currency, placed_at, warehouse_id, released_at, released_by, version, created_at) VALUES (?, ?,"
                + " 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'IN_PRODUCTION', 20000, 'VND', NOW(), "
                + HCM + ", NOW(), " + USER + ", 0, NOW())", sales, "SO-20991228-%06d".formatted(++salesOrders));
        update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency,"
                + " design_snapshot_id, design_checksum) VALUES (?, ?, 'SOFA-3S-GREY', ?, 2000, 'VND', ?, ?)",
                line, sales, planned, snapshot, checksum);
        boolean done = status.equals("COMPLETED");
        update(db, "INSERT INTO production.production_order (id, order_number, type, status, warehouse_id, blank_sku,"
                + " design_snapshot_id, design_checksum, planned_quantity, issued_quantity, printed_quantity,"
                + " good_quantity, scrap_quantity, sales_order_id, sales_order_line_id, prepress_checked_at,"
                + " prepress_checked_by, completed_at) VALUES (?, ?, 'ORDER', ?, " + HCM + ", 'SOFA-3S-GREY', ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, NOW(), " + USER + ", " + (done ? "NOW()" : "NULL") + ")",
                production, number, status, snapshot, checksum, planned, printed, printed, good, printed - good, sales,
                line);
        String[] path = {"PENDING_PREPRESS", "READY", "MATERIAL_ISSUED", "PRINTING", "QC", "COMPLETED"};
        String previous = null;
        for (String step : path) {
            if (pressedAt == null && step.equals("QC")) {
                break;   // a row set straight to QC with no history of it (the case that cannot be dated)
            }
            Instant at = pressedAt == null ? Instant.now().minus(10, ChronoUnit.DAYS)
                    : step.equals("QC") ? pressedAt : pressedAt.minus(5 - indexOf(path, step), ChronoUnit.HOURS);
            update(db, "INSERT INTO production.production_order_status_history (id, production_order_id, from_status,"
                    + " to_status, reason, changed_at, changed_by) VALUES (gen_random_uuid(), ?, ?, ?, NULL, ?, " + USER
                    + ")", production, previous, step, Timestamp.from(at));
            previous = step;
            if (step.equals(status)) {
                break;
            }
        }
        return production;
    }

    private static int indexOf(String[] path, String step) {
        for (int i = 0; i < path.length; i++) {
            if (path[i].equals(step)) {
                return i;
            }
        }
        return -1;
    }

    /** The order's finished goods on a layer in HCM-PACK01, held for its line, as a completion leaves them. */
    private static void holdInPacking(Connection db, UUID productionOrder, int quantity) throws Exception {
        UUID layer = UUID.randomUUID();
        UUID hold = UUID.randomUUID();
        update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version,"
                + " created_at, received_at) VALUES (?, 'SOFA-3S-GREY', 'HCM-PACK01', ?, ?, 'AVAILABLE', 0, NOW(), NOW())",
                layer, quantity, quantity);
        update(db, "INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id,"
                + " quantity, reserved_at, expires_at, status) SELECT ?, ?, sales_order_id,"
                + " gen_random_uuid(), gen_random_uuid(), ?, NOW(), NULL, 'HELD'"
                + " FROM production.production_order WHERE id = ?", hold, layer, quantity, productionOrder);
        update(db, "INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) SELECT"
                + " sales_order_line_id, ? FROM production.production_order WHERE id = ?", hold, productionOrder);
    }

    private static Flyway flyway(PostgreSQLContainer<?> pg, String target) {
        var config = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }
}
