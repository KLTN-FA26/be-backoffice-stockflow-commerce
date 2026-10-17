package com.stockflow.production;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static com.stockflow.support.RawSql.number;
import static com.stockflow.support.RawSql.refused;
import static com.stockflow.support.RawSql.text;
import static com.stockflow.support.RawSql.texts;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261017000800 (read grants for the Sprint 2 screens) and V20261017000810 (the PACKING area a completed order's
 * goods went to) on a database that already holds completed production orders - one whose hand-over is in the
 * ledger, one whose is not - upgraded from the newest migration before them.
 */
class ScreenGrantsAndHandOverMigrationUpgradeTest {

    private static final String BEFORE = "20261017000720";
    private static final String HCM = "(SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM')";
    private static final String USER = "(SELECT id FROM identity.app_user LIMIT 1)";
    private static int salesOrders;

    @Test
    void upgradeGrantsOnlyTheReadsAndRecordsWhereFinishedGoodsWent() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                UUID ledgered = completedOrder(db, "LSX-UP-PACKED");
                UUID unledgered = completedOrder(db, "LSX-UP-SILENT");
                update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code,"
                        + " to_location_code, quantity, from_status, to_status, reference_type, reference_id, occurred_at)"
                        + " VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-PRD01', 'HCM-PACK01', 10,"
                        + " 'IN_PRODUCTION', 'AVAILABLE', 'PRODUCTION_ORDER', ?, NOW())", ledgered);
                // The blanks' issue to the print shop is a MOVE of the same order too, but not to packing.
                update(db, "INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code,"
                        + " to_location_code, quantity, from_status, to_status, reference_type, reference_id, occurred_at)"
                        + " VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-1-A', 'HCM-PRD01', 10,"
                        + " 'AVAILABLE', 'IN_PRODUCTION', 'PRODUCTION_ORDER', ?, NOW())", unledgered);
                long sales = version(db, "SALES_STAFF");
                long manager = version(db, "WAREHOUSE_MANAGER");
                long qc = version(db, "QC_STAFF");

                var result = flyway(pg, null).migrate();
                assertThat(result.migrations).extracting(m -> m.version)
                        .contains("20261017000800", "20261017000810");

                assertThat(text(db, "SELECT packing_location_code FROM production.production_order WHERE id = ?",
                        ledgered)).isEqualTo("HCM-PACK01");
                assertThat(text(db, "SELECT COALESCE(packing_location_code, 'none') FROM production.production_order"
                        + " WHERE id = ?", unledgered)).as("nothing invented without a ledger line").isEqualTo("none");

                assertThat(grants(db, "SALES_STAFF", "warehouse-warehouses")).containsExactly("READ");
                assertThat(grants(db, "SALES_STAFF", "design-snapshots")).containsExactly("READ");
                assertThat(grants(db, "WAREHOUSE_MANAGER", "procurement-suppliers")).containsExactly("READ");
                assertThat(grants(db, "QC_STAFF", "warehouse-locations")).as("QC reads its order's areas instead")
                        .isEmpty();
                assertThat(version(db, "SALES_STAFF")).isEqualTo(sales + 1);
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(manager + 1);
                assertThat(version(db, "QC_STAFF")).as("no grant, no cache invalidation").isEqualTo(qc);

                // Only a completed ORDER is handed to packing; the area is a real location.
                UUID open = completedOrder(db, "LSX-UP-OPEN");
                update(db, "UPDATE production.production_order SET status = 'QC', completed_at = NULL WHERE id = ?",
                        open);
                refused(db, "a packing area on an order not completed",
                        "UPDATE production.production_order SET packing_location_code = 'HCM-PACK01' WHERE id = ?",
                        open);
                refused(db, "a packing area that is no location",
                        "UPDATE production.production_order SET packing_location_code = 'HCM-NOWHERE' WHERE id = ?",
                        unledgered);
                assertThat(number(db, "SELECT count(*) FROM pg_constraint WHERE convalidated AND conname IN"
                        + " ('ck_production_order_packing_location', 'fk_production_order_packing_location')"))
                        .isEqualTo(2);
            }
        }
    }

    /** A completed in-house ORDER production order of the demo customer, printed in full. */
    private static UUID completedOrder(Connection db, String number) throws Exception {
        UUID draft = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID production = UUID.randomUUID();
        String checksum = "c".repeat(64);
        update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)"
                + " VALUES (?, 'c0000000-0000-4000-8000-000000000001', md5('demo:product:SOFA-3S')::uuid, 'DRAFT',"
                + " NOW(), '{}'::jsonb)", draft);
        update(db, "INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,"
                + " artifact_manifest) VALUES (?, ?, ?, 'https://example.com/p.pdf', NOW(), NOW(), '[]'::jsonb)",
                snapshot, draft, checksum);
        update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,"
                + " currency, placed_at, warehouse_id, released_at, released_by, version, created_at) VALUES (?, ?,"
                + " 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'IN_PRODUCTION', 20000, 'VND', NOW(), "
                + HCM + ", NOW(), " + USER + ", 0, NOW())", order, "SO-20991230-%06d".formatted(++salesOrders));
        update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency,"
                + " design_snapshot_id, design_checksum) VALUES (?, ?, 'SOFA-3S-GREY', 10, 2000, 'VND', ?, ?)",
                line, order, snapshot, checksum);
        update(db, "INSERT INTO production.production_order (id, order_number, type, status, warehouse_id, blank_sku,"
                + " design_snapshot_id, design_checksum, planned_quantity, issued_quantity, printed_quantity,"
                + " good_quantity, sales_order_id, sales_order_line_id, prepress_checked_at, prepress_checked_by,"
                + " completed_at) VALUES (?, ?, 'ORDER', 'COMPLETED', " + HCM + ", 'SOFA-3S-GREY', ?, ?, 10, 10, 10,"
                + " 10, ?, ?, NOW(), " + USER + ", NOW())", production, number, snapshot, checksum, order, line);
        return production;
    }

    private static java.util.List<String> grants(Connection db, String role, String resource) throws Exception {
        return texts(db, "SELECT p.action FROM identity.role_permission rp"
                + " JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id"
                + " WHERE r.code = ? AND p.resource = ? ORDER BY p.action", role, resource);
    }

    private static long version(Connection db, String role) throws Exception {
        return number(db, "SELECT version FROM identity.app_role WHERE code = ?", role);
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
