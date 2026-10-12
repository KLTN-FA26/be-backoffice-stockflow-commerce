package com.stockflow.order;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261014000300 on the real upgrade path: a database at V20261014000200 (the production foundation)
 * holding rows written before — a customer, a user and a grant an administrator added — then upgraded.
 * Proves the quote tables and their constraints, the grants and the role-version moves (ADR-0008), the
 * validated foreign key from sample requests, and that nothing written before is touched.
 */
class QuoteMigrationUpgradeTest {

    private static final String BEFORE = "20261014000200";
    private static final String MIGRATION = "20261014000300";

    @Test
    void upgradeAddsQuotesGrantsAndTheSampleLink() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID customer = UUID.randomUUID();
                exec(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                        + "VALUES ('" + customer + "', 'Old customer', 'old@example.com', 'ACTIVE', 0, NOW())");
                exec(db, "INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by) "
                        + "SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'admin' FROM identity.app_role r "
                        + "JOIN identity.permission p ON p.code = 'sales-orders:EXPORT' WHERE r.code = 'SALES_STAFF' "
                        + "ON CONFLICT DO NOTHING");
                Map<String, Long> versionsBefore = versions(db);
                List<String> salesBefore = grants(db, "SALES_STAFF");

                var result = flyway(postgres, null).migrate();
                assertThat(result.migrations).extracting(m -> m.version).contains(MIGRATION);

                assertThat(grants(db, "SALES_STAFF")).containsAll(salesBefore)
                        .contains("sales-quotes:CREATE", "sales-quotes:UPDATE")
                        .doesNotContain("sales-quotes:APPROVE");
                assertThat(grants(db, "CUSTOMER")).filteredOn(g -> g.startsWith("sales-quotes:"))
                        .containsExactlyInAnyOrder("sales-quotes:VIEW_PAGE", "sales-quotes:READ", "sales-quotes:APPROVE");
                assertThat(grants(db, "ORDER_COORDINATOR")).filteredOn(g -> g.startsWith("sales-quotes:"))
                        .containsExactlyInAnyOrder("sales-quotes:VIEW_PAGE", "sales-quotes:READ", "sales-quotes:UPDATE");
                Map<String, Long> versionsAfter = versions(db);
                for (String role : versionsBefore.keySet()) {
                    boolean changed = List.of("SALES_STAFF", "ORDER_COORDINATOR", "CUSTOMER", "SYSTEM_ADMIN").contains(role);
                    assertThat(versionsAfter.get(role)).as("version of " + role)
                            .isEqualTo(versionsBefore.get(role) + (changed ? 1 : 0));
                }

                assertThat(scalar(db, "SELECT count(*) FROM pg_constraint WHERE conname = 'fk_sample_request_quote' "
                        + "AND convalidated")).as("no sample named a quote before: validated at once").isEqualTo(1);
                assertThat(scalar(db, "SELECT count(*) FROM customer.customer WHERE id = '" + customer + "'")).isEqualTo(1);

                // The table refuses what the aggregate refuses.
                UUID user = UUID.randomUUID();
                exec(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at) "
                        + "VALUES ('" + user + "', 'sales-1', 'sales-1@example.com', '!x', 'ACTIVE', 0, NOW())");
                String quote = "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, payment_term, "
                        + "deposit_percent, valid_until, sent_at, sent_by) VALUES (gen_random_uuid(), '%s', gen_random_uuid(), '"
                        + customer + "', '%s', '%s', %s, NOW() + interval '7 days', %s, %s)";
                exec(db, quote.formatted("QT-OK-1", "DRAFT", "PREPAID", "NULL", "NULL", "NULL"));
                exec(db, quote.formatted("QT-OK-2", "SENT", "DEPOSIT", "30", "NOW()", "'" + user + "'"));
                assertThatThrownBy(() -> exec(db, quote.formatted("QT-BAD-1", "SENT", "PREPAID", "NULL", "NULL", "NULL")))
                        .as("sent without sent_at").hasMessageContaining("ck_quote_sent");
                assertThatThrownBy(() -> exec(db, quote.formatted("QT-BAD-2", "DRAFT", "PREPAID", "30", "NULL", "NULL")))
                        .as("deposit share on a prepaid quote").hasMessageContaining("ck_quote_deposit");
                assertThatThrownBy(() -> exec(db, quote.formatted("QT-BAD-3", "CONVERTED", "PREPAID", "NULL", "NOW()",
                        "'" + user + "'"))).as("converted without an order").hasMessageContaining("ck_quote");
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static List<String> grants(Connection db, String role) throws Exception {
        List<String> codes = new ArrayList<>();
        try (ResultSet rows = db.createStatement().executeQuery("SELECT p.code FROM identity.role_permission rp "
                + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id "
                + "WHERE r.code = '" + role + "'")) {
            while (rows.next()) {
                codes.add(rows.getString(1));
            }
        }
        return codes;
    }

    private static Map<String, Long> versions(Connection db) throws Exception {
        Map<String, Long> versions = new HashMap<>();
        try (ResultSet rows = db.createStatement().executeQuery("SELECT code, version FROM identity.app_role")) {
            while (rows.next()) {
                versions.put(rows.getString(1), rows.getLong(2));
            }
        }
        return versions;
    }

    private static long scalar(Connection db, String sql) throws Exception {
        try (ResultSet rows = db.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void exec(Connection db, String sql) throws Exception {
        db.createStatement().execute(sql);
    }
}
