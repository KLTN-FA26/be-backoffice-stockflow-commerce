package com.stockflow;

import com.stockflow.common.security.SecretCipher;
import com.stockflow.common.security.SecretCipherProperties;
import com.stockflow.support.DemoData;
import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tools/db/seed/seed_test_data.sql} (team plan 5-10) loaded the way its header says - {@code psql -v
 * ON_ERROR_STOP=1 -f} on a freshly migrated database - and what it claims to describe checked on the result.
 *
 * <p>Two databases on one server, the two places the seed is loaded: one migrated with {@code db/migration} only (a
 * deployed environment), loaded without the Ahamove key, and one with {@code db/demo} as well (the {@code local}
 * profile), loaded with the key sealed the way {@code seal_ahamove_key.py} seals it. Nothing turns a trigger off in
 * the seed, so loading at all already proves every row passes the deferred constraint triggers; the checks below are
 * the facts the demo walks through: every state of the documents, stock only in whole LPN cartons whose ledger adds
 * up, lots that are production orders, returned cartons in quarantine, and no reference to nothing.</p>
 *
 * <p>Exact counts are the generator's (it is deterministic): a regenerated seed that changes them changes this test
 * with it, on purpose.</p>
 */
class SeedTestDataLoadTest {

    private static final Path SEED = Path.of("tools/db/seed/seed_test_data.sql");
    private static final Path ORPHANS = Path.of("tools/db/orphan_check.sql");
    private static final UUID AHAMOVE = DemoData.demoId("seed:carrier:AHAMOVE");

    @Test
    @DisplayName("the seed loads on a database with db/migration only, holds its facts, and refuses a second load")
    void loadsOnAMigratedDatabase() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            pg.copyFileToContainer(MountableFile.forHostPath(SEED), "/tmp/seed.sql");
            pg.copyFileToContainer(MountableFile.forHostPath(ORPHANS), "/tmp/orphans.sql");
            String database = pg.getDatabaseName();
            migrate(pg, database, "classpath:db/migration");

            ExecResult load = psql(pg, database, List.of());
            assertThat(load.getExitCode()).withFailMessage(load.getStderr()).isZero();
            assertThat(load.getStderr()).doesNotContain("ERROR");

            try (Connection db = connect(pg, database)) {
                staffAndPlaces(db);
                documentsInEveryState(db);
                stockInWholeCartons(db);
                outbound(db);
                assertThat(RawSql.text(db, "SELECT connection_type || '|' || (api_key_sealed IS NULL) FROM "
                        + "fulfillment.carrier WHERE id = ?", AHAMOVE)).as("Ahamove without a key").isEqualTo("MANUAL|true");
            }
            noOrphans(pg, database);

            ExecResult again = psql(pg, database, List.of());
            assertThat(again.getExitCode()).as("a second load").isNotZero();
            assertThat(again.getStderr()).contains("seed_test_data.sql has already been loaded into this database");
        }
    }

    @Test
    @DisplayName("next to db/demo, the seed makes Ahamove API_BOOKING with a key the application opens")
    void loadsNextToTheDemoWithTheAhamoveKey() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            pg.copyFileToContainer(MountableFile.forHostPath(SEED), "/tmp/seed.sql");
            pg.copyFileToContainer(MountableFile.forHostPath(ORPHANS), "/tmp/orphans.sql");
            String database = pg.getDatabaseName();
            migrate(pg, database, "classpath:db/migration", "classpath:db/demo");

            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            var cipher = new SecretCipher(new SecretCipherProperties(Base64.getEncoder().encodeToString(key)));
            String apiKey = "staging-key-" + UUID.randomUUID();
            String token = "webhook-token-" + UUID.randomUUID();
            ExecResult load = psql(pg, database, List.of(
                    "-v", "ahamove_api_key_sealed=" + cipher.seal(apiKey, "carrier:" + AHAMOVE + ":api-key"),
                    "-v", "ahamove_webhook_auth_sealed=" + cipher.seal(token, "carrier:" + AHAMOVE + ":webhook-auth-value"),
                    "-v", "ahamove_api_account=84909000111"));
            assertThat(load.getExitCode()).withFailMessage(load.getStderr()).isZero();

            try (Connection db = connect(pg, database)) {
                assertThat(RawSql.text(db, "SELECT connection_type || '|' || api_environment || '|' || api_account || '|' "
                        + "|| webhook_enabled || '|' || webhook_auth_header FROM fulfillment.carrier WHERE id = ?", AHAMOVE))
                        .isEqualTo("API_BOOKING|STAGING|84909000111|true|X-Ahamove-Token");
                assertThat(cipher.open(RawSql.text(db, "SELECT api_key_sealed FROM fulfillment.carrier WHERE id = ?",
                        AHAMOVE), "carrier:" + AHAMOVE + ":api-key")).isEqualTo(apiKey);
                assertThat(cipher.open(RawSql.text(db, "SELECT webhook_auth_value_sealed FROM fulfillment.carrier WHERE "
                        + "id = ?", AHAMOVE), "carrier:" + AHAMOVE + ":webhook-auth-value")).isEqualTo(token);
                // The demo's own rows are still there, next to the seed's.
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.warehouse WHERE id = ?",
                        DemoData.WAREHOUSE_HCM)).isOne();
                stockInWholeCartons(db);
            }
            noOrphans(pg, database);
        }
    }

    /** Staff of every role but the system administrator, customers' accounts, and the areas each warehouse works in. */
    private static void staffAndPlaces(Connection db) throws Exception {
        assertThat(RawSql.texts(db, "SELECT DISTINCT r.code FROM identity.user_role ur JOIN identity.app_role r ON r.id = "
                + "ur.role_id JOIN identity.app_user u ON u.id = ur.user_id WHERE u.created_by = 'seed'"))
                .containsExactlyInAnyOrder("ACCOUNTANT", "CUSTOMER", "PRODUCTION_COORDINATOR", "SALES_MANAGER",
                        "SALES_STAFF", "WAREHOUSE_MANAGER", "WAREHOUSE_STAFF");
        assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.warehouse WHERE created_by = 'seed'")).isEqualTo(2);
        assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.warehouse w WHERE w.created_by = 'seed' AND "
                + "(SELECT count(DISTINCT a.type) FROM warehouse.area a WHERE a.warehouse_id = w.id AND a.type IN "
                + "('RECEIVING', 'QUARANTINE', 'STAGING', 'DISPATCH', 'OVERFLOW')) = 5"))
                .as("every warehouse has receiving, quarantine, staging, dispatch and overflow").isEqualTo(2);
        assertThat(RawSql.number(db, "SELECT count(*) FROM product.products WHERE created_by = 'seed' AND kind <> "
                + "'CUSTOMIZABLE'")).as("every product customizable").isZero();
        assertThat(RawSql.number(db, "SELECT count(*) FROM design.design_project WHERE created_by = 'seed'")).isPositive();
    }

    private static void documentsInEveryState(Connection db) throws Exception {
        assertThat(byStatus(db, "ordering.customer_order")).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("DRAFT", 1L), Map.entry("PENDING_CONTRACT", 1L), Map.entry("PENDING_PAYMENT", 1L),
                Map.entry("CONFIRMED", 1L), Map.entry("AWAITING_STOCK", 3L), Map.entry("READY_TO_FULFILL", 1L),
                Map.entry("PICKING", 1L), Map.entry("PACKED", 1L), Map.entry("SHIPPED", 2L), Map.entry("DELIVERED", 1L),
                Map.entry("DELIVERY_FAILED", 1L), Map.entry("RETURN_REQUESTED", 2L), Map.entry("COMPLETED", 1L),
                Map.entry("CANCELLED", 2L)));
        assertThat(byStatus(db, "ordering.production_order")).as("a production order in every state")
                .containsOnlyKeys(allowedStatuses(db, "ordering.production_order"));
        assertThat(byStatus(db, "ordering.production_order")).containsEntry("RECEIVED", 10L);
        assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.production_order_sample_round r JOIN "
                + "ordering.production_order p ON p.id = r.production_order_id WHERE p.created_by = 'seed'"))
                .as("print-sample rounds").isPositive();
        for (String table : List.of("ordering.quote", "ordering.sales_contract", "payment.installment",
                "payment.payment_receipt")) {
            assertThat(byStatus(db, table)).as("%s in every state", table).containsOnlyKeys(allowedStatuses(db, table));
        }
        assertThat(byStatus(db, "ordering.return_request")).containsOnlyKeys("REQUESTED", "RECEIVED", "REJECTED");
    }

    /**
     * 20 BR-18 / BR-22: stock is whole LPN cartons only, one row each; a carton in stock holds its standard count, a
     * consumed one nothing; the ledger of each carton adds up to what it holds; every lot is a production order's
     * code (03 BR-03), and returned cartons wait in a quarantine area.
     */
    private static void stockInWholeCartons(Connection db) throws Exception {
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item WHERE created_by = 'seed' AND "
                + "stock_package_id IS NULL")).as("no loose stock").isZero();
        assertThat(byStatus(db, "inventory.stock_package")).containsOnlyKeys("IN_STOCK", "CONSUMED");
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_package p WHERE p.created_by = 'seed' AND "
                + "(SELECT count(*) FROM inventory.stock_item s WHERE s.stock_package_id = p.id) <> 1"))
                .as("one row per carton").isZero();
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_package p JOIN inventory.stock_item s ON "
                + "s.stock_package_id = p.id WHERE p.created_by = 'seed' AND NOT CASE p.status WHEN 'IN_STOCK' THEN "
                + "s.on_hand = p.received_qty AND p.received_qty = p.nominal_qty ELSE s.on_hand = 0 END"))
                .as("a carton holds its standard count or, consumed, nothing").isZero();
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_package p JOIN inventory.stock_item s ON "
                + "s.stock_package_id = p.id WHERE p.created_by = 'seed' AND s.on_hand <> COALESCE((SELECT "
                + "COALESCE(sum(m.quantity) FILTER (WHERE m.to_stock_package_id = p.id), 0) - COALESCE(sum(m.quantity) "
                + "FILTER (WHERE m.from_stock_package_id = p.id), 0) FROM inventory.stock_movement m WHERE p.id IN "
                + "(m.to_stock_package_id, m.from_stock_package_id)), 0)")).as("each carton's ledger adds up").isZero();
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item s JOIN inventory.stock_lot l ON l.id = "
                + "s.lot_id LEFT JOIN ordering.production_order o ON o.id = l.production_order_id WHERE s.created_by = "
                + "'seed' AND (l.origin_type <> 'PRODUCTION_ORDER' OR s.lot_number IS DISTINCT FROM o.code)"))
                .as("lot = the production order's code").isZero();
        assertThat(RawSql.number(db, "SELECT count(*) FROM procurement.goods_receipts WHERE created_by = 'seed' AND "
                + "receipt_type = 'RETURN' AND status = 'CLOSED'")).as("a return received").isPositive();
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item s JOIN warehouse.storage_location l ON "
                + "l.location_code = s.location_code JOIN warehouse.area a ON a.location_id = l.id WHERE s.created_by = "
                + "'seed' AND s.on_hand > 0 AND s.status = 'QUARANTINE' AND a.type = 'QUARANTINE'"))
                .as("returned cartons in quarantine").isPositive();
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item s WHERE s.created_by = 'seed' AND "
                + "s.status = 'QUARANTINE' AND NOT EXISTS (SELECT 1 FROM warehouse.storage_location l JOIN "
                + "warehouse.area a ON a.location_id = l.id WHERE l.location_code = s.location_code AND a.type = "
                + "'QUARANTINE')")).as("quarantined stock outside a quarantine area").isZero();
    }

    /** Picks to staging, carton checks, load plans, trips with waybills, carrier bookings and webhook events, failures included. */
    private static void outbound(Connection db) throws Exception {
        assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.pick_line l JOIN fulfillment.pick p ON p.id = "
                + "l.pick_id WHERE p.created_by = 'seed' AND l.status = 'STAGED'")).as("lines picked to staging")
                .isPositive();
        assertThat(RawSql.number(db, "SELECT count(*) FROM inventory.stock_item s JOIN warehouse.storage_location l ON "
                + "l.location_code = s.location_code JOIN warehouse.area a ON a.location_id = l.id WHERE s.created_by = "
                + "'seed' AND s.on_hand > 0 AND a.type = 'STAGING'")).as("cartons waiting in staging").isPositive();
        assertThat(byStatus(db, "fulfillment.pack")).containsKey("COMPLETED");
        assertThat(byStatus(db, "fulfillment.load_plan")).containsKeys("CONFIRMED", "DISPATCHED");
        assertThat(byStatus(db, "fulfillment.shipment")).containsKeys("READY_TO_DISPATCH", "HANDED_OVER", "DELIVERED",
                "DELIVERY_FAILED");
        assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.shipment WHERE created_by = 'seed' AND "
                + "tracking_number IS NOT NULL")).as("trips with a waybill number").isPositive();
        assertThat(byStatus(db, "fulfillment.carrier_booking")).containsKeys("BOOKED", "FAILED");
        assertThat(RawSql.texts(db, "SELECT DISTINCT result FROM fulfillment.carrier_event WHERE created_by = 'seed'"))
                .contains("APPLIED", "INVALID_SIGNATURE", "UNMATCHED", "DUPLICATE");
    }

    private static Map<String, Long> byStatus(Connection db, String table) throws Exception {
        return RawSql.numbersByText(db, "SELECT status, count(*) FROM " + table + " WHERE created_by = 'seed' GROUP BY 1");
    }

    /** The values of the table's {@code status IN (...)} CHECK, as the schema states them. */
    private static List<String> allowedStatuses(Connection db, String table) throws Exception {
        String check = RawSql.text(db, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ?::regclass "
                + "AND contype = 'c' AND pg_get_constraintdef(oid) LIKE 'CHECK (((status)::text = ANY%'", table);
        List<String> values = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(check);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        assertThat(values).as("statuses of %s", table).isNotEmpty();
        return values;
    }

    private static void noOrphans(PostgreSQLContainer<?> pg, String database) throws Exception {
        ExecResult orphans = pg.execInContainer("psql", "-U", pg.getUsername(), "-d", database, "-X", "-A", "-t", "-f",
                "/tmp/orphans.sql");
        assertThat(orphans.getExitCode()).withFailMessage(orphans.getStderr()).isZero();
        assertThat(orphans.getStdout().lines().filter(line -> !line.isBlank()).toList())
                .isNotEmpty().allMatch(line -> line.endsWith("|0|ok"));
    }

    private static ExecResult psql(PostgreSQLContainer<?> pg, String database, List<String> variables) throws Exception {
        List<String> command = new ArrayList<>(List.of("psql", "-v", "ON_ERROR_STOP=1", "-X", "-q", "-o", "/dev/null",
                "-U", pg.getUsername(), "-d", database));
        command.addAll(variables);
        command.addAll(List.of("-f", "/tmp/seed.sql"));
        return pg.execInContainer(command.toArray(String[]::new));
    }

    private static void migrate(PostgreSQLContainer<?> pg, String database, String... locations) {
        Flyway.configure().dataSource(url(pg, database), pg.getUsername(), pg.getPassword()).locations(locations).load()
                .migrate();
    }

    private static Connection connect(PostgreSQLContainer<?> pg, String database) throws Exception {
        return DriverManager.getConnection(url(pg, database), pg.getUsername(), pg.getPassword());
    }

    private static String url(PostgreSQLContainer<?> pg, String database) {
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/"
                + database;
    }
}
