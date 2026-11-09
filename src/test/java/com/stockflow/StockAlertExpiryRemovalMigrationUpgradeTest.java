package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261111000300 on the real upgrade path (team plan 5-1; kltn-docs 20 BR-13, 03 BR-06, 01 BR-17): stock alerts,
 * their queue and the triggers that fed it go; the thresholds and expiry settings of an item, and the expiry dates of
 * stock, lots and receipt lines, go - each archived first in {@code platform.legacy_archive}; the alert permission
 * goes from every role, whose version moves; the pending publications of the two events only the alerts used are
 * archived and cleared, while other publications stay. Lots of one receipt told apart by expiry alone are merged into
 * the oldest, their stock rows and ledger lines repointed. Stock already past its expiry date, and held by nobody, is
 * marked EXPIRED with its ledger line, so it does not turn sellable once nothing sweeps it. Afterwards nothing becomes EXPIRED, stock or adjustment, while what was EXPIRED stays readable.
 */
class StockAlertExpiryRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261111000200";
    private static final String MIGRATION = "20261111000300";

    private static final String SOFA = "SOFA-3S-GREY";
    private static final String TABLE = "TABLE-OAK-160";
    private static final String ALERT = "00000000-0000-4000-8000-0000000e5101";
    private static final String ROW = "00000000-0000-4000-8000-0000000e5201";
    private static final String RECEIPT = "00000000-0000-4000-8000-0000000e5301";
    private static final String RECEIPT_LINE = "00000000-0000-4000-8000-0000000e5302";
    private static final String LOT_A = "00000000-0000-4000-8000-0000000e5401";
    private static final String LOT_B = "00000000-0000-4000-8000-0000000e5402";
    private static final String PUB_ALERT = "00000000-0000-4000-8000-0000000e5501";
    private static final String PUB_CONTROL = "00000000-0000-4000-8000-0000000e5502";
    private static final String PUB_OTHER = "00000000-0000-4000-8000-0000000e5503";
    private static final String OLD_EXPIRED = "00000000-0000-4000-8000-0000000e5601";
    private static final String ROW_B = "00000000-0000-4000-8000-0000000e5202";
    private static final String LINE_B = "00000000-0000-4000-8000-0000000e5701";
    private static final String PAST_DATE = "00000000-0000-4000-8000-0000000e5801";
    private static final String PAST_DATE_HELD = "00000000-0000-4000-8000-0000000e5802";

    @Test
    void alertsAndExpiryGoArchivedAndTheAlertPermissionWithThem() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                long managerVersion = RawSql.number(db,
                        "SELECT version FROM identity.app_role WHERE code = 'WAREHOUSE_MANAGER'");
                long accountantVersion = RawSql.number(db,
                        "SELECT version FROM identity.app_role WHERE code = 'ACCOUNTANT'");

                assertThat(flyway(postgres, MIGRATION).migrate().migrations).extracting(m -> m.version)
                        .containsExactly(MIGRATION);

                // Gone.
                assertThat(RawSql.text(db, "SELECT to_regclass('inventory.stock_alert')::text")).isNull();
                assertThat(RawSql.text(db, "SELECT to_regclass('inventory.stock_alert_dirty')::text")).isNull();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'trg_stock_item_dirty%'"))
                        .isZero();
                assertThat(RawSql.number(db, """
                        SELECT count(*) FROM information_schema.columns
                         WHERE column_name IN ('expiry_date', 'expiry_tracked', 'max_shelf_life_days', 'reorder_point',
                                               'safety_stock', 'max_qty')
                           AND table_schema IN ('inventory', 'procurement')
                           AND table_name IN ('inventory_items', 'stock_item', 'stock_lot', 'goods_receipt_lines',
                                              'stock_package_balance')""")).isZero();

                // Archived first.
                assertThat(RawSql.text(db, "SELECT row_data->>'kind' FROM platform.legacy_archive"
                        + " WHERE source_table = 'inventory.stock_alert' AND row_id = ?::uuid", ALERT)).isEqualTo("REORDER");
                assertThat(RawSql.text(db, "SELECT row_data->>'sku' FROM platform.legacy_archive"
                        + " WHERE source_table = 'inventory.stock_alert_dirty'")).isEqualTo(SOFA);
                assertThat(RawSql.text(db, """
                        SELECT (row_data->>'reorder_point') || '/' || (row_data->>'safety_stock') || '/'
                               || (row_data->>'max_qty')
                          FROM platform.legacy_archive
                         WHERE source_table = 'inventory.inventory_items' AND row_data->>'sku' = ?""", SOFA))
                        .isEqualTo("12/5/40");
                assertThat(RawSql.text(db, """
                        SELECT (row_data->>'expiry_tracked') || '/' || (row_data->>'max_shelf_life_days')
                          FROM platform.legacy_archive
                         WHERE source_table = 'inventory.inventory_items' AND row_data->>'sku' = ?""", TABLE))
                        .isEqualTo("true/365");
                assertThat(RawSql.text(db, "SELECT row_data->>'expiry_date' FROM platform.legacy_archive"
                        + " WHERE source_table = 'inventory.stock_item' AND row_id = ?::uuid", ROW)).isNotNull();
                assertThat(RawSql.text(db, "SELECT row_data->>'expiry_date' FROM platform.legacy_archive"
                        + " WHERE source_table = 'inventory.stock_lot' AND row_id = ?::uuid", LOT_A)).isEqualTo("2027-03-01");

                // Two lots of one receipt, SKU and number, told apart by expiry alone: one now, the oldest; what
                // pointed at the other points at it, and the other is archived whole.
                assertThat(RawSql.texts(db, "SELECT id::text FROM inventory.stock_lot WHERE goods_receipt_id = ?::uuid",
                        RECEIPT)).containsExactly(LOT_A);
                assertThat(RawSql.text(db, "SELECT lot_id::text FROM inventory.stock_item WHERE id = ?::uuid", ROW_B))
                        .isEqualTo(LOT_A);
                assertThat(RawSql.text(db, "SELECT lot_id::text FROM inventory.stock_movement WHERE id = ?::uuid",
                        LINE_B)).isEqualTo(LOT_A);
                assertThat(RawSql.text(db, "SELECT row_data->>'expiry_date' FROM platform.legacy_archive"
                        + " WHERE source_table = 'inventory.stock_lot' AND row_id = ?::uuid AND row_data->>'origin_type' IS NOT NULL",
                        LOT_B)).isEqualTo("2027-06-01");
                assertThat(RawSql.text(db, "SELECT row_data->>'merged_into' FROM platform.legacy_archive"
                        + " WHERE source_table = 'inventory.stock_movement' AND row_id = ?::uuid", LINE_B))
                        .isEqualTo(LOT_A);
                assertThat(RawSql.text(db, "SELECT row_data->>'expiry_date' FROM platform.legacy_archive"
                        + " WHERE source_table = 'procurement.goods_receipt_lines' AND row_id = ?::uuid", RECEIPT_LINE))
                        .isEqualTo("2030-01-01");

                // The stock row, its lot and the receipt line stay, without the expiry.
                assertThat(RawSql.number(db, "SELECT on_hand FROM inventory.stock_item WHERE id = ?::uuid", ROW))
                        .isEqualTo(3);
                assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_lot WHERE id = ?::uuid", LOT_A))
                        .isOne();
                assertThat(RawSql.text(db, "SELECT lot_number FROM procurement.goods_receipt_lines WHERE id = ?::uuid",
                        RECEIPT_LINE)).isEqualTo("LOT-UPG");

                // Stock past its date at the upgrade: EXPIRED with a STATUS_CHANGE line; held stock is left alone.
                assertThat(RawSql.text(db, "SELECT status FROM inventory.stock_item WHERE id = ?::uuid", PAST_DATE))
                        .isEqualTo("EXPIRED");
                assertThat(RawSql.text(db, """
                        SELECT movement_type || ':' || from_status || '>' || to_status || ':' || quantity
                          FROM inventory.stock_movement WHERE reference_type = 'EXPIRY_SWEEP' AND reference_id = ?::uuid""",
                        PAST_DATE)).isEqualTo("STATUS_CHANGE:AVAILABLE>EXPIRED:4");
                assertThat(RawSql.text(db, "SELECT status FROM inventory.stock_item WHERE id = ?::uuid", PAST_DATE_HELD))
                        .isEqualTo("AVAILABLE");
                assertThat(RawSql.text(db, "SELECT status FROM inventory.stock_item WHERE id = ?::uuid", ROW))
                        .as("not yet past its date").isEqualTo("AVAILABLE");

                // The permission, and every holder's version moved; a role that never held it keeps its version.
                assertThat(RawSql.number(db,
                        "SELECT count(*) FROM identity.permission WHERE resource = 'inventory-alerts'")).isZero();
                assertThat(RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = 'WAREHOUSE_MANAGER'"))
                        .isEqualTo(managerVersion + 1);
                assertThat(RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = 'ACCOUNTANT'"))
                        .isEqualTo(accountantVersion);

                // Publications: the alerts' ones gone (the pending one archived), any other kept.
                assertThat(RawSql.texts(db, "SELECT id::text FROM event_publication WHERE id IN (?::uuid, ?::uuid, ?::uuid)",
                        PUB_ALERT, PUB_CONTROL, PUB_OTHER)).containsExactly(PUB_OTHER);
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive"
                        + " WHERE source_table = 'public.event_publication' AND row_id = ?::uuid", PUB_ALERT)).isOne();

                // EXPIRED is read only: an old EXPIRED row is still written off; nothing new becomes EXPIRED.
                RawSql.update(db, "UPDATE inventory.stock_item SET on_hand = 0 WHERE id = ?::uuid", OLD_EXPIRED);
                RawSql.refused(db, "a stock row turned EXPIRED",
                        "UPDATE inventory.stock_item SET status = 'EXPIRED' WHERE id = ?::uuid", ROW);
                RawSql.refused(db, "an adjustment for EXPIRED stock", """
                        INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta,
                                                                reason_code, requested_by)
                        VALUES (gen_random_uuid(), 'ADJ-UPG-EXP', 'HCM-A01-1-A', ?, -1, 'EXPIRED',
                                md5('demo:user:editor')::uuid)""", TABLE);
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        // Thresholds on the sofa, expiry tracking on the oak table (lot-tracked and FEFO in the demo data).
        RawSql.update(db, "UPDATE inventory.inventory_items SET reorder_point = 12, safety_stock = 5, max_qty = 40"
                + " WHERE sku = ?", SOFA);
        RawSql.update(db, "UPDATE inventory.inventory_items SET lot_tracked = TRUE, removal_strategy = 'FEFO',"
                + " expiry_tracked = TRUE, max_shelf_life_days = 365 WHERE sku = ?", TABLE);
        RawSql.update(db, "INSERT INTO inventory.stock_alert (id, kind, sku, observed_qty, threshold_qty, opened_at,"
                + " last_observed_at) VALUES (?::uuid, 'REORDER', ?, 3, 12, NOW(), NOW())", ALERT, SOFA);
        RawSql.update(db, "DELETE FROM inventory.stock_alert_dirty");
        RawSql.update(db, "INSERT INTO inventory.stock_alert_dirty (sku) VALUES (?)", SOFA);
        // A loose row of the table with an expiry date (its UNTRACED lot carries the date too).
        RawSql.update(db, """
                INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved,
                                                  status, created_at, received_at)
                VALUES (?::uuid, ?, 'HCM-A01-1-A', 'LOT-UPG-S', CURRENT_DATE + 30, 3, 0, 'AVAILABLE', NOW(),
                        NOW() - interval '1 day')""", ROW, TABLE);
        // Two loose sofa rows already past their date at the upgrade, one of them held; the sofa's policy is off for the
        // insert, which would refuse an expiry date on a SKU that is not expiry-tracked.
        RawSql.update(db, "UPDATE inventory.inventory_items SET policy_configured = FALSE WHERE sku = ?", SOFA);
        RawSql.update(db, """
                INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved,
                                                  status, created_at, received_at)
                VALUES (?::uuid, ?, 'HCM-A02-1-A', 'LOT-OLD', CURRENT_DATE - 1, 4, 0, 'AVAILABLE', NOW(),
                        NOW() - interval '400 days'),
                       (?::uuid, ?, 'HCM-A02-1-B', 'LOT-OLD', CURRENT_DATE - 1, 3, 3, 'AVAILABLE', NOW(),
                        NOW() - interval '400 days')""", PAST_DATE, SOFA, PAST_DATE_HELD, SOFA);
        RawSql.update(db, "UPDATE inventory.inventory_items SET policy_configured = TRUE WHERE sku = ?", SOFA);
        // A row the old expiry sweep left EXPIRED.
        RawSql.update(db, """
                INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at,
                                                  received_at)
                VALUES (?::uuid, ?, 'HCM-QC01', 2, 0, 'EXPIRED', NOW(), NOW() - interval '400 days')""",
                OLD_EXPIRED, SOFA);
        // A receipt without a PO, its line with an expiry date, and two of its lots of one number told apart by expiry.
        RawSql.update(db, """
                INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, received_at, received_by,
                                                        supplier_id, blind_reason)
                SELECT ?::uuid, 'GR-UPG-51', md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid, s.id, 'upgrade'
                  FROM procurement.suppliers s ORDER BY s.id LIMIT 1""", RECEIPT);
        RawSql.update(db, """
                INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty,
                                                             lot_number, expiry_date)
                VALUES (?::uuid, ?::uuid, md5('demo:item:TABLE-OAK-160')::uuid, md5('demo:loc:HCM-RCV01')::uuid, 2,
                        'LOT-UPG', DATE '2030-01-01')""", RECEIPT_LINE, RECEIPT);
        RawSql.update(db, """
                INSERT INTO inventory.stock_lot (id, sku, lot_number, expiry_date, received_at, origin_type,
                                                 goods_receipt_id)
                VALUES (?::uuid, ?, 'LOT-UPG', DATE '2027-03-01', NOW() - interval '1 hour', 'GOODS_RECEIPT', ?::uuid),
                       (?::uuid, ?, 'LOT-UPG', DATE '2027-06-01', NOW(), 'GOODS_RECEIPT', ?::uuid)""",
                LOT_A, TABLE, RECEIPT, LOT_B, TABLE, RECEIPT);
        // Stock of the second lot, and a ledger line naming it: both must follow the merge.
        RawSql.update(db, """
                INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, lot_id, on_hand,
                                                  reserved, status, created_at, received_at)
                VALUES (?::uuid, ?, 'HCM-A01-1-B', 'LOT-UPG', DATE '2027-06-01', ?::uuid, 2, 0, 'AVAILABLE', NOW(),
                        NOW() - interval '1 day')""", ROW_B, TABLE, LOT_B);
        RawSql.update(db, """
                INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, to_location_code, quantity,
                                                      reference_type, reference_id, occurred_at, version, created_at,
                                                      lot_id)
                VALUES (?::uuid, 'ADJUSTMENT', ?, 'LOT-UPG', 'HCM-A01-1-B', 2, 'STOCK_ADJUSTMENT', gen_random_uuid(),
                        NOW(), 0, NOW(), ?::uuid)""", LINE_B, TABLE, LOT_B);
        // Publications: one pending of the alerts' event, one done of the control-change event, one of something else.
        RawSql.update(db, """
                INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,
                                               completion_date)
                VALUES (?::uuid, 'com.stockflow.notification.internal.service.InventoryAlertNotificationListener.on',
                        'com.stockflow.contracts.InventoryAlertsRaised', '{}', NOW(), NULL),
                       (?::uuid, 'com.stockflow.inventory.internal.service.StockAlertEventListener.on',
                        'com.stockflow.contracts.SkuInventoryControlChanged', '{}', NOW(), NOW()),
                       (?::uuid, 'com.stockflow.order.internal.service.PaymentEventListener.on',
                        'com.stockflow.contracts.PaymentCaptured', '{}', NOW(), NULL)""",
                PUB_ALERT, PUB_CONTROL, PUB_OTHER);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo")
                .target(target)
                .load();
    }
}
