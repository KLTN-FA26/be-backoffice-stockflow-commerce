package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261114000400 on the real upgrade path, over the demo seed (team plan 5-6; kltn-docs 07 §4.4 step 9, 08 §4.2 step 4,
 * 20 BR-21; owner's decision QD-1: nothing archived, nothing refuses the upgrade). Written at the schema before it: a
 * damage report with its photo and the SHORT line it left with no carton (the unit held on a loose DAMAGED row in
 * quarantine), a picked carton of three found damaged on the packing bench, an ordinary picked carton, unfinished and
 * finished event publications of the two damage events.
 *
 * <p>Upgraded: the impaired hold is RELEASED (reason DAMAGED, the row's {@code reserved} lowered) and its line CANCELLED
 * with reason DAMAGED; the packing-damaged line is CANCELLED; the ordinary line is untouched; the report tables, the
 * damage columns and their CHECKs and functions are gone, the rules that named them stated again; the permission of
 * the reports is gone and the roles that held it are a version on; the unfinished damage publications are deleted and
 * nothing else.</p>
 */
class UnitDamageRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261114000300";
    private static final String MIGRATION = "20261114000400";

    private static final String IMPAIRED_LINE = "00000000-0000-4000-8000-0000000d5401";
    private static final String PACKING_DAMAGED_LINE = "00000000-0000-4000-8000-0000000d5402";
    private static final String ORDINARY_LINE = "00000000-0000-4000-8000-0000000d5403";
    private static final String IMPAIRED_HOLD = "00000000-0000-4000-8000-0000000d5301";
    private static final String DAMAGED_ROW = "00000000-0000-4000-8000-0000000d5201";

    /** Rows of the schema before the upgrade, written with triggers and foreign keys off (their history is not the subject). */
    private static final String SEED = """
            SET session_replication_role = replica;
            INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at, warehouse_id)
            VALUES ('00000000-0000-4000-8000-0000000d5001', 'SO-UPG5C6-1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'PICKING', 1000, 'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid);
            INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
            VALUES ('00000000-0000-4000-8000-0000000d5011', '00000000-0000-4000-8000-0000000d5001', 'SOFA-3S-GREY', 10, 500, 'VND');
            INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
            VALUES ('00000000-0000-4000-8000-0000000d5101', '00000000-0000-4000-8000-0000000d5001', 'SO-UPG5C6-1', md5('demo:wh:HCM')::uuid, 'PICKING', NOW());
            -- A damaged unit nothing could replace: held on a loose DAMAGED row in quarantine, the line SHORT with no carton.
            INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, version, created_at, received_at)
            VALUES ('00000000-0000-4000-8000-0000000d5201', 'SOFA-3S-GREY', 'HCM-QC01', 'UNTRACED', 1, 1, 'DAMAGED', 0, NOW(), NOW());
            INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity, reserved_at, expires_at, status, sku)
            VALUES ('00000000-0000-4000-8000-0000000d5301', '00000000-0000-4000-8000-0000000d5201', '00000000-0000-4000-8000-0000000d5001', gen_random_uuid(), gen_random_uuid(), 1, NOW(), NULL, 'HELD', 'SOFA-3S-GREY');
            INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, reservation_id, sku, location_code, sequence_no, allocated_qty, short_qty, status, damage_report_id)
            VALUES ('00000000-0000-4000-8000-0000000d5401', '00000000-0000-4000-8000-0000000d5101', '00000000-0000-4000-8000-0000000d5011', '00000000-0000-4000-8000-0000000d5301', 'SOFA-3S-GREY', 'HCM-A01-2-B', 1, 1, 1, 'SHORT', '00000000-0000-4000-8000-0000000d5501');
            INSERT INTO inventory.stock_damage_report (id, report_number, request_id, origin, warehouse_id, sku, lot_number, source_stock_item_id, source_location_code, damaged_stock_item_id, damage_location_code, quantity, reason_code, order_id, pick_line_id, reservation_id, impaired_reservation_id, reported_by, reported_at)
            SELECT '00000000-0000-4000-8000-0000000d5501', 'DMG-UPG5C6-1', gen_random_uuid(), 'PICKING', md5('demo:wh:HCM')::uuid, 'SOFA-3S-GREY', 'UNTRACED', s.id, 'HCM-A01-2-B', '00000000-0000-4000-8000-0000000d5201', 'HCM-QC01', 1, 'BROKEN', '00000000-0000-4000-8000-0000000d5001', '00000000-0000-4000-8000-0000000d5401', '00000000-0000-4000-8000-0000000d5301', '00000000-0000-4000-8000-0000000d5301', md5('demo:user:editor')::uuid, NOW()
              FROM inventory.stock_item s WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' LIMIT 1;
            INSERT INTO inventory.stock_damage_photo (id, damage_report_id, storage_key, original_name, content_type, size_bytes, checksum, sort_order)
            VALUES (gen_random_uuid(), '00000000-0000-4000-8000-0000000d5501', 'qc-evidence/upg5c6/broken.jpg', 'broken.jpg', 'image/jpeg', 20, repeat('a', 64), 1);
            -- A picked carton of three the packing bench found all damaged (the carton went to quarantine), and an ordinary picked carton.
            INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
            VALUES ('00000000-0000-4000-8000-0000000d5601', md5('demo:item:SOFA-3S-GREY')::uuid, 'UPG5C6_CTN', 3);
            INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status)
            VALUES ('00000000-0000-4000-8000-0000000d5701', 'UPG5C6-LPN-3', md5('demo:item:SOFA-3S-GREY')::uuid, '00000000-0000-4000-8000-0000000d5601', 3, 3, 'HCM-QC01', 'IN_STOCK');
            INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, sequence_no, allocated_qty, picked_qty, packing_damaged_qty, status, lpn, whole_carton, picked_by, picked_at, consumed_at)
            VALUES ('00000000-0000-4000-8000-0000000d5402', '00000000-0000-4000-8000-0000000d5101', '00000000-0000-4000-8000-0000000d5011', 'SOFA-3S-GREY', 'HCM-A01-2-B', 2, 3, 3, 3, 'PICKED', 'UPG5C6-LPN-3', TRUE, md5('demo:user:editor')::uuid, NOW(), NOW());
            INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, sequence_no, allocated_qty, picked_qty, status, lpn, whole_carton, picked_by, picked_at, consumed_at)
            SELECT '00000000-0000-4000-8000-0000000d5403', '00000000-0000-4000-8000-0000000d5101', '00000000-0000-4000-8000-0000000d5011', 'SOFA-3S-GREY', 'HCM-A01-2-B', 3, 1, 1, 'PICKED', p.lpn, TRUE, md5('demo:user:editor')::uuid, NOW(), NOW()
              FROM inventory.stock_package p JOIN inventory.stock_item s ON s.stock_package_id = p.id
             WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' AND p.status = 'IN_STOCK' ORDER BY p.lpn LIMIT 1;
            INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
            VALUES ('00000000-0000-4000-8000-0000000d5801', 'l1', 'com.stockflow.contracts.StockDamageReported', '{}', NOW(), NULL),
                   ('00000000-0000-4000-8000-0000000d5802', 'l1', 'com.stockflow.contracts.StockDamageDisposed', '{}', NOW(), NULL),
                   ('00000000-0000-4000-8000-0000000d5803', 'l1', 'com.stockflow.contracts.StockDamageReported', '{}', NOW(), NOW()),
                   ('00000000-0000-4000-8000-0000000d5804', 'l1', 'com.stockflow.contracts.ReturnApproved', '{}', NOW(), NULL);
            SET session_replication_role = DEFAULT;
            """;

    @Test
    void impairedHoldsAreReleasedDamageTablesGoAndTheRulesStayInTheDatabase() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                db.createStatement().execute(SEED);
                String rolesBefore = RawSql.text(db, "SELECT string_agg(r.code || ':' || r.version, ',' ORDER BY r.code) "
                        + "FROM identity.app_role r WHERE r.id IN (SELECT rp.role_id FROM identity.role_permission rp "
                        + "JOIN identity.permission p ON p.id = rp.permission_id WHERE p.resource = "
                        + "'inventory-damage-reports')");
                assertThat(rolesBefore).as("the demo grants the damage reports to someone").isNotBlank();

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // The impaired allocation: the hold released with its reason, the unit unheld, the line cancelled.
                assertThat(RawSql.text(db, "SELECT status || ':' || short_reason FROM fulfillment.pick_line WHERE id = "
                        + "?::uuid", IMPAIRED_LINE)).isEqualTo("CANCELLED:DAMAGED");
                assertThat(RawSql.text(db, "SELECT status || ':' || release_reason || ':' || (closed_at IS NOT NULL) "
                        + "FROM inventory.stock_reservation WHERE id = ?::uuid", IMPAIRED_HOLD))
                        .isEqualTo("RELEASED:DAMAGED:true");
                assertThat(RawSql.number(db, "SELECT reserved FROM inventory.stock_item WHERE id = ?::uuid", DAMAGED_ROW))
                        .as("the damaged unit stays on the books, unheld").isZero();
                assertThat(RawSql.number(db, "SELECT on_hand FROM inventory.stock_item WHERE id = ?::uuid", DAMAGED_ROW))
                        .isOne();
                // The carton the packing bench found damaged went to quarantine: its line ends; an ordinary one stays.
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.pick_line WHERE id = ?::uuid",
                        PACKING_DAMAGED_LINE)).isEqualTo("CANCELLED");
                assertThat(RawSql.text(db, "SELECT status || ':' || picked_qty FROM fulfillment.pick_line WHERE id = "
                        + "?::uuid", ORDINARY_LINE)).isEqualTo("PICKED:1");

                // The reports, their photos, dispositions and hand-overs, the damage columns and rules are gone.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'inventory' AND table_name IN ('stock_damage_report', 'stock_damage_photo', "
                        + "'stock_damage_disposition', 'stock_damage_supplier_shipment')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE proname IN "
                        + "('check_stock_damage_supplier_shipment', 'check_stock_damage_disposition', "
                        + "'check_stock_damage_report_photo', 'check_stock_damage_report_after_pick')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'fulfillment' AND table_name = 'pick_line' AND column_name IN ('damaged_qty', "
                        + "'damage_report_id', 'packing_damaged_qty')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conname IN "
                        + "('ck_pick_line_damaged', 'ck_pick_line_packing_damaged', 'ck_pick_line_packing_damaged_whole', "
                        + "'fk_pick_line_damage_report')")).isZero();

                // The rules the dropped CHECKs carried are stated again, and hold for what is written next.
                assertThat(RawSql.texts(db, "SELECT conname FROM pg_constraint WHERE conrelid = "
                        + "'fulfillment.pick_line'::regclass AND conname IN ('ck_pick_line_lpn', "
                        + "'ck_pick_line_short_reported', 'ck_pick_line_put_back', 'ck_pick_line_within_allocation') "
                        + "ORDER BY conname")).containsExactly("ck_pick_line_lpn", "ck_pick_line_put_back",
                        "ck_pick_line_short_reported", "ck_pick_line_within_allocation");
                assertThat(RawSql.text(db, "SELECT convalidated::text FROM pg_constraint WHERE conname = "
                        + "'ck_pick_line_within_allocation'")).isEqualTo("true");
                assertThat(RawSql.text(db, "SELECT convalidated::text FROM pg_constraint WHERE conname = "
                        + "'ck_pick_line_put_back'")).as("no partial put-back was written").isEqualTo("true");
                RawSql.refused(db, "ck_pick_line_within_allocation", "UPDATE fulfillment.pick_line SET short_qty = 1 "
                        + "WHERE id = ?::uuid", ORDINARY_LINE);
                RawSql.refused(db, "ck_pick_line_put_back", "UPDATE fulfillment.pick_line SET status = 'PUT_BACK', "
                        + "put_back_qty = 0, put_back_by = md5('demo:user:editor')::uuid, put_back_location_code = "
                        + "'HCM-A01-2-B', put_back_at = NOW() WHERE id = ?::uuid", ORDINARY_LINE);
                RawSql.refused(db, "ck_pick_line_short_reported", "UPDATE fulfillment.pick_line SET status = 'SHORT', "
                        + "short_qty = 1, picked_qty = 0 WHERE id = ?::uuid", ORDINARY_LINE);
                assertThat(RawSql.text(db, "SELECT (pg_get_triggerdef(oid) LIKE '%BEFORE UPDATE OF status ON "
                        + "fulfillment.pick_line%')::text FROM pg_trigger WHERE tgname = 'tg_pick_line_carton_removed' "
                        + "AND NOT tgisinternal")).as("it fires on a status change only").isEqualTo("true");

                // The permission is gone with its grants; each role that held it is a version on (ADR-0008).
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE resource = "
                        + "'inventory-damage-reports'")).isZero();
                for (String entry : rolesBefore.split(",")) {
                    String[] role = entry.split(":");
                    assertThat(RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = ?", role[0]))
                            .as(role[0]).isEqualTo(Long.parseLong(role[1]) + 1);
                }

                // Only the unfinished damage publications went.
                assertThat(RawSql.texts(db, "SELECT regexp_replace(event_type, '^.*[.]', '') || ':' || "
                        + "(completion_date IS NULL) FROM event_publication WHERE id::text LIKE "
                        + "'00000000-0000-4000-8000-0000000d58%' ORDER BY id"))
                        .containsExactly("StockDamageReported:false", "ReturnApproved:true");
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
