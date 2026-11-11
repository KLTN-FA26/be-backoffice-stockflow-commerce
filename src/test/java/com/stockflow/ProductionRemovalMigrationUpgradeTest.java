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
 * V20261108000100 on the real upgrade path: a database migrated to the migration before it, holding what in-house
 * production left behind - an order still printed for (its blanks allocated on a shelf, and some issued to the print
 * shop), one whose stock was already being picked, one held from printing, one waiting for its prints after its stock
 * left, one ready again with its task open, a production-shortfall agreement on a line, production's own hold (and a
 * write-off naming it), people holding PRODUCTION_STAFF and an unfinished event publication of a removed event - then
 * upgraded.
 *
 * <p>Proves nothing is lost (every row dropped is archived), the orders move on to READY_TO_FULFILL through an edge the
 * transition table has, {@code reserved} follows the holds still HELD, the hold CHECKs come back without the dropped
 * column, the role goes with its people's sessions, and only the publications that can no longer be delivered go.</p>
 */
class ProductionRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261107000100";
    private static final String MIGRATION = "20261108000100";

    @Test
    void upgradeRetiresInHouseProductionKeepingEverythingInTheArchive() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID customer = UUID.randomUUID();
                UUID warehouse = UUID.randomUUID();
                UUID staff = UUID.randomUUID();
                UUID printing = UUID.randomUUID();
                UUID held = UUID.randomUUID();
                UUID awaiting = UUID.randomUUID();
                UUID pickingWhilePrinted = UUID.randomUUID();
                UUID readyWithTask = UUID.randomUUID();
                UUID blankRow = UUID.randomUUID();
                UUID blankAllocation = UUID.randomUUID();
                UUID printLine = UUID.randomUUID();
                UUID shortLine = UUID.randomUUID();
                UUID shelfRow = UUID.randomUUID();
                UUID printShopRow = UUID.randomUUID();
                UUID productionHold = UUID.randomUUID();
                UUID issuedHold = UUID.randomUUID();
                UUID writeOff = UUID.randomUUID();
                UUID onlyProduction = UUID.randomUUID();
                UUID alsoWarehouse = UUID.randomUUID();
                UUID onlySession = UUID.randomUUID();
                UUID alsoSession = UUID.randomUUID();
                UUID stalePublication = UUID.randomUUID();
                UUID donePublication = UUID.randomUUID();
                UUID otherPublication = UUID.randomUUID();
                long managerVersion = RawSql.number(db,
                        "SELECT version FROM identity.app_role WHERE code = 'WAREHOUSE_MANAGER'");
                long accountantVersion = RawSql.number(db,
                        "SELECT version FROM identity.app_role WHERE code = 'ACCOUNTANT'");

                // The customer, warehouse and releasing user exist: an order the migration moves through two status
                // edges is written twice in one transaction, and Postgres checks a row's foreign keys again on the
                // second write.
                RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, created_at) "
                        + "VALUES (?, 'Old customer', 'old-customer@example.com', 'ACTIVE', NOW())", customer);
                RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, "
                        + "map_width, map_height, version, created_at) VALUES (?, 'Old warehouse', 'ACTIVE', 'OLDWH', "
                        + "'Somewhere', 'M', 50, 50, 0, NOW())", warehouse);
                RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, status, created_at) "
                        + "VALUES (?, 'releaser', 'releaser@example.com', 'ACTIVE', NOW())", staff);
                // Other modules' rows (locations, SKUs, the other users) are not what this test is about: their foreign
                // keys and the row triggers are off while the old state is written.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                        + "total_amount, currency, placed_at, version, created_at, warehouse_id, released_at, released_by) "
                        + "VALUES (?, 'SO-OLD-1', ?, gen_random_uuid(), 'IN_PRODUCTION', 20000, 'VND', NOW(), 0, NOW(), "
                        + "?, NOW(), ?)", printing, customer, warehouse, staff);
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                        + "total_amount, currency, placed_at, version, created_at, warehouse_id, released_at, released_by, "
                        + "hold_reason, status_before_hold) VALUES (?, 'SO-OLD-2', ?, gen_random_uuid(), 'ON_HOLD', 20000, "
                        + "'VND', NOW(), 0, NOW(), ?, NOW(), ?, 'CUSTOMER', 'IN_PRODUCTION')", held, customer, warehouse,
                        staff);
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                        + "total_amount, currency, placed_at, version, created_at, warehouse_id, released_at, released_by, "
                        + "allow_partial_shipment, awaiting_production) VALUES (?, 'SO-OLD-3', ?, gen_random_uuid(), "
                        + "'PARTIALLY_FULFILLED', 20000, 'VND', NOW(), 0, NOW(), ?, NOW(), ?, TRUE, TRUE)", awaiting,
                        customer, warehouse, staff);
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                        + "total_amount, currency, placed_at, version, created_at, warehouse_id, released_at, released_by, "
                        + "fulfilment_started_at) VALUES (?, 'SO-OLD-4', ?, gen_random_uuid(), 'IN_PRODUCTION', 20000, "
                        + "'VND', NOW(), 0, NOW(), ?, NOW(), ?, NOW())", pickingWhilePrinted, customer, warehouse, staff);
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                        + "total_amount, currency, placed_at, version, created_at, warehouse_id, released_at, released_by, "
                        + "fulfilment_started_at) VALUES (?, 'SO-OLD-5', ?, gen_random_uuid(), 'READY_TO_FULFILL', 20000, "
                        + "'VND', NOW(), 0, NOW(), ?, NOW(), ?, NOW())", readyWithTask, customer, warehouse, staff);
                RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, "
                        + "design_snapshot_id, design_checksum) VALUES (?, ?, 'CUP-PRINT', 10, 2000, 'VND', ?, ?)",
                        printLine, printing, UUID.randomUUID(), "a".repeat(64));
                // The printing order's print line: 5 blanks allocated on a shelf, not issued yet.
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, status, on_hand, reserved, "
                        + "created_at) VALUES (?, 'CUP-PRINT', 'HCM-A01-1-B', 'AVAILABLE', 8, 5, NOW())", blankRow);
                RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, root_request_id, request_id, quantity, "
                        + "reserved_at, status, sku, stock_item_id, order_id) VALUES (?, gen_random_uuid(), "
                        + "gen_random_uuid(), 5, NOW(), 'HELD', 'CUP-PRINT', ?, ?)", blankAllocation, blankRow, printing);
                RawSql.update(db, "INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id) "
                        + "VALUES (?, ?)", printLine, blankAllocation);
                RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, "
                        + "design_snapshot_id, design_checksum, cancelled_quantity, shortfall_agreed_quantity, "
                        + "shortfall_agreed_note, shortfall_agreed_by, shortfall_agreed_at, shortfall_closed_quantity) "
                        + "VALUES (?, ?, 'CUP-PRINT', 10, 2000, 'VND', ?, ?, 2, 3, 'agreed on the phone', ?, NOW(), 2)",
                        shortLine, awaiting, UUID.randomUUID(), "a".repeat(64), staff);
                // Production's own hold of 4 on a shelf row, and a write-off production asked for naming it.
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, status, on_hand, reserved, "
                        + "created_at) VALUES (?, 'CUP-BLANK', 'HCM-A01-1-A', 'AVAILABLE', 10, 4, NOW())", shelfRow);
                RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, root_request_id, request_id, quantity, "
                        + "reserved_at, status, sku, stock_item_id, production_order_id) VALUES (?, gen_random_uuid(), "
                        + "gen_random_uuid(), 4, NOW(), 'HELD', 'CUP-BLANK', ?, gen_random_uuid())", productionHold,
                        shelfRow);
                RawSql.update(db, "INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, "
                        + "quantity_delta, reason_code, requested_by, reservation_id) VALUES (?, 'ADJ-OLD-1', "
                        + "'HCM-A01-1-A', 'CUP-BLANK', -1, 'SCRAP', ?, ?)", writeOff, staff, productionHold);
                // The printing order's blanks, issued to the print shop under its own hold of 6.
                RawSql.update(db, "INSERT INTO inventory.stock_item (id, sku, location_code, status, on_hand, reserved, "
                        + "created_at) VALUES (?, 'CUP-BLANK', 'HCM-PRD01', 'IN_PRODUCTION', 6, 6, NOW())", printShopRow);
                RawSql.update(db, "INSERT INTO inventory.stock_reservation (id, root_request_id, request_id, quantity, "
                        + "reserved_at, status, sku, stock_item_id, order_id) VALUES (?, gen_random_uuid(), "
                        + "gen_random_uuid(), 6, NOW(), 'HELD', 'CUP-BLANK', ?, ?)", issuedHold, printShopRow, printing);
                // A print-shop worker, and a warehouse clerk who also printed; both signed in.
                RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, status, created_at) "
                        + "VALUES (?, 'printer', 'printer@example.com', 'ACTIVE', NOW())", onlyProduction);
                RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, status, created_at) "
                        + "VALUES (?, 'clerk', 'clerk@example.com', 'ACTIVE', NOW())", alsoWarehouse);
                RawSql.update(db, "INSERT INTO identity.user_role (id, user_id, role_id, created_at) SELECT "
                        + "gen_random_uuid(), ?, id, NOW() FROM identity.app_role WHERE code = 'PRODUCTION_STAFF'",
                        onlyProduction);
                RawSql.update(db, "INSERT INTO identity.user_role (id, user_id, role_id, created_at) SELECT "
                        + "gen_random_uuid(), ?, id, NOW() FROM identity.app_role WHERE code IN ('PRODUCTION_STAFF', "
                        + "'WAREHOUSE_STAFF')", alsoWarehouse);
                RawSql.update(db, "INSERT INTO identity.user_session (id, user_id, issued_at, expires_at, created_at) "
                        + "VALUES (?, ?, NOW(), NOW() + interval '1 hour', NOW())", onlySession, onlyProduction);
                RawSql.update(db, "INSERT INTO identity.user_session (id, user_id, issued_at, expires_at, created_at) "
                        + "VALUES (?, ?, NOW(), NOW() + interval '1 hour', NOW())", alsoSession, alsoWarehouse);
                // A release production never handled, one it handled, and an unrelated one still waiting.
                RawSql.update(db, "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, "
                        + "publication_date) VALUES (?, 'com.stockflow.production.internal.service.OrderReleaseListener."
                        + "on(com.stockflow.contracts.OrderLinesReleasedForProduction)', "
                        + "'com.stockflow.contracts.OrderLinesReleasedForProduction', '{}', NOW())", stalePublication);
                RawSql.update(db, "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, "
                        + "publication_date, completion_date) VALUES (?, 'com.stockflow.production.internal.service."
                        + "OrderReleaseListener.on(com.stockflow.contracts.OrderLinesReleasedForProduction)', "
                        + "'com.stockflow.contracts.OrderLinesReleasedForProduction', '{}', NOW(), NOW())",
                        donePublication);
                RawSql.update(db, "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, "
                        + "publication_date) VALUES (?, 'com.stockflow.payment.internal.service.OrderPlacedListener."
                        + "on(com.stockflow.contracts.OrderPlaced)', 'com.stockflow.contracts.OrderPlaced', '{}', NOW())",
                        otherPublication);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).contains(MIGRATION);

                // The production schema and the order module's copies of production's facts are gone.
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_namespace WHERE nspname = 'production'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'ordering' AND table_name IN ('approved_sample', 'order_line_production')")).isZero();

                // Orders still printed for move on, through the transition table's own edge, with a history row.
                assertThat(RawSql.text(db, "SELECT status FROM ordering.customer_order WHERE id = ?", printing))
                        .isEqualTo("READY_TO_FULFILL");
                assertThat(RawSql.text(db, "SELECT reason FROM ordering.order_status_history WHERE order_id = ? "
                        + "AND from_status = 'IN_PRODUCTION' AND to_status = 'READY_TO_FULFILL'", printing))
                        .isEqualTo("IN_HOUSE_PRODUCTION_REMOVED");
                assertThat(RawSql.text(db, "SELECT status || ' ' || status_before_hold FROM ordering.customer_order "
                        + "WHERE id = ?", held)).isEqualTo("ON_HOLD READY_TO_FULFILL");
                // A task already open: PICKING, never offered for a second task.
                assertThat(RawSql.text(db, "SELECT status FROM ordering.customer_order WHERE id = ?",
                        pickingWhilePrinted)).isEqualTo("PICKING");
                assertThat(RawSql.text(db, "SELECT from_status || ' ' || to_status FROM ordering.order_status_history "
                        + "WHERE order_id = ?", pickingWhilePrinted)).isEqualTo("IN_PRODUCTION PICKING");
                assertThat(RawSql.text(db, "SELECT status FROM ordering.customer_order WHERE id = ?", readyWithTask))
                        .isEqualTo("PICKING");
                assertThat(RawSql.text(db, "SELECT status FROM ordering.customer_order WHERE id = ?", awaiting))
                        .isEqualTo("PARTIALLY_FULFILLED");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'ordering' AND table_name = 'customer_order' AND column_name = 'awaiting_production'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'ordering.customer_order' AND row_id IN (?, ?, ?)", printing, held, awaiting)).isEqualTo(3);
                assertThat(RawSql.text(db, "SELECT row_data ->> 'status' FROM platform.legacy_archive WHERE "
                        + "source_table = 'ordering.customer_order' AND row_id = ?", printing)).isEqualTo("IN_PRODUCTION");

                // The shortfall agreement is archived; the units it closed stay closed.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'ordering' AND table_name = 'order_line' AND column_name LIKE 'shortfall%'")).isZero();
                assertThat(RawSql.number(db, "SELECT cancelled_quantity FROM ordering.order_line WHERE id = ?", shortLine))
                        .isEqualTo(2);
                assertThat(RawSql.text(db, "SELECT row_data ->> 'shortfall_agreed_note' FROM platform.legacy_archive "
                        + "WHERE source_table = 'ordering.order_line.shortfall' AND row_id = ?", shortLine))
                        .isEqualTo("agreed on the phone");

                // Production's hold is archived and gone, the write-off no longer names it, the shelf row is free.
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_reservation WHERE id = ?",
                        productionHold)).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'inventory.stock_reservation' AND row_id = ?", productionHold)).isEqualTo(1);
                assertThat(RawSql.text(db, "SELECT reservation_id FROM inventory.stock_adjustment WHERE id = ?",
                        writeOff)).isNull();
                assertThat(RawSql.text(db, "SELECT row_data ->> 'reservation_id' FROM platform.legacy_archive WHERE "
                        + "source_table = 'inventory.stock_adjustment.reservation' AND row_id = ?", writeOff))
                        .isEqualTo(productionHold.toString());
                assertThat(RawSql.text(db, "SELECT on_hand || ' ' || reserved FROM inventory.stock_item WHERE id = ?",
                        shelfRow)).isEqualTo("10 0");
                // The print line's blanks on the shelf would be picked as plain cups: released, the row free again.
                assertThat(RawSql.text(db, "SELECT status || ' ' || release_reason FROM inventory.stock_reservation "
                        + "WHERE id = ?", blankAllocation)).isEqualTo("RELEASED PRODUCTION_CANCELLED");
                assertThat(RawSql.text(db, "SELECT on_hand || ' ' || reserved FROM inventory.stock_item WHERE id = ?",
                        blankRow)).isEqualTo("8 0");
                // The order's hold on blanks in the print shop is released; the row stays, unsellable, nothing held.
                assertThat(RawSql.text(db, "SELECT status || ' ' || release_reason FROM inventory.stock_reservation "
                        + "WHERE id = ?", issuedHold)).isEqualTo("RELEASED PRODUCTION_CANCELLED");
                assertThat(RawSql.text(db, "SELECT status || ' ' || on_hand || ' ' || reserved FROM inventory.stock_item "
                        + "WHERE id = ?", printShopRow)).isEqualTo("IN_PRODUCTION 6 0");

                // The hold CHECKs come back without production_order_id: a hold has exactly one owner.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'inventory' AND table_name = 'stock_reservation' AND column_name = 'production_order_id'"))
                        .isZero();
                RawSql.refused(db, "ck_stock_reservation_owner", "INSERT INTO inventory.stock_reservation (id, "
                        + "root_request_id, request_id, quantity, reserved_at, status, sku, stock_item_id) VALUES "
                        + "(gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1, NOW(), 'HELD', 'CUP-BLANK', ?)",
                        shelfRow);
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conrelid = "
                        + "'inventory.stock_reservation'::regclass AND conname IN ('ck_stock_reservation_owner', "
                        + "'ck_stock_reservation_kind')")).isEqualTo(2);

                // Functions that read production tables are rewritten and still run.
                RawSql.number(db, "SELECT count(*) FROM inventory.inventory_items WHERE inventory.item_in_use(id, sku)");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = "
                        + "p.pronamespace WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') "
                        + "AND p.prosrc LIKE '%production.%'")).isZero();

                // Permissions and the role: grants removed with the version moved, the role gone, its people signed out.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code LIKE 'production-%'"))
                        .isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.app_role WHERE code = 'PRODUCTION_STAFF'"))
                        .isZero();
                assertThat(RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = 'WAREHOUSE_MANAGER'"))
                        .as("held production grants").isGreaterThan(managerVersion);
                assertThat(RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = 'ACCOUNTANT'"))
                        .as("held none").isEqualTo(accountantVersion);
                assertThat(RawSql.text(db, "SELECT status FROM identity.app_user WHERE id = ?", onlyProduction))
                        .isEqualTo("DISABLED");
                assertThat(RawSql.text(db, "SELECT action || ' ' || to_status FROM identity.user_status_change "
                        + "WHERE user_id = ?", onlyProduction)).isEqualTo("DISABLE DISABLED");
                assertThat(RawSql.text(db, "SELECT revoked_reason FROM identity.user_session WHERE id = ?", onlySession))
                        .isEqualTo("ACCOUNT_DISABLED");
                assertThat(RawSql.text(db, "SELECT status FROM identity.app_user WHERE id = ?", alsoWarehouse))
                        .isEqualTo("ACTIVE");
                assertThat(RawSql.texts(db, "SELECT r.code FROM identity.user_role ur JOIN identity.app_role r "
                        + "ON r.id = ur.role_id WHERE ur.user_id = ?", alsoWarehouse)).containsExactly("WAREHOUSE_STAFF");
                assertThat(RawSql.text(db, "SELECT revoked_reason FROM identity.user_session WHERE id = ?", alsoSession))
                        .isEqualTo("ROLE_CHANGED");
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'identity.user_role' AND row_data ->> 'user_id' IN (?, ?)", onlyProduction.toString(),
                        alsoWarehouse.toString())).isEqualTo(2);

                // Only the publication that can no longer be delivered goes, archived.
                assertThat(RawSql.texts(db, "SELECT id::text FROM event_publication WHERE id IN (?, ?, ?) ORDER BY id",
                        stalePublication, donePublication, otherPublication))
                        .containsExactlyInAnyOrder(donePublication.toString(), otherPublication.toString());
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'public.event_publication' AND row_id = ?", stalePublication)).isEqualTo(1);
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
