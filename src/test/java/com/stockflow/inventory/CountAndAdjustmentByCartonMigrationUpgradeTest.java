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
 * V20261230000100 on the real upgrade path (ADR-0011): count lines and adjustments written while they still kept
 * units end as what the units said.
 *
 * <ul>
 *   <li><b>A count line.</b> {@code expected_qty} becomes {@code booked} (the books had units of it), {@code
 *       counted_qty} becomes {@code found} - and stays unsaid on a line nobody counted, with its counter.</li>
 *   <li><b>An adjustment.</b> A negative {@code quantity_delta} becomes {@code WRITE_OFF}, a positive one
 *       {@code RESTOCK}; who decided it and when is untouched.</li>
 *   <li><b>Loose history.</b> A line and an adjustment that name no carton are removed, so both tables can require
 *       one; from then on the tables refuse a row without a carton, a line without {@code booked} and a direction
 *       that is neither of the two.</li>
 * </ul>
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the
 * migration on rows the old rules accepted (a carton that no table holds, a loose line), not how the application made
 * them.</p>
 */
class CountAndAdjustmentByCartonMigrationUpgradeTest {

    /** The last migration before the one under test. */
    private static final String BEFORE = "20261229000100";
    private static final String MIGRATION = "20261230000100";

    private static final String USER = "00000000-0000-4000-8000-0000000b0001";
    private static final String APPROVER = "00000000-0000-4000-8000-0000000b0002";
    private static final String COUNT = "00000000-0000-4000-8000-0000000b0010";
    private static final String LINE_NOT_FOUND = "00000000-0000-4000-8000-0000000b0011";
    private static final String LINE_FOUND_UNBOOKED = "00000000-0000-4000-8000-0000000b0012";
    private static final String LINE_UNCOUNTED = "00000000-0000-4000-8000-0000000b0013";
    private static final String LINE_LOOSE = "00000000-0000-4000-8000-0000000b0014";
    private static final String LINE_LOOSE_ADJUSTED = "00000000-0000-4000-8000-0000000b0015";
    private static final String CARTON_1 = "00000000-0000-4000-8000-0000000b0021";
    private static final String CARTON_2 = "00000000-0000-4000-8000-0000000b0022";
    private static final String CARTON_3 = "00000000-0000-4000-8000-0000000b0023";

    @Test
    void unitsBecomeAnswersAndLooseHistoryGoes() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // ---- count lines: two answers, no figure
                assertThat(RawSql.texts(db, "SELECT right(id::text, 2) || ':' || booked || ':' || "
                        + "coalesce(found::text, 'not counted') || ':' || (counted_by IS NOT NULL) "
                        + "FROM inventory.cycle_count_line ORDER BY id"))
                        .as("booked from expected_qty > 0, found from counted_qty > 0; an uncounted line says nothing; "
                                + "the two loose lines are gone")
                        .containsExactly("11:true:false:true", "12:false:true:true", "13:true:not counted:false");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name IN ('cycle_count_line', 'stock_adjustment') AND column_name IN "
                        + "('expected_qty', 'counted_qty', 'quantity_delta')")).as("the unit columns are dropped")
                        .isZero();

                // ---- adjustments: a direction, the decision untouched
                assertThat(RawSql.texts(db, "SELECT adjustment_number || ':' || direction || ':' || status || ':' || "
                        + "(decided_by IS NOT NULL) FROM inventory.stock_adjustment ORDER BY adjustment_number"))
                        .as("negative is WRITE_OFF, positive RESTOCK; the loose adjustment is gone")
                        .containsExactly("ADJ-UPG-C-1:WRITE_OFF:POSTED:true", "ADJ-UPG-C-2:RESTOCK:PENDING_APPROVAL:false",
                                "ADJ-UPG-C-3:WRITE_OFF:PENDING_APPROVAL:false");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conrelid IN "
                        + "('inventory.cycle_count_line'::regclass, 'inventory.stock_adjustment'::regclass) "
                        + "AND convalidated = false")).as("every constraint validated on the rows that stayed").isZero();

                // ---- from then on (triggers off: the columns and CHECKs themselves are what is asked)
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.refusedBy(db, "\"stock_package_id\"", "a count line of no carton", "INSERT INTO "
                        + "inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked) VALUES "
                        + "(gen_random_uuid(), ?::uuid, 'HCM-A01-1-A', 'SKU-UPG-C', TRUE)", COUNT);
                RawSql.refusedBy(db, "\"booked\"", "a count line that does not say booked", "INSERT INTO "
                        + "inventory.cycle_count_line (id, cycle_count_id, location_code, sku, stock_package_id) VALUES "
                        + "(gen_random_uuid(), ?::uuid, 'HCM-A01-1-A', 'SKU-UPG-C', gen_random_uuid())", COUNT);
                RawSql.refusedBy(db, "ck_cycle_count_line_counted", "an answer with no counter", "UPDATE "
                        + "inventory.cycle_count_line SET found = TRUE WHERE id = ?::uuid", LINE_UNCOUNTED);
                String adjustment = "INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, "
                        + "direction, reason_code, requested_by, stock_package_id) VALUES (gen_random_uuid(), ?, "
                        + "'HCM-A01-1-A', 'SKU-UPG-C', %s, %s, ?::uuid, %s)";
                RawSql.refusedBy(db, "\"stock_package_id\"", "an adjustment of no carton",
                        adjustment.formatted("'WRITE_OFF'", "'DAMAGED'", "NULL"), "ADJ-UPG-C-7", USER);
                RawSql.refusedBy(db, "\"direction\"", "an adjustment with no direction",
                        adjustment.formatted("NULL", "'DAMAGED'", "gen_random_uuid()"), "ADJ-UPG-C-8", USER);
                RawSql.refusedBy(db, "ck_stock_adjustment_direction", "units where the direction goes",
                        adjustment.formatted("'-50'", "'DAMAGED'", "gen_random_uuid()"), "ADJ-UPG-C-9", USER);
                RawSql.refusedBy(db, "ck_stock_adjustment_writes_down", "a SCRAP that restocks",
                        adjustment.formatted("'RESTOCK'", "'SCRAP'", "gen_random_uuid()"), "ADJ-UPG-C-10", USER);
                RawSql.update(db, "SET session_replication_role = origin");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status) "
                + "VALUES (?::uuid, 'CC-UPG-C', gen_random_uuid(), 'MANUAL', 'PLANNED')", COUNT);
        String line = "INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, lot_number, "
                + "expected_qty, counted_qty, counted_by, counted_at, stock_package_id) VALUES (?::uuid, ?::uuid, "
                + "'HCM-A01-1-A', 'SKU-UPG-C', ?, %s, %s)";
        String counted = "?::uuid, NOW()";
        // Booked, not found: a shortage of the whole carton.
        RawSql.update(db, line.formatted("50, 0, " + counted, "?::uuid"), LINE_NOT_FOUND, COUNT, "LOT-1", USER,
                CARTON_1);
        // Not booked (a LOST carton), found: a surplus of the whole carton.
        RawSql.update(db, line.formatted("0, 50, " + counted, "?::uuid"), LINE_FOUND_UNBOOKED, COUNT, "LOT-2", USER,
                CARTON_2);
        // Planned, nobody counted it yet.
        RawSql.update(db, line.formatted("50, NULL, NULL, NULL", "?::uuid"), LINE_UNCOUNTED, COUNT, "LOT-3",
                CARTON_3);
        // Loose lines of before counts were by carton: one nothing refers to, one with its loose adjustment.
        RawSql.update(db, line.formatted("10, 9, " + counted, "NULL"), LINE_LOOSE, COUNT, "LOT-4", USER);
        RawSql.update(db, line.formatted("10, 7, " + counted, "NULL"), LINE_LOOSE_ADJUSTED, COUNT, "LOT-5", USER);

        String adjustment = "INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, "
                + "quantity_delta, reason_code, status, cycle_count_line_id, requested_by, decided_by, decided_at, "
                + "posted_at, stock_package_id) VALUES (gen_random_uuid(), ?, 'HCM-A01-1-A', 'SKU-UPG-C', ?, ?, ?, "
                + "%s, ?::uuid, %s, %s)";
        RawSql.update(db, adjustment.formatted("?::uuid", "?::uuid, NOW(), NOW()", "?::uuid"), "ADJ-UPG-C-1", -50,
                "COUNT_VARIANCE", "POSTED", LINE_NOT_FOUND, USER, APPROVER, CARTON_1);
        RawSql.update(db, adjustment.formatted("?::uuid", "NULL, NULL, NULL", "?::uuid"), "ADJ-UPG-C-2", 50,
                "COUNT_VARIANCE", "PENDING_APPROVAL", LINE_FOUND_UNBOOKED, USER, CARTON_2);
        RawSql.update(db, adjustment.formatted("NULL", "NULL, NULL, NULL", "?::uuid"), "ADJ-UPG-C-3", -50, "DAMAGED",
                "PENDING_APPROVAL", USER, CARTON_3);
        RawSql.update(db, adjustment.formatted("?::uuid", "NULL, NULL, NULL", "NULL"), "ADJ-UPG-C-4", -3,
                "COUNT_VARIANCE", "PENDING_APPROVAL", LINE_LOOSE_ADJUSTED, USER);
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
