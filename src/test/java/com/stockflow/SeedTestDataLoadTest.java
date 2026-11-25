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
 * <p>A fresh container per test, for the two places the seed is loaded: a database migrated with {@code db/migration}
 * only (a deployed environment), loaded without the Ahamove key, and one with {@code db/demo} as well (the
 * {@code local} profile), loaded with the key sealed the way {@code seal_ahamove_key.py} seals it. Nothing turns a trigger off in
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
                // Review S-2: a MANUAL carrier has no API traffic; its trips carry typed waybill numbers instead.
                for (String table : List.of("fulfillment.carrier_booking", "fulfillment.carrier_api_call",
                        "fulfillment.carrier_event")) {
                    assertThat(RawSql.number(db, "SELECT count(*) FROM " + table + " WHERE created_by = 'seed'"))
                            .as("%s without the key", table).isZero();
                }
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
                ahamoveTraffic(db);
                // The demo's own rows are still there, next to the seed's.
                assertThat(RawSql.number(db, "SELECT count(*) FROM warehouse.warehouse WHERE id = ?",
                        DemoData.WAREHOUSE_HCM)).isOne();
                stockInWholeCartons(db);
            }
            noOrphans(pg, database);
        }
    }

    /**
     * The WF-05 demo's own locations - {@code db/demo-sepay} and {@code db/demo-wf05}, which the integration tests never
     * load (their jobs would find what these leave waiting) - load on top of {@code db/migration} and {@code db/demo}
     * with every trigger on, leave nothing pointing at nothing, and leave each scene in the state the runbook opens it
     * in (WF-05 demo-run 24/11/2026: the blocked steps).
     */
    @Test
    @DisplayName("the WF-05 demo locations load and leave every seeded scene in its state")
    void wf05DemoScenes() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            pg.copyFileToContainer(MountableFile.forHostPath(ORPHANS), "/tmp/orphans.sql");
            String database = pg.getDatabaseName();
            migrate(pg, database, "classpath:db/migration", "classpath:db/demo", "classpath:db/demo-sepay",
                    "classpath:db/demo-wf05");
            try (Connection db = connect(pg, database)) {
                wf05Scenes(db);
            }
            noOrphans(pg, database);
        }
    }

    private static UUID wf05(String key) {
        return DemoData.demoId("demo:wf05:" + key);
    }

    private static String orderStatus(Connection db, String key) throws Exception {
        return RawSql.text(db, "SELECT status FROM ordering.customer_order WHERE id = ?", wf05("order:" + key));
    }

    private static String invoice(Connection db, String key) throws Exception {
        return RawSql.text(db, "SELECT type || '|' || status || '|' || coalesce(provider, '-') || '|' || attempt || '|' "
                + "|| coalesce(warnings, '-') FROM invoicing.einvoice WHERE id = ?", wf05("einvoice:" + key));
    }

    private static void wf05Scenes(Connection db) throws Exception {
        // D-5 / TH-5: a second member of Sales, same role.
        assertThat(RawSql.texts(db, "SELECT r.code FROM identity.user_role ur JOIN identity.app_role r ON r.id = "
                + "ur.role_id JOIN identity.app_user u ON u.id = ur.user_id WHERE u.username = 'demo.sales2'"))
                .containsExactly("SALES_STAFF");
        // Steps 8, 9, HĐ-2, HĐ-7: drafts the automatic issuing does not take.
        assertThat(invoice(db, "WF5-C:1")).isEqualTo("ORIGINAL|DRAFT|-|0|TAX_RATE_DIFFERS_FROM_CONTRACT");
        assertThat(invoice(db, "WF5-D:1")).isEqualTo("ORIGINAL|DRAFT|SANDBOX|1|-");
        assertThat(invoice(db, "WF5-HD7:1"))
                .isEqualTo("ORIGINAL|DRAFT|-|0|TAX_RATE_DIFFERS_FROM_CONTRACT,TAX_CLASS_UNKNOWN");
        assertThat(RawSql.number(db, "SELECT count(*) FROM invoicing.einvoice_line WHERE einvoice_id = ? AND tax_rate = 10",
                wf05("einvoice:WF5-HD7:1"))).as("the cup without a class at the highest rate").isOne();
        // HĐ-1: one the sandbox keeps "processing", one it never received.
        assertThat(invoice(db, "WF5-HD1B:1")).isEqualTo("ORIGINAL|SUBMITTING|SANDBOX|1|-");
        assertThat(RawSql.text(db, "SELECT (s.ready_at > now() + INTERVAL '1 year')::text FROM invoicing.sandbox_issuance s "
                + "JOIN invoicing.einvoice e ON e.transaction_id = s.transaction_id WHERE e.id = ?",
                wf05("einvoice:WF5-HD1B:1"))).isEqualTo("true");
        assertThat(invoice(db, "WF5-HD1C:1")).isEqualTo("ORIGINAL|SUBMITTING|SANDBOX|1|-");
        assertThat(RawSql.number(db, "SELECT count(*) FROM invoicing.sandbox_issuance s JOIN invoicing.einvoice e ON "
                + "e.transaction_id = s.transaction_id WHERE e.id = ?", wf05("einvoice:WF5-HD1C:1"))).isZero();
        // HĐ-3 step 5, HĐ-5.
        assertThat(invoice(db, "WF5-HD3R:1")).isEqualTo("ORIGINAL|REPLACED|SANDBOX|1|-");
        assertThat(invoice(db, "WF5-HD3R:replacement")).isEqualTo("REPLACEMENT|ISSUED|SANDBOX|1|-");
        assertThat(invoice(db, "WF5-HD5:1")).isEqualTo("ORIGINAL|ISSUED|VIETTEL_SINVOICE|1|-");
        // The provider's and the codes' sequences continue after what was seeded.
        assertThat(RawSql.number(db, "SELECT count(*) FROM invoicing.einvoice e WHERE e.provider = 'SANDBOX' AND "
                + "e.invoice_number > (SELECT last_number FROM invoicing.sandbox_number WHERE symbol = 'C26TSB')")).isZero();
        assertThat(RawSql.number(db, "SELECT count(*) FROM invoicing.einvoice e WHERE split_part(e.code, '-', 3)::int > "
                + "(SELECT d.last_value FROM platform.document_sequence d WHERE d.document_type = 'EINV' AND "
                + "to_char(d.sequence_date, 'YYYYMMDD') = split_part(e.code, '-', 2))")).isZero();
        // Step 11: WF5-E completes on the next run, WF5-E2 closes, WF5-F's contract is liquidated.
        assertThat(orderStatus(db, "WF5-E")).isEqualTo("DELIVERED");
        assertThat(RawSql.number(db, "SELECT count(*) FROM payment.installment WHERE order_id = ? AND status <> 'PAID'",
                wf05("order:WF5-E"))).isZero();
        assertThat(orderStatus(db, "WF5-E2")).isEqualTo("COMPLETED");
        assertThat(RawSql.text(db, "SELECT (completed_at < now() - INTERVAL '30 days')::text FROM ordering.customer_order "
                + "WHERE id = ?", wf05("order:WF5-E2"))).isEqualTo("true");
        assertThat(orderStatus(db, "WF5-F")).isEqualTo("CANCELLED");
        assertThat(RawSql.text(db, "SELECT status FROM ordering.sales_contract WHERE order_id = ?", wf05("order:WF5-F")))
                .isEqualTo("SIGNED");
        assertThat(RawSql.number(db, "SELECT (SELECT sum(refundable_amount) FROM payment.order_settlement WHERE order_id"
                + " = ?) - (SELECT sum(amount) FROM payment.refund_record WHERE order_id = ? AND status = 'RECORDED')",
                wf05("order:WF5-F"), wf05("order:WF5-F"))).as("nothing left to refund").isZero();
        // TT-1: held for credit; TT-2: due yesterday, not announced; TT-5 and TH-1: a batch never released.
        for (String key : List.of("WF5-TT1A", "WF5-TT1B")) {
            assertThat(RawSql.text(db, "SELECT o.status || '|' || o.hold_reason || '|' || c.outcome FROM "
                    + "ordering.customer_order o JOIN ordering.order_credit_check c ON c.order_id = o.id JOIN "
                    + "ordering.order_hold h ON h.order_id = o.id AND h.resolved_at IS NULL WHERE o.id = ?",
                    wf05("order:" + key))).isEqualTo("ON_HOLD|CREDIT|OVERDUE");
        }
        assertThat(RawSql.text(db, "SELECT status || '|' || (due_date = (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - 1)"
                + " || '|' || (overdue_announced_for IS NULL) FROM payment.installment WHERE id = ?",
                wf05("installment:WF5-TT2:2"))).isEqualTo("DUE|true|true");
        assertThat(orderStatus(db, "WF5-TT5")).isEqualTo("PARTIALLY_FULFILLED");
        assertThat(orderStatus(db, "WF5-TH1")).isEqualTo("PARTIALLY_FULFILLED");
        // DĐ-1: refunded more than 30 days ago; A has a delivery not invoiced, B only its return's adjustment.
        for (String key : List.of("WF5-DD1A", "WF5-DD1B")) {
            assertThat(RawSql.text(db, "SELECT status || '|' || (refunded_at < now() - INTERVAL '30 days') FROM "
                    + "ordering.customer_order WHERE id = ?", wf05("order:" + key))).isEqualTo("PARTIALLY_REFUNDED|true");
            assertThat(RawSql.text(db, "SELECT status FROM invoicing.einvoice WHERE id = ?", wf05("einvoice:" + key
                    + ":return"))).isEqualTo("DRAFT");
        }
        assertThat(invoice(db, "WF5-DD1A:2")).isEqualTo("ORIGINAL|DRAFT|SANDBOX|1|-");
        assertThat(invoice(db, "WF5-DD1B:2")).isEqualTo("ORIGINAL|ISSUED|SANDBOX|1|-");
        assertThat(RawSql.text(db, "SELECT status || '|' || return_ownership FROM inventory.stock_package WHERE id = ?",
                wf05("lpn:WF5-DD1A-1:1"))).isEqualTo("IN_STOCK|COMPANY");
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
                Map.entry("DELIVERY_FAILED", 1L), Map.entry("RETURN_REQUESTED", 1L), Map.entry("RETURNED", 1L),
                Map.entry("COMPLETED", 1L), Map.entry("CANCELLED", 2L)));
        // Review S-1: goods back in the warehouse move the order to RETURNED (ReturnRequestService.goodsReceived),
        // with its history line; a return still waiting for its goods leaves the order RETURN_REQUESTED.
        assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.return_request r JOIN ordering.customer_order o ON "
                + "o.id = r.order_id WHERE r.created_by = 'seed' AND r.status IN ('RECEIVED', 'SETTLEMENT_PENDING', "
                + "'RESOLVED')")).as("returns whose goods came back").isPositive();
        assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.return_request r JOIN ordering.customer_order o ON "
                + "o.id = r.order_id WHERE r.created_by = 'seed' AND r.status IN ('RECEIVED', 'SETTLEMENT_PENDING', "
                + "'RESOLVED') AND (o.status <> 'RETURNED' OR NOT EXISTS (SELECT 1 FROM ordering.order_status_history h "
                + "WHERE h.order_id = o.id AND h.to_status = 'RETURNED'))")).as("their orders RETURNED").isZero();
        assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.return_request r JOIN ordering.customer_order o ON "
                + "o.id = r.order_id WHERE r.created_by = 'seed' AND r.status = 'REQUESTED' AND o.status <> "
                + "'RETURN_REQUESTED'")).as("a return waiting for its goods").isZero();
        // PENDING_APPROVAL is history only: no edge leads into it since the approval step went (V20261213000100).
        List<String> reachable = new ArrayList<>(allowedStatuses(db, "ordering.production_order"));
        reachable.remove("PENDING_APPROVAL");
        assertThat(byStatus(db, "ordering.production_order")).as("a production order in every reachable state")
                .containsOnlyKeys(reachable);
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
                + "COALESCE(sum(m.quantity) FILTER (WHERE m.to_location_code IS NOT NULL), 0) - COALESCE(sum(m.quantity) "
                + "FILTER (WHERE m.from_location_code IS NOT NULL), 0) FROM inventory.stock_movement m WHERE "
                + "m.stock_package_id = p.id), 0)")).as("each carton's ledger adds up").isZero();
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

    /** Picks to staging, carton checks, load plans, trips with waybills and their outcomes, failures included. */
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
    }

    /** With the key, Ahamove's bookings, API calls and webhook events, failures included (review S-2). */
    private static void ahamoveTraffic(Connection db) throws Exception {
        outbound(db);
        assertThat(byStatus(db, "fulfillment.carrier_booking")).containsKeys("BOOKED", "FAILED");
        assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.carrier_api_call WHERE created_by = 'seed'"))
                .isPositive();
        assertThat(RawSql.texts(db, "SELECT DISTINCT result FROM fulfillment.carrier_event WHERE created_by = 'seed'"))
                .contains("APPLIED", "INVALID_SIGNATURE", "UNMATCHED", "DUPLICATE");
    }

    private static Map<String, Long> byStatus(Connection db, String table) throws Exception {
        return RawSql.numbersByText(db, "SELECT status, count(*) FROM " + table + " WHERE created_by = 'seed' GROUP BY 1");
    }

    /** The values of the table's {@code status IN (...)} CHECK, as the schema states them. */
    private static List<String> allowedStatuses(Connection db, String table) throws Exception {
        String check = RawSql.text(db, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ?::regclass "
                + "AND contype = 'c' AND pg_get_constraintdef(oid) LIKE 'CHECK (((status)::text = ANY%' ORDER BY conname LIMIT 1",
                table);
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
        assertOrphanReportClean(orphans.getStdout());
    }

    /**
     * {@code tools/db/orphan_check.sql}'s three sections (5-11): every value reference counts 0 orphans, no foreign key
     * is NOT VALID, and no reference-looking column is unreviewed. The NOT VALID CHECKs of section 2 are the schema's
     * own (a CHECK added over history it may not hold for), the same on every database, and not the seed's business.
     */
    static void assertOrphanReportClean(String report) {
        java.util.List<String> lines = report.lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines.stream().filter(line -> line.startsWith("values|")).toList()).as("section 1")
                .isNotEmpty().allMatch(line -> line.endsWith("|0|ok"))
                .as("load estimates are keyed since V20261117000200, not counted")
                .noneMatch(line -> line.contains("load_estimate"));
        assertThat(lines).as("a foreign key left NOT VALID").noneMatch(line -> line.endsWith("|f"));
        assertThat(lines).as("section 3: unreviewed references").noneMatch(line -> line.startsWith("unreviewed|"));
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
