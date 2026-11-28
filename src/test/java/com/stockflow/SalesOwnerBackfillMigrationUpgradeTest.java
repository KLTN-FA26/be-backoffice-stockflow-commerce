package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20270128000100..000145 on the real upgrade path (sales-owner plan §4 case 28; API contract "Backfill rule"): every
 * customer that existed before gets the owner of its newest sent document - a quote's {@code sent_by} or a contract's
 * {@code COALESCE(sent_by, signed_by)} - ASSIGNED when that account is ACTIVE, NEEDS_ASSIGNMENT with it as the former
 * owner when it is not, and stays UNASSIGNED with no sent document. One history row (BACKFILL) per customer filled.
 * Only a document a SALES_STAFF account sent counts (owner decision D42): a manager's or a portal account's is skipped.
 * The grants of the plan are given and the roles that gained them advanced.
 */
class SalesOwnerBackfillMigrationUpgradeTest {

    private static final String BEFORE = "20270127000500";
    private static final String BACKFILL = "20270128000145";

    private static final UUID ACTIVE_SALES = UUID.randomUUID();
    private static final UUID LOCKED_SALES = UUID.randomUUID();
    private static final UUID CONTRACT_SALES = UUID.randomUUID();
    private static final UUID PORTAL = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();

    private static final UUID NEWEST_IS_CONTRACT = UUID.randomUUID();
    private static final UUID SENDER_LOCKED = UUID.randomUUID();
    private static final UUID NO_DOCUMENT = UUID.randomUUID();
    private static final UUID DRAFT_ONLY = UUID.randomUUID();
    private static final UUID PORTAL_IGNORED = UUID.randomUUID();

    private static final Instant T1 = Instant.parse("2026-11-01T03:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-10T03:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-20T03:00:00Z");

    @Test
    @DisplayName("Case 28: owners are filled from the newest sent document; inactive senders leave NEEDS_ASSIGNMENT")
    void ownersAreFilledFromTheNewestSentDocument() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                long managerVersion = RawSql.number(db,
                        "SELECT version FROM identity.app_role WHERE code = 'SALES_MANAGER'");

                var result = flyway(postgres, BACKFILL).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly("20270128000100",
                        "20270128000110", "20270128000120", "20270128000130", "20270128000140", BACKFILL);

                assertThat(owner(db, NEWEST_IS_CONTRACT))
                        .as("the contract sent after the quote wins; D42: the manager's newer quote does not count")
                        .isEqualTo(CONTRACT_SALES + ":ASSIGNED:true");
                assertThat(owner(db, SENDER_LOCKED)).as("a locked sender is kept as the former owner")
                        .isEqualTo(LOCKED_SALES + ":NEEDS_ASSIGNMENT:true");
                assertThat(owner(db, NO_DOCUMENT)).isEqualTo("null:UNASSIGNED:false");
                assertThat(owner(db, DRAFT_ONLY)).as("a draft was never sent").isEqualTo("null:UNASSIGNED:false");
                assertThat(owner(db, PORTAL_IGNORED)).as("a portal account never owns; the older quote counts")
                        .isEqualTo(ACTIVE_SALES + ":ASSIGNED:true");

                assertThat(RawSql.texts(db, """
                        SELECT customer_id || '|' || coalesce(from_user_id::text, '-') || '>'
                               || coalesce(to_user_id::text, '-') || ':' || to_state || ':' || reason || ':'
                               || coalesce(changed_by::text, 'system')
                          FROM customer.customer_sales_owner_history ORDER BY 1"""))
                        .as("one BACKFILL row per customer filled, none for the others")
                        .containsExactlyInAnyOrder(
                                NEWEST_IS_CONTRACT + "|->" + CONTRACT_SALES + ":ASSIGNED:BACKFILL:system",
                                SENDER_LOCKED + "|" + LOCKED_SALES + ">-:NEEDS_ASSIGNMENT:BACKFILL:system",
                                PORTAL_IGNORED + "|->" + ACTIVE_SALES + ":ASSIGNED:BACKFILL:system");

                assertThat(RawSql.number(db, """
                        SELECT count(*) FROM identity.role_permission rp
                          JOIN identity.app_role r ON r.id = rp.role_id
                          JOIN identity.permission p ON p.id = rp.permission_id
                         WHERE r.code = 'SALES_MANAGER' AND p.code IN ('customer-sales-owners:UPDATE',
                               'sales-owner-change-requests:APPROVE', 'sales-workload:READ')""")).isEqualTo(3);
                assertThat(RawSql.number(db, """
                        SELECT count(*) FROM identity.role_permission rp
                          JOIN identity.app_role r ON r.id = rp.role_id
                          JOIN identity.permission p ON p.id = rp.permission_id
                         WHERE r.code = 'SALES_STAFF' AND p.code = 'customer-sales-owners:UPDATE'"""))
                        .as("BR-S01: a salesperson never changes owners").isZero();
                assertThat(RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = 'SALES_MANAGER'"))
                        .as("ADR-0008: the role that gained grants is advanced").isGreaterThan(managerVersion);

                RawSql.refusedBy(db, "append-only", "the history cannot be edited",
                        "UPDATE customer.customer_sales_owner_history SET reason = 'OTHER', note = 'x'");
                RawSql.refusedBy(db, "must be an active salesperson", "a locked account cannot be assigned",
                        "UPDATE customer.customer SET sales_owner_state = 'ASSIGNED' WHERE id = ?", SENDER_LOCKED);
                RawSql.refusedBy(db, "must be an active salesperson", "D42: a Sales Manager is never an owner",
                        "UPDATE customer.customer SET sales_owner_state = 'ASSIGNED', sales_owner_id = ?, "
                                + "sales_owner_since = NOW() WHERE id = ?", MANAGER, NO_DOCUMENT);
            }
        }
    }

    private static String owner(Connection db, UUID customer) throws SQLException {
        return RawSql.text(db, "SELECT coalesce(sales_owner_id::text, 'null') || ':' || sales_owner_state || ':' "
                + "|| (sales_owner_since IS NOT NULL) FROM customer.customer WHERE id = ?", customer);
    }

    private static void seed(Connection db) throws SQLException {
        // Foreign keys and triggers off, as rows written before; CHECKs still hold.
        RawSql.update(db, "SET session_replication_role = replica");
        user(db, ACTIVE_SALES, "ACTIVE", "SALES_STAFF");
        user(db, LOCKED_SALES, "LOCKED", "SALES_STAFF");
        user(db, CONTRACT_SALES, "ACTIVE", "SALES_STAFF");
        user(db, MANAGER, "ACTIVE", "SALES_MANAGER");
        user(db, PORTAL, "ACTIVE", "CUSTOMER");
        for (UUID customer : new UUID[] {NEWEST_IS_CONTRACT, SENDER_LOCKED, NO_DOCUMENT, DRAFT_ONLY, PORTAL_IGNORED}) {
            RawSql.update(db, "INSERT INTO customer.customer (id, email, full_name, status, created_at) VALUES "
                    + "(?, ?, 'Upgrade customer', 'ACTIVE', NOW())", customer, "upg-" + customer + "@example.test");
        }
        sentQuote(db, NEWEST_IS_CONTRACT, ACTIVE_SALES, T1);
        sentContract(db, NEWEST_IS_CONTRACT, CONTRACT_SALES, T2);
        sentQuote(db, NEWEST_IS_CONTRACT, MANAGER, T3);
        sentQuote(db, SENDER_LOCKED, LOCKED_SALES, T1);
        RawSql.update(db, "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until, "
                + "created_at) VALUES (gen_random_uuid(), ?, gen_random_uuid(), ?, 'DRAFT', NOW() + interval '30 days', "
                + "NOW())", "QD-" + DRAFT_ONLY.toString().substring(0, 8), DRAFT_ONLY);
        sentQuote(db, PORTAL_IGNORED, ACTIVE_SALES, T1);
        sentQuote(db, PORTAL_IGNORED, PORTAL, T3);
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static void user(Connection db, UUID id, String status, String role) throws SQLException {
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, status, version, created_at) VALUES "
                + "(?, ?, ?, ?, 0, NOW())", id, "upg-" + id, "upg-" + id + "@example.test", status);
        RawSql.update(db, "INSERT INTO identity.user_role (id, user_id, role_id, version, created_at) SELECT "
                + "gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?", id, role);
    }

    private static void sentQuote(Connection db, UUID customer, UUID sender, Instant at) throws SQLException {
        RawSql.update(db, "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until, "
                + "sent_at, sent_by, created_at) VALUES (gen_random_uuid(), ?, gen_random_uuid(), ?, 'SENT', "
                + "NOW() + interval '30 days', ?, ?, NOW())", "Q-" + UUID.randomUUID().toString().substring(0, 12),
                customer, Timestamp.from(at), sender);
    }

    private static void sentContract(Connection db, UUID customer, UUID sender, Instant at) throws SQLException {
        UUID order = UUID.randomUUID();
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, request_id, customer_id, status, "
                + "total_amount, currency, placed_at, created_at) VALUES (?, ?, gen_random_uuid(), ?, 'DRAFT', 0, "
                + "'VND', NOW(), NOW())", order, "SO-" + order.toString().substring(0, 12), customer);
        RawSql.update(db, "INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, "
                + "seller_tax_code, seller_address, seller_representative, seller_representative_title, buyer_name, "
                + "buyer_address, buyer_representative, buyer_representative_title, free_sample_rounds, sent_at, "
                + "sent_by, sent_document_key) VALUES (gen_random_uuid(), ?, ?, 'SENT', 'Seller', '0312345678', "
                + "'Addr', 'Rep', 'Director', 'Buyer', 'Addr', 'Rep', 'Director', 0, ?, ?, 'contracts/k.pdf')",
                "HD-" + order.toString().substring(0, 12), order, Timestamp.from(at), sender);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
