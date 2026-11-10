package com.stockflow.fulfillment;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * V20261113000700 on the real upgrade path (team plan 4C-6; kltn-docs 08 §5.1, §5.2, 17 BR-12, BR-16): the repack
 * station's PACKING and PACKED sessions become IN_PROGRESS and COMPLETED; repack parcels a session still PENDING or
 * IN_PROGRESS left half done (OPEN, or SEALED in no shipment) are VOIDED so the session goes on at the check station,
 * while a parcel already in a shipment is untouched; an order the repack station packed counts as labelled from when it
 * was packed (a cancelled one does not); and the new CHECKs and triggers hold on the migrated data. The owner's decision
 * QD-1 (2026-11-10) keeps nothing in {@code platform.legacy_archive} and stops nothing.
 *
 * <p>The old state is written with {@code session_replication_role = replica}: foreign keys and row triggers are off
 * (the orders, picks and users these rows name are not what this is about), the CHECKs are not, so every old row is
 * one the old schema accepted.</p>
 */
class CartonCheckMigrationUpgradeTest {

    private static final String BEFORE = "20261113000600";
    private static final String MIGRATION = "20261113000700";
    /** The review fixes after it: an index on package(lpn), no first label on an order on hold. */
    private static final String REVIEW_FIXES = "20261113000710";

    private static final String PACK_PACKING = id(1);
    private static final String PACK_PENDING = id(2);
    private static final String PACK_PACKED = id(3);
    private static final String PACK_CANCELLED = id(4);

    private static final String ORDER_PACKED = id(11);
    private static final String ORDER_CANCELLED_AFTER_PACKED = id(12);
    private static final String ORDER_NOT_PACKED = id(13);

    private static final String PARCEL_OPEN = id(21);
    private static final String PARCEL_SEALED_FREE = id(22);
    private static final String PARCEL_SEALED_SHIPPED = id(23);
    private static final String PARCEL_PENDING_SEALED = id(24);
    private static final String PARCEL_COMPLETED_SEALED = id(25);
    private static final String PARCEL_VOIDED_ALREADY = id(26);

    private static final String SHIPMENT = id(31);

    @Test
    @DisplayName("PACKING/PACKED sessions are renamed; half-done parcels of a live session are voided, shipped ones kept; "
            + "packed orders count as labelled; the new CHECKs refuse the old names")
    void sessionsRenamedParcelsVoidedOrdersLockedAndTheNewRulesHold() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                assertThat(RawSql.text(db, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_pack_status'"))
                        .as("before: the repack station's two steps").contains("PACKING").contains("PACKED");
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // ---- the sessions
                assertThat(RawSql.texts(db, "SELECT id::text || ':' || status FROM fulfillment.pack ORDER BY id"))
                        .containsExactly(PACK_PACKING + ":IN_PROGRESS", PACK_PENDING + ":PENDING",
                                PACK_PACKED + ":COMPLETED", PACK_CANCELLED + ":CANCELLED");
                assertThat(RawSql.text(db, "SELECT (started_at IS NOT NULL)::text || ':' || (packed_at IS NOT NULL)::text "
                        + "FROM fulfillment.pack WHERE id = ?::uuid", PACK_PACKED))
                        .as("a completed session keeps when it started and was completed").isEqualTo("true:true");
                assertThat(RawSql.text(db, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_pack_status'"))
                        .contains("IN_PROGRESS").contains("COMPLETED").doesNotContain("PACKING").doesNotContain("PACKED");
                RawSql.refused(db, "ck_pack_status refuses PACKING", "UPDATE fulfillment.pack SET status = 'PACKING' WHERE id = '"
                        + PACK_PENDING + "'::uuid");
                RawSql.refused(db, "ck_pack_status refuses PACKED", "UPDATE fulfillment.pack SET status = 'PACKED', "
                        + "started_at = NOW(), packed_at = NOW() WHERE id = '" + PACK_PENDING + "'::uuid");

                // ---- the parcels
                assertThat(RawSql.texts(db, "SELECT id::text || ':' || status || ':' || (shipment_id IS NOT NULL) "
                        + "FROM fulfillment.package ORDER BY id"))
                        .containsExactly(
                                PARCEL_OPEN + ":VOIDED:false",
                                PARCEL_SEALED_FREE + ":VOIDED:false",
                                PARCEL_SEALED_SHIPPED + ":SEALED:true",
                                PARCEL_PENDING_SEALED + ":VOIDED:false",
                                PARCEL_COMPLETED_SEALED + ":SEALED:false",
                                PARCEL_VOIDED_ALREADY + ":VOIDED:false");
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.package WHERE status = 'VOIDED' AND voided_at IS NULL"))
                        .as("a voided parcel says when").isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.package WHERE pick_line_id IS NOT NULL OR lpn IS NOT NULL"))
                        .as("no old parcel is a shipping carton").isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = 'fulfillment.package'"))
                        .as("QD-1: nothing is archived").isZero();

                // ---- the orders
                assertThat(RawSql.texts(db, "SELECT id::text || ':' || (first_labeled_at IS NOT NULL) || ':' "
                        + "|| coalesce((first_labeled_at = packed_at)::text, '-') FROM ordering.customer_order ORDER BY id"))
                        .containsExactly(ORDER_PACKED + ":true:true", ORDER_CANCELLED_AFTER_PACKED + ":false:-",
                                ORDER_NOT_PACKED + ":false:-");
                RawSql.refused(db, "ck_order_labeled_not_cancelled: a cancelled order is never labelled",
                        "UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = '" + ORDER_CANCELLED_AFTER_PACKED + "'::uuid");
                refusedWith(db, "tg_order_label_lock", "UPDATE ordering.customer_order SET shipping_line1 = 'Elsewhere' "
                        + "WHERE id = '" + ORDER_PACKED + "'::uuid");
                refusedWith(db, "tg_order_label_lock", "UPDATE ordering.customer_order SET first_labeled_at = NULL "
                        + "WHERE id = '" + ORDER_PACKED + "'::uuid");
                RawSql.update(db, "UPDATE ordering.customer_order SET shipping_line1 = 'Elsewhere' WHERE id = '"
                        + ORDER_NOT_PACKED + "'::uuid");
                RawSql.update(db, "UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = '" + ORDER_NOT_PACKED
                        + "'::uuid");
                refusedWith(db, "tg_order_label_lock", "UPDATE ordering.customer_order SET shipping_line1 = 'Again' "
                        + "WHERE id = '" + ORDER_NOT_PACKED + "'::uuid");

                // ---- the package CHECKs: a parcel stays what it was, a carton row is the new kind
                RawSql.refused(db, "ck_package_status refuses an unknown status", "UPDATE fulfillment.package SET status = 'BOXED' "
                        + "WHERE id = '" + PARCEL_OPEN + "'::uuid");
                RawSql.refused(db, "ck_package_kind: a parcel with a carton's status", "UPDATE fulfillment.package SET "
                        + "status = 'TO_CHECK' WHERE id = '" + PARCEL_OPEN + "'::uuid");
                RawSql.refused(db, "ck_package_shipped: a voided parcel may stay in a shipment but a REMOVED row may not",
                        "UPDATE fulfillment.package SET status = 'REMOVED', removed_at = NOW(), removal_reason = 'ORDER_CANCELLED' "
                                + "WHERE id = '" + PARCEL_SEALED_SHIPPED + "'::uuid");
                assertThat(RawSql.text(db, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_package_shipped'"))
                        .contains("LABELED").contains("SEALED");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_package_shipped_sealed'"))
                        .as("the old shipped rule was replaced").isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_trigger WHERE tgname IN ('tg_package_carton', "
                        + "'tg_pick_line_carton_removed', 'tg_order_label_lock', 'tg_package_label_locks_order') AND NOT tgisinternal"))
                        .as("the four new triggers").isEqualTo(4);
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uk_package_lpn_checking'")).isOne();
                assertThat(RawSql.text(db, "SELECT pg_get_functiondef('fulfillment.check_load_package'::regproc)"))
                        .as("a labelled carton loads like a sealed parcel").contains("LABELED");
                assertThat(RawSql.text(db, "SELECT pg_get_functiondef('fulfillment.check_load_plan_package'::regproc)"))
                        .contains("LABELED");
            }
        }
    }

    @Test
    @DisplayName("V20261113000710 after it (review L3, L6): changes no data; cartons get an index by LPN; a held order is "
            + "not labelled, a labelled one may still be held, and the address lock stands")
    void reviewFixesOnTheMigratedData() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                flyway(postgres, MIGRATION).migrate();
                String orders = "SELECT id::text || ':' || status || ':' || coalesce(first_labeled_at::text, '-') "
                        + "FROM ordering.customer_order ORDER BY id";
                String packages = "SELECT id::text || ':' || status FROM fulfillment.package ORDER BY id";
                var ordersBefore = RawSql.texts(db, orders);
                var packagesBefore = RawSql.texts(db, packages);
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_package_lpn'")).isZero();

                var result = flyway(postgres, REVIEW_FIXES).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(REVIEW_FIXES);

                assertThat(RawSql.texts(db, orders)).as("no order changed").isEqualTo(ordersBefore);
                assertThat(RawSql.texts(db, packages)).as("no package changed").isEqualTo(packagesBefore);
                assertThat(RawSql.text(db, "SELECT indexdef FROM pg_indexes WHERE schemaname = 'fulfillment' "
                        + "AND indexname = 'ix_package_lpn'")).contains("(lpn)").contains("lpn IS NOT NULL");

                // An ADDRESS hold on an order nobody labelled: no first label until it is resolved.
                RawSql.update(db, "UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'ADDRESS', "
                        + "status_before_hold = 'PICKING' WHERE id = '" + ORDER_NOT_PACKED + "'::uuid");
                refusedWith(db, "tg_order_label_lock", "UPDATE ordering.customer_order SET first_labeled_at = NOW() "
                        + "WHERE id = '" + ORDER_NOT_PACKED + "'::uuid");
                RawSql.update(db, "UPDATE ordering.customer_order SET shipping_line1 = 'Corrected' WHERE id = '"
                        + ORDER_NOT_PACKED + "'::uuid");
                RawSql.update(db, "UPDATE ordering.customer_order SET status = 'PICKING', hold_reason = NULL, "
                        + "status_before_hold = NULL WHERE id = '" + ORDER_NOT_PACKED + "'::uuid");
                RawSql.update(db, "UPDATE ordering.customer_order SET first_labeled_at = NOW() WHERE id = '"
                        + ORDER_NOT_PACKED + "'::uuid");

                // An order labelled already (packed by the repack station) may be held; its lock is unchanged.
                RawSql.update(db, "UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'CUSTOMER', "
                        + "status_before_hold = 'PICKING' WHERE id = '" + ORDER_PACKED + "'::uuid");
                refusedWith(db, "tg_order_label_lock", "UPDATE ordering.customer_order SET shipping_line1 = 'Elsewhere' "
                        + "WHERE id = '" + ORDER_PACKED + "'::uuid");
                refusedWith(db, "tg_order_label_lock", "UPDATE ordering.customer_order SET first_labeled_at = NULL "
                        + "WHERE id = '" + ORDER_PACKED + "'::uuid");
            }
        }
    }

    // ---- the old state --------------------------------------------------------------------------------------------

    private static String id(int n) {
        return "00000000-0000-4000-8000-0000000c6%03d".formatted(n);
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        try {
            pack(db, PACK_PACKING, 1, "PACKING", "NOW()", "NULL");
            pack(db, PACK_PENDING, 2, "PENDING", "NULL", "NULL");
            pack(db, PACK_PACKED, 3, "PACKED", "NOW()", "NOW()");
            pack(db, PACK_CANCELLED, 4, "CANCELLED", "NULL", "NULL");

            order(db, ORDER_PACKED, "PICKING", "NOW() - interval '1 day'", false);
            order(db, ORDER_CANCELLED_AFTER_PACKED, "CANCELLED", "NOW() - interval '2 days'", true);
            order(db, ORDER_NOT_PACKED, "PICKING", "NULL", false);

            // A shipment the shipped parcel is in; the migration never reads it.
            RawSql.update(db, "INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, created_at) "
                    + "VALUES (?::uuid, ?::uuid, ?::uuid, 'READY_TO_DISPATCH', 'SO-UPG6-S1', gen_random_uuid(), NOW(), NOW())",
                    SHIPMENT, PACK_PACKING, ORDER_PACKED);

            parcel(db, PARCEL_OPEN, PACK_PACKING, 1, "OPEN", null);
            parcel(db, PARCEL_SEALED_FREE, PACK_PACKING, 2, "SEALED", null);
            parcel(db, PARCEL_SEALED_SHIPPED, PACK_PACKING, 3, "SEALED", SHIPMENT);
            parcel(db, PARCEL_PENDING_SEALED, PACK_PENDING, 1, "SEALED", null);
            parcel(db, PARCEL_COMPLETED_SEALED, PACK_PACKED, 1, "SEALED", null);
            parcel(db, PARCEL_VOIDED_ALREADY, PACK_PACKING, 4, "VOIDED", null);
        } finally {
            RawSql.update(db, "SET session_replication_role = DEFAULT");
        }
    }

    private static void pack(Connection db, String id, int n, String status, String startedAt, String packedAt)
            throws SQLException {
        RawSql.update(db, "INSERT INTO fulfillment.pack (id, pick_id, status, started_at, packed_at, created_at) VALUES (?::uuid, "
                + "'00000000-0000-4000-8000-0000000c6" + "%03d".formatted(100 + n) + "'::uuid, '" + status + "', " + startedAt
                + ", " + packedAt + ", NOW())", id);
    }

    private static void order(Connection db, String id, String status, String packedAt, boolean cancelled)
            throws SQLException {
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, "
                + "currency, placed_at, created_at, packed_at, shipping_recipient_name, shipping_phone, shipping_line1, "
                + "shipping_ward_code, shipping_province_code, shipping_country_code"
                + (cancelled ? ", cancellation_reason, cancellation_reason_code" : "") + ") VALUES (?::uuid, ?, gen_random_uuid(), "
                + "gen_random_uuid(), '" + status + "', 1, 'VND', NOW(), NOW(), " + packedAt + ", 'Ms Lan', '0900000001', "
                + "'1 Le Loi', 'W1', 'P1', 'VN'" + (cancelled ? ", 'cancelled', 'OTHER'" : "") + ")",
                id, "SO-UPG6-" + id.substring(33));
    }

    private static void parcel(Connection db, String id, String pack, int n, String status, String shipment)
            throws SQLException {
        boolean sealed = status.equals("SEALED");
        RawSql.update(db, "INSERT INTO fulfillment.package (id, pack_id, shipment_id, package_no, label_code, status, sealed_at, "
                + "voided_at, weight_kg, length_cm, width_cm, height_cm) VALUES (?::uuid, ?::uuid, "
                + (shipment == null ? "NULL" : "'" + shipment + "'::uuid") + ", " + n + ", 'PKG-UPG6-' || ?, '" + status + "', "
                + (sealed ? "NOW()" : "NULL") + ", " + (status.equals("VOIDED") ? "NOW()" : "NULL") + ", "
                + (sealed ? "5, 40, 30, 20" : "NULL, NULL, NULL, NULL") + ")", id, pack, id.substring(30));
    }

    /** The statement is refused by a rule of the database, and the rule says {@code message}. */
    private static void refusedWith(Connection db, String message, String sql) {
        Throwable refusal = catchThrowable(() -> RawSql.update(db, sql));
        assertThat(refusal).as("refused with '%s': %s", message, sql).isInstanceOf(SQLException.class)
                .hasMessageContaining(message);
        assertThat(((SQLException) refusal).getSQLState()).startsWith("23");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
