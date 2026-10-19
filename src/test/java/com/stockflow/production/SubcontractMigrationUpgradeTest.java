package com.stockflow.production;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lane A's finishing migrations (V20261017000700 hold from QC, 000710 subcontract shortfall, recall, settlement and
 * PO amendment, 000720 print runs for the activity dashboard) on the real upgrade path: a database with the demo seed and a split production order (an in-house
 * root and its SUBCONTRACTED child) written before them, upgraded. The child becomes a SPLIT, the root stays a root,
 * a hold may now remember QC, an in-house child is allowed only with an origin, and the new constraints are valid.
 */
class SubcontractMigrationUpgradeTest {

    private static final String BEFORE = "20261017000540";
    private static final String LAST = "20261017000720";

    @Test
    void upgradeKeepsExistingChildrenAndOpensTheNewPaths() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID root = UUID.randomUUID();
                UUID child = UUID.randomUUID();
                splitOrder(db, root, child);
                exec(db, "INSERT INTO production.production_order_status_history (id, production_order_id, from_status, "
                        + "to_status, reason, changed_at) VALUES (gen_random_uuid(), '" + root + "', 'PRINTING', 'QC', "
                        + "'Printed 5, scrap 1', NOW() - INTERVAL '2 days')");
                assertThatThrownBy(() -> exec(db, "UPDATE production.production_order SET status = 'ON_HOLD', "
                        + "status_before_hold = 'QC', hold_reason = 'OTHER', prepress_checked_at = NOW(), "
                        + "prepress_checked_by = (SELECT id FROM identity.app_user LIMIT 1) WHERE id = '" + root + "'"))
                        .as("before the upgrade a hold could not remember QC").hasMessageContaining("status_before_hold");

                var result = flyway(postgres, LAST).migrate();
                assertThat(result.migrations).extracting(m -> m.version)
                        .contains("20261017000700", "20261017000710", LAST);
                assertThat(text(db, "SELECT printed_quantity || '/' || scrap_quantity FROM production.production_print_run "
                        + "WHERE production_order_id = '" + root + "'")).as("QA3 backfill from the run's history row")
                        .isEqualTo("5/1");

                assertThat(text(db, "SELECT origin FROM production.production_order WHERE id = '" + child + "'"))
                        .isEqualTo("SPLIT");
                assertThat(text(db, "SELECT COALESCE(origin, 'none') FROM production.production_order WHERE id = '"
                        + root + "'")).isEqualTo("none");
                exec(db, "UPDATE production.production_order SET status = 'ON_HOLD', status_before_hold = 'QC', "
                        + "hold_reason = 'OTHER', prepress_checked_at = NOW(), "
                        + "prepress_checked_by = (SELECT id FROM identity.app_user LIMIT 1) WHERE id = '" + root + "'");
                assertThat(text(db, "SELECT status_before_hold FROM production.production_order WHERE id = '" + root
                        + "'")).isEqualTo("QC");

                // An in-house child of the subcontracted one: allowed with an origin, refused without one.
                assertThatThrownBy(() -> exec(db, inHouseChild(UUID.randomUUID(), child, root, null)))
                        .hasMessageContaining("ck_production_order_origin");
                exec(db, inHouseChild(UUID.randomUUID(), child, root, "'SHORTFALL'"));

                assertThat(scalar(db, "SELECT count(*) FROM pg_constraint WHERE convalidated AND conname IN ("
                        + "'ck_production_order_origin', 'ck_production_order_status_before_hold', "
                        + "'ck_production_subcontract_recall_quantities', 'ck_subcontract_settlement_approval', "
                        + "'ck_subcontract_settlement_amounts', 'ck_subcontract_settlement_quantities')"))
                        .as("the new checks, all validated").isEqualTo(6);
                assertThat(scalar(db, "SELECT count(*) FROM pg_constraint WHERE conname = "
                        + "'ck_production_order_child_subcontracted'")).as("replaced by the origin check").isZero();
            }
        }
    }

    private static void splitOrder(Connection db, UUID root, UUID child) throws Exception {
        UUID draft = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        String checksum = "b".repeat(64);
        String hcm = "(SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM')";
        String user = "(SELECT id FROM identity.app_user LIMIT 1)";
        exec(db, "INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts) "
                + "VALUES ('" + draft + "', 'c0000000-0000-4000-8000-000000000001', "
                + "md5('demo:product:SOFA-3S')::uuid, 'DRAFT', NOW(), '{}'::jsonb)");
        exec(db, "INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, "
                + "artifact_manifest) VALUES ('" + snapshot + "', '" + draft + "', '" + checksum
                + "', 'https://example.com/p.pdf', NOW(), NOW(), '[]'::jsonb)");
        exec(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, "
                + "currency, placed_at, warehouse_id, released_at, released_by, version, created_at) VALUES ('" + order
                + "', 'SO-20991231-000001', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'IN_PRODUCTION', "
                + "2000000, 'VND', NOW(), " + hcm + ", NOW(), " + user + ", 0, NOW())");
        exec(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id, "
                + "design_checksum) VALUES ('" + line + "', '" + order + "', 'SOFA-3S-GREY', 1000, 2000, 'VND', '"
                + snapshot + "', '" + checksum + "')");
        exec(db, "INSERT INTO production.production_order (id, order_number, type, status, warehouse_id, blank_sku, "
                + "design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id) VALUES ('"
                + root + "', 'LSX-UP-ROOT', 'ORDER', 'PENDING_PREPRESS', " + hcm + ", 'SOFA-3S-GREY', '" + snapshot
                + "', '" + checksum + "', 600, '" + order + "', '" + line + "')");
        exec(db, "INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id, blank_sku, "
                + "design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id, parent_id, "
                + "subcontract_method, subcontractor_id, subcontract_unit_price, subcontract_currency) VALUES ('" + child
                + "', 'LSX-UP-CHILD', 'ORDER', 'SUBCONTRACTED', 'PENDING_PREPRESS', " + hcm + ", 'SOFA-3S-GREY', '"
                + snapshot + "', '" + checksum + "', 400, '" + order + "', '" + line + "', '" + root
                + "', 'SUPPLIED_BLANKS', (SELECT id FROM procurement.suppliers WHERE code = 'INNHANH'), 800, 'VND')");
    }

    private static String inHouseChild(UUID id, UUID parent, UUID root, String origin) {
        return "INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id, blank_sku, "
                + "design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id, parent_id, "
                + "origin) SELECT '" + id + "', 'LSX-UP-" + id.toString().substring(24) + "', 'ORDER', 'IN_HOUSE', "
                + "'PENDING_PREPRESS', warehouse_id, blank_sku, design_snapshot_id, design_checksum, 10, sales_order_id, "
                + "sales_order_line_id, '" + parent + "', " + origin + " FROM production.production_order WHERE id = '"
                + root + "'";
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo")
                .target(target)
                .load();
    }

    private static String text(Connection db, String sql) throws Exception {
        return RawSql.text(db, sql);
    }

    private static long scalar(Connection db, String sql) throws Exception {
        return RawSql.number(db, sql);
    }

    private static void exec(Connection db, String sql) throws Exception {
        RawSql.update(db, sql);
    }
}
