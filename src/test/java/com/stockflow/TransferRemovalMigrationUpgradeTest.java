package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108000300 on the real upgrade path: a database migrated to the migration before it, holding a transfer order
 * still being picked - its hold on a shelf next to an order's, its packing session with a sealed parcel (a line, a
 * scan), its calculated load plan (the parcel placed on a vehicle: a placement refers to the plan's parcel row) and
 * the vehicle's dispatch load (manifest line, parcel on board, parcel to take off), a putaway task from its line, its
 * load estimate and the transfer number sequence - then upgraded.
 *
 * <p>Proves nothing is lost (every row dropped is archived), the shelf's {@code reserved} keeps only the order's hold,
 * an ORDER plan is untouched, the transfer tables, columns and functions are gone, the NOT NULLs come back, the CHECKs
 * no longer know TRANSFER, and every role that held a transfer-order permission moves its version.</p>
 */
class TransferRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261108000200";
    private static final String MIGRATION = "20261108000300";

    @Test
    void upgradeRetiresTransfersKeepingEverythingInTheArchive() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID from = UUID.randomUUID();
                UUID to = UUID.randomUUID();
                UUID user = UUID.randomUUID();
                UUID transfer = UUID.randomUUID();
                UUID transferLine = UUID.randomUUID();
                UUID shelfRow = UUID.randomUUID();
                UUID transferHold = UUID.randomUUID();
                UUID orderHold = UUID.randomUUID();
                UUID order = UUID.randomUUID();
                UUID pack = UUID.randomUUID();
                UUID parcel = UUID.randomUUID();
                UUID plan = UUID.randomUUID();
                UUID vehicle = UUID.randomUUID();
                UUID load = UUID.randomUUID();
                UUID orderPlan = UUID.randomUUID();
                UUID task = UUID.randomUUID();
                UUID estimate = UUID.randomUUID();
                long managerVersion = version(db, "WAREHOUSE_MANAGER");
                long plannerVersion = version(db, "INVENTORY_PLANNER");
                long staffVersion = version(db, "WAREHOUSE_STAFF");
                long accountantVersion = version(db, "ACCOUNTANT");

                // The warehouses, the SKU, the order and the people are not what this test is about: foreign keys and
                // row triggers are off while the old state is written.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO inventory.transfer_order (id, transfer_number, from_warehouse_id, "
                        + "to_warehouse_id, status, approved_by, approved_at) VALUES (?, 'TO-OLD-1', ?, ?, 'PICKING', ?, NOW())",
                        transfer, from, to, user);
                RawSql.update(db, "INSERT INTO inventory.transfer_order_line (id, transfer_order_id, line_no, sku, "
                        + "requested_qty) VALUES (?, ?, 1, 'CUP-OLD', 3)", transferLine, transfer);
                // A shelf row holding 3 for the transfer and 2 for an order.
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, "
                        + "created_at) VALUES (?, 'CUP-OLD', 'OLD-A01-1-A', 10, 5, 'AVAILABLE', NOW())", shelfRow);
                RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, stock_item_id, transfer_order_id, "
                        + "root_request_id, request_id, quantity, reserved_at, status, sku) VALUES (?, ?, ?, "
                        + "gen_random_uuid(), gen_random_uuid(), 3, NOW(), 'HELD', 'CUP-OLD')", transferHold, shelfRow,
                        transfer);
                RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, "
                        + "request_id, quantity, reserved_at, status, sku) VALUES (?, ?, ?, gen_random_uuid(), "
                        + "gen_random_uuid(), 2, NOW(), 'HELD', 'CUP-OLD')", orderHold, shelfRow, order);
                // Its packing session, a sealed parcel with its line and the scan behind it.
                RawSql.update(db, "INSERT INTO fulfillment.pack (id, transfer_order_id, status, created_at, started_at, "
                        + "packed_at) VALUES (?, ?, 'PACKED', NOW(), NOW(), NOW())", pack, transfer);
                RawSql.update(db, "INSERT INTO fulfillment.package (id, pack_id, package_no, label_code, status, "
                        + "transfer_order_id, weight_kg, length_cm, width_cm, height_cm, sealed_at, sealed_by) "
                        + "VALUES (?, ?, 1, 'PKG-OLD-1', 'SEALED', ?, 5, 30, 30, 30, NOW(), ?)", parcel, pack, transfer,
                        user);
                RawSql.update(db, "INSERT INTO fulfillment.package_line (id, package_id, transfer_line_id, quantity) "
                        + "VALUES (gen_random_uuid(), ?, ?, 3)", parcel, transferLine);
                RawSql.update(db, "INSERT INTO fulfillment.package_scan (id, package_id, transfer_line_id, scanned_code, "
                        + "quantity, scanned_by, scanned_at) VALUES (gen_random_uuid(), ?, ?, 'CUP-OLD', 3, ?, NOW())",
                        parcel, transferLine, user);
                // Its calculated load plan (the parcel placed on the plan's vehicle) and the vehicle's load: manifest
                // line, parcel on board, parcel to take off.
                RawSql.update(db, "INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, status, "
                        + "source_type, calculated_at) VALUES (?, 'LP-OLD-1', gen_random_uuid(), ?, 'PLANNED', "
                        + "'TRANSFER', NOW())", plan, from);
                RawSql.update(db, "INSERT INTO fulfillment.load_plan_vehicle (id, load_plan_id, seq, vehicle_type_id, "
                        + "vehicle_code, vehicle_kind, inner_length_mm, inner_width_mm, inner_height_mm, max_payload_kg) "
                        + "VALUES (?, ?, 1, gen_random_uuid(), 'TRUCK-OLD', 'TRUCK', 2000, 1000, 1000, 1000)", vehicle,
                        plan);
                RawSql.update(db, "INSERT INTO fulfillment.load_plan_package (id, load_plan_id, package_id, "
                        + "transfer_order_id, added_at) VALUES (gen_random_uuid(), ?, ?, ?, NOW())", plan, parcel,
                        transfer);
                RawSql.update(db, "INSERT INTO fulfillment.load_placement (id, load_plan_id, load_plan_vehicle_id, "
                        + "package_id, x_mm, y_mm, z_mm, w_mm, h_mm, d_mm, orientation) VALUES (gen_random_uuid(), ?, ?, "
                        + "?, 0, 0, 0, 300, 300, 300, 0)", plan, vehicle, parcel);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status, "
                        + "mode, load_plan_vehicle_id) VALUES (?, 'LD-OLD-1', gen_random_uuid(), ?, 'PLANNED', 'FULL_TRUCKLOAD', ?)",
                        load, from, vehicle);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, transfer_order_id, "
                        + "added_at, added_by, mode) VALUES (gen_random_uuid(), ?, ?, NOW(), ?, 'FULL_TRUCKLOAD')", load,
                        transfer, user);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load_package (id, load_id, package_id, "
                        + "transfer_order_id, scan_id, loaded_at, loaded_by) VALUES (gen_random_uuid(), ?, ?, ?, "
                        + "gen_random_uuid(), NOW(), ?)", load, parcel, transfer, user);
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, "
                        + "transfer_order_id, reason, requested_at) VALUES (gen_random_uuid(), ?, ?, ?, 'called off', "
                        + "NOW())", load, parcel, transfer);
                // An ORDER plan, untouched; a putaway task from the transfer line; its estimate; the TO sequence.
                RawSql.update(db, "INSERT INTO fulfillment.load_plan (id, code, request_id, warehouse_id, status, "
                        + "source_type) VALUES (?, 'LP-OLD-2', gen_random_uuid(), ?, 'DRAFT', 'ORDER')", orderPlan, from);
                RawSql.update(db, "INSERT INTO warehouse.putaway_task (id, transfer_order_line_id, sku, quantity, status, "
                        + "created_at) VALUES (?, ?, 'CUP-OLD', 3, 'PENDING', NOW())", task, transferLine);
                RawSql.update(db, "INSERT INTO fulfillment.load_estimate (id, source_type, source_id, mode, status) "
                        + "VALUES (?, 'TRANSFER', ?, 'SINGLE_TYPE', 'CURRENT')", estimate, transfer);
                RawSql.update(db, "INSERT INTO platform.document_sequence (document_type, sequence_date, last_value) "
                        + "VALUES ('TO', CURRENT_DATE, 1)");
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // The shelf keeps the order's hold only.
                assertThat(RawSql.texts(db, "SELECT id::text FROM inventory.stock_reservation WHERE stock_item_id = ?",
                        shelfRow)).containsExactly(orderHold.toString());
                assertThat(RawSql.number(db, "SELECT reserved FROM inventory.stock_item WHERE id = ?", shelfRow))
                        .isEqualTo(2);
                // The transfer's packing, plan and load are gone; the ORDER plan stays.
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.package WHERE id = ?", parcel)).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.pack WHERE id = ?", pack)).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.load_plan WHERE id = ?", plan)).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.dispatch_load WHERE id = ?", load))
                        .isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.load_plan WHERE id = ?", orderPlan))
                        .isEqualTo(1);
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.putaway_task WHERE id = ?", task)).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.load_estimate WHERE id = ?", estimate))
                        .isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.document_sequence WHERE document_type = "
                        + "'TO'")).isZero();

                // Archived before it went.
                assertThat(RawSql.numbersByText(db, "SELECT source_table, count(*) FROM platform.legacy_archive WHERE "
                        + "source_table IN ('inventory.transfer_order', 'inventory.transfer_order_line', "
                        + "'inventory.stock_reservation', 'fulfillment.pack', 'fulfillment.package', "
                        + "'fulfillment.package_line', 'fulfillment.package_scan', 'fulfillment.load_plan', "
                        + "'fulfillment.load_plan_vehicle', 'fulfillment.load_plan_package', 'fulfillment.load_placement', "
                        + "'fulfillment.dispatch_load', "
                        + "'fulfillment.dispatch_load_shipment', 'fulfillment.dispatch_load_package', "
                        + "'fulfillment.dispatch_load_unload', 'warehouse.putaway_task', 'fulfillment.load_estimate', "
                        + "'platform.document_sequence') GROUP BY source_table"))
                        .containsOnlyKeys("inventory.transfer_order", "inventory.transfer_order_line",
                                "inventory.stock_reservation", "fulfillment.pack", "fulfillment.package",
                                "fulfillment.package_line", "fulfillment.package_scan", "fulfillment.load_plan",
                                "fulfillment.load_plan_vehicle", "fulfillment.load_plan_package",
                                "fulfillment.load_placement", "fulfillment.dispatch_load", "fulfillment.dispatch_load_shipment",
                                "fulfillment.dispatch_load_package", "fulfillment.dispatch_load_unload",
                                "warehouse.putaway_task", "fulfillment.load_estimate", "platform.document_sequence")
                        .allSatisfy((table, count) -> assertThat(count).as(table).isEqualTo(1L));
                assertThat(RawSql.text(db, "SELECT row_data ->> 'transfer_order_id' FROM platform.legacy_archive WHERE "
                        + "source_table = 'inventory.stock_reservation' AND row_id = ?", transferHold))
                        .isEqualTo(transfer.toString());

                // What transfers kept in the schema is gone; the NOT NULLs and CHECKs no longer leave room for one.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'inventory' AND table_name LIKE 'transfer_order%'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema IN "
                        + "('inventory', 'fulfillment', 'warehouse') AND column_name LIKE '%transfer%'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE prosrc ILIKE '%transfer_order%'"))
                        .isZero();
                RawSql.refused(db, "an orderless hold", "INSERT INTO inventory.stock_reservation (id, stock_item_id, "
                        + "root_request_id, request_id, quantity, reserved_at, status, sku) VALUES (gen_random_uuid(), ?, "
                        + "gen_random_uuid(), gen_random_uuid(), 1, NOW(), 'HELD', 'CUP-OLD')", shelfRow);
                RawSql.refused(db, "ck_load_plan_source", "UPDATE fulfillment.load_plan SET source_type = 'TRANSFER' "
                        + "WHERE id = ?", orderPlan);

                // Every role that held a transfer-order permission moves its version; one that held none does not.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code LIKE "
                        + "'inventory-transfer-orders:%'")).isZero();
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(managerVersion + 1);
                assertThat(version(db, "INVENTORY_PLANNER")).isEqualTo(plannerVersion + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staffVersion + 1);
                assertThat(version(db, "ACCOUNTANT")).isEqualTo(accountantVersion);
            }
        }
    }

    private static long version(Connection db, String role) throws Exception {
        return RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = ?", role);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
