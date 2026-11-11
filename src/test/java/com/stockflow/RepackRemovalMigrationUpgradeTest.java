package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261114000300 on the real upgrade path, over the demo seed (team plan 4C-7; kltn-docs 08 BR-02; owner's decision
 * QD-1: nothing archived, nothing refuses the upgrade). Written at the schema before it: an order whose packing
 * started, with a repack parcel never sealed (its contents and a scan), a sealed one in a shipment (a tracking number,
 * a fragile mark, contents), a voided one, and a shipping carton of the check station.
 *
 * <p>Upgraded: the open parcel is deleted with its contents and scans; the sealed and the voided parcel stay, in their
 * shipment, with no contents on record; the carton row is untouched; the parcel type, kit, fragile, outdated-label and
 * tracking columns, the contents and scans tables, the kit triggers and the order's {@code packing_started_at} are gone;
 * a new row must be a shipping carton, and ck_package_kind still tells the two kinds of row apart.</p>
 */
class RepackRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261114000200";
    private static final String MIGRATION = "20261114000300";

    private static final String ORDER = "00000000-0000-4000-8000-0000000c7001";
    private static final String PACK = "00000000-0000-4000-8000-0000000c7201";
    private static final String SHIPMENT = "00000000-0000-4000-8000-0000000c7301";
    private static final String OPEN_PARCEL = "00000000-0000-4000-8000-0000000c7401";
    private static final String SEALED_PARCEL = "00000000-0000-4000-8000-0000000c7402";
    private static final String VOIDED_PARCEL = "00000000-0000-4000-8000-0000000c7403";
    private static final String CARTON = "00000000-0000-4000-8000-0000000c7404";
    private static final String CARTON_LINE = "00000000-0000-4000-8000-0000000c7501";

    /** Rows of the schema before the upgrade, written with triggers and foreign keys off (their history is not the subject). */
    private static final String SEED = """
            SET session_replication_role = replica;
            INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at, warehouse_id, packing_started_at)
            VALUES ('00000000-0000-4000-8000-0000000c7001', 'SO-UPG4C7-1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PICKING', 1000, 'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid, NOW());
            INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
            VALUES ('00000000-0000-4000-8000-0000000c7011', '00000000-0000-4000-8000-0000000c7001', 'SOFA-3S-GREY', 10, 500, 'VND');
            INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, picked_at, created_at)
            VALUES ('00000000-0000-4000-8000-0000000c7101', '00000000-0000-4000-8000-0000000c7001', 'SO-UPG4C7-1', md5('demo:wh:HCM')::uuid, 'PICKED', NOW(), NOW());
            INSERT INTO fulfillment.pack (id, pick_id, status, created_at)
            VALUES ('00000000-0000-4000-8000-0000000c7201', '00000000-0000-4000-8000-0000000c7101', 'PENDING', NOW());
            INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, created_at)
            VALUES ('00000000-0000-4000-8000-0000000c7301', '00000000-0000-4000-8000-0000000c7201', '00000000-0000-4000-8000-0000000c7001', 'READY_TO_DISPATCH', 'SO-UPG4C7-1-S1', md5('demo:wh:HCM')::uuid, NOW(), NOW());
            -- A repack parcel never sealed, with its contents and a scan; a sealed one in the shipment (tracking number, a kit part); a voided one.
            INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, created_at)
            VALUES ('00000000-0000-4000-8000-0000000c7401', '00000000-0000-4000-8000-0000000c7201', 1, 'PKG-UPG4C7-1', 'OPEN', NOW());
            INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity)
            VALUES (gen_random_uuid(), '00000000-0000-4000-8000-0000000c7401', '00000000-0000-4000-8000-0000000c7011', 2);
            INSERT INTO fulfillment.package_scan (id, package_id, order_line_id, scanned_code, quantity, scanned_by, scanned_at)
            VALUES (gen_random_uuid(), '00000000-0000-4000-8000-0000000c7401', '00000000-0000-4000-8000-0000000c7011', 'SOFA-3S-GREY', 2, md5('demo:user:editor')::uuid, NOW());
            INSERT INTO fulfillment.package (id, pack_id, shipment_id, package_no, label_code, weight_kg, length_cm, width_cm, height_cm, tracking_number, status, sealed_at, fragile)
            VALUES ('00000000-0000-4000-8000-0000000c7402', '00000000-0000-4000-8000-0000000c7201', '00000000-0000-4000-8000-0000000c7301', 2, 'PKG-UPG4C7-2', 30, 215, 95, 90, 'GHN-UPG4C7', 'SEALED', NOW(), TRUE);
            INSERT INTO fulfillment.package_line (id, package_id, order_line_id, quantity)
            VALUES (gen_random_uuid(), '00000000-0000-4000-8000-0000000c7402', '00000000-0000-4000-8000-0000000c7011', 8);
            INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, sealed_at, voided_at, voided_by, weight_kg, length_cm, width_cm, height_cm)
            VALUES ('00000000-0000-4000-8000-0000000c7403', '00000000-0000-4000-8000-0000000c7201', 3, 'PKG-UPG4C7-3', 'VOIDED', NOW(), NOW(), md5('demo:user:editor')::uuid, 5, 40, 40, 30);
            -- A shipping carton of the check station (kept): a picked demo carton of one sofa.
            INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, sequence_no, allocated_qty, picked_qty, status, lpn, whole_carton, picked_by, picked_at, consumed_at)
            SELECT '00000000-0000-4000-8000-0000000c7501', '00000000-0000-4000-8000-0000000c7101', '00000000-0000-4000-8000-0000000c7011', 'SOFA-3S-GREY', 'HCM-A01-2-B', 1, 1, 1, 'PICKED', p.lpn, TRUE, md5('demo:user:editor')::uuid, NOW(), NOW()
              FROM inventory.stock_package p JOIN inventory.stock_item s ON s.stock_package_id = p.id
             WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' AND p.status = 'IN_STOCK' ORDER BY p.lpn LIMIT 1;
            INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, pick_line_id, lpn)
            SELECT '00000000-0000-4000-8000-0000000c7404', '00000000-0000-4000-8000-0000000c7201', 4, 'SO-UPG4C7-1-C1', 'TO_CHECK', '00000000-0000-4000-8000-0000000c7501', l.lpn
              FROM fulfillment.pick_line l WHERE l.id = '00000000-0000-4000-8000-0000000c7501';
            SET session_replication_role = DEFAULT;
            """;

    @Test
    void openParcelsGoSealedOnesStayAndNewRowsAreCartons() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                db.createStatement().execute(SEED);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // What never shipped is gone with its contents; what shipped or was voided is history and stays.
                assertThat(RawSql.texts(db, "SELECT label_code || ':' || status FROM fulfillment.package WHERE pack_id = "
                        + "?::uuid ORDER BY package_no", PACK))
                        .containsExactly("PKG-UPG4C7-2:SEALED", "PKG-UPG4C7-3:VOIDED", "SO-UPG4C7-1-C1:TO_CHECK");
                assertThat(RawSql.text(db, "SELECT shipment_id::text FROM fulfillment.package WHERE id = ?::uuid",
                        SEALED_PARCEL)).isEqualTo(SHIPMENT);
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.package WHERE id = ?::uuid", OPEN_PARCEL))
                        .isZero();
                assertThat(RawSql.text(db, "SELECT pick_line_id::text || ':' || (lpn IS NOT NULL) FROM "
                        + "fulfillment.package WHERE id = ?::uuid", CARTON)).isEqualTo(CARTON_LINE + ":true");

                // Contents, scans, kit rules and the repack station's columns are gone.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'fulfillment' AND table_name IN ('package_line', 'package_scan')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'fulfillment' AND table_name = 'package' AND column_name IN ('package_type', 'fragile', "
                        + "'fragile_overridden', 'kit_group_id', 'kit_part_no', 'kit_part_count', 'kit_order_line_id', "
                        + "'kit_override_reason', 'label_outdated_at', 'tracking_number')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_trigger WHERE tgname IN ('tg_package_kit_check', "
                        + "'tg_package_kit_ships_whole', 'tg_package_kit_fixed')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE proname IN ("
                        + "'check_package_line_quantity', 'check_kit_part_line', 'check_package_kit', "
                        + "'check_kit_ships_whole', 'refuse_package_kit_change')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'ordering' AND table_name = 'customer_order' AND column_name = 'packing_started_at'")).isZero();
                assertThat(RawSql.text(db, "SELECT status FROM ordering.customer_order WHERE id = ?::uuid", ORDER))
                        .as("the order itself is untouched").isEqualTo("PICKING");

                // A new row is a shipping carton: a parcel naming no pick line is refused, and the two kinds still differ.
                RawSql.refused(db, "tg_package_is_carton", "INSERT INTO fulfillment.package (id, pack_id, package_no, "
                        + "label_code, status) VALUES (gen_random_uuid(), ?::uuid, 9, 'PKG-UPG4C7-9', 'OPEN')", PACK);
                RawSql.refused(db, "ck_package_kind", "UPDATE fulfillment.package SET status = 'OPEN' WHERE id = "
                        + "?::uuid", CARTON);
                RawSql.refused(db, "ck_package_kind", "UPDATE fulfillment.package SET status = 'TO_CHECK' WHERE id = "
                        + "?::uuid", SEALED_PARCEL);
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo")
                .target(target)
                .load();
    }
}
